package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.CheckedLine
import io.github.open_carrier_data.check.owner.core.EntryRecord
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.HeaderNotes
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.LastResult
import io.github.open_carrier_data.check.owner.core.LeftoversLine
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.LogEvent
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
import io.github.open_carrier_data.check.owner.core.StatusKind
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
        assertEquals("Signed in, calls untested", volte.text)
        assertEquals("VoLTE: signed in, calls untested, step 3 of 4", volte.description)
        assertEquals(3, wifi.reached)
        assertEquals(4, wifi.rungs.size)
        assertEquals("SMS over IMS has no call test, so no fourth rung", listOf(Rung.NETWORK_OFFERS, Rung.SWITCHED_ON, Rung.SIGNED_IN), sms.rungs)
        assertEquals(3, sms.reached)
        assertEquals("Signed in", sms.text)
        assertTrue(video.notPossible)
        assertTrue("never supported: leaves the list", video.unsupported && vonr.unsupported)
        assertEquals(Tone.NOT_POSSIBLE, video.tone)
        assertEquals(0, video.reached)
        assertEquals("Video calling and VoNR aren't supported on this phone", Ladders.unsupportedLine(ladders))
        assertEquals("VoNR isn't supported on this phone", Ladders.unsupportedLine(listOf(vonr)))
        assertEquals(null, Ladders.unsupportedLine(listOf(volte, wifi)))
        val status = StatusSentence.of(s20Tonight(), listOf(view), ladders)
        assertEquals(Tone.WORKS, status.tone)
        assertEquals("All signed in on your phone's own settings", status.headline)
    }

    @Test
    fun `a T2 pass fills the last rung, only for the SIM's network`() {
        val state = s20Tonight()
        val passed = Ladders.of(OwnerFeature.VOLTE, state, view, listOf(CallsTested(OwnerFeature.VOLTE, "26202")))
        assertEquals(4, passed.reached)
        assertEquals("Works, calls tested", passed.text)
        assertEquals("VoLTE: works, calls tested, step 4 of 4", passed.description)
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
        assertEquals("VoLTE: switched on by Open Carrier Data, step 3 of 4", volte.description)
        val status = StatusSentence.of(state, listOf(view), Ladders.of(state, view))
        assertEquals(Tone.CHANGED, status.tone)
        assertEquals("Open Carrier Data switched on VoLTE", status.headline)
        assertEquals("Open Carrier Data switched on VoLTE", SettingsSummary.line(state, privileged = true, sims = listOf(view)))
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
        assertEquals("Signed in, calls untested", volte.text)
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
        assertEquals("Off, test needs Wi-Fi", wifi.text)
        // Stopped at the network's offer: not offered, no rung.
        val blocked = OwnerState(entries = listOf(record("volte:on", false, "blocked_at_network_offers", false)))
        val volte = Ladders.of(OwnerFeature.VOLTE, blocked, view)
        assertEquals(0, volte.reached)
        assertTrue(volte.notPossible)
        assertEquals("Not offered by your network", volte.text)
        assertFalse("can change later, so it stays a row", volte.unsupported)
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
        assertEquals("On, but not signed in", wifi.text)
        val status = StatusSentence.of(state, listOf(view), Ladders.of(state, view))
        assertEquals("Wi-Fi calling isn't signed in", status.headline)
        assertEquals(Tone.PROBLEM, status.tone)
    }

    @Test
    fun `a kept value that stopped working is a problem`() {
        val kept = LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)
        val state = OwnerState(overrides = listOf(kept), entries = listOf(record("volte:on", false, "observed", true)))
        val volte = Ladders.of(OwnerFeature.VOLTE, state, view)
        assertEquals(Tone.PROBLEM, volte.tone)
        assertEquals("Switched on by Open Carrier Data, not signed in", volte.text)
        // Not called an improvement: the headline names the problem, and the way back is Undo changes.
        val status = StatusSentence.of(state, listOf(view), Ladders.of(state, view))
        assertEquals("Open Carrier Data switched on VoLTE, but it isn't signed in", status.headline)
        assertEquals(Tone.PROBLEM, status.tone)
        assertEquals(StatusKind.KEPT_BROKEN, status.kind)
    }

    @Test
    fun `a trial shows as a test running, row and sentence`() {
        val trial = LiveOverride(0, key, OwnerFeature.VOWIFI.key, false, Hold.TRIAL, now)
        val state = s20Tonight().copy(overrides = listOf(trial))
        assertEquals("Testing now", Ladders.of(OwnerFeature.VOWIFI, state, view).text)
        assertEquals(Tone.CHANGED, Ladders.of(OwnerFeature.VOWIFI, state, view).tone)
        assertEquals("Testing", StatusSentence.of(state, listOf(view), Ladders.of(state, view)).headline)
        assertEquals("Testing", StatusSentence.of(s20Tonight(), listOf(view), emptyList(), running = true, testing = view).headline)
        val other = SimView(1, 4, "26223", "Vodafone")
        assertEquals("Testing Vodafone", StatusSentence.of(s20Tonight(), listOf(view, other), emptyList(), running = true, testing = other).headline)
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
        assertEquals("No SIM card", StatusSentence.of(works, emptyList(), emptyList()).headline)
        assertEquals("Changes paused: a setting didn't go back", StatusSentence.of(works.copy(stop = "revert_not_proven"), listOf(view), ladders).headline)
        assertEquals("A test failed — your own settings are back", StatusSentence.of(works.copy(lastResult = LastResult(now, "x", failed = true)), listOf(view), ladders).headline)
        assertEquals("Not checked yet", StatusSentence.of(OwnerState(), listOf(view), Ladders.of(OwnerState(), view)).headline)
        val apn = LiveApn(key, 7L, "web", "web|default|", Hold.KEPT, now, null, true, true)
        val volteOn = LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)
        fun headline(state: OwnerState) = StatusSentence.of(state, listOf(view), Ladders.of(state, view)).headline
        assertEquals("Open Carrier Data switched on VoLTE and added an access point name", headline(works.copy(overrides = listOf(volteOn), apns = listOf(apn))))
        val twoApns = listOf(apn, apn.copy(rowId = 8L, apnKey = "web2|default|"))
        assertEquals("access point names count as one item", "Open Carrier Data added 2 access point names", headline(works.copy(apns = twoApns)))
        assertEquals("Open Carrier Data switched on VoLTE and added 2 access point names", headline(works.copy(overrides = listOf(volteOn), apns = twoApns)))
        val wifiOn = LiveOverride(0, key, OwnerFeature.VOWIFI.key, true, Hold.KEPT, now)
        assertEquals("Open Carrier Data switched on VoLTE and Wi-Fi calling", headline(works.copy(overrides = listOf(volteOn, wifiOn))))
        assertEquals("Open Carrier Data switched off Wi-Fi calling", headline(works.copy(overrides = listOf(wifiOn.copy(value = false)))))
        assertEquals("Open Carrier Data changed 3 settings", headline(works.copy(overrides = listOf(volteOn, wifiOn), apns = twoApns)))
        assertEquals(listOf("VoLTE switched on", "2 access point names added"), StatusSentence.keptChanges(works.copy(overrides = listOf(volteOn), apns = twoApns)))
    }

    @Test
    fun `the Settings summary is the headline, with the call tests, and says when it can't change settings`() {
        assertEquals("All signed in on your phone's own settings", SettingsSummary.line(s20Tonight(), privileged = true, sims = listOf(view)))
        val calls = listOf(CallsTested(OwnerFeature.VOLTE, "26202"), CallsTested(OwnerFeature.VOWIFI, "26202"))
        assertEquals("All working on your phone's own settings", SettingsSummary.line(s20Tonight(), privileged = true, sims = listOf(view), calls = calls))
        assertEquals("Not checked yet. Can't change settings", SettingsSummary.line(OwnerState(), privileged = false, sims = listOf(view)))
        assertEquals("Changes paused: a setting didn't go back", SettingsSummary.line(OwnerState(stop = "revert_not_proven"), privileged = true, sims = listOf(view)))
        assertEquals("No SIM card", SettingsSummary.line(OwnerState(), privileged = true, sims = emptyList()))
    }

    @Test
    fun `the line under the headline says what to do, or when, and on which SIMs`() {
        val zone = ZoneId.of("Europe/Berlin")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
        val time: (Long) -> String = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString() }
        val date: (Long) -> String = { "Oct ${java.time.Instant.ofEpochMilli(it).atZone(zone).dayOfMonth}" }
        val night = at(2026, 10, 20, 3, 12)
        val state = OwnerState(lastCheckAtMs = night, runs = s20Tonight().runs, entries = s20Tonight().entries)
        fun line(nowMs: Long, steps: Int = 0, s: OwnerState = state, sims: List<SimView>? = listOf(view), schedule: Boolean = true, canTest: Boolean = true): String? {
            val status = StatusSentence.of(s, sims, sims.orEmpty().flatMap { Ladders.of(s, it) }, running = steps > 0)
            return CheckedLine.of(status, s, sims, steps, nowMs, zone, time, date, schedule, canTest)
        }
        assertEquals("Checked tonight at 03:12", line(at(2026, 10, 20, 4, 0)))
        assertEquals("Checked last night at 03:12", line(at(2026, 10, 20, 9, 0)))
        assertEquals("Checked today at 13:41", line(at(2026, 10, 20, 14, 0), s = state.copy(lastCheckAtMs = at(2026, 10, 20, 13, 41))))
        assertEquals("Checked yesterday at 03:12", line(at(2026, 10, 21, 9, 0)))
        assertEquals("Checked Oct 20 at 03:12", line(at(2026, 10, 23, 9, 0)))
        assertEquals("4 steps to go", line(night, steps = 4))
        assertEquals("1 step to go", line(night, steps = 1))
        assertEquals("Tap Test now, or wait until tonight", line(night, s = OwnerState()))
        assertEquals("Tap Test now to start", line(night, s = OwnerState(), schedule = false))
        assertEquals("Test now is hidden, so the line does not point at it", null, line(night, s = OwnerState(), canTest = false))
        assertEquals("Insert a SIM card to check it", line(night, sims = emptyList()))
        val failed = state.copy(lastResult = LastResult(night, "x", failed = true))
        assertEquals("Nothing to do. It tries again tonight", line(night, s = failed))
        assertEquals("Nothing to do", line(night, s = failed, schedule = false))
        val notSignedIn = state.copy(entries = listOf(record("vowifi:on", false, "observed", true)))
        assertEquals("Checked tonight at 03:12. Tap Test now to look for a fix", line(at(2026, 10, 20, 4, 0), s = notSignedIn))
        val broken = state.copy(overrides = listOf(LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)), entries = listOf(record("volte:on", false, "observed", true)))
        assertEquals("Tap Undo changes to go back", line(night, s = broken))
        val two = listOf(view, SimView(1, 4, "26223", "1&1"))
        assertEquals("Checked tonight at 03:12 on LIDL Connect and 1&1", line(at(2026, 10, 20, 4, 0), sims = two))
    }

    @Test
    fun `the header notes are short and say what to do`() {
        assertEquals(listOf(Tone.PROBLEM to "See the log in Details before resuming"), HeaderNotes.of(OwnerState(stop = "x", rebootCleared = true), canWrite = true, ownerPhone = true))
        assertEquals("This phone can be checked, not changed", HeaderNotes.of(OwnerState(rebootCleared = true), canWrite = false, ownerPhone = false).single().second)
        assertEquals("Tests can't run: no system access. See Details", HeaderNotes.of(OwnerState(rebootCleared = true), canWrite = false, ownerPhone = true).single().second)
        assertEquals("Changes start after the restart check in Details", HeaderNotes.of(OwnerState(), canWrite = true, ownerPhone = true).single().second)
    }

    @Test
    fun `the mobile data line uses Android's access point names`() {
        assertEquals("All 6 access point names are on your phone", MobileDataLine.of(s20Tonight(), view))
        val partly = OwnerState(entries = listOf(
            record(QuietPlan.apnEntry("a"), true, "present", null),
            record(QuietPlan.apnEntry("b"), true, OwnerRunner.SAME_AS_PHONE_ROW, null),
            record(QuietPlan.apnEntry("c"), null, "refused_mobile_data_off", null),
        ))
        assertEquals("2 of 3 access point names are on your phone, 1 not tested yet", MobileDataLine.of(partly, view))
        val added = partly.copy(apns = listOf(LiveApn(key, 9L, "c", "c", Hold.KEPT, now, null, true, true)))
        assertEquals("Open Carrier Data added an access point name. 2 were already on your phone", MobileDataLine.of(added, view))
        assertEquals("Open Carrier Data added an access point name. 2 were already on your phone, 1 not tested yet", MobileDataLine.of(added, view, candidates = 4))
        assertEquals("Not checked yet", MobileDataLine.of(OwnerState(), view))
        val fromRun = OwnerState(runs = listOf(RunResult("r", now, scheduled = false, sim = key, apns = listOf(ApnResult("web", "web|default|", "present")))))
        assertEquals("The access point name is on your phone", MobileDataLine.of(fromRun, view))
        assertEquals("No access point names for this SIM", MobileDataLine.of(OwnerState(), view, candidates = 0))
        assertEquals("6 access point names, not tested yet", MobileDataLine.of(OwnerState(), view, candidates = 6))
    }

    @Test
    fun `the schedule line is plain`() {
        assertEquals("At night while charging; keeps what works better", ScheduleLine.of(on = true))
        assertEquals("Only when you tap Test now", ScheduleLine.of(on = false))
    }

    @Test
    fun `the leftovers line says what the last cleanup found`() {
        val zone = ZoneId.of("UTC")
        val date: (Long) -> String = { "Oct 1" }
        assertEquals("Not run yet", LeftoversLine.of(emptyList(), now, zone, date))
        assertEquals("None found today", LeftoversLine.of(listOf(LogEvent(now - 60_000, "cleanup", "no leftovers")), now, zone, date))
        val removed = LogEvent(now - 60_000, "cleanup", "slot 1: 1 leftover trial(s), 0 keep(s) of another SIM reverted, proof holds")
        assertEquals("Removed 1 today", LeftoversLine.of(listOf(removed), now, zone, date))
    }

    @Test
    fun `no internal terms or retired words reach the main view`() {
        val internal = Regex("TRIAL|wfc_override|same_as_phone_row|fingerprint|reconcile|_bool|·|[Rr]evert|[Ii]mprovement|\\blive\\b|\\bkept\\b|\\bapplied\\b|[Oo]verride|\\bentr(y|ies)\\b")
        val kept = LiveOverride(0, key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now)
        val apn = LiveApn(key, 7L, "web", "web|default|", Hold.KEPT, now, null, true, true)
        val states = listOf(
            s20Tonight(), OwnerState(), s20Tonight().copy(overrides = listOf(kept)), s20Tonight().copy(apns = listOf(apn)),
            s20Tonight().copy(stop = "x"), s20Tonight().copy(lastResult = LastResult(now, "x", failed = true)),
            s20Tonight().copy(overrides = listOf(kept.copy(hold = Hold.TRIAL))),
        )
        for (state in states) {
            val ladders = Ladders.of(state, view)
            val status = StatusSentence.of(state, listOf(view), ladders)
            val texts = ladders.flatMap { listOf(it.text, it.description) } + listOfNotNull(Ladders.unsupportedLine(ladders)) +
                status.headline + MobileDataLine.of(state, view) + StatusSentence.keptChanges(state) +
                HeaderNotes.of(state, canWrite = false, ownerPhone = true).map { it.second } + ScheduleLine.of(true) + ScheduleLine.of(false) +
                listOfNotNull(CheckedLine.of(status, state, listOf(view), 0, now, ZoneId.of("UTC"), { "t" }, { "d" }, scheduleOn = true, canTest = true))
            for (text in texts) {
                assertFalse(text, internal.containsMatchIn(text))
                assertFalse("no final full stop: $text", text.endsWith("."))
            }
        }
    }
}
