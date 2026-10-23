package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallKind
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.MediaRule
import io.github.open_carrier_data.check.core.MediaVerdict
import io.github.open_carrier_data.check.core.MediaWindowBuilder
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RtpFlow
import io.github.open_carrier_data.check.core.StreamClass
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.CallsLevel
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Provisional
import io.github.open_carrier_data.check.owner.core.ProvisionalDecision
import io.github.open_carrier_data.check.owner.core.ProvisionalKeep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Round 28 in the owner build's ladder: the media rule decides L2 and L3's
 * audio for the mailbox call and for passive real calls alike; only "no
 * audio stream received" can become a measured failure, at once on the
 * mailbox call and after two calls on separate days for a passive real call.
 */
class MediaLevelsTest {
    private val sim = OwnerFixtures.SIM.key
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = TimeUnit.DAYS.toMillis(1)
    private val aosp = MediaRule.AOSP_IMSSTACK

    /** 2026-10-21 03:00 in Berlin. */
    private val applied = ZonedDateTime.of(2026, 10, 21, 3, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun reading(bearer: ProvenBearer, type: Int = bearer.callNetworkType) =
        if (bearer == ProvenBearer.IWLAN) BearerReading(type, listOf(ImsTransport.IWLAN), "lte") else BearerReading(type, listOf(ImsTransport.LTE), "lte")

    /** A measured call with AOSP ImsMedia's 5 s reports: per report [recv] received, [sent] sent, [sid] SID and [lost] lost. */
    private fun call(
        at: Long,
        direction: CallDirection,
        method: CallMethod = CallMethod.REAL_CALL,
        bearer: ProvenBearer = ProvenBearer.IWLAN,
        stack: String = aosp,
        recv: Long = 245,
        sent: Long = 250,
        sid: Long = 0,
        lost: Long = 0,
        reports: Int = 7,
        end: DisconnectReading = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE"),
        atActive: BearerReading? = reading(bearer),
        fromMailbox: Boolean? = if (direction == CallDirection.INCOMING) false else null,
        hungUp: Boolean? = null,
    ): CallRecord {
        val b = MediaWindowBuilder()
        for (i in 1..reports) {
            b.add(CallQualityReading(i * 5_000L + 1, i * sent, i * recv, notReceived = i * lost, sidReceived = i * sid, duplicates = 0, codecType = 2, avgRttMs = 0))
        }
        val activeMs = reports * 5_000L + 1
        val proof = BearerProof.of(true, atActive, atActive)
        return CallRecord(
            atMs = at, slot = 0, sim = sim, method = method, direction = direction,
            rang = direction == CallDirection.INCOMING, answered = true, active = true,
            bearerAtStart = reading(bearer, 0), bearerAtActive = atActive, bearerAtHangUp = atActive, bearer = proof,
            rtp = RtpFlow.of(CallQualityReading(5_001, sent, recv), CallQualityReading(activeMs, reports * sent, reports * recv), false),
            media = MediaRule.judge(b.window(), CallRecord.kindOf(method, direction), stack, "rom-1", proof, end),
            activeMs = activeMs, disconnect = end, disconnectFresh = true, hungUpByPhone = hungUp, fromMailbox = fromMailbox,
            build = "rom-1", stack = stack,
        )
    }

    private fun mailbox(at: Long, recv: Long = 245, sid: Long = 0, stack: String = aosp, bearer: ProvenBearer = ProvenBearer.IWLAN) = call(
        at, CallDirection.OUTGOING, method = CallMethod.MAILBOX_CALL, bearer = bearer, stack = stack, recv = recv, sid = sid, reports = 3,
        end = DisconnectReading(501, "CODE_USER_TERMINATED", 3), hungUp = true,
    )

    /** The A50 to S20 silent call: about 46 received, 31 SID and 250 sent per 5 s, no loss. */
    private fun silentFarEnd(at: Long, stack: String = aosp, bearer: ProvenBearer = ProvenBearer.IWLAN) =
        call(at, CallDirection.INCOMING, stack = stack, bearer = bearer, recv = 46, sid = 31)

    private fun noStream(at: Long, direction: CallDirection = CallDirection.INCOMING, bearer: ProvenBearer = ProvenBearer.IWLAN, atActive: BearerReading? = reading(bearer), fromMailbox: Boolean? = null) =
        call(at, direction, bearer = bearer, recv = 0, atActive = atActive, fromMailbox = fromMailbox ?: if (direction == CallDirection.INCOMING) false else null)

    private fun keep(callsOutAt: Long? = null, callsOutBy: Long = Long.MAX_VALUE) =
        ProvisionalKeep(0, sim, OwnerFeature.VOWIFI, true, ProvenBearer.IWLAN, applied, callsOutBy, callsOutAt)

    @Test
    fun `call kinds - the mailbox call, passive outgoing and passive incoming`() {
        assertEquals(CallKind.MAILBOX_AUTO, CallRecord.kindOf(CallMethod.MAILBOX_CALL, CallDirection.OUTGOING))
        assertEquals(CallKind.PASSIVE_OUTGOING, CallRecord.kindOf(CallMethod.REAL_CALL, CallDirection.OUTGOING))
        assertEquals(CallKind.PASSIVE_INCOMING, CallRecord.kindOf(CallMethod.REAL_CALL, CallDirection.INCOMING))
        assertNull(CallRecord.kindOf(CallMethod.REAL_CALL, CallDirection.UNKNOWN))
    }

    @Test
    fun `a silent far end counts as the incoming leg on AOSP ImsStack, so the level is works`() {
        val incoming = silentFarEnd(applied + 60_000)
        assertEquals(StreamClass.DTX, incoming.media!!.recvClass)
        assertTrue(CallLevels.incoming(incoming).toString(), CallLevels.incoming(incoming).passed)
        val out = mailbox(applied)
        assertTrue(CallLevels.callsOut(out).passed)
        assertEquals(CallsLevel.WORKS, CallLevels.level(listOf(out, incoming), sim, OwnerFeature.VOWIFI, "rom-1", 0, applied + day))
        // On PHH the same call is not counted: calls out only, and nothing reverts.
        val phh = silentFarEnd(applied + 60_000, stack = "phh_ims")
        assertEquals("not_counted_retry", CallLevels.incoming(phh).result)
        assertEquals(listOf("media:recv_dtx_not_available:stack"), CallLevels.incoming(phh).notCounted)
        assertEquals(CallsLevel.CALLS_OUT, CallLevels.level(listOf(out, phh), sim, OwnerFeature.VOWIFI, "rom-1", 0, applied + day))
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(keep(callsOutAt = out.atMs), listOf(out, phh), "rom-1", applied + day, zone))
        // A passive outgoing call with a silent callee counts as calls out the same way.
        val quietCallee = call(applied, CallDirection.OUTGOING, recv = 46, sid = 31)
        assertTrue(CallLevels.callsOut(quietCallee).passed)
    }

