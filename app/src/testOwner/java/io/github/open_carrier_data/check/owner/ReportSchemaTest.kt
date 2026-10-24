package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SchemaSubset
import io.github.open_carrier_data.check.owner.core.SimKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** The main commit the owner build pinned (round 29 S13: the report names it instead of a release tag). */
    private val commit = "e32fd75687d01e7271b640c3adb41c52d42af6a8"
    private val cell = ReportCell(
        model = "SM-G981B", codename = "x1s", androidMajor = 16, romFamily = "lineageos", buildType = "userdebug", imsStack = "phh_ims",
        spn = "LIDL Connect", appVersion = "0.3.0-owner", dataDigest = digest, dataSource = "pulled", dataCommit = commit,
    )
    private val ocdOn = OcdAnswer(true, "device_record", profile, recordSource = "samsung_omc", recordStack = "stock", sameStack = false)

    /** The S20 with the LIDL SIM on 2026-10-20 (as OverviewTest's run ee4601a5), with OCD's APN rows and an off test of Wi-Fi calling. */
    private fun s20Run() = RunResult(
        id = "ee4601a5", startedMs = 1_792_456_340_000L, scheduled = false, sim = SimKey(3, "26202", 2397), simName = "LIDL Connect",
        profileId = profile, releaseTag = "main e32fd75687d0 (2026-10-24)",
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
        assertEquals("""{"commit":"$commit","data_digest":"$digest"}""", report.getValue("release").toString())
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
        // A bundled snapshot names the commit it was built from.
        val bundledCommit = "8cb64d63d9a482a40f43a10b37744241f9526e0e"
        val bundled = IssueReport.report(s20Run(), cell.copy(dataSource = "bundled", dataCommit = bundledCommit), calls)!!
        assertEquals("""{"commit":"$bundledCommit","data_digest":"$digest"}""", bundled.getValue("release").toString())
        assertEquals(emptyList<String>(), SchemaSubset.problems(bundled, schema))
    }

    @Test
    fun `a run recorded before round 29 still names its release, which the public schema still takes`() {
        val old = IssueReport.report(s20Run(), cell.copy(dataCommit = null, dataRelease = "data-2026.10"), calls)!!
        assertEquals("""{"tag":"data-2026.10","data_digest":"$digest"}""", old.getValue("release").toString())
        assertEquals(emptyList<String>(), SchemaSubset.problems(old, schema))
        // A bundled snapshot of then named no tag; the bot finds the release by its digest.
        val bundled = IssueReport.report(s20Run(), cell.copy(dataCommit = null, dataRelease = null, dataSource = "bundled"), calls)!!
        assertEquals(JsonNull, bundled.getValue("release").jsonObject["tag"])
        assertEquals(emptyList<String>(), SchemaSubset.problems(bundled, schema))
        // Something that is no full commit is never written as one.
        val short = IssueReport.report(s20Run(), cell.copy(dataCommit = commit.take(12), dataRelease = null), calls)!!
        assertNull(short.getValue("release").jsonObject["commit"])
        // The schema takes one form or the other, never both, and no commit of another shape.
        fun withRelease(release: String) = JsonObject(old + ("release" to Json.parseToJsonElement(release)))
        assertTrue(SchemaSubset.problems(withRelease("""{"commit":"$commit","tag":"data-2026.10","data_digest":"$digest"}"""), schema).any { "release" in it })
        assertTrue(SchemaSubset.problems(withRelease("""{"commit":"main","data_digest":"$digest"}"""), schema).any { "release" in it })
        assertTrue(SchemaSubset.problems(withRelease("""{"commit":"$commit"}"""), schema).any { "release" in it })
    }

    @Test
    fun `the IMS stack is named from the installed ImsService, and the public schema takes every name`() {
        assertEquals("phh_ims", ImsStack.of(listOf("me.phh.ims")))
        assertEquals("samsung_ims", ImsStack.of(listOf("com.sec.imsservice")))
        // The S20 on LineageOS 24.0 with Google's AOSP ImsStack (2026-10-22).
        assertEquals("aosp_imsstack", ImsStack.of(listOf("com.android.imsstack")))
        assertEquals("aosp_imsstack", ImsStack.of(listOf("com.android.imsstack", "com.example.ims")))
        assertEquals("other", ImsStack.of(listOf("com.android.imsstack.test")))
        assertEquals("other", ImsStack.of(listOf("com.example.ims")))
        assertEquals("not_known", ImsStack.of(emptyList()))
        val stacks = schema.getValue("properties").jsonObject.getValue("device").jsonObject.getValue("properties").jsonObject
            .getValue("ims_stack").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("phh_ims", "samsung_ims", "aosp_imsstack", "other", "not_known"), stacks)
        for (stack in stacks) {
            val report = IssueReport.report(s20Run(), cell.copy(imsStack = stack), calls)!!
            assertEquals(stack, report.getValue("device").jsonObject.getValue("ims_stack").jsonPrimitive.content)
            assertEquals(stack, emptyList<String>(), SchemaSubset.problems(report, schema))
        }
        assertTrue(SchemaSubset.problems(IssueReport.report(s20Run(), cell.copy(imsStack = "imsstack"), calls)!!, schema).any { "ims_stack" in it })
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
