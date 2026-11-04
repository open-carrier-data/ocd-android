package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.IwlanHold
import io.github.open_carrier_data.check.owner.core.EmergencyApi
import io.github.open_carrier_data.check.owner.core.EntryRecord
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.IwlanDumps
import io.github.open_carrier_data.check.owner.core.IwlanRegistry
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.Notices
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PlanWhy
import io.github.open_carrier_data.check.owner.core.PowerDump
import io.github.open_carrier_data.check.owner.core.QuietBlock
import io.github.open_carrier_data.check.owner.core.QuietInputs
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.QuietWindow
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.Refusals
import io.github.open_carrier_data.check.owner.core.SafetyInputs
import io.github.open_carrier_data.check.owner.core.ServerClock
import io.github.open_carrier_data.check.owner.core.ServerSighting
import io.github.open_carrier_data.check.owner.core.ServerStart
import io.github.open_carrier_data.check.owner.core.SimCheck
import io.github.open_carrier_data.check.owner.core.TestBasis
import io.github.open_carrier_data.check.owner.core.UndoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The silent window, the night plan, when the owner hears of it, and the soft-restart window (2026-10-20). */
class QuietTest {
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_792_440_000_000L

    /** A phone on its charger at 03:00, unused for two hours, on Wi-Fi, quiet. */
    private val night = QuietInputs(
        interactive = false, screenOffForMs = 2 * hour, plugged = true, batteryPercent = 90, inCall = false, audioIdle = true,
        roaming = false, wifiConnected = true, trafficBytesPerMinute = 2_000, nextAlarmInMs = 4 * hour, minuteOfDay = 3 * 60,
    )

    @Test
    fun `trials start only when every condition of the silent window holds`() {
        assertNull(QuietWindow.trialBlocker(night, nightOnly = true))
        val cases = listOf(
            night.copy(interactive = true) to QuietBlock.SCREEN_ON,
            night.copy(interactive = null) to QuietBlock.SCREEN_ON,
            night.copy(screenOffForMs = 59 * 60_000) to QuietBlock.SCREEN_ON_RECENTLY,
            night.copy(plugged = false) to QuietBlock.NOT_CHARGING,
            night.copy(batteryPercent = 49) to QuietBlock.BATTERY_LOW,
            night.copy(inCall = true) to QuietBlock.IN_CALL,
            night.copy(inCall = null) to QuietBlock.IN_CALL,
            night.copy(audioIdle = false) to QuietBlock.AUDIO_IN_USE,
            night.copy(roaming = true) to QuietBlock.ROAMING,
            night.copy(wifiConnected = false) to QuietBlock.NO_WIFI,
            night.copy(nextAlarmInMs = 30 * 60_000) to QuietBlock.ALARM_SOON,
            night.copy(minuteOfDay = 5 * 60) to QuietBlock.OUTSIDE_NIGHT,
            night.copy(minuteOfDay = 1 * 60 + 59) to QuietBlock.OUTSIDE_NIGHT,
            night.copy(trafficBytesPerMinute = 60 * 1024) to QuietBlock.DATA_IN_USE,
            night.copy(trafficBytesPerMinute = null) to QuietBlock.DATA_IN_USE,
        )
        for ((inputs, expected) in cases) assertEquals(inputs.toString(), expected, QuietWindow.trialBlocker(inputs, nightOnly = true))
        // A revert of a failing keep may run by day; the light check needs only the screen off and no call.
        assertNull(QuietWindow.trialBlocker(night.copy(minuteOfDay = 15 * 60), nightOnly = false))
        assertNull(QuietWindow.checkBlocker(night.copy(plugged = false, wifiConnected = false, screenOffForMs = 0)))
        assertEquals(QuietBlock.SCREEN_ON, QuietWindow.checkBlocker(night.copy(interactive = true)))
        assertNull("no alarm set", QuietWindow.trialBlocker(night.copy(nextAlarmInMs = null), nightOnly = true))
        assertEquals(30_000L, QuietWindow.perMinute(1_000, 11_000, 20_000))
        assertNull("a counter that went back", QuietWindow.perMinute(5_000, 1_000, 20_000))
    }

