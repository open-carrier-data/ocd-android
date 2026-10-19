package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import io.github.open_carrier_data.check.phone.DeepCheckProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.ShizukuApiConstants
import java.io.File

/**
 * Guards for the deep check: the command allowlist is closed, read-only and
 * built from validated digits only, and nothing else in the app can start a
 * process or reach Shizuku's write paths.
 */
class AllowlistTest {
    private val targets = (0..3).flatMap { slot ->
        listOf(null, "26202", "310260", "00101").map { DeepCheckTarget(SimSlot.of(slot)!!, HomeNetwork.of(it)) }
    }

    private fun everyArgv(): List<List<String>> =
        ShellCommand.entries.flatMap { command -> targets.mapNotNull { command.argv(it) } }.distinct()

    /** Verbs that write, or that open a way to run anything. */
    private val writeVerbs = listOf(
        Regex("^settings (put|delete|reset)\\b"),
        Regex("^content (insert|update|delete|call|write|bulkinsert)\\b"),
        Regex("^cmd\\b"),
        Regex("^(am|pm|svc|setprop|wm|input|appops|device_config|telecom|ip|iptables|ndc|reboot|rm|mv|cp|dd|chmod|chown|kill|killall|stop|start|monkey|bmgr|dpm|sm|vdc|resetprop|su|sh|bash|toybox|busybox)\\b"),
        // logcat may only dump: no clearing, buffer resizing, file output, rotation or prune list.
        Regex("^logcat\\b.*\\s(-c|--clear|-G|--buffer-size|-f|--file|-r|--rotate-kbytes|-n|--rotate-count|-P|--prune|-L|--last)\\b"),
        Regex("\\b(set-value|clear-values|override|set-ims-service|enable|disable|grant|revoke|install|uninstall|clear)\\b"),
        Regex("content://telephony/carriers/(restore|update_db|delete|preferapn|dpc)"),
        Regex("[;&|`$<>\\\\\\n]"),
    )

    /** The only dumps the deep check reads. */
    private val dumpsysServices = listOf(
        listOf("dumpsys", "secims"),
        listOf("dumpsys", "activity", "service", "com.android.phone"),
        listOf("dumpsys", "telephony.registry"),
        listOf("dumpsys", "connectivity"),
    )

    /** logcat only dumps (-d) the IWLAN tags of one slot, every other tag silenced (-s). */
    private val logcatDumps = (0..3).map { slot ->
        listOf(
            "logcat", "-d", "-v", "epoch", "-b", "main", "-b", "radio", "-s",
            "EpdgSelector:V", "IwlanDataService[$slot]:V", "EpdgTunnelManager[$slot]:V",
        )
    }

    @Test
    fun `every allowlisted command is read-only`() {
        val all = everyArgv()
        assertTrue(all.isNotEmpty())
        for (argv in all) {
            val line = argv.joinToString(" ")
            for (verb in writeVerbs) assertFalse("$line matches $verb", verb.containsMatchIn(line))
            when (argv.first()) {
                "dumpsys" -> assertTrue(line, argv in dumpsysServices)
                "settings" -> assertEquals(listOf("settings", "get", "system"), argv.take(3))
                "ping" -> assertEquals(listOf("ping", "-c1", "-W2"), argv.take(3))
                "content" -> assertEquals(listOf("content", "query"), argv.take(2))
                "logcat" -> assertTrue(line, argv in logcatDumps)
                else -> throw AssertionError("not on the read-only tool list: $line")
            }
        }
    }

