package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.Campaign
import io.github.open_carrier_data.check.owner.core.CampaignPlanner
import io.github.open_carrier_data.check.owner.core.Candidate
import io.github.open_carrier_data.check.owner.core.Changed
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetFiles
import io.github.open_carrier_data.check.owner.core.DatasetRetry
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DatasetWords
import io.github.open_carrier_data.check.owner.core.EimsInputs
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.EmergencyDump
import io.github.open_carrier_data.check.owner.core.EmergencyFirstUse
import io.github.open_carrier_data.check.owner.core.ExplainKey
import io.github.open_carrier_data.check.owner.core.InstallRefusal
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.InstallView
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.Settlement
import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.owner.core.TestClass
import io.github.open_carrier_data.check.owner.core.TrialOutcome
import io.github.open_carrier_data.check.owner.core.TrialVerdict
import io.github.open_carrier_data.check.owner.core.TypedValues
import io.github.open_carrier_data.check.owner.core.VoiceLoss
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 31's provider interface (the spec's "Round 31 changes", protocol 3) as the app reads and
 * drives it: the status fields, the round 30 ROM read as before, the re-hand-off after the app was
 * disabled, the voice-loss split over the held build's dataset, the default emergency APN cycle's
 * pieces, and the first-use record.
 */
class Round31ProtocolTest {
    private fun answer(json: String) = DataAnswer(Json.parseToJsonElement(json).jsonObject)
    private val sim = OwnerSim(0, 3, SimIdentity("26202", "LIDL Connect", null, null), "LIDL Connect", 1)
    private val now = 1_800_000_000_000L

    private val round31 = answer(
        """{"ok":true,"protocol":3,
            "build":{"incremental":"eng.20261028","device":"x1s","model":"SM-G981B","rom_family":"lineageos","rom_line":"24.0","ims_stack_id":"aosp_imsstack","ims_stack_fork":"ocd","ims_stack_version":34},
            "installed":{"commit":"5cb3c43fc536be51b9ee663ad04d05f07d546006","data_digest":"sha256:x","dataset_digest":"sha256:i","generated_at":"2026-10-25T09:57:41Z"},
            "serving":"build","serving_reason":"voice_loss_hold","changes_halted":{"on":false},
            "emergency_gate":"narrow","emergency_gate_source":"build",
            "app":{"package":"io.github.open_carrier_data.check.owner","state":"enabled"},
            "device_override":{"keys":["imsvoice.voice_on_default_bearer_supported_bool","imsvoice.audio_evs_support_bool"],"values":{"gps.longterm_psds_server_1":""},"readable":true},
            "tester":{"active":true,"declared":true,"since":"2026-10-28T10:00:00Z","reason":"x"},"sm7":false,
            "voice_loss":{"hold":{"since":"2026-10-28T11:00:00Z","reason":"r","dataset_digest":"sha256:i","values_digest":"sha256:v"},"stop":null,"blocked":["sha256:old"]},
            "apn_test_set":{"sub_id":3,"since":"2026-10-28T11:05:00Z"}}""",
    )

    @Test
    fun `status - protocol 3 with the hold, the device override, the tester, the gate's source and the test set`() {
        val s = DatasetStatus.parse(round31, 1)
        assertTrue(s.available && s.round31)
        assertEquals("ocd", s.build.imsStackFork)
        assertTrue(s.voiceLoss.hold)
        assertFalse(s.voiceLoss.stop)
        assertEquals("sha256:v", s.voiceLoss.valuesDigest)
        assertEquals(listOf("sha256:old"), s.voiceLoss.blocked)
        assertEquals(setOf("imsvoice.voice_on_default_bearer_supported_bool", "imsvoice.audio_evs_support_bool", "gps.longterm_psds_server_1"), s.deviceOverride.keys)
        assertEquals(true, s.tester.declared)
        assertEquals(false, s.sm7)
        assertTrue(s.emergencyTestOn)
        assertTrue(s.apnTestSet)
        assertEquals("enabled", s.appState)
        // The gate narrowed by anything but the build's declaration is not the ROM builder's switch (SM10).
        assertFalse(s.copy(emergencyGateSource = "none").emergencyTestOn)
        assertFalse(s.copy(emergencyGate = "full").emergencyTestOn)
        // Words: the hold and the test set, plainly; never safe mode.
        val words = DatasetWords.of(s, OwnerState()).lines
        assertTrue(words.first(), words.first().startsWith("The ROM's own data serves: calls came back only with it"))
        assertTrue(words.any { "emergency entry" in it })
        assertEquals("The ROM's own data serves: Open Carrier Data was disabled", DatasetWords.of(s.copy(servingReason = "app_disabled", apnTestSet = false), OwnerState()).lines.first())
        assertEquals("The ROM's own data serves", DatasetWords.of(s.copy(servingReason = "cleared", apnTestSet = false), OwnerState()).lines.first())
    }

