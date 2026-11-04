package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.BuildCell
import io.github.open_carrier_data.check.owner.core.CallSeen
import io.github.open_carrier_data.check.owner.core.CallsSeen
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.DatasetRef
import io.github.open_carrier_data.check.owner.core.DatasetRetry
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.EimsInputs
import io.github.open_carrier_data.check.owner.core.EmergencyApi
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.IdleBlock
import io.github.open_carrier_data.check.owner.core.IdleInputs
import io.github.open_carrier_data.check.owner.core.IdleMoment
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.InstallView
import io.github.open_carrier_data.check.owner.core.NotReady
import io.github.open_carrier_data.check.owner.core.Readiness
import io.github.open_carrier_data.check.owner.core.ReadyLook
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.Refusals
import io.github.open_carrier_data.check.owner.core.RestartWindow
import io.github.open_carrier_data.check.owner.core.SafetyInputs
import io.github.open_carrier_data.check.owner.core.SimPhase
import io.github.open_carrier_data.check.owner.core.SlotLook
import io.github.open_carrier_data.check.owner.core.SlotWait
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Test now" means now (the owner's rule of 2026-10-25): the fixed 10 minutes after a boot become
 * the call watch's event check and a bounded readiness wait; only the refusals that name a harm stop
 * an explicit run; a refused install goes again for a new CarrierSettings and on every "Test now".
 */
class TestNowTest {
    private val now = 1_792_408_702_000L
    private val min = 60_000L
    private val registry = OwnerFixtures.REGISTRY
    private val clear = EmergencyApi(ecbm = false, inEmergencyCall = false, emergencySmsMode = false)

    /** A watch that has run for a day and saw [calls]. */
    private fun watch(vararg calls: CallSeen) = CallsSeen(now - 24 * 60 * min, calls.toList())

    private fun safety(uptime: Long, calls: CallsSeen?, serverUp: Long? = null) =
        SafetyInputs(registry, null, null, slot = 0, homeMccMnc = "26202", nowMs = now, uptimeMs = uptime, seen = null, emergencyApi = clear, serverUpMs = serverUp, callsSeen = calls)

    @Test
    fun `a boot refuses only when the call watch cannot rule out a call just before it`() {
        // Booted 3 minutes ago; the watch saw nothing for a day: no refusal, the fixed 10 minutes are gone.
        assertNull(Refusals.check(safety(3 * min, watch())).refusal)
        // No watch record (a fresh install, an older build): the 10 minutes after the boot stay.
        assertEquals(Refusal.JUST_BOOTED, Refusals.check(safety(3 * min, null)).refusal)
        // The watch started only 5 minutes ago: it did not cover the window.
        assertEquals(Refusal.JUST_BOOTED, Refusals.check(safety(3 * min, CallsSeen(now - 5 * min))).refusal)
        // A call ended 6 minutes ago, before the boot 3 minutes ago: it may have been an emergency call.
        val before = CallSeen(now - 8 * min, now - 6 * min)
        assertEquals(Refusal.JUST_BOOTED, Refusals.check(safety(3 * min, watch(before))).refusal)
        // The same call 11 minutes ago is outside the window.
        assertNull(Refusals.check(safety(3 * min, watch(CallSeen(now - 13 * min, now - 11 * min)))).refusal)
        // A call still open at the restart ended with it: the restart 3 minutes ago is the end.
        assertEquals(Refusal.JUST_BOOTED, Refusals.check(safety(3 * min, watch(CallSeen(now - 40 * min)))).refusal)
        assertNull(Refusals.check(safety(11 * min, watch(CallSeen(now - 40 * min)))).refusal)
        // A sighting left open long ago (a missed idle) ended before the next one began: it does not hold every later restart.
        assertNull(Refusals.check(safety(3 * min, watch(CallSeen(now - 5 * 60 * min), CallSeen(now - 4 * 60 * min, now - 4 * 60 * min + min)))).refusal)
        // The app's own mailbox call is never an emergency call.
        assertNull(Refusals.check(safety(3 * min, watch(before.copy(own = true)))).refusal)
        // A call after the boot is the registry's: no refusal from the watch.
        assertNull(Refusals.check(safety(5 * min, watch(CallSeen(now - 2 * min, now - min)))).refusal)
        // A clock that went back proves nothing.
        assertEquals(Refusal.JUST_BOOTED, Refusals.check(safety(3 * min, watch(CallSeen(now + 5 * min, now + 6 * min)))).refusal)
    }

