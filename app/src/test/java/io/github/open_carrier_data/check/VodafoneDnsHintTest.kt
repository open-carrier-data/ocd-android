package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.deep
import io.github.open_carrier_data.check.Fixtures.network
import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.sim
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.IwlanLogParser
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.PrivateDns
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimReading
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import io.github.open_carrier_data.check.core.WifiResolver
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint for Vodafone Germany's DNS location filter on its ePDG name (OCD
 * Lab research note ocd-lab-20261018, measured 2026-10-19). Inputs: OCD Lab's
 * fixture of the S20's empty ePDG lookup (lab/iwlan_lidl_selector_failed.log)
 * and the A50's Samsung readings.
 */
class VodafoneDnsHintTest {
    private val code = "vodafone_de_epdg_dns_filter"
    private val selectorFailed = requireNotNull(javaClass.getResource("/lab/iwlan_lidl_selector_failed.log")).readText()
    private val a50 = DeviceInfo("samsung", "SM-A505F", "a50", 14, RomFamily.STOCK)
    private val telekom = sim(mccmnc = "26201", spn = "Telekom.de", carrierId = 3, specificCarrierId = 3)

    private fun compare(deep: DeepCheckReading, device: DeviceInfo = a50, sim: SimReading = sim()): List<Finding> {
        val readings = readings(device = device, network = network(), sim = sim, deep = deep)
        val resolution = readings.sim?.identity()?.let { Matcher.resolve(Fixtures.bundle, it) }
        return Findings.compare(readings, resolution, Fixtures.bundle)
    }

    private fun hint(deep: DeepCheckReading, device: DeviceInfo = a50, sim: SimReading = sim()): Finding? =
        compare(deep, device, sim).singleOrNull { it.code == code }

    /** The S20 on LineageOS with the lab's log: the phone's own lookup of the 26202 name got no address. */
    private fun s20FromLabLog(ping: EpdgName = EpdgName.RESOLVES, privateDns: PrivateDns? = PrivateDns.NOT_IN_USE): DeepCheckReading {
        val name = HomeNetwork.of("26202")!!.epdgFqdn
        val events = IwlanLogParser.events(selectorFailed.lineSequence(), name)
        val iwlan = IwlanLogParser.summary(events, events.last().at + 10 * 60)
        return Fixtures.s20Deep(ping = ping).copy(iwlan = iwlan, privateDns = privateDns, privateDnsActive = privateDns?.let { it != PrivateDns.NOT_IN_USE })
    }

    @Test
    fun `the lab's empty lookup on a LIDL SIM gives the hint with the cause, both fixes and the privacy cost`() {
        val findings = compare(s20FromLabLog(), device = Fixtures.s20Lineage)
        val codes = findings.map { it.code }
        val hint = findings.single { it.code == code }
        // Named right after the DNS finding, which then leaves the advice to it.
        assertEquals(codes.indexOf("wifi_calling_not_signed_in_epdg_dns") + 1, codes.indexOf(code))
        val dns = findings.single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertTrue(dns.text, "The next finding names the likely cause on this SIM's network and the fixes." in dns.text)
        assertFalse(dns.text, "Set Private DNS to a public resolver" in dns.text)
        val text = hint.text
        for (part in listOf(
            "Likely cause",
            "epdg.epc.mnc002.mcc262.pub.3gppnetwork.org",
            "epdg.epc.drz1.vodafone-ip.de",
            "only to lookups they place in Germany",
            "EDNS Client Subnet (ECS)",
            "without any refusal by the carrier",
            "Seen on this phone: the phone's own lookup of that name got no address.",
            "asks the router",
            "internet provider's own DNS servers (AVM's advice",
            "dns11.quad9.net (Quad9 with ECS) or dns.google",
            "Settings, Network & internet, Private DNS",
            "the first three parts of your IP address",
            "This is a likely cause, not a proof",
            "does not look the name up itself",
        )) {
            assertTrue("$part\n$text", part in text)
        }
    }

