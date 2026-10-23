package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.BearerVerdict
import io.github.open_carrier_data.check.core.CallKind
import io.github.open_carrier_data.check.core.CallMediaParser
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.Codec
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.MediaResult
import io.github.open_carrier_data.check.core.MediaRule
import io.github.open_carrier_data.check.core.MediaVerdict
import io.github.open_carrier_data.check.core.MediaWindow
import io.github.open_carrier_data.check.core.MediaWindowBuilder
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.StreamClass
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 28's media rule (research/rule-decisions-20261006/round28/ruling.json):
 * the window over all ACTIVE, non-held reports, the full-rate and DTX
 * classes, the DTX class only on AOSP ImsStack, the per-interval minimums,
 * the SID, loss and codec gates, the three verdicts and the exact wording.
 * The real readings are the A50 to S20 silent call on LTE and o2 with AOSP
 * ImsStack, 2026-10-23, read every 5 s for 20 s.
 */
class MediaRuleTest {
    private val aosp = MediaRule.AOSP_IMSSTACK
    private val lte = BearerVerdict(true, ProvenBearer.LTE)
    private val wifi = BearerVerdict(true, ProvenBearer.IWLAN)

    /** One CallQuality report exactly as AOSP ImsStack prints it in `dumpsys telephony.registry`. */
    private fun aospLine(durationMs: Long, sent: Long, received: Long, sid: Long, inactive: Boolean = false, rtt: Long = 98, codec: Int = 2) =
        "mCallQuality=CallQuality: {downlinkCallQualityLevel=0 uplinkCallQualityLevel=0 callDuration=$durationMs " +
            "numRtpPacketsTransmitted=$sent numRtpPacketsReceived=$received numRtpPacketsTransmittedLost=0 numRtpPacketsNotReceived=0 " +
            "averageRelativeJitter=5 maxRelativeJitter=8 averageRoundTripTime=$rtt codecType=$codec rtpInactivityDetected=$inactive " +
            "txSilenceDetected=false rxSilenceDetected=false numVoiceFrames=45 numNoDataFrames=930 numDroppedRtpPackets=0 " +
            "minPlayoutDelayMillis=30 maxPlayoutDelayMillis=65 numRtpSidPacketsReceived=$sid numRtpDuplicatePackets=0}"

    /** The four real reports: received 31/79/127/170, SID 29/60/91/125, sent 217/467/717/967. */
    private val realReports = listOf(
        aospLine(5_001, 217, 31, 29),
        aospLine(10_001, 467, 79, 60),
        aospLine(15_001, 717, 127, 91),
        aospLine(20_001, 967, 170, 125),
    )

    /** A phone block with a call in it; [background] 2 is HOLDING. */
    private fun registry(quality: String?, callState: Int = 2, foreground: Int = 1, background: Int = 0, callType: Int = 13) = """
        |  Phone Id=0
        |    mCallState=$callState
        |    mPreciseCallState=Ringing call state: -1, Foreground call state: $foreground, Background call state: $background, Disconnect cause: -1, Precise disconnect cause: -1
        |    ${quality ?: "mCallQuality=CallQuality: {downlinkCallQualityLevel=0 uplinkCallQualityLevel=0 callDuration=0 numRtpPacketsTransmitted=0 numRtpPacketsReceived=0}"}
        |    mCallNetworkType=$callType
        |  Phone Id=1
        |    mCallState=0
    """.trimMargin()

    private fun sample(text: String, atMs: Long) = RegistryParser.parse(text.lineSequence(), SimSlot.of(0)!!)!!.call.copy(atMs = atMs)

    /** A watch that reads the registry once a second while AOSP reports every 5 s: each report is read five times. */
    private fun watchReal(reports: List<String>, background: (Int) -> Int = { 0 }): CallWatch {
        val watch = CallWatch()
        var t = 0L
        watch.add(sample(registry(null, callState = 0, foreground = 0), t))
        // ACTIVE before the first report: the registry still holds an empty CallQuality.
        for (second in 0 until 5) watch.add(sample(registry(null), ++t * 1_000))
        for ((i, line) in reports.withIndex()) {
            repeat(5) { watch.add(sample(registry(line, background = background(i)), ++t * 1_000)) }
        }
        watch.add(sample(registry(null, callState = 0, foreground = 0), ++t * 1_000))
        return watch
    }

