package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.BuildCell
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.Groups
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordCheck
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordReport
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.owner.core.SentRecord
import io.github.open_carrier_data.check.owner.core.TypedValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Round 30's measured records (spec 2.10 and 6.4): the key, the members, the candidate check, groups A and B, privacy, the issue. */
class RecordsTest {
    private val cell = RecordCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", null, 34)
    private val now = LocalDate.of(2026, 10, 26).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
    private val commit = "5cb3c43fc536be51b9ee663ad04d05f07d546006"
    private val digest = "sha256:" + "50c89010".repeat(8)

    private fun record(
        key: String = "carrier_volte_available_bool",
        value: RecordValue = RecordValue.Value(JsonPrimitive(true)),
        carrier: RecordCarrier = RecordCarrier.Profile("open.26207.c07dcc040b58"),
        result: RecordResult = RecordResult.WINNER_AFTER_FAILURE,
        at: Long = now,
        basis: String = "measured failure with false; true passed under the same conditions",
        target: RecordTarget = RecordTarget.Config(key),
    ) = MeasuredRecord.build(RecordInput(cell, carrier, target, value, result, "L2", "override_trial", "lte", 3, at, at, basis, commit, digest))

    @Test
    fun `record_key - the spec's five S20 records`() {
        val c = RecordCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", null, null)
        val all = RecordCarrier.All
        assertEquals("r:40ee9931e862c76c", MeasuredRecord.key("configured_device", c, all, RecordTarget.Config("imsvoice.voice_on_default_bearer_supported_bool")))
        assertEquals("r:63d82c4e3fa3a0ae", MeasuredRecord.key("configured_device", c.copy(fork = "ocd"), all, RecordTarget.Config("imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool")))
        assertEquals("r:5e72fea0c8d2f885", MeasuredRecord.key("configured_device", c, all, RecordTarget.Config("imsvoice.audio_evs_support_bool")))
        assertEquals("r:e41c807e9f154e6a", MeasuredRecord.key("configured_device", c, all, RecordTarget.Config("gps.longterm_psds_server_1")))
        assertEquals("r:937a114148bf73b8", MeasuredRecord.key("configured_device", c, all, RecordTarget.Config("imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool")))
        // The model is upper-cased for the key; the stack version is recorded, not matched.
        assertEquals(MeasuredRecord.key("measured", c, all, RecordTarget.Config("k")), MeasuredRecord.key("measured", c.copy(model = "sm-g981b", stackVersion = 99), all, RecordTarget.Config("k")))
    }

