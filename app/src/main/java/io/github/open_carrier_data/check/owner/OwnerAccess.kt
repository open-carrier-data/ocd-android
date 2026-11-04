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
import io.github.open_carrier_data.check.owner.core.RegistrationWatch
import io.github.open_carrier_data.check.owner.core.Route
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.ServerStart
import io.github.open_carrier_data.check.owner.core.TrialGuard
import io.github.open_carrier_data.check.owner.core.TypedOverride
import io.github.open_carrier_data.check.owner.core.retrying
import io.github.open_carrier_data.check.owner.core.sha256
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

/** What the app may do on this phone, and why not (round 25 OB2). */
data class OwnerGate(
    val route: Route?,
    val buildType: String,
    val privilegedGranted: Boolean,
    val reason: String?,
    /** CarrierConfigManager.overrideConfig(int, PersistableBundle) found (the hidden-API check passed); null when not looked up. */
    val overrideApi: Boolean? = null,
    /** [reason] in plain words for the screen ("No system access: …"); the technical one goes to the log. */
    val plainReason: String? = null,
) {
    val canWrite: Boolean get() = route != null && reason == null
}

object OwnerAccess {
    /**
     * What the privileged route needs; granted only to a priv-app with the
     * shipped allowlist. READ_LOGS is gone (2026-10-20): the owner build reads
     * no log; PACKAGE_USAGE_STATS opens the IWLAN and QNS dumps instead.
     */
    val PRIVILEGED_PERMISSIONS = listOf(
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.READ_PRECISE_PHONE_STATE",
        "android.permission.WRITE_APN_SETTINGS",
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
        // Round 30: the emergency data test's request for NET_CAPABILITY_EIMS, a restricted network.
        "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS",
        // Round 42 (entitlement note): declared next to READ_PRIVILEGED_PHONE_STATE for the EAP-AKA
        // getIccAuthentication path. Behind an aconfig flag and not required, so the privileged route
        // does not depend on it (filtered out of privilegedGranted below).
        "android.permission.USE_ICC_AUTH",
    )

    /** Only the emergency data test needs it; a ROM build denies it unless its builder turned the test on (round 31, SM10). */
    const val RESTRICTED_NETWORKS = "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS"

    /** Round 42: Android 16's SIM-auth permission, behind an aconfig flag; nothing uses it yet, so it is not required. */
    const val ICC_AUTH = "android.permission.USE_ICC_AUTH"

    fun privilegedGranted(context: Context): Boolean {
        val info = context.applicationInfo
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        return system && PRIVILEGED_PERMISSIONS.filterNot { it == RESTRICTED_NETWORKS || it == ICC_AUTH }.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun restrictedNetworksGranted(context: Context): Boolean = context.checkSelfPermission(RESTRICTED_NETWORKS) == PackageManager.PERMISSION_GRANTED

    /**
     * The privileged route's one write API, looked up without calling it: a
     * system app missing from the hidden-API allowlist gets no method here,
     * and the gate falls back to Shizuku instead of failing at the first test.
     */
    fun overrideApiReachable(): Boolean = runCatching {
        CarrierConfigManager::class.java.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)
    }.isSuccess

    /**
     * The write gate, the same on every phone (round 46: no device check): a privileged system app whose override API is
     * reachable. A hand install holds no privileged permission, so it has no route and stays passive.
     */
    fun gate(context: Context): OwnerGate {
        val privileged = privilegedGranted(context)
        val overrideApi = if (privileged) overrideApiReachable() else null
        val route = if (privileged && overrideApi == true) Route.PRIVILEGED else null
        val reason = when {
            route != null -> null
            privileged -> "a privileged system app, but CarrierConfigManager.overrideConfig is not reachable (hidden-API allowlist)"
            else -> "no route: not a privileged system app"
        }
        val plain = when {
            reason == null -> null
            privileged -> "Android blocks the change"
            else -> "not installed as a system app"
        }
        return OwnerGate(route, Build.TYPE, privileged, reason, overrideApi, plain)
    }