    /**
     * A window from per-report deltas (index 0 is what the first report
     * already counts): received is gross, [dup] counts duplicates inside it.
     */
    private fun window(
        recv: List<Long>,
        sent: List<Long> = recv.map { 250L },
        sid: List<Long>? = null,
        lost: List<Long>? = null,
        dup: List<Long>? = null,
        codec: Int? = Codec.AMR_WB,
        rtt: Long? = 0,
        everyMs: Long = 5_000,
        inactive: Set<Int> = emptySet(),
    ): MediaWindow {
        val b = MediaWindowBuilder()
        var r = 0L
        var s = 0L
        var si = 0L
        var l = 0L
        var d = 0L
        for (i in recv.indices) {
            r += recv[i]
            s += sent[i]
            si += sid?.get(i) ?: 0
            l += lost?.get(i) ?: 0
            d += dup?.get(i) ?: 0
            b.add(
                CallQualityReading(
                    (i + 1) * everyMs + 1, s, r, i in inactive,
                    notReceived = lost?.let { l }, sidReceived = sid?.let { si }, duplicates = dup?.let { d }, codecType = codec, avgRttMs = rtt,
                ),
            )
        }
        return b.window()!!
    }

    private fun judge(
        w: MediaWindow?,
        stack: String = aosp,
        kind: CallKind = CallKind.PASSIVE_INCOMING,
        bearer: BearerVerdict = lte,
        end: DisconnectReading? = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE"),
        ptime: Int? = null,
    ): MediaResult = MediaRule.judge(w, kind, stack, "rom-1", bearer, end, ptime)

    private val texts = mutableListOf<String>()

    private fun MediaResult.said(): MediaResult = also { texts += it.text }

    // --- the readings ---

    @Test
    fun `the real AOSP line is read by key - unknown and missing keys tolerated, the silence flags and transmitted-lost never read`() {
        val q = CallMediaParser.quality("    " + realReports.last())!!
        assertEquals(CallQualityReading(20_001, 967, 170, false, notReceived = 0, sidReceived = 125, duplicates = 0, codecType = 2, avgRttMs = 98), q)
        assertFalse("never read", "Silence" in q.toString() || "TransmittedLost" in q.toString())
        // Further fields, in any order, are ignored; a line cut before its brace still reads.
        val extra = realReports.last().replace("numRtpDuplicatePackets=0}", "numRtpDuplicatePackets=0 someFutureField=7 anotherOne=-3}")
        assertEquals(q, CallMediaParser.quality(extra))
        assertEquals(q, CallMediaParser.quality(realReports.last().removeSuffix("}")))
        // A report with only round 26's keys (PHH-like): the new counters are null, never 0.
        val thin = CallMediaParser.quality("mCallQuality=CallQuality: {callDuration=3000 numRtpPacketsTransmitted=150 numRtpPacketsReceived=147 rtpInactivityDetected=false}")!!
        assertEquals(CallQualityReading(3_000, 150, 147, false), thin)
        assertNull(thin.sidReceived)
        assertNull(thin.notReceived)
        assertNull(thin.codecType)
        assertNull(CallMediaParser.quality("mCallState=0"))
    }