    @Test
    fun `a soft restart follows the same event check, and an emergency call the watch saw refuses for 10 minutes in any boot`() {
        val before = CallSeen(now - 8 * min, now - 6 * min)
        assertNull(Refusals.check(safety(5 * 60 * min, watch(), serverUp = 2 * min)).refusal)
        assertEquals(Refusal.SYSTEM_RESTARTED, Refusals.check(safety(5 * 60 * min, watch(before), serverUp = 2 * min)).refusal)
        assertEquals(Refusal.SYSTEM_RESTARTED, Refusals.check(safety(5 * 60 * min, null, serverUp = 2 * min)).refusal)
        val emergency = CallSeen(now - 20 * min, now - 9 * min, emergency = true)
        assertEquals(Refusal.EMERGENCY_CALL_RECENT, Refusals.check(safety(5 * 60 * min, watch(emergency))).refusal)
        assertNull(Refusals.check(safety(5 * 60 * min, watch(emergency.copy(endMs = now - 11 * min)))).refusal)
    }

    @Test
    fun `the restart window's own rules`() {
        assertFalse(RestartWindow.clear(null, now - min, now, 10 * min))
        assertTrue(RestartWindow.clear(watch(), now - min, now, 10 * min))
        assertEquals(now - 6 * min, RestartWindow.lastCallAtMs(watch(CallSeen(now - 8 * min, now - 6 * min), CallSeen(now - 3 * min, now - 2 * min, own = true)), own = false))
        assertEquals(now - 2 * min, RestartWindow.lastCallAtMs(watch(CallSeen(now - 8 * min, now - 6 * min), CallSeen(now - 3 * min, now - 2 * min, own = true)), own = true))
        assertNull(RestartWindow.emergencyAtMs(watch(CallSeen(now - 8 * min, now - 6 * min))))
    }

    private val idle = IdleInputs(false, 5 * min, false, null, false, false, false, false, null, 2 * 60 * min, 80, false, false, now)

    @Test
    fun `background runs keep the idle moment, with the hour after a restart as an event check`() {
        assertNull(IdleMoment.blocker(idle))
        assertEquals(IdleBlock.EMERGENCY_RECENT, IdleMoment.blocker(idle.copy(upMs = 30 * min)))
        assertNull(IdleMoment.blocker(idle.copy(upMs = 30 * min, callsSeen = watch())))
        assertEquals(IdleBlock.EMERGENCY_RECENT, IdleMoment.blocker(idle.copy(upMs = 30 * min, callsSeen = watch(CallSeen(now - 50 * min, now - 45 * min)))))
        assertEquals(IdleBlock.EMERGENCY_RECENT, IdleMoment.blocker(idle.copy(callsSeen = watch(CallSeen(now - 50 * min, now - 45 * min, emergency = true)))))
        // The screen still holds a background run.
        assertEquals(IdleBlock.SCREEN_ON, IdleMoment.blocker(idle.copy(interactive = true)))
    }

