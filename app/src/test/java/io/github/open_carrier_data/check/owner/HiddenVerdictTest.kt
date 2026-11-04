package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ApnRowClass
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.SetupAttempts
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.ApnRows
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import io.github.open_carrier_data.check.owner.core.DiagnosisMark
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 43, item 1 on the owner (and rom) build: the hidden-cause verdict over the registry log's
 * refused setups. The case is the S20's provoked case 2 of 2026-10-31: a made-up internet APN (a row the
 * owner inserted by hand, not OCD) refused twice with UNKNOWN(0x10000), the LIDL balance at 0.10 EUR,
 * a ladder run of the earlier session (ACCOUNT_BLOCK_SIGNATURE) still under 6 hours old, MMS ended.
 */
class HiddenVerdictTest {
    private val hidden = SetupRefusal(VerdictFeature.MOBILE_DATA, 65536, ApnRowClass.OTHER, subId = 3)

    /** The check-now of 11:39:11: mobile data on for every reason, no cellular internet network up, IMS registered. */
    private val case2 = DiagnosisInputs(
        dataSetup = hidden,
        dataOnAllReasons = true,
        cellularUp = false,
        userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE,
        imsRegistered = true,
        mmsEnded = true,
    )
    private val line = "Mobile data refused by the network; this phone's modem software hides the reason"

    @Test
    fun `case 2 names the hidden refusal, not the account, and opens the gate`() {
        val d = Diagnoses.of(case2)
        assertEquals(line, d.line)
        val data = d.verdicts.single { it.feature == VerdictFeature.MOBILE_DATA }
        assertEquals(CauseClass.HIDDEN, data.cause)
        assertFalse(data.confident)
        assertEquals(listOf("Mobile data: Android cause UNKNOWN (0x10000); access point: other"), d.details)
        assertTrue("a measured failure: round 42's gate opens", case2.measuredDataFailure)
        assertFalse("never not counted", d.notCounted())
        // MMS ended keeps its own line beside it.
        assertEquals("MMS ended on this network", d.verdicts.single { it.feature == VerdictFeature.MMS }.line)
    }

    @Test
    fun `a fresh blocked account read is added after the words, never in their place`() {
        val withBalance = Diagnoses.of(case2.copy(account = AccountState.BLOCKED, balance = "0.10 EUR"))
        assertEquals("$line; the account also reads as blocked (balance 0.10 EUR)", withBalance.line)
        assertFalse(withBalance.verdicts.first().confident)
        assertFalse(withBalance.notCounted())
        assertEquals("$line; the account also reads as blocked", Diagnoses.of(case2.copy(account = AccountState.BLOCKED)).line)
    }

    @Test
    fun `the line holds only while mobile data is still down`() {
        // Data back (a cellular network up) or switched off, or either unreadable: no hidden line, and it opens no gate.
        for (gone in listOf(case2.copy(cellularUp = true), case2.copy(dataOnAllReasons = false), case2.copy(dataOnAllReasons = null), case2.copy(cellularUp = null))) {
            val d = Diagnoses.of(gone)
            assertTrue(d.verdicts.none { it.cause == CauseClass.HIDDEN })
            assertEquals("the verdict is round 42's, as without the refusal", Diagnoses.of(gone.copy(dataSetup = null)).verdicts, d.verdicts)
            assertEquals(gone.copy(dataSetup = null).measuredDataFailure, gone.measuredDataFailure)
        }
    }

    @Test
    fun `without a refusal the verdict is 0_4_10's`() {
        // After the restore: data up on web.vodafone.de, not validated (LIDL's block), the ladder's signature, strict Private DNS failing.
        val restored = DiagnosisInputs(
            cellularUp = true, dataValidated = false, dataOnAllReasons = true, userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE,
            privateDnsStrict = true, privateDnsFails = true, imsRegistered = true, mmsEnded = true,
        )
        assertEquals("Mobile data probably blocked by the account; Phone-side: Private DNS (strict) cannot reach its server over this network", Diagnoses.of(restored).summary)
        assertTrue(Diagnoses.of(restored).details.isEmpty())
    }

    @Test
    fun `a readable code or an attach reject ranks first, the ladder and the phone side below`() {
        // A live code first, then a code from the log's refused setup (the S20 once the RIL passes the real cause).
        // Round 44: a live #33 that is not the log's refused setup does not tell the row.
        assertEquals(
            "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM",
            Diagnoses.of(case2.copy(defaultDataCause = 33)).line,
        )
        assertEquals(
            "The network refused the access point name (code 27): probably wrong settings",
            Diagnoses.of(case2.copy(dataSetup = hidden.copy(cause = 27))).line,
        )
        // An attach reject outranks the hidden refusal.
        assertEquals("Refused for mobile data, probably the tariff (code 7)", Diagnoses.of(case2.copy(rejectCause = 7)).line)
        // The ladder's class and the phone-side reasons rank below it.
        assertEquals(line, Diagnoses.of(case2.copy(dataOffReason = null, vpnActive = true)).line)
        assertEquals(line, Diagnoses.of(case2.copy(userPlane = UserPlaneClass.PRIVATE_DNS_BROKEN)).line)
    }

    @Test
    fun `AOSP's -1 to -6 never read as refused and never open the gate`() {
        for (c in -6..-1) {
            val i = case2.copy(dataSetup = hidden.copy(cause = c), userPlane = null)
            assertTrue("$c", Diagnoses.of(i).verdicts.none { it.feature == VerdictFeature.MOBILE_DATA })
            assertFalse("$c", i.measuredDataFailure)
        }
        assertTrue(case2.copy(dataSetup = hidden.copy(cause = -7)).measuredDataFailure)
    }

