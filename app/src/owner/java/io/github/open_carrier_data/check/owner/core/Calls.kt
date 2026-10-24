package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.BearerVerdict
import io.github.open_carrier_data.check.core.CallBearer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallKind
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.MediaResult
import io.github.open_carrier_data.check.core.MediaRule
import io.github.open_carrier_data.check.core.MediaVerdict
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
 *   round 28's media rule passed ([MediaRule]: an audio stream both ways,
 *   full rate or, on a stack with verified counters, DTX), no SIP failure
 *   cause, and the call ended normally (the app's own mailbox call: the
 *   phone hung up itself). A real call the owner makes anyway, measured
 *   passively, counts the same as the app's muted call to the SIM's own
 *   mailbox; that call is only the fallback.
 * - Works (L3): calls out plus an incoming call on the same bearer that rang,
 *   was answered, did not come from a mailbox code, went ACTIVE with the
 *   bearer proof, passed the media rule and lasted at least 20 s.
 *
 * Round 28: a media result that is not a pass is "not counted, retry",
 * never "failed", except "no audio stream received" (zero packets received
 * over at least 10 s ACTIVE). That is a measured failure at once on the
 * app's own mailbox call, which plays continuously; a passive real call
 * needs it twice, on the same SIM and bearer on separate days
 * ([CallLevels.noStreamTwice]), because a far end that sends nothing in
 * silence cannot be told from a broken downlink after one call. A passive
 * call that ended with a CODE_SIP_* or CODE_MEDIA_* code is "not counted".
 *
 * Each level is per phone x SIM x software build x feature x bearer. The
 * audio is judged from packet counts only, so every detail line says so
 * ("(audio from packet counts)", "packets only", "No sound was checked").
 * No level ever claims anything about emergency calls, and no record holds
 * a number.
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
    /** Round 26's plain per-10-s numbers; the ladder reads them only for records made before round 28 ([media] null). */
    val rtp: RtpFlow? = null,
    /** Round 28's media result, with the data the ruling asks each result to carry; null when the call never went ACTIVE. */
    val media: MediaResult? = null,
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
            val kind = kindOf(method, summary.direction)
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
                media = if (summary.wentActive) {
                    MediaRule.judge(summary.media, kind, stack, build, summary.bearerProof, summary.disconnectAfter)
                } else {
                    null
                },
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

        /** Round 28's call_kind; null for a real call whose direction is not known (it never counts). */
        fun kindOf(method: CallMethod, direction: CallDirection): CallKind? = when {
            method == CallMethod.MAILBOX_CALL -> CallKind.MAILBOX_AUTO
            direction == CallDirection.OUTGOING -> CallKind.PASSIVE_OUTGOING
            direction == CallDirection.INCOMING -> CallKind.PASSIVE_INCOMING
            else -> null
        }
    }
}

