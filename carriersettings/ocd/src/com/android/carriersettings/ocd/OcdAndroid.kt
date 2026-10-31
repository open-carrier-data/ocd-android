/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.service.carrier.CarrierIdentifier
import android.telecom.TelecomManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import android.util.Log
import com.android.carriersettings.R
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android side of OCD's dataset in CarrierSettings: the [Platform] the [Engine] runs on,
 * the writer check of the pinned writer (round 29 S3, with the writer a build parameter since
 * round 31), the writer app's on/off, the call and emergency readings (round 29's
 * OcdLayerPhone, kept), the preferred APN, IMS and data readings of the default emergency APN
 * test, the alarms, and the conversion of a built bundle to a PersistableBundle. Nothing here
 * does network I/O or places calls; the only UI is a stop-event notification. Nothing logs an
 * IMSI, ICCID, a hash of either, or a number.
 */
object OcdAndroid {
    const val TAG = "OcdDataset"
    const val ACTION_DAILY = "com.android.carriersettings.ocd.DAILY"
    const val ACTION_RETRY = "com.android.carriersettings.ocd.RETRY"
    const val ACTION_APP_CHECK = "com.android.carriersettings.ocd.APP_CHECK"
    private const val CHANNEL = "ocd_stop_events"
    const val BAKED_DIR = "/system_ext/etc/OcdCarrierSettings/dataset"
    const val BUILD_FILE = "/system_ext/etc/OcdCarrierSettings/ocd-build.json"
    private const val IMS_SERVICE = "android.telephony.ims.ImsService"

