package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ApnRowClass
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.RetryWait
import io.github.open_carrier_data.check.core.RetryWaits
import io.github.open_carrier_data.check.core.SetupAttempts
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.ApnRows
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import io.github.open_carrier_data.check.owner.core.DiagnosisMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OCD rule round 43, item 2 on the owner (and rom) build: the wait clause after a shown refusal line, from
 * DataRetryManager's entries in the phone service's dump (the privileged OwnerCommand.PHONE read). Case 2
 * of 2026-10-31 on the S20: the throttle of the made-up APN's profile ran until 11:49:42.149.
 */
class RetryWaitVerdictTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val check = ms("2026-10-31T11:39:11")
    private val until = ms("2026-10-31T11:49:42.149")
    private val hidden = SetupRefusal(VerdictFeature.MOBILE_DATA, 65536, ApnRowClass.OTHER, subId = 3, waitUntilMs = until)
    private val case2 = DiagnosisInputs(
        dataSetup = hidden, dataOnAllReasons = true, cellularUp = false, userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, imsRegistered = true, mmsEnded = true,
    )
    private val line = "Mobile data refused by the network; this phone's modem software hides the reason"

    @Test
    fun `the clause follows the line, after the account clause and before the phone-side factors`() {
        val d = Diagnoses.of(case2)
        assertEquals("$line. The phone waits 11 minutes before trying again", d.shown(check))
        assertEquals("the log's change check and the summary leave it out", line, d.summary)
        assertEquals(
            "$line; the account also reads as blocked (balance 0.10 EUR). The phone waits 11 minutes before trying again",
            Diagnoses.of(case2.copy(account = AccountState.BLOCKED, balance = "0.10 EUR")).shown(check),
        )
        val withFactor = d.copy(factors = listOf("Phone-side: Private DNS (strict) cannot reach its server over this network"))
        assertEquals("$line. The phone waits 11 minutes before trying again; Phone-side: Private DNS (strict) cannot reach its server over this network", withFactor.shown(check))
        // Under a minute left, or none read: the line alone.
        assertEquals(line, d.shown(until - 30_000))
        assertEquals(line, Diagnoses.of(case2.copy(dataSetup = hidden.copy(waitUntilMs = null))).shown(check))
    }

    @Test
    fun `the clause changes nothing else`() {
        val with = Diagnoses.of(case2)
        val without = Diagnoses.of(case2.copy(dataSetup = hidden.copy(waitUntilMs = null)))
        assertEquals(without.verdicts, with.verdicts)
        assertEquals(without.dataRefusal, with.dataRefusal)
        assertEquals(without.notCounted(), with.notCounted())
        assertEquals(case2.copy(dataSetup = hidden.copy(waitUntilMs = null)).measuredDataFailure, case2.measuredDataFailure)
    }

    @Test
    fun `it may follow a coded refusal too, never a line that is not the refused setup's`() {
        assertEquals(
            "The network refused the access point name (code 27): probably wrong settings. The phone waits 11 minutes before trying again",
            Diagnoses.of(case2.copy(dataSetup = hidden.copy(cause = 27))).shown(check),
        )
        // An attach reject outranks the hidden refusal: its line gets no wait of the setup's.
        assertEquals("Refused for mobile data, probably the tariff (code 7)", Diagnoses.of(case2.copy(rejectCause = 7)).shown(check))
        // Data back: no refusal, no clause; the headline is MMS's own line.
        assertEquals("MMS ended on this network", Diagnoses.of(case2.copy(cellularUp = true, userPlane = null)).shown(check))
        // VoLTE's own refused setup carries its own wait.
        val volte = DiagnosisInputs(imsSetup = SetupRefusal(VerdictFeature.VOLTE, 65536, ApnRowClass.OTHER, waitUntilMs = until), imsRegistered = false)
        assertEquals("VoLTE refused by the network; this phone's modem software hides the reason. The phone waits 11 minutes before trying again", Diagnoses.of(volte).shown(check))
    }

    @Test
    fun `the screen works N out again each time it shows the line`() {
        val mark = DiagnosisMark.put(emptyList(), 0, 3, Diagnoses.of(case2), check).single()
        assertEquals(until, mark.waitUntilMs)
        assertEquals("$line. The phone waits 11 minutes before trying again", mark.shown(check))
        assertEquals("$line. The phone waits 5 minutes before trying again", mark.shown(ms("2026-10-31T11:45:00")))
        assertEquals(line, mark.shown(ms("2026-10-31T11:49:00")))
        // Logged at two checks: one entry, since the change check strips the minutes.
        assertEquals(RetryWait.strip(mark.shown(check)!!), RetryWait.strip(mark.shown(ms("2026-10-31T11:45:00"))!!))
    }

    @Test
    fun `from the S20's registry log and phone service dump to the line, as the light check reads them`() {
        val registry = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()
            .lineSequence().filter { (Regex("^\\s*(\\S+) - ").find(it)?.groupValues?.get(1) ?: "") <= "2026-10-31T11:39:11" }.joinToString("\n")
        val dump = requireNotNull(javaClass.getResource("/lab/phone_s20_retry_case2.txt")).readText()
        val a = SetupAttempts.newest(RegistryDataLog.parse(registry), 0, 3, VerdictFeature.MOBILE_DATA)!!
        val refusal = SetupRefusal(
            VerdictFeature.MOBILE_DATA, a.cause, ApnRows.classOf(a.rowId, a.edited, emptyList(), 3),
            subId = 3, waitUntilMs = RetryWaits.untilMs(dump, 0, a.rowId, a.at, zone, check),
        )
        assertEquals(hidden, refusal)
        assertEquals("$line. The phone waits 11 minutes before trying again", Diagnoses.of(case2.copy(dataSetup = refusal)).shown(check))
        val restored = requireNotNull(javaClass.getResource("/lab/phone_s20_retry_after_restore.txt")).readText()
        assertNull(RetryWaits.untilMs(restored, 0, a.rowId, a.at, zone, check))
    }
}