    @Test
    fun `the call watch builds the window from AOSP's 5 s reports read once a second`() {
        val summary = watchReal(realReports).summary()
        val w = summary.media!!
        assertEquals(4, w.reports)
        assertEquals(15_000L, w.windowMs)
        assertEquals(5_000L, w.maxReportGapMs)
        assertEquals(750L, w.sent)
        assertEquals(139L, w.received)
        assertEquals(96L, w.sidReceived)
        assertEquals(0L, w.notReceived)
        assertEquals(0L, w.duplicates)
        // Per interval: 48, 48 and 43 received per 5 s; 250 sent each.
        assertEquals(86L, w.minReceivedPer10s)
        assertEquals(500L, w.minSentPer10s)
        assertFalse(w.emptyReceivedInterval || w.emptySentInterval || w.inactivitySeen || w.heldDuringWindow || w.countersReset)
        assertEquals(Codec.AMR_WB, w.codecType)
        assertEquals(98L, w.avgRttMs)
        assertEquals(20_001L, w.callDurationMs)
        // The summary survives its wire form with the window.
        assertEquals(summary, io.github.open_carrier_data.check.core.CallWatchSummary.decode(summary.encode()))
    }

    // --- pass ---

    @Test
    fun `silent far end on AOSP ImsStack - the real call passes as DTX with the exact wording`() {
        val r = judge(watchReal(realReports).summary().media).said()
        assertEquals(MediaVerdict.PASS, r.verdict)
        assertEquals(StreamClass.FULL_RATE, r.sentClass)
        assertEquals(StreamClass.DTX, r.recvClass)
        assertEquals(500L, r.sentPer10s)
        assertEquals(92L, r.recvPer10s)
        assertEquals(64L, r.sidPer10s)
        assertEquals(96L, r.sidRecvWindow)
        assertEquals(0.0, r.lossPercent!!, 0.0)
        assertEquals(emptyList<String>(), r.reasons)
        assertEquals(
            "Audio stream both ways (packets only): this phone sent 500 and received 92 RTP packets per 10 s over LTE. " +
                "The low receive rate matches a silent far end: 64 of 92 were comfort-noise (SID) packets, 0% lost. No sound was checked. " +
                "RTCP round trip 98 ms: the far media endpoint (the network or the other phone) received this phone's RTCP reports.",
            r.text,
        )
        assertEquals(MediaResult.NOT_FILLED, r.rxSilence)
        assertEquals(MediaResult.NOT_FILLED, r.txSilence)
        assertEquals(MediaRule.VERSION, r.mediaRule)
        assertEquals("AMR-WB", r.codecName)
    }

    @Test
    fun `mailbox call - full rate passes on every stack, DTX passes with a note for Lab review`() {
        val full = window(recv = List(4) { 245L }, sent = List(4) { 250L }, rtt = 0)
        for (stack in listOf(aosp, "phh_ims", "samsung_ims", "other", "not_known")) {
            val r = judge(full, stack = stack, kind = CallKind.MAILBOX_AUTO, bearer = wifi, end = DisconnectReading(501, "CODE_USER_TERMINATED", 3)).said()
            assertEquals(stack, MediaVerdict.PASS, r.verdict)
            assertEquals(StreamClass.FULL_RATE, r.recvClass)
            assertEquals("Audio stream both ways (packets only): this phone sent 500 and received 490 RTP packets per 10 s over Wi-Fi. No sound was checked.", r.text)
            assertTrue(r.notes.isEmpty())
            assertEquals(true, r.byThisPhone)
        }
        // The prompts stop while the menu waits: the DTX class still counts, marked for review.
        val quiet = window(recv = List(4) { 40L }, sid = List(4) { 32L }, lost = List(4) { 0L })
        val dtx = judge(quiet, kind = CallKind.MAILBOX_AUTO, bearer = wifi).said()
        assertEquals(MediaVerdict.PASS, dtx.verdict)
        assertEquals(StreamClass.DTX, dtx.recvClass)
        assertEquals(listOf(MediaRule.MAILBOX_BELOW_FULL_RATE), dtx.notes)
    }

    @Test
    fun `a low send rate on AOSP counts on rate and continuity alone, labelled as assumed`() {
        val w = window(recv = List(4) { 245L }, sent = List(4) { 35L })
        val r = judge(w).said()
        assertEquals(MediaVerdict.PASS, r.verdict)
        assertEquals(StreamClass.DTX, r.sentClass)
        assertEquals(listOf(MediaRule.SENT_DTX_ASSUMED), r.notes)
        assertEquals(
            "Audio stream both ways (packets only): this phone sent 70 (low send rate, DTX assumed, not verified) and received 490 RTP packets per 10 s over LTE. No sound was checked.",
            r.text,
        )
        // An interrupted send stream does not.
        val gap = judge(window(recv = List(4) { 245L }, sent = listOf(35L, 35L, 0L, 35L))).said()
        assertEquals(MediaVerdict.NOT_COUNTED, gap.verdict)
        assertTrue(gap.reasons.toString(), "sent_rate_not_explained:empty_interval" in gap.reasons)
    }

