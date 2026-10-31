package io.github.open_carrier_data.check.rom

import io.github.open_carrier_data.check.owner.OwnerBalance
import io.github.open_carrier_data.check.owner.OwnerProbe
import io.github.open_carrier_data.check.owner.OwnerTester
import io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 31 (SM9): the build other ROMs ship starts the round 29 setting trials (a non-persistent
 * override, a temporary access point row, the restart check's override) only after the person's opt-in. It
 * ran them with no opt-in on a phone with no dataset installer. The owner build's case, and the places the
 * gate sits, are in src/testOwner (TrialGateTest there).
 */
class TrialGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    @Test
    fun `without the opt-in the rom build starts no setting trial`() {
        assertEquals(TesterBuild.ROM, OwnerTester.build)
        assertFalse(OwnerTester.trialsAllowed(OwnerState()))
        // Turned on and off again: the opt-in is the switch, not the time it was set.
        assertFalse(OwnerTester.trialsAllowed(OwnerState(activeTests = ActiveTestsOptIn(on = false, atMs = 1_761_800_000_000L))))
    }

    @Test
    fun `with the opt-in the rom build starts them`() {
        assertTrue(OwnerTester.trialsAllowed(optedIn))
    }

    @Test
    fun `the trials follow the same opt-in as the probe ladder and the balance read`() {
        for (state in listOf(OwnerState(), optedIn)) {
            assertEquals(OwnerProbe.allowed(state), OwnerTester.trialsAllowed(state))
            assertEquals(OwnerBalance.allowed(state.copy(balanceUssdOn = true)), OwnerTester.trialsAllowed(state))
        }
    }
}
