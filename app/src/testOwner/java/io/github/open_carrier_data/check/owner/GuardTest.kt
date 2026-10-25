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
import org.junit.Assert.assertNotNull
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

    /** A test row as the runner builds it (the report of 2026-10-20): empty numeric, the SIM's carrier ID, edited. */
    private val args = OwnerArgs(slot = 0, subId = 3, key = "carrier_volte_available_bool", value = true, numeric = "26202", id = 7,
        binds = mapOf(
            "name" to "LIDL Internet${ApnColumns.MARKER}", "apn" to "web.vodafone.de", "numeric" to "", "mcc" to "", "mnc" to "",
            "type" to "default,supl", "carrier_id" to "2397", "infrastructure_bitmask" to "1", "apn_set_id" to "0", "edited" to "1",
        ))

    @Test
    fun `every command has a fixed shape, and the writes are the six listed`() {
        val lines = OwnerCommand.entries.associateWith { it.argv(args)!!.joinToString(" ") }
        assertEquals("cmd phone cc set-value -s 0 carrier_volte_available_bool true", lines[OwnerCommand.CC_SET])
        assertEquals("cmd phone cc clear-values -s 0", lines[OwnerCommand.CC_CLEAR])
        assertEquals("content delete --uri content://telephony/carriers --where _id=7", lines[OwnerCommand.APN_DELETE])
        assertEquals("content insert --uri content://telephony/carriers/preferapn/subId/3 --bind apn_id:l:7", lines[OwnerCommand.PREFERRED_SET])
        // The IWLAN and QNS dumps in place of the log (2026-10-20); no Wi-Fi command at all.
        assertEquals("dumpsys activity service com.google.android.iwlan/.IwlanDataService", lines[OwnerCommand.IWLAN_DUMP])
        assertEquals("dumpsys activity service com.android.telephony.qns", lines[OwnerCommand.QNS_DUMP])
        assertEquals("dumpsys power", lines[OwnerCommand.POWER])
        // How a test SMS came in, from Telephony's own record (2026-10-24).
        assertEquals("dumpsys isms", lines[OwnerCommand.SMS_DUMP])
        assertEquals(
            setOf(OwnerCommand.CC_SET, OwnerCommand.CC_CLEAR, OwnerCommand.APN_INSERT, OwnerCommand.APN_DELETE, OwnerCommand.PREFERRED_SET, OwnerCommand.PREFERRED_CLEAR),
            OwnerCommand.entries.filter { it.writes }.toSet(),
        )
        val banned = Regex("(^|\\s)-p(\\s|$)|persistent|settings put|reboot|\\bam\\b|\\bsvc\\b|setprop|tel:|ACTION_CALL|KEYCODE|\\bsu\\b|\\bsh\\b|logcat|cmd wifi|[;&|`$<>\\n]")
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
        // Inserts are test rows only (the report of 2026-10-20).
        assertNull("edited=0 leaves a tombstone", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("edited" to "0"))))
        assertNull("no edited", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds - "edited")))
        assertNull("a numeric would share a stock row's key", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("numeric" to "26202"))))
        assertNull("no carrier ID", OwnerCommand.APN_INSERT.argv(args.copy(binds = binds - "carrier_id")))
        for (type in listOf("default,ims", "default,ia", "*")) assertNull(type, OwnerCommand.APN_INSERT.argv(args.copy(binds = binds + ("type" to type))))
        // A lookup by the stock key is fine.
        assertNotNull(OwnerCommand.APN_CONFLICTS.argv(args.copy(binds = mapOf("apn" to "web.vodafone.de", "numeric" to "26202", "mcc" to "262", "mnc" to "02"))))
    }

    @Test
    fun `an emergency APN row is never written`() {
        val row = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "sos", "type" to "emergency", "carrier" to "SOS"))
        try {
            ApnColumns.testRow(row, 2397)
            fail("emergency row passed")
        } catch (e: SafetyException) {
            assertEquals("emergency_apn", e.reason)
        }
    }

    private val ownerSources = File("src/owner/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `the owner build never opens a dialler, never touches settings or the emergency dialer`() {
        assertTrue(ownerSources.size > 8)
        val banned = Regex(
            "ACTION_CALL\\b|ACTION_DIAL|ACTION_CALL_PRIVILEGED|CALL_EMERGENCY|\"tel:|KEYCODE_ENDCALL|KEYCODE_CALL|acceptRingingCall|" +
                "Settings\\.(Global|System|Secure)\\.put|\"-p\"|persistent = true|overrideConfig\\([^)]*true\\)|reboot\\(",
        )
        for (file in ownerSources) {
            val hit = banned.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
    }

    @Test
    fun `round 26 - one place dials, only after the mailbox guard, and one place ends the app's own call`() {
        // Telecom's placeCall and endCall appear once each, in the Android side of the mailbox call.
        val dialling = ownerSources.filter { Regex("placeCall|endCall").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("OwnerCalls.kt"), dialling)
        val calls = ownerSources.single { it.name == "OwnerCalls.kt" }.readText()
        assertEquals(1, Regex("\\.placeCall\\(").findAll(calls).count())
        assertEquals(1, Regex("\\.endCall\\(").findAll(calls).count())
        val place = calls.substringAfter("override suspend fun place(").substringBefore("override suspend fun end(")
        assertTrue("the last look before the dial", place.indexOf("MailboxGuard.refusal(number) != null) return false") in 0 until place.indexOf("placeCall"))
        // Only the mailbox caller asks the device to place a call.
        val placers = ownerSources.filter { Regex("device\\.place\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("MailboxCall.kt"), placers)
        val caller = ownerSources.single { it.name == "MailboxCall.kt" }.readText()
        // The dial comes after the mailbox guard, the refusals and the mute.
        val body = caller.substringAfter("suspend fun run(")
        assertTrue(body.indexOf("MailboxGuard.target(") < body.indexOf("session.guard("))
        assertTrue(caller.indexOf("device.mute()") < caller.indexOf("device.place("))
    }

    @Test
    fun `round 26 - never DTMF, never a text or multimedia message, and a data SMS only on the app's port`() {
        val banned = Regex("(?i)dtmf|sendTextMessage|sendMultipartTextMessage|sendMultimediaMessage|downloadMultimediaMessage")
        for (file in ownerSources) {
            val hit = banned.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
        // Round 29: one HTTP POST in the whole owner build, the automatic report's issue on OCD's public repository.
        val posters = ownerSources.filter { "\"POST\"" in it.readText() }
        assertEquals(listOf("OwnerReports.kt"), posters.map { it.name })
        val reports = posters.single().readText()
        assertEquals(1, Regex("\"POST\"").findAll(reports).count())
        assertTrue("https://api.github.com/repos/\${DataSource.REPOSITORY}/issues" in reports)
        // The token goes into the Authorization header only: no device log, no state log, no message in that file.
        assertNull(Regex("Log\\.[a-z]\\(|\\.logged\\(|println").find(reports)?.value)
        assertEquals(1, Regex("\\\$token").findAll(reports).count())
        val senders = ownerSources.filter { "sendDataMessage" in it.readText() }
        assertEquals(listOf("OwnerMessages.kt"), senders.map { it.name })
        assertTrue(Regex("sendDataMessage\\(own, null, SelfSms\\.PORT, SelfSms\\.payload\\(nonce\\)").containsMatchIn(senders.single().readText()))
    }

    @Test
    fun `the owner build never reads the device log and never switches Wi-Fi`() {
        // 2026-10-20: reading logcat asks the owner for log access; the Wi-Fi-off measurement is retired.
        val banned = Regex("\"logcat\"|setWifiEnabled\\(|WifiManager|\"android\\.permission\\.READ_LOGS\"|\"android\\.permission\\.CHANGE_WIFI_STATE\"|setVoWiFiModeSetting|setVoWiFiSettingEnabled")
        for (file in ownerSources) {
            val hit = banned.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
        assertTrue(OwnerCommand.entries.none { c -> runCatching { c.argv(args) }.getOrNull()?.firstOrNull() == "logcat" })
    }

    @Test
    fun `the test row is keyed by carrier ID, edited, and never carries ia, wildcard, ims or emergency`() {
        val row = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "web.vodafone.de", "type" to "default,ia,supl,ims", "protocol" to "IPV4V6", "carrier" to "Vodafone", "mvno_type" to "spn", "mvno_match_data" to "LIDL"))
        val v = ApnColumns.testRow(row, 2397)
        assertEquals("default,supl", v["type"])
        assertEquals("", v["numeric"])
        assertEquals("2397", v["carrier_id"])
        assertEquals("1", v["edited"])
        assertNull("the carrier ID is the MVNO match", v["mvno_type"])
        assertTrue(v["name"]!!.endsWith(ApnColumns.MARKER))
        assertNotNull(OwnerCommand.APN_INSERT.argv(OwnerArgs(binds = v)))
        assertEquals("the stock lookup keeps the network code", "26202", ApnColumns.identity(row)["numeric"])
    }

    @Test
    fun `round 30 - typed override bundles go through the trial guard on the privileged route, and the Shizuku route sets none`() {
        val access = ownerSources.single { it.name == "OwnerAccess.kt" }.readText()
        val body = access.substringAfter("override suspend fun ccSetBundle(").substringBefore("override suspend fun telephonyText(")
        assertTrue(body.indexOf("TrialGuard.check(values.keys)") in 0 until body.indexOf("overrideConfig("))
        // Only the privileged route overrides a bundle; the interface's default refuses.
        assertEquals(listOf("OwnerAccess.kt", "OwnerPhone.kt"), ownerSources.filter { "fun ccSetBundle(" in it.readText() }.map { it.name }.sorted())
        assertTrue("CcResult(false, \"not_supported_on_this_route\")" in ownerSources.single { it.name == "OwnerPhone.kt" }.readText())
        // The round 29 layer client is gone.
        assertTrue(ownerSources.none { "com.android.carriersettings.ocdlayer" in it.readText() || "WRITE_OCD_LAYER" in it.readText() })
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
