package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.Mailbox
import io.github.open_carrier_data.check.core.FreeFlags
import io.github.open_carrier_data.check.owner.core.CampaignPhase
import io.github.open_carrier_data.check.owner.core.CampaignPlanner
import io.github.open_carrier_data.check.owner.core.Changed
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DeviceKeys
import io.github.open_carrier_data.check.owner.core.DeviceOverride
import io.github.open_carrier_data.check.owner.core.ExplainKey
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.OptInWords
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordCheck
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.owner.core.StackCalibration
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import io.github.open_carrier_data.check.owner.core.TrialGuard
import io.github.open_carrier_data.check.owner.core.TrialOutcome
import io.github.open_carrier_data.check.owner.core.TrialVerdict
import io.github.open_carrier_data.check.owner.core.VoiceLoss
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * OCD round 31 in the app: the keys the ROM's device override sets are the device's (no candidate,
 * no record), the passive check on an uncalibrated stack or without the opt-in (SM8, SM9), the ROM
 * build's policy (SM9, SM11), the allowlist's free flags, and no safe mode anywhere.
 */
class Round31Test {
    private val now = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
    private val sim = OwnerSim(0, 3, SimIdentity("26207", "Example", null, null), "o2", 1911)

    private fun explain(keys: Map<String, ExplainKey>) = ExplainView(3, network = "262-07", keys = keys)

    @Test
    fun `device keys - the override, LineageOS's fixed key and a round 30 device record are the device's`() {
        val e = explain(
            mapOf(
                "imsvoice.voice_on_default_bearer_supported_bool" to ExplainKey(JsonPrimitive(true), "device_override"),
                "imsvoice.audio_evs_support_bool" to ExplainKey(JsonPrimitive(false), "lineageos_fixed"),
                "gps.longterm_psds_server_1" to ExplainKey(JsonPrimitive(""), "device_record:r:e41c807e9f154e6a"),
                "carrier_volte_available_bool" to ExplainKey(JsonPrimitive(true), "ocd:open.26207.x"),
                "imsvoice.x_int" to ExplainKey(JsonPrimitive(1), "measured_record:r:0123456789abcdef"),
            ),
        )
        // The source codes of R31.3, and status.device_override's keys.
        val status = DatasetStatus(available = true, protocol = 3, deviceOverride = DeviceOverride(setOf("imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool")))
        assertEquals(
            setOf(
                "imsvoice.voice_on_default_bearer_supported_bool", "imsvoice.audio_evs_support_bool", "gps.longterm_psds_server_1",
                "imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool",
            ),
            DeviceKeys.of(e, status),
        )
        assertEquals(setOf("imsvoice.voice_on_default_bearer_supported_bool", "imsvoice.audio_evs_support_bool", "gps.longterm_psds_server_1"), DeviceKeys.of(e))
        assertTrue(DeviceKeys.of(null).isEmpty())
        // explain names them with the source CarrierSettings gives (R31.3).
        val parsed = ExplainView.parse(3, DataAnswer(Json.parseToJsonElement("""{"ok":true,"explain":{"network":"262-07","keys":{"a_bool":{"value":true,"source":"lineageos_fixed","under_override":false}}}}""") as JsonObject))!!
        assertEquals(setOf("a_bool"), DeviceKeys.of(parsed))
    }

    @Test
    fun `device keys - no trial, no campaign entry, no build override and no record for them`() {
        val device = setOf("imsvoice.voice_on_default_bearer_supported_bool")
        assertEquals(DeviceKeys.WHY, TrialGuard.problem("imsvoice.voice_on_default_bearer_supported_bool", emptySet(), device))
        val changes = listOf(Changed("imsvoice.voice_on_default_bearer_supported_bool", JsonPrimitive(false), JsonPrimitive(true)), Changed("imsvoice.x_int", JsonPrimitive(1), JsonPrimitive(2)))
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", changes, emptySet(), now, device = device)
        assertEquals(listOf("imsvoice.x_int"), c.changes.map { it.key })
        // A campaign opened before the keys were known loses them at its next run.
        val old = CampaignPlanner.start("c", sim, "commit", "sha256:d", changes, emptySet(), now)
        assertEquals(listOf("imsvoice.x_int"), CampaignPlanner.withoutDevice(old, device).changes.map { it.key })
        assertEquals(listOf("imsvoice.x_int"), VoiceLoss.suspects(changes, device).map { it.key })
        val served = mapOf("imsvoice.voice_on_default_bearer_supported_bool" to ExplainKey(JsonPrimitive(true), "device_override"), "imsvoice.x_int" to ExplainKey(JsonPrimitive(2), "ocd:p"))
        val build = mapOf("imsvoice.voice_on_default_bearer_supported_bool" to ExplainKey(JsonPrimitive(false), "ocd:p"), "imsvoice.x_int" to ExplainKey(JsonPrimitive(1), "ocd:p"))
        assertEquals(setOf("imsvoice.x_int"), VoiceLoss.buildOverrides(served, build, emptySet(), device).keys)
        val cell = RecordCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", 34)
        val record = MeasuredRecord.build(
            RecordInput(
                cell, RecordCarrier.Profile("open.26207.c07dcc040b58"), RecordTarget.Config("imsvoice.voice_on_default_bearer_supported_bool"),
                RecordValue.Value(JsonPrimitive(true)), RecordResult.CALLS_OUT, "L2", "mailbox_call", "lte", null, now, now, "x",
                "5cb3c43fc536be51b9ee663ad04d05f07d546006", "sha256:" + "0".repeat(64),
            ),
        )
        assertNull(RecordCheck.problem(record, listOf(JsonPrimitive(true)), "bool", emptySet(), now))
        assertEquals(DeviceKeys.WHY, RecordCheck.problem(record, listOf(JsonPrimitive(true)), "bool", emptySet(), now, device))
    }

