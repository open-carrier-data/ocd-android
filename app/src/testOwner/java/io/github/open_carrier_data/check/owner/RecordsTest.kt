package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.BuildCell
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.Groups
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.NeighbourRecords
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordCheck
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.owner.core.TypedValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Round 30's measured records (spec 2.10 and 6.4): the key, the members, the candidate check, groups A and B, privacy, what does not lapse. */
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

    /** The spec's nine lines, hashed by hand (spec 2.10), to check the app's formula. */
    private fun nineLines(vararg lines: String): String =
        "r:" + java.security.MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Test
    fun `record_key - the spec's formula, which keys the five S20 records too`() {
        // The five S20 records on main until round 31's step 8 ("all" carriers, configured_device): the formula gives their keys.
        assertEquals("r:40ee9931e862c76c", nineLines("configured_device", "x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "", "all", "config:imsvoice.voice_on_default_bearer_supported_bool"))
        assertEquals("r:63d82c4e3fa3a0ae", nineLines("configured_device", "x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", "all", "config:imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool"))
        // Round 31: the app writes measured records only, always with a carrier key, and its key follows the same formula.
        val c = RecordCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", null)
        val profile = RecordCarrier.Profile("open.26207.c07dcc040b58")
        assertEquals(
            nineLines("measured", "x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", "profile:open.26207.c07dcc040b58", "config:carrier_volte_available_bool"),
            MeasuredRecord.key("measured", c, profile, RecordTarget.Config("carrier_volte_available_bool")),
        )
        // The model is upper-cased for the key; the stack version is recorded, not matched; the fork is part of the cell (R31.10).
        assertEquals(MeasuredRecord.key("measured", c, profile, RecordTarget.Config("k")), MeasuredRecord.key("measured", c.copy(model = "sm-g981b", stackVersion = 99), profile, RecordTarget.Config("k")))
        assertTrue(MeasuredRecord.key("measured", c, profile, RecordTarget.Config("k")) != MeasuredRecord.key("measured", c.copy(fork = null), profile, RecordTarget.Config("k")))
    }

    @Test
    fun `a measured record has the spec's 24 members in order, the 90-day expiry, and names the data it was measured on`() {
        val r = record()
        assertEquals(MeasuredRecord.MEMBERS, r.keys.toList())
        assertEquals("measured", r["kind"]!!.jsonPrimitive.content)
        assertEquals("measured", r["claim"]!!.jsonPrimitive.content)
        assertEquals("local", r["tester"]!!.jsonPrimitive.content)
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
        val old = record(at = now - 100L * 86_400_000, result = RecordResult.WORKS)
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
    fun `every record stays on this phone as local, on every build`() {
        assertEquals("local", record()["tester"]!!.jsonPrimitive.content)
        assertEquals(MeasuredRecord.MEMBERS, record().keys.toList())
    }

    @Test
    fun `emergency_default_won and winner_after_failure do not lapse, as in CarrierSettings's BundleBuilder expired`() {
        val past = now - 100L * 86_400_000
        val winner = record(at = past)
        assertEquals("2026-10-16", winner["valid_until"]!!.jsonPrimitive.content)
        assertNull(RecordCheck.problem(winner, listOf(JsonPrimitive(true)), "bool", emptySet(), now))
        val won = record(target = RecordTarget.EmergencyData("a:a913d9286c805fdb"), value = RecordValue.None, result = RecordResult.EMERGENCY_DEFAULT_WON, at = past, basis = "Android's default won; says nothing about emergency calls")
        assertNull(RecordCheck.problem(won, emptyList(), null, emptySet(), now))
        // The rest of the results lapse as before, and a record without the field is still refused.
        for (result in RecordResult.entries.filterNot { it.wire in MeasuredRecord.NO_LAPSE }) {
            assertEquals(result.wire, "expired", RecordCheck.problem(record(at = past, result = result), listOf(JsonPrimitive(true)), "bool", emptySet(), now))
        }
        val noDate = JsonObject(winner + ("valid_until" to JsonNull))
        assertEquals("no valid_until", RecordCheck.problem(noDate, listOf(JsonPrimitive(true)), "bool", emptySet(), now))
    }

    @Test
    fun `a neighbouring cell's winner past its valid_until still comes first, another lapsed result does not`() {
        val neighbour = RecordCell("x1s", "SM-G981B", "lineageos", "23.0", "aosp_imsstack", null, 34)
        fun rec(result: RecordResult, value: Boolean) = MeasuredRecord.build(
            RecordInput(neighbour, RecordCarrier.Profile("open.26207.c07dcc040b58"), RecordTarget.Config("carrier_volte_available_bool"), RecordValue.Value(JsonPrimitive(value)), result, "L2", "override_trial", "lte", 3, now - 100L * 86_400_000, now - 100L * 86_400_000, "measured", commit, digest),
        )
        val records = JsonArray(listOf(rec(RecordResult.WINNER_AFTER_FAILURE, true), rec(RecordResult.WORKS, false)))
        assertEquals(listOf<JsonElement>(JsonPrimitive(true)), NeighbourRecords.values(records, cell, RecordCarrier.Profile("open.26207.c07dcc040b58"), "carrier_volte_available_bool", now))
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
