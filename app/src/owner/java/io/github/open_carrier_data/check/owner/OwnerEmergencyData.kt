package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.telephony.AccessNetworkConstants
import android.telephony.PreciseDataConnectionState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import android.util.Log
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.EimsAfter
import io.github.open_carrier_data.check.owner.core.EimsAttempt
import io.github.open_carrier_data.check.owner.core.EimsInputs
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.EmergencyDump
import io.github.open_carrier_data.check.owner.core.EmergencyFirstUse
import io.github.open_carrier_data.check.owner.core.EmergencySession
import io.github.open_carrier_data.check.owner.core.EmergencySetJobs
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.ExplainRow
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.Notice
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.ProviderReason
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.owner.core.RegistryText
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The emergency data test on the phone (OCD round 30, "emergencyDataTest"): a request for
 * NET_CAPABILITY_EIMS on the SIM's home mobile network, with the subscription's network specifier,
 * that waits at most 15 s and is released within 5 s of CONNECTED. It needs
 * CONNECTIVITY_USE_RESTRICTED_NETWORKS (the owner build's privapp file). It places no call, dials
 * no number, sends nothing, and starts no emergency registration; releasing the app's own request
 * leaves any other request, such as a real emergency call's, untouched.
 *
 * Every step goes to the device log (tag [TAG]) for the first use, which the owner runs by hand
 * with `adb logcat` streamed. Until the owner records that the first use passed, the app never runs
 * the test on its own and writes no record.
 */
object OwnerEmergencyData {
    const val TAG = "OcdCheckEims"
    private const val NO_STATE = Int.MIN_VALUE

    private fun log(store: StateStore, text: String) {
        runCatching { Log.i(TAG, text) }
        store.update { it.logged(System.currentTimeMillis(), "emergency_data", text) }
    }

    /**
     * One session on [sim]: the row's request, and (only after a counted failure) Android's default through round 31's
     * temporary set (CarrierSettings installs the installed commit's APN file without this SIM's emergency rows, the
     * request goes out with Android's default `sos`, CarrierSettings restores the full set, or keeps the set without the
     * row for this cell when the default won). [manual]: the owner's (or a ROM builder's) run over adb, the first use;
     * it skips only the due rules and the build switch, never a condition. Returns the log line, or null when nothing
     * was due.
     */
    suspend fun session(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sim: OwnerSim, manual: Boolean): String? {
        var cycle: DefaultCycle? = null
        try {
            return sessionBody(context, store, phone, client, sim, manual) { cycle = it }
        } finally {
            // Whatever ended the session (a cancelled worker, an error), the temporary set never stays in place by the app's doing.
            val c = cycle
            if (c != null && c.placed && !c.restoreAttempted) withContext(NonCancellable) { runCatching { c.restore(keep = false) } }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sessionBody(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sim: OwnerSim, manual: Boolean, onCycle: (DefaultCycle) -> Unit): String? {
        val state = store.read()
        val firstUse = state.emergencyFirstUse
        val status = OwnerDatasetStep.status(context, store)
        if (!status.available) return "emergency data test: no dataset installer on this ROM"
        // Round 31 (SM9, SM10): in a ROM build only with the person's opt-in and the ROM builder's switch; the owner build after its first use.
        if (!manual) OwnerTester.policy(state).emergencyRefusal(status.emergencyTestOn, firstUse?.passed == true)?.let { return null }
        if (firstUse?.result == EmergencyFirstUse.NOT_MEANINGFUL && !manual) return null
        if (!OwnerAccess.restrictedNetworksGranted(context)) return "emergency data test not run: this build does not grant CONNECTIVITY_USE_RESTRICTED_NETWORKS"
        val explain = ExplainView.parse(sim.subId, client.explain(sim.subId)) ?: return "emergency data test: explain not readable"
        val row: ExplainRow? = explain.emergencyRow
        val basis = EmergencyData.basis(OwnerBasis.current(context, state).rom, status.build.imsStackId, status.build.imsStackVersion, runCatching { Build.getRadioVersion() }.getOrNull())
        val now = System.currentTimeMillis()
        if (!manual) EmergencyData.due(state.emergencySessions, sim.key, row?.rowKey, basis, now) ?: return null
        val session = OwnerSession(phone, store, sim)
        val inputs = inputs(context, store, phone, session, sim, status, now)
        val refusal = EmergencyData.refusal(inputs)
        if (refusal != null) {
            store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "refused", now))) }
            log(store, "slot ${sim.slot + 1}: not run: $refusal")
            return "emergency data test not run: $refusal"
        }
        val imsBefore = imsLine(phone, sim)
        log(store, "slot ${sim.slot + 1}: requesting the emergency data network (EIMS) with ${row?.let { "row ${it.rowKey} ${it.apn} (${it.protocol})" } ?: "Android's default sos profile"}; no call, nothing sent, released within 5 s")
        val attempt = request(context, sim.subId, row)
        val protocol = row?.protocol?.ifEmpty { "IPV4V6" } ?: "IPV4V6"
        val verdict = EmergencyData.classify(attempt, protocol)
        log(store, "slot ${sim.slot + 1}: ${verdict.wire}: ${attemptLine(attempt)}")
        delay(3_000)
        val after = after(context, phone, session, sim, imsBefore)
        EmergencyData.stopEvent(attempt, after, verdict)?.let { why -> return stop(context, store, client, sim, row, basis, now, why) }
        if (verdict == EmergencyData.Verdict.WIFI) {
            store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "not_counted", now))) }
            return "emergency data connection came up on Wi-Fi: released at once, nothing recorded"
        }
        // Android's default is tested only after a counted failure of the SIM's own row, in the same session (round 31:
        // through the temporary set; while the row is selected, Telephony always uses it).
        var default: EmergencyData.Verdict? = if (verdict == EmergencyData.Verdict.COUNTED_FAIL && row == null) verdict else null
        var defaultAttempt: EimsAttempt? = null
        var notRun: String? = null
        var cycle: DefaultCycle? = null
        if (verdict == EmergencyData.Verdict.COUNTED_FAIL && row != null) {
            log(store, "slot ${sim.slot + 1}: the row was refused (${EmergencyData.COUNTED_CAUSES[attempt.failCause]}); Android's default is tested through the temporary set without the row")
            cycle = DefaultCycle(context, store, phone, client, sim, explain.rows.filter { it.emergency || "emergency" in it.types }.map { it.rowKey }.toSet())
            onCycle(cycle)
            val placed = cycle.install()
            if (placed != null) {
                notRun = placed
            } else if (cycle.sosShown(30_000) != true) {
                notRun = "not counted: DataProfileManager did not show Android's default sos profile while the set was in place"
            } else {
                // The refusals again: the set's install took time, and a call or an emergency state may have come up.
                EmergencyData.refusal(inputs(context, store, phone, session, sim, status, System.currentTimeMillis()))?.let { notRun = "not run: $it" }
                if (notRun == null) {
                    log(store, "slot ${sim.slot + 1}: requesting EIMS with Android's default sos profile while the set is in place; no call, nothing sent, released within 5 s")
                    val a = request(context, sim.subId, null)
                    defaultAttempt = a
                    default = EmergencyData.classify(a, "IPV4V6")
                    log(store, "slot ${sim.slot + 1}: Android's default: ${default.wire}: ${attemptLine(a)}")
                    delay(3_000)
                    val afterDefault = after(context, phone, session, sim, imsBefore)
                    EmergencyData.stopEvent(a, afterDefault, default)?.let { why ->
                        cycle.restore(keep = false)
                        return stop(context, store, client, sim, row, basis, now, why)
                    }
                }
            }
            notRun?.let { log(store, "slot ${sim.slot + 1}: Android's default $it") }
        }
        val result = EmergencyData.result(verdict, default)
        val outcome = when {
            result == RecordResult.EMERGENCY_DATA_ACCEPTED -> "passed"
            result == RecordResult.EMERGENCY_DEFAULT_WON -> "default_won"
            result == RecordResult.EMERGENCY_DATA_REFUSED -> "both_failed"
            verdict == EmergencyData.Verdict.COUNTED_FAIL -> "failed"
            else -> "not_counted"
        }
        store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), outcome, now))) }
        // Records only after a first use passed: the owner's own, or the ROM builder's declared by the build (SM10).
        val recordsAllowed = store.read().emergencyFirstUse?.passed == true || status.emergencyTestOn
        var line: String? = null
        if (result != null && recordsAllowed) {
            val cell = RecordCell.of(status.build)
            val carrier = RecordCarrier.of(explain, sim.identity.mccmnc.take(3), sim.identity.mccmnc.drop(3), sim.identity.spn?.trim(), sim.identity.gid1)
            if (cell == null || carrier == null) {
                line = "no record (${if (cell == null) "no cell" else "no SIM key"})"
            } else {
                val families = (if (result == RecordResult.EMERGENCY_DEFAULT_WON) defaultAttempt?.families else attempt.families).orEmpty()
                val record = MeasuredRecord.build(
                    RecordInput(
                        cell, carrier, RecordTarget.EmergencyData(row?.rowKey), RecordValue.None, result, "emergency_data", "eims_request",
                        if (inputs.dataRat == "nr") "nr" else "lte", if (default != null) 2 else 1, now, now,
                        EmergencyData.recordBasis(result, row?.apn, if (result == RecordResult.EMERGENCY_DEFAULT_WON) "IPV4V6" else protocol, families, attempt.failCause),
                        (if (status.servingInstalled) status.installed else status.buildPin)?.commit.orEmpty(),
                        (if (status.servingInstalled) status.installed else status.buildPin)?.dataDigest.orEmpty(),
                    ),
                )
                line = OwnerDatasetStep.putRecord(store, client, record, emptyList(), null, emptySet())
            }
        }
        // The restore: the row back at once, or, when Android's default won and its record is stored, the set without the row for this cell.
        cycle?.takeIf { it.placed }?.let { c ->
            // Kept without the row only when Android's default won and CarrierSettings stored its record (R31.9).
            val stored = line?.let { it.startsWith("record r:") && "refused by CarrierSettings" !in it } == true
            val keep = result == RecordResult.EMERGENCY_DEFAULT_WON && stored
            val restored = c.restore(keep)
            line = listOfNotNull(line, restored).joinToString("; ")
        }
        if (result == RecordResult.EMERGENCY_DEFAULT_WON && !status.round31) {
            val a = client.applyApnRecords()
            log(store, "apply_apn_records after the default won: ${if (a.ok) "job ${a.str("job_id")}" else a.line}")
        }
        OwnerDatasetStep.publish(context, store)
        val noRecord = if (result != null && !recordsAllowed) " (no record before a first use is recorded as passed)" else ""
        return "emergency data test: $outcome$noRecord" + (notRun?.let { "; Android's default $it" } ?: "") + (line?.let { "; $it" } ?: "")
    }

    /**
     * Round 31's first use of the default emergency APN cycle (defaultEmergencyApnTest), run by hand over adb with
     * `adb logcat` streamed: the temporary set's install, the passive check that DataProfileManager shows Android's
     * default `sos` profile while it is in place, and the restore with its proof. No EIMS request, no record.
     */
    @SuppressLint("MissingPermission")
    suspend fun defaultFirstUse(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sim: OwnerSim): String {
        val status = OwnerDatasetStep.status(context, store)
        if (!status.round31) return "default APN first use: not run (${if (status.available) "this ROM build has no temporary emergency set" else "no dataset installer"})"
        val session = OwnerSession(phone, store, sim)
        val now = System.currentTimeMillis()
        val inputs = inputs(context, store, phone, session, sim, status, now)
        EmergencyData.firstUseRefusal(inputs)?.let {
            log(store, "default APN first use, slot ${sim.slot + 1}: not run: $it")
            return "default APN first use: not run: $it"
        }
        val explain = ExplainView.parse(sim.subId, client.explain(sim.subId))
        val row = explain?.emergencyRow
        val imsBefore = imsLine(phone, sim)
        val dataBefore = phone.mobileData(sim.subId).validated
        log(store, "default APN first use, slot ${sim.slot + 1}: before: emergency row ${row?.let { "${it.rowKey} ${it.apn} (${it.protocol})" } ?: "none"}, IMS $imsBefore, mobile data validated $dataBefore; no EIMS request in this run")
        val cycle = DefaultCycle(context, store, phone, client, sim, explain?.rows.orEmpty().filter { it.emergency || "emergency" in it.types }.map { it.rowKey }.toSet())
        val sos: Boolean?
        val restored: String
        try {
            cycle.install()?.let { why ->
                log(store, "default APN first use, slot ${sim.slot + 1}: FAILED: the set did not go in place: $why")
                return "default APN first use: failed: $why"
            }
            sos = cycle.sosShown(60_000)
            log(store, "default APN first use, slot ${sim.slot + 1}: DataProfileManager shows Android's default sos profile: ${sos ?: "not readable"}")
            restored = cycle.restore(keep = false)
        } finally {
            if (cycle.placed && !cycle.restoreAttempted) withContext(NonCancellable) { runCatching { cycle.restore(keep = false) } }
        }
        val dataAfter = waitFor(60_000) { phone.mobileData(sim.subId).validated == true }
        // IMS may register again after the restore's install: up to 2 minutes for the same state as before.
        waitFor(120_000) { imsLine(phone, sim) == imsBefore }
        val imsAfter = imsLine(phone, sim)
        val checks = listOf(
            "only the emergency row removed" to cycle.removedOnlyEmergency,
            "Android's default sos profile shown while in place" to (sos == true),
            "the row, the row count and the preferred APN back" to (cycle.restoredState == InstallState.RESTORED && cycle.restoreChecksOk),
            "mobile data validated" to (dataBefore != true || dataAfter),
            "IMS registered with the same services" to (imsBefore != null && imsBefore == imsAfter),
        )
        val passed = checks.all { it.second }
        val line = "default APN first use, slot ${sim.slot + 1}: ${if (passed) "PASSED" else "FAILED"}: " + checks.joinToString { "${it.first} ${if (it.second) "yes" else "no"}" } + "; $restored; IMS after $imsAfter"
        log(store, line)
        return line
    }

    /** The inputs of the never-run conditions and the preconditions, read now. */
    private suspend fun inputs(context: Context, store: StateStore, phone: OwnerPhone, session: OwnerSession, sim: OwnerSim, status: io.github.open_carrier_data.check.owner.core.DatasetStatus, now: Long): EimsInputs {
        val state = store.read()
        val safety = runCatching { session.safety() }.getOrNull()
        val o = runCatching { session.observe() }.getOrNull()
        val registry = phone.registryText()
        val telephony = phone.telephonyText()
        val rat = when (o?.ps?.rat) {
            "lte", "lte_ca" -> "lte"
            "nr" -> "nr"
            else -> o?.ps?.rat
        }
        val calls = runCatching { phone.callsSeen() }.getOrNull()
        return EimsInputs(
            inCall = QuietReader.inCall(context, registry),
            // Any call, the app's own mailbox calls included (round 30 item 5: never within 10 minutes of a call).
            lastCallSeenAtMs = listOfNotNull(state.lastCallSeenAtMs, io.github.open_carrier_data.check.owner.core.RestartWindow.lastCallAtMs(calls, own = true)).maxOrNull(),
            emergencySeenAtMs = state.emergencySeen?.firstSeenAtMs,
            upMs = minOf(SystemClock.elapsedRealtime(), runCatching { session.serverUpMs() }.getOrNull() ?: Long.MAX_VALUE),
            ecbm = safety?.emergency?.ecbm,
            emergencySmsMode = safety?.emergency?.emergencySmsMode,
            roaming = safety?.roaming?.roaming?.let { it || o?.ps?.state == "roaming" },
            eimsExists = eimsNetwork(context),
            dataRat = rat,
            emcSupported = EmergencyDump.emcSupported(RegistryText.block(registry.orEmpty(), sim.slot), rat),
            dataWorks = phone.mobileData(sim.subId).validated,
            honorRetryTimer = EmergencyDump.honorRetryTimer(telephony, sim.slot),
            emergencyTransport = EmergencyDump.emergencyTransport(telephony, sim.slot),
            stopped = state.emergencyStop ?: state.stop ?: status.changesHalted.reason?.takeIf { status.changesHalted.on }
                ?: "the voice-loss hold or stop".takeIf { status.voiceLoss.hold || status.voiceLoss.stop }
                ?: "the emergency default test set is in place".takeIf { status.apnTestSet },
            nowMs = now,
            callsSeen = calls,
        )
    }

    private suspend fun imsLine(phone: OwnerPhone, sim: OwnerSim): String? =
        phone.imsApi(sim.slot, sim.subId)?.let { "${it.state().registered}/${it.state().logTransport}/${it.state().services.sortedBy { s -> s.wire }}" }

    private fun attemptLine(a: EimsAttempt): String =
        "connected=${a.connected} wwan=${a.cellular} profile=${a.profileMatches} families=${a.families.sorted()} cause=${a.failCause} released_in_time=${a.releasedInTime}${if (a.aborted) " (aborted: a call state changed)" else ""}"

    private suspend fun waitFor(maxMs: Long, check: suspend () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (check()) return true
            if (waited >= maxMs) return false
            delay(5_000)
            waited += 5_000
        }
    }

    /** A stop event of the test: it stops until a judged round; tests and changes pause until Resume. */
    private fun stop(context: Context, store: StateStore, client: DatasetClient, sim: OwnerSim, row: ExplainRow?, basis: String, now: Long, why: String): String {
        store.update { s ->
            s.copy(emergencyStop = why, stop = s.stop ?: "emergency_data_test: $why", emergencySessions = EmergencyData.add(s.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "stopped", now)))
        }
        client.stop("emergency data test: $why")
        log(store, "STOP slot ${sim.slot + 1}: $why; the test stops until a judged round")
        OwnerNotifications.notice(context, Notice("Emergency data test stopped", "$why. Tests and changes are paused until you tap Resume; the test itself stays stopped until a judged round.", failed = true))
        return "STOP: $why"
    }

    /**
     * Round 31's temporary emergency-less APN set (R31.9) for one SIM: CarrierSettings composes, checks, installs and
     * confirms it ([install]); the app checks Android's default profile ([sosShown]); CarrierSettings restores the full
     * set, or keeps the set without the row ([restore]). Both installs count toward the re-registration caps.
     */
    private class DefaultCycle(
        private val context: Context,
        private val store: StateStore,
        private val phone: OwnerPhone,
        private val client: DatasetClient,
        private val sim: OwnerSim,
        /** The SIM's selected emergency rows, which alone may leave the set. */
        private val emergencyRows: Set<String>,
    ) {
        var placed = false
            private set
        var removedOnlyEmergency = false
            private set
        var restoredState: String? = null
            private set
        var restoreChecksOk = false
            private set
        var restoreAttempted = false
            private set

        private fun count() = store.update { it.copy(caps = io.github.open_carrier_data.check.owner.core.TestCaps.add(it.caps, sim.key, io.github.open_carrier_data.check.owner.core.CapEvent.REREGISTRATION, System.currentTimeMillis())) }

        /** CarrierSettings's jobs are followed on the elapsed clock (0.4.4, [EmergencySetJobs]). */
        private val clock = object : io.github.open_carrier_data.check.owner.core.JobClock {
            override fun uptimeMs(): Long = android.os.SystemClock.elapsedRealtime()
            override suspend fun sleep(ms: Long) = delay(ms)
        }

        /**
         * Null when the set is in place; else why not (the session's "not run"). 0.4.4: the install is followed until
         * CarrierSettings reports its end, within its own deadline less the margin and the session; when the app gives up
         * it asks for the restore at once ([EmergencySetJobs.set]).
         */
        suspend fun install(): String? {
            val end = EmergencySetJobs.set(client, sim.subId, clock, placed = { count() }, restore = { until -> restoreFollowed(keep = false, retryBusyUntil = until) })
            end.refusal?.let { a ->
                log(store, "slot ${sim.slot + 1}: emergency_default_set refused (${ProviderReason.of(a.error ?: "no answer", a.str(OcdDataProtocol.REASON))})")
                return end.notRun
            }
            val view = end.view ?: return end.notRun
            val removed = view.arr("removed_rows").orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: (it as? kotlinx.serialization.json.JsonObject)?.let { o -> io.github.open_carrier_data.check.owner.core.DatasetStatus.s(o, "row_key") } }
            log(store, "slot ${sim.slot + 1}: the emergency default test set: ${view.str("state") ?: "no answer"}; removed rows ${removed.ifEmpty { listOf("none") }}, rows ${view.long("rows") ?: "?"}, preferred APN recorded ${view.bool("preferred_apn_recorded") ?: "?"}")
            if (!end.inPlace) return end.notRun
            placed = true
            removedOnlyEmergency = removed.isNotEmpty() && (emergencyRows.isEmpty() || emergencyRows.containsAll(removed))
            return null
        }

        /** Whether DataProfileManager shows Android's default sos profile for the SIM within [maxMs]; null when unreadable. */
        suspend fun sosShown(maxMs: Long): Boolean? {
            var waited = 0L
            var last: Boolean? = null
            while (true) {
                last = EmergencyDump.defaultSosProfile(phone.telephonyText(), sim.slot)
                if (last == true || waited >= maxMs) return last
                delay(5_000)
                waited += 5_000
            }
        }

        /**
         * The restore, followed until it ends ([EmergencySetJobs.restore], 0.4.4: its install and its checks' wait;
         * CarrierSettings finishes a waiting restore on its own). [retryBusyUntil]: the app gave up on the set's install
         * and asks for the restore at once, again while CarrierSettings still installs, until that uptime.
         */
        suspend fun restore(keep: Boolean): String = restoreFollowed(keep, null).line

        private suspend fun restoreFollowed(keep: Boolean, retryBusyUntil: Long?): EmergencySetJobs.RestoreEnd {
            if (!placed && retryBusyUntil == null) return EmergencySetJobs.RestoreEnd(null, null, null, "nothing to restore")
            restoreAttempted = true
            val r = EmergencySetJobs.restore(client, clock, keep, accepted = { count() }, retryBusyUntil = retryBusyUntil)
            if (!r.accepted) {
                log(store, "slot ${sim.slot + 1}: emergency_default_${r.line}")
                return r
            }
            val view = r.view
            val state = r.state ?: "no answer"
            restoredState = state
            val failed = view?.arr("checks").orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }.filter { io.github.open_carrier_data.check.owner.core.DatasetStatus.bool(it, "ok") == false }
                .map { "${io.github.open_carrier_data.check.owner.core.DatasetStatus.s(it, "check")}: ${io.github.open_carrier_data.check.owner.core.DatasetStatus.s(it, "detail").orEmpty().take(80)}" }
            restoreChecksOk = state in setOf(InstallState.RESTORED, InstallState.KEPT) && failed.isEmpty()
            val line = "the emergency default test set ${if (keep) "kept without the row for this cell" else "restored"}: $state" +
                (view?.long("rows")?.let { ", rows $it" } ?: "") + (if (failed.isEmpty()) "" else "; failed checks: ${failed.joinToString()}") +
                (if (r.timedOut) "; ${r.line}" else "")
            log(store, "slot ${sim.slot + 1}: $line")
            if (state == InstallState.RESTORE_FAILED) {
                // CarrierSettings halted changes and installed the last good set (or the build's APN file); a stop event here too.
                store.update { it.copy(stop = it.stop ?: "the emergency default test set could not be restored").logged(System.currentTimeMillis(), "stop", "the emergency default test set could not be restored: ${view?.str("error") ?: ""}") }
                OwnerNotifications.notice(context, Notice("Access point names restored by CarrierSettings", "The emergency test's temporary access point names did not come back as they were, so CarrierSettings installed the last good set again. Tests and changes are paused until you tap Resume.", failed = true))
            }
            return r.copy(line = line)
        }
    }

    /** Whether an EIMS network exists now (one the app can see with CONNECTIVITY_USE_RESTRICTED_NETWORKS); null when unreadable. */
    @Suppress("DEPRECATION")
    private fun eimsNetwork(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS) == true }
    }.getOrNull()

    /** The request: at most 15 s, released at once after CONNECTED is read (well within 5 s), aborted on any call-state change. */
    @SuppressLint("MissingPermission")
    private suspend fun request(context: Context, subId: Int, row: ExplainRow?): EimsAttempt {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
        if (cm == null || tm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return EimsAttempt(false, null, null, emptySet(), null, null, true)
        val executor = Executors.newSingleThreadExecutor()
        val done = CompletableDeferred<Unit>()
        val initialCallState = AtomicInteger(NO_STATE)
        val abortedRef = AtomicBoolean(false)
        val preciseRef = AtomicReference<PreciseDataConnectionState?>(null)
        val linkRef = AtomicReference<LinkProperties?>(null)
        val connectedRef = AtomicLong(0L)
        val telephony = object : TelephonyCallback(), TelephonyCallback.CallStateListener, TelephonyCallback.PreciseDataConnectionStateListener {
            override fun onCallStateChanged(state: Int) {
                // The first callback is the state at registration; any call at all, or any change after it, aborts.
                if (initialCallState.compareAndSet(NO_STATE, state)) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) {
                        abortedRef.set(true)
                        done.complete(Unit)
                    }
                } else if (state != initialCallState.get()) {
                    abortedRef.set(true)
                    done.complete(Unit)
                }
            }

            override fun onPreciseDataConnectionStateChanged(s: PreciseDataConnectionState) {
                val setting = s.apnSetting ?: return
                if (setting.apnTypeBitmask and ApnSetting.TYPE_EMERGENCY == 0) return
                preciseRef.set(s)
                if (s.state == TelephonyManager.DATA_DISCONNECTED && s.lastCauseCode != 0 && connectedRef.get() == 0L) done.complete(Unit)
            }
        }
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                connectedRef.compareAndSet(0L, SystemClock.elapsedRealtime())
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                linkRef.set(lp)
                if (connectedRef.get() != 0L) done.complete(Unit)
            }

            override fun onUnavailable() {
                done.complete(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
            .build()
        var released = 0L
        try {
            tm.registerTelephonyCallback(executor, telephony)
            cm.requestNetwork(request, network, EmergencyData.WAIT_MS.toInt())
            withTimeoutOrNull(EmergencyData.WAIT_MS + 1_000) { done.await() }
            // CONNECTED but no link properties yet: give them at most one second, then release regardless.
            if (connectedRef.get() != 0L && linkRef.get() == null) withTimeoutOrNull(1_000) { while (linkRef.get() == null) delay(100) }
        } finally {
            runCatching { cm.unregisterNetworkCallback(network) }
            released = SystemClock.elapsedRealtime()
            runCatching { tm.unregisterTelephonyCallback(telephony) }
            executor.shutdown()
        }
        val p = preciseRef.get()
        val link = linkRef.get()
        val connectedAt = connectedRef.get()
        val aborted = abortedRef.get()
        val families = link?.linkAddresses.orEmpty().mapNotNull { a ->
            when (a.address) {
                is Inet4Address -> "ipv4"
                is Inet6Address -> if (a.address.isLinkLocalAddress) null else "ipv6"
                else -> null
            }
        }.toSet()
        val apnName = p?.apnSetting?.apnName.orEmpty()
        val profileMatches = p?.apnSetting?.let { if (row != null) apnName.equals(row.apn, ignoreCase = true) else apnName.equals("sos", ignoreCase = true) }
        val pcscf = link?.let { lp -> runCatching { (LinkProperties::class.java.getMethod("getPcscfServers").invoke(lp) as? Collection<*>)?.isNotEmpty() }.getOrNull() }
        return EimsAttempt(
            connected = connectedAt != 0L,
            cellular = p?.transportType?.let { it == AccessNetworkConstants.TRANSPORT_TYPE_WWAN },
            profileMatches = profileMatches,
            families = families,
            failCause = p?.lastCauseCode?.takeIf { it != 0 },
            dataWorks = null,
            releasedInTime = connectedAt == 0L || released - connectedAt <= EmergencyData.RELEASE_MS,
            aborted = aborted,
            pcscf = pcscf,
        ).let { a ->
            // Ordinary data during the attempt: read after the release (the request does not touch the default network).
            a.copy(dataWorks = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }.getOrNull())
        }
    }

    /** The checks after every attempt (the ruling's list). */
    private suspend fun after(context: Context, phone: OwnerPhone, session: OwnerSession, sim: OwnerSim, imsBefore: String?): EimsAfter {
        val emergency = phone.emergencyApi()
        val registry = phone.registryText()
        val o = runCatching { session.observe() }.getOrNull()
        val imsAfter = phone.imsApi(sim.slot, sim.subId)?.let { "${it.state().registered}/${it.state().logTransport}/${it.state().services.sortedBy { s -> s.wire }}" }
        return EimsAfter(
            imsUnchanged = if (imsBefore == null || imsAfter == null) null else imsBefore == imsAfter,
            ecbm = emergency?.ecbm,
            emergencyCallState = emergency?.inEmergencyCall,
            callUp = QuietReader.inCall(context, registry),
            emergencyThrottle = EmergencyDump.emergencyThrottle(phone.telephonyText(), sim.slot),
            eimsGone = eimsNetwork(context)?.not(),
            serviceOk = o?.ps?.state?.let { it == "home" },
        )
    }
}
