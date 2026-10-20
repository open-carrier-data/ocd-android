package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LastResult
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SettingsSummary
import io.github.open_carrier_data.check.owner.core.QuietSchedule
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
    fun `the schedule line says what runs when, and the two nights of on values only`() {
        val state = OwnerState(lastCheckAtMs = now - 3_600_000, onOnlyUntilMs = now + day)
        val line = QuietSchedule.line(on = true, registered = true, state = state, nowMs = now, format = format)
        assertTrue(line, "trials at night (02:00 to 05:00)" in line)
        assertTrue(line, "last check T${now - 3_600_000}" in line)
        assertTrue(line, "only on values until T${now + day}" in line)
        assertEquals("off", QuietSchedule.line(on = false, registered = false, state = OwnerState(), nowMs = now, format = format))
        assertTrue("not registered" in QuietSchedule.line(on = true, registered = false, state = OwnerState(), nowMs = now, format = format))
    }

    @Test
    fun `the Settings summary says what is kept, the last result and missing access`() {
        val sim = OwnerFixtures.SIM.key
        val kept = LiveOverride(0, sim, "carrier_volte_available_bool", true, Hold.KEPT, now)
        val run = RunResult("r1", startedMs = now, scheduled = false)
        assertEquals("Phone's own carrier settings · not tested yet", SettingsSummary.line(OwnerState(), privileged = true, format = format))
        assertEquals("1 kept · last test T$now", SettingsSummary.line(OwnerState(overrides = listOf(kept), runs = listOf(run)), privileged = true, format = format))
        // The last result wins over the run date: a light check, a night test.
        val checked = OwnerState(runs = listOf(run), lastResult = LastResult(now + day, "checked, nothing changed"))
        assertEquals("Phone's own carrier settings · T${now + day}: checked, nothing changed", SettingsSummary.line(checked, privileged = true, format = format))
        assertEquals(
            "Test running · not tested yet · no privileged access",
            SettingsSummary.line(OwnerState(overrides = listOf(kept.copy(hold = Hold.TRIAL))), privileged = false, format = format),
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
