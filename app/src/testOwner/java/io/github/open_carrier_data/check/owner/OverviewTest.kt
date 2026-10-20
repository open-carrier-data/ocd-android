package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.CheckedLine
import io.github.open_carrier_data.check.owner.core.EntryRecord
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.LastResult
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.MobileDataLine
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.Rung
import io.github.open_carrier_data.check.owner.core.ScheduleLine
import io.github.open_carrier_data.check.owner.core.SettingsSummary
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.StatusSentence
import io.github.open_carrier_data.check.owner.core.TestBasis
import io.github.open_carrier_data.check.owner.core.Tone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The owner screen's plain words: the evidence ladder from the results, the status sentence, and the lines around them. */
class OverviewTest {
    private val s20 = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val sim = OwnerFixtures.SIM
    private val view = SimView(slot = 0, subId = 3, mccmnc = "26202", name = "LIDL Connect")
    private val key = sim.key
    private val basis = TestBasis("data-2026.10@sha256:36d5f7ebb084", "lineage/x1s@1", "0.3.0-owner(3)/1/1")
    private val fast = RunOptions(settleMs = 30_000, restoreMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000, basis = basis)
    private val ocdOn = OcdAnswer(true, "device_record", "open.26202.aaaaaaaaaaaa", recordSource = "samsung_omc", recordStack = "stock", sameStack = false)
    private val now = 1_792_456_400_000L

    private fun record(entry: String, works: Boolean?, outcome: String, effective: Boolean?, sim: SimKey = key, atMs: Long = now) =
        EntryRecord(sim, entry, atMs, basis, works, outcome, effective)

