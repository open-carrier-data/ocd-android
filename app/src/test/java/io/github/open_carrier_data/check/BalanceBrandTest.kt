package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.BalanceChoice
import io.github.open_carrier_data.check.core.BalanceUssd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 42, item 4, the follow-up fix: the balance code is keyed by the brand the SIM's own
 * provider name (EF_SPN) identifies, never by the network code. On 26203/26207 the network-keyed
 * allowlist sent o2's *101# to every SIM, ALDI TALK's (*100#) included; 26201 carries Kaufland mobil
 * (*144#) and fraenk (no code), 26202 carries LIDL Connect, otelo and CallYa.
 */
class BalanceBrandTest {
    private fun sent(mccmnc: String, spn: String?): String? = (BalanceUssd.choose(mccmnc, spn) as? BalanceChoice.Send)?.code

    private fun notSent(mccmnc: String, spn: String?): BalanceChoice.NotSent =
        BalanceUssd.choose(mccmnc, spn) as? BalanceChoice.NotSent ?: throw AssertionError("a code would be sent to $spn on $mccmnc")

    @Test
    fun `an identified brand gets its own documented code`() {
        assertEquals("*100#", sent("26202", "Vodafone.de"))
        assertEquals("*100#", sent("26202", "Vodafone"))
        assertEquals("*100#", sent("26201", "Telekom.de"))
        assertEquals("*100#", sent("26206", "T-Mobile D"))
        assertEquals("*100#", sent("26201", "congstar"))
        assertEquals("*100#", sent("26201", "congstar.de"))
        assertEquals("Vodafone", (BalanceUssd.choose("26202", "Vodafone") as BalanceChoice.Send).brand.brand)
    }

    @Test
    fun `LIDL Connect's first reply is a menu, so nothing is sent and the hint names the code and the item`() {
        // Measured on the S20 on 2026-10-30: *100# answers with a menu; the balance sits under "2 Guthaben & Verbrauch".
        val lidl = BalanceUssd.brands("26202", "LIDL Connect").single()
        assertTrue(lidl.firstReplyMenu)
        assertEquals("Guthaben & Verbrauch", lidl.menuItem)
        assertNull(sent("26202", "LIDL Connect"))
        val choice = notSent("26202", "LIDL Connect")
        assertEquals("balance code not sent: LIDL Connect answers *100# with a menu", choice.reason)
        assertEquals("dial *100#, choose \"Guthaben & Verbrauch\"", choice.hint)
        assertEquals("balance code not sent: LIDL Connect answers *100# with a menu; dial *100#, choose \"Guthaben & Verbrauch\"", choice.line)
        // Nothing at all: not even the brand's own exact code passes the send check.
        assertFalse(BalanceUssd.allowed("26202", "LIDL Connect", "*100#"))
        assertFalse(BalanceUssd.allowed("26202", " LIDL Connect ", "*100#"))
        // The other brands on the same network keep their one read.
        assertEquals("*100#", sent("26202", "Vodafone"))
        assertTrue(BalanceUssd.allowed("26202", "Vodafone", "*100#"))
    }

    @Test
    fun `a failed or missing reply has its own record`() {
        assertEquals("balance not read: the carrier's reply needs a menu step or failed", BalanceUssd.REPLY_FAILED)
        assertEquals("balance not read: no reply from the carrier", BalanceUssd.NO_REPLY)
    }

    @Test
    fun `no SIM on an o2 network gets a code, ALDI TALK above all, and the hint names both codes`() {
        for (mccmnc in listOf("26203", "26207")) {
            for (spn in listOf("ALDI TALK", "AldiTalk", "MEDIONmobile", "o2 - de", "O2", "Blau", "blau.de", "fonic", "netzclub", "", null)) {
                val choice = notSent(mccmnc, spn)
                assertEquals(BalanceUssd.NOT_IDENTIFIED, choice.reason)
                assertTrue(choice.hint, choice.hint.contains("ALDI TALK and Ay Yildiz *100#"))
                assertTrue(choice.hint, choice.hint.contains("*101#"))
            }
        }
        assertEquals(
            "balance code not sent: brand not identified; dial it by hand: o2 prepaid, Blau, fonic and netzclub *101#; ALDI TALK and Ay Yildiz *100#",
            notSent("26203", "ALDI TALK").line,
        )
    }

    @Test
    fun `on Telekom's network fraenk gets no code, and Kaufland mobil is not identified`() {
        val fraenk = notSent("26201", "fraenk")
        assertEquals("balance code not sent: fraenk has no balance code", fraenk.reason)
        assertEquals("fraenk shows the balance only in its app", fraenk.hint)
        for (spn in listOf("Kaufland mobil", "Kaufland", "Penny Mobil", "ja!mobil", "EDEKA smart", "debitel", "DEBITEL")) {
            val choice = notSent("26201", spn)
            assertEquals(BalanceUssd.NOT_IDENTIFIED, choice.reason)
            assertTrue(choice.hint, choice.hint.contains("Kaufland mobil *144#"))
            assertTrue(choice.hint, choice.hint.contains("*100#"))
        }
    }

    @Test
    fun `on Vodafone's network only Vodafone and LIDL Connect are identified`() {
        for (spn in listOf("otelo", "CallYa", "ewe.de", "1&1", "BILDmobil", "fyve", "", null)) {
            val choice = notSent("26202", spn)
            assertEquals(BalanceUssd.NOT_IDENTIFIED, choice.reason)
            assertEquals("dial it by hand: Vodafone CallYa, LIDL Connect and otelo *100#", choice.hint)
        }
    }

    @Test
    fun `the provider name matches exactly, with only surrounding blanks trimmed`() {
        assertEquals("LIDL Connect", BalanceUssd.brands("26202", " LIDL Connect ").single().brand)
        assertEquals("*100#", sent("26202", " Vodafone.de "))
        val near = listOf(
            "26202" to "lidl connect", "26202" to "LIDL CONNECT", "26202" to "LIDL", "26202" to "LIDL Connect Plus",
            "26202" to "LIDLConnect", "26202" to "Lidl Connect", "26202" to "VODAFONE.DE", "26202" to "Vodafone DE",
            "26201" to "Telekom", "26201" to "CONGSTAR", "26201" to "telekom.de",
        )
        for ((mccmnc, spn) in near) {
            assertEquals(spn, BalanceUssd.NOT_IDENTIFIED, notSent(mccmnc, spn).reason)
        }
    }

    @Test
    fun `a brand on another network than its own is not identified`() {
        assertEquals(BalanceUssd.NOT_IDENTIFIED, notSent("26207", "LIDL Connect").reason) // Lidl Mobile runs on o2
        assertEquals(BalanceUssd.NOT_IDENTIFIED, notSent("26203", "Telekom.de").reason)
        assertEquals(BalanceUssd.NOT_IDENTIFIED, notSent("26202", "congstar").reason)
        assertEquals(BalanceUssd.NOT_IDENTIFIED, notSent("23410", "Vodafone").reason)
        assertEquals("no balance code is documented for this network", notSent("99999", "LIDL Connect").hint)
    }

    @Test
    fun `a network code alone never picks a code`() {
        // Every network the table covers, with an empty, a missing or a foreign provider name: nothing is sent.
        for (mccmnc in BalanceUssd.BRANDS.flatMap { it.networks }.toSet() + setOf("26203", "26207", "26208", "26211", "26223")) {
            for (spn in listOf(null, "", "   ", "Some Other Brand")) {
                assertEquals("$mccmnc/$spn", BalanceUssd.NOT_IDENTIFIED, notSent(mccmnc, spn).reason)
                assertFalse(BalanceUssd.allowed(mccmnc, spn, "*100#"))
                assertFalse(BalanceUssd.allowed(mccmnc, spn, "*101#"))
            }
        }
    }

    @Test
    fun `the dangerous codes and forms are refused even for an identified brand`() {
        for (code in listOf("*100*1234#", "*123#", "*133#", "*124#", "*135#", "*#123#", "*#100#", "*100", "100#", "*1000#", "**100#", "*101#", "*144#", "")) {
            assertFalse(code, BalanceUssd.allowed("26202", "LIDL Connect", code))
            assertFalse(code, BalanceUssd.allowed("26202", "Vodafone", code))
            assertFalse(code, BalanceUssd.allowed("26201", "Telekom.de", code))
        }
        // A brand without a code never gets one, not even the network's usual code.
        assertFalse(BalanceUssd.allowed("26201", "fraenk", "*100#"))
    }

    @Test
    fun `the table stays small, sourced, safe and unambiguous`() {
        val shape = Regex("^\\*[0-9]{3}#$")
        val dangerous = setOf("*123#", "*124#", "*133#", "*135#")
        assertTrue(BalanceUssd.BRANDS.size <= 8)
        for (brand in BalanceUssd.BRANDS) {
            assertTrue(brand.brand, brand.spns.isNotEmpty() && brand.networks.isNotEmpty())
            assertTrue(brand.brand, brand.spnSource.isNotBlank() && brand.codeSource.isNotBlank())
            assertTrue(brand.brand, brand.spns.all { it == it.trim() && it.isNotEmpty() })
            assertTrue(brand.brand, brand.networks.all { Regex("262[0-9]{2}").matches(it) })
            val code = brand.code
            if (code == null) {
                assertTrue(brand.brand, !brand.elsewhere.isNullOrBlank())
            } else {
                assertTrue(brand.brand, shape.matches(code) && code !in dangerous)
            }
            // A menu-first brand names the measured item and where it was measured; the item is never a top-up, a tariff or an offer.
            if (brand.firstReplyMenu) {
                assertTrue(brand.brand, !brand.menuItem.isNullOrBlank() && !brand.menuSource.isNullOrBlank())
                assertFalse(brand.brand, Regex("(?i)aufladen|tarif|option|angebot|buchen").containsMatchIn(brand.menuItem!!))
            }
        }
        // No provider name names two brands on one network.
        val keys = BalanceUssd.BRANDS.flatMap { b -> b.networks.flatMap { n -> b.spns.map { n to it } } }
        assertEquals(keys.size, keys.toSet().size)
        // No o2-network brand is in the table: no source names its EF_SPN.
        assertTrue(BalanceUssd.BRANDS.none { it.networks.any { n -> n in setOf("26203", "26205", "26207", "26208", "26211", "26277") } })
    }

    @Test
    fun `the hints name only documented codes and never a dangerous one`() {
        for (mccmnc in listOf("26201", "26206", "26202", "26203", "26207", "99999")) {
            val hint = BalanceUssd.hint(mccmnc)
            val codes = Regex("\\*[0-9#*]+").findAll(hint).map { it.value }.toSet()
            assertTrue(hint, codes.all { it in setOf("*100#", "*101#", "*144#") })
        }
        assertTrue(BalanceUssd.hint("26201").contains("Kaufland mobil *144#"))
        assertTrue(BalanceUssd.hint("26202").contains("LIDL Connect"))
    }
}
