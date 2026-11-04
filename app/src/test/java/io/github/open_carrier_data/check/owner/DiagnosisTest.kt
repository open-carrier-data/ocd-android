package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.EpdgLookup
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
        // Round 44: a live #33 with no matching refused setup in the log does not tell the row, so both readings.
        val d = Diagnoses.of(DiagnosisInputs(defaultDataCause = 33))
        val data = d.verdicts.single { it.feature == VerdictFeature.MOBILE_DATA }
        assertEquals("Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM", data.line)
        assertEquals(CauseClass.AMBIGUOUS, data.cause)
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
            "Refused for VoLTE, probably the tariff",
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

    @Test
    fun `a working connection's cause 0 is no measured failure, so nothing automatic runs`() {
        // PreciseDataConnectionState.getLastCauseCode() is 0 (DataFailCause.NONE) on a working connection.
        assertFalse(DiagnosisInputs(defaultDataCause = 0).measuredDataFailure)
        assertFalse(DiagnosisInputs().measuredDataFailure)
        assertTrue(DiagnosisInputs(defaultDataCause = 33).measuredDataFailure)
        assertTrue(DiagnosisInputs(defaultDataCause = 0, rejectCause = 7).measuredDataFailure)
        // And cause 0 names no verdict.
        assertNull(Diagnoses.of(DiagnosisInputs(defaultDataCause = 0)).line)
    }

    private val wifiDns = "Wi-Fi side: this Wi-Fi's DNS gives no address for the carrier's Wi-Fi calling server; the mobile network's DNS does"

    @Test
    fun `W1's comparison names the Wi-Fi side in the Wi-Fi-calling verdict`() {
        val d = Diagnoses.of(DiagnosisInputs(imsRegistered = false, epdgWifi = EpdgLookup.NO_ADDRESS, epdgCellular = EpdgLookup.RESOLVES))
        val wfc = d.verdicts.single { it.feature == VerdictFeature.VOWIFI }
        assertEquals(wifiDns, wfc.line)
        assertEquals(CauseClass.PHONE_SIDE, wfc.cause)
        // It is about Wi-Fi calling only: no VoLTE or mobile-data verdict, and it never sets a test aside.
        assertTrue(d.verdicts.none { it.feature == VerdictFeature.VOLTE || it.feature == VerdictFeature.MOBILE_DATA })
        assertFalse(d.notCounted())
    }

    @Test
    fun `W1 sharpens a phone-side IWLAN code, never displaces a refusal code, and comes before the SIP class`() {
        // IWLAN_DNS_RESOLUTION_NAME_FAILURE (0x4004) is phone-side; W1 says which side.
        val dns = Diagnoses.of(DiagnosisInputs(imsRegistered = false, iwlanDataCause = 0x4004, epdgWifi = EpdgLookup.FAILS, epdgCellular = EpdgLookup.RESOLVES))
        assertEquals(
            "Wi-Fi side: this Wi-Fi's DNS does not answer for the carrier's Wi-Fi calling server; the mobile network's DNS does",
            dns.verdicts.single { it.feature == VerdictFeature.VOWIFI }.line,
        )
        // A refusal code (9003, barred) stays the cause.
        val barred = Diagnoses.of(DiagnosisInputs(imsRegistered = false, iwlanDataCause = 9003, epdgWifi = EpdgLookup.NO_ADDRESS, epdgCellular = EpdgLookup.RESOLVES))
        assertEquals(CauseClass.BARRED, barred.verdicts.single { it.feature == VerdictFeature.VOWIFI }.cause)
        // A SIP timeout alone would read as temporary; with W1's finding the Wi-Fi side is named.
        val timeout = Diagnoses.of(DiagnosisInputs(imsRegistered = false, imsTimeout = true, epdgWifi = EpdgLookup.NO_ADDRESS, epdgCellular = EpdgLookup.RESOLVES))
        assertEquals(wifiDns, timeout.verdicts.single { it.feature == VerdictFeature.VOWIFI }.line)
    }

    @Test
    fun `W1 adds nothing without a comparison or while IMS is registered`() {
        assertTrue(Diagnoses.of(DiagnosisInputs(imsRegistered = false, epdgWifi = EpdgLookup.NO_ADDRESS)).verdicts.none { it.feature == VerdictFeature.VOWIFI })
        assertTrue(Diagnoses.of(DiagnosisInputs(imsRegistered = false, epdgWifi = EpdgLookup.NO_ADDRESS, epdgCellular = EpdgLookup.NO_ADDRESS)).verdicts.isEmpty())
        assertTrue(Diagnoses.of(DiagnosisInputs(imsRegistered = true, epdgWifi = EpdgLookup.NO_ADDRESS, epdgCellular = EpdgLookup.RESOLVES)).verdicts.isEmpty())
        assertTrue(Diagnoses.of(DiagnosisInputs(imsRegistered = false, epdgWifi = EpdgLookup.RESOLVES, epdgCellular = EpdgLookup.RESOLVES)).verdicts.isEmpty())
    }
}
