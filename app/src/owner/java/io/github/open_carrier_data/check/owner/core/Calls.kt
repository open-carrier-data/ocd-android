package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.BearerVerdict
import io.github.open_carrier_data.check.core.CallBearer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RtpFlow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * Round 26 (OCD's private research/rule-decisions-20261006, round 26 and
 * r26-agreement.md; the coordinator's refinements of 2026-10-20): the
 * evidence ladder "signed in -> calls out -> works", measured by the phone.
 *
 * - Calls out (L2): an outgoing call went ACTIVE, the bearer proof passed,
 *   RTP flowed both ways (at least 300 packets each way per 10 s, no
 *   inactivity), no SIP failure cause, and the call ended normally (the app's
 *   own mailbox call: the phone hung up itself). A real call the owner makes
 *   anyway, measured passively, counts the same as the app's muted call to
 *   the SIM's own mailbox; that call is only the fallback.
 * - Works (L3): calls out plus an incoming call on the same bearer that rang,
 *   was answered, did not come from a mailbox code, went ACTIVE with the
 *   bearer proof, had RTP both ways and lasted at least 20 s.
 *
 * Each level is per phone x SIM x software build x feature x bearer. The
 * audio is judged from packet counts until PHH IMS exports its audible
 * downlink and live uplink, so every detail line says "(audio from packet
 * counts)". No level ever claims anything about emergency calls, and no
 * record holds a number.
 */

/** How a call came to be measured. */
@Serializable
enum class CallMethod(val wire: String, val plain: String) {
    /** The app's own muted call to the SIM's allowlisted mailbox (the fallback). */
    MAILBOX_CALL("mailbox_call", "an automatic call to your mailbox"),

    /** A real call the owner made or took anyway, measured passively. */
    REAL_CALL("real_call", "one of your own calls"),
}

/** One measured call in the private ledger. No number, no time of day beyond the record's own clock, no cell. */
@Serializable
data class CallRecord(
    val atMs: Long,
    val slot: Int,
    /** The SIM in the slot when the call ran; null when the slot had no active SIM the app knows. */
    val sim: SimKey?,
    val method: CallMethod,
    val direction: CallDirection,
    val rang: Boolean,
    /** Went off-hook: an incoming call was answered, an outgoing one connected. */
    val answered: Boolean,
    val active: Boolean,
    /** IMS's bearer readings when the call started (it rang or dialled), at ACTIVE and at hang-up. */
    val bearerAtStart: BearerReading? = null,
    val bearerAtActive: BearerReading? = null,
    val bearerAtHangUp: BearerReading? = null,
    val bearer: BearerVerdict? = null,
    val rtp: RtpFlow? = null,
    val activeMs: Long? = null,
    /** Why it ended, read right after the end; [disconnectFresh] false: the same reading as before the call. */
    val disconnect: DisconnectReading? = null,
    val disconnectFresh: Boolean? = null,
    /** The app's own call: the phone ended it while it was still active. */
    val hungUpByPhone: Boolean? = null,
    /** The incoming number was one of the mailbox short codes (compared in the parser, never kept). */
    val fromMailbox: Boolean? = null,
    /** The watch saw more than one call: its readings cannot be told apart. */
    val severalCalls: Boolean = false,
    /** The ROM build ([TestBasis.rom]) and the IMS stack the call ran on: the level's software key. */
    val build: String,
    val stack: String,
    /** The app's own call: the voice-call stream was muted (true) or turned to its lowest volume. */
    val muted: Boolean? = null,
) {
    companion object {
        const val MAX = 120

        /** A record of a watched call; null when the watch saw an emergency call (nothing is kept of it). */
        fun of(
            summary: CallWatchSummary, atMs: Long, slot: Int, sim: SimKey?, method: CallMethod, build: String, stack: String,
            hungUpByPhone: Boolean? = null, muted: Boolean? = null,
        ): CallRecord? {
            if (summary.emergencySeen) return null
            return CallRecord(
                atMs = atMs, slot = slot, sim = sim, method = method,
                direction = summary.direction,
                rang = summary.ringingSeen,
                answered = summary.connected,
                active = summary.wentActive,
                bearerAtStart = summary.bearerAtStart,
                bearerAtActive = summary.bearerAtActive,
                bearerAtHangUp = summary.bearerAtHangUp,
                bearer = summary.bearerProof,
                rtp = summary.rtp,
                activeMs = summary.activeMs,
                disconnect = summary.disconnectAfter,
                disconnectFresh = summary.disconnectFresh,
                hungUpByPhone = hungUpByPhone,
                fromMailbox = summary.incomingIsMailbox,
                severalCalls = summary.callsSeen > 1,
                build = build,
                stack = stack,
                muted = muted,
            )
        }
    }
}

