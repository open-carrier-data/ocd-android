package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.CallSample
import io.github.open_carrier_data.check.core.CallState
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTargetsFile
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.Mailbox
import io.github.open_carrier_data.check.core.PreciseCallState
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.SimSlot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/*
 * A test call on the person's own tap (round 47, in place of round 26's night call): the
 * app dials only the SIM's own allowlisted mailbox, under the emergency-number check, the
 * service-prefix refusal and the LB6 refusals, and never anything else. Nothing dials by itself.
 * A real outgoing call measured passively counts the same.
 *
 * Every guard runs before the dial, and the dial is one call to Telecom's call placement with
 * that number on the SIM's own phone account:
 * - the number is the SIM's own entry of the bundled mailbox table, of the SIM's own brand
 *   ([CallTargets.dialableFor]), a plain 3 to 6 digit short code, not on the fixed emergency list,
 *   not a service prefix (0, 11x, 19x), not marked unverified, and Android says it is not an
 *   emergency number for the SIM;
 * - the refusals pass (round 25 LB6: no call up, no emergency callback or SMS mode, no emergency
 *   call within 10 minutes, not within 10 minutes of a boot or a system restart, not roaming, all
 *   readable) and the stop flag is clear;
 * - IMS is registered for calls on the bearer under test.
 * The call is not muted: it uses the normal call screen, the person can hang up, and it stays in the
 * call log. While it runs, an emergency mark (the registry's emergency number changing, or Telecom's
 * emergency call) aborts at once and no call is touched. The app ends its call itself after ACTIVE,
 * once round 28's media window can be judged (at least 15 s, and at least 3 CallQuality reports over
 * 10 s: AOSP ImsMedia reports every 5 s; at most 25 s, inside the mailbox's prompts). It never sends a
 * menu digit (round 26 rule change 10).
 *
 * A dry run runs every guard without changing anything and logs what it would dial, then refuses.
 */

/** The Android side of the mailbox call (Telecom); a fake in the tests. */
interface CallDevice {
    /** Android's answer whether [number] is an emergency number, for the SIM and in general. Throws when it cannot answer. */
    suspend fun isEmergencyNumber(number: String, subId: Int): Boolean

    /** Telecom's isInEmergencyCall, through its binder; null when it cannot be read. */
    suspend fun inEmergencyCall(): Boolean?

    /** One call to Telecom's call placement with [number] on the SIM's own phone account; false: not placed. */
    suspend fun place(number: String, subId: Int): Boolean

    /** Telecom's end of the foreground call (Telecom never ends an emergency call this way). */
    suspend fun end(): Boolean

    /** The call is over, placed or not: the passive watch counts every call again. */
    fun callEnded()
}

/** The SIM's own mailbox, or why there is none. */
object MailboxGuard {
    private val SHAPE = Regex("[1-9][0-9]{2,5}")

    /** Why [number] may not be dialled, or null: the shape, the fixed emergency list and the service prefixes. */
    fun refusal(number: String): String? = when {
        !SHAPE.matches(number) -> "not_a_short_code"
        number in CallTargets.EMERGENCY_NUMBERS -> "emergency_number"
        CallTargets.SERVICE_PREFIXES.any { number.startsWith(it) } -> "service_prefix"
        else -> null
    }

    /** The SIM's own allowlisted mailbox under every check, or the reason there is none. */
    suspend fun target(file: CallTargetsFile?, sim: OwnerSim, device: CallDevice): Pair<Mailbox?, String?> {
        file ?: return null to "mailbox_table_unreadable"
        val mccmnc = sim.identity.mccmnc
        val spn = sim.identity.spn
        // The table's own entry for this SIM first (the SPN rule, then the network code), with the fixed checks.
        CallTargets.mailboxFor(file, mccmnc, spn) { false } ?: return null to "no_mailbox_for_this_sim"
        // Round 46: the network's entry is for its own brand; for any other SPN it is display only, even on a tap.
        val candidate = CallTargets.dialableFor(file, mccmnc, spn) { false } ?: return null to "mailbox_for_display_only"
        refusal(candidate.number)?.let { return null to it }
        val emergency = try {
            device.isEmergencyNumber(candidate.number, sim.subId)
        } catch (_: Exception) {
            return null to "emergency_check_unreadable"
        }
        if (emergency) return null to "android_says_emergency_number"
        val mailbox = CallTargets.dialableFor(file, mccmnc, spn) { emergency } ?: return null to "not_allowed"
        if (mailbox.number != candidate.number) return null to "not_allowed"
        if (mailbox.unverified) return null to "mailbox_unverified"
        return mailbox to null
    }
}