    @Volatile private var engine: Engine? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ocd-dataset").apply { isDaemon = true } }
    /** Binder callbacks land here, never on [worker], which may be waiting for them. */
    private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "ocd-callbacks").apply { isDaemon = true } }

    /** The last call end and emergency sighting on the elapsed clock, in memory too (round 29). */
    @Volatile var callEndedElapsed = 0L
    @Volatile var emergencyElapsed = 0L

    fun engine(context: Context): Engine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val platform = AndroidPlatform(context.applicationContext)
            // 9.2: at first start the round 29 files go.
            runCatching { platform.store.deleteRound29(context.applicationContext.createDeviceProtectedStorageContext().filesDir) }
            val e = Engine(platform).also { engine = it }
            watchWriter(context.applicationContext)
            return e
        }
    }

    /**
     * R31.4: while this process runs, a PACKAGE_CHANGED of the writer app (a disable or an
     * enable) checks at once; the 15-minute alarm and every load cover the rest (an implicit
     * broadcast does not start this app).
     */
    private fun watchWriter(context: Context) {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.data?.schemeSpecificPart != writerPackage(c)) return
                    runOnWorker { engine(c).appCheck("PACKAGE_CHANGED") }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.e(TAG, "the writer app is not watched", it) }
    }

    /** The SIM of an onLoadConfig call (4.1); the ICCID as LineageOS's service reads it. In memory only. */
    fun simOf(context: Context, subId: Int, id: CarrierIdentifier?): SimId {
        id ?: return SimId.NO_SIM
        val iccid = runCatching { context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)?.iccId }.getOrNull()
        return SimId(id.mcc.orEmpty(), id.mnc.orEmpty(), id.spn, id.imsi, id.gid1, id.gid2, iccid, id.carrierId, id.specificCarrierId, carrierIdTable(context))
    }

    @Volatile private var carrierIds: AndroidCarrierIdTable? = null

    /** The phone's carrier ID table for the resolution order (rule round 45), one per process. */
    fun carrierIdTable(context: Context): CarrierIdTable =
        carrierIds ?: synchronized(this) { carrierIds ?: AndroidCarrierIdTable(context.applicationContext ?: context).also { carrierIds = it } }

    /** A built bundle as Telephony takes it. */
    fun toBundle(built: Built): PersistableBundle {
        val out = PersistableBundle()
        for ((key, value) in built.values) put(out, key, built.types.getValue(key), value)
        return out
    }

    private fun put(out: PersistableBundle, key: String, type: KeyType, value: Any) {
        when (type) {
            KeyType.BOOL -> out.putBoolean(key, value as Boolean)
            KeyType.INT -> out.putInt(key, value as Int)
            KeyType.LONG -> out.putLong(key, value as Long)
            KeyType.DOUBLE -> out.putDouble(key, value as Double)
            KeyType.STRING -> out.putString(key, value as String)
            KeyType.STRING_ARRAY -> out.putStringArray(key, (value as List<*>).map { it as String }.toTypedArray())
            KeyType.INT_ARRAY -> out.putIntArray(key, (value as List<*>).map { it as Int }.toIntArray())
            KeyType.BUNDLE -> {
                val inner = PersistableBundle()
                @Suppress("UNCHECKED_CAST")
                for ((k, v) in value as Map<String, Inner>) put(inner, k, v.type, v.value)
                out.putPersistableBundle(key, inner)
            }
        }
    }

    // --- identity (round 29 S3; the writer a build parameter since round 31, SM5) ----------------

    /** The pinned writer certificate digests (SHA-256, the same array as the permission's knownCerts). */
    fun ownerCerts(context: Context): List<ByteArray> = context.resources.getStringArray(R.array.ocd_owner_certs)
        .map { it.replace(":", "").trim().lowercase() }
        .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        .map { hex -> ByteArray(32) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }

    /** The writer's package, the build parameter OCD_WRITER_PACKAGE (an OCD app flavor). */
    fun writerPackage(context: Context): String =
        runCatching { context.resources.getString(R.string.ocd_writer_package) }.getOrNull()
            ?.takeIf { it == Protocol.OWNER_PACKAGE || it == Protocol.PLAIN_PACKAGE } ?: Protocol.OWNER_PACKAGE

    /**
     * The writer app's state (R31.4): `enabled`; `disabled` when the person disabled it (Settings >
     * Apps > Disable, `pm disable-user`) or removed it for the user; `absent` when it is not in the
     * image or not signed with the pinned certificate; `unknown` when PackageManager cannot say.
     * A crash, an update or hibernation leave it enabled. Works before the first unlock.
     */
    fun writerState(context: Context): String {
        val pm = context.packageManager
        val pkg = writerPackage(context)
        val info = try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (e: PackageManager.NameNotFoundException) {
            return "absent"
        } catch (e: Exception) {
            return "unknown"
        }
        val certs = ownerCerts(context)
        val signed = runCatching { certs.any { pm.hasSigningCertificate(pkg, it, PackageManager.CERT_INPUT_SHA256) } }.getOrNull() ?: return "unknown"
        if (!signed) return "absent"
        if ((info.flags and ApplicationInfo.FLAG_INSTALLED) == 0) return "disabled"
        val setting = runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull() ?: return "unknown"
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> "disabled"
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> if (info.enabled) "enabled" else "disabled"
            else -> "enabled"
        }
    }

    /** S3: the calling UID is this build's writer app alone, with the pinned certificate, installed and enabled. */
    fun callerIsOwner(context: Context, uid: Int): Boolean {
        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull() ?: return false
        if (packages.size != 1 || packages[0] != writerPackage(context)) return false
        val certs = ownerCerts(context)
        if (certs.none { runCatching { pm.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }.getOrDefault(false) }) return false
        return writerState(context) == "enabled"
    }

    // --- call and emergency state (round 29's OcdLayerPhone, kept) ------------------------------

    private const val TELEPHONY = "com.android.internal.telephony.ITelephony"
    private const val TELECOM = "com.android.internal.telecom.ITelecomService"

    fun phoneState(context: Context): PhoneState {
        val telephony = telephony()
        val telecom = telecom(context)
        return PhoneState(
            inCall = inCall(context, telecom),
            inEmergencyCall = call(telecom, TELECOM, "isInEmergencyCall") as? Boolean,
            ecbm = ecbm(context, telephony),
            emergencySms = call(telephony, TELEPHONY, "isInEmergencySmsMode") as? Boolean,
        )
    }

    fun inEmergencyCall(context: Context): Boolean? = call(telecom(context), TELECOM, "isInEmergencyCall") as? Boolean

    private fun telephony(): Any? = runCatching {
        TelephonyManager::class.java.getDeclaredMethod("getITelephony").apply { isAccessible = true }.invoke(null)
    }.getOrNull()

    private fun telecom(context: Context): Any? = runCatching {
        val manager = context.getSystemService(TelecomManager::class.java) ?: return null
        TelecomManager::class.java.getDeclaredMethod("getTelecomService").apply { isAccessible = true }.invoke(manager)
    }.getOrNull()

    private fun inCall(context: Context, telecom: Any?): Boolean? {
        telecom ?: return null
        val iface = runCatching { Class.forName(TELECOM) }.getOrNull() ?: return null
        val methods = iface.methods.filter { it.name == "isInCall" && it.returnType == Boolean::class.javaPrimitiveType }
        for (m in methods.sortedByDescending { it.parameterTypes.size }) {
            if (m.parameterTypes.any { it != String::class.java }) continue
            val args: Array<Any?> = when (m.parameterTypes.size) {
                2 -> arrayOf(context.packageName, null)
                1 -> arrayOf(context.packageName)
                0 -> emptyArray()
                else -> continue
            }
            return runCatching { m.invoke(telecom, *args) as? Boolean }.getOrNull()
        }
        return null
    }

    private fun ecbm(context: Context, telephony: Any?): Boolean? {
        val subs = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
        }.getOrNull() ?: return null
        val answers = (listOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID) + subs).distinct().map { sub ->
            call(telephony, TELEPHONY, "getEmergencyCallbackMode", sub) as? Boolean
        }
        return if (answers.any { it == true }) true else if (answers.all { it == false }) false else null
    }

    private fun call(binder: Any?, iface: String, method: String, vararg args: Int): Any? {
        binder ?: return null
        return runCatching {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            Class.forName(iface).getMethod(method, *types).invoke(binder, *args.toTypedArray())
        }.getOrNull()
    }

    /** Roaming, national roaming included; null when the service state cannot be read (round 29). */
    fun roaming(context: Context, subId: Int, simMcc: String?): Boolean? = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId) ?: return null
        val ss = tm.serviceState ?: return null
        val operator = ss.operatorNumeric.orEmpty()
        ss.roaming || (operator.length >= 5 && !simMcc.isNullOrEmpty() && operator.take(3) != simMcc)
    }.getOrNull()

    // --- alarms (round 29's, kept) ----------------------------------------------------------------

    private fun pending(context: Context, action: String, request: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, request, Intent(action).setPackage(context.packageName).setClass(context, OcdAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )

    fun armDaily(context: Context, force: Boolean) {
        runCatching {
            if (!force && pending(context, ACTION_DAILY, 1, PendingIntent.FLAG_NO_CREATE) != null) return
            context.getSystemService(AlarmManager::class.java)?.setInexactRepeating(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_DAY, AlarmManager.INTERVAL_DAY,
                pending(context, ACTION_DAILY, 1)!!,
            )
        }.onFailure { Log.e(TAG, "daily check not armed", it) }
    }

    /** R31.4: a light check of the writer app's state every 15 minutes (inexact). */
    fun armAppCheck(context: Context, force: Boolean) {
        runCatching {
            if (!force && pending(context, ACTION_APP_CHECK, 3, PendingIntent.FLAG_NO_CREATE) != null) return
            context.getSystemService(AlarmManager::class.java)?.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pending(context, ACTION_APP_CHECK, 3)!!,
            )
        }.onFailure { Log.e(TAG, "the app check is not armed", it) }
    }

    fun armRetry(context: Context, delayMs: Long) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending(context, ACTION_RETRY, 2)!!,
            )
        }.onFailure { Log.e(TAG, "retry not armed", it) }
    }

    fun runOnWorker(task: () -> Unit) {
        worker.execute {
            try {
                task()
            } catch (t: Throwable) {
                Log.e(TAG, "worker task failed", t)
            }
        }
    }

    // --- the platform -----------------------------------------------------------------------------

    private class AndroidPlatform(private val context: Context) : Platform {
        override val store = Store(File(context.createDeviceProtectedStorageContext().filesDir, "ocd"))
        override val bakedDir = File(BAKED_DIR)

        @Volatile private var cellRead: Pair<BuildCell?, String?>? = null
        @Volatile private var defaults: PersistableBundle? = null

        override fun nowMs(): Long = System.currentTimeMillis()

        override fun sleep(ms: Long) = Thread.sleep(ms)

        private fun systemProperty(name: String): String? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        private fun buildFile(): Pair<BuildCell?, String?> {
            cellRead?.let { return it }
            val parsed = runCatching { BuildCell.parse(Files.readBounded(File(BUILD_FILE), 64L * 1024)) }.getOrNull()
            val cell = parsed?.first?.takeIf { c ->
                c.codename == Build.DEVICE && c.model.equals(Build.MODEL, ignoreCase = true) && c.romFamily == "lineageos" &&
                    c.romLine == systemProperty("ro.lineage.build.version")
            }
            if (parsed != null && cell == null) Log.w(TAG, "ocd-build.json does not fit this phone; no measured record is served and the emergency gate stays full")
            return (cell to parsed?.second).also { cellRead = it }
        }

        override fun cell(): BuildCell? = buildFile().first

        override fun incremental(): String = Build.VERSION.INCREMENTAL.orEmpty().filter { it in ' '..'~' }.take(160)

        override fun buildInfo(): Map<String, Any?> {
            val (cell, pkg) = buildFile()
            val version = pkg?.let { runCatching { context.packageManager.getPackageInfo(it, 0).longVersionCode }.getOrNull() }
            return linkedMapOf(
                "incremental" to incremental(),
                "device" to Build.DEVICE,
                "model" to Build.MODEL,
                "rom_family" to cell?.romFamily,
                "rom_line" to systemProperty("ro.lineage.build.version"),
                "ims_stack_id" to cell?.stackId,
                "ims_stack_fork" to cell?.stackFork,
                "ims_stack_version" to version,
                "cell_matches" to (cell != null),
            )
        }

        override fun platformApnVersion(): Int? = runCatching {
            val res = Resources.getSystem()
            val id = res.getIdentifier("apns", "xml", "android")
            if (id == 0) return null
            res.getXml(id).use { parser ->
                var event = parser.next()
                while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
                if (event != XmlPullParser.START_TAG || parser.name != "apns") return null
                parser.getAttributeValue(null, "version")?.toInt()
            }
        }.getOrNull()

        override fun androidDefault(key: String): DefaultType {
            val bundle = defaults ?: runCatching {
                CarrierConfigManager::class.java.getMethod("getDefaultConfig").invoke(null) as PersistableBundle
            }.getOrNull()?.also { defaults = it } ?: return DefaultType.Absent
            if (!bundle.containsKey(key)) return DefaultType.Absent
            @Suppress("DEPRECATION")
            val value = bundle.get(key) ?: return DefaultType.Null
            return DefaultType.Of(if (value is PersistableBundle) KeyType.BUNDLE else Types.typeOfAndroid(value))
        }

        override fun activeSims(): List<ActiveSim>? = runCatching {
            context.getSystemService(SubscriptionManager::class.java)!!.activeSubscriptionInfoList.orEmpty().map { info: SubscriptionInfo ->
                ActiveSim(info.subscriptionId, info.simSlotIndex, AndroidSims.of(context, info.subscriptionId) ?: error("SIM ${info.subscriptionId} unreadable"))
            }
        }.getOrNull()

        override fun phoneState(): PhoneState = phoneState(context)

        override fun recentCall(): String? {
            val elapsed = SystemClock.elapsedRealtime()
            if (callEndedElapsed > 0 && elapsed - callEndedElapsed < Guard.WINDOW_MS) return "call_ended_10min"
            return null
        }

        override fun recentEmergency(): Boolean {
            val elapsed = SystemClock.elapsedRealtime()
            return emergencyElapsed > 0 && elapsed - emergencyElapsed < Guard.EMERGENCY_WINDOW_MS
        }

        override fun roaming(subId: Int, simMcc: String): Boolean? = roaming(context, subId, simMcc)

        private val carriers: Uri = Uri.parse("content://telephony/carriers")

        override fun apnTrialInPlace(): Boolean? = runCatching {
            context.contentResolver.query(carriers, arrayOf("name"), "name LIKE ?", arrayOf("% (OCD Check test)"), null)?.use { it.count > 0 }
        }.getOrNull()

        override fun queryCarriers(): List<ApnLogic.DbRow>? = runCatching {
            val columns = arrayOf("mcc", "mnc", "apn", "type", "mvno_type", "mvno_match_data", "protocol", "roaming_protocol", "carrier_id", "edited", "name")
            context.contentResolver.query(carriers, columns, null, null, null)?.use { c ->
                val out = ArrayList<ApnLogic.DbRow>(c.count)
                fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
                fun n(i: Int) = if (c.isNull(i)) null else c.getInt(i)
                while (c.moveToNext()) out += ApnLogic.DbRow(s(0), s(1), s(2), s(3), s(4), s(5), s(6), s(7), n(8), n(9), s(10))
                out
            }
        }.onFailure { Log.e(TAG, "TelephonyProvider could not be read", it) }.getOrNull()

        override fun sendApnUpdate(name: String, version: Long, requiredHash: String): Boolean = runCatching {
            val intent = Intent("com.android.internal.intent.action.UPDATE_APN_DB")
                .setComponent(ComponentName("android", "com.android.server.updates.ApnDbInstallReceiver"))
                .setDataAndType(Uri.parse("content://${Protocol.APN_AUTHORITY}/apns/$name"), "application/xml")
                .putExtra("VERSION", version.toString())
                .putExtra("REQUIRED_HASH", requiredHash)
            context.sendBroadcast(intent)
            Log.i(TAG, "UPDATE_APN_DB sent: $name, VERSION $version, REQUIRED_HASH ${requiredHash.take(12)}")
            true
        }.onFailure { Log.e(TAG, "UPDATE_APN_DB not sent", it) }.getOrDefault(false)

        override fun notifyConfigChanged(subId: Int): Boolean = runCatching {
            context.getSystemService(CarrierConfigManager::class.java)!!.notifyConfigChangedForSubId(subId)
            true
        }.onFailure { Log.e(TAG, "notifyConfigChangedForSubId($subId) failed", it) }.getOrDefault(false)

        override fun effectiveVersion(subId: Int): String? = runCatching {
            val key = CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING
            context.getSystemService(CarrierConfigManager::class.java)!!.getConfigForSubId(subId, key).getString(key) ?: ""
        }.getOrNull()

        override fun armRetry(delayMs: Long) = armRetry(context, delayMs)

        override fun armDaily() = armDaily(context, force = false)

        override fun runOnWorker(task: () -> Unit) = OcdAndroid.runOnWorker(task)

        override fun log(message: String) {
            Log.i(TAG, message)
        }

        override fun writer(): Writer = Writer(writerPackage(context), writerState(context))

        override fun deviceOverride(sim: SimId?): Map<String, OverrideValue>? = DeviceOverride.effective(context, sim)

        private fun preferUri(subId: Int): Uri = Uri.parse("content://telephony/carriers/preferapn/subId/$subId")

        /** A row's fields as strings, without `_id`, user and password (credentials are never kept). */
        private fun fieldsOf(c: android.database.Cursor): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                if (name == "_id" || name == "user" || name == "password") continue
                out[name] = if (c.isNull(i)) "" else runCatching { c.getString(i) }.getOrDefault("")
            }
            return out
        }

        override fun preferredApn(subId: Int): PreferredRead = runCatching {
            context.contentResolver.query(preferUri(subId), null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return PreferredRead.None
                val id = c.getColumnIndex("_id").takeIf { it >= 0 }?.let { c.getLong(it) } ?: return PreferredRead.Unreadable
                PreferredRead.Row(id, fieldsOf(c))
            } ?: PreferredRead.Unreadable
        }.getOrDefault(PreferredRead.Unreadable)

        override fun setPreferredApn(subId: Int, rowId: Long): Boolean = runCatching {
            context.contentResolver.update(preferUri(subId), ContentValues().apply { put("apn_id", rowId) }, null, null)
            Log.i(TAG, "sub $subId: the preferred APN is set again")
            true
        }.onFailure { Log.e(TAG, "sub $subId: the preferred APN could not be set", it) }.getOrDefault(false)

        override fun carrierRows(): List<Pair<Long, Map<String, String>>>? = runCatching {
            context.contentResolver.query(carriers, null, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow("_id")
                val out = ArrayList<Pair<Long, Map<String, String>>>(c.count)
                while (c.moveToNext()) out += c.getLong(idCol) to fieldsOf(c)
                out
            }
        }.onFailure { Log.e(TAG, "TelephonyProvider could not be read", it) }.getOrNull()

        /** IMS registration state and the MmTel capabilities available per registration technology. */
        override fun imsSnapshot(subId: Int): String? = runCatching {
            val mmtel = context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId) ?: return null
            val state = AtomicInteger(-1)
            val latch = CountDownLatch(1)
            mmtel.getRegistrationState(callbacks) { s ->
                state.set(s)
                latch.countDown()
            }
            if (!latch.await(3, TimeUnit.SECONDS)) return null
            val available = mmtel.javaClass.getMethod("isAvailable", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val caps = ArrayList<String>()
            for ((cap, capName) in listOf(1 to "voice", 2 to "video", 4 to "ut", 8 to "sms")) {
                for ((tech, techName) in listOf(0 to "lte", 1 to "iwlan", 3 to "nr")) {
                    if (available.invoke(mmtel, cap, tech) == true) caps += "$capName@$techName"
                }
            }
            "registration=${state.get()};" + caps.sorted().joinToString(",")
        }.onFailure { Log.w(TAG, "sub $subId: IMS state unreadable: $it") }.getOrNull()

        override fun mobileDataValidated(subId: Int): Boolean? = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val mine = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId == subId
            }
            if (mine.isEmpty()) null else mine.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        }.getOrNull()

        override fun notifyOwner(title: String, text: String) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open Carrier Data stop events", NotificationManager.IMPORTANCE_HIGH))
                val n = Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .build()
                nm.notify(1, n)
            }.onFailure { Log.w(TAG, "the stop-event notification was not posted: $it") }
        }
    }

    /**
     * CARRIER_CONFIG_CHANGED: first, for any SIM or none, whether an APN candidate set's SIM is
     * still in the phone (round 32: a SIM change restores it); then the crash guard, for a loaded
     * SIM only (a PIN-locked or absent SIM is not compared).
     */
    fun onConfigChanged(context: Context, subId: Int) {
        runCatching { engine(context).onSimChange("carrier config changed") }.onFailure { Log.e(TAG, "the candidate set's SIM was not checked", it) }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId) ?: return
        if (runCatching { tm.simState }.getOrNull() != TelephonyManager.SIM_STATE_READY) return
        val sim = AndroidSims.of(context, subId) ?: return
        engine(context).onConfigChanged(subId, sim)
    }

    fun onCallState(context: Context, idle: Boolean) {
        val emergency = if (idle) null else inEmergencyCall(context)
        val elapsed = SystemClock.elapsedRealtime()
        if (idle) callEndedElapsed = elapsed
        if (!idle && emergency != false) emergencyElapsed = elapsed
        engine(context).onCallState(idle, emergency)
    }
}