/** The result of one call against a level. */
@Serializable
data class LevelCheck(
    val passed: Boolean,
    val bearer: ProvenBearer?,
    /** passed, not_counted_retry or failed. */
    val result: String,
    val failed: List<String> = emptyList(),
    val notCounted: List<String> = emptyList(),
)

/** A measured incoming failure (round 26 automatic check 6): what it was, and the bearer IMS was on when the call came in. */
data class IncomingFailure(val kind: String, val bearer: ProvenBearer)

object CallLevels {
    /** L3: an incoming call counts after at least 20 s. */
    const val L3_MIN_MS = 20_000L

    /** A passing call of at least 60 s records the build sound note. */
    const val SOUND_NOTE_MS = 60_000L

    /** Evidence older than this counts no more ("re-tested by OCD" expires after 90 days, round 19). */
    val VALID_MS: Long = TimeUnit.DAYS.toMillis(90)

    fun bearerOf(feature: OwnerFeature): ProvenBearer? = when (feature) {
        OwnerFeature.VOLTE -> ProvenBearer.LTE
        OwnerFeature.VOWIFI -> ProvenBearer.IWLAN
        else -> null
    }

    fun featureOf(bearer: ProvenBearer): OwnerFeature = if (bearer == ProvenBearer.IWLAN) OwnerFeature.VOWIFI else OwnerFeature.VOLTE

    /**
     * L2 "calls out" for one outgoing call (lab `calls_out`): ACTIVE, the
     * bearer proof, RTP both ways, no SIP failure, and a normal end (the
     * app's own call: the phone hung up itself). A bearer that is not proven
     * is "not counted, retry"; anything else that fails is "failed".
     */
    fun callsOut(r: CallRecord): LevelCheck {
        val failed = mutableListOf<String>()
        val notCounted = mutableListOf<String>()
        // The mailbox always answers; a real call's callee may be busy, decline or not answer, which says nothing about this phone.
        val mailbox = r.method == CallMethod.MAILBOX_CALL
        if (r.direction != CallDirection.OUTGOING) notCounted += "not_outgoing"
        if (r.severalCalls) notCounted += "several_calls"
        if (!r.active) {
            if (mailbox) failed += "not_active" else notCounted += "not_answered"
        } else if (r.bearer?.counted != true) {
            notCounted += (r.bearer?.why ?: listOf("no_reading")).map { "bearer_not_proven:$it" }
        }
        if (r.active && r.rtp?.passed != true) failed += (r.rtp?.why ?: listOf("unreadable")).map { "rtp:$it" }
        if (r.disconnect?.sipFailure == true && (mailbox || r.active)) failed += "sip_failure:${r.disconnect.imsName}"
        if (r.active) {
            when (r.method) {
                CallMethod.MAILBOX_CALL -> if (r.hungUpByPhone != true || r.disconnect?.byThisPhone != true) failed += "not_hung_up_by_the_phone"
                CallMethod.REAL_CALL -> if (r.disconnect != null && !r.disconnect.normal) failed += "ended_abnormally"
            }
        }
        return check(r, failed, notCounted)
    }

