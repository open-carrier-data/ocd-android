package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.HoldMark
import io.github.open_carrier_data.check.owner.core.HoldNotices
import io.github.open_carrier_data.check.owner.core.Notices
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.VoiceLossState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 48, item 1: no icon on a ROM, the entry last on About phone, and a hold notice a swipe cannot lose.
 * Hiding goes no further than the launcher alias: the app stays in Settings > Apps, where disabling it is the way back.
 */
class Round48EntryTest {
    private val root = "src/main/java/io/github/open_carrier_data/check"
    private val code = File(root).walkTopDown().filter { it.extension == "kt" }.toList()
    private val day = HoldNotices.EVERY_MS
    private val now = 1_800_000_000_000L
    private fun mark(atMs: Long) = HoldMark("Calls could not go out", "Tap Resume", atMs)

    @Test
    fun `a hold notice comes back at most once a day until the hold ends`() {
        val stopped = OwnerState(stop = "real_call_hold", holdNotice = mark(now))
        assertFalse(HoldNotices.due(stopped, now))
        assertFalse(HoldNotices.due(stopped, now + day - 1))
        assertTrue(HoldNotices.due(stopped, now + day))
        // No hold, nothing comes back, however old the mark.
        assertFalse(HoldNotices.due(OwnerState(holdNotice = mark(now)), now + 10 * day))
        // A clock that went back does not hold the notice up.
        assertTrue(HoldNotices.due(stopped, now - 1))
    }

    @Test
    fun `a stop from a build without the mark gets its notice, in plain words that lead to the page`() {
        val state = OwnerState(stop = "voice_loss_stop")
        assertTrue(HoldNotices.due(state, now))
        val notice = HoldNotices.again(state)
        assertTrue(notice.hold)
        assertTrue(notice.text, "Settings > About phone > Open Carrier Data" in notice.text)
    }

    @Test
    fun `the notice that comes back keeps the words of the one that was swiped`() {
        val notice = HoldNotices.again(OwnerState(stop = "real_call_hold", holdNotice = mark(now)))
        assertEquals("Calls could not go out", notice.title)
        assertEquals("Tap Resume", notice.text)
    }

    @Test
    fun `a voice-loss hold without a stop waits too, since the split needs a tap`() {
        val held = OwnerState(datasetStatus = DatasetStatus(available = true, voiceLoss = VoiceLossState(hold = true)))
        assertTrue(HoldNotices.waiting(held))
        assertFalse(HoldNotices.waiting(OwnerState()))
    }

    @Test
    fun `the generic pause line is a hold notice, and an old state file still reads`() {
        assertTrue(Notices.of(OwnerState(), OwnerState(stop = "revert_not_proven"))!!.hold)
        assertEquals(mark(now), OwnerState.decode(OwnerState(holdNotice = mark(now)).encode()).holdNotice)
        assertEquals(null, OwnerState.decode("{}").holdNotice)
    }

    @Test
    fun `every notice that asks for Resume is a hold notice, on its own ID`() {
        val notices = code.flatMap { f -> f.readLines().filter { "OwnerNotifications.notice(" in it && "Notice(\"" in it }.map { f.name to it } }
        val resume = notices.filter { (_, line) -> "Resume" in line || "paused" in line }
        assertTrue(resume.size >= 8)
        for ((file, line) in resume) assertTrue("$file: $line", "hold = true" in line)
        val receivers = File("$root/owner/OwnerReceivers.kt").readText()
        assertTrue(receivers.contains("post(context, HOLD_ID, notice)"))
        assertTrue(receivers.contains("post(context, EVENT_ID, notice)"))
        assertFalse("Android lets people dismiss those too", receivers.contains("setOngoing"))
    }

    @Test
    fun `Resume takes the notice away at once`() {
        val activity = File("$root/owner/OwnerActivity.kt").readText()
        assertTrue(activity.substringAfter("fun clearStop()").substringBefore("say(\"Changes resumed\")").contains("OwnerNotifications.refresh("))
    }

    @Test
    fun `only the launcher alias is turned off, and nothing hides the app itself`() {
        val text = code.joinToString("\n") { it.readText() }
        assertEquals(1, Regex("setComponentEnabledSetting\\(").findAll(text).count())
        val hide = File("$root/OwnerHook.kt").readText().substringAfter("fun hideLauncher(").substringBefore("/** A card")
        assertTrue(hide.contains("\${context.packageName}.ui.Launcher"))
        for (never in listOf("setApplicationEnabledSetting", "setApplicationHidden", "COMPONENT_ENABLED_STATE_DISABLED_USER")) assertFalse(never, never in text)
        assertTrue(hide.contains("COMPONENT_ENABLED_STATE_DISABLED"))
    }
}
