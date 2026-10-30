package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCD round 42, items 1/3/7 on the owner build: the diagnosis assembler over the passive read. */
class DiagnosisTest {
    @Test
    fun `a data fail cause names the data verdict`() {
        val d = Diagnoses.of(DiagnosisInputs(defaultDataCause = 33))
        val data = d.verdicts.single { it.feature == VerdictFeature.MOBILE_DATA }
        assertEquals("Refused for mobile data, probably the tariff (code 33)", data.line)
        assertEquals(CauseClass.NOT_BOOKED, data.cause)
    }

    @Test
    fun `the LIDL case names an account block from two signals`() {
        // The probe ladder's account-block signature plus a zero balance.
        val d = Diagnoses.of(
            DiagnosisInputs(
                userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE,
                account = AccountState.BLOCKED,
                balance = "0.10 EUR",
            ),
        )
        val data = d.verdicts.single { it.feature == VerdictFeature.MOBILE_DATA }
        assertEquals("Mobile data blocked by the account: balance 0.10 EUR", data.line)
        assertTrue(data.confident)
        assertTrue(d.notCounted())
    }

    @Test
    fun `a broken private DNS is read as phone-side`() {
        val d = Diagnoses.of(DiagnosisInputs(privateDnsBroken = true))
        assertEquals(
            "Phone-side: the Private DNS server can't be reached on mobile data",
            d.verdicts.single { it.feature == VerdictFeature.MOBILE_DATA }.line,
        )
        // Phone-side is never "not counted": the failure is the phone's, and it is not an account block.
        assertFalse(d.notCounted())
    }

    @Test
    fun `an IWLAN device code is read for Wi-Fi calling only while IMS is not registered`() {
        val off = Diagnoses.of(DiagnosisInputs(iwlanDataCause = 9006, imsRegistered = false))
        assertEquals(
            "Device not allowed by the network (code 9006)",
            off.verdicts.single { it.feature == VerdictFeature.VOWIFI }.line,
        )
        // Registered: no Wi-Fi-calling refusal is read.
        val on = Diagnoses.of(DiagnosisInputs(iwlanDataCause = 9006, imsRegistered = true))
        assertTrue(on.verdicts.none { it.feature == VerdictFeature.VOWIFI })
    }

    @Test
    fun `a SIP 403 names VoLTE as probably the tariff`() {
        val d = Diagnoses.of(DiagnosisInputs(sipResponseCode = 403, imsRegistered = false))
        assertEquals(
            "Refused for this SIM, probably the tariff",
            d.verdicts.single { it.feature == VerdictFeature.VOLTE }.line,
        )
    }

    @Test
    fun `mms ended is its own line`() {
        val d = Diagnoses.of(DiagnosisInputs(mmsEnded = true))
        assertEquals("MMS ended on this network", d.verdicts.single { it.feature == VerdictFeature.MMS }.line)
    }

    @Test
    fun `a clean read gives no verdict and does not blame OCD`() {
        val d = Diagnoses.of(DiagnosisInputs())
        assertNull(d.line)
        assertFalse(d.notCounted())
    }

    @Test
    fun `a definite temporary data cause does not count against OCD`() {
        val d = Diagnoses.of(DiagnosisInputs(defaultDataCause = 38))
        assertEquals("Temporary network fault (code 38)", d.line)
        assertTrue(d.notCounted())
    }
}
