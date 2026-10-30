package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.Refusal
import io.github.open_carrier_data.check.core.RejectCauseClass
import io.github.open_carrier_data.check.core.SipRegClass
import io.github.open_carrier_data.check.core.TestBlame
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.core.Verdicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCD rule round 42: the shared cause classifiers and the verdict model (items 1, 3, 7). */
class CausesTest {
    @Test
    fun `data fail causes sort into the report classes`() {
        assertEquals(CauseClass.NOT_BOOKED, DataFailCauseClass.of(33))
        assertEquals(CauseClass.BARRED, DataFailCauseClass.of(8))
        assertEquals(CauseClass.WRONG_SETTINGS, DataFailCauseClass.of(27))
        assertEquals(CauseClass.WRONG_SETTINGS, DataFailCauseClass.of(51))
        assertEquals(CauseClass.TEMPORARY, DataFailCauseClass.of(38))
        assertEquals(CauseClass.AMBIGUOUS, DataFailCauseClass.of(30))
        assertEquals(CauseClass.UNKNOWN, DataFailCauseClass.of(99))
    }

    @Test
    fun `iwlan data fail causes classify the account and the path apart`() {
        assertEquals(CauseClass.NOT_BOOKED, DataFailCauseClass.of(9002))
        assertEquals(CauseClass.BARRED, DataFailCauseClass.of(9003))
        assertEquals(CauseClass.DEVICE_NOT_ALLOWED, DataFailCauseClass.of(9006))
        assertEquals(CauseClass.ROAMING, DataFailCauseClass.of(11011))
        assertEquals(CauseClass.TEMPORARY, DataFailCauseClass.of(10500))
        // 0x4000+ is IWLAN path/timeout, phone- or path-side, never the account.
        assertEquals(CauseClass.PHONE_SIDE, DataFailCauseClass.of(0x4004))
    }

    @Test
    fun `AOSP marks the subscription and settings causes permanent`() {
        assertTrue(DataFailCauseClass.permanent(33))
        assertTrue(DataFailCauseClass.permanent(27))
        assertFalse(DataFailCauseClass.permanent(38))
    }

    @Test
    fun `reject causes split roaming from not-subscribed by where the phone is`() {
        assertEquals(CauseClass.NOT_BOOKED, RejectCauseClass.of(7, roaming = false))
        assertEquals(CauseClass.BARRED, RejectCauseClass.of(8, roaming = false))
        assertEquals(CauseClass.DEVICE_NOT_ALLOWED, RejectCauseClass.of(6, roaming = false))
        assertEquals(CauseClass.ROAMING, RejectCauseClass.of(13, roaming = true))
        assertEquals(CauseClass.NOT_BOOKED, RejectCauseClass.of(13, roaming = false))
    }

    @Test
    fun `sip registration classes work before and after the ImsStack fork`() {
        // Today on the S20: 403, a timeout, or (1000, 0).
        assertEquals(SipRegClass.FORBIDDEN, SipRegClass.of(403, null, timeout = false))
        assertEquals(SipRegClass.TIMEOUT, SipRegClass.of(null, null, timeout = true))
        assertEquals(SipRegClass.OTHER, SipRegClass.of(0, 1000, timeout = false))
        assertEquals(SipRegClass.DATA_LOST, SipRegClass.of(null, 121, timeout = false))
        // After the fork fills SipDetails.
        assertEquals(SipRegClass.USER_UNKNOWN, SipRegClass.of(404, null, timeout = false))
        assertEquals(SipRegClass.TEMPORARY, SipRegClass.of(503, null, timeout = false))
        assertEquals(SipRegClass.STACK, SipRegClass.of(488, null, timeout = false))
        assertEquals(CauseClass.NOT_BOOKED, SipRegClass.FORBIDDEN.cause)
    }

    @Test
    fun `the account-block verdict is plain with two signals and hedged with one`() {
        // Two signals: the LIDL user-plane signature plus a zero balance read.
        val two = Verdicts.of(
            VerdictFeature.MOBILE_DATA,
            Refusal(CauseClass.BARRED, userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE),
            AccountState.BLOCKED,
            balance = "0.10 EUR",
        )!!
        assertTrue(two.confident)
        assertEquals("Mobile data blocked by the account: balance 0.10 EUR", two.line)

        // One signal: the balance alone.
        val one = Verdicts.of(VerdictFeature.MOBILE_DATA, null, AccountState.BLOCKED)!!
        assertFalse(one.confident)
        assertEquals("Mobile data probably blocked by the account", one.line)
    }

    @Test
    fun `the fixed wordings match the ruling`() {
        assertEquals(
            "Refused for this SIM, probably the tariff (code 33)",
            Verdicts.of(VerdictFeature.VOLTE, Refusal(CauseClass.NOT_BOOKED, code = 33), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "The network refused the access point name (code 27): probably wrong settings",
            Verdicts.of(VerdictFeature.MOBILE_DATA, Refusal(CauseClass.WRONG_SETTINGS, code = 27), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Temporary network fault (code 38)",
            Verdicts.of(VerdictFeature.MOBILE_DATA, Refusal(CauseClass.TEMPORARY, code = 38, temporaryDefinite = true), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Phone-side: the Private DNS server can't be reached on mobile data",
            Verdicts.of(VerdictFeature.MOBILE_DATA, Refusal(CauseClass.PHONE_SIDE, userPlane = UserPlaneClass.PRIVATE_DNS_BROKEN), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Device not allowed by the network (code 6)",
            Verdicts.of(VerdictFeature.VOLTE, Refusal(CauseClass.DEVICE_NOT_ALLOWED, code = 6), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "MMS ended on this network",
            Verdicts.of(VerdictFeature.MMS, null, AccountState.UNKNOWN, mmsEnded = true)!!.line,
        )
    }

    @Test
    fun `a verdict never fires on a clean read`() {
        assertNull(Verdicts.of(VerdictFeature.VOLTE, null, AccountState.UNKNOWN))
        assertNull(Verdicts.of(VerdictFeature.VOLTE, Refusal(CauseClass.UNKNOWN), AccountState.UNKNOWN))
    }

    @Test
    fun `not-counted needs two signals or a definite temporary code`() {
        // Two agreeing signals.
        assertTrue(TestBlame.notCounted(Refusal(CauseClass.BARRED, userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE), AccountState.BLOCKED))
        assertTrue(TestBlame.notCounted(Refusal(CauseClass.BARRED), AccountState.BLOCKED))
        // A definite temporary code alone.
        assertTrue(TestBlame.notCounted(Refusal(CauseClass.TEMPORARY, code = 38, temporaryDefinite = true), AccountState.UNKNOWN))
        // One hedged signal never suppresses a test.
        assertFalse(TestBlame.notCounted(Refusal(CauseClass.BARRED), AccountState.UNKNOWN))
        assertFalse(TestBlame.notCounted(null, AccountState.BLOCKED))
        assertFalse(TestBlame.notCounted(Refusal(CauseClass.NOT_BOOKED, code = 33), AccountState.BLOCKED))
    }
}
