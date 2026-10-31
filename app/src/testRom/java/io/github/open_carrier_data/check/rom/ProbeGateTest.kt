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
 * OCD rule round 42, item 2: the build other ROMs ship runs the probe ladder (D1-D5) and W1 only after the
 * person's SM9 opt-in. 0.4.12 ran both with no opt-in. The owner build's case is in src/testOwner (ProbeGateTest there).
 */
class ProbeGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    @Test
    fun `without the opt-in the rom build runs neither the ladder nor W1`() {
        assertEquals(TesterBuild.ROM, OwnerTester.build)
        assertFalse(OwnerProbe.allowed(OwnerState()))
        // Turned on and off again: the opt-in is the switch, not the time it was set.
        assertFalse(OwnerProbe.allowed(OwnerState(activeTests = ActiveTestsOptIn(on = false, atMs = 1_761_800_000_000L))))
    }

    @Test
    fun `with the opt-in the rom build runs both`() {
        assertTrue(OwnerProbe.allowed(optedIn))
    }

    @Test
    fun `the ladder follows the same opt-in as the balance read`() {
        for (state in listOf(OwnerState(), optedIn)) {
            assertEquals(OwnerBalance.allowed(state.copy(balanceUssdOn = true)), OwnerProbe.allowed(state))
        }
    }
}
