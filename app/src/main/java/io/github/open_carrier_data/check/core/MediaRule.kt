package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Round 28 (OCD's private research/rule-decisions-20261006, round 28 and
 * round28/ruling.json, decided 2026-10-23): the media rule for L2 "calls out"
 * and for the audio of L3's incoming leg, for every call kind. It replaces
 * round 26's single RtpFlow test (300 packets each way per 10 s), which a
 * working call with a silent far end fails: a silent AMR or AMR-WB sender
 * transmits only comfort-noise (SID) packets under DTX, about 62 per 10 s.
 *
 * 1. Window: each direction is judged over the whole ACTIVE, non-held window,
 *    from the first CallQuality report to the last, which must span at least
 *    10 s and hold at least 3 reports (on AOSP ImsMedia's 5 s reports that is
 *    about 15 s connected). Received packets are net of duplicates.
 * 2. Full rate (unchanged, every stack): at least 300 packets per 10 s (60 %
 *    of 10000/ptime where the Lab reads ptime), and no RTP inactivity.
 * 3. DTX (new): only on a stack whose SID and sequence-loss counters a Lab run
 *    has shown to be filled ([MediaRule.DTX_STACKS], today AOSP ImsStack with
 *    ImsMedia, never PHH). Below the floor a direction still counts with
 *    (a) at least 40 packets per 10 s over the window and in every report
 *    interval, (b) no inactivity and no empty interval, and for the received
 *    direction (c) at least 20 SID packets per 10 s, (d) at most 20 %
 *    sequence-gap loss and (e) AMR or AMR-WB (EVS waits for its own
 *    measurement). The sent direction has no SID counter: it counts on (a)
 *    and (b) alone, labelled "low send rate, DTX assumed, not verified".
 * 4. Verdicts: pass, "not counted, retry" (any other result where packets
 *    were received), or "no audio stream received" (zero received over at
 *    least 10 s ACTIVE). Only the last can become a measured failure: at once
 *    on the app's own mailbox call, after two calls on separate days for a
 *    passive real call (owner build, CallLevels).
 * 5. rxSilenceDetected and txSilenceDetected are never used: PHH leaves them
 *    unset and ImsMedia never sets them, so they are recorded as "not_filled".
 *
 * What packets show, written next to the rule: received packets prove a live
 * downlink from the network's media edge to this phone's negotiated port,
 * parsed in the negotiated payload format; on an inter-operator call that
 * edge may be a gateway, so they do not prove the far phone or person is
 * there. Sent packets prove only that this phone generated them. Nothing here
 * says whether anything was audible, so every text says "packets only" and
 * "No sound was checked", and never "speech". No number, address or SDP text
 * is kept; ptime would be a number only.
 */

/** ImsStreamMediaProfile.AUDIO_QUALITY_*, as CallQuality.getCodecType reports it. */
object Codec {
    const val NONE = 0
    const val AMR = 1
    const val AMR_WB = 2
    const val EVS_NB = 17
    const val EVS_WB = 18
    const val EVS_SWB = 19
    const val EVS_FB = 20

    fun name(type: Int?): String = when (type) {
        null -> "unknown"
        NONE -> "none"
        AMR -> "AMR"
        AMR_WB -> "AMR-WB"
        3 -> "QCELP13K"
        4 -> "EVRC"
        5 -> "EVRC-B"
        6 -> "EVRC-WB"
        7 -> "EVRC-NW"
        8 -> "GSM-EFR"
        9 -> "GSM-FR"
        10 -> "GSM-HR"
        11 -> "G.711 mu-law"
        12 -> "G.723"
        13 -> "G.711 A-law"
        14 -> "G.722"
        15 -> "G.711AB"
        16 -> "G.729"
        EVS_NB -> "EVS-NB"
        EVS_WB -> "EVS-WB"
        EVS_SWB -> "EVS-SWB"
        EVS_FB -> "EVS-FB"
        else -> "type $type"
    }
}

