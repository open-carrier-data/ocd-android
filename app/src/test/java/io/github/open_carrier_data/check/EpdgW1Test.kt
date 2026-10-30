package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.CauseVerdicts
import io.github.open_carrier_data.check.core.EpdgClass
import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.EpdgPlan
import io.github.open_carrier_data.check.core.EpdgPolicy
import io.github.open_carrier_data.check.core.EpdgW1
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.VerdictFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 42, item 2, W1: the ePDG name lookup through the Wi-Fi resolver and the cellular
 * resolver, kept as "resolves", "no address" or "fails" only, and its bounds. W2 (IKE) is not built.
 */
class EpdgW1Test {
    private val now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `the looked-up name is the standard 3GPP name built from the SIM's own MCC and MNC`() {
        assertEquals("epdg.epc.mnc002.mcc262.pub.3gppnetwork.org", HomeNetwork.of("26202")!!.epdgFqdn)
        assertEquals("epdg.epc.mnc007.mcc262.pub.3gppnetwork.org", HomeNetwork.of("26207")!!.epdgFqdn)
        assertEquals("epdg.epc.mnc260.mcc310.pub.3gppnetwork.org", HomeNetwork.of("310260")!!.epdgFqdn)
        // Only digits make a name: nothing else can reach the resolver.
        assertNull(HomeNetwork.of("262"))
        assertNull(HomeNetwork.of("2620a"))
        assertNull(HomeNetwork.of("26202.evil.example"))
    }

    @Test
    fun `a resolver's answer is kept as a class, from the count and the rcode only`() {
        assertEquals(EpdgLookup.RESOLVES, EpdgLookup.of(addresses = 2, rcode = 0))
        assertEquals(EpdgLookup.NO_ADDRESS, EpdgLookup.of(addresses = 0, rcode = 0)) // NOERROR, no data (the Vodafone ECS filter)
        assertEquals(EpdgLookup.NO_ADDRESS, EpdgLookup.of(addresses = 0, rcode = 3)) // NXDOMAIN
        assertEquals(EpdgLookup.FAILS, EpdgLookup.of(addresses = 0, rcode = 2)) // SERVFAIL
        assertEquals(EpdgLookup.FAILS, EpdgLookup.of(addresses = 0, rcode = 5)) // REFUSED
        assertEquals(EpdgLookup.FAILS, EpdgLookup.of(addresses = null, rcode = null)) // an error or a timeout
        assertEquals(setOf("resolves", "no_address", "fails"), EpdgLookup.entries.map { it.wire }.toSet())
    }

    @Test
    fun `only a comparison names the Wi-Fi side's DNS`() {
        assertEquals(EpdgClass.WIFI_DNS_NO_ADDRESS, EpdgW1.classify(EpdgLookup.NO_ADDRESS, EpdgLookup.RESOLVES))
        assertEquals(EpdgClass.WIFI_DNS_FAILS, EpdgW1.classify(EpdgLookup.FAILS, EpdgLookup.RESOLVES))
        assertNull(EpdgW1.classify(EpdgLookup.RESOLVES, EpdgLookup.RESOLVES))
        // One resolver alone, or a carrier DNS that has no address either, is never evidence.
        for (wifi in EpdgLookup.entries + listOf(null)) {
            for (cellular in listOf(EpdgLookup.NO_ADDRESS, EpdgLookup.FAILS, null)) {
                assertNull("$wifi/$cellular", EpdgW1.classify(wifi, cellular))
                assertNull(EpdgW1.refusal(wifi, cellular))
            }
        }
        assertNull(EpdgW1.classify(null, EpdgLookup.RESOLVES))
    }

    @Test
    fun `the Wi-Fi-calling verdict names the Wi-Fi side, plainly, as phone-side`() {
        val v = CauseVerdicts.of(VerdictFeature.VOWIFI, EpdgW1.refusal(EpdgLookup.NO_ADDRESS, EpdgLookup.RESOLVES), AccountState.UNKNOWN)!!
        assertEquals("Wi-Fi side: this Wi-Fi's DNS gives no address for the carrier's Wi-Fi calling server; the mobile network's DNS does", v.line)
        assertEquals(CauseClass.PHONE_SIDE, v.cause)
        assertTrue(v.confident)
        val fails = CauseVerdicts.of(VerdictFeature.VOWIFI, EpdgW1.refusal(EpdgLookup.FAILS, EpdgLookup.RESOLVES), AccountState.UNKNOWN)!!
        assertEquals("Wi-Fi side: this Wi-Fi's DNS does not answer for the carrier's Wi-Fi calling server; the mobile network's DNS does", fails.line)
        // The words follow round 42 item 7: no "booked", no tariff, no number, no address.
        for (cls in EpdgClass.entries) {
            assertFalse(cls.line, Regex("(?i)booked|tarif|\\d").containsMatchIn(cls.line))
        }
    }

