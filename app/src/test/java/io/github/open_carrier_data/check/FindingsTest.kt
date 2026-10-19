package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.deep
import io.github.open_carrier_data.check.Fixtures.network
import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.Fixtures.sim
import io.github.open_carrier_data.check.core.ApnEdited
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.IwlanHold
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.NetworkReading
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindingsTest {
    private fun compare(readings: PhoneReadings): List<Finding> {
        val resolution = readings.sim?.identity()?.let { Matcher.resolve(Fixtures.bundle, it) }
        return Findings.compare(readings, resolution, Fixtures.bundle)
    }

    private fun codes(readings: PhoneReadings) = compare(readings).map { it.code }

    @Test
    fun `no SIM gives one finding and nothing else`() {
        assertEquals(listOf("no_sim"), codes(readings(sim = null)))
    }

    @Test
    fun `no profile for the network code`() {
        assertTrue("no_profile" in codes(readings(sim = sim(mccmnc = "99999", spn = ""))))
    }

    @Test
    fun `IMS registration is never guessed`() {
        val findings = compare(readings())
        val deep = findings.single { it.code == Findings.NEEDS_DEEP_CHECK }
        assertTrue(deep.text.contains("deep check"))
        assertTrue(findings.none { it.text.contains("signed in (", ignoreCase = true) })
    }

    @Test
    fun `no hard-coded operator service status - no German MMS finding`() {
        // 26202's base profile says mms conditional; phone_check.py printed the German MMS note here.
        val findings = compare(readings(sim = sim(spn = "Vodafone.de")))
        assertTrue(findings.none { "MMS" in it.text && "German" in it.text })
        assertTrue(findings.none { it.code.contains("mms") && it.code.contains("ended") })
    }

    @Test
    fun `findings never call OCD wrong, deny carrier support or claim that something works`() {
        val scenarios = listOf(
            readings(),
            readings(settings = settings(volte = false, wfc = false, vt = true, vonr = true, mms = false)),
            readings(sim = sim(mccmnc = "26201", spn = "Telekom.de", carrierId = 3, specificCarrierId = 3)),
            readings(network = network(icon = 3, nrSignal = false, roaming = true, serving = "20404")),
            readings(sim = sim(mccmnc = "20404", spn = "")),
        )
        val banned = Regex(
            "(?i)(OCD is wrong|OCD's .* wrong|does not support|doesn't support|not supported by your carrier|" +
                "\\bworks\\b|is working|ended MMS)",
        )
        for (scenario in scenarios) {
            for (finding in compare(scenario)) {
                assertFalse("${finding.code}: ${finding.text}", banned.containsMatchIn(finding.text))
            }
        }
    }

    @Test
    fun `carrier settings off where OCD's tables turn the feature on`() {
        val codes = codes(readings(settings = settings(volte = false, wfc = false)))
        assertTrue("phone_off_ocd_conditional:volte" in codes)
        assertTrue("phone_off_ocd_supported:vowifi" in codes)
        assertFalse("no_difference" in codes)
    }

    @Test
    fun `carrier settings on where OCD has no value or says unsupported`() {
        // 26202 base: video_calling is unknown; Android's default for the VT switch is off.
        val codes = codes(readings(settings = settings(vt = true)))
        assertTrue("phone_on_ocd_unknown:video_calling" in codes)
        // enabledMMS is on by default, so on with an unknown OCD value is not a finding.
        assertFalse(codes.any { it.endsWith(":mms") })
    }

    @Test
    fun `agreeing settings give the no-difference finding`() {
        val codes = codes(readings())
        assertTrue("no_difference" in codes)
        assertFalse(codes.any { it.startsWith("phone_") })
    }

    @Test
    fun `an SPN OCD does not know quotes the README MVNO limit`() {
        val finding = compare(readings()).single { it.code == "spn_not_in_ocd" }
        assertTrue(finding.text.contains("LIDL Connect"))
        assertTrue(finding.text.contains(Fixtures.bundle.limits.mvnoMerge))
        assertFalse("spn_not_in_ocd" in codes(readings(sim = sim(spn = "Vodafone.de"))))
    }

    @Test
    fun `profiles behind hidden selectors are named`() {
        val finding = compare(readings()).single { it.code == "hidden_selectors" }
        assertTrue(finding.text.contains("ICCID"))
        assertTrue(finding.text.contains("vf nl"))
    }

    @Test
    fun `missing permission and default settings are said plainly`() {
        assertTrue("settings_need_permission" in codes(readings(permission = false, settings = null)))
        assertTrue("settings_default" in codes(readings(settings = settings(identified = false))))
    }

    @Test
    fun `roaming and a 5G icon without a 5G signal`() {
        val codes = codes(readings(network = network(icon = 3, nrSignal = false, roaming = true, serving = "20404")))
        assertTrue("roaming" in codes)
        assertTrue("five_g_icon_only" in codes)
        assertFalse("five_g_icon_only" in codes(readings(network = network(icon = 3, nrSignal = true))))
        assertFalse("five_g_icon_only" in codes(readings(network = network(data = Radio.NR))))
    }

    @Test
    fun `Samsung stock software gets the vendor IMS caveat`() {
        val samsung = DeviceInfo("samsung", "SM-A505F", "a50", 14, RomFamily.STOCK)
        assertTrue("samsung_own_ims" in codes(readings(device = samsung)))
        assertFalse("samsung_own_ims" in codes(readings(device = samsung.copy(romFamily = RomFamily.LINEAGEOS))))
    }

    private val samsung = DeviceInfo("samsung", "SM-A505F", "a50", 14, RomFamily.STOCK)

    private fun deepCodes(deep: DeepCheckReading, network: NetworkReading = network()) =
        compare(readings(device = samsung, network = network, deep = deep))

    @Test
    fun `the A50 case - Wi-Fi calling on, signed in over 4G only, and the ePDG name does not resolve`() {
        val findings = deepCodes(deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE))
        val codes = findings.map { it.code }
        assertTrue("volte_signed_in" in codes)
        assertFalse(Findings.NEEDS_DEEP_CHECK in codes)
        val dns = findings.single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertTrue(dns.text, "dns.google" in dns.text && "Private DNS" in dns.text && "router" in dns.text)
        assertTrue(dns.text, "epdg.epc.mnc002.mcc262.pub.3gppnetwork.org" in dns.text)
        assertTrue(dns.text, "OCD's answer for Wi-Fi calling is supported" in dns.text)
    }

    @Test
    fun `the A50 after the fix - signed in over Wi-Fi`() {
        val codes = deepCodes(deep(transport = ImsTransport.IWLAN, wifiCalling = WifiCallingSwitch.ON)).map { it.code }
        assertTrue("wifi_calling_signed_in" in codes)
        assertFalse(codes.any { it.startsWith("wifi_calling_not_signed_in") })
    }

    @Test
    fun `not signed in for calls over 4G, with possible causes where OCD's tables turn VoLTE on`() {
        val finding = deepCodes(deep(registered = false)).single { it.code == "volte_not_signed_in" }
        assertTrue(finding.text, finding.text.startsWith("Not signed in for calls over 4G"))
        assertTrue(finding.text, "not in the plan" in finding.text)
        val on3g = deepCodes(deep(registered = false), network(data = 3)).map { it.code }
        assertTrue("ims_not_signed_in" in on3g)
    }

    @Test
    fun `Wi-Fi calling on without Wi-Fi, or with a working name, gets the plain causes`() {
        val noWifi = deepCodes(deep(wifiCalling = WifiCallingSwitch.ON, lookup = LookupNetwork.MOBILE)).map { it.code }
        assertTrue("wifi_calling_on_no_wifi" in noWifi)
        val plain = deepCodes(deep(wifiCalling = WifiCallingSwitch.ON)).single { it.code == "wifi_calling_not_signed_in" }
        assertTrue("back-off" in plain.text)
    }

    @Test
    fun `an ePDG name that does not resolve on Wi-Fi is said even with Wi-Fi calling off`() {
        val codes = deepCodes(deep(epdg = EpdgName.DOES_NOT_RESOLVE)).map { it.code }
        assertTrue("epdg_name_not_resolving" in codes)
        assertFalse("epdg_name_not_resolving" in deepCodes(deep(epdg = EpdgName.DOES_NOT_RESOLVE, lookup = LookupNetwork.MOBILE)).map { it.code })
        val offline = deepCodes(deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE, lookup = LookupNetwork.NONE))
        assertFalse(offline.any { "epdg" in it.code })
        assertTrue("wifi_calling_on_no_wifi" in offline.map { it.code })
        val withPrivateDns = deepCodes(deep(epdg = EpdgName.DOES_NOT_RESOLVE, privateDns = true)).single { it.code == "epdg_name_not_resolving" }
        assertTrue("already on" in withPrivateDns.text)
    }

    @Test
    fun `no Samsung IMS, an unreadable IMS, signed in without calls, and a hand-made APN`() {
        assertTrue("ims_not_found" in deepCodes(deep(source = ImsSource.NOT_FOUND)).map { it.code })
        assertTrue("ims_unreadable" in deepCodes(deep(source = ImsSource.UNREADABLE)).map { it.code })
        assertTrue("ims_no_task" in deepCodes(deep(registered = null)).map { it.code })
        assertTrue("ims_signed_in_without_calls" in deepCodes(deep(services = listOf(ImsService.SMSIP))).map { it.code })
        assertTrue("vonr_signed_in" in deepCodes(deep(transport = ImsTransport.NR)).map { it.code })
        val apns = Fixtures.a50Apns.copy(rows = Fixtures.a50Apns.rows.map { it.copy(edited = ApnEdited.BY_USER) })
        assertTrue("apn_edited_by_user" in deepCodes(deep(apns = apns)).map { it.code })
    }

    @Test
    fun `deep findings never say a feature works, that OCD is wrong, or that a carrier lacks a feature`() {
        val scenarios = listOf(
            deep(),
            deep(registered = false),
            deep(registered = null),
            deep(transport = ImsTransport.IWLAN, wifiCalling = WifiCallingSwitch.ON),
            deep(wifiCalling = WifiCallingSwitch.ON, epdg = EpdgName.DOES_NOT_RESOLVE, privateDns = true),
            deep(wifiCalling = WifiCallingSwitch.ON, lookup = LookupNetwork.MOBILE),
            deep(source = ImsSource.NOT_FOUND, epdg = EpdgName.DOES_NOT_RESOLVE),
        )
        val banned = Regex("(?i)(OCD is wrong|OCD's .* wrong|does not support|doesn't support|\\bworks\\b|is working|calls work)")
        for (scenario in scenarios) {
            for (finding in deepCodes(scenario)) {
                assertFalse("${finding.code}: ${finding.text}", banned.containsMatchIn(finding.text))
                assertTrue(finding.code, Regex("^[a-z_]+(:[a-z_]+)?$").matches(finding.code))
            }
        }
    }

    @Test
    fun `stale data is flagged`() {
        val later = readings(checkedOn = java.time.LocalDate.parse(Fixtures.bundle.source.staleAfter).plusDays(1))
        assertEquals("data_stale", compare(later).first().code)
        assertFalse("data_stale" in codes(readings()))
    }

    // --- Android's own IMS (LineageOS), the S20 with the LIDL SIM ----------------------------------

    private fun s20(deep: DeepCheckReading, network: NetworkReading = network()) =
        compare(readings(device = Fixtures.s20Lineage, network = network, deep = deep))

    @Test
    fun `the S20 on 2026-10-19 - signed in over LTE though the log says Wi-Fi, the phone's own lookup empty, held back`() {
        val findings = s20(Fixtures.s20Deep())
        val codes = findings.map { it.code }
        val volte = findings.single { it.code == "volte_signed_in" }
        assertTrue(volte.text, "still names Wi-Fi (IWLAN)" in volte.text && "IMS data connection is on 4G (LTE)" in volte.text)
        val dns = findings.single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertTrue(dns.text, "does not resolve over Wi-Fi" in dns.text)
        assertTrue(dns.text, "got no address in the phone's own lookup 39 minutes ago" in dns.text)
        assertTrue(dns.text, "resolver in the local network (usually the router)" in dns.text)
        assertTrue(dns.text, "The phone's own lookup is the one Wi-Fi calling uses." in dns.text)
        val held = findings.single { it.code == "wifi_calling_held_back" }
        assertTrue(held.text, "for about 21 more minutes by Android's retry wait" in held.text)
        assertTrue(held.text, "IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED" in held.text)
        assertFalse(Findings.NEEDS_DEEP_CHECK in codes)
        assertFalse("samsung_own_ims" in codes)
    }

    @Test
    fun `the phone's own failed lookup counts even when ping resolves`() {
        val dns = s20(Fixtures.s20Deep(ping = EpdgName.RESOLVES)).single { it.code == "wifi_calling_not_signed_in_epdg_dns" }
        assertFalse(dns.text, "does not resolve over" in dns.text)
        assertTrue(dns.text, "got no address in the phone's own lookup" in dns.text)
        // A lookup that got an address and a resolving ping: no DNS finding.
        val ok = s20(Fixtures.s20Deep(ping = EpdgName.RESOLVES, lookupResolved = true, heldBack = null)).map { it.code }
        assertTrue(ok.toString(), "wifi_calling_not_signed_in" in ok)
        assertFalse("wifi_calling_held_back" in ok)
    }

    @Test
    fun `signed in over Wi-Fi - no DNS or hold finding, even with an old failure in the log`() {
        val codes = s20(Fixtures.s20Deep(transport = ImsTransport.IWLAN, logTransport = null)).map { it.code }
        assertTrue("wifi_calling_signed_in" in codes)
        assertFalse(codes.any { it.startsWith("wifi_calling_not_signed_in") || it == "wifi_calling_held_back" })
    }

    @Test
    fun `not signed in on an LTE cell without VoPS says so, with the last sign-out reason`() {
        val finding = s20(Fixtures.s20Deep(registered = false, vops = LteVops.NOT_SUPPORTED)).single { it.code == "volte_not_signed_in_no_vops" }
        assertTrue(finding.text, "does not offer voice over IMS (VoPS)" in finding.text)
        assertTrue(finding.text, "Android's state: not registered" in finding.text)
        assertTrue(finding.text, "Last sign-out reason: 0 (CODE_UNSPECIFIED)." in finding.text)
        val withVops = s20(Fixtures.s20Deep(registered = false)).single { it.code == "volte_not_signed_in" }
        assertTrue(withVops.text, "Android's IMS is not registered" in withVops.text)
    }

    @Test
    fun `Android's IMS findings keep the wording rules`() {
        val banned = Regex("(?i)(OCD is wrong|OCD's .* wrong|does not support|doesn't support|\\bworks\\b|is working|calls work)")
        val scenarios = listOf(
            Fixtures.s20Deep(),
            Fixtures.s20Deep(registered = false, vops = LteVops.NOT_SUPPORTED),
            Fixtures.s20Deep(transport = ImsTransport.WWAN, logTransport = null),
            Fixtures.s20Deep(ping = EpdgName.RESOLVES, lookupResolved = true, heldBack = IwlanHold.NETWORK_BACKOFF_TIMER),
        )
        for (scenario in scenarios) {
            for (finding in s20(scenario)) {
                assertFalse("${finding.code}: ${finding.text}", banned.containsMatchIn(finding.text))
                assertTrue(finding.code, Regex("^[a-z_]+(:[a-z_]+)?$").matches(finding.code))
            }
        }
        assertTrue("ims_signed_in_mobile" in s20(Fixtures.s20Deep(transport = ImsTransport.WWAN, logTransport = null)).map { it.code })
    }
}