/**
 * What a call's CallQuality reports showed over its ACTIVE, non-held window,
 * as scalars only, never a sample list. Deltas run from the first report to
 * the last; per-interval values compare consecutive distinct reports.
 */
@Serializable
data class MediaWindow(
    /** Distinct reports (a new callDuration) in the window. */
    val reports: Int = 0,
    val windowMs: Long = 0,
    /** The longest time between two consecutive reports; null with fewer than two. */
    val maxReportGapMs: Long? = null,
    val sent: Long = 0,
    /** Received over the window, net of duplicates. */
    val received: Long = 0,
    /** Deltas of numRtpDuplicatePackets, numRtpSidPacketsReceived and numRtpPacketsNotReceived; null when a report lacks the key. */
    val duplicates: Long? = null,
    val sidReceived: Long? = null,
    val notReceived: Long? = null,
    /** The lowest rate of any interval between two reports, scaled to 10 s; null with fewer than two reports. */
    val minSentPer10s: Long? = null,
    val minReceivedPer10s: Long? = null,
    /** An interval between two reports carried no packet that way. */
    val emptySentInterval: Boolean = false,
    val emptyReceivedInterval: Boolean = false,
    /** rtpInactivityDetected in any active reading, latched (ImsMedia clears it when packets resume). */
    val inactivitySeen: Boolean = false,
    /** The last report's codec (AUDIO_QUALITY_*). */
    val codecType: Int? = null,
    /** The last report's RTCP round trip, ms; null when 0 (not measured) or not reported. */
    val avgRttMs: Long? = null,
    /** The call was held (or a second call was held beside it) after it went ACTIVE; the window ended there. */
    val heldDuringWindow: Boolean = false,
    /** The duration or a packet counter went backwards between two reports; the window ended there. */
    val countersReset: Boolean = false,
    /** The whole call by the last report in the window: its duration, packets sent and received (net). */
    val callDurationMs: Long = 0,
    val sentTotal: Long = 0,
    val receivedTotal: Long = 0,
)

/**
 * Builds a [MediaWindow] report by report while a call is watched. The
 * registry shows the last report until the next one, so a reading with the
 * same callDuration is the same report and only adds its inactivity flag.
 * A hold or an SRVCC ends the window; later reports are ignored.
 */
class MediaWindowBuilder {
    private var first: CallQualityReading? = null
    private var last: CallQualityReading? = null
    private var reports = 0
    private var maxGap: Long? = null
    private var minSent: Long? = null
    private var minReceived: Long? = null
    private var emptySent = false
    private var emptyReceived = false
    private var inactive = false
    private var held = false
    private var reset = false
    private var ended = false

    /** One reading taken while the call was ACTIVE. */
    fun add(q: CallQualityReading) {
        if (ended || q.durationMs <= 0) return
        if (q.rtpInactive) inactive = true
        val prev = last
        if (prev == null) {
            first = q
            last = q
            reports = 1
            return
        }
        if (q.durationMs == prev.durationMs) return
        val dt = q.durationMs - prev.durationMs
        if (dt < 0 || q.rtpSent < prev.rtpSent || q.rtpReceived < prev.rtpReceived) {
            reset = true
            ended = true
            return
        }
        val dSent = q.rtpSent - prev.rtpSent
        val dReceived = (q.receivedNet - prev.receivedNet).coerceAtLeast(0)
        val sentRate = dSent * MediaRule.PER_10S_MS / dt
        val receivedRate = dReceived * MediaRule.PER_10S_MS / dt
        reports++
        maxGap = maxOf(maxGap ?: 0L, dt)
        minSent = minOf(minSent ?: sentRate, sentRate)
        minReceived = minOf(minReceived ?: receivedRate, receivedRate)
        if (dSent == 0L) emptySent = true
        if (dReceived == 0L) emptyReceived = true
        last = q
    }

