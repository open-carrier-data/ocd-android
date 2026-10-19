package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.SystemClock
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.owner.core.ApnColumns
import io.github.open_carrier_data.check.owner.core.CcResult
import io.github.open_carrier_data.check.owner.core.EmergencyApi
import io.github.open_carrier_data.check.owner.core.ImsApi
import io.github.open_carrier_data.check.owner.core.KeyGuard
import io.github.open_carrier_data.check.owner.core.MobileData
import io.github.open_carrier_data.check.owner.core.OwnerArgs
import io.github.open_carrier_data.check.owner.core.OwnerCommand
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.PreferredApn
import io.github.open_carrier_data.check.owner.core.Route
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.retrying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the owner build may do on this phone, and why not (round 25 OB2). */
data class OwnerGate(
    val route: Route?,
    val ownerPhone: Boolean,
    val buildType: String,
    val shizukuRunning: Boolean,
    val shizukuRoot: Boolean,
    val shizukuPermitted: Boolean,
    val privilegedGranted: Boolean,
    val reason: String?,
    /** CarrierConfigManager.overrideConfig(int, PersistableBundle) found (the hidden-API check passed); null when not looked up. */
    val overrideApi: Boolean? = null,
) {
    val canWrite: Boolean get() = route != null && reason == null
}

object OwnerAccess {
    /** The owner's S20 (Exynos, LineageOS build x1s). The A50 and every other phone stay read-only. */
    const val OWNER_DEVICE = "x1s"
    const val OWNER_MODEL = "SM-G981B"

    /** What the privileged route needs; granted only to a priv-app with the shipped allowlist. */
    val PRIVILEGED_PERMISSIONS = listOf(
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.WRITE_APN_SETTINGS",
        "android.permission.DUMP",
        "android.permission.READ_LOGS",
    )

    fun privilegedGranted(context: Context): Boolean {
        val info = context.applicationInfo
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        return system && PRIVILEGED_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * The privileged route's one write API, looked up without calling it: a
     * system app missing from the hidden-API allowlist gets no method here,
     * and the gate falls back to Shizuku instead of failing at the first test.
     */
    fun overrideApiReachable(): Boolean = runCatching {
        CarrierConfigManager::class.java.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)
    }.isSuccess

    fun shizukuState(): Triple<Boolean, Boolean, Boolean> {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return Triple(false, false, false)
        val permitted = runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val root = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)
        return Triple(true, root, permitted)
    }

    /**
     * The write gate: the owner's S20, not Samsung's stock software (Samsung's
     * IMS ignores CarrierConfig), and a route. The privileged route wins when
     * its permissions are granted; else Shizuku as root on a non-user build
     * (`cmd phone cc` refuses on user builds and for any uid but root).
     */
    fun gate(context: Context): OwnerGate {
        val ownerPhone = Build.DEVICE.equals(OWNER_DEVICE, ignoreCase = true) && Build.MODEL.equals(OWNER_MODEL, ignoreCase = true)
        val lineage = context.packageManager.hasSystemFeature("org.lineageos.android")
        val privileged = privilegedGranted(context)
        val overrideApi = if (privileged) overrideApiReachable() else null
        val (running, root, permitted) = shizukuState()
        val route = when {
            privileged && overrideApi == true -> Route.PRIVILEGED
            running && root && permitted && Build.TYPE != "user" -> Route.SHIZUKU_ROOT
            else -> null
        }
        val reason = when {
            !ownerPhone -> "this is not the owner's S20 (${Build.MODEL}); the owner build stays read-only here"
            !lineage -> "not the owner's LineageOS build; Samsung's stock IMS ignores CarrierConfig"
            route != null -> null
            running && !permitted -> "Shizuku is running but has not granted this app"
            running && !root -> "Shizuku runs as the shell user; cmd phone cc needs root"
            Build.TYPE == "user" -> "a user build refuses cmd phone cc"
            privileged -> "a privileged system app, but CarrierConfigManager.overrideConfig is not reachable (hidden-API allowlist), and Shizuku is not running"
            else -> "no route: not a privileged system app, and Shizuku is not running"
        }
        return OwnerGate(route, ownerPhone, Build.TYPE, running, root, permitted, privileged, reason, overrideApi)
    }

    /** Opens the route the gate picked, or throws. Close it with [OwnerPhoneHandle.close]. */
    suspend fun open(context: Context): OwnerPhoneHandle {
        // In a fresh process (a job after boot) Shizuku's binder arrives a moment after start.
        if (!(privilegedGranted(context) && overrideApiReachable())) {
            repeat(20) {
                if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return@repeat
                delay(250)
            }
        }
        val gate = gate(context)
        if (!gate.canWrite) throw SafetyException("no_access", gate.reason ?: "no route")
        return when (gate.route!!) {
            Route.PRIVILEGED -> OwnerPhoneHandle(PrivilegedPhone(context)) {}
            Route.SHIZUKU_ROOT -> {
                val bound = ShizukuPhone.bind(context) ?: throw SafetyException("no_access", "the Shizuku service did not start")
                // The service stays bound for the next step of this process (see ShizukuPhone.bind).
                OwnerPhoneHandle(ShizukuPhone(context, bound)) {}
            }
        }
    }
}