/** A call's media verdict for the ladder, with its machine reasons. */
data class MediaCheck(val verdict: MediaVerdict, val reasons: List<String> = emptyList())

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
        if (r.active) {
            val m = media(r)
            when (m.verdict) {
                MediaVerdict.PASS -> Unit
                MediaVerdict.NOT_COUNTED -> notCounted += m.reasons.map { "media:$it" }
                // Round 28: the mailbox plays continuously, so nothing received is a measured failure at once; a real call needs two.
                MediaVerdict.NO_STREAM -> if (mailbox) failed += "media:$NO_STREAM" else notCounted += "media:$NO_STREAM"
            }
        }
        // Round 28: a passive call's CODE_SIP_* or CODE_MEDIA_* end is "not counted"; the mailbox call keeps "failed".
        if (r.disconnect?.sipFailure == true && mailbox) failed += "sip_failure:${r.disconnect.imsName}"
        passiveEnd(r, notCounted)
        if (r.active) {
            when (r.method) {
                CallMethod.MAILBOX_CALL -> if (r.hungUpByPhone != true || r.disconnect?.byThisPhone != true) failed += "not_hung_up_by_the_phone"
                CallMethod.REAL_CALL -> if (r.disconnect != null && !r.disconnect.normal && !r.disconnect.sipFailure && !r.disconnect.mediaFailure) {
                    failed += "ended_abnormally"
                }
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
        if (r.active) {
            val m = media(r)
            // A passive incoming call with nothing received is not counted on its own: it fails only twice ([noStreamTwice]).
            if (m.verdict != MediaVerdict.PASS) notCounted += m.reasons.map { "media:$it" }
        }
        if (r.active && (r.activeMs ?: 0) < L3_MIN_MS) notCounted += "under_20s"
        // Round 28: an ACTIVE passive call's CODE_SIP_* or CODE_MEDIA_* end is "not counted"; before ACTIVE a SIP failure still fails.
        if (r.disconnect?.sipFailure == true && !r.active) failed += "sip_failure:${r.disconnect.imsName}"
        passiveEnd(r, notCounted)
        return check(r, failed, notCounted)
    }

    /** Round 28: an ACTIVE real call that ended with CODE_SIP_* or CODE_MEDIA_* is not counted (its media result says so too). */
    private fun passiveEnd(r: CallRecord, notCounted: MutableList<String>) {
        val end = r.disconnect ?: return
        if (r.method != CallMethod.REAL_CALL || !r.active || !(end.sipFailure || end.mediaFailure)) return
        val reason = "ended_with:${end.imsName ?: end.imsCode}"
        if (notCounted.none { it.endsWith(reason) }) notCounted += reason
    }

    /**
     * The media verdict of an ACTIVE call: round 28's [MediaResult], or for a
     * record made before round 28 its round 26 flow (a pass only on the 300
     * floor; zero received over at least 10 s is no stream; anything else is
     * not counted).
     */
    fun media(r: CallRecord): MediaCheck {
        r.media?.let { return MediaCheck(it.verdict, it.reasons) }
        val rtp = r.rtp ?: return MediaCheck(MediaVerdict.NOT_COUNTED, listOf("no_call_quality_reports"))
        return when {
            rtp.receivedPer10s == 0L && (rtp.windowMs ?: 0) >= MediaRule.MIN_WINDOW_MS && (rtp.sentPer10s ?: 0) > 0 ->
                MediaCheck(MediaVerdict.NO_STREAM, listOf(NO_STREAM))
            rtp.passed -> MediaCheck(MediaVerdict.PASS)
            else -> MediaCheck(MediaVerdict.NOT_COUNTED, rtp.why.map { "round26:$it" })
        }
    }

    /** The machine reason of "no audio stream received". */
    const val NO_STREAM = "no_audio_stream_received"

    /**
     * Round 28's measured failure for passive real calls: "no audio stream
     * received" on two calls of [direction] on [bearer] (the incoming leg:
     * rang, answered, not from a mailbox code, one call in the watch), on
     * separate days in [zone], each with the bearer proof. An IMS call that
     * went ACTIVE with the bearer proof ran over the IMS registration, so the
     * proof stands for "IMS registered". [calls]: one SIM's records.
     */
    fun noStreamTwice(calls: List<CallRecord>, direction: CallDirection, bearer: ProvenBearer, zone: ZoneId): Boolean =
        noStreamCalls(calls, direction, bearer).map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }.toSet().size >= 2

    /** The passive real calls of [direction] on [bearer] that received no audio stream ([noStreamTwice] counts their days). */
    fun noStreamCalls(calls: List<CallRecord>, direction: CallDirection, bearer: ProvenBearer): List<CallRecord> =
        calls.filter { r ->
            r.method == CallMethod.REAL_CALL && r.direction == direction && r.active && !r.severalCalls &&
                r.bearer?.counted == true && r.bearer.bearer == bearer &&
                (direction != CallDirection.INCOMING || (r.rang && r.answered && r.fromMailbox != true)) &&
                media(r).verdict == MediaVerdict.NO_STREAM
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

    /**
     * One plain line for Details: what the call showed, then round 28's media
     * text ("packets only", "No sound was checked") for an ACTIVE call. The
     * media reasons are said in its plain words, not as codes. Never the number.
     */
    fun line(r: CallRecord, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
        val how = if (r.method == CallMethod.MAILBOX_CALL) "Automatic call to your mailbox" else if (r.direction == CallDirection.INCOMING) "Incoming call" else "Outgoing call"
        val over = r.bearer?.takeIf { it.counted }?.bearer?.let { " over ${it.plain}" }.orEmpty()
        fun said(head: String, reasons: List<String>): String {
            val own = reasons.filterNot { it.startsWith("media:") && r.media != null }
            return if (own.isEmpty()) head else "$head: ${own.joinToString()}"
        }
        val outcome = if (r.direction == CallDirection.INCOMING) {
            val c = incoming(r)
            when {
                c.passed -> "answered incoming call with an audio stream both ways (packets)"
                !r.answered -> "not answered (that is not a failure)"
                c.result == "failed" -> said("did not work", c.failed)
                else -> said("not counted", c.notCounted)
            }
        } else {
            val c = callsOut(r)
            when {
                c.passed -> "calls out work (audio from packet counts)"
                c.result == "failed" -> said("did not work", c.failed)
                else -> said("not counted, tried again later", c.notCounted)
            }
        }
        val media = r.media?.takeIf { r.active }?.text?.let { " $it" }.orEmpty()
        return "$how$over, $day: $outcome.$media"
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

    /** [zone]: the owner's days, for round 28's "two calls on separate days". */
    fun decide(p: ProvisionalKeep, calls: List<CallRecord>, build: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ProvisionalDecision {
        val mine = calls.filter { it.sim?.sameCard(p.sim) == true && it.atMs >= p.appliedAtMs }
        // A measured incoming failure on the keep's bearer reverts it at once.
        mine.firstNotNullOfOrNull { r -> CallLevels.incomingFailure(r)?.takeIf { it.bearer == p.bearer } }?.let {
            return ProvisionalDecision.Revert("incoming_failed:${it.kind}")
        }
        // Round 28: no audio stream received on two answered incoming calls on its bearer, on separate days.
        if (CallLevels.noStreamTwice(mine, CallDirection.INCOMING, p.bearer, zone)) {
            return ProvisionalDecision.Revert("incoming_failed:${CallLevels.NO_STREAM}_twice")
        }
        val out = p.callsOutAtMs ?: CallLevels.callsOutEvidence(calls, p.sim, p.bearer, build, p.appliedAtMs, nowMs)?.atMs
        if (out == null) {
            // A measured outgoing failure on its bearer after the apply (a real call's "no stream" only twice), or the night over without calls out.
            val failedOut = mine.any { it.direction == CallDirection.OUTGOING && CallLevels.callsOut(it).let { c -> c.result == "failed" && imsOn(it) == p.bearer } } ||
                CallLevels.noStreamTwice(mine, CallDirection.OUTGOING, p.bearer, zone)
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
    internal fun imsOn(r: CallRecord): ProvenBearer? = r.bearer?.takeIf { it.counted }?.bearer ?: CallLevels.imsBearerAtStart(r)
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
                // A voice value kept as an override, or kept after restart in the runtime layer (its weekly calls out renews it).
                val kept = state.overrides.any { it.hold == Hold.KEPT && it.slot == sim.slot && it.sim.sameCard(sim.key) && it.key == feature.key && it.value } ||
                    state.layer.any { it.sim.sameCard(sim.key) && it.key == feature.key && it.boolValue == true && it.removing == null }
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
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<OwnerState, List<Triple<Int, OwnerFeature, String>>> {
        var next = state.copy(calls = (state.calls + record).takeLast(CallRecord.MAX))
        next = retested(next, record, nowMs, cellOf, shortBuild)
        return settle(next, record.build, nowMs, zone)
    }

    /** Decides every provisional keep against the ledger. */
    fun settle(state: OwnerState, build: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<OwnerState, List<Triple<Int, OwnerFeature, String>>> {
        val reverts = mutableListOf<Triple<Int, OwnerFeature, String>>()
        val kept = state.provisional.mapNotNull { p ->
            when (val d = Provisional.decide(p, state.calls, build, nowMs, zone)) {
                ProvisionalDecision.Wait -> p
                is ProvisionalDecision.CallsOut -> p.copy(callsOutAtMs = d.atMs)
                is ProvisionalDecision.Promote -> null
                is ProvisionalDecision.Revert -> {
                    reverts += Triple(p.slot, p.feature, d.reason)
                    p
                }
            }
        }
        val promoted = state.provisional.filter { p -> Provisional.decide(p, state.calls, build, nowMs, zone) is ProvisionalDecision.Promote }
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