    /** The call (or a call beside it) is on hold: the window ends, and the result is "call was on hold". */
    fun hold() {
        held = true
        ended = true
    }

    /** The call left IMS (SRVCC): the window ends here. */
    fun end() {
        ended = true
    }

    val windowMs: Long get() = (last?.durationMs ?: 0L) - (first?.durationMs ?: 0L)

    /** The window is long enough to be judged: at least 3 reports over at least 10 s. */
    val ready: Boolean get() = reports >= MediaRule.MIN_REPORTS && windowMs >= MediaRule.MIN_WINDOW_MS

    fun window(): MediaWindow? {
        val a = first
        val b = last
        if (a == null || b == null) return if (held) MediaWindow(heldDuringWindow = true, inactivitySeen = inactive) else null
        fun delta(x: Long?, y: Long?): Long? = if (x != null && y != null) y - x else null
        return MediaWindow(
            reports = reports,
            windowMs = b.durationMs - a.durationMs,
            maxReportGapMs = maxGap,
            sent = b.rtpSent - a.rtpSent,
            received = (b.receivedNet - a.receivedNet).coerceAtLeast(0),
            duplicates = delta(a.duplicates, b.duplicates),
            sidReceived = delta(a.sidReceived, b.sidReceived),
            notReceived = delta(a.notReceived, b.notReceived),
            minSentPer10s = minSent,
            minReceivedPer10s = minReceived,
            emptySentInterval = emptySent,
            emptyReceivedInterval = emptyReceived,
            inactivitySeen = inactive,
            codecType = b.codecType,
            avgRttMs = b.avgRttMs?.takeIf { it > 0 },
            heldDuringWindow = held,
            countersReset = reset,
            callDurationMs = b.durationMs,
            sentTotal = b.rtpSent,
            receivedTotal = b.receivedNet.coerceAtLeast(0),
        )
    }
}

/** A direction's stream class (round 28). */
@Serializable
enum class StreamClass(val wire: String) {
    @SerialName("full_rate")
    FULL_RATE("full_rate"),

    @SerialName("dtx")
    DTX("dtx"),

    @SerialName("low_unexplained")
    LOW_UNEXPLAINED("low_unexplained"),

    @SerialName("none")
    NONE("none"),
}

/** The media verdict (round 28): only [NO_STREAM] can ever become a measured failure. */
@Serializable
enum class MediaVerdict(val wire: String) {
    @SerialName("pass")
    PASS("pass"),

    @SerialName("not_counted")
    NOT_COUNTED("not_counted"),

    @SerialName("no_stream")
    NO_STREAM("no_stream"),
}

/** How a measured call came about (round 28's call_kind). */
@Serializable
enum class CallKind(val wire: String) {
    /** OCD's automatic, muted call to the SIM's own mailbox. */
    @SerialName("mailbox_auto")
    MAILBOX_AUTO("mailbox_auto"),

    @SerialName("passive_outgoing")
    PASSIVE_OUTGOING("passive_outgoing"),

    @SerialName("passive_incoming")
    PASSIVE_INCOMING("passive_incoming"),

    /** A call between the owner's own lines (not placed by the app today). */
    @SerialName("owner_peer")
    OWNER_PEER("owner_peer"),

    /** Round 27's mailbox notification call (off until its number is in the table; not recorded by the app today). */
    @SerialName("mailbox_notification")
    MAILBOX_NOTIFICATION("mailbox_notification");

    val passive: Boolean get() = this == PASSIVE_OUTGOING || this == PASSIVE_INCOMING
}

/**
 * One call's media result with the data round 28 asks each result to carry.
 * No number, address or SDP text; ptime is a number only (null: unread, so
 * the floor assumes 20 ms).
 */