    /** The incoming leg of L3. */
    fun incoming(r: CallRecord): LevelCheck {
        val failed = mutableListOf<String>()
        val notCounted = mutableListOf<String>()
        if (r.direction != CallDirection.INCOMING || !r.rang) notCounted += "not_incoming"
        if (r.severalCalls) notCounted += "several_calls"
        if (r.fromMailbox == true) notCounted += "from_mailbox"
        if (!r.answered) notCounted += "not_answered"
        if (r.answered && !r.active) failed += "not_active"
        if (r.active && r.bearer?.counted != true) notCounted += (r.bearer?.why ?: listOf("no_reading")).map { "bearer_not_proven:$it" }
        if (r.active && r.rtp?.passed != true) failed += (r.rtp?.why ?: listOf("unreadable")).map { "rtp:$it" }
        if (r.active && (r.activeMs ?: 0) < L3_MIN_MS) notCounted += "under_20s"
        if (r.disconnect?.sipFailure == true) failed += "sip_failure:${r.disconnect.imsName}"
        return check(r, failed, notCounted)
    }

    private fun check(r: CallRecord, failed: List<String>, notCounted: List<String>): LevelCheck {
        val passed = failed.isEmpty() && notCounted.isEmpty()
        return LevelCheck(
            passed = passed,
            bearer = r.bearer?.bearer.takeIf { r.bearer?.counted == true },
            result = when {
                passed -> "passed"
                failed.isEmpty() -> "not_counted_retry"
                else -> "failed"
            },
            failed = failed,
            notCounted = notCounted,
        )
    }

    /** The bearer IMS was on when the call started: its ims data connection's one transport. */
    fun imsBearerAtStart(r: CallRecord): ProvenBearer? {
        val pdn = r.bearerAtStart?.imsPdn?.distinct() ?: return null
        val one = pdn.singleOrNull() ?: return null
        return if (one == ImsTransport.IWLAN) ProvenBearer.IWLAN else ProvenBearer.LTE
    }

    /**
     * A measured incoming failure (round 26 automatic check 6): the call was
     * answered but never went ACTIVE, went ACTIVE on another bearer than the
     * one IMS was on when it rang (a circuit-switched call included), or
     * dropped under 20 s with an abnormal disconnect cause. A call the owner
     * declined or missed is never a failure, and readings that disagree say
     * nothing.
     */
    fun incomingFailure(r: CallRecord): IncomingFailure? {
        if (r.direction != CallDirection.INCOMING || !r.rang || !r.answered || r.severalCalls || r.fromMailbox == true) return null
        val imsBearer = imsBearerAtStart(r) ?: return null
        if (!r.active) return IncomingFailure("answered_not_active", imsBearer)
        val proven = r.bearer?.takeIf { it.counted }?.bearer
        val circuitSwitched = CallBearer.of(r.bearerAtActive?.callNetworkType) == CallBearer.CS
        if ((proven != null && proven != imsBearer) || circuitSwitched) return IncomingFailure("active_on_wrong_bearer", imsBearer)
        val end = r.disconnect
        if ((r.activeMs ?: 0) < L3_MIN_MS && end != null && !end.normal) return IncomingFailure("dropped_under_20s", imsBearer)
        return null
    }

    /** The newest call that passed L2 on [bearer] for [sim] on [build], at or after [sinceMs] and not older than [VALID_MS]. */
    fun callsOutEvidence(calls: List<CallRecord>, sim: SimKey, bearer: ProvenBearer, build: String, sinceMs: Long, nowMs: Long): CallRecord? =
        calls.filter { it.sim?.sameCard(sim) == true && it.build == build && it.atMs >= sinceMs && nowMs - it.atMs < VALID_MS }
            .lastOrNull { callsOut(it).let { c -> c.passed && c.bearer == bearer } }

    /** The newest incoming call that passed L3's incoming leg on [bearer], on the same terms. */
    fun incomingEvidence(calls: List<CallRecord>, sim: SimKey, bearer: ProvenBearer, build: String, sinceMs: Long, nowMs: Long): CallRecord? =
        calls.filter { it.sim?.sameCard(sim) == true && it.build == build && it.atMs >= sinceMs && nowMs - it.atMs < VALID_MS }
            .lastOrNull { incoming(it).let { c -> c.passed && c.bearer == bearer } }