    @Test
    fun `a measured record has the spec's 24 members in order, the 90-day expiry, and names the data it was measured on`() {
        val r = record()
        assertEquals(MeasuredRecord.MEMBERS, r.keys.toList())
        assertEquals("measured", r["kind"]!!.jsonPrimitive.content)
        assertEquals("measured", r["claim"]!!.jsonPrimitive.content)
        assertEquals("owner", r["tester"]!!.jsonPrimitive.content)
        assertEquals("2026-10-26", r["last_measured"]!!.jsonPrimitive.content)
        assertEquals("2027-01-24", r["valid_until"]!!.jsonPrimitive.content)
        assertEquals(commit, r["data"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, r["second_agent_read"])
        assertEquals("aosp_imsstack", r["ims_stack"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(MeasuredRecord.key("measured", cell, RecordCarrier.Profile("open.26207.c07dcc040b58"), RecordTarget.Config("carrier_volte_available_bool")), MeasuredRecord.recordKey(r))
        val androidDefault = record(value = RecordValue.AndroidDefault)
        assertEquals("android_default", androidDefault["value_kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, androidDefault["value"])
    }

    @Test
    fun `SIMs served by default or a fill-only profile are keyed by network and SPN, else GID1, never by the network alone`() {
        val byDefault = ExplainView(subId = 3, serving = "default")
        val fill = ExplainView(subId = 3, serving = "fill.00101.0123456789ab")
        val profile = ExplainView(subId = 3, serving = "open.26202.8cebd7550c67")
        assertEquals("profile:open.26202.8cebd7550c67", RecordCarrier.of(profile, "262", "02", "Lidl Connect", "11AB")!!.text)
        assertEquals("sim:262-02:spn=Lidl Connect", RecordCarrier.of(byDefault, "262", "02", "Lidl Connect", "11ab")!!.text)
        assertEquals("sim:001-01:gid1=ABCD2025FF", RecordCarrier.of(fill, "001", "01", null, "abcd2025ff")!!.text)
        assertEquals("ABCD2025FF", (RecordCarrier.of(fill, "001", "01", "", "abcd2025ff") as RecordCarrier.SimKeyed).json()["gid1"]!!.jsonPrimitive.content)
        assertNull(RecordCarrier.of(byDefault, "999", "99", null, null))
    }

    @Test
    fun `the cell comes from CarrierSettings's status, and only a known IMS stack makes one`() {
        assertNotNull(RecordCell.of(BuildCell("x", "x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", 34)))
        assertNull(RecordCell.of(BuildCell("x", "x1s", "SM-G981B", "lineageos", "24.0", "some_stack", null, null)))
        assertNull(RecordCell.of(BuildCell(device = "x1s")))
    }

    @Test
    fun `group A, group B and the version string never become records`() {
        for (key in listOf(
            "carrier_use_ims_first_for_emergency_bool", "imsemergency.emergency_domain_preference_int_array", "ecbm_timer_int", "rtt_supported_bool",
            "call_forwarding_blocks_while_roaming_string_array", "ims_reasoninfo_mapping_string_array",
            // The owner's eight of 2026-10-25.
            "use_only_dialed_sim_ecc_list_bool", "rilext.carrier_ignore_clir_when_ecc", "rilext.carrier_ignore_sms_for_eims",
            "rilext.exiting_ecc_mode_after_epdn_disconnected_enable", "rilext.ims_prefer_apm_normal_ecc_enable", "gps.es_extension_sec",
            "dial_string_replace_string_array", "international_roaming_dial_string_replace_string_array",
            "carrier_metered_apn_types_strings", "carrier_metered_roaming_apn_types_strings", "carrier_config_version_string",
        )) {
            assertNotNull(key, Groups.untestable(key))
            assertNotNull(key, RecordCheck.problem(record(key = key, value = RecordValue.AndroidDefault), emptyList(), null, emptySet(), now))
        }
        // The dataset's own marking adds to CarrierSettings's, never takes away.
        assertNotNull(Groups.untestable("some_other_key", setOf("some_other_key")))
        assertNull(Groups.untestable("carrier_volte_available_bool", setOf("some_other_key")))
        // The pattern's ecm needs a separator: "ecm" inside a word is not group A.
        assertTrue(Groups.isA("ims.ecm_timer"))
        assertFalse(Groups.isA("carrier_ecmp_bool"))
    }

    @Test
    fun `a config record holds a value OCD knows for the key and profile, of the key's type`() {
        val r = record(value = RecordValue.Value(JsonPrimitive(false)))
        assertNull(RecordCheck.problem(r, listOf(JsonPrimitive(true), JsonPrimitive(false)), "bool", emptySet(), now))
        assertEquals("the value is not one OCD knows for this key and profile", RecordCheck.problem(r, listOf(JsonPrimitive(true)), "bool", emptySet(), now))
        assertEquals("the value is not a int", RecordCheck.problem(r, listOf(JsonPrimitive(false)), "int", emptySet(), now))
        // Android's default needs no candidate.
        assertNull(RecordCheck.problem(record(value = RecordValue.AndroidDefault), emptyList(), "bool", emptySet(), now))
        // No value passed is informational: value_kind none.
        assertNull(RecordCheck.problem(record(value = RecordValue.None, result = RecordResult.NO_VALUE_PASSED), emptyList(), "bool", emptySet(), now))
    }

    @Test
    fun `an expired record and one the privacy scan refuses are never written`() {
        val old = record(at = now - 100L * 86_400_000)
        assertEquals("expired", RecordCheck.problem(old, listOf(JsonPrimitive(true)), "bool", emptySet(), now))
        val ip = record(key = "iwlan.epdg_static_address_string", value = RecordValue.Value(JsonPrimitive("192.0.2.10")))
        assertTrue(RecordCheck.problem(ip, listOf(JsonPrimitive("192.0.2.10")), "string", emptySet(), now)!!.startsWith("the privacy scan refused"))
        val number = record(basis = "called +4917612345678")
        assertTrue(RecordCheck.problem(number, listOf(JsonPrimitive(true)), "bool", emptySet(), now)!!.startsWith("the privacy scan refused"))
        // The record's own hex fields pass: the key, the commit, the digest.
        assertNull(RecordCheck.problem(record(), listOf(JsonPrimitive(true)), "bool", emptySet(), now))
    }

    @Test
    fun `an emergency data record holds no value and never speaks about calls`() {
        val r = record(target = RecordTarget.EmergencyData("a:a913d9286c805fdb"), value = RecordValue.None, result = RecordResult.EMERGENCY_DATA_ACCEPTED, basis = "emergency data connection accepted; says nothing about emergency calls")
        assertNull(RecordCheck.problem(r, emptyList(), null, emptySet(), now))
        assertEquals("emergency_data:a:a913d9286c805fdb", RecordTarget.EmergencyData("a:a913d9286c805fdb").text)
        assertEquals("emergency_data:default", RecordTarget.EmergencyData(null).text)
        val withValue = record(target = RecordTarget.EmergencyData(null), value = RecordValue.Value(JsonPrimitive(true)), result = RecordResult.EMERGENCY_DATA_ACCEPTED)
        assertEquals("an emergency data record holds no value", RecordCheck.problem(withValue, emptyList(), null, emptySet(), now))
    }

    @Test
    fun `a record goes to main as one issue with exactly the record as its JSON block, labelled for the owner-only intake`() {
        val r = record()
        val issue = RecordReport.issue(r)
        assertEquals("ocd-device-record", RecordReport.LABEL)
        assertTrue(issue.title.startsWith("[OCD device record] winner_after_failure: carrier_volte_available_bool for open.26207.c07dcc040b58 on SM-G981B"))
        val blocks = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(issue.body).map { it.groupValues[1] }.toList()
        assertEquals(1, blocks.size)
        assertEquals(r, Json.parseToJsonElement(blocks.single()))
        assertEquals(MeasuredRecord.recordKey(r), issue.recordKey)
    }

    @Test
    fun `a record is sent again only with a new result or measurement day, or after 14 days`() {
        val r = record()
        val sent = RecordReport.record(emptyList(), r, now, 7)
        assertTrue(RecordReport.due(listOf(r), sent, now + 3_600_000).isEmpty())
        assertEquals(1, RecordReport.due(listOf(r), sent, now + 15L * 86_400_000).size)
        val later = record(at = now + 86_400_000)
        assertEquals(1, RecordReport.due(listOf(later), sent, now + 86_400_000).size)
        assertEquals(listOf(SentRecord(MeasuredRecord.recordKey(r), "2026-10-26", "winner_after_failure", now, 7)), sent)
    }

    @Test
    fun `typed values follow the registry's JSON forms`() {
        assertTrue(TypedValues.fits("bool", JsonPrimitive(true)))
        assertFalse(TypedValues.fits("bool", JsonPrimitive("true")))
        assertTrue(TypedValues.fits("int_array", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))))
        assertFalse(TypedValues.fits("int_array", JsonArray(listOf(JsonPrimitive("2")))))
        assertTrue(TypedValues.fits("bundle", Json.parseToJsonElement("""{"103":{"type":"int","value":1}}""")))
        assertFalse(TypedValues.fits("int", JsonPrimitive(2_147_483_648L)))
        assertEquals("string_array", TypedValues.typeBySuffix("carrier_metered_apn_types_strings"))
        assertNull(TypedValues.typeBySuffix("iwlan.ike_rekey_hard_timer_in_sec"))
    }
}