    // --- no stream ---

    @Test
    fun `one-way audio - zero received over at least 10 s is no audio stream, on every stack`() {
        val oneWay = window(recv = List(4) { 0L }, sid = List(4) { 0L }, lost = List(4) { 0L }, inactive = setOf(0, 1, 2, 3))
        for (stack in listOf(aosp, "phh_ims", "other")) {
            val r = judge(oneWay, stack = stack).said()
            assertEquals(stack, MediaVerdict.NO_STREAM, r.verdict)
            assertEquals(StreamClass.NONE, r.recvClass)
            assertEquals(listOf("no_audio_stream_received"), r.reasons)
            assertEquals("No audio packets received for 20 s after the call connected (this phone sent 500 per 10 s).", r.text)
        }
        // From the readings: the S20 sends, nothing comes back.
        val reports = (1..4).map { aospLine(it * 5_000L + 1, it * 250L, 0, 0, inactive = it > 1, rtt = 0) }
        assertEquals(MediaVerdict.NO_STREAM, judge(watchReal(reports).summary().media).verdict)
        // A passive call that ended with CODE_MEDIA_NO_DATA and received nothing is still "no stream".
        assertEquals(MediaVerdict.NO_STREAM, judge(oneWay, end = DisconnectReading(402, "CODE_MEDIA_NO_DATA")).verdict)
        // Under 10 s it is only too short; on hold nothing is expected.
        val short = window(recv = listOf(0L, 0L), sent = listOf(250L, 250L))
        assertEquals(listOf("window_under_10s", "reports_under_3"), judge(short).said().reasons)
        // Counters that stay at 0 both ways are not a measurement.
        val unfilled = judge(window(recv = List(4) { 0L }, sent = List(4) { 0L }), stack = "other").said()
        assertEquals(MediaVerdict.NOT_COUNTED, unfilled.verdict)
        assertEquals("Audio stream not judged, retry: this IMS stack reported no RTP packets either way", unfilled.text)
    }

    // --- not counted ---