    /** The level [feature] reached on [sim] and [build]: 2 calls out, 3 works, null neither. */
    fun level(calls: List<CallRecord>, sim: SimKey, feature: OwnerFeature, build: String, sinceMs: Long, nowMs: Long): CallsLevel? {
        val bearer = bearerOf(feature) ?: return null
        val out = callsOutEvidence(calls, sim, bearer, build, sinceMs, nowMs) ?: return null
        val incoming = incomingEvidence(calls, sim, bearer, build, sinceMs, nowMs)
        out.atMs
        return if (incoming != null) CallsLevel.WORKS else CallsLevel.CALLS_OUT
    }

    /** One plain line for Details: what the call showed, "(audio from packet counts)" when it passed. Never the number. */
    fun line(r: CallRecord, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
        val how = if (r.method == CallMethod.MAILBOX_CALL) "Automatic call to your mailbox" else if (r.direction == CallDirection.INCOMING) "Incoming call" else "Outgoing call"
        val over = r.bearer?.takeIf { it.counted }?.bearer?.let { " over ${it.plain}" }.orEmpty()
        val outcome = if (r.direction == CallDirection.INCOMING) {
            val c = incoming(r)
            when {
                c.passed -> "came in and worked both ways (audio from packet counts)"
                !r.answered -> "not answered (that is not a failure)"
                c.result == "failed" -> "did not work: ${c.failed.joinToString()}"
                else -> "not counted: ${c.notCounted.joinToString()}"
            }
        } else {
            val c = callsOut(r)
            when {
                c.passed -> "calls out work (audio from packet counts)"
                c.result == "failed" -> "did not work: ${c.failed.joinToString()}"
                else -> "not counted, tried again later: ${c.notCounted.joinToString()}"
            }
        }
        return "$how$over, $day: $outcome"
    }
}

/** How far the calls went for one feature: calls out (L2) or works, calls both ways (L3). */
@Serializable
enum class CallsLevel { CALLS_OUT, WORKS }

/** Whether the app places its own mailbox call: [DRY_RUN] runs every guard and logs what it would dial, and dials nothing. */
@Serializable
enum class CallsOutMode(val wire: String) {
    DRY_RUN("dry_run"),
    LIVE("live"),
}

/** The SIM card itself: the subscription and the network code (the carrier ID can arrive later). */
fun SimKey.sameCard(other: SimKey): Boolean = subId == other.subId && mccmnc == other.mccmnc

/**
 * A changed voice value kept for now (round 26 automatic check 6 and rule
 * change 7). Only a value that differs from the phone's own needs this:
 * - it is kept past the night only after "calls out" passed on its bearer
 *   ([callsOutByMs], the end of the night it was applied in);
 * - it then shows "incoming not yet seen";
 * - it reverts by itself on a measured incoming failure on its bearer, or
 *   when no incoming evidence came within 7 days of the apply;
 * - a declined or missed call is never a failure.
 */
@Serializable
data class ProvisionalKeep(
    val slot: Int,
    val sim: SimKey,
    val feature: OwnerFeature,
    val value: Boolean,
    val bearer: ProvenBearer,
    val appliedAtMs: Long,
    val callsOutByMs: Long,
    val callsOutAtMs: Long? = null,
)

/** What to do with a provisional keep now. */
sealed interface ProvisionalDecision {
    data object Wait : ProvisionalDecision
    data class CallsOut(val atMs: Long) : ProvisionalDecision
    data class Promote(val atMs: Long) : ProvisionalDecision
    data class Revert(val reason: String) : ProvisionalDecision
}

object Provisional {
    val INCOMING_WAIT_MS: Long = TimeUnit.DAYS.toMillis(7)

    /** Whether a keep of [value] (the phone's own is [baseline]) is provisional, and on which bearer. */
    fun bearerFor(feature: OwnerFeature, value: Boolean, baseline: Boolean?): ProvenBearer? =
        if (feature.voice && value && baseline != value) CallLevels.bearerOf(feature) else null