    @Test
    fun `a neighbouring cell's measured value is tried first, never served, and never added as a new candidate`() {
        val cell = RecordCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", 34)
        val carrier = RecordCarrier.Profile("open.26207.c07dcc040b58")
        fun rec(rom: String, fork: String?, value: Int, last: String = "2026-10-20", until: String = "2027-01-18", key: String = "imsvoice.x_int", device: String = "x1s") = Json.parseToJsonElement(
            """{"record_key":"r:0000000000000000","kind":"measured","device":{"codename":"$device","model":"SM-G981B"},"rom":{"family":"$rom","line":"24.0"},
                "ims_stack":{"id":"aosp_imsstack","fork":${fork?.let { "\"$it\"" } ?: "null"},"version":34},"carrier":{"scope":"profile","profile_id":"open.26207.c07dcc040b58"},
                "target":{"type":"config","key":"$key"},"value_kind":"value","value":$value,"last_measured":"$last","valid_until":"$until"}""",
        )
        val records = kotlinx.serialization.json.JsonArray(
            listOf(
                rec("lineageos", "ocd", 1), // the exact cell: CarrierSettings serves it, not a neighbour
                rec("aosp", null, 2, last = "2026-10-01"),
                rec("lineageos", null, 3, last = "2026-10-25"),
                rec("aosp", null, 4, until = "2026-10-01"), // expired
                rec("aosp", null, 5, key = "imsvoice.y_int"),
                rec("aosp", null, 6, device = "a50"),
            ),
        )
        val values = io.github.open_carrier_data.check.owner.core.NeighbourRecords.values(records, cell, carrier, "imsvoice.x_int", now)
        assertEquals(listOf(JsonPrimitive(3), JsonPrimitive(2)), values)
        val cands = listOf(io.github.open_carrier_data.check.owner.core.Candidate(JsonPrimitive(2), "alternative:a"), io.github.open_carrier_data.check.owner.core.Candidate(JsonPrimitive(9), "google"), io.github.open_carrier_data.check.owner.core.Candidate(null, "android_default"))
        val ordered = io.github.open_carrier_data.check.owner.core.NeighbourRecords.first(cands, values)
        assertEquals(listOf(JsonPrimitive(2), JsonPrimitive(9), null), ordered.map { it.value })
        assertEquals(cands.toSet(), ordered.toSet())
    }

    @Test
    fun `measured records only - the app writes no configured_device record and no carrier-less one`() {
        // RecordCarrier has no "all" scope any more: every record names a profile or a SIM key.
        assertFalse("data object All" in File("src/owner/java/io/github/open_carrier_data/check/owner/core/Records.kt").readText())
        val e = explain(emptyMap()).copy(serving = "default")
        assertNull(RecordCarrier.of(e, "262", "07", null, null))
        val sources = File("src/owner/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertFalse("\"configured_device\"" in sources)
    }

    @Test
    fun `SM8 - only AOSP ImsStack is calibrated, elsewhere results are not counted`() {
        assertTrue(StackCalibration.counted("aosp_imsstack"))
        for (stack in listOf("phh_ims", "samsung_ims", "other", "", null)) {
            assertFalse(StackCalibration.counted(stack))
            assertNotNull(StackCalibration.notCounted(stack))
        }
    }

