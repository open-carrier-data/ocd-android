package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ApnRowClass
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.RetryWaits
import io.github.open_carrier_data.check.core.SetupAttempts
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.ApnRows
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OCD rule round 44 on the owner (and rom) build: a #33 through the assembler, its words by the refused
 * setup's access point row (round 43's [ApnRows], by row ID only), a live code that equals the registry
 * log's refused setup, and a blocked account read after the words. The case is round 43's case 2 on the
 * S20 (a made-up internet APN at 0.10 EUR on Vodafone 262-02), which build I's RIL table showed was #33.
 */
class Code33VerdictTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val check = ms("2026-10-31T11:39:11")
    private val until = ms("2026-10-31T11:49:42.149")

    private val sim = SimKey(3, "26202", 2397)
    private fun live(id: Long, hold: Hold) = LiveApn(sim, id, "web.vodafone.de [default] IPV4V6", "web-key", hold, 0, null, true, true)

    /** OCD's kept row and a running trial's test row carrying the same served value: only the row ID tells them apart. */
    private val apns = listOf(live(25001, Hold.KEPT), live(25000, Hold.TRIAL))
    private val ocdRow = ApnRows.classOf(25001, ApnRows.OCD_EDITED, apns, 3)
    private val testRow = ApnRows.classOf(25000, ApnRows.OCD_EDITED, apns, 3)
    private val otherRow = ApnRows.classOf(24962, "CARRIER_EDITED", apns, 3)

    private val dataDown = DiagnosisInputs(dataOnAllReasons = true, cellularUp = false, imsRegistered = true)
    private fun data(row: ApnRowClass?) = Diagnoses.of(dataDown.copy(dataSetup = SetupRefusal(VerdictFeature.MOBILE_DATA, 33, row, subId = 3)))
    private fun volte(row: ApnRowClass?) = Diagnoses.of(DiagnosisInputs(imsRegistered = false, imsSetup = SetupRefusal(VerdictFeature.VOLTE, 33, row, subId = 3)))
    private fun mms(row: ApnRowClass?) = Diagnoses.of(DiagnosisInputs(mmsSetup = SetupRefusal(VerdictFeature.MMS, 33, row, subId = 3)))

    private val neutral = "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM"
    private val testWords = "Mobile data refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan"

    @Test
    fun `the rows are told by their row ID, whatever their value`() {
        assertEquals(ApnRowClass.OCD, ocdRow)
        assertEquals(ApnRowClass.TEST, testRow)
        assertEquals(ApnRowClass.OTHER, otherRow)
    }

    @Test
    fun `mobile data, by row`() {
        assertEquals("Refused for mobile data, probably the tariff (code 33)", data(ocdRow).line)
        assertEquals(CauseClass.NOT_BOOKED, data(ocdRow).verdicts.single().cause)
        assertEquals(testWords, data(testRow).line)
        assertEquals(CauseClass.AMBIGUOUS, data(testRow).verdicts.single().cause)
        assertEquals(neutral, data(otherRow).line)
        assertEquals(neutral, data(null).line)
        for (row in listOf(ocdRow, testRow, otherRow, null)) {
            val d = data(row)
            assertFalse("$row", d.verdicts.single().confident)
            // A #33 is a measured failure under every wording: the gate opens, and it counts against a test row.
            assertTrue("$row", dataDown.copy(dataSetup = SetupRefusal(VerdictFeature.MOBILE_DATA, 33, row, subId = 3)).measuredDataFailure)
            assertFalse("$row", d.notCounted())
            assertTrue("$row", d.details.isEmpty())
        }
    }

    @Test
    fun `MMS, by row`() {
        fun line(row: ApnRowClass?) = mms(row).verdicts.single { it.feature == VerdictFeature.MMS }.line
        assertEquals("Refused for MMS, probably the tariff (code 33)", line(ocdRow))
        assertEquals("MMS refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan", line(testRow))
        assertEquals("Refused for MMS (code 33): not in the plan, or an access point name the network does not know for this SIM", line(otherRow))
        assertEquals("Refused for MMS (code 33): not in the plan, or an access point name the network does not know for this SIM", line(null))
    }

    @Test
    fun `VoLTE, by row`() {
        fun line(row: ApnRowClass?) = volte(row).verdicts.single { it.feature == VerdictFeature.VOLTE }.line
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", line(ocdRow))
        assertEquals("VoLTE refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan", line(testRow))
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", line(otherRow))
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", line(null))
        // A live VoLTE #33 with no refused IMS setup in the log keeps the tariff words too.
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", Diagnoses.of(DiagnosisInputs(imsRegistered = false, imsDataCause = 33)).line)
    }

    @Test
    fun `a live code that equals the log's refused setup takes the setup's row and wait`() {
        val setup = SetupRefusal(VerdictFeature.MOBILE_DATA, 33, ApnRowClass.TEST, subId = 3, waitUntilMs = until)
        val matched = Diagnoses.of(dataDown.copy(defaultDataCause = 33, dataSetup = setup))
        assertEquals("$testWords. The phone waits 11 minutes before trying again", matched.shown(check))
        assertEquals(setup.refusal(), matched.dataRefusal)
        // A live #33 beside a hidden refusal (no match): the not-told words, no wait of the setup's.
        val unmatched = Diagnoses.of(dataDown.copy(defaultDataCause = 33, dataSetup = setup.copy(cause = 65536)))
        assertEquals(neutral, unmatched.shown(check))
        // No setup at all: the not-told words.
        assertEquals(neutral, Diagnoses.of(DiagnosisInputs(defaultDataCause = 33)).line)
        // VoLTE and MMS alike.
        val ims = SetupRefusal(VerdictFeature.VOLTE, 33, ApnRowClass.TEST, subId = 3, waitUntilMs = until)
        assertEquals(
            "VoLTE refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan. The phone waits 11 minutes before trying again",
            Diagnoses.of(DiagnosisInputs(imsRegistered = false, imsDataCause = 33, imsSetup = ims)).shown(check),
        )
        val mmsSetup = SetupRefusal(VerdictFeature.MMS, 33, ApnRowClass.OCD, subId = 3, waitUntilMs = until)
        assertEquals(
            "Refused for MMS, probably the tariff (code 33). The phone waits 11 minutes before trying again",
            Diagnoses.of(DiagnosisInputs(mmsDataCause = 33, mmsSetup = mmsSetup)).shown(check),
        )
    }

    @Test
    fun `case 2 at 0_10 EUR keeps the code's words and adds the account read after them`() {
        // Round 43's case 2 with the real cause: a made-up APN (not OCD's), the ladder's account-block signature,
        // the balance read blocked. Before round 44 this read "Mobile data probably blocked by the account".
        val case2 = dataDown.copy(
            dataSetup = SetupRefusal(VerdictFeature.MOBILE_DATA, 33, otherRow, subId = 3, waitUntilMs = until),
            userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, account = AccountState.BLOCKED, balance = "0.10 EUR",
        )
        val d = Diagnoses.of(case2)
        assertEquals("$neutral; the account also reads as blocked (balance 0.10 EUR). The phone waits 11 minutes before trying again", d.shown(check))
        assertEquals(CauseClass.AMBIGUOUS, d.verdicts.single().cause)
        assertFalse(d.verdicts.single().confident)
        assertFalse(d.notCounted())
        // A #27 (other cores' answer) alike.
        assertEquals(
            "The network refused the access point name (code 27): probably wrong settings; the account also reads as blocked (balance 0.10 EUR)",
            Diagnoses.of(case2.copy(dataSetup = case2.dataSetup!!.copy(cause = 27, waitUntilMs = null))).line,
        )
        // A #30, where no credit can be the cause, keeps round 42's account line, from the log or live.
        assertEquals(
            "Mobile data probably blocked by the account: balance 0.10 EUR",
            Diagnoses.of(case2.copy(dataSetup = case2.dataSetup!!.copy(cause = 30, waitUntilMs = null))).line,
        )
        assertEquals(
            "Mobile data probably blocked by the account: balance 0.10 EUR",
            Diagnoses.of(DiagnosisInputs(defaultDataCause = 30, account = AccountState.BLOCKED, balance = "0.10 EUR")).line,
        )
    }

    @Test
    fun `from the S20's registry log, as a phone that passes the cause prints it, to the line`() {
        val registry = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()
            .replace("UNKNOWN(0x10000)", "SERVICE_OPTION_NOT_SUBSCRIBED(0x21)")
            .lineSequence().filter { (Regex("^\\s*(\\S+) - ").find(it)?.groupValues?.get(1) ?: "") <= "2026-10-31T11:39:11" }.joinToString("\n")
        val dump = requireNotNull(javaClass.getResource("/lab/phone_s20_retry_case2.txt")).readText()
        val a = SetupAttempts.newest(RegistryDataLog.parse(registry), 0, 3, VerdictFeature.MOBILE_DATA)!!
        assertEquals(33, a.cause)
        val refusal = SetupRefusal(
            VerdictFeature.MOBILE_DATA, a.cause, ApnRows.classOf(a.rowId, a.edited, apns, 3),
            subId = 3, waitUntilMs = RetryWaits.untilMs(dump, 0, a.rowId, a.at, zone, check),
        )
        // The hand-made row 24962 is in no record of OCD's.
        assertEquals(ApnRowClass.OTHER, refusal.apnRow)
        assertEquals("$neutral. The phone waits 11 minutes before trying again", Diagnoses.of(dataDown.copy(dataSetup = refusal)).shown(check))
    }
}