    @Test
    fun `a lossy stream is not counted - sequence gaps tell loss from DTX`() {
        // About 50 % of a speech stream lost: few SIDs, heavy loss.
        val lossy = judge(window(recv = List(4) { 125L }, sid = List(4) { 2L }, lost = List(4) { 125L })).said()
        assertEquals(MediaVerdict.NOT_COUNTED, lossy.verdict)
        assertEquals(StreamClass.LOW_UNEXPLAINED, lossy.recvClass)
        assertEquals(50.0, lossy.lossPercent!!, 0.0)
        assertTrue(lossy.reasons.toString(), "recv_loss_over_20_percent" in lossy.reasons && "recv_rate_not_explained:sid_under_20_per_10s" in lossy.reasons)
        assertEquals(
            "Audio stream not judged, retry: receive rate 250 per 10 s not explained by comfort-noise packets; 50% of packets lost",
            lossy.text,
        )
        // Enough SIDs but more than 20 % sequence loss: loss alone.
        val gaps = judge(window(recv = List(4) { 50L }, sid = List(4) { 15L }, lost = List(4) { 20L })).said()
        assertEquals(listOf("recv_loss_over_20_percent"), gaps.reasons)
        assertEquals("Audio stream not judged, retry: 28.6% of packets lost", gaps.text)
        // 20 % exactly still counts.
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 40L }, sid = List(4) { 15L }, lost = List(4) { 10L })).verdict)
    }

    @Test
    fun `a full-rate count with more than 20 % loss is not counted where the loss counter is verified, and passes on PHH`() {
        // 200 received and 100 lost per 5 s: 400 per 10 s over the window, a third of the packets lost.
        val lossyFull = window(recv = List(4) { 200L }, sid = List(4) { 0L }, lost = List(4) { 100L })
        val r = judge(lossyFull).said()
        assertEquals(400L, r.recvPer10s)
        assertEquals(MediaVerdict.NOT_COUNTED, r.verdict)
        assertEquals(StreamClass.LOW_UNEXPLAINED, r.recvClass)
        assertEquals(listOf("recv_loss_over_20_percent"), r.reasons)
        assertEquals(33.3, r.lossPercent!!, 0.0)
        assertEquals("Audio stream not judged, retry: 33.3% of packets lost", r.text)
        assertTrue(MediaRule.lossCounterVerified(aosp))
        // The same counts on the mailbox call: still not counted (the ladder reads it as "not counted, retry").
        assertEquals(listOf("recv_loss_over_20_percent"), judge(lossyFull, kind = CallKind.MAILBOX_AUTO, bearer = wifi).said().reasons)
        // PHH (and any stack without a verified loss counter) keeps the plain 300 floor: a pass, its loss null.
        for (stack in listOf("phh_ims", "samsung_ims", "other", "not_known")) {
            assertFalse(MediaRule.lossCounterVerified(stack))
            val phh = judge(lossyFull, stack = stack).said()
            assertEquals(stack, MediaVerdict.PASS, phh.verdict)
            assertEquals(StreamClass.FULL_RATE, phh.recvClass)
            assertNull(phh.rtpNotReceivedWindow)
            assertNull(phh.lossPercent)
        }
        // 20 % exactly at full rate still passes on AOSP; AOSP without the loss key in its reports keeps the plain floor.
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 200L }, lost = List(4) { 50L })).verdict)
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 200L })).verdict)
        // The sent direction has no loss counter: a full-rate send is never capped.
        assertEquals(StreamClass.FULL_RATE, r.sentClass)
    }

    @Test
    fun `a low rate the SIDs do not explain is not counted`() {
        val r = judge(window(recv = List(4) { 45L }, sid = List(4) { 2L }, lost = List(4) { 0L })).said()
        assertEquals(MediaVerdict.NOT_COUNTED, r.verdict)
        assertEquals(StreamClass.LOW_UNEXPLAINED, r.recvClass)
        assertEquals(listOf("recv_rate_not_explained:sid_under_20_per_10s"), r.reasons)
        assertEquals("Audio stream not judged, retry: receive rate 90 per 10 s not explained by comfort-noise packets", r.text)
        // A trickle under 40 per 10 s, one thin interval, an empty interval or inactivity fail even with SIDs.
        assertTrue("recv_rate_not_explained:under_40_per_10s" in judge(window(recv = List(4) { 15L }, sid = List(4) { 15L }, lost = List(4) { 0L })).said().reasons)
        val thin = judge(window(recv = listOf(50L, 50L, 10L, 50L), sid = List(4) { 15L }, lost = List(4) { 0L })).said()
        assertEquals(listOf("recv_rate_not_explained:interval_under_40_per_10s"), thin.reasons)
        assertEquals(20L, thin.minRecvPer10s)
        val empty = judge(window(recv = listOf(50L, 60L, 0L, 60L), sid = listOf(15L, 20L, 0L, 20L), lost = List(4) { 0L })).said()
        assertTrue(empty.reasons.toString(), "recv_rate_not_explained:empty_interval" in empty.reasons)
        val inactive = judge(window(recv = List(4) { 50L }, sid = List(4) { 15L }, lost = List(4) { 0L }, inactive = setOf(2))).said()
        assertTrue(inactive.reasons.toString(), "recv_rate_not_explained:rtp_inactivity" in inactive.reasons)
        assertTrue(inactive.rtpInactivitySeen)
    }

    @Test
    fun `a window under 10 s or under 3 reports is not counted`() {
        val two = judge(window(recv = listOf(245L, 245L))).said()
        assertEquals(MediaVerdict.NOT_COUNTED, two.verdict)
        assertEquals(listOf("window_under_10s", "reports_under_3"), two.reasons)
        assertEquals("Audio stream not judged, retry: connected for less than 15 s", two.text)
        // Three reports over 8 s, and two reports 10 s apart.
        assertEquals(listOf("window_under_10s"), judge(window(recv = List(3) { 196L }, sent = List(3) { 200L }, everyMs = 4_000)).said().reasons)
        assertEquals(listOf("reports_under_3"), judge(window(recv = listOf(490L, 490L), sent = listOf(500L, 500L), everyMs = 10_000)).said().reasons)
        assertEquals(MediaVerdict.NOT_COUNTED, judge(null).said().verdict)
        assertEquals(listOf("no_call_quality_reports"), judge(null).reasons)
        // One report: nothing to rate.
        assertEquals(listOf("window_under_10s", "reports_under_3"), judge(window(recv = listOf(245L))).reasons)
    }

    @Test
    fun `EVS below the full-rate floor waits for its own measurement, full-rate EVS passes`() {
        val evs = judge(window(recv = List(4) { 45L }, sid = List(4) { 30L }, lost = List(4) { 0L }, codec = Codec.EVS_WB)).said()
        assertEquals(MediaVerdict.NOT_COUNTED, evs.verdict)
        assertEquals(listOf("recv_codec_not_measured"), evs.reasons)
        assertEquals("Audio stream not judged, retry: codec EVS-WB not yet measured", evs.text)
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 245L }, codec = Codec.EVS_SWB)).verdict)
        // AMR (narrowband) is admitted like AMR-WB; a codec the report leaves out is not.
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 45L }, sid = List(4) { 30L }, lost = List(4) { 0L }, codec = Codec.AMR)).verdict)
        assertEquals("Audio stream not judged, retry: codec unknown not yet measured", judge(window(recv = List(4) { 45L }, sid = List(4) { 30L }, lost = List(4) { 0L }, codec = null)).said().text)
    }

    @Test
    fun `PHH and unknown stacks get no DTX class - a low rate is not counted and their SID counter is null, never 0`() {
        // PHH prints numRtpSidPacketsReceived=0 because it does not fill it: the same silent call reads not counted.
        val phhReports = (1..4).map { aospLine(it * 5_000L + 1, it * 250L, it * 46L, 0, rtt = 0, codec = 0) }
        val phh = judge(watchReal(phhReports).summary().media, stack = "phh_ims").said()
        assertEquals(MediaVerdict.NOT_COUNTED, phh.verdict)
        assertEquals(StreamClass.LOW_UNEXPLAINED, phh.recvClass)
        assertEquals(listOf("recv_dtx_not_available:stack"), phh.reasons)
        assertEquals("Audio stream not judged, retry: this IMS stack does not report comfort-noise packets", phh.text)
        assertNull(phh.sidRecvWindow)
        assertNull(phh.rtpNotReceivedWindow)
        assertNull(phh.lossPercent)
        // Even the real AOSP readings on another stack name: no DTX class.
        for (stack in listOf("phh_ims", "samsung_ims", "other", "not_known", "AOSP_IMSSTACK")) {
            val r = judge(watchReal(realReports).summary().media, stack = stack).said()
            assertEquals(stack, MediaVerdict.NOT_COUNTED, r.verdict)
            assertNull(stack, r.sidRecvWindow)
        }
        // A low send rate on PHH is not counted either.
        assertEquals(listOf("sent_dtx_not_available:stack"), judge(window(recv = List(4) { 245L }, sent = List(4) { 140L }), stack = "phh_ims").said().reasons)
        // AOSP without the SID or loss key in its reports: not filled, so no DTX.
        val noKeys = judge(window(recv = List(4) { 45L })).said()
        assertEquals(listOf("recv_dtx_not_available:sid_counter", "recv_dtx_not_available:loss_counter"), noKeys.reasons)
        assertNull(noKeys.sidRecvWindow)
    }

    @Test
    fun `a hold ends the window and the call is not counted`() {
        // The call is put on hold after the second report: the window ends there.
        val held = watchReal(realReports, background = { if (it >= 2) 2 else 0 }).summary().media!!
        assertTrue(held.heldDuringWindow)
        assertEquals(2, held.reports)
        val r = judge(held).said()
        assertEquals(MediaVerdict.NOT_COUNTED, r.verdict)
        assertTrue(r.heldDuringWindow)
        assertEquals("call_on_hold", r.reasons.first())
        assertTrue(r.text, r.text.startsWith("Audio stream not judged, retry: call was on hold"))
        // Nothing received while held is not "no stream".
        val b = MediaWindowBuilder()
        for (i in 1..4) b.add(CallQualityReading(i * 5_000L + 1, i * 250L, 0))
        b.hold()
        assertEquals(MediaVerdict.NOT_COUNTED, judge(b.window()).verdict)
    }

    @Test
    fun `full rate with RTP inactivity, counters that restart, or a passive call ended by a SIP or media code is not counted`() {
        val inactive = judge(window(recv = List(4) { 245L }, inactive = setOf(2))).said()
        assertEquals(listOf("rtp_inactivity"), inactive.reasons)
        assertEquals(StreamClass.FULL_RATE, inactive.recvClass)
        val b = MediaWindowBuilder()
        b.add(CallQualityReading(5_001, 250, 245))
        b.add(CallQualityReading(10_001, 500, 490))
        b.add(CallQualityReading(3_001, 150, 147))
        b.add(CallQualityReading(15_001, 750, 735))
        val reset = b.window()!!
        assertTrue(reset.countersReset)
        assertEquals(2, reset.reports)
        assertEquals("call_quality_counters_reset", judge(reset).said().reasons.first())
        val full = window(recv = List(4) { 245L })
        val media = judge(full, end = DisconnectReading(402, "CODE_MEDIA_NO_DATA")).said()
        assertEquals(listOf("ended_with:CODE_MEDIA_NO_DATA"), media.reasons)
        assertEquals(listOf("ended_with:CODE_SIP_SERVER_ERROR"), judge(full, kind = CallKind.PASSIVE_OUTGOING, end = DisconnectReading(351, "CODE_SIP_SERVER_ERROR")).said().reasons)
        // The mailbox call keeps its own end rules in the ladder; normal clearing by the far end is fine for a passive call.
        assertEquals(MediaVerdict.PASS, judge(full, kind = CallKind.MAILBOX_AUTO, end = DisconnectReading(402, "CODE_MEDIA_NO_DATA")).verdict)
        assertEquals(MediaVerdict.PASS, judge(full, end = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE")).verdict)
    }

    @Test
    fun `duplicates are subtracted, ptime sets the floor, and the RTCP line shows only when measured`() {
        // 600 gross per 5 s with 300 duplicates: 600 net per 10 s.
        val dup = window(recv = List(4) { 600L }, dup = List(4) { 300L })
        assertEquals(900L, dup.received)
        assertEquals(900L, dup.duplicates)
        assertEquals(600L, judge(dup).recvPer10s)
        // A stream that is full rate only before its duplicates come off.
        val padded = judge(window(recv = List(4) { 160L }, dup = List(4) { 60L }), stack = "phh_ims")
        assertEquals(200L, padded.recvPer10s)
        assertEquals(MediaVerdict.NOT_COUNTED, padded.verdict)
        assertEquals(300L, MediaRule.fullRateFloor(null))
        assertEquals(300L, MediaRule.fullRateFloor(20))
        assertEquals(150L, MediaRule.fullRateFloor(40))
        assertEquals(MediaVerdict.PASS, judge(window(recv = List(4) { 100L }, sent = List(4) { 100L }), stack = "phh_ims", ptime = 40).verdict)
        assertEquals(MediaVerdict.NOT_COUNTED, judge(window(recv = List(4) { 100L }, sent = List(4) { 100L }), stack = "phh_ims").verdict)
        assertFalse(judge(window(recv = List(4) { 245L }, rtt = 0)).text.contains("RTCP"))
        assertFalse(judge(window(recv = List(4) { 245L }, rtt = null)).text.contains("RTCP"))
        assertTrue(judge(window(recv = List(4) { 245L }, rtt = 61)).text.endsWith("RTCP round trip 61 ms: the far media endpoint (the network or the other phone) received this phone's RTCP reports."))
    }

    @Test
    fun `a result carries the ruling's fields by name, no number, address or SDP, and never the words reserved for a person`() {
        val r = judge(watchReal(realReports).summary().media)
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(MediaResult.serializer(), r)
        for (field in listOf(
            "\"call_kind\":\"passive_incoming\"", "\"ims_stack\":\"aosp_imsstack\"", "\"build\":\"rom-1\"", "\"media_rule\":\"round28.1\"", "\"bearer_verdict\"",
            "\"window_ms\":15000", "\"report_count\":4", "\"max_report_gap_ms\":5000", "\"sent_window\":750", "\"recv_window\":139", "\"sent_per_10s\":500",
            "\"recv_per_10s\":92", "\"min_sent_per_10s\":500", "\"min_recv_per_10s\":86", "\"sid_recv_window\":96", "\"rtp_not_received_window\":0",
            "\"duplicates_window\":0", "\"rtp_inactivity_seen\":false", "\"rx_silence\":\"not_filled\"", "\"tx_silence\":\"not_filled\"", "\"codec_type\":2",
            "\"codec_name\":\"AMR-WB\"", "\"ptime\":null", "\"avg_rtt_ms\":98", "\"held_during_window\":false", "\"disconnect\"", "\"by_this_phone\":false",
            "\"sent_class\":\"full_rate\"", "\"recv_class\":\"dtx\"", "\"verdict\":\"pass\"", "\"reasons\":[]",
        )) {
            assertTrue("$field in $text", field in text)
        }
        assertEquals(r, json.decodeFromString(MediaResult.serializer(), text))
        assertTrue("\"sid_recv_window\":null" in json.encodeToString(MediaResult.serializer(), judge(window(recv = List(4) { 45L }), stack = "phh_ims")))
        for (banned in listOf("number", "address", "sdp", "msisdn")) assertFalse(banned, banned in text.lowercase())
    }

    @Test
    fun `every pass text says packets only and no sound checked, and no text uses the words reserved for a person`() {
        // Gather the texts of the other cases too.
        `silent far end on AOSP ImsStack - the real call passes as DTX with the exact wording`()
        `mailbox call - full rate passes on every stack, DTX passes with a note for Lab review`()
        `a low send rate on AOSP counts on rate and continuity alone, labelled as assumed`()
        `one-way audio - zero received over at least 10 s is no audio stream, on every stack`()
        `a lossy stream is not counted - sequence gaps tell loss from DTX`()
        `a full-rate count with more than 20 % loss is not counted where the loss counter is verified, and passes on PHH`()
        `a low rate the SIDs do not explain is not counted`()
        `a window under 10 s or under 3 reports is not counted`()
        `EVS below the full-rate floor waits for its own measurement, full-rate EVS passes`()
        `PHH and unknown stacks get no DTX class - a low rate is not counted and their SID counter is null, never 0`()
        `a hold ends the window and the call is not counted`()
        `full rate with RTP inactivity, counters that restart, or a passive call ended by a SIP or media code is not counted`()
        assertTrue(texts.size > 30)
        val banned = listOf("speech", "heard", "hear each other", "two-way audio confirmed", "audio works", "delivered", "the other side")
        for (t in texts) {
            for (word in banned) assertFalse("$word in $t", word in t.lowercase())
            if (t.startsWith("Audio stream both ways")) assertTrue(t, "(packets only)" in t && "No sound was checked." in t)
        }
        assertTrue(texts.any { it.startsWith("Audio stream both ways") })
        assertTrue(texts.any { it.startsWith("Audio stream not judged, retry: ") })
        assertTrue(texts.any { it.startsWith("No audio packets received for ") })
    }
}