class OwnerPhoneHandle(val phone: OwnerPhone, private val release: suspend () -> Unit) {
    suspend fun close() = release()
}

/** What both routes read in the app itself: mobile data, Wi-Fi, clocks. */
abstract class AppSidePhone(protected val context: Context) : OwnerPhone {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun mobileData(subId: Int): MobileData {
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
        val enabled = runCatching { tm?.isDataEnabled }.getOrNull()
        val validated = runCatching {
            connectivity?.allNetworks?.any { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrNull()
        return MobileData(enabled, validated)
    }

    override suspend fun wifiConnected(): Boolean? = runCatching {
        connectivity?.allNetworks?.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull()

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun uptimeMs(): Long = SystemClock.elapsedRealtime()
    override suspend fun sleep(ms: Long) = delay(ms)
}

/** Route (b): Shizuku's server as root runs [OwnerShellService]. */
class ShizukuPhone(context: Context, private val binder: IBinder) : AppSidePhone(context) {
    override val route = Route.SHIZUKU_ROOT

    private suspend fun run(command: OwnerCommand, args: OwnerArgs = OwnerArgs()): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (command == OwnerCommand.CC_SET) KeyGuard.check(args.key ?: return@withContext null)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OwnerShellProtocol.DESCRIPTOR)
            OwnerShellProtocol.writeArgs(data, command, args)
            if (!binder.transact(OwnerShellProtocol.RUN, data, reply, 0)) return@withContext null
            reply.readException()
            val exit = reply.readInt()
            if (reply.readInt() != 1) return@withContext null
            val pfd = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
            if (exit == -2) null else exit to text
        } catch (_: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun text(command: OwnerCommand, args: OwnerArgs = OwnerArgs()): String? = run(command, args)?.second

    override suspend fun carrierConfigText() = text(OwnerCommand.CC_DUMP)
    override suspend fun registryText() = text(OwnerCommand.REGISTRY)
    override suspend fun phoneText() = text(OwnerCommand.PHONE)
    override suspend fun ecmProperty() = text(OwnerCommand.ECM_PROPERTY)
    override suspend fun iwlanLogText(slot: Int) = text(OwnerCommand.IWLAN_LOG, OwnerArgs(slot = slot))

    override suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult {
        val out = run(OwnerCommand.CC_SET, OwnerArgs(slot = slot, key = key, value = value)) ?: return CcResult(false, "no_answer")
        return CcResult.parse(out.second, out.first)
    }

    override suspend fun ccClear(slot: Int, subId: Int): CcResult {
        val out = run(OwnerCommand.CC_CLEAR, OwnerArgs(slot = slot)) ?: return CcResult(false, "no_answer")
        return CcResult.parse(out.second, out.first)
    }

    override suspend fun apnQuery(numeric: String) = PhoneApnRow.parseQuery(text(OwnerCommand.APN_QUERY, OwnerArgs(numeric = numeric)))
    override suspend fun apnQueryMarked() = PhoneApnRow.parseQuery(text(OwnerCommand.APN_QUERY_MARKED))
    override suspend fun apnConflicts(values: Map<String, String>) =
        PhoneApnRow.parseQuery(text(OwnerCommand.APN_CONFLICTS, OwnerArgs(binds = values)))?.map { it.id }
    override suspend fun apnInsert(values: Map<String, String>) = run(OwnerCommand.APN_INSERT, OwnerArgs(binds = values))?.first == 0
    override suspend fun apnDelete(id: Long) = run(OwnerCommand.APN_DELETE, OwnerArgs(id = id))?.first == 0

    override suspend fun setWifiEnabled(on: Boolean): Boolean = run(OwnerCommand.WIFI_SET, OwnerArgs(value = on))?.first == 0

    override suspend fun preferredApn(subId: Int): PreferredApn {
        val out = text(OwnerCommand.PREFERRED_QUERY, OwnerArgs(subId = subId)) ?: return PreferredApn(false, null)
        if ("No result found" in out) return PreferredApn(true, null)
        val id = Regex("_id=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return PreferredApn(false, null)
        return PreferredApn(true, id)
    }

    override suspend fun setPreferredApn(subId: Int, id: Long?): Boolean =
        if (id == null) {
            run(OwnerCommand.PREFERRED_CLEAR, OwnerArgs(subId = subId))?.first == 0
        } else {
            run(OwnerCommand.PREFERRED_SET, OwnerArgs(subId = subId, id = id))?.first == 0
        }

    companion object {
        private const val BIND_TIMEOUT_MS = 20_000L
        private const val ATTEMPTS = 3

        private val lock = Mutex()

        @Volatile
        private var bound: Bound? = null

        private class Bound(val args: Shizuku.UserServiceArgs, val connection: ServiceConnection, val binder: IBinder)

        private fun args(context: Context) = Shizuku.UserServiceArgs(ComponentName(context.packageName, OwnerShellService::class.java.name))
            .tag("owner")
            .daemon(false)
            .processNameSuffix("owner")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)

        /**
         * The root service, one per app process and reused by every step
         * (daemon off: it ends with the app process). The first owner run on
         * the S20 (2026-10-19) unbound and removed it after each step; a bind
         * that came within milliseconds of the old service process exiting
         * lost its record in Shizuku's server ("unable to find token") and
         * timed out, so the APN step and a cleanup found no service. Now a
         * live binder is reused, and a failed start is unbound, given a pause
         * and tried again.
         */
        suspend fun bind(context: Context): IBinder? = lock.withLock {
            bound?.binder?.takeIf { it.pingBinder() }?.let { return@withLock it }
            bound?.let { stale -> unbind(stale.args, stale.connection) }
            bound = null
            retrying(ATTEMPTS, pause = { delay(3_000L * it) }) { bindOnce(context) }?.also { bound = it }?.binder
        }

        private suspend fun bindOnce(context: Context): Bound? {
            val args = args(context)
            val connected = CompletableDeferred<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    connected.complete(service?.takeIf { it.pingBinder() })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    connected.complete(null)
                    if (bound?.connection === this) bound = null
                }
            }
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
            if (binder == null) {
                unbind(args, connection)
                return null
            }
            return Bound(args, connection, binder)
        }

