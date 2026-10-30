package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.BalanceUssd
import io.github.open_carrier_data.check.core.CarrierSms
import io.github.open_carrier_data.check.core.Mask
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbeResults
import io.github.open_carrier_data.check.core.RedirectHost
import io.github.open_carrier_data.check.core.UserPlaneClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCD round 42: the probe ladder (item 2), balance USSD (item 4) and carrier SMS (item 5) classifiers. */
class ProbeLadderTest {
    @Test
    fun `the LIDL signature is read as an account block`() {
        // DNS answers, every connection is dropped, no redirect.
        val r = ProbeResults(
            loopbackOk = true, carrierDnsOk = true, publicDnsOk = true,
            http204Clean = false, hostOk = false, ipv4LiteralOk = false, ipv6LiteralOk = false,
            redirect = RedirectHost.NONE,
        )
        assertEquals(UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, ProbeLadder.classify(r))
    }

    @Test
    fun `a captive portal redirect is read as a block, a broken private DNS as phone-side`() {
        assertEquals(
            UserPlaneClass.PORTAL_REDIRECT,
            ProbeLadder.classify(ProbeResults(carrierDnsOk = true, redirect = RedirectHost.CARRIER_PORTAL)),
        )
        assertEquals(
            UserPlaneClass.PRIVATE_DNS_BROKEN,
            ProbeLadder.classify(ProbeResults(privateDnsOn = true, privateDnsOk = false, carrierDnsOk = true)),
        )
    }

    @Test
    fun `the carrier page reachable while the internet fails is a block, DNS-only is temporary, a clean path is clean`() {
        assertEquals(
            UserPlaneClass.CARRIER_PAGE_WHILE_INTERNET_FAILS,
            ProbeLadder.classify(ProbeResults(carrierDnsOk = false, carrierPageOk = true, http204Clean = false, hostOk = false)),
        )
        assertEquals(
            UserPlaneClass.DNS_ONLY_BROKEN,
            ProbeLadder.classify(ProbeResults(carrierDnsOk = false, ipv4LiteralOk = true)),
        )
        assertEquals(
            UserPlaneClass.PATH_CLEAN,
            ProbeLadder.classify(ProbeResults(carrierDnsOk = true, http204Clean = true, hostOk = true)),
        )
    }

    @Test
    fun `balance USSD allows only the identified brand's exact free code and refuses dangerous forms`() {
        assertTrue(BalanceUssd.allowed("26202", "LIDL Connect", "*100#"))
        assertFalse(BalanceUssd.allowed("26202", "LIDL Connect", "*101#"))
        assertFalse(BalanceUssd.allowed("26202", "LIDL Connect", "*100*1234#")) // a top-up/voucher argument
        assertFalse(BalanceUssd.allowed("26202", "LIDL Connect", "*123#")) // a credit advance
        assertFalse(BalanceUssd.allowed("26203", "", "*101#")) // a network alone names no code (BalanceBrandTest)
        assertFalse(BalanceUssd.allowed("99999", "LIDL Connect", "*100#")) // not the brand's network
    }

    @Test
    fun `a menu reply is never answered and a low balance is blocked`() {
        // The LIDL *100# first reply is a menu; the balance sits one step down.
        val menu = BalanceUssd.classify("Wählen Sie bitte aus: 1 Aufladen, 2 Guthaben & Verbrauch, 3 Tarife & Optionen")
        assertEquals(AccountState.UNKNOWN, menu.state)
        assertTrue(menu.note!!.contains("one step down"))

        val low = BalanceUssd.classify("Aktuelles Guthaben: 0,10 EUR")
        assertEquals(AccountState.BLOCKED, low.state)
        assertEquals("0,10 EUR", low.balance)

        assertEquals(AccountState.OK, BalanceUssd.classify("Aktuelles Guthaben: 12,50 EUR").state)
    }

    @Test
    fun `balance masking hides a long digit run but keeps a short figure`() {
        assertEquals("0,10 EUR", Mask.digits("0,10 EUR"))
        assertEquals("##########", Mask.digits("1724512345"))
    }

    @Test
    fun `carrier SMS is classified only from the carrier's own senders`() {
        assertTrue(CarrierSms.fromSender("26202", "LIDL Connect"))
        assertFalse(CarrierSms.fromSender("26202", "+491700000000"))
        assertEquals(CarrierSms.PlanClass.VOLUME_USED_UP, CarrierSms.classify("Ihr Datenvolumen ist aufgebraucht."))
        assertEquals(AccountState.BLOCKED, CarrierSms.classify("Ihr Datenvolumen ist aufgebraucht.")!!.account)
        assertEquals(CarrierSms.PlanClass.OPTION_ACTIVE, CarrierSms.classify("Ihre Option ist wieder aktiv."))
        assertEquals(AccountState.OK, CarrierSms.PlanClass.TOP_UP_DONE.account)
        assertNull(CarrierSms.classify("Hallo, wie geht es dir?"))
    }
}