    @Test
    fun `OCD's rows are named only by their row ID in OCD's own record`() {
        assertEquals(
            "Mobile data refused by the network for the access point under test; this phone's modem software hides the reason",
            Diagnoses.of(case2.copy(dataSetup = hidden.copy(apnRow = ApnRowClass.TEST))).line,
        )
        assertEquals(
            "Mobile data refused by the network for OCD's access point; this phone's modem software hides the reason",
            Diagnoses.of(case2.copy(dataSetup = hidden.copy(apnRow = ApnRowClass.OCD))).line,
        )
        assertEquals(
            listOf("Mobile data: Android cause UNKNOWN (0x10000); access point: OCD test row"),
            Diagnoses.of(case2.copy(dataSetup = hidden.copy(apnRow = ApnRowClass.TEST))).details,
        )
        val sim = SimKey(3, "26202", 2397)
        fun live(id: Long, hold: Hold) = LiveApn(sim, id, "web [default] IPV4V6", "k", hold, 0, null, true, true)
        val apns = listOf(live(25000, Hold.TRIAL), live(25001, Hold.KEPT))
        assertEquals(ApnRowClass.TEST, ApnRows.classOf(25000, "USER_EDITED", apns, 3))
        assertEquals(ApnRowClass.OCD, ApnRows.classOf(25001, "USER_EDITED", apns, 3))
        // Not in the record (case 2's hand-made row 24962), another SIM, an unreadable ID: other.
        assertEquals(ApnRowClass.OTHER, ApnRows.classOf(24962, "CARRIER_EDITED", apns, 3))
        assertEquals(ApnRowClass.OTHER, ApnRows.classOf(25000, "USER_EDITED", apns, 4))
        assertEquals(ApnRowClass.OTHER, ApnRows.classOf(null, "USER_EDITED", apns, 3))
        // An edited state OCD's insert does not write (a reused ID after a table rebuild, a carrier edit): the plain words.
        assertEquals(ApnRowClass.OTHER, ApnRows.classOf(25001, "UNEDITED", apns, 3))
        assertEquals(ApnRowClass.OTHER, ApnRows.classOf(25001, "CARRIER_EDITED", apns, 3))
    }

    @Test
    fun `VoLTE and MMS use the same words, Wi-Fi calling goes to Details only`() {
        val volte = DiagnosisInputs(imsSetup = SetupRefusal(VerdictFeature.VOLTE, 65536, ApnRowClass.OTHER), imsRegistered = false)
        assertEquals(
            "VoLTE refused by the network; this phone's modem software hides the reason",
            Diagnoses.of(volte).verdicts.single { it.feature == VerdictFeature.VOLTE }.line,
        )
        // IMS registered, or not read: no VoLTE refusal.
        assertTrue(Diagnoses.of(volte.copy(imsRegistered = true)).verdicts.none { it.feature == VerdictFeature.VOLTE })
        assertTrue(Diagnoses.of(volte.copy(imsRegistered = null)).verdicts.none { it.feature == VerdictFeature.VOLTE })
        assertFalse("VoLTE opens no mobile-data gate", volte.measuredDataFailure)
        val mms = DiagnosisInputs(mmsSetup = SetupRefusal(VerdictFeature.MMS, 65536, ApnRowClass.TEST))
        assertEquals(
            "MMS refused by the network for the access point under test; this phone's modem software hides the reason",
            Diagnoses.of(mms).verdicts.single { it.feature == VerdictFeature.MMS }.line,
        )
        assertEquals("MMS ended on this network", Diagnoses.of(mms.copy(mmsEnded = true)).verdicts.single { it.feature == VerdictFeature.MMS }.line)
        // An unknown IWLAN value: no Wi-Fi-calling line, never "the modem software hides the reason"; the value in Details.
        val wifi = Diagnoses.of(DiagnosisInputs(iwlanDataCause = 65536, imsRegistered = false))
        assertTrue(wifi.verdicts.none { it.feature == VerdictFeature.VOWIFI })
        assertEquals(listOf("Wi-Fi calling: Android cause UNKNOWN (0x10000)"), wifi.details)
    }

    @Test
    fun `from the S20's registry log to the line, as the light check reads it`() {
        // The masked registry local log of 2026-10-31 as a dump at the 11:39:11 check would hold it.
        val text = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()
            .lineSequence().filter { (Regex("^\\s*(\\S+) - ").find(it)?.groupValues?.get(1) ?: "") <= "2026-10-31T11:39:11" }.joinToString("\n")
        val attempt = SetupAttempts.newest(RegistryDataLog.parse(text), 0, 3, VerdictFeature.MOBILE_DATA)!!
        assertTrue(DataFailCauseClass.refusal(attempt.cause))
        val refusal = SetupRefusal(VerdictFeature.MOBILE_DATA, attempt.cause, ApnRows.classOf(attempt.rowId, attempt.edited, emptyList(), 3), subId = 3)
        assertEquals(hidden, refusal)
        assertEquals(line, Diagnoses.of(case2.copy(dataSetup = refusal)).line)
    }

    @Test
    fun `the screen keeps each SIM's current verdict and drops it when the check names nothing`() {
        val d = Diagnoses.of(case2)
        val marks = DiagnosisMark.put(emptyList(), 0, 3, d, 1_000)
        assertEquals(line, marks.single().line)
        assertEquals(d.details, marks.single().details)
        assertEquals(line, marks.single().shown(1_000))
        val replaced = DiagnosisMark.put(marks, 0, 3, Diagnoses.of(DiagnosisInputs()), 2_000)
        assertTrue(replaced.isEmpty())
        // The state file keeps it as plain words and classes; an old file without it reads as none.
        assertEquals(OwnerState(), OwnerState.decode("{}"))
    }
}