    /** The S20 on 2026-10-20 (run ee4601a5): VoLTE measured over LTE, SMS and Wi-Fi calling seen, video and VoNR static. */
    private fun s20Tonight(): OwnerState {
        val features = listOf(
            FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn, offReason = "off_tests_not_chosen", best = true, method = "wfc_override", freshRegister = true, restored = true),
            FeatureResult(OwnerFeature.SMS_OVER_IMS, true, true, OcdAnswer(true, "carrier_profile", capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.VIDEO_CALLING, false, false, OcdAnswer(null, "carrier_profile", capability = "unknown"), onWorks = false, onReason = OwnerRunner.NOT_AVAILABLE, best = false),
            FeatureResult(OwnerFeature.VOWIFI, true, true, OcdAnswer(true, "carrier_profile", capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.VONR, false, false, OcdAnswer(true, "carrier_profile", capability = "supported"), onWorks = false, onReason = "device_not_capable", best = false),
        )
        val entries = listOf(
            record("volte:on", true, "wfc_override", true),
            record("volte:off", null, "off_tests_not_chosen", true),
            record("sms_over_ims:on", true, "observed", true),
            record("video_calling:off", true, "observed", false),
            record("video_calling:on", false, OwnerRunner.NOT_AVAILABLE, false),
            record("vowifi:on", true, "observed", true),
            record("vonr:on", false, "device_not_capable", false),
        ) + (1..6).map { record("apn:web.vodafone.de|row$it|", true, if (it % 2 == 0) "present" else OwnerRunner.SAME_AS_PHONE_ROW, null) }
        val run = RunResult("ee4601a5", startedMs = now - 60_000, endedMs = now - 40_000, scheduled = false, sim = key, simName = "Vodafone", features = features)
        return OwnerState(rebootCleared = true, runs = listOf(run), entries = entries, lastResult = LastResult(now - 40_000, "test of VoLTE, …: nothing changed"))
    }

    @Test
    fun `the S20's results read as the design's wireframe`() {
        val ladders = Ladders.of(s20Tonight(), view)
        assertEquals(Ladders.ORDER, ladders.map { it.feature })
        val (volte, wifi, sms, video, vonr) = ladders
        assertEquals(Tone.WORKS, volte.tone)
        assertEquals(3, volte.reached)
        assertEquals(Rung.entries, volte.rungs)
        assertEquals("Works, calls not tested yet", volte.text)
        assertEquals("VoLTE: signed in, calls not tested yet", volte.description)
        assertEquals(3, wifi.reached)
        assertEquals(4, wifi.rungs.size)
        assertEquals("SMS over IMS has no call test, so no fourth rung", listOf(Rung.NETWORK_OFFERS, Rung.SWITCHED_ON, Rung.SIGNED_IN), sms.rungs)
        assertEquals(3, sms.reached)
        assertEquals("Works", sms.text)
        assertTrue(video.notPossible)
        assertEquals(Tone.NOT_POSSIBLE, video.tone)
        assertEquals("Not possible on this phone (software)", video.text)
        assertEquals(0, video.reached)
        assertEquals("Not possible on this phone (modem)", vonr.text)
        assertEquals("VoNR: not possible on this phone because of its modem", vonr.description)
        val status = StatusSentence.of(s20Tonight(), listOf(view), ladders)
        assertEquals(Tone.WORKS, status.tone)
        assertEquals("Everything works with your phone's own settings", status.headline)
    }

    @Test
    fun `a T2 pass fills the last rung, only for the SIM's network`() {
        val state = s20Tonight()
        val passed = Ladders.of(OwnerFeature.VOLTE, state, view, listOf(CallsTested(OwnerFeature.VOLTE, "26202")))
        assertEquals(4, passed.reached)
        assertEquals("Works, calls tested", passed.text)
        assertEquals("VoLTE: signed in, calls tested", passed.description)
        assertEquals(3, Ladders.of(OwnerFeature.VOLTE, state, view, listOf(CallsTested(OwnerFeature.VOLTE, "26203"))).reached)
        assertEquals("a VoLTE pass is not Wi-Fi calling's", 3, Ladders.of(OwnerFeature.VOWIFI, state, view, listOf(CallsTested(OwnerFeature.VOLTE, "26202"))).reached)
    }

    @Test
    fun `a value Open Carrier Data keeps is amber and named in the status sentence`() = runBlocking {
        val phone = FakePhone()
        OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        val state = phone.store.state
        val volte = Ladders.of(OwnerFeature.VOLTE, state, view)
        assertEquals(Tone.CHANGED, volte.tone)
        assertEquals("Switched on by Open Carrier Data", volte.text)
        assertEquals(3, volte.reached)
        assertEquals("VoLTE: switched on by Open Carrier Data, signed in, calls not tested yet", volte.description)
        val status = StatusSentence.of(state, listOf(view), Ladders.of(state, view))
        assertEquals(Tone.CHANGED, status.tone)
        assertEquals("1 improvement active: VoLTE switched on", status.headline)
        assertEquals("1 improvement active: VoLTE switched on", SettingsSummary.line(state, privileged = true, sims = listOf(view)))
    }

    @Test
    fun `an on that did not register leaves the phone's off and a cross`() = runBlocking {
        val phone = FakePhone().apply { imsWorks = false }
        OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        val volte = Ladders.of(OwnerFeature.VOLTE, phone.store.state, view)
        assertTrue(volte.notPossible)
        assertEquals("Didn't sign in when tested", volte.text)
        assertEquals(0, volte.reached)
        assertEquals(Tone.NOT_POSSIBLE, volte.tone)
    }

    @Test
    fun `VoLTE measured inside the Wi-Fi-calling-off override counts as signed in`() = runBlocking {
        val phone = FakePhone().apply {
            defaultApp = mapOf("carrier_volte_available_bool" to "true", "carrier_wfc_ims_available_bool" to "true")
            imsOverWifi = true
            wifi = true
        }
        OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(testOffs = false))
        val volte = Ladders.of(OwnerFeature.VOLTE, phone.store.state, view)
        assertEquals(Tone.WORKS, volte.tone)
        assertEquals(3, volte.reached)
        assertEquals("Works, calls not tested yet", volte.text)
    }

    @Test
    fun `each rung needs the one before it`() {
        // Off, OCD's data says the carrier offers it, not tried yet: one rung.
        val offered = OwnerState(entries = listOf(record("vowifi:off", true, "observed", false)), runs = listOf(
            RunResult("r", now, scheduled = false, sim = key, features = listOf(FeatureResult(OwnerFeature.VOWIFI, false, false, ocdOn, onReason = "wifi_not_connected"))),
        ))
        val wifi = Ladders.of(OwnerFeature.VOWIFI, offered, view)
        assertEquals(1, wifi.reached)
        assertEquals(Tone.NEUTRAL, wifi.tone)
        assertEquals("Off, tested when Wi-Fi is on", wifi.text)
        // Stopped at the network's offer: not offered, no rung.
        val blocked = OwnerState(entries = listOf(record("volte:on", false, "blocked_at_network_offers", false)))
        val volte = Ladders.of(OwnerFeature.VOLTE, blocked, view)
        assertEquals(0, volte.reached)
        assertTrue(volte.notPossible)
        assertEquals("Not offered by your network here", volte.text)
        // Nothing known: nothing filled.
        val unknown = Ladders.of(OwnerFeature.SMS_OVER_IMS, OwnerState(), view)
        assertEquals(0, unknown.reached)
        assertEquals("Not checked yet", unknown.text)
    }