@Serializable
data class MediaResult(
    @SerialName("call_kind") val callKind: CallKind? = null,
    @SerialName("ims_stack") val imsStack: String,
    val build: String,
    @SerialName("media_rule") val mediaRule: String = MediaRule.VERSION,
    @SerialName("bearer_verdict") val bearerVerdict: BearerVerdict? = null,
    @SerialName("window_ms") val windowMs: Long? = null,
    @SerialName("report_count") val reportCount: Int = 0,
    @SerialName("max_report_gap_ms") val maxReportGapMs: Long? = null,
    @SerialName("sent_window") val sentWindow: Long? = null,
    /** Net of duplicates. */
    @SerialName("recv_window") val recvWindow: Long? = null,
    @SerialName("sent_per_10s") val sentPer10s: Long? = null,
    @SerialName("recv_per_10s") val recvPer10s: Long? = null,
    @SerialName("min_sent_per_10s") val minSentPer10s: Long? = null,
    @SerialName("min_recv_per_10s") val minRecvPer10s: Long? = null,
    /** Null when the stack does not fill the SID counter, never 0 in that case. */
    @SerialName("sid_recv_window") val sidRecvWindow: Long? = null,
    @SerialName("sid_per_10s") val sidPer10s: Long? = null,
    /** Sequence-gap losses; null when the stack does not fill the counter. */
    @SerialName("rtp_not_received_window") val rtpNotReceivedWindow: Long? = null,
    /** not received / (received + not received), in percent with one decimal; null without the counter. */
    @SerialName("loss_percent") val lossPercent: Double? = null,
    @SerialName("duplicates_window") val duplicatesWindow: Long? = null,
    @SerialName("rtp_inactivity_seen") val rtpInactivitySeen: Boolean = false,
    /** rxSilenceDetected and txSilenceDetected: never read, no stack fills them. */
    @SerialName("rx_silence") val rxSilence: String = NOT_FILLED,
    @SerialName("tx_silence") val txSilence: String = NOT_FILLED,
    @SerialName("codec_type") val codecType: Int? = null,
    @SerialName("codec_name") val codecName: String = Codec.name(codecType),
    val ptime: Int? = null,
    /** RTCP round trip, ms; null when 0 (not measured). */
    @SerialName("avg_rtt_ms") val avgRttMs: Long? = null,
    @SerialName("held_during_window") val heldDuringWindow: Boolean = false,
    /** The disconnect cause and code (never the ImsReasonInfo message), and whether this phone ended the call. */
    val disconnect: DisconnectReading? = null,
    @SerialName("by_this_phone") val byThisPhone: Boolean? = null,
    @SerialName("call_duration_ms") val callDurationMs: Long? = null,
    @SerialName("recv_total") val recvTotal: Long? = null,
    @SerialName("sent_class") val sentClass: StreamClass? = null,
    @SerialName("recv_class") val recvClass: StreamClass? = null,
    val verdict: MediaVerdict,
    /** Machine-readable: why the stream was not counted (empty on a pass). */
    val reasons: List<String> = emptyList(),
    /** For Lab review, on any verdict (mailbox_below_full_rate, sent_dtx_assumed_not_verified). */
    val notes: List<String> = emptyList(),
) {
    val passed: Boolean get() = verdict == MediaVerdict.PASS

    private val over: String get() = bearerVerdict?.takeIf { it.counted }?.bearer?.plain ?: "an unproven bearer"

    /** The plain words of one machine reason (round 28's wording; the ones it does not list say what the reason is). */
    fun plain(reason: String): String = when (reason.substringBefore(':')) {
        "window_under_10s", "reports_under_3" -> "connected for less than 15 s"
        "call_on_hold" -> "call was on hold"
        "recv_dtx_not_available", "sent_dtx_not_available" -> "this IMS stack does not report comfort-noise packets"
        "recv_codec_not_measured" -> "codec $codecName not yet measured"
        "recv_loss_over_20_percent" -> "${percent(lossPercent)}% of packets lost"
        "recv_rate_not_explained" -> "receive rate ${recvPer10s ?: 0} per 10 s not explained by comfort-noise packets"
        "sent_rate_not_explained" -> "send rate ${sentPer10s ?: 0} per 10 s too low or interrupted"
        "rtp_inactivity" -> "RTP inactivity was flagged during the call"
        "no_call_quality_reports" -> "no call-quality reports were read"
        "call_quality_counters_reset" -> "the call-quality counters restarted during the call"
        "rtp_counters_not_filled" -> "this IMS stack reported no RTP packets either way"
        "ended_with" -> "the call ended with ${reason.substringAfter(':')}"
        "no_audio_stream_received" -> "no audio packets received"
        else -> reason
    }

    /** Round 28's result text, with the RTCP line when the round trip was measured. */
    val text: String
        get() {
            val main = when (verdict) {
                MediaVerdict.PASS -> buildString {
                    val sent = if (sentClass == StreamClass.DTX) "sent $sentPer10s (low send rate, DTX assumed, not verified)" else "sent $sentPer10s"
                    append("Audio stream both ways (packets only): this phone $sent and received $recvPer10s RTP packets per 10 s over $over.")
                    if (recvClass == StreamClass.DTX) {
                        append(" The low receive rate matches a silent far end: $sidPer10s of $recvPer10s were comfort-noise (SID) packets, ${percent(lossPercent)}% lost.")
                    }
                    append(" No sound was checked.")
                }
                MediaVerdict.NOT_COUNTED -> "Audio stream not judged, retry: ${reasons.map { plain(it) }.distinct().joinToString("; ")}"
                MediaVerdict.NO_STREAM -> "No audio packets received for ${noStreamSeconds} s after the call connected (this phone sent ${sentPer10s ?: 0} per 10 s)."
            }
            val rtcp = avgRttMs?.let { " RTCP round trip $it ms: the far media endpoint (the network or the other phone) received this phone's RTCP reports." }
            return main + rtcp.orEmpty()
        }

    /** How long nothing came: since the call connected when nothing ever came, else over the window. */
    private val noStreamSeconds: Long get() = (if ((recvTotal ?: 0L) == 0L) callDurationMs ?: windowMs ?: 0L else windowMs ?: 0L) / 1000

    companion object {
        const val NOT_FILLED = "not_filled"

        fun percent(value: Double?): String {
            val v = value ?: 0.0
            return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        }
    }
}