    @Test
    fun `a round 30 ROM (protocol 2) still reads, without the round 31 steps, and another protocol does not`() {
        val old = DatasetStatus.parse(answer("""{"ok":true,"protocol":2,"serving":"installed","installed":{"commit":"c","dataset_digest":"sha256:i"}}"""), 1)
        assertTrue(old.available)
        assertFalse(old.round31)
        assertFalse(old.voiceLoss.hold)
        assertFalse(old.emergencyTestOn)
        assertFalse(DatasetStatus.parse(answer("""{"ok":true,"protocol":4}"""), 1).available)
        assertFalse(DatasetStatus.parse(answer("""{"ok":true,"protocol":1}"""), 1).available)
        // Every round 31 call of the absent client answers absent.
        val absent = DatasetClient.Absent
        for (a in listOf(absent.setTester(true, "x"), absent.voiceLossHold("x"), absent.voiceLossRelease(OcdDataProtocol.OUTCOME_WINNERS, "x"), absent.emergencyDefaultSet(3), absent.emergencyDefaultRestore(false))) {
            assertFalse(a.ok)
            assertEquals(DataAnswer.ABSENT, a.error)
        }
        assertEquals("not run: this ROM build has no temporary emergency set (round 30)", EmergencyData.setRefusal(OcdDataProtocol.UNKNOWN_METHOD))
    }

    @Test
    fun `after the app was disabled and enabled again, the same dataset goes to CarrierSettings again, once`() {
        val installed = InstallView("i1", InstallState.INSTALLED, datasetDigest = "sha256:i", commit = "c")
        val buildServes = DatasetStatus(available = true, protocol = 3, serving = "build", servingReason = "no_dataset")
        assertNotNull(DatasetRetry.dropped(installed, buildServes))
        // Still installed, the crash guard's dataset, a hold or a voice-loss stop's blocked one: never again.
        assertNull(DatasetRetry.dropped(installed, buildServes.copy(installed = io.github.open_carrier_data.check.owner.core.DatasetRef("c", datasetDigest = "sha256:i"), serving = "installed")))
        assertNull(DatasetRetry.dropped(installed, buildServes.copy(servingReason = "crash_guard")))
        assertNull(DatasetRetry.dropped(installed, buildServes.copy(servingReason = "voice_loss_stop")))
        assertNull(DatasetRetry.dropped(installed, buildServes.copy(voiceLoss = io.github.open_carrier_data.check.owner.core.VoiceLossState(blocked = listOf("sha256:i")))))
        // A refused stage is not handed over again for the same CarrierSettings (no loop).
        assertNull(DatasetRetry.dropped(installed.copy(state = InstallState.REFUSED), buildServes))
        // Round 32: the emergency default set and the APN candidate set both wait under this one value.
        assertEquals("a temporary APN test set is in place", InstallRefusal.waiting("apn_test_set_in_place"))
        assertEquals("an emergency call ended less than 60 minutes ago", InstallRefusal.waiting("emergency_call_60min"))
        assertTrue("voice_loss_hold" in InstallRefusal.VOICE_LOSS && "voice_loss_stop" in InstallRefusal.VOICE_LOSS)
    }

    @Test
    fun `the group A reads file is an optional role of the dataset`() {
        assertEquals("group-a-reads.json" to "generated/ocd-dataset/group-a-reads.json", DatasetFiles.ROLES["group_a_reads"])
        assertTrue("group_a_reads" in DatasetFiles.OPTIONAL_ROLES && "group_a_reads" !in DatasetFiles.REQUIRED_ROLES)
        assertEquals(4L * 1024 * 1024, DatasetFiles.CAPS["group-a-reads.json"])
        assertTrue("group-a-reads.json" in DatasetFiles.JSON_FILES)
    }

