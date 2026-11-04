package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.BuildConfig
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
 * OCD rule rounds 46 and 47: one build and one package for everyone. What runs depends on how the app is installed, never
 * on who signed it, and the emergency data test's restricted-network permission stays denied unless the ROM builder
 * turns the test on (SM10).
 */
class OneBuildTest {
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
    fun `one package, and no owner or rom build type is left`() {
        assertEquals("io.github.open_carrier_data.check", BuildConfig.APPLICATION_ID)
        assertEquals("io.github.open_carrier_data.check", merged.documentElement.getAttribute("package"))
        assertTrue(BuildConfig.BUILD_TYPE in setOf("debug", "release"))
        val gradle = File("build.gradle.kts").readText()
        for (gone in listOf("create(\"owner\")", "create(\"rom\")", "signingConfig", "applicationIdSuffix", "buildConfigField", "local.properties")) assertFalse(gone, gone in gradle)
        for (gone in listOf("src/owner", "src/rom", "src/readonly", "src/testOwner", "src/testRom")) assertFalse(gone, File(gone).exists())
    }

    @Test
    fun `no owner check is left in the code - no device, no certificate, no build name`() {
        val code = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (gone in listOf("OWNER_DEVICE", "OWNER_MODEL", "BuildConfig.TESTER", "BuildConfig.OWNER", "TesterBuild", "ownerPhone", "ShizukuPhone")) assertFalse(gone, gone in code)
    }

    @Test
    fun `a hand install keeps a launcher and does no background work, a ROM's system app has no icon`() {
        val aliases = elements(merged, "activity-alias")
        val launcher = aliases.single { it.getAttributeNS(android, "name").endsWith(".ui.Launcher") }
        assertEquals("true", launcher.getAttributeNS(android, "exported"))
        val root = "src/main/java/io/github/open_carrier_data/check"
        val hook = File("$root/OwnerHook.kt").readText()
        assertTrue(hook.substringAfter("fun onAppStart(context: Context) {").trimStart().startsWith("if (!tester(context)) return"))
        assertTrue(hook.contains("fun tester(context: Context): Boolean = OwnerAccess.privilegedGranted(context)"))
        assertTrue(hook.contains("PackageManager.COMPONENT_ENABLED_STATE_DISABLED"))
        val boot = File("$root/owner/OwnerReceivers.kt").readText().substringAfter("class OwnerBootReceiver")
        assertTrue(boot.contains("if (!OwnerAccess.privilegedGranted(context)) return"))
        assertTrue(boot.indexOf("privilegedGranted") < boot.indexOf("hideLauncher"))
    }

    @Test
    fun `the privapp file grants the owner build's privileged permissions but denies the restricted network`() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/priv-app/privapp-permissions-ocd-check.xml"))
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
        for (name in listOf("default-permissions-ocd-check.xml", "sysconfig-ocd-check.xml")) {
            val text = File("src/main/priv-app/$name").readText()
            assertTrue(name, "package=\"${BuildConfig.APPLICATION_ID}\"" in text)
            assertFalse(name, ".check.owner" in text)
        }
    }
}