    @Test
    fun `the mailbox call - full rate and DTX pass, a low unexplained rate is not counted, nothing received fails at once`() {
        assertTrue(CallLevels.callsOut(mailbox(applied)).passed)
        val quiet = mailbox(applied, recv = 40, sid = 32)
        assertTrue(CallLevels.callsOut(quiet).passed)
        assertEquals(listOf(MediaRule.MAILBOX_BELOW_FULL_RATE), quiet.media!!.notes)
        // Was "failed" under round 26; now "not counted, retry".
        val thin = mailbox(applied, recv = 45, sid = 2)
        assertEquals("not_counted_retry", CallLevels.callsOut(thin).result)
        assertEquals(listOf("media:recv_rate_not_explained:sid_under_20_per_10s"), CallLevels.callsOut(thin).notCounted)
        assertEquals("not_counted_retry", CallLevels.callsOut(mailbox(applied, recv = 45, stack = "phh_ims")).result)
        val silent = mailbox(applied + 1, recv = 0)
        assertEquals(MediaVerdict.NO_STREAM, silent.media!!.verdict)
        assertEquals("failed", CallLevels.callsOut(silent).result)
        assertEquals(listOf("media:no_audio_stream_received"), CallLevels.callsOut(silent).failed)
        assertEquals(ProvisionalDecision.Revert("calls_out_failed"), Provisional.decide(keep(), listOf(silent), "rom-1", applied + 60_000, zone))
        // A thin mailbox call is no failure: the keep waits.
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(keep(), listOf(thin), "rom-1", applied + 60_000, zone))
    }

    @Test
    fun `passive incoming - one call without an audio stream is no failure, two on separate days on the same SIM and bearer are`() {
        val out = mailbox(applied)
        val p = keep(callsOutAt = out.atMs)
        val first = noStream(applied + day)
        assertEquals("not_counted_retry", CallLevels.incoming(first).result)
        assertEquals(listOf("media:no_audio_stream_received"), CallLevels.incoming(first).notCounted)
        assertNull(CallLevels.incomingFailure(first))
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, first), "rom-1", applied + day + 1, zone))
        // The same day twice: still one day.
        val sameDay = noStream(applied + day + TimeUnit.HOURS.toMillis(2))
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, first, sameDay), "rom-1", applied + day + 1, zone))
        // A second day: a measured incoming failure, and the keep reverts.
        val second = noStream(applied + 2 * day)
        assertTrue(CallLevels.noStreamTwice(listOf(first, second), CallDirection.INCOMING, ProvenBearer.IWLAN, zone))
        assertEquals(
            ProvisionalDecision.Revert("incoming_failed:no_audio_stream_received_twice"),
            Provisional.decide(p, listOf(out, first, second), "rom-1", applied + 2 * day + 1, zone),
        )
        // Not on the keep's bearer, without the bearer proof, from a mailbox code, or before the apply: no.
        val onLte = noStream(applied + 2 * day, bearer = ProvenBearer.LTE)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, first, onLte), "rom-1", applied + 2 * day + 1, zone))
        val unproven = noStream(applied + 2 * day, atActive = BearerReading(13, listOf(ImsTransport.IWLAN), "lte"))
        assertFalse(unproven.bearer!!.counted)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, first, unproven), "rom-1", applied + 2 * day + 1, zone))
        val fromMailbox = noStream(applied + 2 * day, fromMailbox = true)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, first, fromMailbox), "rom-1", applied + 2 * day + 1, zone))
        val before = noStream(applied - day)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(before, out, first), "rom-1", applied + day + 1, zone))
        // Low or lossy results never count as failures, however often.
        val lossy = (1..3).map { call(applied + it * day, CallDirection.INCOMING, recv = 125, sid = 2, lost = 125) }
        assertTrue(lossy.all { CallLevels.incoming(it).result == "not_counted_retry" })
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out) + lossy, "rom-1", applied + 4 * day, zone))
    }

    @Test
    fun `passive outgoing - no audio stream twice on separate days is a measured calls-out failure, once is not`() {
        val p = keep()
        val once = noStream(applied + 60_000, CallDirection.OUTGOING)
        assertEquals("not_counted_retry", CallLevels.callsOut(once).result)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(once), "rom-1", applied + 120_000, zone))
        val again = noStream(applied + day, CallDirection.OUTGOING)
        assertEquals(ProvisionalDecision.Revert("calls_out_failed"), Provisional.decide(p, listOf(once, again), "rom-1", applied + day + 1, zone))
        // "Separate days" are the owner's days: 23:30 and 00:30 in Berlin are two.
        val late = ZonedDateTime.of(2026, 10, 22, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val calls = listOf(noStream(late, CallDirection.OUTGOING), noStream(late + TimeUnit.HOURS.toMillis(1), CallDirection.OUTGOING))
        assertTrue(CallLevels.noStreamTwice(calls, CallDirection.OUTGOING, ProvenBearer.IWLAN, zone))
        assertFalse(CallLevels.noStreamTwice(calls, CallDirection.OUTGOING, ProvenBearer.IWLAN, ZoneId.of("UTC")))
    }

    @Test
    fun `a passive call that ended with a SIP or media code is not counted, and with nothing received it is still no stream`() {
        val media = call(applied, CallDirection.INCOMING, end = DisconnectReading(402, "CODE_MEDIA_NO_DATA"))
        assertEquals("not_counted_retry", CallLevels.incoming(media).result)
        assertEquals(listOf("media:ended_with:CODE_MEDIA_NO_DATA"), CallLevels.incoming(media).notCounted)
        val sip = call(applied, CallDirection.OUTGOING, end = DisconnectReading(351, "CODE_SIP_SERVER_ERROR"))
        assertEquals("not_counted_retry", CallLevels.callsOut(sip).result)
        val silent = call(applied, CallDirection.INCOMING, recv = 0, end = DisconnectReading(402, "CODE_MEDIA_NO_DATA"))
        assertEquals(MediaVerdict.NO_STREAM, silent.media!!.verdict)
        // A record whose disconnect changed after its media result was made: the ladder still sees the end.
        val copied = call(applied, CallDirection.INCOMING).copy(disconnect = DisconnectReading(338, "CODE_SIP_BUSY"))
        assertEquals(listOf("ended_with:CODE_SIP_BUSY"), CallLevels.incoming(copied).notCounted)
    }

    @Test
    fun `records made before round 28 keep round 26's pass and read zero received as no stream`() {
        val pass = call(applied, CallDirection.OUTGOING, reports = 4).copy(media = null)
        assertTrue(pass.rtp!!.passed)
        assertTrue(CallLevels.callsOut(pass).passed)
        val thin = call(applied, CallDirection.OUTGOING, recv = 46, sid = 31).copy(media = null)
        assertEquals("not_counted_retry", CallLevels.callsOut(thin).result)
        assertEquals(listOf("media:round26:received_92_per_10s_under_300"), CallLevels.callsOut(thin).notCounted)
        val silent = mailbox(applied, recv = 0).copy(media = null)
        assertEquals(MediaVerdict.NO_STREAM, CallLevels.media(silent).verdict)
        assertEquals("failed", CallLevels.callsOut(silent).result)
        assertEquals(MediaVerdict.NOT_COUNTED, CallLevels.media(silent.copy(rtp = null)).verdict)
    }

    @Test
    fun `the Details line says packets only and no sound checked, and the media reasons in plain words`() {
        val works = CallLevels.line(silentFarEnd(applied), zone)
        assertTrue(works, works.startsWith("Incoming call over Wi-Fi, 2026-10-21: answered incoming call with an audio stream both ways (packets). Audio stream both ways (packets only): this phone sent 500 and received 92 RTP packets per 10 s over Wi-Fi."))
        assertTrue(works, works.endsWith("The low receive rate matches a silent far end: 62 of 92 were comfort-noise (SID) packets, 0% lost. No sound was checked."))
        val phh = CallLevels.line(silentFarEnd(applied, stack = "phh_ims"), zone)
        assertEquals("Incoming call over Wi-Fi, 2026-10-21: not counted. Audio stream not judged, retry: this IMS stack does not report comfort-noise packets", phh)
        val silent = CallLevels.line(mailbox(applied, recv = 0), zone)
        assertEquals(
            "Automatic call to your mailbox over Wi-Fi, 2026-10-21: did not work. No audio packets received for 15 s after the call connected (this phone sent 500 per 10 s).",
            silent,
        )
        val out = CallLevels.line(mailbox(applied), zone)
        assertTrue(out, out.startsWith("Automatic call to your mailbox over Wi-Fi, 2026-10-21: calls out work (audio from packet counts). Audio stream both ways (packets only)"))
        for (line in listOf(works, phh, silent, out)) {
            assertFalse(line, "media:" in line)
            for (word in listOf("speech", "heard", "audio works", "delivered", "worked both ways")) assertFalse("$word: $line", word in line.lowercase())
        }
    }

    @Test
    fun `the ledger keeps the media result through the state's wire form, without a number`() {
        val state = OwnerState(calls = listOf(silentFarEnd(applied), mailbox(applied + 1), noStream(applied + 2)))
        val text = state.encode()
        assertEquals(state, OwnerState.decode(text))
        assertTrue(text, "\"recv_class\":\"dtx\"" in text && "\"call_kind\":\"passive_incoming\"" in text && "\"rx_silence\":\"not_filled\"" in text)
        for (key in listOf("\"number\"", "\"msisdn\"", "\"address\"", "\"sdp\"")) assertFalse(key, key in text)
        // A state written before round 28 (no media) still reads.
        val tree = Json.parseToJsonElement(text).jsonObject
        val calls = tree.getValue("calls").jsonArray.map { JsonObject(it.jsonObject - "media") }
        val old = OwnerState.decode(JsonObject(tree + ("calls" to JsonArray(calls))).toString())
        assertTrue(old.calls.all { it.media == null })
        assertEquals(3, old.calls.size)
    }
}
