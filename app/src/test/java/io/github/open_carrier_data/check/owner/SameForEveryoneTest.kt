package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ReportWords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * OCD rule round 47, item 2: no token at all, and results reach OCD the same way for everyone: the person's tap on the
 * prefilled closed report, under their own GitHub login. Round 48 adds the week report after a yes, with no credential either.
 */
class SameForEveryoneTest {
    private val ownerRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val sources = File(ownerRoot, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `the token path is gone from the code`() {
        val gone = Regex("\\b(OwnerToken|TokenForm|AutoReport|RecordReport|IssuePoster|GitHubIssues|TokenRow|saveToken|removeToken|sendsWithToken|STEP_AUTO_REPORT|pendingRecords|recordsSent|pendingReviews|reviewsSent|SourceReview|ownerFirstUsePassed|OwnerCertificate)\\b")
        for (file in sources) {
            val hit = gone.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
        assertFalse(File(ownerRoot, "java/io/github/open_carrier_data/check/owner/core/AutoReport.kt").exists())
    }

    @Test
    fun `a state file of an older build still reads, and its token path keys are dropped`() {
        val old = JsonObject(
            Json.parseToJsonElement(OwnerState(scheduleOn = false).encode()).jsonObject + mapOf<String, JsonElement>(
                "autoSent" to Json.parseToJsonElement("""[{"key":"SM-G981B|volte:on","atMs":1,"issue":301}]"""),
                "autoLine" to JsonPrimitive("Sent #301 on 2026-10-24"),
                "autoRefusedAtMs" to JsonPrimitive(1),
                "pendingRecords" to JsonArray(listOf(JsonPrimitive("{}"))),
                "recordsSent" to Json.parseToJsonElement("""[{"recordKey":"r:0123456789abcdef","lastMeasured":"2026-10-26","result":"works","atMs":1}]"""),
                "pendingReviews" to Json.parseToJsonElement("""[{"key":"k","title":"t","body":"b","atMs":1}]"""),
                "reviewsSent" to JsonArray(listOf(JsonPrimitive("k"))),
            ),
        ).toString()
        val state = OwnerState.decode(old)
        assertFalse(state.scheduleOn)
        for (key in listOf("autoSent", "autoLine", "autoRefusedAtMs", "pendingRecords", "recordsSent", "pendingReviews", "reviewsSent")) {
            assertFalse(key, state.encode().contains("\"$key\""))
        }
    }

    @Test
    fun `a report token stored by an older build is deleted, unread`() {
        val dir = Files.createTempDirectory("no_backup").toFile()
        val token = File(dir, "report-token.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val other = File(dir, "other.bin").apply { writeBytes(byteArrayOf(4)) }
        OwnerAutoReports.forgetOldToken(dir)
        assertFalse(token.exists())
        assertTrue(other.exists())
        // A phone that never held one: nothing happens.
        OwnerAutoReports.forgetOldToken(dir)
    }

    @Test
    fun `the words say what stays on the phone, how to send a report, and what the person publishes`() {
        // Round 48: a yes to the weekly summary sends by itself, so the section no longer says nothing does.
        assertEquals("Reports you send yourself", ReportWords.SECTION_TITLE)
        assertTrue(ReportWords.SECTION_TEXT, "Apart from the weekly summary, if you said yes to it, results stay on this phone" in ReportWords.SECTION_TEXT)
        assertTrue(ReportWords.SECTION_TEXT, "tap ${ReportWords.BUTTON}" in ReportWords.SECTION_TEXT)
        assertTrue(ReportWords.SECTION_TEXT, "a public issue under your own GitHub account" in ReportWords.SECTION_TEXT)
        assertTrue(ReportWords.SECTION_TEXT, "Without a GitHub account you can read the report but not send it" in ReportWords.SECTION_TEXT)
        for (needed in listOf("nothing is sent until you submit it", "The issue is public, under your own GitHub account", "never holds your login", "older versions stay in git history", "reported, not checked by OCD")) {
            assertTrue(needed, needed in ReportWords.FORM_TEXT)
        }
        // The same words on OCD's public form, above the prefilled report.
        val form = requireNotNull(javaClass.getResourceAsStream("/public/ocd-check-report.yml")).use { it.readBytes().decodeToString() }
        assertTrue("This issue is public, under your own GitHub account" in form)
        assertTrue("older versions stay in git history" in form)
    }

    @Test
    fun `every build shows the same Reports section, and the report before the browser`() {
        val screen = File(ownerRoot, "java/io/github/open_carrier_data/check/owner/OwnerScreen.kt").readText()
        val section = screen.substringAfter("SectionTitle(ReportWords.SECTION)").substringBefore("Spacer(")
        assertFalse("no build branch in the section", Regex("\\bowner\\b").containsMatchIn(section.substringBefore("ui.reportDraft")))
        assertTrue(section, "ReportWords.SECTION_TITLE" in section && "ReportWords.SECTION_TEXT" in section && "ReportWords.BUTTON" in section)
        val draft = screen.substringAfter("private fun ReportDraft(")
        assertTrue("ReportWords.FORM_TEXT" in draft && "draft.text" in draft)
        // The browser opens only from the report the person read, and only the person's tap reaches it.
        val activity = File(ownerRoot, "java/io/github/open_carrier_data/check/owner/OwnerActivity.kt").readText()
        assertEquals(1, Regex("Uri\\.parse\\(prefilled\\.url\\)").findAll(activity).count())
        assertEquals(1, Regex("actions\\.openReport\\(").findAll(screen).count())
    }
}