    @Test
    fun `dumpsys power gives how long the screen has been off`() {
        val asleep = """
            |  mWakefulness=Dozing
            |  mLastWakeTime=0 (7628373 ms ago)
            |  mLastSleepTime=3191318 (4437055 ms ago)
            |  mLastGlobalWakeTimeRealtime=0 (7628373 ms ago)
            |  mLastGlobalSleepTimeRealtime=3191322 (4437051 ms ago)
        """.trimMargin()
        assertEquals(4_437_051L, PowerDump.screenOffForMs(asleep))
        assertEquals(0L, PowerDump.screenOffForMs(asleep.replace("mWakefulness=Dozing", "mWakefulness=Awake")))
        assertEquals("woken after the last sleep", 0L, PowerDump.screenOffForMs(asleep.replace("(7628373 ms ago)", "(5 ms ago)")))
        assertNull(PowerDump.screenOffForMs(""))
        assertNull(PowerDump.screenOffForMs("mWakefulness=Asleep"))
    }

    private val sim = OwnerFixtures.SIM
    private val basis = TestBasis("data-2026.10@sha256:36d5f7ebb084", "lineage/x1s@1", "0.3.0-owner(3)/1/1")

    /** The S20 at home: everything it can see settled by the light check, VoLTE on behind Wi-Fi, the rest needs changes. */
    private fun check(failing: List<OwnerFeature> = emptyList()) = SimCheck(
        sim = sim,
        effective = mapOf(OwnerFeature.VOLTE to true, OwnerFeature.SMS_OVER_IMS to true, OwnerFeature.VIDEO_CALLING to false, OwnerFeature.VOWIFI to true, OwnerFeature.VONR to false),
        failingKeeps = failing,
        observed = mapOf("sms_over_ims:on" to true, "video_calling:off" to true, "vowifi:on" to true, "vonr:off" to true),
        static = mapOf("vonr:on" to "device_not_capable", "video_calling:on" to "not_available_on_this_build"),
    )

    /** What needs a change on the S20: VoLTE on (measured behind Wi-Fi), VoLTE off, SMS off, Wi-Fi calling off. */
    private val changes = listOf("volte:on", "volte:off", "sms_over_ims:off", "vowifi:off")

    private fun record(entry: String, at: Long, works: Boolean? = true, b: TestBasis = basis, failures: Int = 0, effective: Boolean? = null) =
        EntryRecord(sim.key, entry, at, b, works, "x", effective, failures)