    @Test
    fun `Test now stops only for a call, an emergency mode or roaming, not for the screen, media, the battery or a recent call`() {
        val inUse = idle.copy(interactive = true, screenOffForMs = 0, lastCallSeenAtMs = now - min, mediaActive = true, batteryPercent = 5, upMs = 3 * min)
        val all = IdleMoment.blockers(inUse)
        assertTrue(all.containsAll(listOf(IdleBlock.SCREEN_ON, IdleBlock.CALL_RECENT, IdleBlock.MEDIA, IdleBlock.BATTERY_LOW)))
        assertNull(all.firstOrNull { it in IdleMoment.OWNER })
        // The screen being on no longer hides a call from the owner's check (the first blocker was all it looked at).
        assertEquals(IdleBlock.IN_CALL, IdleMoment.blockers(inUse.copy(inCall = true)).firstOrNull { it in IdleMoment.OWNER })
        assertEquals(IdleBlock.EMERGENCY_MODE, IdleMoment.blockers(inUse.copy(ecbm = null)).firstOrNull { it in IdleMoment.OWNER })
        assertEquals(IdleBlock.ROAMING, IdleMoment.blockers(inUse.copy(roaming = true)).firstOrNull { it in IdleMoment.OWNER })
        assertEquals(TrialRunner.OWNER_INTERRUPTS, IdleMoment.OWNER)
    }

    @Test
    fun `the emergency data test's hour after a restart is an event check too`() {
        val ok = EimsInputs(
            inCall = false, lastCallSeenAtMs = null, emergencySeenAtMs = null, upMs = 20 * min, ecbm = false, emergencySmsMode = false,
            roaming = false, eimsExists = false, dataRat = "lte", emcSupported = true, dataWorks = true, honorRetryTimer = false,
            emergencyTransport = "WWAN", stopped = null, nowMs = now,
        )
        assertNotNull(EmergencyData.refusal(ok))
        assertNull(EmergencyData.refusal(ok.copy(callsSeen = watch())))
        assertNotNull(EmergencyData.refusal(ok.copy(callsSeen = watch(CallSeen(now - 40 * min, now - 30 * min)))))
        assertNotNull(EmergencyData.refusal(ok.copy(upMs = 5 * 60 * min, callsSeen = watch(CallSeen(now - 40 * min, now - 30 * min, emergency = true)))))
    }

    private fun slot(registered: Boolean?, state: String? = null, served: Boolean? = true, reason: Boolean = false, sim: SimPhase = SimPhase.LOADED) =
        SlotLook(0, sim, served, registered, state, reason)

    private fun looks(vararg s: Pair<Long, SlotLook>) = s.map { (at, look) -> ReadyLook(at, listOf(look, SlotLook(1, SimPhase.COMING))) }

    @Test
    fun `ready waits for the SIM, CarrierSettings's config and a stable IMS state, not for a timer`() {
        // A phone that has run for a while: ready at the first look, the empty second slot (NOT_READY for good) ignored.
        assertNull(Readiness.blocker(looks(0L to slot(true))))
        assertEquals(SlotWait(0, NotReady.CONFIG), Readiness.blocker(looks(0L to slot(null, served = false))))
        assertEquals(SlotWait(0, NotReady.CONFIG), Readiness.blocker(looks(0L to slot(true, served = null))))
        // No SIM loaded yet but one coming: wait for it; nothing at all: nothing to wait for.
        assertEquals(NotReady.SIM, Readiness.blocker(listOf(ReadyLook(0, listOf(SlotLook(0, SimPhase.COMING), SlotLook(1, SimPhase.NONE)))))?.why)
        assertNull(Readiness.blocker(listOf(ReadyLook(0, listOf(SlotLook(0, SimPhase.NONE))))))
        // IMS registering: not yet. Not registered without an attempt for a minute: a clear state.
        assertEquals(NotReady.IMS, Readiness.blocker(looks(0L to slot(false, "registering")))?.why)
        assertEquals(NotReady.IMS, Readiness.blocker(looks(0L to slot(false, "not_registered"), 30_000L to slot(false, "not_registered")))?.why)
        assertNull(Readiness.blocker(looks(0L to slot(false, "not_registered"), 30_000L to slot(false, "not_registered"), 60_000L to slot(false, "not_registered"))))
        // An attempt in between starts the minute again.
        assertEquals(NotReady.IMS, Readiness.blocker(looks(0L to slot(false, "not_registered"), 30_000L to slot(false, "registering"), 60_000L to slot(false, "not_registered")))?.why)
        // IMS named why its last attempt ended, on two looks: clear at once.
        assertNull(Readiness.blocker(looks(0L to slot(false, "not_registered", reason = true), 5_000L to slot(false, "not_registered", reason = true))))
        // Looks before the config was served do not count toward the minute.
        assertEquals(NotReady.IMS, Readiness.blocker(looks(0L to slot(false, served = false), 55_000L to slot(false, "not_registered"), 60_000L to slot(false, "not_registered")))?.why)
    }