    @Test
    fun `the same readings on a SIM of another network give no hint and keep the general advice`() {
        val findings = compare(s20FromLabLog(), device = Fixtures.s20Lineage, sim = telekom)
        assertTrue(findings.none { it.code == code })
        // A failing lookup on another network's SIM keeps the general advice.
        val failing = compare(deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE), sim = telekom)
        assertTrue(failing.none { it.code == code })
        val dns = failing.single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertTrue(dns.text, "Set Private DNS to a public resolver, for example dns.google" in dns.text)
    }

    @Test
    fun `the A50 case on Samsung's IMS - the shell's lookup over Wi-Fi failed, Samsung's settings path`() {
        val hint = hint(deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE).copy(privateDns = PrivateDns.NOT_IN_USE))!!
        assertTrue(hint.text, "Seen on this phone: the lookup over this Wi-Fi did not resolve it." in hint.text)
        assertTrue(hint.text, "Settings, Connections, More connection settings, Private DNS" in hint.text)
        // A failed shell lookup over mobile data says nothing about a Wi-Fi's resolver.
        assertNull(hint(deep(epdg = EpdgName.DOES_NOT_RESOLVE, lookup = LookupNetwork.MOBILE)))
        assertNull(hint(deep(epdg = EpdgName.DOES_NOT_RESOLVE, lookup = LookupNetwork.NONE)))
    }

    @Test
    fun `signed in over Wi-Fi - no hint, whatever the resolver`() {
        val signedIn = deep(transport = ImsTransport.IWLAN, wifiCalling = WifiCallingSwitch.ON).copy(privateDns = PrivateDns.QUAD9)
        assertNull(hint(signedIn))
        assertNull(hint(s20FromLabLog().copy(ims = Fixtures.s20Deep(transport = ImsTransport.IWLAN, logTransport = null).ims), Fixtures.s20Lineage))
    }

    @Test
    fun `Private DNS on a service without ECS gives the hint until a lookup on the phone gets the address`() {
        for (noEcs in listOf(PrivateDns.QUAD9, PrivateDns.CLOUDFLARE)) {
            val unknown = hint(deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.UNKNOWN).copy(privateDns = noEcs))
            assertTrue("$noEcs", unknown != null && "sends no ECS" in unknown.text && noEcs.plain in unknown.text)
            assertFalse(unknown!!.text, "Seen on this phone" in unknown.text)
            // The lookup through that server got the address: the evidence wins over the class.
            assertNull(hint(deep(epdg = EpdgName.RESOLVES).copy(privateDns = noEcs)))
            assertNull(hint(deep(epdg = EpdgName.RESOLVES, lookup = LookupNetwork.MOBILE).copy(privateDns = noEcs)))
        }
    }

    @Test
    fun `the A50 now - Private DNS dns_google and a resolving lookup give no hint, a failing one says it is already set`() {
        assertNull(hint(deep(epdg = EpdgName.RESOLVES).copy(privateDns = PrivateDns.GOOGLE, privateDnsActive = true)))
        val failing = hint(s20FromLabLog(privateDns = PrivateDns.GOOGLE), Fixtures.s20Lineage)!!
        assertTrue(failing.text, "already dns.google (Google), which passes ECS and usually gets the address" in failing.text)
        // With a Private DNS hostname the Wi-Fi's router is not asked, so the DNS finding does not name it.
        val dns = compare(s20FromLabLog(privateDns = PrivateDns.GOOGLE), Fixtures.s20Lineage).single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertFalse(dns.text, "resolver in the local network" in dns.text)
        val quad9Ecs = hint(s20FromLabLog(privateDns = PrivateDns.QUAD9_ECS), Fixtures.s20Lineage)!!
        assertTrue(quad9Ecs.text, "already dns11.quad9.net (Quad9 with ECS)" in quad9Ecs.text)
        // Without a failure, an ECS server gives no hint at all.
        assertNull(hint(deep(epdg = EpdgName.UNKNOWN).copy(privateDns = PrivateDns.QUAD9_ECS)))
        val unknownServer = hint(s20FromLabLog(privateDns = PrivateDns.OTHER), Fixtures.s20Lineage)!!
        assertTrue(unknownServer.text, "which this app does not know" in unknownServer.text)
        assertNull(hint(deep(epdg = EpdgName.UNKNOWN).copy(privateDns = PrivateDns.OTHER)))
    }

    @Test
    fun `a public resolver on the Wi-Fi gives the hint unless Private DNS overrides it or the lookup resolved`() {
        val public = deep(epdg = EpdgName.UNKNOWN).copy(wifiResolver = WifiResolver.PUBLIC, privateDns = PrivateDns.NOT_IN_USE)
        assertTrue(hint(public)!!.text.contains("This Wi-Fi gives the phone a resolver outside the local network"))
        assertTrue(hint(public.copy(wifiResolver = WifiResolver.MIXED, privateDns = PrivateDns.AUTOMATIC)) != null)
        assertNull(hint(public.copy(epdgName = EpdgName.RESOLVES)))
        assertNull(hint(public.copy(privateDns = PrivateDns.GOOGLE)))
        // A router as the resolver, without a failed lookup, is no reason by itself.
        assertNull(hint(public.copy(wifiResolver = WifiResolver.LOCAL)))
        assertNull(hint(public.copy(lookupNetwork = LookupNetwork.MOBILE)))
    }

    @Test
    fun `the hint keeps the wording rules`() {
        val banned = Regex("(?i)(OCD is wrong|OCD's .* wrong|does not support|doesn't support|\\bworks\\b|is working|calls work|\\bproves?\\b)")
        val scenarios = listOf(
            s20FromLabLog() to Fixtures.s20Lineage,
            s20FromLabLog(privateDns = PrivateDns.GOOGLE) to Fixtures.s20Lineage,
            deep(epdg = EpdgName.UNKNOWN).copy(privateDns = PrivateDns.QUAD9) to a50,
            deep(epdg = EpdgName.UNKNOWN).copy(wifiResolver = WifiResolver.PUBLIC, privateDns = PrivateDns.NOT_IN_USE) to a50,
        )
        for ((deep, device) in scenarios) {
            val hint = hint(deep, device)!!
            assertFalse(hint.text, banned.containsMatchIn(hint.text))
            assertTrue(Regex("^[a-z_]+$").matches(hint.code))
        }
    }

    @Test
    fun `Private DNS is classed from LinkProperties and the hostname is never kept`() {
        assertEquals(PrivateDns.GOOGLE, PrivateDns.classify(true, "dns.google"))
        assertEquals(PrivateDns.GOOGLE, PrivateDns.classify(true, " DNS.Google. "))
        assertEquals(PrivateDns.QUAD9_ECS, PrivateDns.classify(true, "dns11.quad9.net"))
        assertEquals(PrivateDns.QUAD9, PrivateDns.classify(true, "dns.quad9.net"))
        assertEquals(PrivateDns.CLOUDFLARE, PrivateDns.classify(true, "one.one.one.one"))
        assertEquals(PrivateDns.CLOUDFLARE, PrivateDns.classify(null, "1dot1dot1dot1.cloudflare-dns.com"))
        // A provider name with a personal configuration ID is only "other".
        assertEquals(PrivateDns.OTHER, PrivateDns.classify(true, "a1b2c3.dns.nextdns.io"))
        assertEquals(PrivateDns.AUTOMATIC, PrivateDns.classify(true, null))
        assertEquals(PrivateDns.NOT_IN_USE, PrivateDns.classify(false, ""))
        assertNull(PrivateDns.classify(null, null))
        assertTrue(PrivateDns.QUAD9.strict && !PrivateDns.AUTOMATIC.strict && !PrivateDns.NOT_IN_USE.strict)
        // The typed reading has no field a hostname could land in.
        val encoded = deep().copy(privateDns = PrivateDns.OTHER).encode()
        assertTrue(encoded, "\"privateDns\":\"OTHER\"" in encoded)
        assertEquals(PrivateDns.OTHER, DeepCheckReading.decode(encoded).privateDns)
    }

    @Test
    fun `the report holds the Private DNS class and the hint's code, and passes the scan`() {
        val readings = readings(device = Fixtures.s20Lineage, deep = s20FromLabLog(privateDns = PrivateDns.OTHER))
        val resolution = readings.sim?.identity()?.let { Matcher.resolve(Fixtures.bundle, it) }
        val findings = Findings.compare(readings, resolution, Fixtures.bundle)
        val result = Report.build(readings, resolution, findings, Fixtures.bundle, "0.3.0", false)
        assertEquals(emptyList<Any>(), result.hits)
        assertEquals(emptyList<String>(), Report.unexpectedKeys(result.json))
        assertEquals("other", result.json["wifi_calling"]!!.jsonObject["private_dns"]!!.jsonPrimitive.content)
        assertTrue(code in result.json["findings"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
