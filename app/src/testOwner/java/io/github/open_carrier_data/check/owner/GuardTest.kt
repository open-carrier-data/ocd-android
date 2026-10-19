package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnColumns
import io.github.open_carrier_data.check.owner.core.KeyGuard
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerArgs
import io.github.open_carrier_data.check.owner.core.OwnerCommand
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.SafetyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The owner build's safety floor in code: the key guard, the command list and the sources (lab test_key_guard and more). */
class GuardTest {
    @Test
    fun `the key guard refuses every safety-class key and anything off the list`() {
        for (key in listOf(
            "carrier_use_ims_first_for_emergency_bool", "emergency_number_prefix_string_array", "e911_url_string",
            "ecbm_timer_int", "carrier_volte_tty_supported_bool", "rtt_supported_bool", "ims.rtt_upgrade_bool",
            "iwlan.epdg_static_address_string", "support_wps_over_ims_bool", "enabledMMS", "ims.EMERGENCY_over_ims_bool",
            "carrier_volte_available_bool_x", "",
        )) {
            try {
                KeyGuard.check(key)
                fail("$key passed the guard")
            } catch (_: SafetyException) {
            }
        }
        for (feature in OwnerFeature.entries) KeyGuard.check(feature.key)
        assertEquals(5, KeyGuard.ALLOWED.size)
    }

    @Test
    fun `the floor's regex is exactly the owner's`() {
        assertEquals("emergency|e911|ecbm|wps|tty|rtt|iwlan\\.", KeyGuard.FORBIDDEN.pattern)
        assertTrue(KeyGuard.FORBIDDEN.containsMatchIn("CARRIER_ECBM"))
    }

    private val args = OwnerArgs(slot = 0, subId = 3, key = "carrier_volte_available_bool", value = true, numeric = "26202", id = 7,
        binds = mapOf("name" to "LIDL Internet${ApnColumns.MARKER}", "apn" to "web.vodafone.de", "numeric" to "26202", "type" to "default,supl"))

    @Test
    fun `every command has a fixed shape, and the writes are the seven listed`() {
        val lines = OwnerCommand.entries.associateWith { it.argv(args)!!.joinToString(" ") }
        assertEquals("cmd phone cc set-value -s 0 carrier_volte_available_bool true", lines[OwnerCommand.CC_SET])
        assertEquals("cmd phone cc clear-values -s 0", lines[OwnerCommand.CC_CLEAR])
        assertEquals("content delete --uri content://telephony/carriers --where _id=7", lines[OwnerCommand.APN_DELETE])
        assertEquals("content insert --uri content://telephony/carriers/preferapn/subId/3 --bind apn_id:l:7", lines[OwnerCommand.PREFERRED_SET])
        // VoLTE's Wi-Fi-off measurement (the owner's first run, 2026-10-19): Wi-Fi only, never svc.
        assertEquals("cmd wifi set-wifi-enabled enabled", lines[OwnerCommand.WIFI_SET])
        assertEquals("cmd wifi set-wifi-enabled disabled", OwnerCommand.WIFI_SET.argv(args.copy(value = false))!!.joinToString(" "))
        assertNull(OwnerCommand.WIFI_SET.argv(args.copy(value = null)))
        assertEquals(
            setOf(
                OwnerCommand.CC_SET, OwnerCommand.CC_CLEAR, OwnerCommand.APN_INSERT, OwnerCommand.APN_DELETE, OwnerCommand.PREFERRED_SET, OwnerCommand.PREFERRED_CLEAR,
                OwnerCommand.WIFI_SET,
            ),
            OwnerCommand.entries.filter { it.writes }.toSet(),
        )
        val banned = Regex("(^|\\s)-p(\\s|$)|persistent|settings put|reboot|\\bam\\b|\\bsvc\\b|setprop|tel:|ACTION_CALL|KEYCODE|\\bsu\\b|\\bsh\\b|[;&|`$<>\\n]")
        for ((command, line) in lines) assertFalse("$command: $line", banned.containsMatchIn(line))
    }

    @Test
    fun `the service-side argv checks the key guard and every argument again`() {
        try {
            OwnerCommand.CC_SET.argv(args.copy(key = "carrier_use_ims_first_for_emergency_bool"))
            fail("emergency key passed")
        } catch (_: SafetyException) {
        }
        assertNull(OwnerCommand.CC_SET.argv(args.copy(slot = 4)))
        assertNull(OwnerCommand.CC_SET.argv(args.copy(value = null)))
        assertNull(OwnerCommand.APN_QUERY.argv(args.copy(numeric = "26202' OR 1=1")))
        assertNull(OwnerCommand.APN_DELETE.argv(args.copy(id = null)))
        assertNull(OwnerCommand.PREFERRED_SET.argv(args.copy(subId = 0)))
        val binds = args.binds
        assertNull("emergency type", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("type" to "default,emergency"))))
        assertNull("no marker", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("name" to "LIDL Internet"))))
        assertNull("unknown column", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("_id" to "1"))))
        assertNull("not ASCII", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("apn" to "web\nvodafone"))))
        assertNull("integer column", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("mtu" to "x"))))
    }

    @Test
    fun `an emergency APN row is never written`() {
        val row = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "sos", "type" to "emergency", "carrier" to "SOS"))
        try {
            ApnColumns.values(row)
            fail("emergency row passed")
        } catch (e: SafetyException) {
            assertEquals("emergency_apn", e.reason)
        }
    }

    private val ownerSources = File("src/owner/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `the owner build never dials, ends a call or touches settings or the emergency dialer`() {
        assertTrue(ownerSources.size > 8)
        val banned = Regex(
            "ACTION_CALL\\b|ACTION_DIAL|CALL_EMERGENCY|\"tel:|placeCall|endCall|KEYCODE_ENDCALL|KEYCODE_CALL|" +
                "Settings\\.(Global|System|Secure)\\.put|\"-p\"|persistent = true|overrideConfig\\([^)]*true\\)|reboot\\(",
        )
        for (file in ownerSources) {
            val hit = banned.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
    }

    @Test
    fun `only the two routes start a process, each from an OwnerCommand argv`() {
        val starters = Regex("ProcessBuilder|Runtime\\.getRuntime|\\.exec\\(")
        val users = ownerSources.filter { starters.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("OwnerAccess.kt", "OwnerShellService.kt"), users)
        for (name in users) {
            val text = ownerSources.single { it.name == name }.readText()
            assertEquals(listOf("ProcessBuilder(argv)"), Regex("ProcessBuilder\\([^)]*\\)").findAll(text).map { it.value }.toList())
        }
        val privileged = ownerSources.single { it.name == "OwnerAccess.kt" }.readText()
        assertTrue("the privileged route runs reads only", "if (command.writes) return@withContext null" in privileged)
    }
}