    @Test
    fun `the readiness wait is bounded and answers at once when ready`() = runBlocking {
        var clock = 0L
        val ready = Readiness.await({ ReadyLook(clock, listOf(slot(true))) }, { clock }, { clock += it })
        assertTrue(ready.ok)
        assertEquals(0L, ready.waitedMs)
        assertEquals(1, ready.looks)
        // Registers after 20 s.
        clock = 0
        val later = Readiness.await({ ReadyLook(clock, listOf(slot(clock >= 20_000, if (clock >= 20_000) "registered" else "registering"))) }, { clock }, { clock += it })
        assertTrue(later.ok)
        assertEquals(20_000L, later.waitedMs)
        // Never served: stops at the bound and says what is missing.
        clock = 0
        val never = Readiness.await({ ReadyLook(clock, listOf(slot(null, served = false))) }, { clock }, { clock += it })
        assertFalse(never.ok)
        assertEquals(Readiness.MAX_WAIT_MS, never.waitedMs)
        assertTrue(never.waitedMs <= 180_000)
        assertEquals("slot 1: CarrierSettings has not served its config for the SIM yet", never.line)
    }

    @Test
    fun `CarrierSettings has served its config when its layer is there and the loader announced it`() {
        val cc = OwnerFixtures.CC
        assertEquals(false, CcDump.served(cc, 0))
        assertEquals(true, CcDump.served(cc, 1))
        assertEquals(false, CcDump.served(cc.replace("mHasSentConfigChange=[true, true]", "mHasSentConfigChange=[true, false]"), 1))
        assertNull(CcDump.served(null, 0))
        assertNull(CcDump.served("", 0))
        assertEquals(mapOf(0 to true, 1 to false), CcDump.configSent("mHasSentConfigChange=[true, false]"))
    }

    private fun status(incremental: String = "eng.20261025", protocol: Int = 1, pin: String = "b1") =
        DatasetStatus(available = true, protocol = protocol, build = BuildCell(incremental = incremental), buildPin = DatasetRef(commit = pin, datasetDigest = "d-$pin"))

    @Test
    fun `a refused install goes again once for a new CarrierSettings and on every Test now, never in a loop`() {
        val s = status()
        val refused = InstallView("i1", InstallState.REFUSED, error = "storage", datasetDigest = "x", carrierSettings = DatasetRetry.key(s))
        // The same CarrierSettings: not again in the background (no loop), but on "Test now".
        assertNull(DatasetRetry.again(refused, s, testNow = false))
        assertEquals("your request", DatasetRetry.again(refused, s, testNow = true))
        // A new ROM build, provider protocol or build pin: again once.
        assertNotNull(DatasetRetry.again(refused, status(incremental = "eng.20261026"), testNow = false))
        assertNotNull(DatasetRetry.again(refused, status(protocol = 2), testNow = false))
        assertNotNull(DatasetRetry.again(refused, status(pin = "b2"), testNow = false))
        // A record from before this rule names no CarrierSettings: again once.
        assertNotNull(DatasetRetry.again(refused.copy(carrierSettings = ""), s, testNow = false))
        // An install still under way is polled, not handed over again.
        assertNull(DatasetRetry.again(refused.copy(state = InstallState.VERIFYING), s, testNow = true))
    }
}
