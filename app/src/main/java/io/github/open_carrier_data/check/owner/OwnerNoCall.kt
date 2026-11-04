package io.github.open_carrier_data.check.owner

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.telephony.CarrierConfigManager
import io.github.open_carrier_data.check.owner.core.CrashCount
import io.github.open_carrier_data.check.owner.core.NoCallChecks
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.QosCheck
import io.github.open_carrier_data.check.owner.core.StackDump
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update

/** Round 49, item 2 on the phone: reads for the checks without a call ([NoCallChecks]). Nothing here sends or changes anything. */
object OwnerNoCall {
    /** The light check's part: the QoS state per SIM and the crash counters. Only a change is written. */
    suspend fun check(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>) {
        val text = runCatching { phone.imsStackText() }.getOrNull()
        val state = store.read()
        val radio = NoCallChecks.radioOf(state.noCall.radio, OwnerBasis.current(context, state).rom)
        val qos = sims.map { sim -> QosCheck.of(sim.key, StackDump.parse(text, sim.slot), ccm(context, sim.subId), radio) }
        val crashes = crashes(context)
        if (qos != state.noCall.qos || crashes != state.noCall.crashes) store.update { it.copy(noCall = it.noCall.copy(qos = qos, crashes = crashes)) }
    }

    /** CarrierConfigManager's layer for the named keys; null when it cannot be read, so a key that needs it stays unknown. */
    private fun ccm(context: Context, subId: Int): Map<String, Boolean>? = runCatching {
        val bundle = context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(subId) ?: return null
        QosCheck.KEYS.filter(bundle::containsKey).associateWith { bundle.getBoolean(it) }
    }.getOrNull()

    /**
     * The exit records of Iwlan and ImsStack. DUMP opens another package's records (ActivityManagerService
     * enforceDumpPermissionForPackage, read in the lineage-23.2 source), and no device log is read. Only the reason and
     * the time of each record are looked at, never its description or trace. A package this phone lacks, or a refused
     * read, stays unknown.
     */
    private fun crashes(context: Context): List<CrashCount> {
        val since = System.currentTimeMillis() - NoCallChecks.CRASH_WINDOW_MS
        return NoCallChecks.CRASH_PACKAGES.map { (tag, name) ->
            val count = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else runCatching {
                context.packageManager.getPackageInfo(name, 0)
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(name, 0, MAX_RECORDS)
                    .count { it.timestamp >= since && (it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) }
            }.getOrNull()
            CrashCount(tag, count)
        }
    }

    private const val MAX_RECORDS = 32
}
