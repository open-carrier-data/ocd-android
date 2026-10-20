package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SimKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.time.LocalDate

/**
 * The owner build's upload against OCD's public report schema and form,
 * copied into `testOwner/resources/public/` from the public repository's
 * `schemas/ocd-check-report.schema.json` and
 * `.github/ISSUE_TEMPLATE/ocd-check-report.yml`. Keep the copies in step: a
 * report this test passes is one the public bot's schema check passes.
 */
class ReportSchemaTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/public/$name")) { name }.use { it.readBytes().decodeToString() }

    private val schema = Json.parseToJsonElement(resource("ocd-check-report.schema.json")).jsonObject
    private val form = resource("ocd-check-report.yml")

    private val digest = "sha256:36d5f7ebb084a1a915c4961f8a5f3a11c70150b0fca7f68ccc050ffbe3989d37"
    private val profile = "open.26202.8cebd7550c67"
    private val cell = ReportCell(
        model = "SM-G981B", codename = "x1s", androidMajor = 16, romFamily = "lineageos", buildType = "userdebug", imsStack = "phh_ims",
        spn = "LIDL Connect", appVersion = "0.3.0-owner", dataRelease = "data-2026.10", dataDigest = digest, dataSource = "pulled",
    )
    private val ocdOn = OcdAnswer(true, "device_record", profile, recordSource = "samsung_omc", recordStack = "stock", sameStack = false)

    /** The S20 with the LIDL SIM on 2026-10-20 (as OverviewTest's run ee4601a5), with OCD's APN rows and an off test of Wi-Fi calling. */
    private fun s20Run() = RunResult(
        id = "ee4601a5", startedMs = 1_792_456_340_000L, scheduled = false, sim = SimKey(3, "26202", 2397), simName = "LIDL Connect",
        profileId = profile, releaseTag = "data-2026.10",
        features = listOf(
            FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn, offReason = "off_tests_not_chosen", best = true, method = "wfc_override", freshRegister = true, restored = true),
            FeatureResult(OwnerFeature.SMS_OVER_IMS, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.VIDEO_CALLING, false, false, OcdAnswer(null, "carrier_profile", profile, capability = "unknown"), onWorks = false, onReason = OwnerRunner.NOT_AVAILABLE, best = false),
            FeatureResult(OwnerFeature.VOWIFI, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offWorks = true, offReason = "service_gone", restored = true, best = true),
            FeatureResult(OwnerFeature.VONR, false, false, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), onWorks = false, onReason = "device_not_capable", best = false),
        ),
        apns = listOf(
            ApnResult("web.vodafone.de [default,supl] IPV4V6", "web.vodafone.de|default,supl|IPV4V6", "present"),
            ApnResult("web.vodafone.de [default,supl,xcap] IPV4V6", "web.vodafone.de|default,supl,xcap|IPV4V6", OwnerRunner.SAME_AS_PHONE_ROW),
            ApnResult("web.vodafone.de [default,dun] IPV4V6", "web.vodafone.de|default,dun|IPV4V6", "inserted", dataWorks = false),
        ),
        cell = cell,
    )

    private val calls = listOf(CallsTested(OwnerFeature.VOLTE, "26202"))

    private fun results(report: JsonObject): Map<String, JsonObject> =
        report.getValue("results").jsonArray.associate { it.jsonObject.getValue("entry").jsonPrimitive.content to it.jsonObject }

    @Test
    fun `the S20's report validates against the public schema`() {
        val report = IssueReport.report(s20Run(), cell, calls)!!
        assertEquals(emptyList<String>(), SchemaSubset.problems(report, schema))
        // The same text the URL carries, for a check with the public bot's tool (tools/observed_reports.py check-file).
        File("build/test-results").mkdirs()
        File("build/test-results/ocd-check-report-s20.json").writeText(IssueReport.text(report))
    }

    @Test
    fun `every result is a pointer into the release's closed list, never a value`() {
        val report = IssueReport.report(s20Run(), cell, calls)!!
        val byEntry = results(report)
        assertEquals(
            listOf(
                "volte:on", "volte:off", "sms_over_ims:on", "sms_over_ims:off", "video_calling:on", "video_calling:off",
                "vowifi:on", "vowifi:off", "vonr:on", "vonr:off",
                "apn:web.vodafone.de|default,supl|IPV4V6", "apn:web.vodafone.de|default,supl,xcap|IPV4V6", "apn:web.vodafone.de|default,dun|IPV4V6",
            ),
            report.getValue("results").jsonArray.map { it.jsonObject.getValue("entry").jsonPrimitive.content },
        )
        fun field(entry: String, key: String) = byEntry.getValue(entry)[key]
        assertEquals(JsonPrimitive("works"), field("volte:on", "result"))
        assertEquals(JsonPrimitive("observed"), field("volte:on", "how"))
        assertEquals(JsonPrimitive(true), field("volte:on", "ocd"))
        assertEquals(JsonPrimitive("wfc_override"), field("volte:on", "method"))
        assertEquals("[{\"test\":\"t2\",\"network\":\"lte\"}]", field("volte:on", "call_types").toString())
        assertNull("empty fields are left out", field("volte:on", "reason"))
        assertNull(field("volte:off", "call_types"))
        assertNull(field("volte:off", "kept"))
        assertEquals(JsonPrimitive("not_tested"), field("volte:off", "result"))
        assertEquals(JsonPrimitive("off_tests_not_chosen"), field("volte:off", "reason"))
        assertEquals(JsonPrimitive("signed_in"), field("vowifi:on", "result"))
        assertEquals(JsonPrimitive("service_off"), field("vowifi:off", "result"))
        assertEquals(JsonPrimitive(true), field("vowifi:off", "restored"))
        assertEquals(JsonPrimitive("not_possible"), field("vonr:on", "result"))
        assertEquals(JsonPrimitive("device_not_capable"), field("vonr:on", "reason"))
        assertEquals(JsonPrimitive(true), field("vonr:on", "ocd"))
        assertEquals(JsonPrimitive("service_off"), field("vonr:off", "result"))
        assertEquals(JsonPrimitive(true), field("vonr:off", "phone"))
        assertEquals(JsonPrimitive("not_possible"), field("video_calling:on", "result"))
        assertEquals(JsonPrimitive(false), field("video_calling:on", "ocd"))
        assertEquals(JsonPrimitive("not_tested"), field("apn:web.vodafone.de|default,supl|IPV4V6", "result"))
        assertEquals(JsonPrimitive(true), field("apn:web.vodafone.de|default,supl|IPV4V6", "phone"))
        assertEquals(JsonPrimitive("failed"), field("apn:web.vodafone.de|default,dun|IPV4V6", "result"))
        assertEquals(JsonPrimitive("no_data"), field("apn:web.vodafone.de|default,dun|IPV4V6", "reason"))
        // No value anywhere: no APN string beyond the row key, no CarrierConfig key, no Markdown.
        val text = IssueReport.text(report)
        for (forbidden in listOf("carrier_volte_available_bool", "\"apn\":", "mmsc", "|---|", "| Feature")) assertFalse(forbidden, forbidden in text)
        assertEquals("""{"tag":"data-2026.10","data_digest":"$digest"}""", report.getValue("release").toString())
    }

    @Test
    fun `the URL opens the public form with exactly one JSON block`() {
        val prefilled = IssueReport.prefill(s20Run(), cell, calls)!!
        assertTrue(prefilled.url.startsWith("https://github.com/open-carrier-data/open-carrier-data/issues/new?template=${IssueReport.TEMPLATE}&title="))
        assertTrue(prefilled.url.length <= IssueReport.MAX_URL)
        assertTrue("a signed-out browser's login URL fits too", IssueReport.loginLength(prefilled.url) <= IssueReport.MAX_URL)
        val params = prefilled.url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(setOf("template", "title", IssueReport.FIELD), params.keys)
        assertEquals("[OCD Check report] SM-G981B on 26202 (LIDL Connect)", params["title"])
        val text = params.getValue(IssueReport.FIELD)
        assertEquals(prefilled.text, text)
        assertTrue(text.startsWith("{\n") && text.endsWith("\n}"))
        assertEquals(IssueReport.report(s20Run(), cell, calls), Json.parseToJsonElement(text))
        // One result per line, readable on the issue.
        assertEquals(13, text.lines().count { it.startsWith("    {\"entry\":") })
    }

    @Test
    fun `the hash is the one the public bot computes`() {
        // python3: "sha256:" + hashlib.sha256('{\n  "schema": "ocd-check-test-report"\n}'.encode()).hexdigest()
        val text = "{\n  \"schema\": \"ocd-check-test-report\"\n}"
        assertEquals("sha256:1d84c3ec9d19ac510f1ed4b49a1b1c31b10363d38c8a3a70dd746a3cc875b5d2", IssueReport.hash(text))
        assertEquals(IssueReport.hash(text), IssueReport.hash("\r\n" + text.replace("\n", "\r\n") + "\n  "))
        val prefilled = IssueReport.prefill(s20Run(), cell, calls)!!
        assertEquals(IssueReport.hash(prefilled.text), prefilled.hash)
    }

    @Test
    fun `the form is the one the app prefills`() {
        assertTrue(Regex("(?m)^name: OCD Check report$").containsMatchIn(form))
        assertTrue(form.contains("title: \"${IssueReport.TITLE_PREFIX} \""))
        assertTrue(
            "the textarea the app prefills is id ${IssueReport.FIELD}, rendered as JSON",
            Regex("(?ms)^  - type: textarea\\n    id: ${IssueReport.FIELD}\\n    attributes:\\n      label: Report\\n.*?      render: json\\n").containsMatchIn(form),
        )
        assertEquals("ocd-check-test-report", schema.getValue("properties").jsonObject.getValue("schema").jsonObject.getValue("const").jsonPrimitive.content)
        assertEquals(IssueReport.SCHEMA_VERSION.toLong(), schema.getValue("properties").jsonObject.getValue("schema_version").jsonObject.getValue("const").jsonPrimitive.longOrNull)
    }

    @Test
    fun `many APN rows are left out and counted so the URL fits, and the report stays valid`() {
        val many = (1..60).map { ApnResult("internet$it.example [default] IPV4V6", "internet$it.example|default|IPV4V6", "inserted", dataWorks = it % 2 == 0, kept = false) }
        val run = s20Run().copy(apns = many)
        val prefilled = IssueReport.prefill(run, cell, calls)!!
        assertTrue(prefilled.url.length <= IssueReport.MAX_URL)
        assertTrue(IssueReport.loginLength(prefilled.url) <= IssueReport.MAX_URL)
        val report = Json.parseToJsonElement(prefilled.text).jsonObject
        val shown = report.getValue("results").jsonArray.count { it.jsonObject.getValue("entry").jsonPrimitive.content.startsWith("apn:") }
        assertEquals(60, shown + report.getValue("results_left_out").jsonPrimitive.longOrNull!!.toInt())
        assertEquals(emptyList<String>(), SchemaSubset.problems(report, schema))
    }

    @Test
    fun `a run without a release digest, a SIM or a result writes no report, and an identifier stops the URL`() {
        assertNull(IssueReport.report(s20Run(), cell.copy(dataDigest = ""), calls))
        assertNull(IssueReport.report(s20Run().copy(sim = null), cell, calls))
        assertNull(IssueReport.report(s20Run().copy(features = emptyList(), apns = emptyList()), cell, calls))
        assertNull(IssueReport.url(s20Run(), cell.copy(spn = "+491701234567"), calls))
        assertNull(IssueReport.url(s20Run(), cell.copy(spn = "sip:4917012345@ims"), calls))
        // A bundled snapshot names no tag; the bot finds the release by its digest.
        val bundled = IssueReport.report(s20Run(), cell.copy(dataRelease = null, dataSource = "bundled"), calls)!!
        assertEquals(JsonNull, bundled.getValue("release").jsonObject["tag"])
        assertEquals(emptyList<String>(), SchemaSubset.problems(bundled, schema))
    }

    @Test
    fun `the schema check finds what the public bot's schema check finds`() {
        val report = IssueReport.report(s20Run(), cell, calls)!!
        fun broken(change: (MutableMap<String, JsonElement>) -> Unit): List<String> =
            SchemaSubset.problems(JsonObject(report.toMutableMap().also(change)), schema)
        assertTrue(broken { it["imsi"] = JsonPrimitive("1") }.any { "unknown key imsi" in it })
        assertTrue(broken { it.remove("release") }.any { "lacks release" in it })
        assertTrue(broken { it["tested_on"] = JsonPrimitive("2026-13-01") }.any { "not a date" in it })
        val badEntry = JsonArray(listOf(JsonObject(report.getValue("results").jsonArray[0].jsonObject + ("entry" to JsonPrimitive("carrier_volte_available_bool:on")))))
        assertTrue(broken { it["results"] = badEntry }.any { "results[0].entry" in it })
        assertNotNull(LocalDate.parse(report.getValue("tested_on").jsonPrimitive.content))
    }
}

