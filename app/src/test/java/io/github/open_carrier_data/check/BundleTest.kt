package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.FiveG
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class BundleTest {
    @Test
    fun `the fixture parses with the snapshot dates and README limits`() {
        val bundle = Fixtures.bundle
        assertEquals(OcdBundle.FORMAT, bundle.format)
        assertTrue(Regex("^[0-9a-f]{40}$").matches(bundle.source.commit))
        assertEquals(setOf("supported", "conditional", "unsupported", "unknown"), bundle.limits.valueMeanings.keys)
        assertTrue(bundle.limits.meaning(CapabilityValue.SUPPORTED).contains("your plan includes it"))
        assertTrue(bundle.limits.gates.contains("approved phone models"))
        assertTrue(bundle.limits.tablesNotTests.startsWith("A capability value says what carrier tables configure, not what works."))
        assertFalse(bundle.isStale(LocalDate.parse(bundle.source.staleAfter)))
        assertTrue(bundle.isStale(LocalDate.parse(bundle.source.staleAfter).plusDays(1)))
    }

    /** The committed asset is what the app ships; it must parse with the app's own reader. */
    @Test
    fun `the committed asset parses and resolves a real SIM`() {
        val asset = File("src/main/assets/${OcdBundle.ASSET_NAME}")
        assertTrue("run the test from the app module: ${asset.absolutePath}", asset.isFile)
        val bundle = asset.inputStream().use(OcdBundle::readGzip)
        assertTrue(bundle.profiles.size > 1000)
        assertEquals(bundle.profiles.map { it.profileId }.sorted(), bundle.profiles.map { it.profileId })
        val lidl = Matcher.resolve(bundle, SimIdentity("26202", "LIDL Connect", "", setOf(2397L)))
        assertTrue(lidl.stack.isNotEmpty())
        assertEquals(0, lidl.mostSpecific!!.match.specificity)
    }

    @Test
    fun `5G is read as SA, NSA with a signal, or an icon alone`() {
        assertEquals(FiveG.SA, FiveG.classify(Fixtures.network(data = Radio.NR)))
        assertEquals(FiveG.NSA_WITH_NR_SIGNAL, FiveG.classify(Fixtures.network(icon = 3, nrSignal = true)))
        assertEquals(FiveG.NSA_ICON_ONLY, FiveG.classify(Fixtures.network(icon = 5, nrSignal = false)))
        assertEquals(FiveG.NR_SIGNAL_NO_ICON, FiveG.classify(Fixtures.network(icon = 0, nrSignal = true)))
        assertEquals(FiveG.NONE, FiveG.classify(Fixtures.network()))
        assertEquals(FiveG.NONE, FiveG.classify(Fixtures.network(data = 3)))
        assertEquals(FiveG.UNKNOWN, FiveG.classify(null))
        assertEquals("lte", Radio.wire(Radio.LTE))
        assertEquals("nr_nsa", Radio.iconWire(3))
    }

    @Test
    fun `ROM family comes from public build fields only as a family`() {
        assertEquals(RomFamily.LINEAGEOS, RomFamily.detect("lineage_x1s-userdebug 16 BP4A eng.someone", null, "lineage_x1s", "userdebug", "test-keys"))
        assertEquals(RomFamily.OTHER, RomFamily.detect("BP2A.250805.005", null, "x1s", "userdebug", "release-keys"))
        assertEquals(RomFamily.STOCK, RomFamily.detect("A505FDDS3AYB3", "samsung/a50/a50:14", "a50", "user", "release-keys"))
        // The owner's S20: LineageOS 23.2 with Samsung's stock fingerprint, seen only through the system feature.
        assertEquals(
            RomFamily.LINEAGEOS,
            RomFamily.detect(
                "BP4A.251205.006", "samsung/x1sxxx/x1s:11/RP1A.200720.012/G981BXXSNHYB1:user/release-keys",
                "x1sxxx", "userdebug", "release-keys", lineageFeature = true,
            ),
        )
    }
}