    /** A voice value that differs from the phone's own and has no bearer proof (VoNR): never kept past its test. */
    fun unprovable(feature: OwnerFeature, value: Boolean, baseline: Boolean?): Boolean =
        feature.voice && value && baseline != value && CallLevels.bearerOf(feature) == null

    /** The end of the night window (05:00 local) at or after [atMs]: a keep applied then needs calls out by this time. */
    fun nightEnd(atMs: Long, zone: ZoneId): Long {
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        var end = at.toLocalDate().atTime(QuietWindow.NIGHT_UNTIL_MINUTE / 60, QuietWindow.NIGHT_UNTIL_MINUTE % 60).atZone(zone)
        if (!end.isAfter(at)) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }

    fun decide(p: ProvisionalKeep, calls: List<CallRecord>, build: String, nowMs: Long): ProvisionalDecision {
        val mine = calls.filter { it.sim?.sameCard(p.sim) == true && it.atMs >= p.appliedAtMs }
        // A measured incoming failure on the keep's bearer reverts it at once.
        mine.firstNotNullOfOrNull { r -> CallLevels.incomingFailure(r)?.takeIf { it.bearer == p.bearer } }?.let {
            return ProvisionalDecision.Revert("incoming_failed:${it.kind}")
        }
        val out = p.callsOutAtMs ?: CallLevels.callsOutEvidence(calls, p.sim, p.bearer, build, p.appliedAtMs, nowMs)?.atMs
        if (out == null) {
            // A measured outgoing failure on its bearer after the apply, or the night over without calls out.
            val failedOut = mine.any { it.direction == CallDirection.OUTGOING && CallLevels.callsOut(it).let { c -> c.result == "failed" && imsOn(it) == p.bearer } }
            if (failedOut) return ProvisionalDecision.Revert("calls_out_failed")
            if (nowMs >= p.callsOutByMs) return ProvisionalDecision.Revert("no_calls_out_by_the_end_of_the_night")
            return ProvisionalDecision.Wait
        }
        val incoming = CallLevels.incomingEvidence(calls, p.sim, p.bearer, build, p.appliedAtMs, nowMs)
        if (incoming != null) return ProvisionalDecision.Promote(incoming.atMs)
        if (nowMs - p.appliedAtMs >= INCOMING_WAIT_MS) return ProvisionalDecision.Revert("no_incoming_in_7_days")
        return if (p.callsOutAtMs == null) ProvisionalDecision.CallsOut(out) else ProvisionalDecision.Wait
    }

    /** The bearer an outgoing call ran on, as far as the readings tell: proven, else IMS's at the start. */
    private fun imsOn(r: CallRecord): ProvenBearer? = r.bearer?.takeIf { it.counted }?.bearer ?: CallLevels.imsBearerAtStart(r)
}

/** One mailbox call placed (or tried) in a night, for "at most one call per feature and bearer a night". */
@Serializable
data class CallsOutNight(val sim: SimKey, val feature: OwnerFeature, val bearer: ProvenBearer, val night: String)

/** A mailbox call due tonight. */
data class CallsOutDue(val slot: Int, val sim: SimKey, val feature: OwnerFeature, val bearer: ProvenBearer, val why: String)

/**
 * When the app places its own mailbox call (round 26 check 4 and the
 * coordinator's refinement of 2026-10-20: passive first, the mailbox call is
 * only the fallback):
 * - after an apply: a provisional keep without calls out since it was applied;
 * - after a pointer: others' report points at a voice entry's on value of
 *   this SIM's cell, the feature is on, and no real outgoing call on its
 *   bearer passed within 3 days;
 * - weekly per kept voice entry: no calls-out evidence on its bearer within
 *   the last 7 days.
 * At most one call per feature and bearer a night.
 */
object CallsOutPlan {
    val PASSIVE_RECENT_MS: Long = TimeUnit.DAYS.toMillis(3)
    val WEEKLY_MS: Long = TimeUnit.DAYS.toMillis(7)
    const val AFTER_APPLY = "after_apply"
    const val POINTER = "pointer"
    const val WEEKLY = "weekly"