object MediaRule {
    /** The media_rule version each result carries. */
    const val VERSION = "round28.1"

    const val FULL_RATE_PER_10S = 300L
    const val DTX_MIN_PER_10S = 40L
    const val SID_MIN_PER_10S = 20L
    const val MAX_LOSS = 0.20
    const val MIN_WINDOW_MS = 10_000L
    const val MIN_REPORTS = 3
    const val PER_10S_MS = 10_000L

    /** The owner build's ImsStack name for Google's AOSP ImsStack with ImsMedia (com.android.imsstack). */
    const val AOSP_IMSSTACK = "aosp_imsstack"

    /**
     * Stacks whose SID and sequence-loss counters a Lab run has shown to be
     * filled: AOSP ImsStack (A50 to S20 silent call, LTE, o2, 2026-10-23:
     * 125 SID of 170 received in 20 s, no sequence loss, AMR-WB). Not PHH, and
     * no stack until its own run: a 0 from an unfilled counter must never read
     * as "no SIDs".
     */
    val DTX_STACKS = setOf(AOSP_IMSSTACK)

    /** Codecs admitted to the DTX class: AMR and AMR-WB. EVS (other SID intervals, TS 26.445) waits for its own measurement. */
    val DTX_CODECS = setOf(Codec.AMR, Codec.AMR_WB)

    /** Notes for Lab review. */
    const val MAILBOX_BELOW_FULL_RATE = "mailbox_below_full_rate"
    const val SENT_DTX_ASSUMED = "sent_dtx_assumed_not_verified"

