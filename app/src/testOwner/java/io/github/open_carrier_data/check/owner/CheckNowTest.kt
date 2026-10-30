package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.QuietBlock
import io.github.open_carrier_data.check.owner.core.QuietInputs
import io.github.open_carrier_data.check.owner.core.QuietWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD round 42, follow-up fix of 2026-10-30 (the S20 test of 0.4.7, finding 4): "Check without changing
 * anything" did nothing while the screen was on, and the button can only be tapped with the screen on.
 * A tap now runs the check at once; the screen-off condition stays for background runs only, and a
 * call still stops both.
 */
class CheckNowTest {
    /** The owner, screen on, at 18:30, using the phone. */
    private val awake = QuietInputs(
        interactive = true, screenOffForMs = 0, plugged = false, batteryPercent = 60, inCall = false, audioIdle = true,
        roaming = false, wifiConnected = true, trafficBytesPerMinute = 200_000, nextAlarmInMs = null, minuteOfDay = 18 * 60 + 30,
    )

    @Test
    fun `a tap runs at once with the screen on, a background run still waits for the screen off`() {
        assertNull(QuietWindow.checkBlocker(awake, ownerTap = true))
        assertEquals(QuietBlock.SCREEN_ON, QuietWindow.checkBlocker(awake, ownerTap = false))
        assertEquals("the default is the background rule", QuietBlock.SCREEN_ON, QuietWindow.checkBlocker(awake))
        assertEquals(QuietBlock.SCREEN_ON, QuietWindow.checkBlocker(awake.copy(interactive = null)))
        assertNull(QuietWindow.checkBlocker(awake.copy(interactive = false)))
    }

    @Test
    fun `a call stops a tap too, and an unreadable call state counts as a call`() {
        assertEquals(QuietBlock.IN_CALL, QuietWindow.checkBlocker(awake.copy(inCall = true), ownerTap = true))
        assertEquals(QuietBlock.IN_CALL, QuietWindow.checkBlocker(awake.copy(inCall = null), ownerTap = true))
    }

    @Test
    fun `a tap with the screen on, or with automatic tests off, is the check only`() {
        assertTrue(QuietWindow.checkOnly(ownerTap = true, i = awake, scheduleOn = true))
        assertTrue(QuietWindow.checkOnly(ownerTap = true, i = awake.copy(interactive = false), scheduleOn = false))
        // A tap whose run starts with the screen already off, automatic tests on: the full light check, as before.
        assertFalse(QuietWindow.checkOnly(ownerTap = true, i = awake.copy(interactive = false), scheduleOn = true))
        // A background run is never "check only".
        assertFalse(QuietWindow.checkOnly(ownerTap = false, i = awake.copy(interactive = false), scheduleOn = true))
    }

    @Test
    fun `the quiet job lets the tap through and stops it before the dataset step, the emergency data test and the tests`() {
        val work = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerWork.kt").readText()
        assertTrue(work.contains("if ((!state.scheduleOn && !tap) || OwnerScheduler.runActive(context)) return Result.success()"))
        assertTrue(work.contains("QuietWindow.checkBlocker(look, ownerTap = tap)"))
        val quiet = work.substring(work.indexOf("private suspend fun quiet("), work.indexOf("private suspend fun callsOutDue("))
        val stop = quiet.indexOf("if (checkOnly) {")
        assertTrue(stop > 0)
        // The read and the diagnosis come first; everything that may change a value comes after the stop.
        assertTrue(quiet.indexOf("diagnoseCauses(store, phone, sims)") in 0 until stop)
        for (later in listOf("OwnerDatasetStep.run(context, store, phone, sims)", "OwnerEmergencyData.session(", "OwnerScheduler.runPlanned(", "OwnerScheduler.runCallsOut(")) {
            assertTrue(later, quiet.indexOf(later) > stop)
        }
        // The revert of a provisional keep and the leftover cleanup are skipped on a check-only tap.
        assertTrue(quiet.contains("if (!checkOnly) {\n                if (checks.any { it.missingKeeps.isNotEmpty() })"))
        // The check now button still reaches the quiet job (no "periodic" flag: a tap).
        assertTrue(work.contains("\"owner-check-now\", ExistingWorkPolicy.REPLACE"))
    }
}
