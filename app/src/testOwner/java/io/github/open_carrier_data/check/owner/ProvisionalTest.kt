package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RtpFlow
import io.github.open_carrier_data.check.owner.core.CallLedger
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.CallsLevel
import io.github.open_carrier_data.check.owner.core.CallsOutNight
import io.github.open_carrier_data.check.owner.core.CallsOutPlan
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Provisional
import io.github.open_carrier_data.check.owner.core.ProvisionalDecision
import io.github.open_carrier_data.check.owner.core.ProvisionalKeep
import io.github.open_carrier_data.check.owner.core.Retested
import io.github.open_carrier_data.check.owner.core.RetestedEntry
import io.github.open_carrier_data.check.owner.core.provisionalSettled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Round 26's levels and the provisional keep (automatic checks 5 and 6):
 * calls out (L2) from a passive real call or the app's mailbox call alike,
 * works (L3) with an answered incoming call of at least 20 s on the same
 * bearer, and a changed voice value kept past the night only after calls out,
 * reverted on a measured incoming failure or after 7 days without incoming.
 */
class ProvisionalTest {
    private val sim = OwnerFixtures.SIM.key
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = TimeUnit.DAYS.toMillis(1)

    /** 2026-10-21 03:00 in Berlin: inside the night window. */
    private val applied = ZonedDateTime.of(2026, 10, 21, 3, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun reading(bearer: ProvenBearer, type: Int = bearer.callNetworkType) =
        if (bearer == ProvenBearer.IWLAN) BearerReading(type, listOf(ImsTransport.IWLAN), "lte") else BearerReading(type, listOf(ImsTransport.LTE), "lte")

    private fun call(
        at: Long,
        direction: CallDirection,
        bearer: ProvenBearer = ProvenBearer.IWLAN,
        method: CallMethod = CallMethod.REAL_CALL,
        answered: Boolean = true,
        active: Boolean = true,
        activeMs: Long = 45_000,
        rate: Long = 50,
        end: DisconnectReading = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE"),
        atActive: BearerReading? = reading(bearer),
        startBearer: ProvenBearer = bearer,
        fromMailbox: Boolean? = if (direction == CallDirection.INCOMING) false else null,
        build: String = "rom-1",
        hungUp: Boolean? = null,
    ): CallRecord {
        val first = CallQualityReading(1_000, rate, rate)
        val last = CallQualityReading(activeMs, rate * activeMs / 1000, rate * activeMs / 1000)
        return CallRecord(
            atMs = at, slot = 0, sim = sim, method = method, direction = direction,
            rang = direction == CallDirection.INCOMING, answered = answered, active = active,
            bearerAtStart = reading(startBearer, 0), bearerAtActive = atActive, bearerAtHangUp = atActive,
            bearer = BearerProof.of(active, atActive, atActive),
            rtp = if (active) RtpFlow.of(first, last, false) else null,
            activeMs = if (active) activeMs else null,
            disconnect = end, disconnectFresh = true, hungUpByPhone = hungUp, fromMailbox = fromMailbox,
            build = build, stack = "phh_ims",
        )
    }

    private fun keep(callsOutAt: Long? = null) = ProvisionalKeep(0, sim, OwnerFeature.VOWIFI, true, ProvenBearer.IWLAN, applied, Provisional.nightEnd(applied, zone), callsOutAt)

    // --- the levels ---

    @Test
    fun `calls out - a real outgoing call and the app's mailbox call are equal evidence`() {
        val real = call(applied, CallDirection.OUTGOING)
        assertTrue(CallLevels.callsOut(real).passed)
        assertEquals(ProvenBearer.IWLAN, CallLevels.callsOut(real).bearer)
        val mailbox = call(applied, CallDirection.OUTGOING, method = CallMethod.MAILBOX_CALL, end = DisconnectReading(501, "CODE_USER_TERMINATED"), activeMs = 15_000, hungUp = true)
        assertTrue(CallLevels.callsOut(mailbox).passed)
        // The mailbox call must have been ended by the phone itself.
        assertEquals(listOf("not_hung_up_by_the_phone"), CallLevels.callsOut(mailbox.copy(hungUpByPhone = false)).failed)
        // A SIP failure, thin RTP or an abnormal end fails; an unproven bearer does not count.
        assertEquals("failed", CallLevels.callsOut(real.copy(disconnect = DisconnectReading(338, "CODE_SIP_BUSY"))).result)
        assertEquals("failed", CallLevels.callsOut(call(applied, CallDirection.OUTGOING, rate = 20)).result)
        assertEquals("failed", CallLevels.callsOut(real.copy(disconnect = DisconnectReading(1510, "CODE_MEDIA_NO_DATA"))).result)
        val unproven = call(applied, CallDirection.OUTGOING, atActive = BearerReading(13, listOf(ImsTransport.IWLAN), "lte"))
        assertEquals("not_counted_retry", CallLevels.callsOut(unproven).result)
        // A real call nobody answered, or a busy callee, says nothing about this phone; the mailbox always answers.
        assertEquals("not_counted_retry", CallLevels.callsOut(call(applied, CallDirection.OUTGOING, active = false)).result)
        assertEquals("not_counted_retry", CallLevels.callsOut(call(applied, CallDirection.OUTGOING, active = false, end = DisconnectReading(338, "CODE_SIP_BUSY"))).result)
        assertEquals("failed", CallLevels.callsOut(mailbox.copy(active = false, rtp = null)).result)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(keep(), listOf(call(applied + 1, CallDirection.OUTGOING, active = false, end = DisconnectReading(338, "CODE_SIP_BUSY"))), "rom-1", applied + 60_000))
        // An incoming call is never calls out.
        assertFalse(CallLevels.callsOut(call(applied, CallDirection.INCOMING)).passed)
    }