    private fun held(n: Int): Campaign {
        val changes = (0 until n).map { Changed("imsvoice.k${it}_int", JsonPrimitive(it), JsonPrimitive(it + 100), "ocd:p") }
        return CampaignPlanner.start("h", sim, "c", "sha256:i", changes, emptySet(), now, CampaignPlanner.VOICE_LOSS_SPLIT).copy(held = true)
    }

    /** The split against an oracle: over the held build, a trial fails when [culprit] is set to its newest value (or a candidate other than [fix]). */
    private fun split(c0: Campaign, culprit: String?, fix: JsonElement?, candidates: List<Candidate>): Campaign {
        var c = c0
        while (!c.done) {
            val plan = CampaignPlanner.onHeld(c, CampaignPlanner.next(c) ?: break)
            // No dialled call or SMS in the split.
            assertTrue(plan.classes.none { it == TestClass.VOICE || it == TestClass.WIFI_CALLING || it == TestClass.SMS })
            val v = plan.overrides[culprit]
            val broken = culprit != null && v != null && (v.origin == Candidate.HELD_NEWEST || !TypedValues.same(v.value, fix))
            val verdict = if (broken) TrialVerdict(TrialOutcome.MEASURED_FAIL, setOf(TestClass.VOICE), "IMS lost mmtel") else TrialVerdict(TrialOutcome.PASS, reason = "IMS with the same services")
            c = CampaignPlanner.advance(c, verdict, { if (it.key == culprit) candidates else emptyList() }, now).first
        }
        return c
    }

    @Test
    fun `the split sets the newest values over the held build's, finds the one at fault, and its winner is released`() {
        val c = held(8)
        val first = CampaignPlanner.onHeld(c, CampaignPlanner.next(c)!!)
        // The first trial: every newest value on, as overrides over the build's dataset.
        assertEquals(8, first.overrides.size)
        assertTrue(first.overrides.values.all { it.origin == Candidate.HELD_NEWEST })
        val cands = listOf(Candidate(JsonPrimitive(7), "alternative:a"), Candidate(null, Candidate.ANDROID_DEFAULT))
        val done = split(c, "imsvoice.k5_int", JsonPrimitive(7), cands)
        assertEquals(mapOf("imsvoice.k5_int" to cands[0]), done.winners)
        assertEquals(VoiceLoss.SplitEnd.Winners(listOf("imsvoice.k5_int")), VoiceLoss.splitEnd(done))
    }

    @Test
    fun `the split - no failing value releases the newest dataset, a failing value without a winner keeps the hold`() {
        assertEquals(VoiceLoss.SplitEnd.NoFailingValue, VoiceLoss.splitEnd(split(held(5), null, null, emptyList())))
        val noWinner = split(held(5), "imsvoice.k2_int", JsonPrimitive(-1), listOf(Candidate(JsonPrimitive(3), "google")))
        assertEquals(VoiceLoss.SplitEnd.NoWinner(listOf("imsvoice.k2_int")), VoiceLoss.splitEnd(noWinner))
        assertNull(VoiceLoss.splitEnd(held(3)))
    }

    @Test
    fun `the split's differences - every served value that differs from the build's, without groups A and B or the device's keys`() {
        val newest = mapOf(
            "imsvoice.a_int" to ExplainKey(JsonPrimitive(2), "ocd:p"),
            "imsvoice.same_int" to ExplainKey(JsonPrimitive(1), "ocd:p"),
            "only_newest_bool" to ExplainKey(JsonPrimitive(true), "ocd:p"),
            "imsemergency.x_int" to ExplainKey(JsonPrimitive(9), "group_a:google"),
            "carrier_metered_apn_types_strings" to ExplainKey(Json.parseToJsonElement("""["default"]"""), "group_b:ocd"),
            "imsvoice.voice_on_default_bearer_supported_bool" to ExplainKey(JsonPrimitive(true), "device_override"),
        )
        val build = mapOf(
            "imsvoice.a_int" to ExplainKey(JsonPrimitive(1), "ocd:p"),
            "imsvoice.same_int" to ExplainKey(JsonPrimitive(1), "ocd:p"),
            "only_build_int" to ExplainKey(JsonPrimitive(5), "ocd:p"),
            "imsemergency.x_int" to ExplainKey(JsonPrimitive(8), "group_a:google"),
            "imsvoice.voice_on_default_bearer_supported_bool" to ExplainKey(JsonPrimitive(true), "device_override"),
        )
        val d = VoiceLoss.differences(newest, build, emptySet(), setOf("imsvoice.voice_on_default_bearer_supported_bool"))
        assertEquals(listOf("imsvoice.a_int", "only_build_int", "only_newest_bool"), d.map { it.key })
        val onlyBuild = d.single { it.key == "only_build_int" }
        assertNull("absent in the newest dataset: Android's default there", onlyBuild.new)
        assertEquals(JsonPrimitive(5), onlyBuild.old)
    }