    @Test
    fun `the allowlist is exactly the eight commands, each with a fixed shape`() {
        assertEquals(
            listOf(
                "SAMSUNG_IMS", "WIFI_CALLING_SWITCH", "EPDG_LOOKUP", "APN_LIST",
                "AOSP_IMS", "TELEPHONY_REGISTRY", "IWLAN_LOG", "WIFI_DNS",
            ),
            ShellCommand.entries.map { it.name },
        )
        val target = DeepCheckTarget(SimSlot.of(1)!!, HomeNetwork.of("26202"))
        assertEquals(
            listOf(
                "dumpsys secims",
                "settings get system wifi_call_enable2",
                "ping -c1 -W2 epdg.epc.mnc002.mcc262.pub.3gppnetwork.org",
                "content query --uri content://telephony/carriers " +
                    "--projection apn:type:protocol:roaming_protocol:carrier_enabled:mvno_type:edited --where numeric='26202'",
                "dumpsys activity service com.android.phone",
                "dumpsys telephony.registry",
                "logcat -d -v epoch -b main -b radio -s EpdgSelector:V IwlanDataService[1]:V EpdgTunnelManager[1]:V",
                "dumpsys connectivity",
            ),
            ShellCommand.entries.map { it.argv(target)!!.joinToString(" ") },
        )
        val noHome = DeepCheckTarget(SimSlot.of(0)!!, null)
        assertNull(ShellCommand.EPDG_LOOKUP.argv(noHome))
        assertNull(ShellCommand.APN_LIST.argv(noHome))
        assertNull(ShellCommand.WIFI_DNS.argv(noHome))
    }

    @Test
    fun `the logcat guard catches every writing or rotating option`() {
        val guard = writeVerbs.last { "logcat" in it.pattern }
        for (bad in listOf("logcat -c", "logcat -d -b all -c", "logcat -G 16M", "logcat -d -f /sdcard/x", "logcat -P '~!'", "logcat -L")) {
            assertTrue(bad, guard.containsMatchIn(bad))
        }
        for (argv in logcatDumps) assertFalse(argv.joinToString(" "), guard.containsMatchIn(argv.joinToString(" ")))
    }

    @Test
    fun `the APN projection never asks for credentials, servers or MVNO match data`() {
        val projection = ShellCommand.APN_LIST.argv(DeepCheckTarget(SimSlot.of(0)!!, HomeNetwork.of("26202")))!!
            .let { it[it.indexOf("--projection") + 1] }.split(':')
        for (column in listOf("user", "password", "server", "proxy", "port", "mmsc", "mmsproxy", "mmsport", "mvno_match_data", "authtype")) {
            assertFalse(column, column in projection)
        }
    }

    @Test
    fun `targets accept only validated digits and slot numbers`() {
        for (bad in listOf("26202; reboot", "262 02", "2620", "2620212", "26202'", "abcde", "", "２６２０２", "٢٦٢٠٢")) {
            assertNull(bad, HomeNetwork.of(bad))
        }
        assertEquals("26202", HomeNetwork.of("26202")!!.mccmnc)
        for (bad in listOf(-1, 4, 99, null)) assertNull(SimSlot.of(bad))
        assertEquals(2, SimSlot.of(1)!!.number)
    }

