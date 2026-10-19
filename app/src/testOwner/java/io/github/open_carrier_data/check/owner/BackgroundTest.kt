package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SettingsSummary
import io.github.open_carrier_data.check.owner.core.WeeklySchedule
import io.github.open_carrier_data.check.owner.core.retrying
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background schedule, the Settings entry's summary and the retried service start. */
class BackgroundTest {
    private val now = 1_792_440_000_000L
    private val day = 24 * 3_600_000L
    private val format: (Long) -> String = { "T$it" }

    @Test
    fun `the first weekly run comes a week after the last test, never at once`() {
        assertEquals(WeeklySchedule.PERIOD_MS, WeeklySchedule.initialDelayMs(null, now))
        assertEquals(5 * day, WeeklySchedule.initialDelayMs(now - 2 * day, now))
        assertEquals(0L, WeeklySchedule.initialDelayMs(now - 9 * day, now))
        assertEquals(WeeklySchedule.PERIOD_MS, WeeklySchedule.initialDelayMs(now + day, now))
    }

    @Test
    fun `the schedule line never shows a time in the past`() {
        // The first owner run (2026-10-19) showed "next run after 9:27 PM", the time it was registered.
        val due = WeeklySchedule.line(on = true, registered = true, nextMs = now - 3_600_000, nowMs = now, format = format)
        assertTrue(due, "due now" in due)
        assertTrue(due, "T${now - 3_600_000}" !in due)
        assertEquals(
            "on; next run from T${now + day}, when the phone is idle, charging and on Wi-Fi",
            WeeklySchedule.line(on = true, registered = true, nextMs = now + day, nowMs = now, format = format),
        )
        assertEquals("off", WeeklySchedule.line(on = false, registered = false, nextMs = null, nowMs = now, format = format))
        assertTrue("not registered" in WeeklySchedule.line(on = true, registered = false, nextMs = null, nowMs = now, format = format))
    }

    @Test
    fun `the Settings summary says what is kept, the last test and missing access`() {
        val sim = OwnerFixtures.SIM.key
        val kept = LiveOverride(0, sim, "carrier_volte_available_bool", true, Hold.KEPT, now)
        val run = RunResult("r1", startedMs = now, scheduled = false)
        assertEquals("Phone's own carrier settings · not tested yet", SettingsSummary.line(OwnerState(), privileged = true, format = format))
        assertEquals("1 kept · last test T$now", SettingsSummary.line(OwnerState(overrides = listOf(kept), runs = listOf(run)), privileged = true, format = format))
        assertEquals(
            "Test running · not tested yet · no privileged access",
            SettingsSummary.line(OwnerState(wifiOffSinceMs = now), privileged = false, format = format),
        )
        assertTrue("Stopped" in SettingsSummary.line(OwnerState(stop = "revert_not_proven"), privileged = true, format = format))
    }

    @Test
    fun `a failed start is tried again after a pause, up to the limit`() = runBlocking {
        val pauses = mutableListOf<Int>()
        val third = retrying(3, pause = { pauses += it }) { attempt -> if (attempt == 3) "bound" else null }
        assertEquals("bound", third)
        assertEquals(listOf(1, 2), pauses)
        pauses.clear()
        assertNull(retrying(3, pause = { pauses += it }) { null as String? })
        assertEquals(listOf(1, 2), pauses)
    }
}