    /** Opens the route the gate picked, or throws. Close it with [OwnerPhoneHandle.close]. */
    suspend fun open(context: Context): OwnerPhoneHandle {
        val gate = gate(context)
        if (!gate.canWrite) throw SafetyException("no_access", gate.reason ?: "no route")
        return OwnerPhoneHandle(PrivilegedPhone(context)) {}
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

    /**
     * The running system_server's start: the property (when SELinux lets the
     * app read it), the kernel's boot ID, and zygote's PID (the app's parent;
     * a soft restart starts a new zygote).
     */
    override suspend fun serverStart(): ServerStart = withContext(Dispatchers.IO) {
        ServerStart(
            startElapsedMs = property("sys.system_server.start_elapsed")?.toLongOrNull(),
            startCount = property("sys.system_server.start_count")?.toIntOrNull(),
            bootId = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) },
            zygotePid = runCatching { android.system.Os.getppid() }.getOrNull()?.takeIf { it > 1 },
            elapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun property(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    override suspend fun callsSeen(): io.github.open_carrier_data.check.owner.core.CallsSeen = withContext(Dispatchers.IO) { CallSightings.read(context) }

    override suspend fun watchRegistrations(subId: Int): RegistrationWatch? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ImsRegistrationWatch.start(context, subId) else null
}

/**
 * Route (a): the app as a privileged system app. CarrierConfigManager's
 * system API overrideConfig(subId, bundle), which is never persistent, and
 * the telephony provider directly. The carrier config and the registry are
 * read with dumpsys as the app (DUMP), the IWLAN data service and QNS with
 * `dumpsys activity service` (DUMP plus PACKAGE_USAGE_STATS); the emergency
 * state and IMS through the system APIs ([emergencyApi], [imsApi]). Never the
 * device log: reading it asks the owner for log access, and a privileged app
 * cannot skip that prompt (2026-10-20).
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
     * Not read for the observe path: the emergency state and IMS come from [emergencyApi] and [imsApi].
     * The "Permission Denial: can't dump ActivityManager" of 2026-10-19 is stale since PACKAGE_USAGE_STATS
     * (#12): the same dump is read through [telephonyText] for the emergency data test and, since round 43,
     * for DataRetryManager's wait after a refused setup.
     */
    override suspend fun phoneText(): String? = null

    /** Not read here: SELinux keeps priv_app from ril.cdma.inecmmode (radio_cdma_ecm_prop); [emergencyApi] reads the mode. */
    override suspend fun ecmProperty(): String? = null
    override suspend fun powerText() = local(OwnerCommand.POWER)

    /** "Permission Denial" when PACKAGE_USAGE_STATS (or its app-op) is missing: then unreadable. */
    override suspend fun iwlanServiceText() = local(OwnerCommand.IWLAN_DUMP)?.takeIf { "IwlanDataServiceProvider" in it }
    override suspend fun qnsText() = local(OwnerCommand.QNS_DUMP)?.takeIf { "QualifiedNetworksServiceImpl" in it }

    /** The SMS service's dump (DUMP): its inbound SMS handler's records say how each SMS came in ([InboundSms]). */
    override suspend fun smsText() = local(OwnerCommand.SMS_DUMP)?.takeIf { "SmsManager for subId" in it }

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

    /** Round 30's override trials: one typed bundle, never persistent; the trial guard again (never group A or B). */
    override suspend fun ccSetBundle(slot: Int, subId: Int, values: Map<String, TypedOverride>): CcResult {
        TrialGuard.check(values.keys)
        val bundle = try {
            OverrideBundle.of(values)
        } catch (e: RuntimeException) {
            return CcResult(false, "a value does not fit its type")
        }
        return withContext(Dispatchers.IO) { overrideConfig(subId, bundle) }.also { waitForLoad() }
    }

    /**
     * The phone service's dump, read for the emergency data test (DataConfigManager,
     * AccessNetworksManager, DataRetryManager) and, round 43, after a refused setup for DataRetryManager's
     * wait: DUMP and PACKAGE_USAGE_STATS, held since the IWLAN dumps (2026-10-20). The emergency state
     * and IMS still come from the system APIs.
     */
    override suspend fun telephonyText(): String? = local(OwnerCommand.PHONE)?.takeIf { "DataConfigManager-" in it }

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
                            numeric = c.getString(6),
                            carrierId = if (c.isNull(7)) null else c.getLong(7),
                        ),
                    )
                }
            }
        }
    }.getOrNull()

    override suspend fun apnRowById(id: Long): PhoneApnRow? = withContext(Dispatchers.IO) { query("_id = ?", arrayOf(id.toString()))?.firstOrNull() }

    /** Every visible row's identity columns, hashed in row order; the same text as the Shizuku route's `content query` gives. */
    override suspend fun apnTableHash(): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(carriers, OwnerCommand.HASH_COLUMNS.toTypedArray(), null, null, "_id")?.use { c ->
                val text = StringBuilder()
                var n = 0
                while (c.moveToNext()) {
                    text.append("Row: ").append(n++).append(' ')
                    for (i in OwnerCommand.HASH_COLUMNS.indices) {
                        if (i > 0) text.append(", ")
                        text.append(OwnerCommand.HASH_COLUMNS[i]).append('=').append(if (c.isNull(i)) "NULL" else c.getString(i))
                    }
                    text.append('\n')
                }
                sha256(text.toString())
            }
        }.getOrNull()
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

    /** The new row's ID; null when the provider answered no URI (a merge into an existing row, or a refusal): the runner stops. */
    override suspend fun apnInsert(values: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        // The same checks as the Shizuku route's argv.
        if (OwnerCommand.APN_INSERT.argv(OwnerArgs(binds = values)) == null) return@withContext null
        val cv = ContentValues()
        for ((k, v) in values) {
            if (k in ApnColumns.INTEGER && v.isNotEmpty()) cv.put(k, v.toLong()) else cv.put(k, v)
        }
        runCatching { resolver.insert(carriers, cv)?.let { android.content.ContentUris.parseId(it) } }.getOrNull()?.takeIf { it > 0 }
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