    @Test
    fun `the incoming leg - rang, answered, not a mailbox, ACTIVE with the bearer proven, RTP both ways, at least 20 s`() {
        assertTrue(CallLevels.incoming(call(applied, CallDirection.INCOMING)).passed)
        assertEquals(listOf("under_20s"), CallLevels.incoming(call(applied, CallDirection.INCOMING, activeMs = 19_000)).notCounted)
        assertTrue(CallLevels.incoming(call(applied, CallDirection.INCOMING, activeMs = 20_000)).passed)
        assertEquals(listOf("from_mailbox"), CallLevels.incoming(call(applied, CallDirection.INCOMING, fromMailbox = true)).notCounted)
        assertEquals("not_counted_retry", CallLevels.incoming(call(applied, CallDirection.INCOMING, answered = false, active = false)).result)
        assertEquals("failed", CallLevels.incoming(call(applied, CallDirection.INCOMING, rate = 10)).result)
    }

    @Test
    fun `measured incoming failures - answered but not ACTIVE, the wrong bearer, a drop under 20 s - and declined or missed calls are none`() {
        assertEquals("answered_not_active", CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, active = false))?.kind)
        // IMS on Wi-Fi when it rang, the call came over 2G/3G or over LTE.
        assertEquals("active_on_wrong_bearer", CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, atActive = BearerReading(16, listOf(ImsTransport.IWLAN), "lte")))?.kind)
        assertEquals("active_on_wrong_bearer", CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, bearer = ProvenBearer.LTE, startBearer = ProvenBearer.IWLAN))?.kind)
        assertEquals("dropped_under_20s", CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, activeMs = 8_000, end = DisconnectReading(1510, "CODE_MEDIA_NO_DATA")))?.kind)
        // A short call the caller ended normally, a declined or a missed call: no failure.
        assertNull(CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, activeMs = 8_000)))
        assertNull(CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, answered = false, active = false)))
        assertNull(CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, answered = false, active = false, end = DisconnectReading(0, "CODE_UNSPECIFIED", cause = 16))))
        // Readings that disagree say nothing; an outgoing call is never an incoming failure.
        assertNull(CallLevels.incomingFailure(call(applied, CallDirection.INCOMING, atActive = BearerReading(13, listOf(ImsTransport.IWLAN), "lte"))))
        assertNull(CallLevels.incomingFailure(call(applied, CallDirection.OUTGOING, active = false)))
    }

    @Test
    fun `the level - calls out alone, works with an incoming leg on the same bearer and build`() {
        val out = call(applied, CallDirection.OUTGOING)
        val incoming = call(applied + 60_000, CallDirection.INCOMING)
        val now = applied + day
        assertEquals(CallsLevel.CALLS_OUT, CallLevels.level(listOf(out), sim, OwnerFeature.VOWIFI, "rom-1", 0, now))
        assertEquals(CallsLevel.WORKS, CallLevels.level(listOf(out, incoming), sim, OwnerFeature.VOWIFI, "rom-1", 0, now))
        // Another bearer, another build, older than 90 days, or before a keep: nothing.
        assertNull(CallLevels.level(listOf(out, incoming), sim, OwnerFeature.VOLTE, "rom-1", 0, now))
        assertNull(CallLevels.level(listOf(out, incoming), sim, OwnerFeature.VOWIFI, "rom-2", 0, now))
        assertNull(CallLevels.level(listOf(out), sim, OwnerFeature.VOWIFI, "rom-1", 0, applied + 91 * day))
        assertNull(CallLevels.level(listOf(out), sim, OwnerFeature.VOWIFI, "rom-1", applied + 1, now))
        // An incoming call alone is not calls out.
        assertNull(CallLevels.level(listOf(incoming), sim, OwnerFeature.VOWIFI, "rom-1", 0, now))
    }

    // --- the provisional keep ---

    @Test
    fun `a changed voice value needs calls out by the end of its night`() {
        val p = keep()
        assertEquals(ZonedDateTime.of(2026, 10, 21, 5, 0, 0, 0, zone).toInstant().toEpochMilli(), p.callsOutByMs)
        // Applied in the afternoon (a trigger after three days): calls out by the next morning's 05:00.
        val afternoon = ZonedDateTime.of(2026, 10, 21, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 22, 5, 0, 0, 0, zone).toInstant().toEpochMilli(), Provisional.nightEnd(afternoon, zone))
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, emptyList(), "rom-1", applied + 60_000))
        assertEquals(ProvisionalDecision.Revert("no_calls_out_by_the_end_of_the_night"), Provisional.decide(p, emptyList(), "rom-1", p.callsOutByMs))
        // A call before the apply says nothing about the new value.
        assertEquals(ProvisionalDecision.Revert("no_calls_out_by_the_end_of_the_night"), Provisional.decide(p, listOf(call(applied - 1, CallDirection.OUTGOING)), "rom-1", p.callsOutByMs))
        // A measured outgoing failure on its bearer reverts at once.
        assertEquals(ProvisionalDecision.Revert("calls_out_failed"), Provisional.decide(p, listOf(call(applied + 1, CallDirection.OUTGOING, rate = 10)), "rom-1", applied + 60_000))
    }

    @Test
    fun `after calls out it waits for incoming - promoted on an incoming call, reverted after 7 days without one`() {
        val p = keep()
        val out = call(applied + 60_000, CallDirection.OUTGOING, method = CallMethod.MAILBOX_CALL, end = DisconnectReading(501, "CODE_USER_TERMINATED"), activeMs = 15_000, hungUp = true)
        assertEquals(ProvisionalDecision.CallsOut(out.atMs), Provisional.decide(p, listOf(out), "rom-1", applied + 120_000))
        val waiting = p.copy(callsOutAtMs = out.atMs)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(waiting, listOf(out), "rom-1", applied + 6 * day))
        assertEquals(ProvisionalDecision.Revert("no_incoming_in_7_days"), Provisional.decide(waiting, listOf(out), "rom-1", applied + 7 * day))
        val incoming = call(applied + 2 * day, CallDirection.INCOMING)
        assertEquals(ProvisionalDecision.Promote(incoming.atMs), Provisional.decide(waiting, listOf(out, incoming), "rom-1", applied + 2 * day + 1))
        // Declined and missed calls are not failures: it keeps waiting.
        val declined = call(applied + day, CallDirection.INCOMING, answered = false, active = false, end = DisconnectReading(0, "CODE_UNSPECIFIED", cause = 16))
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(waiting, listOf(out, declined), "rom-1", applied + 2 * day))
    }

    @Test
    fun `a measured incoming failure on the keep's bearer reverts it, one on another bearer does not`() {
        val p = keep(callsOutAt = applied + 60_000)
        val out = call(applied + 60_000, CallDirection.OUTGOING)
        for ((failure, kind) in listOf(
            call(applied + day, CallDirection.INCOMING, active = false) to "answered_not_active",
            call(applied + day, CallDirection.INCOMING, atActive = BearerReading(16, listOf(ImsTransport.IWLAN), "lte")) to "active_on_wrong_bearer",
            call(applied + day, CallDirection.INCOMING, activeMs = 5_000, end = DisconnectReading(1510, "CODE_MEDIA_NO_DATA")) to "dropped_under_20s",
        )) {
            assertEquals(kind, ProvisionalDecision.Revert("incoming_failed:$kind"), Provisional.decide(p, listOf(out, failure), "rom-1", applied + day + 1))
        }
        // IMS was on LTE when it rang (away from Wi-Fi): that call says nothing about Wi-Fi calling.
        val elsewhere = call(applied + day, CallDirection.INCOMING, bearer = ProvenBearer.LTE, active = false)
        assertEquals(ProvisionalDecision.Wait, Provisional.decide(p, listOf(out, elsewhere), "rom-1", applied + day + 1))
    }

    @Test
    fun `only a changed voice on with a bearer proof is provisional - VoNR is never kept, other values need nothing new`() {
        assertEquals(ProvenBearer.IWLAN, Provisional.bearerFor(OwnerFeature.VOWIFI, true, false))
        assertEquals(ProvenBearer.LTE, Provisional.bearerFor(OwnerFeature.VOLTE, true, false))
        assertNull("the phone's own value needs nothing new", Provisional.bearerFor(OwnerFeature.VOLTE, true, true))
        assertNull(Provisional.bearerFor(OwnerFeature.SMS_OVER_IMS, true, false))
        assertTrue(Provisional.unprovable(OwnerFeature.VONR, true, false))
        assertFalse(Provisional.unprovable(OwnerFeature.VOLTE, true, false))
    }

    @Test
    fun `the ledger settles provisional keeps, drops those whose keep is gone, and names the reverts`() {
        val keepOverride = LiveOverride(0, sim, OwnerFeature.VOWIFI.key, true, Hold.KEPT, applied)
        val state = OwnerState(overrides = listOf(keepOverride), provisional = listOf(keep()))
        val out = call(applied + 60_000, CallDirection.OUTGOING)
        val (next, reverts) = CallLedger.add(state, out, applied + 61_000, { "SM-G981B|lineageos|16|26202|2397" }, "TQ1, phh_ims")
        assertTrue(reverts.isEmpty())
        assertEquals(out.atMs, next.provisional.single().callsOutAtMs)
        assertEquals(CallsLevel.CALLS_OUT, next.retested.single().level)
        val (later, laterReverts) = CallLedger.settle(next, "rom-1", applied + 8 * day)
        assertEquals(listOf(Triple(0, OwnerFeature.VOWIFI, "no_incoming_in_7_days")), laterReverts)
        assertEquals(1, later.provisional.size)
        // Undone by the owner: the keep is gone, so is its provisional record.
        assertTrue(next.copy(overrides = emptyList()).provisionalSettled().provisional.isEmpty())
    }

    // --- when the app places its own call ---

    @Test
    fun `passive first - the mailbox call is due after an apply, a pointer or weekly, only without a real call that showed it`() {
        val now = applied + 2 * 60_000
        val on = mapOf(sim to mapOf(OwnerFeature.VOWIFI to true, OwnerFeature.VOLTE to true))
        val sims = listOf(OwnerFixtures.SIM)
        // After an apply.
        val afterApply = OwnerState(provisional = listOf(keep()))
        assertEquals(listOf(CallsOutPlan.AFTER_APPLY), CallsOutPlan.due(sims, afterApply, on, emptyMap(), "rom-1", "n1", now).map { it.why })
        val realSinceApply = afterApply.copy(calls = listOf(call(applied + 60_000, CallDirection.OUTGOING)))
        assertTrue(CallsOutPlan.due(sims, realSinceApply, on, emptyMap(), "rom-1", "n1", now).isEmpty())
        // A pointer: due unless a real outgoing call passed on its bearer within 3 days.
        val pointed = mapOf(sim to setOf("vowifi:on"))
        assertEquals(listOf(CallsOutPlan.POINTER), CallsOutPlan.due(sims, OwnerState(), on, pointed, "rom-1", "n1", now).map { it.why })
        val twoDays = OwnerState(calls = listOf(call(now - 2 * day, CallDirection.OUTGOING)))
        assertTrue(CallsOutPlan.due(sims, twoDays, on, pointed, "rom-1", "n1", now).isEmpty())
        val fourDays = OwnerState(calls = listOf(call(now - 4 * day, CallDirection.OUTGOING)))
        assertEquals(listOf(CallsOutPlan.POINTER), CallsOutPlan.due(sims, fourDays, on, pointed, "rom-1", "n1", now).map { it.why })
        // A pointer at a feature that is off makes no call.
        assertTrue(CallsOutPlan.due(sims, OwnerState(), mapOf(sim to mapOf(OwnerFeature.VOWIFI to false)), pointed, "rom-1", "n1", now).isEmpty())
        // Weekly per kept voice entry.
        val kept = OwnerState(overrides = listOf(LiveOverride(0, sim, OwnerFeature.VOLTE.key, true, Hold.KEPT, applied - 30 * day)))
        assertEquals(listOf(OwnerFeature.VOLTE to CallsOutPlan.WEEKLY), CallsOutPlan.due(sims, kept, on, emptyMap(), "rom-1", "n1", now).map { it.feature to it.why })
        val keptRecent = kept.copy(calls = listOf(call(now - 5 * day, CallDirection.OUTGOING, bearer = ProvenBearer.LTE)))
        assertTrue(CallsOutPlan.due(sims, keptRecent, on, emptyMap(), "rom-1", "n1", now).isEmpty())
        // Nothing kept, nothing pointed, nothing applied: no call, ever.
        assertTrue(CallsOutPlan.due(sims, OwnerState(), on, emptyMap(), "rom-1", "n1", now).isEmpty())
        // One per feature and bearer a night.
        val called = kept.copy(callsOutNights = listOf(CallsOutNight(sim, OwnerFeature.VOLTE, ProvenBearer.LTE, "n1")))
        assertTrue(CallsOutPlan.due(sims, called, on, emptyMap(), "rom-1", "n1", now).isEmpty())
    }

    // --- "re-tested by OCD" ---

    @Test
    fun `re-tested by OCD names level, method, build and date, says incoming not tested for calls out, and expires after 90 days`() {
        val out = RetestedEntry("cell", "vowifi:on", ProvenBearer.IWLAN, CallsLevel.CALLS_OUT, "mailbox_call", "TQ1, phh_ims", applied)
        assertEquals(
            "re-tested by OCD: calls out over Wi-Fi, incoming not tested (build TQ1, phh_ims, 2026-10-21) (audio from packet counts)",
            out.line(ZoneOffset.UTC),
        )
        val works = out.copy(level = CallsLevel.WORKS, method = "mailbox_call+real_call")
        assertEquals("re-tested by OCD: works, calls both ways over Wi-Fi (build TQ1, phh_ims, 2026-10-21) (audio from packet counts)", works.line(ZoneOffset.UTC))
        assertFalse(out.expired(applied + 89 * day))
        assertTrue(out.expired(applied + 90 * day))
        assertTrue(Retested.current(listOf(out), applied + 91 * day).isEmpty())
        val list = Retested.add(listOf(out), out.copy(atMs = applied + day), applied + day)
        assertEquals(listOf(applied + day), list.map { it.atMs })
        assertEquals(2, Retested.add(list, works, applied + day).size)
        assertEquals("real_call", Retested.method(call(applied, CallDirection.OUTGOING), null))
    }
}