    @Test
    fun `the record names each resolver asked and its class, never an address`() {
        assertEquals("Wi-Fi resolver no address; mobile network's resolver resolves", EpdgW1.record(EpdgLookup.NO_ADDRESS, EpdgLookup.RESOLVES))
        assertEquals("Wi-Fi resolver fails", EpdgW1.record(EpdgLookup.FAILS, null))
        assertEquals("no resolver asked", EpdgW1.record(null, null))
    }

    @Test
    fun `a measured Wi-Fi-calling failure is an IWLAN cause, or Wi-Fi calling on over Wi-Fi with IMS not registered`() {
        assertTrue(EpdgPolicy.wifiCallingFailure(wfcOn = null, wifiConnected = false, imsRegistered = null, iwlanCause = 16388))
        assertFalse("cause 0 is a working connection", EpdgPolicy.wifiCallingFailure(true, true, true, 0))
        assertTrue(EpdgPolicy.wifiCallingFailure(wfcOn = true, wifiConnected = true, imsRegistered = false, iwlanCause = null))
        assertFalse("registered (over LTE or Wi-Fi) is no failure", EpdgPolicy.wifiCallingFailure(true, true, true, null))
        assertFalse("Wi-Fi calling off", EpdgPolicy.wifiCallingFailure(false, true, false, null))
        assertFalse("no Wi-Fi", EpdgPolicy.wifiCallingFailure(true, false, false, null))
        assertFalse("IMS state unreadable", EpdgPolicy.wifiCallingFailure(true, true, null, null))
    }

    private fun auto(failure: Boolean, wifi: Boolean = true, cellularFailing: Boolean = false, cellularInUse: Boolean = false, last: Long? = null) =
        EpdgPolicy.plan(false, true, failure, wifi, cellularFailing, cellularInUse, last, now)

    @Test
    fun `an automatic run needs a measured failure, keeps to 6 hours, and asks only the Wi-Fi resolver`() {
        assertNull(auto(failure = false))
        assertEquals(EpdgPlan(wifi = true, cellular = false), auto(failure = true))
        assertNull("within 6 hours", auto(failure = true, last = now - 5 * hour))
        assertEquals(EpdgPlan(wifi = true, cellular = false), auto(failure = true, last = now - 7 * hour))
        // The cellular resolver only when cellular data itself fails, or mobile data is the default network.
        assertEquals(EpdgPlan(wifi = true, cellular = true), auto(failure = true, cellularFailing = true))
        assertEquals(EpdgPlan(wifi = false, cellular = true), auto(failure = true, wifi = false, cellularInUse = true))
        assertNull("nothing to ask", auto(failure = true, wifi = false))
    }

    @Test
    fun `the owner's check now compares both resolvers while Wi-Fi calling is on or failing`() {
        assertEquals(EpdgPlan(wifi = true, cellular = true), EpdgPolicy.plan(true, true, false, true, false, false, now - hour, now))
        assertEquals(EpdgPlan(wifi = true, cellular = true), EpdgPolicy.plan(true, null, true, true, false, false, null, now))
        assertNull("Wi-Fi calling off and nothing failing", EpdgPolicy.plan(true, false, false, true, false, false, null, now))
        assertNull(EpdgPolicy.plan(true, null, false, true, false, false, null, now))
    }

    @Test
    fun `W2 is not built - no IKE packet and no hand-built packet to a carrier server`() {
        val sources = listOf("src/main/java", "src/readonly/java", "src/owner/java")
            .flatMap { root -> File(root).walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(sources.size > 10)
        val ike = Regex("IkeSession|android\\.net\\.ipsec|IKE_SA_INIT\\s*\\(|ikeSaInit|DatagramChannel|:\\s*(500|4500)\\b")
        for (file in sources) assertNull(file.name, ike.find(file.readText())?.value)
        // The only UDP socket is D2's query to a public resolver on port 53.
        assertEquals(listOf("ProbeNet.kt"), sources.filter { it.readText().contains("DatagramSocket") }.map { it.name })
        assertTrue(sources.single { it.name == "ProbeNet.kt" }.readText().contains("DatagramPacket(query, query.size, resolver, 53)"))
    }
}
