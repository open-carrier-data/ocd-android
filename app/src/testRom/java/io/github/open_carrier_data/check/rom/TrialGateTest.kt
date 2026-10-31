package io.github.open_carrier_data.check.rom

import io.github.open_carrier_data.check.owner.OwnerBalance
import io.github.open_carrier_data.check.owner.OwnerProbe
import io.github.open_carrier_data.check.owner.OwnerTester
import io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.StackCalibration
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OCD rule round 31 (SM9): the build other ROMs ship starts the round 29 setting trials (a non-persistent
 * override, a temporary access point row, the restart check's override) only after the person's opt-in. It
 * ran them with no opt-in on a phone with no dataset installer. SM8 (tests calibrated per IMS stack) is the
 * second half of the same gate: even with the opt-in, no trial starts on PHH IMS, a vendor IMS or a stack the
 * phone does not show, where the round 30 and 31 paths count nothing either. The owner build's case, and the
 * places the gate sits, are in src/testOwner (TrialGateTest there).
 */
class TrialGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    private val calibrated = "aosp_imsstack"

    @Test
    fun `without the opt-in the rom build starts no setting trial`() {
        assertEquals(TesterBuild.ROM, OwnerTester.build)
        assertEquals(TesterPolicy.OPT_IN_OFF, OwnerTester.policy(OwnerState()).trialRefusal(calibrated)?.code)
        // Turned on and off again: the opt-in is the switch, not the time it was set.
        val off = OwnerState(activeTests = ActiveTestsOptIn(on = false, atMs = 1_761_800_000_000L))
        assertEquals(TesterPolicy.OPT_IN_OFF, OwnerTester.policy(off).trialRefusal(calibrated)?.code)
    }

    @Test
    fun `with the opt-in the rom build starts them on the calibrated stack`() {
        assertNull(OwnerTester.policy(optedIn).trialRefusal(calibrated))
    }

    @Test
    fun `SM8 - with the opt-in the rom build starts none on a stack the tests are not calibrated on`() {
        for (stack in listOf("phh_ims", "samsung_ims", "other", "not_known", "", null)) {
            val refusal = OwnerTester.policy(optedIn).trialRefusal(stack)
            assertNotNull(stack, refusal)
            assertEquals(TesterPolicy.NOT_CALIBRATED, refusal!!.code)
            assertEquals(StackCalibration.notCounted(stack), refusal.plain)
            // Without the opt-in the stack is still the reason given: turning the opt-in on would not start them.
            assertEquals(TesterPolicy.NOT_CALIBRATED, OwnerTester.policy(OwnerState()).trialRefusal(stack)?.code)
        }
    }

    @Test
    fun `on the calibrated stack the trials follow the same opt-in as the probe ladder and the balance read`() {
        for (state in listOf(OwnerState(), optedIn)) {
            val allowed = OwnerTester.policy(state).trialRefusal(calibrated) == null
            assertEquals(OwnerProbe.allowed(state), allowed)
            assertEquals(OwnerBalance.allowed(state.copy(balanceUssdOn = true)), allowed)
        }
    }
}