    /**
     * [switches]: each SIM's effective switches by feature; [pointed]: the
     * voice entries ("volte:on") others' reports point at, per SIM.
     */
    fun due(
        sims: List<OwnerSim>,
        state: OwnerState,
        switches: Map<SimKey, Map<OwnerFeature, Boolean?>>,
        pointed: Map<SimKey, Set<String>>,
        build: String,
        night: String,
        nowMs: Long,
    ): List<CallsOutDue> {
        val out = mutableListOf<CallsOutDue>()
        for (sim in sims) {
            for (feature in listOf(OwnerFeature.VOWIFI, OwnerFeature.VOLTE)) {
                val bearer = CallLevels.bearerOf(feature) ?: continue
                if (state.callsOutNights.any { it.sim.sameCard(sim.key) && it.feature == feature && it.bearer == bearer && it.night == night }) continue
                val provisional = state.provisional.firstOrNull { it.sim.sameCard(sim.key) && it.feature == feature && it.callsOutAtMs == null }
                val kept = state.overrides.any { it.hold == Hold.KEPT && it.slot == sim.slot && it.sim.sameCard(sim.key) && it.key == feature.key && it.value }
                val on = switches[sim.key]?.get(feature) == true
                val why = when {
                    provisional != null &&
                        CallLevels.callsOutEvidence(state.calls, sim.key, bearer, build, provisional.appliedAtMs, nowMs) == null -> AFTER_APPLY
                    on && QuietPlan.entry(feature, true) in pointed[sim.key].orEmpty() &&
                        CallLevels.callsOutEvidence(state.calls, sim.key, bearer, build, nowMs - PASSIVE_RECENT_MS, nowMs) == null -> POINTER
                    kept && CallLevels.callsOutEvidence(state.calls, sim.key, bearer, build, nowMs - WEEKLY_MS, nowMs) == null &&
                        state.provisional.none { it.sim.sameCard(sim.key) && it.feature == feature && it.callsOutAtMs == null } -> WEEKLY
                    else -> null
                } ?: continue
                out += CallsOutDue(sim.slot, sim.key, feature, bearer, why)
            }
        }
        return out.sortedBy { listOf(AFTER_APPLY, POINTER, WEEKLY).indexOf(it.why) }
    }
}

/**
 * "Re-tested by OCD" (round 26 rule change 9): set only from the owner's own
 * phones, from L2 or L3 measured by machine. It names the level, the method,
 * the build and the date, and it expires after 90 days. A "calls out" entry
 * always says "incoming not tested".
 */
@Serializable
data class RetestedEntry(
    /** [PointerLedger.cell]: model, ROM family, Android major, network code, carrier ID. */
    val cell: String,
    /** volte:on or vowifi:on. */
    val entry: String,
    val bearer: ProvenBearer,
    val level: CallsLevel,
    /** How each leg was measured: "mailbox_call", "real_call", or "real_call+real_call" for both legs. */
    val method: String,
    /** The software the calls ran on, short: the ROM build and the IMS stack. */
    val build: String,
    val atMs: Long,
) {
    fun expired(nowMs: Long): Boolean = nowMs - atMs >= Retested.VALID_MS

    fun line(zone: ZoneId): String {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val over = bearer.plain
        return when (level) {
            CallsLevel.CALLS_OUT -> "re-tested by OCD: calls out over $over, incoming not tested (build $build, $day) (audio from packet counts)"
            CallsLevel.WORKS -> "re-tested by OCD: works, calls both ways over $over (build $build, $day) (audio from packet counts)"
        }
    }
}

object Retested {
    val VALID_MS: Long = TimeUnit.DAYS.toMillis(90)
    const val MAX = 40

    /** [entries] with [entry] added: one per cell, entry, bearer and level, the newest; expired ones dropped. */
    fun add(entries: List<RetestedEntry>, entry: RetestedEntry, nowMs: Long): List<RetestedEntry> =
        (entries.filterNot { it.cell == entry.cell && it.entry == entry.entry && it.bearer == entry.bearer && it.level == entry.level } + entry)
            .filterNot { it.expired(nowMs) }
            .sortedBy { it.atMs }
            .takeLast(MAX)

