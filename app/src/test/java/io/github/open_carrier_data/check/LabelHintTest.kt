package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.LabelHints
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimReading
import io.github.open_carrier_data.check.core.Gid1Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD round 22: a `supported` summary next to `off` device records gets a
 * hint. fixture-o2-step2.json holds O2 DE (open.26203.dbb2be7109a6) with its
 * VoLTE and video calling records and catalog aliases as the public
 * carrier-variants.json of commit 885c0ba3 has them (via the app's bundled
 * asset), and video calling set to `supported`, as round 22's step 2 makes it
 * (correctness ruling F7: 11 LineageOS models off, the S20 without a record).
 */
class LabelHintTest {
    private val bundle: OcdBundle by lazy {
        OcdBundle.parse(requireNotNull(javaClass.getResource("/fixture-o2-step2.json")).readText())
    }
    private val o2 = "open.26203.dbb2be7109a6"
    private val s20Lineage = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val joy3Lineage = DeviceInfo("Teracube", "Joy 3", "casuarina", 15, RomFamily.LINEAGEOS)
    private val fp5Lineage = DeviceInfo("Fairphone", "FP5", "FP5", 15, RomFamily.LINEAGEOS)

    private val o2Sim = SimReading(0, "26203", "o2 - de", 1984, "O2", 1984, "O2", null, Gid1Status.EMPTY)

    private fun o2Readings(device: DeviceInfo, vt: Boolean = false): PhoneReadings =
        readings(sim = o2Sim, settings = settings(vt = vt), device = device)

    private fun findings(readings: PhoneReadings) = readings.sim!!.identity()!!.let { identity ->
        val resolution = Matcher.resolve(bundle, identity)
        assertEquals(o2, resolution.mostSpecific!!.profileId)
        Findings.compare(readings, resolution, bundle)
    }

    @Test
    fun `O2 video calling on the S20 without a record - the hint names 11 models and goes into the phone-off finding`() {
        val readings = o2Readings(s20Lineage)
        val resolution = Matcher.resolve(bundle, readings.sim!!.identity()!!)
        assertEquals(emptyList<Any>(), bundle.records!!.recordsFor(o2, "video_calling", s20Lineage))
        val hint = LabelHints.offRecords(bundle, resolution, s20Lineage, "video_calling")!!
        assertEquals(11, hint.offModels)
        assertNull(hint.thisModel)
        assertEquals(
            "11 phone models' own files turn Video calling off for this SIM " +
                "(one maker's or maintainer's files; OCD does not count that as proof).",
            hint.text,
        )
        val found = findings(readings)
        val phoneOff = found.single { it.code == "phone_off_ocd_supported:video_calling" }
        assertTrue(phoneOff.text, hint.text in phoneOff.text)
        assertTrue(phoneOff.text, phoneOff.text.startsWith("Video calling: OCD's answer is supported; this phone's carrier settings have it off"))
        // No device line for the S20: a missing record is never off.
        assertFalse(found.any { it.code.startsWith("device_record") && it.code.endsWith(":video_calling") })
    }

    @Test
    fun `a model whose own file is off is told that its file comes first`() {
        val found = findings(o2Readings(joy3Lineage, vt = true))
        val own = found.single { it.code == "device_record_off:video_calling" }
        assertTrue(own.text, own.text.startsWith("This phone model's own file sets Video calling off for this SIM"))
        assertTrue(own.text, "OCD's carrier summary is supported." in own.text)
        assertTrue(own.text, "11 phone models' own files turn Video calling off" in own.text)
        assertTrue(own.text, "This phone model's own file is one of them; for this model, its file comes before OCD's summary." in own.text)
        // The switch is on here, so there is no phone-off finding to carry it.
        assertFalse(found.any { it.code == "phone_off_ocd_supported:video_calling" })
    }

    @Test
    fun `a model whose own file is on still hears about the other models`() {
        val found = findings(o2Readings(fp5Lineage, vt = true))
        val own = found.single { it.code == "device_record_on:video_calling" }
        assertTrue(own.text, "11 phone models' own files turn Video calling off" in own.text)
        assertFalse(own.text, "one of them" in own.text)
    }

    @Test
    fun `no hint without an off record, for a conditional summary, or for another switch`() {
        val resolution = Matcher.resolve(bundle, o2Sim.identity()!!)
        // O2 VoLTE is supported, but every record there says on.
        assertNull(LabelHints.offRecords(bundle, resolution, s20Lineage, "volte"))
        // Before step 2 the label was conditional: the round-17 wording stands, no hint.
        val before = bundle.copy(profiles = bundle.profiles.map { it.copy(capabilities = it.capabilities + ("video_calling" to "conditional")) })
        val beforeResolution = Matcher.resolve(before, o2Sim.identity()!!)
        assertNull(LabelHints.offRecords(before, beforeResolution, s20Lineage, "video_calling"))
        val phoneOff = Findings.compare(o2Readings(s20Lineage), beforeResolution, before)
            .single { it.code == "phone_off_ocd_conditional:video_calling" }
        assertFalse(phoneOff.text, "phone models' own files" in phoneOff.text)
        // Only the three step-2 switches; records from another data snapshot are not used.
        assertNull(LabelHints.offRecords(bundle, resolution, s20Lineage, "vonr"))
        val foreign = bundle.copy(deviceRecords = bundle.deviceRecords!!.copy(basedOnDataDigest = "sha256:" + "0".repeat(64)))
        assertNull(LabelHints.offRecords(foreign, resolution, s20Lineage, "video_calling"))
    }

    @Test
    fun `the hint keeps round 9's wording rules and the report stays clean`() {
        val banned = Regex("(?i)(OCD is wrong|OCD's .* wrong|does not support|doesn't support|\\bworks\\b|is working)")
        for (device in listOf(s20Lineage, joy3Lineage, fp5Lineage)) {
            val readings = o2Readings(device)
            val resolution = Matcher.resolve(bundle, readings.sim!!.identity()!!)
            val found = Findings.compare(readings, resolution, bundle)
            for (finding in found) assertFalse("${finding.code}: ${finding.text}", banned.containsMatchIn(finding.text))
            val report = Report.build(readings, resolution, found, bundle, "0.2.0", false)
            assertEquals(emptyList<Any>(), report.hits)
        }
    }
}
