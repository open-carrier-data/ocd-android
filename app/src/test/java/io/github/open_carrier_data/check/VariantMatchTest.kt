package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.Fixtures.sim
import io.github.open_carrier_data.check.core.DeviceAliases
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.DeviceRecord
import io.github.open_carrier_data.check.core.DeviceRecords
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.RomFamily
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD's device records (round 17): the app shows what this phone model's own
 * file sets next to OCD's carrier summary, labels a record from another
 * software stack as the model's, and never turns a missing record into off.
 * Records shaped like the dry run of 2026-10-17 for Vodafone DE.
 */
class VariantMatchTest {
    private val vodafone = "open.26202.8cebd7550c67"
    private val s20Stock = DeviceInfo("samsung", "SM-G981B", "x1s", 13, RomFamily.STOCK)
    private val s20Lineage = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val nexus = DeviceInfo("LGE", "Nexus 5X", "bullhead", 8, RomFamily.LINEAGEOS)
    private val pixel = DeviceInfo("Google", "Pixel 6", "oriole", 16, RomFamily.STOCK)

    private val records = DeviceRecords(
        basedOnDataDigest = Fixtures.bundle.source.dataDigest!!,
        capabilities = listOf("video_calling", "volte", "vonr", "vowifi"),
        profiles = mapOf(
            vodafone to mapOf(
                "volte" to listOf(
                    DeviceRecord("conditional", "lineageos_device_overlays", "lineageos", "2025-05", devices = listOf("android:benz")),
                    DeviceRecord("off", "lineageos_device_overlays", "lineageos", "2024-12", devices = listOf("android:bullhead")),
                    DeviceRecord("on", "google_carriersettings", "stock", "2026-09", devices = listOf("android:oriole")),
                    DeviceRecord("on", "samsung_omc", "stock", "2025-02", devices = listOf("samsung:SM-G980F", "samsung:SM-G981B")),
                ),
                "vonr" to listOf(
                    DeviceRecord("on", "samsung_omc", "stock", "2025-02", basis = "samsung_vonr_switch", devices = listOf("samsung:SM-G981B")),
                ),
            ),
        ),
        devices = mapOf(
            "android:benz" to DeviceAliases(deviceNames = listOf("benz")),
            "android:bullhead" to DeviceAliases(deviceNames = listOf("bullhead"), models = listOf("Nexus 5X")),
            "android:oriole" to DeviceAliases(deviceNames = listOf("oriole")),
        ),
    )
    private val bundle: OcdBundle = Fixtures.bundle.copy(deviceRecords = records)

    private fun vodafoneReadings(device: DeviceInfo): PhoneReadings =
        readings(sim = sim(spn = "", carrierId = 1911, specificCarrierId = 1911), settings = settings(), device = device)

    private fun findings(device: DeviceInfo, data: OcdBundle = bundle) = vodafoneReadings(device).let { readings ->
        val resolution = Matcher.resolve(data, readings.sim!!.identity()!!)
        assertEquals(vodafone, resolution.mostSpecific!!.profileId)
        Findings.compare(readings, resolution, data).filter { it.code.startsWith("device_record") }
    }

    @Test
    fun `the S20's own Samsung pack is shown next to the conditional carrier summary`() {
        val found = findings(s20Stock)
        assertEquals(listOf("device_record_on:volte", "device_record_on:vonr"), found.map { it.code })
        val volte = found.first().text
        assertTrue(volte, volte.startsWith("This phone model's own file sets VoLTE (calls over 4G) on for this SIM"))
        assertTrue(volte, volte.contains("one source: Samsung's carrier pack, stock firmware, newest entry 2025-02"))
        assertTrue(volte, volte.contains("OCD's carrier summary is conditional."))
        assertTrue(volte, volte.endsWith("Configuration, not a test."))
    }

    @Test
    fun `a stock record on a LineageOS build is the model's stock firmware, not this phone`() {
        val found = findings(s20Lineage)
        assertEquals("device_record_other_stack_on:volte", found.first().code)
        val text = found.first().text
        assertTrue(text, text.startsWith("This model's stock firmware file sets VoLTE (calls over 4G) on"))
        assertTrue(text, text.contains("This phone runs LineageOS, whose own file may differ."))
        assertTrue(text, !text.contains("This phone model's own"))
    }