/**
 * The JSON Schema keywords OCD's public validators implement
 * (validate_public_carrier_data.py SCHEMA_KEYWORDS), on kotlinx JSON. A
 * schema with any other keyword is refused, as there.
 */
object SchemaSubset {
    private val ANNOTATIONS = setOf("\$schema", "\$id", "title", "description")
    private val KEYWORDS = ANNOTATIONS + setOf(
        "\$defs", "\$ref", "type", "const", "enum", "anyOf", "properties", "required", "additionalProperties", "propertyNames",
        "items", "minItems", "maxItems", "uniqueItems", "minimum", "maximum", "minLength", "maxLength", "pattern", "format",
    )

    fun problems(value: JsonElement, root: JsonObject): List<String> {
        walk(root, "#")
        return mutableListOf<String>().also { check(value, root, root, "$", it) }
    }

    private fun walk(node: JsonObject, where: String) {
        val unknown = node.keys - KEYWORDS
        require(unknown.isEmpty()) { "$where uses unsupported keywords $unknown" }
        for (keyword in listOf("\$defs", "properties")) node[keyword]?.jsonObject?.forEach { (k, v) -> walk(v.jsonObject, "$where/$keyword/$k") }
        for (keyword in listOf("items", "propertyNames")) node[keyword]?.let { walk(it.jsonObject, "$where/$keyword") }
        (node["additionalProperties"] as? JsonObject)?.let { walk(it, "$where/additionalProperties") }
        node["anyOf"]?.jsonArray?.forEachIndexed { i, v -> walk(v.jsonObject, "$where/anyOf/$i") }
        node["format"]?.let { require(it.jsonPrimitive.content == "date") { "$where uses an unsupported format" } }
    }