/** How one mailbox call went. */
data class MailboxOutcome(
    /** passed, failed, not_counted_retry, refused, dry_run or aborted. */
    val result: String,
    val reason: String? = null,
    val record: CallRecord? = null,
    /** Provisional keeps the call settled to revert: (slot, feature, reason). */
    val reverts: List<Triple<Int, OwnerFeature, String>> = emptyList(),
)

class MailboxCaller(
    private val phone: OwnerPhone,
    private val store: StateStore,
    private val device: CallDevice,
    /** [TestBasis.rom]: the level's software key. */
    private val build: String,
    /** The short build for "re-tested by OCD". */
    private val shortBuild: String,
    private val stack: String,
    private val model: String,
    /** [PointerLedger.cell] of a SIM. */
    private val cellOf: (SimKey) -> String,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val waitActiveMs: Long = 30_000,
        /**
         * How long the call stays up after ACTIVE at least, and at most while
         * round 28's media window is not ready yet (3 reports over 10 s; on
         * AOSP ImsMedia's 5 s reports about 15 s connected).
         */
        val holdMs: Long = 15_000,
        val holdMaxMs: Long = 25_000,
        val endWaitMs: Long = 15_000,
        val pollMs: Long = 1_000,
        /** How long IMS gets to move to LTE inside the Wi-Fi-calling-off override. */
        val moveMs: Long = 120_000,
        val maxMs: Long = 120_000,
    )

    private fun log(text: String) = store.update { it.logged(phone.nowMs(), "calls_out", text) }

    /**
     * One mailbox call for [due] on [sim], on the person's tap: every guard, then dial, measure,
     * end and record the call. [dryRun]: only say what it would dial.
     */
    suspend fun run(sim: OwnerSim, due: CallsOutDue, targets: CallTargetsFile?, dryRun: Boolean = false): MailboxOutcome {
        val session = OwnerSession(phone, store, sim, model)
        val blocks = mutableListOf<String>()
        val (mailbox, why) = MailboxGuard.target(targets, sim, device)
        if (mailbox == null) blocks += why ?: "no_mailbox"
        if (TestCaps.callsLeft(store.read().calls, sim.key, phone.nowMs()) <= 0) blocks += "daily_cap"
        try {
            session.guard(callWaitMs = 0)
        } catch (e: SafetyException) {
            blocks += e.reason
        }
        val inEmergency = device.inEmergencyCall()
        if (inEmergency != false) blocks += if (inEmergency == null) "emergency_call_unreadable" else "emergency_call_active"
        val o = runCatching { session.observe() }.getOrNull()
        val path = o?.let { bearerPath(it, due.bearer) } ?: "ims_unreadable"
        if (path != ON_BEARER && path != MOVE_TO_LTE) blocks += path
        val what = "the ${mailbox?.brand ?: "-"} mailbox ${mailbox?.number ?: "-"} on slot ${sim.slot + 1} for ${due.feature.label} over ${due.bearer.plain} (${due.why})"
        if (dryRun) {
            val move = if (path == MOVE_TO_LTE) "; IMS would first move to LTE inside the non-persistent Wi-Fi calling off override" else ""
            log(
                "dry run: would dial $what, with normal sound, ended by the app after about ${timing.holdMs / 1000} s$move; " +
                    (if (blocks.isEmpty()) "every guard passed; " else "the guards would refuse: ${blocks.joinToString()}; ") +
                    "nothing dialled (dry run)",
            )
            return MailboxOutcome("dry_run", (blocks + "dry_run").joinToString())
        }
        if (blocks.isNotEmpty() || mailbox == null) {
            log("refused $what: ${blocks.joinToString()}")
            return MailboxOutcome("refused", blocks.joinToString())
        }
        var moved = false
        try {
            if (path == MOVE_TO_LTE) {
                moved = true
                session.takeSnapshot()
                session.apply(OwnerFeature.VOWIFI, false, Hold.TRIAL, CALLS_OUT_WHY)
                var waited = 0L
                var now = session.observe()
                while (waited < timing.moveMs && bearerPath(now, ProvenBearer.LTE) != ON_BEARER) {
                    phone.sleep(5_000)
                    waited += 5_000
                    now = session.observe()
                }
                if (bearerPath(now, ProvenBearer.LTE) != ON_BEARER) {
                    log("refused $what: IMS did not move to LTE within ${timing.moveMs / 1000} s")
                    return MailboxOutcome("refused", "ims_did_not_move_to_lte")
                }
                session.guard(callWaitMs = 0)
            }
            return call(sim, due, mailbox, what)
        } catch (e: SafetyException) {
            log("refused $what: ${e.reason}")
            return MailboxOutcome("refused", e.reason)
        } finally {
            if (moved) {
                withContext(NonCancellable) {
                    val proof = runCatching { session.revertTrials("end of the calls-out call") }.getOrNull()
                    log("the Wi-Fi calling off override ${if (proof?.ok == true) "reverted, proof holds" else "waits for the cleanup"}")
                }
            }
        }
    }

    private suspend fun call(sim: OwnerSim, due: CallsOutDue, mailbox: Mailbox, what: String): MailboxOutcome {
        val slot = SimSlot.of(sim.slot) ?: return MailboxOutcome("refused", "slot")
        val mailboxes = setOf(mailbox.number)
        fun read(text: String?): CallSample? = text?.let { RegistryParser.parse(it.lineSequence(), slot, mailboxes)?.call?.copy(atMs = phone.uptimeMs()) }
        val baseline = read(phone.registryText()) ?: return MailboxOutcome("refused", "registry_unreadable")
        if (baseline.state != CallState.IDLE) return MailboxOutcome("refused", Refusal.CALL_ACTIVE.wire)
        val watch = CallWatch()
        watch.add(baseline)
        var placedAt = 0L
        var abort: String? = null
        var hungUpByPhone = false
        try {
            if (!device.place(mailbox.number, sim.subId)) {
                log("refused $what: Telecom did not place the call")
                return MailboxOutcome("refused", "not_placed")
            }
            placedAt = phone.nowMs()
            log("dialled $what, with normal sound")
            val start = phone.uptimeMs()
            var activeAt: Long? = null
            var endAt: Long? = null
            var offhook = false
            while (true) {
                phone.sleep(timing.pollMs)
                val now = phone.uptimeMs()
                val sample = read(phone.registryText())
                watch.add(sample)
                // An emergency mark aborts at once: no call touched, nothing kept.
                val markChanged = sample?.emergencyMark != baseline.emergencyMark
                if (markChanged || device.inEmergencyCall() != false) {
                    abort = "emergency"
                    break
                }
                val state = sample?.state
                if (state == CallState.OFFHOOK) offhook = true
                val active = state == CallState.OFFHOOK && sample.foreground == PreciseCallState.ACTIVE
                if (active && activeAt == null) activeAt = now
                // Another call ringing: end ours only while ours is the active one (Telecom ends the active call first).
                val ringing = offhook && state == CallState.RINGING
                if (endAt != null) {
                    if (state == CallState.IDLE) {
                        phone.sleep(timing.pollMs)
                        watch.add(read(phone.registryText()))
                        break
                    }
                    if (now - endAt >= timing.endWaitMs) {
                        if (!ringing) device.end()
                        abort = abort ?: "did_not_end"
                        break
                    }
                    continue
                }
                if (offhook && state == CallState.IDLE) {
                    // The call ended before the app hung up (busy, the network, the far end).
                    phone.sleep(timing.pollMs)
                    watch.add(read(phone.registryText()))
                    break
                }
                if (ringing) {
                    abort = "another_call"
                    if (active || activeAt != null) {
                        device.end()
                        endAt = now
                    }
                    if (now - start >= timing.maxMs) break
                    continue
                }
                when {
                    activeAt == null && now - start >= timing.waitActiveMs -> {
                        device.end()
                        endAt = now
                    }
                    activeAt != null && now - activeAt >= timing.holdMs && (watch.mediaReady() || now - activeAt >= timing.holdMaxMs) -> {
                        hungUpByPhone = active
                        device.end()
                        endAt = now
                    }
                    now - start >= timing.maxMs -> {
                        device.end()
                        endAt = now
                    }
                }
            }
        } finally {
            device.callEnded()
        }
        if (abort == "emergency") {
            log("aborted: an emergency call showed during the mailbox call; no call was touched, nothing was kept")
            return MailboxOutcome("aborted", "emergency")
        }
        val summary = watch.summary()
        val record = CallRecord.of(summary, placedAt, sim.slot, sim.key, CallMethod.MAILBOX_CALL, build, stack, hungUpByPhone = hungUpByPhone)
            ?: return MailboxOutcome("aborted", "emergency")
        val check = CallLevels.callsOut(record)
        // A call cut short (another call rang, the charger was pulled) measured nothing: not counted, never failed.
        val result = if (abort != null) "not_counted_retry" else check.result
        val reasons = (listOfNotNull(abort) + check.failed + check.notCounted).joinToString()
        val now = phone.nowMs()
        var reverts: List<Triple<Int, OwnerFeature, String>> = emptyList()
        store.update { s ->
            val (next, r) = CallLedger.add(s, record, now, cellOf, shortBuild)
            reverts = r
            next
        }
        // Round 28's media text ("packets only", "No sound was checked"), or round 26's numbers when the call never went ACTIVE.
        val media = record.media?.text ?: "RTP ${record.rtp?.sentPer10s ?: "-"} sent and ${record.rtp?.receivedPer10s ?: "-"} received per 10 s."
        log(
            "mailbox call for ${due.feature.label} over ${due.bearer.plain}: " +
                when (result) {
                    "passed" -> "calls out work (audio from packet counts); incoming not tested"
                    "failed" -> "failed ($reasons)"
                    else -> "not counted, retry ($reasons)"
                } + ". $media Ended: ${record.disconnect?.plain ?: "not read"}",
        )
        return MailboxOutcome(result, reasons.ifEmpty { null }, record, reverts)
    }

    companion object {
        const val ON_BEARER = "on_bearer"
        const val MOVE_TO_LTE = "move_to_lte"
        const val CALLS_OUT_WHY = "calls-out call over LTE"

        /**
         * Whether IMS carries calls on [bearer] now: registered for calls with
         * its data connection on that bearer ([ON_BEARER]); for LTE, while IMS
         * runs over Wi-Fi and LTE offers a voice path, [MOVE_TO_LTE] (the
         * non-persistent Wi-Fi calling off override moves it, as VoLTE's
         * measurement does). Anything else is the reason there is no call now.
         */
        fun bearerPath(o: Observation, bearer: ProvenBearer): String {
            val registered = o.ims.registered == true && ImsService.MMTEL in o.ims.services && o.registrationOnPdn
            val transport = o.ims.transport
            return when (bearer) {
                ProvenBearer.IWLAN -> when {
                    registered && transport?.isWifi == true -> ON_BEARER
                    o.wifiConnected != true -> "no_wifi"
                    else -> "ims_not_on_wifi"
                }
                ProvenBearer.LTE -> when {
                    registered && transport != null && !transport.isWifi && o.onLte -> ON_BEARER
                    registered && transport?.isWifi == true && o.onLte && o.lteVops == LteVops.SUPPORTED && o.switch(OwnerFeature.VOLTE) == true -> MOVE_TO_LTE
                    else -> "ims_not_on_lte"
                }
            }
        }
    }
}