/** One active SIM's identifiers, in memory only. */
object AndroidSims {
    /** GID2 is not read here (no public getter); only OCD profiles use it, and loads get it from CarrierIdentifier. */
    fun of(context: Context, subId: Int): SimId? = runCatching {
        val info = context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId) ?: return null
        val t = context.getSystemService(TelephonyManager::class.java)!!.createForSubscriptionId(subId)
        val op = t.simOperator.orEmpty()
        SimId(op.take(3), if (op.length > 3) op.substring(3) else "", t.simOperatorName, runCatching { t.subscriberId }.getOrNull(), runCatching { t.groupIdLevel1 }.getOrNull(), null, info.iccId, t.simCarrierId, t.simSpecificCarrierId,
            OcdAndroid.carrierIdTable(context))
    }.getOrNull()
}

/**
 * The phone's own Android carrier ID table, read from TelephonyProvider's CarrierIdProvider
 * (`content://carrier_id/all`, which needs READ_PRIVILEGED_PHONE_STATE, granted to this app):
 * the rules behind a profile's carrier-ID selector weigh it in the resolution order (OCD rule
 * round 45, item 3). One query per network code, kept for the process; a failed read is not kept
 * and is read again next time. A carrier ID or a network code is no SIM identifier, so the log
 * may name them.
 */
