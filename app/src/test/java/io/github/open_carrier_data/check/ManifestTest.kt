package io.github.open_carrier_data.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app asks for exactly two Android permissions and no INTERNET, in its
 * own manifest and in the merged manifest the build packages. The merged
 * manifest also holds Shizuku's own API_V23 permission from its provider
 * library: the Shizuku server finds client apps by that requested
 * permission and sends them its binder, so without it the deep check never
 * sees Shizuku running (found on the A50, 2026-10-13). It is defined by the
 * Shizuku app, not by Android, and grants nothing until Shizuku's own dialog.
 */
class ManifestTest {
    /** These hold for debug and release; the owner build's own manifest test is OwnerManifestTest (src/testOwner). */
    @Before
    fun readOnlyBuildsOnly() = assumeFalse(BuildConfig.OWNER)

    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"
    private val allowed = setOf("android.permission.READ_PHONE_STATE", "android.permission.ACCESS_NETWORK_STATE")

    private fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun requested(document: Document): Set<String> =
        (document.elements("uses-permission") + document.elements("uses-permission-sdk-23"))
            .filter { it.getAttributeNS(tools, "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
            .toSet()

    private val merged: File by lazy {
        val config = Properties()
        requireNotNull(javaClass.getResourceAsStream("/com/android/tools/test_config.properties")) {
            "test_config.properties missing: unitTests.isIncludeAndroidResources must be on"
        }.use(config::load)
        File(config.getProperty("android_merged_manifest")).also { assertTrue("$it", it.isFile) }
    }

    @Test
    fun `only READ_PHONE_STATE and ACCESS_NETWORK_STATE, never INTERNET`() {
        val requested = requested(parse(File("src/main/AndroidManifest.xml")))
        assertEquals(allowed, requested)
        assertFalse("android.permission.INTERNET" in requested)
    }

    @Test
    fun `the merged manifest, with Shizuku's libraries, asks for nothing more`() {
        val document = parse(merged)
        val requested = requested(document)
        assertEquals(allowed + "moe.shizuku.manager.permission.API_V23", requested)
        assertFalse("android.permission.INTERNET" in requested)
        assertEquals(emptyList<Element>(), document.elements("permission"))
    }

    @Test
    fun `every exported component but the launcher is guarded by a system-only permission`() {
        val document = parse(merged)
        val exported = listOf("activity", "provider", "receiver", "service")
            .flatMap { document.elements(it) }
            .filter { it.getAttributeNS(android, "exported") == "true" }
        val guards = exported.associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "permission") }
        assertEquals(
            mapOf(
                "io.github.open_carrier_data.check.ui.MainActivity" to "",
                "rikka.shizuku.ShizukuProvider" to "android.permission.INTERACT_ACROSS_USERS_FULL",
                "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
            ),
            guards,
        )
        val queried = document.elements("package").map { it.getAttributeNS(android, "name") }
        assertEquals(listOf("moe.shizuku.privileged.api"), queried)
    }
}