    @Test
    fun `the phone's own switch on without a registration is a problem the sentence names`() {
        val state = OwnerState(entries = listOf(record("volte:on", true, "observed", true), record("vowifi:on", false, "observed", true), record("sms_over_ims:on", true, "observed", true)))
        val wifi = Ladders.of(OwnerFeature.VOWIFI, state, view)
        assertEquals(Tone.PROBLEM, wifi.tone)
        assertEquals(2, wifi.reached)
        assertEquals("Switched on, but not signed in", wifi.text)
        val status = StatusSentence.of(state, listOf(view), Ladders.of(state, view))
        assertEquals("Your phone uses its own settings, but Wi-Fi calling isn't signed in", status.headline)
        assertEquals(Tone.PROBLEM, status.tone)
    }

    @Test
    fun `a kept value that stopped working is a problem`() {
        val kept = LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)
        val state = OwnerState(overrides = listOf(kept), entries = listOf(record("volte:on", false, "observed", true)))
        val volte = Ladders.of(OwnerFeature.VOLTE, state, view)
        assertEquals(Tone.PROBLEM, volte.tone)
        assertEquals("Switched on by Open Carrier Data, but not signed in", volte.text)
    }

    @Test
    fun `a trial shows as a test running, row and sentence`() {
        val trial = LiveOverride(0, key, OwnerFeature.VOWIFI.key, false, Hold.TRIAL, now)
        val state = s20Tonight().copy(overrides = listOf(trial))
        assertEquals("Being tested now", Ladders.of(OwnerFeature.VOWIFI, state, view).text)
        assertEquals(Tone.CHANGED, Ladders.of(OwnerFeature.VOWIFI, state, view).tone)
        assertEquals("A test is running", StatusSentence.of(state, listOf(view), Ladders.of(state, view)).headline)
        assertEquals("A test is running", StatusSentence.of(s20Tonight(), listOf(view), emptyList(), running = true).headline)
    }

    @Test
    fun `another SIM's records do not count`() {
        val other = SimKey(subId = 9, mccmnc = "26223", carrierId = 2536)
        val state = OwnerState(entries = listOf(record("volte:on", true, "observed", true, sim = other)))
        assertEquals("Not checked yet", Ladders.of(OwnerFeature.VOLTE, state, view).text)
    }

    @Test
    fun `the status sentence in its order of precedence`() {
        val works = s20Tonight()
        val ladders = Ladders.of(works, view)
        assertEquals("No SIM card, so there's nothing to check", StatusSentence.of(works, emptyList(), emptyList()).headline)
        assertEquals("A check failed — changes are paused", StatusSentence.of(works.copy(stop = "revert_not_proven"), listOf(view), ladders).headline)
        assertEquals("A check failed — reverted", StatusSentence.of(works.copy(lastResult = LastResult(now, "x", failed = true)), listOf(view), ladders).headline)
        assertEquals("Not checked yet", StatusSentence.of(OwnerState(), listOf(view), Ladders.of(OwnerState(), view)).headline)
        val two = works.copy(
            overrides = listOf(LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)),
            apns = listOf(LiveApn(key, 7L, "web", "web|default|", Hold.KEPT, now, null, true, true)),
        )
        assertEquals("2 improvements active: VoLTE switched on and mobile data entry added", StatusSentence.of(two, listOf(view), Ladders.of(two, view)).headline)
        val three = two.copy(overrides = two.overrides + LiveOverride(0, key, OwnerFeature.VOWIFI.key, false, Hold.KEPT, now))
        assertEquals("3 improvements active", StatusSentence.of(three, listOf(view), Ladders.of(three, view)).headline)
    }

    @Test
    fun `the Settings summary is the status sentence, and says when system access is missing`() {
        assertEquals("Everything works with your phone's own settings", SettingsSummary.line(s20Tonight(), privileged = true, sims = listOf(view)))
        assertEquals("Not checked yet. No system access", SettingsSummary.line(OwnerState(), privileged = false, sims = listOf(view)))
        assertEquals("A check failed — changes are paused", SettingsSummary.line(OwnerState(stop = "revert_not_proven"), privileged = true, sims = listOf(view)))
        assertEquals("No SIM card, so there's nothing to check", SettingsSummary.line(OwnerState(), privileged = true, sims = emptyList()))
    }

    @Test
    fun `the checked line says when, in words, and on which SIM`() {
        val zone = ZoneId.of("Europe/Berlin")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
        val time: (Long) -> String = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString() }
        val date: (Long) -> String = { "Oct ${java.time.Instant.ofEpochMilli(it).atZone(zone).dayOfMonth}" }
        val night = at(2026, 10, 20, 3, 12)
        val state = OwnerState(lastCheckAtMs = night)
        fun line(nowMs: Long, steps: Int = 0, s: OwnerState = state, sims: List<SimView>? = listOf(view)) = CheckedLine.of(s, sims, steps, nowMs, zone, time, date)
        assertEquals("Checked tonight at 03:12 on LIDL Connect.", line(at(2026, 10, 20, 4, 0)))
        assertEquals("Checked last night at 03:12 on LIDL Connect.", line(at(2026, 10, 20, 9, 0)))
        assertEquals("Checked today at 13:41 on LIDL Connect.", line(at(2026, 10, 20, 14, 0), s = OwnerState(lastCheckAtMs = at(2026, 10, 20, 13, 41))))
        assertEquals("Checked yesterday at 03:12 on LIDL Connect.", line(at(2026, 10, 21, 9, 0)))
        assertEquals("Checked Oct 20 at 03:12 on LIDL Connect.", line(at(2026, 10, 23, 9, 0)))
        assertEquals("Testing on LIDL Connect, 4 steps to go.", line(night, steps = 4))
        assertEquals("Open Carrier Data checks on its own at night. Tap Test now to check right away.", line(night, s = OwnerState()))
        assertEquals("Put a SIM card in to check its calling and data settings.", line(night, sims = emptyList()))
        val two = listOf(view, SimView(1, 4, "26223", "1&1"))
        assertEquals("Checked tonight at 03:12 on LIDL Connect and 1&1.", line(at(2026, 10, 20, 4, 0), sims = two))
    }

    @Test
    fun `the mobile data line counts OCD's entries against the phone's`() {
        assertEquals("All 6 Open Carrier Data entries are already on your phone.", MobileDataLine.of(s20Tonight(), view))
        val partly = OwnerState(entries = listOf(
            record(QuietPlan.apnEntry("a"), true, "present", null),
            record(QuietPlan.apnEntry("b"), true, OwnerRunner.SAME_AS_PHONE_ROW, null),
            record(QuietPlan.apnEntry("c"), null, "refused_mobile_data_off", null),
        ))
        assertEquals("2 of 3 Open Carrier Data entries are already on your phone. The other one hasn't been tested yet.", MobileDataLine.of(partly, view))
        val added = partly.copy(apns = listOf(LiveApn(key, 9L, "c", "c", Hold.KEPT, now, null, true, true)))
        assertEquals("1 entry added by Open Carrier Data. 2 more are already on your phone.", MobileDataLine.of(added, view))
        assertEquals("Not checked yet. Test now checks them too.", MobileDataLine.of(OwnerState(), view))
        val fromRun = OwnerState(runs = listOf(RunResult("r", now, scheduled = false, sim = key, apns = listOf(ApnResult("web", "web|default|", "present")))))
        assertEquals("The Open Carrier Data entry is already on your phone.", MobileDataLine.of(fromRun, view))
        assertEquals("Open Carrier Data has no entries for this SIM.", MobileDataLine.of(OwnerState(), view, candidates = 0))
        val twoOfThree = OwnerState(entries = listOf(record(QuietPlan.apnEntry("a"), true, "present", null), record(QuietPlan.apnEntry("b"), true, "present", null)))
        assertEquals(
            "a row the phone lacks has no record until it is tested",
            "2 of 3 Open Carrier Data entries are already on your phone. The other one hasn't been tested yet.",
            MobileDataLine.of(twoOfThree, view, candidates = 3),
        )
        assertEquals("3 entries from Open Carrier Data, not tested yet.", MobileDataLine.of(OwnerState(), view, candidates = 3))
    }

    @Test
    fun `the schedule line is plain`() {
        assertEquals("Checks quietly at night while charging. Next check: tonight.", ScheduleLine.of(on = true, registered = true))
        assertTrue(ScheduleLine.of(on = false, registered = false).startsWith("Off."))
        assertFalse(ScheduleLine.of(on = true, registered = false).startsWith("Off"))
    }

    @Test
    fun `no internal terms reach the main view`() {
        val internal = Regex("TRIAL|wfc_override|same_as_phone_row|fingerprint|reconcile|_bool|·")
        val states = listOf(s20Tonight(), OwnerState(), s20Tonight().copy(overrides = listOf(LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now))))
        for (state in states) {
            val ladders = Ladders.of(state, view)
            val texts = ladders.flatMap { listOf(it.text, it.description) } +
                StatusSentence.of(state, listOf(view), ladders).headline + MobileDataLine.of(state, view) +
                CheckedLine.of(state, listOf(view), 0, now, ZoneId.of("UTC"), { "t" }, { "d" })
            for (text in texts) assertFalse(text, internal.containsMatchIn(text))
        }
    }
}