class AndroidCarrierIdTable(private val context: Context) : CarrierIdTable {
    private val byNetwork = ConcurrentHashMap<String, Map<Int, List<Int>>>()
    private val logged = ConcurrentHashMap.newKeySet<String>()

    override fun ruleWeights(carrierId: Int, mccmnc: String): List<Int>? {
        val rules = byNetwork[mccmnc] ?: read(mccmnc)?.also { byNetwork[mccmnc] = it } ?: return null
        return rules[carrierId].orEmpty()
    }

    private fun read(mccmnc: String): Map<Int, List<Int>>? = runCatching {
        context.contentResolver.query(ALL, COLUMNS, "mccmnc=?", arrayOf(mccmnc), null)?.use { c ->
            val id = c.getColumnIndexOrThrow("carrier_id")
            val columns = CarrierIdTable.COLUMN_WEIGHTS.keys.associateWith { c.getColumnIndexOrThrow(it) }
            val out = HashMap<Int, MutableList<Int>>()
            while (c.moveToNext()) {
                val present = columns.filterValues { !c.isNull(it) }.keys
                out.getOrPut(c.getInt(id)) { ArrayList() } += CarrierIdTable.weightOf(present)
            }
            out
        }
    }.onFailure { Log.w(OcdAndroid.TAG, "the carrier ID table could not be read for $mccmnc: $it") }.getOrNull()

    override fun fallback(carrierId: Int, mccmnc: String, unreadable: Boolean) {
        if (!logged.add("$carrierId $mccmnc $unreadable")) return
        Log.i(
            OcdAndroid.TAG,
            if (unreadable) "carrier ID $carrierId on $mccmnc: the carrier ID table is unreadable, the selector counts at the MCC/MNC-only weight"
            else "carrier ID $carrierId on $mccmnc: no rule in the phone's carrier ID table, the selector counts at the MCC/MNC-only weight",
        )
    }

    companion object {
        val ALL: Uri = Uri.parse("content://carrier_id/all")
        val COLUMNS = arrayOf("carrier_id") + CarrierIdTable.COLUMN_WEIGHTS.keys.toTypedArray()
    }
}