    @Test
    fun `SM8 and SM9 - the passive check ends the campaign after one counted trial, with no split and no candidate`() {
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", listOf(Changed("imsvoice.x_int", JsonPrimitive(1), JsonPrimitive(2))), emptySet(), now)
        val failed = CampaignPlanner.passive(c, TrialVerdict(TrialOutcome.MEASURED_FAIL, reason = "IMS lost mmtel"), "not calibrated", now)
        assertEquals(CampaignPhase.DONE, failed.phase)
        assertTrue(failed.winners.isEmpty() && failed.candidates.isEmpty() && failed.suspects.isEmpty())
        assertTrue("no candidate is tried" in failed.note)
        val passed = CampaignPlanner.passive(c, TrialVerdict(TrialOutcome.PASS, reason = "IMS with the same services"), "not calibrated", now)
        assertEquals(CampaignPhase.DONE, passed.phase)
        val notCounted = CampaignPlanner.passive(c, TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "no service"), "x", now)
        assertEquals(CampaignPhase.BATCH, notCounted.phase)
    }

    @Test
    fun `SM9 and SM11 - the ROM build tests actively only after the opt-in, only where the allowlist marks it free, and never sends`() {
        val free = Mailbox("Vodafone", "5500", "x", FreeFlags(calls = true, sms = true))
        val notFree = Mailbox("Vodafone", "5500", "x")
        val off = TesterPolicy(TesterBuild.ROM, optIn = false)
        val on = TesterPolicy(TesterBuild.ROM, optIn = true)
        assertFalse(off.activeTests)
        assertEquals(TesterPolicy.OPT_IN_OFF, off.callRefusal(free))
        assertEquals(TesterPolicy.OPT_IN_OFF, off.smsRefusal(free))
        assertNull(on.callRefusal(free))
        assertNull(on.smsRefusal(free))
        assertEquals(TesterPolicy.NOT_FREE, on.callRefusal(notFree))
        assertEquals(TesterPolicy.NOT_FREE, on.smsRefusal(notFree))
        assertEquals(TesterPolicy.NOT_FREE, on.smsRefusal(null))
        assertEquals(TesterPolicy.NOT_FREE, on.callRefusal(Mailbox("x", "333", "x", FreeFlags(calls = false, sms = true))))
        assertFalse(off.sendsWithToken || on.sendsWithToken)
        // The emergency data test also needs the ROM builder's switch (SM10).
        assertNotNull(off.emergencyRefusal(buildSwitch = true, ownerFirstUsePassed = true))
        assertNotNull(on.emergencyRefusal(buildSwitch = null, ownerFirstUsePassed = true))
        assertNotNull(on.emergencyRefusal(buildSwitch = false, ownerFirstUsePassed = true))
        assertNull(on.emergencyRefusal(buildSwitch = true, ownerFirstUsePassed = false))
        // The owner build keeps its own switches and its token.
        val owner = TesterPolicy(TesterBuild.OWNER)
        assertTrue(owner.activeTests && owner.sendsWithToken)
        assertNull(owner.callRefusal(notFree))
        assertNull(owner.emergencyRefusal(buildSwitch = null, ownerFirstUsePassed = true))
        assertNull(owner.emergencyRefusal(buildSwitch = true, ownerFirstUsePassed = false))
        assertNotNull(owner.emergencyRefusal(buildSwitch = null, ownerFirstUsePassed = false))
        // An unknown build name is the stricter ROM build; the opt-in is off in a fresh state.
        assertEquals(TesterBuild.ROM, TesterBuild.of("something"))
        assertFalse(OwnerState().activeTests.on)
    }

    @Test
    fun `SM9 - the opt-in says in plain words what the app does`() {
        val text = OptInWords.explain(emergencyDataTest = true)
        for (part in listOf("mailbox", "at most 10 times a day", "SMS to your SIM's own number", "changes carrier settings", "emergency data connection, without making a call", "free", "never calls an emergency number", "nothing is sent", "a few kilobytes of test requests", "at most once every 6 hours")) {
            assertTrue(part, part.lowercase() in text.lowercase())
        }
        assertFalse("emergency data" in OptInWords.explain(emergencyDataTest = false))
    }

    @Test
    fun `the allowlist's free flags parse, and an entry without them is not free`() {
        val file = CallTargets.parse(
            """{"description":"x","by_spn":[{"spn":"LIDL Connect","mccmnc":["26202"],"brand":"LIDL","mailbox":"5500","verification":"x","free":{"calls":true,"sms":false}}],"by_mccmnc":{"26203":{"brand":"O2","mailbox":"333","verification":"x"}}}""",
        )
        val lidl = CallTargets.mailboxFor(file, "26202", "LIDL Connect") { false }!!
        assertTrue(lidl.freeCalls)
        assertFalse(lidl.freeSms)
        val o2 = CallTargets.mailboxFor(file, "26203", null) { false }!!
        assertFalse(o2.freeCalls || o2.freeSms)
        // The bundled table still parses.
        CallTargets.parse(File("src/main/assets/call-targets.json").readText())
    }

    @Test
    fun `no safe mode in the owner sources - not in the UI, the state, the notification or the protocol`() {
        val sources = File("src/owner").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
        for (f in sources) {
            val text = f.readText()
            for (line in text.lines()) {
                val l = line.lowercase()
                if ("safe mode" in l || "safemode" in l || "safe_mode" in l) {
                    // Only the comments that say it is gone may name it.
                    assertTrue("${f.name}: $line", "removed" in l || "no safe mode" in l || "safe mode is gone" in l || "round 31" in l || "old rom" in l || "round 30 rom" in l)
                }
            }
        }
    }
}
