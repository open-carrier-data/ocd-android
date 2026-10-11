package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.deep
import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.Fixtures.sim
import io.github.open_carrier_data.check.core.ApnListParser
import io.github.open_carrier_data.check.core.DataConnection
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.EpdgLookupParser
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.SamsungImsParser
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private fun report(readings: PhoneReadings = readings(), includeGid1: Boolean = false): ReportResult {
        val resolution = readings.sim?.identity()?.let { Matcher.resolve(Fixtures.bundle, it) }
        val findings = Findings.compare(readings, resolution, Fixtures.bundle)
        return Report.build(readings, resolution, findings, Fixtures.bundle, "0.1.0", includeGid1)
    }

    private fun keys(element: JsonElement, path: String = ""): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, child) -> listOf("$path.$key") + keys(child, "$path.$key") }
        is JsonArray -> element.flatMap { keys(it, "$path[]") }
        else -> emptyList()
    }

    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap(::strings)
        is JsonArray -> element.flatMap(::strings)
        JsonNull -> emptyList()
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
    }

    @Test
    fun `a normal report passes the privacy scan and holds only allowlisted keys`() {
        val result = report()
        assertEquals(emptyList<Any>(), result.hits)
        assertTrue(result.shareable)
        assertEquals(emptyList<String>(), Report.unexpectedKeys(result.json))
        assertEquals("ocd-check-report.v1", result.json["schema"]!!.jsonPrimitive.content)
        assertEquals("none", result.json["phone_writes_performed"]!!.jsonPrimitive.content)
        assertEquals("FP5", result.json["device"]!!.jsonObject["codename"]!!.jsonPrimitive.content)
        assertEquals("normal", result.json["app"]!!.jsonObject["access_tier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a Shizuku report holds the deep check's typed results and passes the scan`() {
        val result = report(readings(deep = deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE)))
        assertEquals(emptyList<Any>(), result.hits)
        assertEquals("shizuku", result.json["app"]!!.jsonObject["access_tier"]!!.jsonPrimitive.content)
        val ims = result.json["ims_registration"]!!.jsonObject
        assertEquals("samsung_ims", ims["source"]!!.jsonPrimitive.content)
        assertEquals("true", ims["registered"]!!.jsonPrimitive.content)
        assertEquals("lte", ims["transport"]!!.jsonPrimitive.content)
        assertEquals(listOf("mmtel", "smsip"), ims["services"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Vodafone_DE", ims["mno"]!!.jsonPrimitive.content)
        val wifi = result.json["wifi_calling"]!!.jsonObject
        assertEquals("on", wifi["switch"]!!.jsonPrimitive.content)
        assertEquals("does_not_resolve", wifi["epdg_name"]!!.jsonPrimitive.content)
        assertEquals("wifi", wifi["lookup_network"]!!.jsonPrimitive.content)
        val rows = result.json["apn_list"]!!.jsonObject["rows"]!!.jsonArray
        assertEquals("web.vodafone.de", rows[0].jsonObject["apn"]!!.jsonPrimitive.content)
        val findings = result.json["findings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(findings.toString(), "wifi_calling_not_signed_in_epdg_dns" in findings)
        assertFalse(Findings.NEEDS_DEEP_CHECK in findings)
        assertEquals("none", result.json["phone_writes_performed"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a Shizuku report built from dumps full of identifiers holds none of them`() {
        val dump = """
  SIM slot: [0] state: [REGISTERED] IMS Profile: [Name : Telekom DE IMS, enabled : 2, pdn : ims]
  impi: 262011234567890@ims.mnc001.mcc262.3gppnetwork.org, impu: sip:+491510000001@ims.telekom.de
  Governor: RegiGvnBase [mMno=Telekom_DE, mFailureCounter=0]
  P-CSCF: [2a01:598:0:0:1, 10.1.2.3] iccid=8949010000000000001 imei=350000000000001
    slot[0]: [Telekom DE IMS|REGISTERED] onRegistered: RAT = 13, profile=Telekom DE IMS, service=[mmtel, smsip, sip:+491510000001]
"""
        val apns = "Row: 0 apn=internet.telekom, type=default,supl, protocol=IPV4V6, roaming_protocol=IPV4V6, " +
            "carrier_enabled=1, mvno_type=NULL, edited=0\nRow: 1 apn=+491510000001, type=default, protocol=IP, " +
            "roaming_protocol=IP, carrier_enabled=1, mvno_type=NULL, edited=1"
        val ping = "PING epdg.epc.mnc001.mcc262.pub.3gppnetwork.org (10.1.2.3) 56(84) bytes of data."
        val deep = DeepCheckReading(
            ims = SamsungImsParser.parse(dump.lineSequence(), SimSlot.of(0)!!),
            wifiCallingSwitch = WifiCallingSwitch.OFF,
            epdgName = EpdgLookupParser.parse(ping.lineSequence()),
            apnList = ApnListParser.parse(apns.lineSequence()),
        )
        val result = report(readings(sim = sim(mccmnc = "26201", spn = "Telekom.de", carrierId = 3, specificCarrierId = 3), deep = deep))
        assertTrue(result.hits.toString(), result.shareable)
        for (value in listOf("262011234567890", "491510000001", "2a01:598", "10.1.2.3", "8949010000000000001", "350000000000001", "sip:")) {
            assertFalse(value, value in result.text)
        }
        assertEquals(1, result.json["apn_list"]!!.jsonObject["rows"]!!.jsonArray.size)
        assertEquals("Telekom_DE", result.json["ims_registration"]!!.jsonObject["mno"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no identifier, location, build or emergency key can appear`() {
        val all = keys(report(readings(sim = sim(gid1 = "BA01")), includeGid1 = true).json)
        val forbidden = Regex(
            "(?i)(imsi|iccid|imei|meid|msisdn|tac\\b|phone_number|number|serial|android_id|device_id|" +
                "location|latitude|longitude|cell|lac|pci|arfcn|ssid|ip_address|fingerprint|build_user|" +
                "host|baseband|password|user|emergency|e911|time\\b|timestamp)",
        )
        for (key in all) assertFalse(key, forbidden.containsMatchIn(key.substringAfterLast('.')))
    }

    @Test
    fun `date only, no time of day`() {
        val result = report()
        assertEquals("2026-10-10", result.json["checked_on"]!!.jsonPrimitive.content)
        assertFalse(Regex("\\d{2}:\\d{2}").containsMatchIn(result.text))
    }

    @Test
    fun `findings go in as codes, never as free text`() {
        val findings = report().json["findings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(Findings.NEEDS_DEEP_CHECK in findings)
        for (code in findings) assertTrue(code, Regex("^[a-z_]+(:[a-z_]+)?$").matches(code))
        assertEquals("needs_deep_check", report().json["ims_registration"]!!.jsonPrimitive.content)
    }

    @Test
    fun `carrier settings appear only as a per-key comparison with OCD`() {
        val result = report(readings(settings = settings(volte = false, wfc = true)))
        val keys = result.json["carrier_settings"]!!.jsonObject["keys"]!!.jsonObject
        val volte = keys["carrier_volte_available_bool"]!!.jsonObject
        assertEquals(setOf("ocd_capability", "ocd_value", "matches_ocd"), volte.keys)
        assertEquals("conditional", volte["ocd_value"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, volte["matches_ocd"]) // conditional: nothing to compare
        assertEquals("true", keys["carrier_wfc_ims_available_bool"]!!.jsonObject["matches_ocd"]!!.jsonPrimitive.content)
        assertFalse(result.text.contains("carrier_nr_availabilities_int_array"))
    }

    @Test
    fun `GID1 is in the report only when the user switches it on`() {
        val readings = readings(sim = sim(gid1 = "BA01270000000000"))
        val off = report(readings, includeGid1 = false).json["sim"]!!.jsonObject
        assertNull(off["gid1"])
        assertEquals("left_out_by_user", off["gid1_status"]!!.jsonPrimitive.content)
        val on = report(readings, includeGid1 = true)
        assertEquals("BA01270000000000", on.json["sim"]!!.jsonObject["gid1"]!!.jsonPrimitive.content)
        assertTrue(on.shareable)
        val denied = report(readings(sim = sim(gid1 = null, gid1Status = Gid1Status.NOT_PERMITTED)), includeGid1 = true)
        assertEquals("not_permitted", denied.json["sim"]!!.jsonObject["gid1_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a GID1 that looks like a long number blocks sharing (fail closed)`() {
        val result = report(readings(sim = sim(gid1 = "2800000000000000")), includeGid1 = true)
        assertFalse(result.shareable)
        assertEquals(listOf("report.sim.gid1"), result.hits.map { it.path }.distinct())
        assertTrue(report(readings(sim = sim(gid1 = "2800000000000000")), includeGid1 = false).shareable)
    }

    @Test
    fun `identifier-shaped readings block copy and share and are named by field, not by value`() {
        val cases = mapOf(
            "+4915112345678" to "international_phone",
            "Call 0151 23456789" to "national_phone",
            "+49 151 2345678" to "spaced_international_phone",
            "262021234567890" to "long_decimal_identity",
            "me@example.org" to "email",
            "sip:262021234567890@ims.example" to "sip_or_tel_uri",
            "10.20.30.40" to "ipv4_address",
            "2001:db8:0:0:1" to "ipv6_address",
            "token" to "secret_word",
        )
        for ((value, rule) in cases) {
            val result = report(readings(sim = sim(spn = value)))
            assertFalse(value, result.shareable)
            assertTrue("$value -> ${result.hits}", result.hits.any { it.path == "report.sim.spn" && it.rule == rule })
            assertTrue(result.hits.none { value in it.path || value in it.rule })
        }
        val apn = report(readings(connections = listOf(DataConnection("192.168.1.10", listOf("internet"), Radio.LTE))))
        assertFalse(apn.shareable)
        assertEquals("report.data_connections[0].apn", apn.hits.single().path)
    }

    @Test
    fun `non-ASCII digits are scanned like ASCII digits`() {
        val arabicIndic = "+٤٩١٥١١٢٣٤٥٦٧٨" // +4915112345678 in Arabic-Indic digits
        assertFalse(report(readings(sim = sim(spn = arabicIndic))).shareable)
    }

    @Test
    fun `every string value of a normal report is scanned clean`() {
        val result = report(readings(sim = sim(mccmnc = "26201", spn = "Telekom.de", carrierId = 3, specificCarrierId = 3)))
        assertTrue(result.hits.toString(), result.shareable)
        assertTrue(strings(result.json).isNotEmpty())
    }
}
