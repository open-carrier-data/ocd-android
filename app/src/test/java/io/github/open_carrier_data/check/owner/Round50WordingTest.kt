package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.WeekQuestion
import io.github.open_carrier_data.check.owner.core.WeekWords
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 50, item 1. "No call details" was false: a result shows that a phone had calls that week, on mobile or
 * Wi-Fi, whether they worked, and that one was outgoing. A yes given to words a report contradicts may not be a valid
 * yes, so the old words must not come back in anything the app holds or says.
 */
class Round50WordingTest {
    private val oldWords = "no call details"
    private val sentence = "It sends no name, phone numbers or SIM ID, no location, no day or time, and no call lengths or counts. " +
        "But a result can show that this phone had calls that week, on mobile or Wi-Fi, whether they worked, and that one was outgoing."
    private val ruleText = "no day or time and no phone numbers, call lengths or counts, though a result can show that calls happened that week, " +
        "on mobile or Wi-Fi, whether they worked, and that one was outgoing"

    /** A source file as one line: a wrapped comment or a joined Kotlin string must not hide the words. */
    private fun flat(file: File) = file.readText().replace(Regex("\"\\s*\\+\\s*\""), "").replace(Regex("\\s*\\n\\s*(\\*\\s+)?"), " ")

    private val sources: List<File> = File("src/main").walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json", "txt") }
        .toList() + File("../README.md")

    @Test
    fun `no text of the app says no call details`() {
        assertTrue("the walk found the sources", sources.size > 50 && sources.any { it.name == "WeekReport.kt" })
        assertEquals(emptyList<String>(), sources.filter { flat(it).contains(oldWords, ignoreCase = true) }.map { it.path })
        for (words in listOf(WeekQuestion.BODY, WeekQuestion.MEANS, WeekQuestion.DETAILS_TEXT, WeekWords.MEANS, WeekWords.ON, WeekWords.OFF)) {
            assertFalse(words, words.contains(oldWords, ignoreCase = true))
        }
    }

    @Test
    fun `the question holds the new sentence and the rest of it stays`() {
        assertTrue(sentence in WeekQuestion.BODY)
        assertFalse("no times" in WeekQuestion.BODY)
        assertTrue(WeekQuestion.BODY.startsWith("Once a week, on Wi-Fi and never while roaming"))
        assertTrue(WeekQuestion.BODY.endsWith("You can stop any time in Settings > About phone > Open Carrier Data."))
    }

    @Test
    fun `every copy of the rule says the same`() {
        for (path in listOf("src/main/java/io/github/open_carrier_data/check/owner/core/WeekReport.kt", "src/main/assets/ocd-week-report.schema.json", "../README.md")) {
            assertTrue(path, ruleText in flat(File(path)))
        }
    }

    @Test
    fun `failed stands beside works and calls out, and bearer is named as a field`() {
        val named = "and a works, calls out or failed result shows that calls happened that week."
        assertTrue(WeekQuestion.MEANS.endsWith(named))
        assertTrue(named in WeekQuestion.DETAILS_TEXT)
        assertTrue(named in WeekWords.MEANS)
        assertTrue("It never holds a plan, a bearer field, codename," in flat(File("src/main/java/io/github/open_carrier_data/check/owner/core/WeekReport.kt")))
    }
}