    fun current(entries: List<RetestedEntry>, nowMs: Long): List<RetestedEntry> = entries.filterNot { it.expired(nowMs) }

    /** The method of a level: one leg, or both legs for works. */
    fun method(out: CallRecord, incoming: CallRecord?): String =
        if (incoming == null) out.method.wire else "${out.method.wire}+${incoming.method.wire}"
}

/** The state after one measured call: in the ledger, the provisional keeps it settles, and "re-tested by OCD". */
object CallLedger {
    /**
     * Adds [record] and returns the new state with the provisional keeps it
     * decided and the re-tested entries it set, plus the keeps to revert
     * (slot, feature, reason). [cellOf] names a SIM's re-tested cell. A
     * call proven over LTE is a VoLTE call and one over Wi-Fi a Wi-Fi calling
     * call, so the bearer names the entry (volte:on, vowifi:on).
     */
    fun add(
        state: OwnerState,
        record: CallRecord,
        nowMs: Long,
        cellOf: (SimKey) -> String,
        shortBuild: String,
    ): Pair<OwnerState, List<Triple<Int, OwnerFeature, String>>> {
        var next = state.copy(calls = (state.calls + record).takeLast(CallRecord.MAX))
        next = retested(next, record, nowMs, cellOf, shortBuild)
        return settle(next, record.build, nowMs)
    }

    /** Decides every provisional keep against the ledger. */
    fun settle(state: OwnerState, build: String, nowMs: Long): Pair<OwnerState, List<Triple<Int, OwnerFeature, String>>> {
        val reverts = mutableListOf<Triple<Int, OwnerFeature, String>>()
        val kept = state.provisional.mapNotNull { p ->
            when (val d = Provisional.decide(p, state.calls, build, nowMs)) {
                ProvisionalDecision.Wait -> p
                is ProvisionalDecision.CallsOut -> p.copy(callsOutAtMs = d.atMs)
                is ProvisionalDecision.Promote -> null
                is ProvisionalDecision.Revert -> {
                    reverts += Triple(p.slot, p.feature, d.reason)
                    p
                }
            }
        }
        val promoted = state.provisional.filter { p -> Provisional.decide(p, state.calls, build, nowMs) is ProvisionalDecision.Promote }
        var next = state.copy(provisional = kept)
        for (p in promoted) next = next.logged(nowMs, "keep", "${p.feature.wire} kept: calls both ways over ${p.bearer.plain} (audio from packet counts)")
        return next to reverts
    }

    private fun retested(state: OwnerState, record: CallRecord, nowMs: Long, cellOf: (SimKey) -> String, shortBuild: String): OwnerState {
        val sim = record.sim ?: return state
        val bearer = record.bearer?.takeIf { it.counted }?.bearer ?: return state
        val feature = CallLevels.featureOf(bearer)
        val out = CallLevels.callsOutEvidence(state.calls, sim, bearer, record.build, 0, nowMs) ?: return state
        val incoming = CallLevels.incomingEvidence(state.calls, sim, bearer, record.build, 0, nowMs)
        val level = if (incoming != null) CallsLevel.WORKS else CallsLevel.CALLS_OUT
        val entry = RetestedEntry(
            cell = cellOf(sim), entry = QuietPlan.entry(feature, true), bearer = bearer, level = level,
            method = Retested.method(out, incoming), build = shortBuild, atMs = maxOf(out.atMs, incoming?.atMs ?: 0),
        )
        if (state.retested.any { it == entry }) return state
        return state.copy(retested = Retested.add(state.retested, entry, nowMs))
    }
}

/** Provisional keeps whose keep is gone (undone, replaced, a SIM swap) are dropped. */
internal fun OwnerState.provisionalSettled(): OwnerState {
    val left = provisional.filter { p -> overrides.any { it.hold == Hold.KEPT && it.slot == p.slot && it.sim.sameCard(p.sim) && it.key == p.feature.key && it.value == p.value } }
    return if (left.size == provisional.size) this else copy(provisional = left)
}