    @Test
    fun `Android's default sos profile is read from DataProfileManager's section`() {
        val dump = """
            DataProfileManager-0:
              Data profiles for the current carrier:
                [DataProfile=[ApnSetting] LIDL Internet, 3101, 26202, internet.v6.lidl, , , , , , 0, default | supl, IPV4V6, IPV4V6, true, 0, ...]
                [DataProfile=[ApnSetting] DEFAULT EIMS, 0, , sos, , , , , , 0, emergency, IPV4V6, IPV4V6, true, 0, ...]
            DataProfileManager-1:
              Data profiles for the current carrier:
                [DataProfile=[ApnSetting] o2 IMS, 12, 26207, ims, , , , , , 0, ims, IPV4V6, IPV4V6, true, 0, ...]
        """.trimIndent()
        assertEquals(true, EmergencyDump.defaultSosProfile(dump, 0))
        assertEquals(false, EmergencyDump.defaultSosProfile(dump, 1))
        assertNull(EmergencyDump.defaultSosProfile(dump, 2))
        assertNull(EmergencyDump.defaultSosProfile(null, 0))
        // The SIM's own emergency row named sos is not Android's default only when it lacks the emergency type.
        assertEquals(false, EmergencyDump.defaultSosProfile("DataProfileManager-0:\n  [ApnSetting] X, 1, 26202, sos, , default\n", 0))
    }

    @Test
    fun `the default APN cycle - its first use guards the install, never the EIMS request's own conditions, and the verdicts`() {
        val ok = EimsInputs(
            inCall = false, lastCallSeenAtMs = null, emergencySeenAtMs = null, upMs = 10 * 3_600_000L, ecbm = false, emergencySmsMode = false,
            roaming = false, eimsExists = false, dataRat = "umts", emcSupported = false, dataWorks = true, honorRetryTimer = true,
            emergencyTransport = "WLAN", stopped = null, nowMs = now,
        )
        assertNull(EmergencyData.firstUseRefusal(ok))
        assertNotNull(EmergencyData.refusal(ok))
        for (bad in listOf(ok.copy(inCall = true), ok.copy(roaming = true), ok.copy(ecbm = null), ok.copy(eimsExists = true), ok.copy(stopped = "x"), ok.copy(lastCallSeenAtMs = now - 60_000), ok.copy(dataWorks = false))) {
            assertNotNull(bad.toString(), EmergencyData.firstUseRefusal(bad))
        }
        assertEquals("not run: user-edited row", EmergencyData.setRefusal("user_edited_row"))
        assertEquals(RecordResult.EMERGENCY_DEFAULT_WON, EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, EmergencyData.Verdict.PASS))
        assertEquals(RecordResult.EMERGENCY_DATA_REFUSED, EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, EmergencyData.Verdict.COUNTED_FAIL))
        assertNull(EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, null))
        assertTrue(InstallState.IN_PLACE in InstallState.SET_FINAL && InstallState.RESTORE_FAILED in InstallState.RESTORE_FINAL && InstallState.WAITING !in InstallState.RESTORE_FINAL)
    }

    @Test
    fun `the first-use record goes with every hand-off, for this phone's device and stack`() {
        val status = DatasetStatus.parse(round31, 1)
        assertNull(OwnerDatasetStep.firstUse(OwnerState(), status))
        val text = OwnerDatasetStep.firstUse(OwnerState(emergencyFirstUse = EmergencyFirstUse(EmergencyFirstUse.PASSED, now)), status)!!
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("ocd-emergency-first-use/1", o["schema"]!!.jsonPrimitive.content)
        assertEquals("x1s", o["device"]!!.jsonObject["codename"]!!.jsonPrimitive.content)
        assertEquals("ocd", o["ims_stack"]!!.jsonObject["fork"]!!.jsonPrimitive.content)
        assertEquals("passed", o["result"]!!.jsonPrimitive.content)
        assertEquals("2027-01-15", o["date"]!!.jsonPrimitive.content)
    }
}