    @Test
    fun `a codename binds a LineageOS tree's off, and a Pixel binds by Build_DEVICE`() {
        assertEquals(listOf("device_record_off:volte"), findings(nexus).map { it.code })
        assertEquals(listOf("device_record_on:volte"), findings(pixel).map { it.code })
        val benz = findings(DeviceInfo("OnePlus", "CPH2581", "benz", 15, RomFamily.LINEAGEOS))
        assertEquals(listOf("device_record_conditional:volte"), benz.map { it.code })
        assertTrue(benz.single().text.contains("and another file of the same model disagree"))
    }

    @Test
    fun `no record gives no device line, never off`() {
        assertEquals(emptyList<Any>(), findings(DeviceInfo("Fairphone", "FP5", "FP5", 15, RomFamily.STOCK)))
        // A Samsung model the records do not name.
        assertEquals(emptyList<Any>(), findings(DeviceInfo("samsung", "SM-A505F", "a50", 14, RomFamily.STOCK)))
    }

    @Test
    fun `an ambiguous codename matches nothing`() {
        val twice = records.copy(
            devices = records.devices + ("android:oriole-clone" to DeviceAliases(deviceNames = listOf("oriole"))),
        )
        assertEquals(emptyList<String>(), twice.keysFor(pixel))
        assertEquals(listOf("samsung:SM-G981B"), records.keysFor(s20Stock))
    }

    @Test
    fun `records from another data snapshot are not used`() {
        val other = bundle.copy(deviceRecords = records.copy(basedOnDataDigest = "sha256:" + "0".repeat(64)))
        assertNull(other.records)
        assertEquals(emptyList<Any>(), findings(s20Stock, other))
    }

    @Test
    fun `the report carries the records as typed fields and passes the privacy scan`() {
        val readings = vodafoneReadings(s20Lineage)
        val resolution = Matcher.resolve(bundle, readings.sim!!.identity()!!)
        val result = Report.build(readings, resolution, Findings.compare(readings, resolution, bundle), bundle, "0.1.0", false)
        assertEquals(emptyList<Any>(), result.hits)
        val volte = result.json.jsonObject["ocd"]!!.jsonObject["device_records"]!!.jsonObject["volte"]!!.jsonObject
        assertEquals("on", volte["state"]!!.jsonPrimitive.content)
        assertEquals("samsung_omc", volte["source"]!!.jsonPrimitive.content)
        assertEquals("stock", volte["stack"]!!.jsonPrimitive.content)
        assertEquals("2025-02", volte["newest_entry"]!!.jsonPrimitive.content)
        assertEquals("false", volte["this_stack"]!!.jsonPrimitive.content)
    }

    /** The committed asset's records belong to its own data and name the owner's S20 on Vodafone DE. */
    @Test
    fun `the committed asset's device records are usable`() {
        val asset = File("src/main/assets/${OcdBundle.ASSET_NAME}")
        val shipped = asset.inputStream().use(OcdBundle::readGzip)
        val shippedRecords = shipped.records ?: return // a bundle built before the first publish has none
        assertEquals(listOf("video_calling", "volte", "vonr", "vowifi"), shippedRecords.capabilities)
        val s20 = shippedRecords.recordsFor(vodafone, "volte", s20Stock).single()
        assertEquals("on", s20.record.state)
        assertEquals("samsung_omc", s20.record.source)
        assertTrue(s20.sameStack)
    }

    @Test
    fun `the bundled records parse from the asset's JSON shape`() {
        val parsed = OcdBundle.parse(
            requireNotNull(javaClass.getResource("/fixture-bundle.json")).readText().trimEnd().removeSuffix("}") +
                ""","device_records":{"based_on_data_digest":"${Fixtures.bundle.source.dataDigest}",""" +
                """"capabilities":["video_calling","volte","vonr","vowifi"],""" +
                """"profiles":{"$vodafone":{"volte":[{"devices":["samsung:SM-G981B"],"newest_entry":"2025-02",""" +
                """"source":"samsung_omc","stack":"stock","state":"on"}]}},"devices":{}}}""",
        )
        val match = parsed.records!!.recordsFor(vodafone, "volte", s20Stock).single()
        assertTrue(match.sameStack)
        assertEquals("samsung:SM-G981B", match.deviceKey)
    }
}
