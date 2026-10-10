package io.github.open_carrier_data.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own manifest asks for exactly two permissions and no INTERNET.
 * (The merged manifest of a build is checked in the README with aapt2.)
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun `only READ_PHONE_STATE and ACCESS_NETWORK_STATE, never INTERNET`() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName("uses-permission")
        val requested = (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttributeNS("http://schemas.android.com/tools", "node") != "remove" }
            .map { it.getAttributeNS(android, "name") }
            .toSet()
        assertEquals(setOf("android.permission.READ_PHONE_STATE", "android.permission.ACCESS_NETWORK_STATE"), requested)
        assertFalse("android.permission.INTERNET" in requested)
    }
}