    @Test
    fun `the deep check runs allowlisted commands only, and only what the target allows`() {
        val ran = mutableListOf<ShellCommand>()
        val runner = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> "Can't find service: secims"
                    ShellCommand.WIFI_CALLING_SWITCH -> "1"
                    ShellCommand.EPDG_LOOKUP -> "ping: unknown host x"
                    ShellCommand.APN_LIST -> "No result found."
                    ShellCommand.AOSP_IMS -> "No services match"
                    ShellCommand.TELEPHONY_REGISTRY -> "last known state:"
                    ShellCommand.IWLAN_LOG -> "--------- beginning of main"
                    ShellCommand.WIFI_DNS -> "NetworkAgentInfos:"
                }
                return read(output.lineSequence())
            }
        }
        val other = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, HomeNetwork.of("26202")), runner)
        assertEquals(
            listOf(
                ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.AOSP_IMS, ShellCommand.IWLAN_LOG,
                ShellCommand.EPDG_LOOKUP, ShellCommand.WIFI_DNS, ShellCommand.APN_LIST,
            ),
            ran,
        )
        // Neither Samsung's IMS nor a phone section for the slot: not found, and no switch guessed.
        assertEquals(ImsSource.NOT_FOUND, other.ims.source)
        assertEquals(WifiCallingSwitch.UNREADABLE, other.wifiCallingSwitch)
        assertEquals(EpdgName.DOES_NOT_RESOLVE, other.epdgName)

        ran.clear()
        val noHome = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, null), runner)
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.AOSP_IMS, ShellCommand.IWLAN_LOG), ran)
        assertEquals(EpdgName.NOT_CHECKED, noHome.epdgName)
        assertNull(noHome.apnList)
        assertNull(noHome.wifiResolver)
    }

    @Test
    fun `the service's destroy call is Shizuku's`() {
        assertEquals(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy, DeepCheckProtocol.DESTROY)
    }

    private val mainSources: List<File> = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `only the deep check's runner starts a process, and only from a ShellCommand`() {
        assertTrue(mainSources.size > 10)
        val starters = Regex("ProcessBuilder|Runtime\\.getRuntime|\\.exec\\(|newProcess|ShizukuRemoteProcess")
        val users = mainSources.filter { starters.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("DeepCheckService.kt"), users)
        val service = mainSources.single { it.name == "DeepCheckService.kt" }.readText()
        assertEquals(listOf("ProcessBuilder(argv)"), Regex("ProcessBuilder\\([^)]*\\)").findAll(service).map { it.value }.toList())
        assertTrue(service.contains("val argv = command.argv(target) ?: return null"))
    }

    @Test
    fun `the call watch takes only mailbox short codes from the app, never other text`() {
        assertEquals(setOf("5500", "333"), DeepCheckProtocol.mailboxCodes(listOf("5500", "333", "5500")))
        for (bad in listOf("12", "1234567", "55 00", "5500;reboot", "+495500", "*100#", "", "５５００")) {
            assertEquals(bad, emptySet<String>(), DeepCheckProtocol.mailboxCodes(listOf(bad)))
        }
        assertEquals(emptySet<String>(), DeepCheckProtocol.mailboxCodes(null))
        assertEquals(32, DeepCheckProtocol.mailboxCodes((100..199).map { it.toString() }).size)
    }

    @Test
    fun `the app never places, answers or ends a call, and opens no dial pad`() {
        val banned = Regex(
            "ACTION_CALL\\b|ACTION_DIAL|ACTION_CALL_PRIVILEGED|CALL_EMERGENCY|\"tel:|placeCall|acceptRingingCall|endCall|" +
                "silenceRinger|KEYCODE_ENDCALL|KEYCODE_CALL|ACTION_ANSWER|setVoNrEnabled|setAdvancedCallingSettingEnabled|" +
                "setVoWiFiSettingEnabled|Settings\\.(Global|System|Secure)\\.put",
        )
        for (file in mainSources) {
            val hit = banned.find(file.readText())
            assertNull("${file.name}: ${hit?.value}", hit)
        }
    }

    @Test
    fun `the app uses only Shizuku's read and permission calls, never its binder or process paths`() {
        val allowed = setOf(
            "pingBinder", "getUid", "getVersion", "isPreV11", "checkSelfPermission", "requestPermission",
            "shouldShowRequestPermissionRationale", "bindUserService", "unbindUserService", "UserServiceArgs",
            "addBinderReceivedListenerSticky", "addBinderDeadListener", "addRequestPermissionResultListener",
            "removeBinderReceivedListener", "removeBinderDeadListener", "removeRequestPermissionResultListener",
            "OnBinderReceivedListener", "OnBinderDeadListener", "OnRequestPermissionResultListener",
        )
        val used = mainSources.flatMap { file -> Regex("\\bShizuku\\.(\\w+)").findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(used.isNotEmpty())
        assertEquals(emptySet<String>(), used - allowed)
        for (file in mainSources) {
            val text = file.readText()
            for (banned in listOf("ShizukuBinderWrapper", "SystemServiceHelper", "transactRemote", "Shizuku.getBinder", "Shizuku.exit")) {
                assertFalse("${file.name} uses $banned", banned in text)
            }
        }
    }
}
