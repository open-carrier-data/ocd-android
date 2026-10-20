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
            "android.permission.MODIFY_PHONE_STATE", "android.permission.READ_PRIVILEGED_PHONE_STATE", "android.permission.READ_PRECISE_PHONE_STATE",
            "android.permission.WRITE_APN_SETTINGS", "android.permission.DUMP", "android.permission.PACKAGE_USAGE_STATS",
        ) + ROUND_26_RUNTIME + ROUND_26_NORMAL
        val readOnly = setOf("android.permission.READ_PHONE_STATE", "android.permission.ACCESS_NETWORK_STATE", "moe.shizuku.manager.permission.API_V23")
        // WorkManager's own: wake lock and its foreground service (never started by the owner build).
        val work = setOf("android.permission.WAKE_LOCK", "android.permission.FOREGROUND_SERVICE")
        assertEquals(emptySet<String>(), requested - own - readOnly - work)
        assertTrue((own + readOnly).all { it in requested })
        // 2026-10-20: no device log, no Wi-Fi switching.
        assertTrue("android.permission.READ_LOGS" !in requested)
        assertTrue("android.permission.CHANGE_WIFI_STATE" !in requested)
    }

    @Test
    fun `the application ID is the owner's own`() {
        assertEquals("io.github.open_carrier_data.check.owner", merged.documentElement.getAttribute("package"))
    }

    @Test
    fun `exported components are the Settings entry, the system-guarded ones and the boot receiver for protected broadcasts`() {
        val exported = listOf("activity", "provider", "receiver", "service").flatMap { elements(it) }
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "permission") }
        // The owner's target form: no launcher icon; the screen opens from Settings only.
        assertTrue("io.github.open_carrier_data.check.ui.MainActivity" !in exported)
        assertEquals("android.permission.MODIFY_PHONE_STATE", exported["io.github.open_carrier_data.check.owner.OwnerActivity"])
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", exported["rikka.shizuku.ShizukuProvider"])
        // Settings' summary provider: guarded by the calling uid in code, as call() checks no manifest permission.
        assertEquals("", exported["io.github.open_carrier_data.check.owner.OwnerSettingsProvider"])
        assertTrue("io.github.open_carrier_data.check.owner.OwnerBootReceiver" in exported)
        assertTrue("io.github.open_carrier_data.check.owner.RevertAllReceiver" !in exported)
        assertTrue("io.github.open_carrier_data.check.owner.RevertAlarmReceiver" !in exported)
        // Round 26: only the system sends these (PHONE_STATE to privileged phone-state holders, the data SMS with BROADCAST_SMS).
        assertEquals("android.permission.READ_PRIVILEGED_PHONE_STATE", exported["io.github.open_carrier_data.check.owner.PhoneStateReceiver"])
        assertEquals("android.permission.BROADCAST_SMS", exported["io.github.open_carrier_data.check.owner.SelfSmsReceiver"])
        assertTrue("io.github.open_carrier_data.check.owner.CallWatchService" !in exported)
        assertEquals(
            setOf(
                "io.github.open_carrier_data.check.owner.OwnerActivity", "rikka.shizuku.ShizukuProvider",
                "io.github.open_carrier_data.check.owner.OwnerSettingsProvider", "io.github.open_carrier_data.check.owner.OwnerBootReceiver",
                "io.github.open_carrier_data.check.owner.PhoneStateReceiver", "io.github.open_carrier_data.check.owner.SelfSmsReceiver",
            ),
            exported.keys.filterNot { it.startsWith("androidx.work") || it.startsWith("androidx.profileinstaller") }.toSet(),
        )
        for ((name, permission) in exported) {
            if (name.startsWith("androidx.work") || name.startsWith("androidx.profileinstaller")) {
                assertTrue("$name unguarded", permission.isNotEmpty())
            }
        }
    }

    @Test
    fun `no launcher icon in the owner build`() {
        val categories = elements("category").map { it.getAttributeNS(android, "name") }
        assertTrue(categories.toString(), "android.intent.category.LAUNCHER" !in categories)
    }

    @Test
    fun `the Settings entry sits under Network and internet with title, live summary and icon`() {
        val activity = elements("activity").single { it.getAttributeNS(android, "name") == "io.github.open_carrier_data.check.owner.OwnerActivity" }
        val actions = activity.getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttributeNS(android, "name") } }
        assertEquals(listOf("com.android.settings.action.IA_SETTINGS"), actions)
        val meta = activity.getElementsByTagName("meta-data").let { n -> (0 until n.length).map { n.item(it) as Element } }
            .associate { it.getAttributeNS(android, "name") to (it.getAttributeNS(android, "value").ifEmpty { it.getAttributeNS(android, "resource") }) }
        // CategoryKey.CATEGORY_NETWORK in AOSP 16 / LineageOS 23.2 SettingsLib.
        assertEquals("com.android.settings.category.ia.wireless", meta["com.android.settings.category"])
        assertEquals("content://io.github.open_carrier_data.check.owner.settings/getDynamicSummary", meta["com.android.settings.summary_uri"])
        assertTrue(meta.toString(), meta["com.android.settings.title"]!!.isNotEmpty())
        assertTrue(meta.toString(), meta["com.android.settings.icon"]!!.isNotEmpty())
        val provider = elements("provider").single { it.getAttributeNS(android, "name") == "io.github.open_carrier_data.check.owner.OwnerSettingsProvider" }
        assertEquals("io.github.open_carrier_data.check.owner.settings", provider.getAttributeNS(android, "authorities"))
    }

    @Test
    fun `the privapp allowlist names exactly the privileged permissions the build asks for`() {
        // On an enforcing ROM (ro.control_privapp_permissions=enforce) a missing entry stops system_server at boot.
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/owner/priv-app/privapp-permissions-ocd-check-owner.xml"))
        val block = xml.getElementsByTagName("privapp-permissions").item(0) as Element
        assertEquals("io.github.open_carrier_data.check.owner", block.getAttribute("package"))
        val listed = block.getElementsByTagName("permission").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttribute("name") } }.toSet()
        assertEquals(OwnerAccess.PRIVILEGED_PERMISSIONS.toSet(), listed)
        // Every requested permission is either one of those or one that is not signature|privileged.
        val notPrivileged = setOf(
            "android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.READ_PHONE_STATE", "android.permission.ACCESS_NETWORK_STATE",
            "moe.shizuku.manager.permission.API_V23", "android.permission.WAKE_LOCK", "android.permission.FOREGROUND_SERVICE",
        ) + ROUND_26_RUNTIME + ROUND_26_NORMAL
        val requested = elements("uses-permission").filter { it.getAttributeNS(tools, "node") != "remove" }.map { it.getAttributeNS(android, "name") }.toSet()
        assertEquals(emptySet<String>(), requested - notPrivileged - listed)
        // Round 26 added no privileged permission: the XML on the phone stays as it is (a mismatch would stop the ROM at boot).
        assertTrue(ROUND_26_RUNTIME.none { it in listed } && ROUND_26_NORMAL.none { it in listed })
    }

    @Test
    fun `round 26's runtime permissions are granted by the default-permissions file, and nothing else is`() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/owner/priv-app/default-permissions-ocd-check-owner.xml"))
        val exception = xml.getElementsByTagName("exception").item(0) as Element
        assertEquals("io.github.open_carrier_data.check.owner", exception.getAttribute("package"))
        val granted = exception.getElementsByTagName("permission").let { n -> (0 until n.length).map { n.item(it) as Element } }
        assertEquals(setOf("android.permission.READ_PHONE_STATE", "android.permission.POST_NOTIFICATIONS") + ROUND_26_RUNTIME, granted.map { it.getAttribute("name") }.toSet())
        // The owner can take each one back.
        assertTrue(granted.all { it.getAttribute("fixed") == "false" })
    }

    companion object {
        /** Round 26: the mailbox call, its call log entry, the optional data SMS; runtime, granted by default-permissions or pm grant. */
        val ROUND_26_RUNTIME = setOf(
            "android.permission.CALL_PHONE", "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
        )

        /** Normal permissions: the microphone muted during the mailbox call, the SIM's MMS network for the MMSC probe. */
        val ROUND_26_NORMAL = setOf("android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.CHANGE_NETWORK_STATE")
    }

    @Test
    fun `the power-save allowlist names the owner build`() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/owner/priv-app/sysconfig-ocd-check-owner.xml"))
        val entry = xml.getElementsByTagName("allow-in-power-save").item(0) as Element
        assertEquals("io.github.open_carrier_data.check.owner", entry.getAttribute("package"))
    }
}
