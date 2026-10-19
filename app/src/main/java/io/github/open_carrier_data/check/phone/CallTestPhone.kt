package io.github.open_carrier_data.check.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTargetsFile
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CallTestRecord
import io.github.open_carrier_data.check.core.T2Record
import io.github.open_carrier_data.check.core.UserToggleRecord
import java.io.File

/**
 * The guided call test's reads on the app side. None of them needs a
 * permission beyond the two the app holds, and none changes anything:
 * - the bundled mailbox table (assets/call-targets.json);
 * - Android's answer whether a number is an emergency number
 *   (TelephonyManager.isEmergencyNumber, exact match, which needs no
 *   permission); the list itself is never read;
 * - whether airplane mode is on and whether the default network is Wi-Fi.
 */
class CallTestPhone(private val context: Context) {
    fun targets(): CallTargetsFile? = runCatching {
        context.assets.open(CallTargets.ASSET_NAME).use { CallTargets.parse(it.readBytes().decodeToString()) }
    }.getOrNull()

    /** Throws when Android cannot answer; [CallTargets.mailboxFor] then shows no number. */
    fun isEmergency(number: String): Boolean {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: error("no telephony")
        return telephony.isEmergencyNumber(number)
    }

    fun airplaneMode(): Boolean? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0 }.getOrNull()

    fun onWifi(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrNull()
}

/**
 * Finished call tests, T2 passes and guided switch steps, in the app's
 * private files on this phone. The app has no backup (allowBackup is false)
 * and no internet permission, so the file never leaves the phone; deleting
 * the app or tapping Delete removes it.
 */
class CallTestStore(context: Context) {
    private val file = File(context.filesDir, "call-tests.json")

    fun load(): CallTestLog = runCatching { CallTestLog.decode(file.readText()) }.getOrDefault(CallTestLog())

    fun add(record: CallTestRecord): CallTestLog = update { it.plus(record) }

    fun add(record: T2Record): CallTestLog = update { it.plus(record) }

    fun add(record: UserToggleRecord): CallTestLog = update { it.plus(record) }

    private fun update(change: (CallTestLog) -> CallTestLog): CallTestLog {
        val log = change(load())
        write(log)
        return log
    }

    fun clear(): CallTestLog {
        file.delete()
        return CallTestLog()
    }

    private fun write(log: CallTestLog) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(log.encode())
        temporary.renameTo(file)
    }
}