    /** The full-rate floor per 10 s: 300 at ptime 20, else 60 % of 10000/ptime. */
    fun fullRateFloor(ptimeMs: Int?): Long = if (ptimeMs != null && ptimeMs > 0) 6_000L / ptimeMs else FULL_RATE_PER_10S

    fun dtxAllowed(stack: String): Boolean = stack in DTX_STACKS

    /**
     * Round 28's judgement of one call's [window]. [kind] null: the direction
     * was not known. [disconnect] makes a passive call that ended with a
     * CODE_SIP_* or CODE_MEDIA_* code "not counted" (or "no stream" when
     * nothing was received); the app's own mailbox call keeps its own end
     * rules in the ladder.
     */
    fun judge(
        window: MediaWindow?,
        kind: CallKind?,
        stack: String,
        build: String,
        bearer: BearerVerdict? = null,
        disconnect: DisconnectReading? = null,
        ptimeMs: Int? = null,
    ): MediaResult {
        val base = MediaResult(
            callKind = kind, imsStack = stack, build = build, bearerVerdict = bearer, ptime = ptimeMs,
            disconnect = disconnect, byThisPhone = disconnect?.byThisPhone, verdict = MediaVerdict.NOT_COUNTED,
        )
        val w = window
        if (w == null || w.reports == 0) {
            val held = w?.heldDuringWindow == true
            return base.copy(heldDuringWindow = held, reasons = listOfNotNull("call_on_hold".takeIf { held }, "no_call_quality_reports"))
        }
        val dtxStack = dtxAllowed(stack)
        val rated = w.reports >= 2 && w.windowMs > 0
        fun per10s(n: Long): Long? = if (rated) n * PER_10S_MS / w.windowMs else null
        val sentPer10s = per10s(w.sent)
        val recvPer10s = per10s(w.received)
        // Only a stack whose counters a Lab run verified gives SID and loss; elsewhere they are null, never 0.
        val sid = w.sidReceived.takeIf { dtxStack }
        val notReceived = w.notReceived.takeIf { dtxStack }
        val sidPer10s = sid?.let { per10s(it) }
        val loss = notReceived?.let { nr -> (w.received + nr).takeIf { it > 0 }?.let { nr.toDouble() / it } }
        val reasons = mutableListOf<String>()
        if (w.heldDuringWindow) reasons += "call_on_hold"
        if (w.countersReset) reasons += "call_quality_counters_reset"
        val floor = fullRateFloor(ptimeMs)
        val recv = if (rated) {
            direction(true, w.received, recvPer10s!!, w.minReceivedPer10s, w.emptyReceivedInterval, w.inactivitySeen, floor, dtxStack, w.codecType, sidPer10s, loss, sid != null, notReceived != null)
        } else {
            null
        }
        val sent = if (rated) {
            direction(false, w.sent, sentPer10s!!, w.minSentPer10s, w.emptySentInterval, w.inactivitySeen, floor, dtxStack, w.codecType, null, null, false, false)
        } else {
            null
        }
        var result = base.copy(
            windowMs = w.windowMs, reportCount = w.reports, maxReportGapMs = w.maxReportGapMs,
            sentWindow = w.sent, recvWindow = w.received, sentPer10s = sentPer10s, recvPer10s = recvPer10s,
            minSentPer10s = w.minSentPer10s, minRecvPer10s = w.minReceivedPer10s,
            sidRecvWindow = sid, sidPer10s = sidPer10s, rtpNotReceivedWindow = notReceived,
            lossPercent = loss?.let { Math.round(it * 1000) / 10.0 }, duplicatesWindow = w.duplicates,
            rtpInactivitySeen = w.inactivitySeen, codecType = w.codecType, codecName = Codec.name(w.codecType),
            avgRttMs = w.avgRttMs, heldDuringWindow = w.heldDuringWindow,
            callDurationMs = w.callDurationMs, recvTotal = w.receivedTotal,
            sentClass = sent?.first, recvClass = recv?.first,
        )
        // No audio stream received: zero received over at least 10 s ACTIVE, while this phone sent (its counters are filled).
        if (w.received == 0L && w.windowMs >= MIN_WINDOW_MS && reasons.isEmpty()) {
            if (w.sent > 0) return result.copy(verdict = MediaVerdict.NO_STREAM, reasons = listOf("no_audio_stream_received"))
            return result.copy(reasons = listOf("rtp_counters_not_filled"))
        }
        if (w.windowMs < MIN_WINDOW_MS) reasons += "window_under_10s"
        if (w.reports < MIN_REPORTS) reasons += "reports_under_3"
        recv?.let { reasons += it.second }
        sent?.let { reasons += it.second }
        if (w.inactivitySeen && reasons.none { it.endsWith("rtp_inactivity") }) reasons += "rtp_inactivity"
        if (kind?.passive == true && disconnect != null && (disconnect.sipFailure || disconnect.mediaFailure)) {
            reasons += "ended_with:${disconnect.imsName ?: disconnect.imsCode}"
        }
        val verdict = if (reasons.isEmpty()) MediaVerdict.PASS else MediaVerdict.NOT_COUNTED
        val notes = buildList {
            if (sent?.first == StreamClass.DTX) add(SENT_DTX_ASSUMED)
            if (verdict == MediaVerdict.PASS && kind == CallKind.MAILBOX_AUTO && (recv?.first == StreamClass.DTX || sent?.first == StreamClass.DTX)) {
                add(MAILBOX_BELOW_FULL_RATE)
            }
        }
        result = result.copy(verdict = verdict, reasons = reasons.distinct(), notes = notes)
        return result
    }