        private suspend fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
        }
    }
}

/**
 * Route (a): the app as a privileged system app. CarrierConfigManager's
 * system API overrideConfig(subId, bundle), which is never persistent, and
 * the telephony provider directly. The carrier config and the registry are
 * read with dumpsys as the app (DUMP), the IWLAN tags with logcat
 * (READ_LOGS); the emergency state and IMS through the system APIs
 * ([emergencyApi], [imsApi]), since the phone service's dump is closed to
 * an app.
 */
class PrivilegedPhone(context: Context) : AppSidePhone(context) {
    override val route = Route.PRIVILEGED
    private val resolver = context.contentResolver
    private val carriers = Uri.parse(OwnerCommand.CARRIERS)

    private suspend fun local(command: OwnerCommand, args: OwnerArgs = OwnerArgs()): String? = withContext(Dispatchers.IO) {
        if (command.writes) return@withContext null // writes go through the APIs below, never a process
        val argv = command.argv(args) ?: return@withContext null
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return@withContext null
        }
        try {
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(command.timeoutSeconds, TimeUnit.SECONDS)) null else text
        } catch (_: IOException) {
            null
        } finally {
            process.destroy()
        }
    }

    override suspend fun carrierConfigText() = local(OwnerCommand.CC_DUMP)
    override suspend fun registryText() = local(OwnerCommand.REGISTRY)

    /**
     * Not read here: `dumpsys activity service com.android.phone` goes through
     * ActivityManager, which wants PACKAGE_USAGE_STATS besides DUMP ("Permission
     * Denial: can't dump ActivityManager", the S20 on 2026-10-19). The
     * emergency state and IMS come from [emergencyApi] and [imsApi].
     */
    override suspend fun phoneText(): String? = null

    /** Not read here: SELinux keeps priv_app from ril.cdma.inecmmode (radio_cdma_ecm_prop); [emergencyApi] reads the mode. */
    override suspend fun ecmProperty(): String? = null
    override suspend fun iwlanLogText(slot: Int) = local(OwnerCommand.IWLAN_LOG, OwnerArgs(slot = slot))

    override suspend fun emergencyApi(): EmergencyApi = withContext(Dispatchers.IO) {
        val telephony = SystemBinders.telephony()
        EmergencyApi(
            ecbm = ecbm(telephony),
            inEmergencyCall = SystemBinders.call(SystemBinders.telecom(context), SystemBinders.TELECOM, "isInEmergencyCall"),
            emergencySmsMode = SystemBinders.call(telephony, SystemBinders.TELEPHONY, "isInEmergencySmsMode"),
        )
    }

    /**
     * ITelephony.getEmergencyCallbackMode for the default phone and every
     * active subscription; true when any says so, null when one call fails.
     */
    @SuppressLint("MissingPermission")
    private fun ecbm(telephony: Any?): Boolean? {
        val subs = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
        }.getOrNull() ?: return null
        val answers = (listOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID) + subs).distinct().map { sub ->
            SystemBinders.call(telephony, SystemBinders.TELEPHONY, "getEmergencyCallbackMode", sub)
        }
        return if (answers.any { it == true }) true else if (answers.all { it == false }) false else null
    }

    override suspend fun imsApi(slot: Int, subId: Int): ImsApi =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ImsApiReader.read(context, subId) else ImsApi(null, null, null, null, null)

    private fun overrideConfig(subId: Int, bundle: PersistableBundle?): CcResult = try {
        val manager = context.getSystemService(CarrierConfigManager::class.java) ?: return CcResult(false, "no_carrier_config_manager")
        // The two-argument system API: CarrierConfigLoader.overrideConfig(subId, bundle, persistent = false).
        CarrierConfigManager::class.java.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)
            .invoke(manager, subId, bundle)
        CcResult(true, null)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        CcResult(false, if (e.targetException is SecurityException) "permission_denied" else "exception")
    } catch (_: ReflectiveOperationException) {
        CcResult(false, "no_override_api")
    }

    override suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult {
        KeyGuard.check(key)
        return withContext(Dispatchers.IO) { overrideConfig(subId, PersistableBundle().apply { putBoolean(key, value) }) }.also { waitForLoad() }
    }

    override suspend fun ccClear(slot: Int, subId: Int): CcResult =
        withContext(Dispatchers.IO) { overrideConfig(subId, null) }.also { waitForLoad() }

    /** overrideConfig posts to the loader's handler; give it a moment before the dump is read. */
    private suspend fun waitForLoad() = delay(1_500)

    private fun query(selection: String?, args: Array<String>?): List<PhoneApnRow>? = runCatching {
        resolver.query(carriers, PhoneApnRow.COLUMNS.toTypedArray(), selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        PhoneApnRow(
                            id = c.getLong(0),
                            name = c.getString(1),
                            apn = c.getString(2).orEmpty(),
                            types = c.getString(3).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                            protocol = c.getString(4)?.uppercase(),
                            mvnoType = c.getString(5)?.takeIf { it.isNotEmpty() }?.lowercase(),
                        ),
                    )
                }
            }
        }
    }.getOrNull()

    /** A system app may still switch Wi-Fi (WifiServiceImpl lets system apps through); needs CHANGE_WIFI_STATE. */
    @Suppress("DEPRECATION")
    override suspend fun setWifiEnabled(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.getSystemService(WifiManager::class.java)?.setWifiEnabled(on) == true }.getOrDefault(false)
    }

    override suspend fun apnQuery(numeric: String) = withContext(Dispatchers.IO) {
        if (!Regex("[0-9]{5,6}").matches(numeric)) null else query("numeric = ?", arrayOf(numeric))
    }

    override suspend fun apnQueryMarked() = withContext(Dispatchers.IO) { query("name LIKE ?", arrayOf("%" + ApnColumns.MARKER.trim())) }

    override suspend fun apnConflicts(values: Map<String, String>): List<Long>? = withContext(Dispatchers.IO) {
        // The same checks as the Shizuku route's argv.
        if (OwnerCommand.APN_CONFLICTS.argv(OwnerArgs(binds = values)) == null) return@withContext null
        val match = ApnColumns.conflictMatch(values)
        runCatching {
            resolver.query(carriers, arrayOf("_id"), match.joinToString(" AND ") { "${it.first} = ?" }, match.map { it.second }.toTypedArray(), null)?.use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
        }.getOrNull()
    }

    /** True only when the provider added a row: a merge into an existing row answers no URI. */
    override suspend fun apnInsert(values: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        // The same checks as the Shizuku route's argv.
        if (OwnerCommand.APN_INSERT.argv(OwnerArgs(binds = values)) == null) return@withContext false
        val cv = ContentValues()
        for ((k, v) in values) {
            if (k in ApnColumns.INTEGER) cv.put(k, v.toLong()) else cv.put(k, v)
        }
        runCatching { resolver.insert(carriers, cv) != null }.getOrDefault(false)
    }

    override suspend fun apnDelete(id: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(carriers, "_id = ?", arrayOf(id.toString())) > 0 }.getOrDefault(false)
    }

    private fun preferUri(subId: Int) = Uri.parse("${OwnerCommand.PREFERAPN}/$subId")

    override suspend fun preferredApn(subId: Int): PreferredApn = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(preferUri(subId), arrayOf("_id"), null, null, null)?.use { c ->
                PreferredApn(true, if (c.moveToFirst()) c.getLong(0) else null)
            } ?: PreferredApn(false, null)
        }.getOrDefault(PreferredApn(false, null))
    }

    override suspend fun setPreferredApn(subId: Int, id: Long?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (id == null) {
                resolver.delete(preferUri(subId), null, null)
                true
            } else {
                // The provider answers a preferred-APN insert without a row URI.
                resolver.insert(preferUri(subId), ContentValues().apply { put("apn_id", id) })
                true
            }
        }.getOrDefault(false)
    }
}