    @Test
    fun `in the steady state everything is recent and nothing changed, so the night has nothing to do`() {
        val records = changes.map { record(it, now - 3 * day) }
        val plan = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key))
        assertTrue(plan.line, plan.all.isEmpty())
        assertEquals(0, plan.waiting)
    }

    @Test
    fun `a new app build re-tests every change, one feature a night, at most one off and two on`() {
        val old = basis.copy(app = "0.3.0-owner(3)/2/2")
        val records = changes.map { record(it, now - 3 * day, b = old) }
        val plan = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key))
        assertTrue(plan.triggered)
        assertEquals(setOf(PlanWhy.NEW_APP_BUILD), plan.entries.map { it.why }.toSet())
        assertEquals("one feature a night", 1, plan.entries.map { it.group }.distinct().size)
        assertEquals(listOf("volte:on", "volte:off"), plan.entries.map { it.entry })
        assertEquals(2, plan.waiting)
        // After a ROM flash or a data release: on values only.
        val onOnly = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), onOnly = true)
        assertEquals(listOf("volte:on"), onOnly.entries.map { it.entry })
        // Tonight's change made already: nothing more.
        assertTrue(QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), nightUsed = true).entries.isEmpty())
    }

    @Test
    fun `a new pin makes a SIM's entries due only when its own data values changed, not per commit (round 29 F4)`() {
        val key = "ocd:1111111111111111"
        val records = changes.map { record(it, now - 3 * day, b = basis.copy(release = key)) }
        // main moved on several commits, none of them changing this SIM's answers or APN rows: nothing is due.
        val same = QuietPlan.plan(listOf(check()), records, basis.copy(release = "main@e32fd75687d0"), now, known = setOf(sim.key), dataKeys = mapOf(sim.key to key))
        assertTrue(same.line, same.all.isEmpty())
        // Another SIM's values changed: not this SIM's entries.
        val other = sim.key.copy(subId = 9, mccmnc = "26207")
        assertTrue(QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), dataKeys = mapOf(sim.key to key, other to "ocd:2222222222222222")).all.isEmpty())
        // This SIM's values changed: its entries are due, as a trigger.
        val changed = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), dataKeys = mapOf(sim.key to "ocd:3333333333333333"))
        assertTrue(changed.triggered)
        assertEquals(setOf(PlanWhy.NEW_DATA_VALUES), changed.entries.map { it.why }.toSet())
        assertEquals(listOf("volte:on", "volte:off"), changed.entries.map { it.entry })
        // The records the light check writes carry the SIM's own key.
        val observed = QuietPlan.observedRecords(check(), basis.forSim(mapOf(sim.key to key), sim.key), now)
        assertTrue(observed.isNotEmpty() && observed.all { it.basis.release == key })
    }

    @Test
    fun `a new SIM is new, rotation takes the oldest entry two weeks on, an undecided one is retried after a day`() {
        val fresh = QuietPlan.plan(listOf(check()), emptyList(), basis, now)
        assertEquals(PlanWhy.NEW_SIM, fresh.entries.first().why)
        assertEquals(PlanWhy.NOT_TESTED, QuietPlan.plan(listOf(check()), emptyList(), basis, now, known = setOf(sim.key)).entries.first().why)
        val records = listOf(
            record("volte:on", now - 20 * day), record("volte:off", now - 2 * day),
            record("sms_over_ims:off", now - 16 * day), record("vowifi:off", now - 30 * day),
        )
        val rotation = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key))
        assertEquals(listOf("vowifi:off"), rotation.entries.map { it.entry })
        assertEquals(PlanWhy.ROTATION, rotation.entries.single().why)
        val undecided = changes.map { record(it, now - 3 * day) }.map { if (it.entry == "sms_over_ims:off") it.copy(works = null, atMs = now - 2 * hour) else it }
        assertTrue("not before a day", QuietPlan.plan(listOf(check()), undecided, basis, now, known = setOf(sim.key)).entries.isEmpty())
        val later = QuietPlan.plan(listOf(check()), undecided, basis, now + day, known = setOf(sim.key))
        assertEquals(PlanWhy.RETRY, later.entries.single().why)
    }

    @Test
    fun `a change the owner undid is not planned again for that SIM`() {
        val old = basis.copy(app = "0.3.0-owner(3)/2/2")
        val records = changes.map { record(it, now - 3 * day, b = old) }
        val undone = listOf(UndoneEntry(sim.key, "volte:on", now - day))
        val plan = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), undone = undone)
        assertTrue(plan.line, plan.entries.none { it.entry == "volte:on" })
        val other = listOf(UndoneEntry(sim.key.copy(subId = 99), "volte:on", now - day))
        assertTrue("another SIM's undo does not count", QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), undone = other).entries.any { it.entry == "volte:on" })
    }

    @Test
    fun `two failures in a row pause an entry for four weeks`() {
        val records = changes.map { record(it, now - 20 * day) }.map { if (it.entry == "volte:on") it.copy(works = null, failures = 2, atMs = now - 2 * day) else it }
        val plan = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key))
        assertTrue(plan.entries.none { it.entry == "volte:on" })
        assertEquals(listOf("volte:on"), plan.paused)
        val after = QuietPlan.plan(listOf(check()), records, basis, now + 27 * day, known = setOf(sim.key))
        assertTrue(after.paused.isEmpty())
    }

    @Test
    fun `a kept value that fails is reverted in any silent window, and a switch that changed under the app is due`() {
        val records = changes.map { record(it, now - 3 * day) }
        val plan = QuietPlan.plan(listOf(check(failing = listOf(OwnerFeature.VOLTE))), records, basis, now, known = setOf(sim.key))
        assertEquals(listOf("volte:on"), plan.reverts.map { it.entry })
        assertEquals(PlanWhy.KEPT_FAILING, plan.reverts.single().why)
        assertFalse("a revert need not wait for the night", QuietPlan.nightOnly(plan.copy(entries = emptyList()), null, now))
        assertTrue(QuietPlan.nightOnly(QuietPlan.plan(listOf(check()), emptyList(), basis, now), null, now))
        val changed = records.map { if (it.entry == "sms_over_ims:off") it.copy(effective = false) else it }
        assertEquals(PlanWhy.VALUE_CHANGED, QuietPlan.plan(listOf(check()), changed, basis, now, known = setOf(sim.key)).entries.single().why)
    }

    @Test
    fun `routine work is silent, a keep that comes or goes or a stop is told`() {
        val sim = OwnerFixtures.SIM.key
        val keep = LiveOverride(0, sim, "carrier_volte_available_bool", true, Hold.KEPT, now)
        val trial = LiveOverride(0, sim, "carrier_wfc_ims_available_bool", false, Hold.TRIAL, now)
        assertNull(Notices.of(OwnerState(), OwnerState()))
        assertNull("a trial live for a moment is no notice", Notices.of(OwnerState(), OwnerState(overrides = listOf(trial))))
        assertNull("a re-apply after boot is routine", Notices.of(OwnerState(overrides = listOf(keep)), OwnerState(overrides = listOf(keep.copy(sinceMs = now + 1)))))
        val added = Notices.of(OwnerState(), OwnerState(overrides = listOf(keep)))!!
        assertFalse(added.failed)
        assertEquals("Carrier settings changed", added.title)
        assertEquals("VoLTE switched on", added.text)
        assertEquals("VoLTE back to your own setting", Notices.of(OwnerState(overrides = listOf(keep)), OwnerState())!!.text)
        val stopped = Notices.of(OwnerState(), OwnerState(stop = "revert_not_proven on slot 0"))!!
        assertTrue(stopped.failed)
        assertEquals("Changes paused: a setting didn't go back", stopped.title)
        val after = OwnerState(entries = listOf(record("volte:on", now, works = null, failures = 1)))
        val failed = Notices.of(OwnerState(), after)!!
        assertEquals("A test failed — your own settings are back", failed.title)
        assertEquals("the plain feature name, never the entry key", "VoLTE", failed.text)
        assertTrue("the run's record keeps the technical line", Notices.failures(OwnerState(), after).single().startsWith("volte:on did not finish cleanly"))
        assertEquals("VoLTE switched on, 2 access point names added", Notices.changed(listOf("VoLTE switched on", "access point name added", "access point name added")).text)
    }

    @Test
    fun `the system server's start comes from its property when readable, else from the first sighting`() {
        val exact = ServerStart(startElapsedMs = 11_332, startCount = 1, bootId = "b", zygotePid = 900, elapsedMs = 8_000_000)
        assertEquals(8_000_000L - 11_332, ServerClock.upMs(exact, null).first)
        val blind = exact.copy(startElapsedMs = null, startCount = null)
        val (first, sighting) = ServerClock.upMs(blind, null)
        assertEquals("a server not seen before counts as just started", 0L, first)
        assertEquals(60_000L, ServerClock.upMs(blind.copy(elapsedMs = 8_060_000), sighting).first)
        // A soft restart: a new zygote, the elapsed clock running on.
        val restarted = blind.copy(zygotePid = 4321, elapsedMs = 9_000_000)
        assertEquals(0L, ServerClock.upMs(restarted, ServerSighting(ServerClock.key(blind), 8_000_000)).first)
        // A property after a soft restart says so itself.
        assertEquals(120_000L, ServerClock.upMs(exact.copy(startElapsedMs = 8_880_000, startCount = 2, elapsedMs = 9_000_000), null).first)
    }

    private val registry = OwnerFixtures.REGISTRY
    private val clear = EmergencyApi(ecbm = false, inEmergencyCall = false, emergencySmsMode = false)

    private fun input(registry: String = this.registry, serverUp: Long? = null, seen: io.github.open_carrier_data.check.owner.core.EmergencySeen? = null) =
        SafetyInputs(registry, null, null, slot = 0, homeMccMnc = "26202", nowMs = now, uptimeMs = 5 * hour, seen = seen, emergencyApi = clear, serverUpMs = serverUp)

    @Test
    fun `without the call watch a soft restart refuses writes for 10 minutes, as a reboot does`() {
        assertNull(Refusals.check(input(serverUp = 11 * 60_000)).refusal)
        assertEquals(Refusal.SYSTEM_RESTARTED, Refusals.check(input(serverUp = 9 * 60_000)).refusal)
        assertNull("not known: the boot window alone", Refusals.check(input(serverUp = null)).refusal)
    }

    @Test
    fun `an emergency sighting the registry lost in a restart still counts for its 10 minutes`() {
        val placed = registry.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=[EmergencyNumber: 112, countryIso=de]")
        val seen = Refusals.check(input(registry = placed)).seen!!
        // The system server restarted 2 minutes later: the field is gone, the sighting is not.
        val lost = Refusals.check(input(serverUp = 60_000, seen = seen.copy(firstSeenAtMs = now - 2 * 60_000)))
        assertEquals(Refusal.EMERGENCY_CALL_RECENT, lost.refusal)
        assertEquals(seen.mark, lost.seen?.mark)
        val over = Refusals.check(input(serverUp = 11 * 60_000, seen = seen.copy(firstSeenAtMs = now - 11 * 60_000)))
        assertNull(over.refusal)
        assertNull(over.seen)
        // A field set again after the restart is a new call: a sighting from before the restart does not date it.
        val again = Refusals.check(input(registry = placed, serverUp = 3 * 60_000, seen = seen.copy(firstSeenAtMs = now - 30 * 60_000)))
        assertEquals(now, again.seen!!.firstSeenAtMs)
    }

    /** The S20's IWLAN data service and QNS dumps of 2026-10-20 (addresses and counts left out). */
    private val iwlanDump = """
        |SERVICE com.google.android.iwlan/.IwlanDataService a4d6f47 pid=9877 user=0
        |    ---- IwlanDataServiceProvider[0] ----
        |    isNetworkConnected: true Wfc enabled: true
        |    Tunnel state for APN: ims
        |    	Current State of this tunnel: 3 UP
        |    ---- ErrorPolicyManager ----
        |    ErrorStats
        |    	Apn: ims
        |    	  TYPE: IWLAN_TUNNEL_NOT_FOUND  : 1
        |    ---- IwlanDataServiceProvider[1] ----
        |    isNetworkConnected: true Wfc enabled: false
    """.trimMargin()
    private val qnsDump = """
        |      NetworkAvailabilityProviderImpl[0]:
        |        ANE[ims_0]:
        |        mLastQualifiedAccessNetworkTypes=IWLAN|, mIsNotifiedLastQualifiedAccessNetworkTypes=true
        |          RestrictManager[ims_0]:
        |          mRestrictInfos={1=RestrictInfo[WWAN] : No restriction, 2=RestrictInfo[WLAN] : No restriction}
        |        ANE[xcap_0]:
    """.trimMargin()

    @Test
    fun `the IWLAN and QNS dumps stand in for the log`() {
        val up = IwlanDumps.reading(registry, iwlanDump, qnsDump, 0)!!
        assertEquals(true, up.tunnelUp)
        assertNull("an old error does not count while the tunnel is up", up.lastRefusal)
        assertNull(up.heldBackBy)
        assertEquals("IWLAN", IwlanDumps.qualifiedForIms(qnsDump, 0))
        val throttled = qnsDump.replace("2=RestrictInfo[WLAN] : No restriction", "2=RestrictInfo[WLAN] : {RESTRICT_TYPE_THROTTLING : release=x}")
        assertEquals(listOf("THROTTLING"), IwlanDumps.restrictions(throttled, 0))
        assertEquals(IwlanHold.NETWORK_BACKOFF_TIMER, IwlanDumps.reading(registry, iwlanDump, throttled, 0)!!.heldBackBy)
        val guarding = qnsDump.replace("2=RestrictInfo[WLAN] : No restriction", "2=RestrictInfo[WLAN] : {RESTRICT_TYPE_GUARDING : release=x}")
        assertNull("the guard after a handover is normal", IwlanDumps.reading(registry, iwlanDump, guarding, 0)!!.heldBackBy)
        val down = iwlanDump.replace("3 UP", "1 DOWN").replace("IWLAN_TUNNEL_NOT_FOUND", "IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED")
        val failed = IwlanDumps.reading(registry.replace("state: HANDOVERINPROGRESS, transport: WLAN", "state: DISCONNECTED, transport: WLAN"), down, qnsDump, 0)!!
        assertEquals(false, failed.tunnelUp)
        assertTrue(failed.selectionFailed)
        // The registry alone: the IMS data connection on WLAN.
        assertEquals(true, IwlanRegistry.reading(registry, 0)!!.tunnelUp)
    }
}
