package io.github.open_carrier_data.check.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/** The owner build's merged manifest: exactly the permissions it needs, and nothing exported without a guard. */
class OwnerManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private val merged: Document by lazy {
        val config = Properties()
        javaClass.getResourceAsStream("/com/android/tools/test_config.properties")!!.use(config::load)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(config.getProperty("android_merged_manifest")))
    }

    private fun elements(tag: String): List<Element> {
        val nodes = merged.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `the owner build asks for the read-only app's permissions plus its own`() {
        val requested = elements("uses-permission").filter { it.getAttributeNS(tools, "node") != "remove" }.map { it.getAttributeNS(android, "name") }.toSet()
        val own = setOf(
            "android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.MODIFY_PHONE_STATE", "android.permission.READ_PRIVILEGED_PHONE_STATE", "android.permission.WRITE_APN_SETTINGS",
            "android.permission.DUMP", "android.permission.READ_LOGS",
        )
        val readOnly = setOf("android.permission.READ_PHONE_STATE", "android.permission.ACCESS_NETWORK_STATE", "moe.shizuku.manager.permission.API_V23")
        // WorkManager's own: wake lock and its foreground service (never started by the owner build).
        val work = setOf("android.permission.WAKE_LOCK", "android.permission.FOREGROUND_SERVICE")
        assertEquals(emptySet<String>(), requested - own - readOnly - work)
        assertTrue((own + readOnly).all { it in requested })
    }

    @Test
    fun `the application ID is the owner's own`() {
        assertEquals("io.github.open_carrier_data.check.owner", merged.documentElement.getAttribute("package"))
    }

    @Test
    fun `exported components are the launcher, the system-guarded ones and the boot receiver for protected broadcasts`() {
        val exported = listOf("activity", "provider", "receiver", "service").flatMap { elements(it) }
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "permission") }
        assertEquals("", exported["io.github.open_carrier_data.check.ui.MainActivity"])
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", exported["rikka.shizuku.ShizukuProvider"])
        assertTrue("io.github.open_carrier_data.check.owner.OwnerBootReceiver" in exported)
        assertTrue("io.github.open_carrier_data.check.owner.OwnerActivity" !in exported)
        assertTrue("io.github.open_carrier_data.check.owner.RevertAllReceiver" !in exported)
        for ((name, permission) in exported) {
            if (name.startsWith("androidx.work") || name.startsWith("androidx.profileinstaller")) {
                assertTrue("$name unguarded", permission.isNotEmpty())
            }
        }
    }
}
