package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.network
import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.Fixtures.sim
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.RomFamily
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

    @Test
    fun `stale data is flagged`() {
        val later = readings(checkedOn = java.time.LocalDate.parse(Fixtures.bundle.source.staleAfter).plusDays(1))
        assertEquals("data_stale", compare(later).first().code)
        assertFalse("data_stale" in codes(readings()))
    }
}