    /** One direction's class and, when it is neither full rate nor DTX, why. */
    private fun direction(
        received: Boolean,
        total: Long,
        per10s: Long,
        minPer10s: Long?,
        emptyInterval: Boolean,
        inactivity: Boolean,
        floor: Long,
        dtxStack: Boolean,
        codec: Int?,
        sidPer10s: Long?,
        loss: Double?,
        sidKnown: Boolean,
        lossKnown: Boolean,
    ): Pair<StreamClass, List<String>> {
        val way = if (received) "recv" else "sent"
        // Nothing received at all: the window, the hold or "no stream" says why; nothing sent still has to be explained.
        if (total == 0L && received) return StreamClass.NONE to emptyList()
        if (per10s >= floor) return StreamClass.FULL_RATE to emptyList()
        val none = total == 0L
        if (!dtxStack) return (if (none) StreamClass.NONE else StreamClass.LOW_UNEXPLAINED) to listOf("${way}_dtx_not_available:stack")
        val why = mutableListOf<String>()
        if (received) {
            if (!sidKnown) why += "recv_dtx_not_available:sid_counter"
            if (!lossKnown) why += "recv_dtx_not_available:loss_counter"
            if (codec !in DTX_CODECS) why += "recv_codec_not_measured"
        }
        val rate = buildList {
            if (per10s < DTX_MIN_PER_10S) add("under_40_per_10s")
            if (minPer10s != null && minPer10s < DTX_MIN_PER_10S) add("interval_under_40_per_10s")
            if (inactivity) add("rtp_inactivity")
            if (emptyInterval) add("empty_interval")
            if (received && sidPer10s != null && sidPer10s < SID_MIN_PER_10S) add("sid_under_20_per_10s")
        }
        why += rate.map { "${way}_rate_not_explained:$it" }
        if (received && loss != null && loss > MAX_LOSS) why += "recv_loss_over_20_percent"
        val cls = when {
            why.isEmpty() -> StreamClass.DTX
            none -> StreamClass.NONE
            else -> StreamClass.LOW_UNEXPLAINED
        }
        return cls to why
    }
}
