package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LastResult
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SettingsSummary
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.retrying
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings entry's summary and the retried service start. */
class BackgroundTest {
    private val now = 1_792_440_000_000L
    private val day = 24 * 3_600_000L
    private val format: (Long) -> String = { "T$it" }

    @Test
    fun `the Settings summary is the screen's status sentence`() {
        val sim = OwnerFixtures.SIM.key
        val view = SimView(0, sim.subId, sim.mccmnc, "LIDL Connect")
        val kept = LiveOverride(0, sim, "carrier_volte_available_bool", true, Hold.KEPT, now)
        val run = RunResult("r1", startedMs = now, scheduled = false)
        assertEquals("Not checked yet", SettingsSummary.line(OwnerState(), privileged = true, sims = listOf(view)))
        assertEquals("Open Carrier Data switched on VoLTE", SettingsSummary.line(OwnerState(overrides = listOf(kept), runs = listOf(run)), privileged = true, sims = listOf(view)))
        // A light check that found nothing to change: everything works as the phone has it.
        val checked = OwnerState(runs = listOf(run), lastResult = LastResult(now + day, "checked, nothing changed"))
        assertEquals("All signed in on your phone's own settings", SettingsSummary.line(checked, privileged = true, sims = listOf(view)))
        assertEquals(
            "Testing. Can't change settings",
            SettingsSummary.line(OwnerState(overrides = listOf(kept.copy(hold = Hold.TRIAL))), privileged = false, sims = listOf(view)),
        )
        assertTrue("paused" in SettingsSummary.line(OwnerState(stop = "revert_not_proven"), privileged = true, sims = listOf(view)))
        assertTrue("·" !in SettingsSummary.line(checked, privileged = false, sims = listOf(view)))
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
