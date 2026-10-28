package io.github.open_carrier_data.check.rom

import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.owner.OwnerAccess
import io.github.open_carrier_data.check.owner.OwnerTester
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OCD round 31: the build other ROMs ship (build type `rom`). It runs the owner build's tester code
 * under the ROM build's policy: the active tests wait for the person's opt-in (SM9), nothing is
 * sent by itself and no token is taken (SM11), and the emergency data test's restricted-network
 * permission stays denied unless the ROM builder turns the test on (SM10).
 */
class RomBuildTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private val merged: Document by lazy {
        val config = Properties()
        javaClass.getResourceAsStream("/com/android/tools/test_config.properties")!!.use(config::load)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(config.getProperty("android_merged_manifest")))
    }

    private fun elements(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `this is the ROM build, never the owner's`() {
        assertEquals("rom", BuildConfig.TESTER)
        assertFalse(BuildConfig.OWNER)
        // The writer package CarrierSettings's OCD_WRITER_PACKAGE names beside the owner's (spec R31.11): the read-only app's own.
        assertEquals("io.github.open_carrier_data.check", BuildConfig.APPLICATION_ID)
        assertEquals(TesterBuild.ROM, OwnerTester.build)
        assertEquals("io.github.open_carrier_data.check", merged.documentElement.getAttribute("package"))
    }

    @Test
    fun `out of the box the active tests are off and nothing is sent`() {
        val policy = OwnerTester.policy(OwnerState())
        assertFalse(policy.activeTests)
        assertFalse(policy.sendsWithToken)
    }

    @Test
    fun `the privapp file grants the owner build's privileged permissions but denies the restricted network`() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/rom/priv-app/privapp-permissions-ocd-check-rom.xml"))
        val block = xml.getElementsByTagName("privapp-permissions").item(0) as Element
        assertEquals(BuildConfig.APPLICATION_ID, block.getAttribute("package"))
        val granted = block.getElementsByTagName("permission").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttribute("name") } }.toSet()
        val denied = block.getElementsByTagName("deny-permission").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttribute("name") } }.toSet()
        val restricted = "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS"
        assertEquals(setOf(restricted), denied)
        assertEquals(OwnerAccess.PRIVILEGED_PERMISSIONS.toSet() - restricted, granted)
        // On an enforcing ROM every requested privileged permission must be granted or denied, or system_server stops at boot.
        val requested = elements(merged, "uses-permission").filter { it.getAttributeNS(tools, "node") != "remove" }.map { it.getAttributeNS(android, "name") }.toSet()
        assertTrue(OwnerAccess.PRIVILEGED_PERMISSIONS.all { it in requested })
        for (name in listOf("default-permissions-ocd-check-rom.xml", "sysconfig-ocd-check-rom.xml")) {
            val text = File("src/rom/priv-app/$name").readText()
            assertTrue(name, "package=\"${BuildConfig.APPLICATION_ID}\"" in text)
            assertFalse(name, ".check.owner" in text)
        }
    }
}