    private fun isType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value is JsonNull
        else -> error("type $type")
    }

    private fun check(value: JsonElement, schema: JsonObject, root: JsonObject, where: String, out: MutableList<String>) {
        schema["\$ref"]?.let { ref ->
            val name = ref.jsonPrimitive.content.removePrefix("#/\$defs/")
            check(value, root.getValue("\$defs").jsonObject.getValue(name).jsonObject, root, where, out)
        }
        schema["type"]?.let { type ->
            val names = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (names.none { isType(value, it) }) {
                out += "$where: must be of type ${names.joinToString("/")}"
                return
            }
        }
        schema["const"]?.let { if (it != value) { out += "$where: must be $it"; return } }
        schema["enum"]?.let { values -> if (value !in values.jsonArray) { out += "$where: must be one of ${values.jsonArray}"; return } }
        schema["anyOf"]?.let { options ->
            if (options.jsonArray.none { option -> mutableListOf<String>().also { check(value, option.jsonObject, root, where, it) }.isEmpty() }) {
                out += "$where: matches none of anyOf"
                return
            }
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            schema["minLength"]?.let { if (text.length < it.jsonPrimitive.longOrNull!!) out += "$where: is shorter than $it" }
            schema["maxLength"]?.let { if (text.length > it.jsonPrimitive.longOrNull!!) out += "$where: is longer than $it" }
            schema["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(text)) out += "$where: does not have the allowed form" }
            if (schema["format"]?.jsonPrimitive?.content == "date" && runCatching { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)); LocalDate.parse(text) }.isFailure) {
                out += "$where: is not a date"
            }
        }
        if (value is JsonPrimitive && !value.isString && value.booleanOrNull == null && value.doubleOrNull != null) {
            val number = value.doubleOrNull!!
            schema["minimum"]?.let { if (number < it.jsonPrimitive.doubleOrNull!!) out += "$where: is below $it" }
            schema["maximum"]?.let { if (number > it.jsonPrimitive.doubleOrNull!!) out += "$where: is above $it" }
        }
        if (value is JsonArray) {
            schema["minItems"]?.let { if (value.size < it.jsonPrimitive.longOrNull!!) out += "$where: has fewer than $it items" }
            schema["maxItems"]?.let { if (value.size > it.jsonPrimitive.longOrNull!!) out += "$where: has more than $it items" }
            if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && value.toSet().size != value.size) out += "$where: has repeated items"
            schema["items"]?.let { items -> value.forEachIndexed { i, item -> check(item, items.jsonObject, root, "$where[$i]", out) } }
        }
        if (value is JsonObject) {
            schema["required"]?.jsonArray?.forEach { key -> if (key.jsonPrimitive.content !in value) out += "$where: lacks ${key.jsonPrimitive.content}" }
            val properties = schema["properties"]?.jsonObject.orEmpty()
            for ((key, item) in value) {
                schema["propertyNames"]?.let { check(JsonPrimitive(key), it.jsonObject, root, "$where key $key", out) }
                val child = properties[key]
                val extra = schema["additionalProperties"]
                when {
                    child != null -> check(item, child.jsonObject, root, "$where.$key", out)
                    extra is JsonPrimitive && extra.booleanOrNull == false -> out += "$where: has the unknown key $key"
                    extra is JsonObject -> check(item, extra, root, "$where.$key", out)
                }
            }
        }
    }
}
