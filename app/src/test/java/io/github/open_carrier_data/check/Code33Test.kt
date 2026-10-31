package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ApnRowClass
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.CauseVerdicts
import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.HiddenLines
import io.github.open_carrier_data.check.core.Refusal
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.SipRegClass
import io.github.open_carrier_data.check.core.TestBlame
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 44: how a #33 ("requested service option not subscribed") reads, by the access point
 * row of the refused setup (round 43's [ApnRowClass], told by row ID only), the features named in every
 * NOT_BOOKED line, a blocked account read after a coded settings refusal or a #33, and the read-only
 * builds' coded lines. On the S20 (build I, Vodafone 262-02) the Samsung RIL's own table showed that a
 * made-up internet APN was refused with #33, not #27; the registry log below is round 43's case 2 as a
 * phone that passes the cause prints it.
 */
class Code33Test {
    private val wwan = listOf(VerdictFeature.MOBILE_DATA, VerdictFeature.MMS, VerdictFeature.VOLTE)
    private val rows = listOf(ApnRowClass.OCD, ApnRowClass.TEST, ApnRowClass.OTHER, null)

    private fun verdict(f: VerdictFeature, row: ApnRowClass?, account: AccountState = AccountState.UNKNOWN, balance: String? = null) =
        CauseVerdicts.of(f, SetupRefusal(f, 33, row).refusal(), account, balance)!!

    @Test
    fun `mobile data's code 33 reads by the access point row`() {
        val ocd = verdict(VerdictFeature.MOBILE_DATA, ApnRowClass.OCD)
        assertEquals("Refused for mobile data, probably the tariff (code 33)", ocd.line)
        assertEquals(CauseClass.NOT_BOOKED, ocd.cause)
        val test = verdict(VerdictFeature.MOBILE_DATA, ApnRowClass.TEST)
        assertEquals(
            "Mobile data refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan",
            test.line,
        )
        assertEquals(CauseClass.AMBIGUOUS, test.cause)
        val neutral = "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM"
        for (row in listOf(ApnRowClass.OTHER, null)) {
            assertEquals("$row", neutral, verdict(VerdictFeature.MOBILE_DATA, row).line)
            assertEquals("$row", CauseClass.AMBIGUOUS, verdict(VerdictFeature.MOBILE_DATA, row).cause)
        }
    }

    @Test
    fun `MMS reads the same with MMS`() {
        assertEquals("Refused for MMS, probably the tariff (code 33)", verdict(VerdictFeature.MMS, ApnRowClass.OCD).line)
        assertEquals(CauseClass.NOT_BOOKED, verdict(VerdictFeature.MMS, ApnRowClass.OCD).cause)
        assertEquals(
            "MMS refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan",
            verdict(VerdictFeature.MMS, ApnRowClass.TEST).line,
        )
        assertEquals(CauseClass.AMBIGUOUS, verdict(VerdictFeature.MMS, ApnRowClass.TEST).cause)
        for (row in listOf(ApnRowClass.OTHER, null)) {
            assertEquals(
                "$row",
                "Refused for MMS (code 33): not in the plan, or an access point name the network does not know for this SIM",
                verdict(VerdictFeature.MMS, row).line,
            )
            assertEquals("$row", CauseClass.AMBIGUOUS, verdict(VerdictFeature.MMS, row).cause)
        }
    }

    @Test
    fun `VoLTE keeps the tariff words on every row but a test row`() {
        for (row in listOf(ApnRowClass.OCD, ApnRowClass.OTHER, null)) {
            assertEquals("$row", "Refused for VoLTE, probably the tariff (code 33)", verdict(VerdictFeature.VOLTE, row).line)
            assertEquals("$row", CauseClass.NOT_BOOKED, verdict(VerdictFeature.VOLTE, row).cause)
        }
        assertEquals(
            "VoLTE refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan",
            verdict(VerdictFeature.VOLTE, ApnRowClass.TEST).line,
        )
        assertEquals(CauseClass.AMBIGUOUS, verdict(VerdictFeature.VOLTE, ApnRowClass.TEST).cause)
    }

    @Test
    fun `no code 33 line says re-test with credit, none is confident, and none is not counted`() {
        for (f in wwan) for (row in rows) for (account in AccountState.entries) {
            val refusal = SetupRefusal(f, 33, row).refusal()!!
            val v = CauseVerdicts.of(f, refusal, account, "0.10 EUR")!!
            assertFalse("$f $row $account", "re-test with credit" in v.line)
            assertTrue("$f $row $account", "(code 33)" in v.line)
            assertFalse("$f $row $account", v.confident)
            assertFalse("$f $row $account", TestBlame.notCounted(refusal, account))
        }
        // The gate and the balance read key on the class, which stays NOT_BOOKED for 33.
        assertEquals(CauseClass.NOT_BOOKED, DataFailCauseClass.of(33))
        assertTrue(DataFailCauseClass.refusal(33))
        assertTrue(DataFailCauseClass.permanent(33))
        // #30/#31 keep their ambiguous line.
        assertEquals(
            "An ambiguous refusal (code 30); re-test with credit",
            CauseVerdicts.of(VerdictFeature.VOLTE, Refusal(CauseClass.AMBIGUOUS, code = 30), AccountState.UNKNOWN)!!.line,
        )
    }

    @Test
    fun `every NOT_BOOKED line names the feature, never this SIM`() {
        for (f in VerdictFeature.entries) for (code in listOf(9000, 33, null)) for (row in rows) {
            val line = CauseVerdicts.of(f, Refusal(CauseClass.NOT_BOOKED, code = code, apnRow = row), AccountState.UNKNOWN)!!.line
            assertFalse("$f $code $row", "Refused for this SIM" in line)
        }
        assertEquals(
            "Refused for Wi-Fi calling, probably the tariff (code 9000)",
            CauseVerdicts.of(VerdictFeature.VOWIFI, Refusal(DataFailCauseClass.of(9000), code = 9000), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Refused for VoLTE, probably the tariff",
            CauseVerdicts.of(VerdictFeature.VOLTE, Refusal(SipRegClass.FORBIDDEN.cause), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Refused for Wi-Fi calling, probably the tariff",
            CauseVerdicts.of(VerdictFeature.VOWIFI, Refusal(SipRegClass.USER_UNKNOWN.cause), AccountState.UNKNOWN)!!.line,
        )
        assertEquals(
            "Refused for SMS over IMS, probably the tariff",
            CauseVerdicts.of(VerdictFeature.SMS_OVER_IMS, Refusal(CauseClass.NOT_BOOKED), AccountState.UNKNOWN)!!.line,
        )
        // A #33 on Wi-Fi calling is no WWAN setup: it keeps the plain tariff words.
        assertEquals(
            "Refused for Wi-Fi calling, probably the tariff (code 33)",
            CauseVerdicts.of(VerdictFeature.VOWIFI, Refusal(CauseClass.NOT_BOOKED, code = 33, apnRow = ApnRowClass.TEST), AccountState.UNKNOWN)!!.line,
        )
    }

    @Test
    fun `a blocked account read is added after a code 33 or a coded settings refusal, never in its place`() {
        val clause = "; the account also reads as blocked (balance 0.10 EUR)"
        assertEquals(
            "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM$clause",
            verdict(VerdictFeature.MOBILE_DATA, ApnRowClass.OTHER, AccountState.BLOCKED, "0.10 EUR").line,
        )
        assertEquals(
            "Refused for mobile data, probably the tariff (code 33)$clause",
            verdict(VerdictFeature.MOBILE_DATA, ApnRowClass.OCD, AccountState.BLOCKED, "0.10 EUR").line,
        )
        assertEquals(
            "Mobile data refused by the network for the access point under test (code 33): a wrong access point name, or not in the plan$clause",
            verdict(VerdictFeature.MOBILE_DATA, ApnRowClass.TEST, AccountState.BLOCKED, "0.10 EUR").line,
        )
        assertEquals(
            "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM; the account also reads as blocked",
            verdict(VerdictFeature.MOBILE_DATA, null, AccountState.BLOCKED).line,
        )
        for (c in listOf(27, 28, 29, 50, 51)) {
            val v = CauseVerdicts.of(VerdictFeature.MOBILE_DATA, SetupRefusal(VerdictFeature.MOBILE_DATA, c).refusal(), AccountState.BLOCKED, "0.10 EUR")!!
            assertEquals("The network refused the access point name (code $c): probably wrong settings$clause", v.line)
            assertEquals(CauseClass.WRONG_SETTINGS, v.cause)
            assertFalse(v.confident)
        }
        // Only mobile data carries the clause; a low or good balance adds nothing.
        assertEquals("Refused for MMS (code 33): not in the plan, or an access point name the network does not know for this SIM", verdict(VerdictFeature.MMS, null, AccountState.BLOCKED, "0.10 EUR").line)
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", verdict(VerdictFeature.VOLTE, null, AccountState.BLOCKED, "0.10 EUR").line)
        assertEquals(
            "The network refused the access point name (code 27): probably wrong settings",
            CauseVerdicts.of(VerdictFeature.MOBILE_DATA, Refusal(CauseClass.WRONG_SETTINGS, code = 27), AccountState.LOW, "1.00 EUR")!!.line,
        )
    }

    @Test
    fun `codes 8, 30 and 31, the other codes and the user-plane classes keep the account line`() {
        fun line(r: Refusal?) = CauseVerdicts.of(VerdictFeature.MOBILE_DATA, r, AccountState.BLOCKED, "0.10 EUR")!!.line
        assertEquals("Mobile data probably blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.AMBIGUOUS, code = 30)))
        assertEquals("Mobile data probably blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.AMBIGUOUS, code = 31)))
        assertEquals("Mobile data blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.BARRED, code = 8)))
        // An attach reject #7 is NOT_BOOKED too, but not a #33.
        assertEquals("Mobile data probably blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.NOT_BOOKED, code = 7)))
        assertEquals("Mobile data probably blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.TEMPORARY, code = 38, temporaryDefinite = true)))
        assertEquals("Mobile data blocked by the account: balance 0.10 EUR", line(Refusal(CauseClass.BARRED, userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE)))
        assertEquals("Mobile data probably blocked by the account: balance 0.10 EUR", line(null))
    }

    private val log = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()

    /** Round 43's case 2 as a phone that passes the cause prints it: #33 (0x21) in place of UNKNOWN (0x10000), up to [time]. */
    private fun passed(time: String): String = log.replace("UNKNOWN(0x10000)", "SERVICE_OPTION_NOT_SUBSCRIBED(0x21)").lineSequence().filter { line ->
        val at = Regex("^\\s*(\\S+) - ").find(line)?.groupValues?.get(1)
        at == null || at <= time
    }.joinToString("\n")

    @Test
    fun `read-only shows a code 33 with the not-told words, only while the feature is still down`() {
        val found = HiddenLines.fromRegistry(RegistryDataLog.parse(passed("2026-10-31T11:39:11")), 0)
        // The MMS refusals of 11:30 (#55) are no refusal class: only mobile data.
        assertEquals(listOf(VerdictFeature.MOBILE_DATA to 33), found.map { (a, f) -> f to a.cause })
        val refusal = found.single().let { (a, f) -> SetupRefusal(f, a.cause, subId = a.subId) }
        val neutral = "Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM"
        assertEquals(listOf(refusal), HiddenLines.shown(listOf(refusal), 3, mobileDataDown = true, imsRegistered = true))
        assertEquals(neutral, HiddenLines.line(refusal))
        // Data back or off, unreadable, or another SIM: no line.
        assertTrue(HiddenLines.shown(listOf(refusal), 3, mobileDataDown = false, imsRegistered = true).isEmpty())
        assertTrue(HiddenLines.shown(listOf(refusal), 3, mobileDataDown = null, imsRegistered = true).isEmpty())
        assertTrue(HiddenLines.shown(listOf(refusal), 4, mobileDataDown = true, imsRegistered = true).isEmpty())
        // After the restore the refusal is no longer the newest state.
        assertTrue(HiddenLines.fromRegistry(RegistryDataLog.parse(passed("9999")), 0).isEmpty())
        // The read-only line never names a row and never carries an account clause.
        assertEquals(neutral, HiddenLines.line(refusal.copy(apnRow = ApnRowClass.OCD)))
        assertEquals(neutral, HiddenLines.line(refusal.copy(apnRow = ApnRowClass.TEST)))
        // VoLTE (only while IMS is not registered) keeps the tariff words; MMS reads the not-told words.
        val volte = SetupRefusal(VerdictFeature.VOLTE, 33, subId = 3)
        assertTrue(HiddenLines.shown(listOf(volte), 3, null, imsRegistered = true).isEmpty())
        assertEquals(listOf(volte), HiddenLines.shown(listOf(volte), 3, null, imsRegistered = false))
        assertEquals("Refused for VoLTE, probably the tariff (code 33)", HiddenLines.line(volte))
        assertEquals(
            "Refused for MMS (code 33): not in the plan, or an access point name the network does not know for this SIM",
            HiddenLines.line(SetupRefusal(VerdictFeature.MMS, 33, subId = 3)),
        )
        // AOSP's -1 to -6 and Android-internal causes stay no refusal.
        for (c in listOf(-1, -6, 0, 0x10004)) assertTrue("$c", HiddenLines.shown(listOf(refusal.copy(cause = c)), 3, true, true).isEmpty())
    }

    @Test
    fun `the deep check keeps a coded refusal from the registry dump it already reads, with no new command`() {
        val ran = mutableListOf<ShellCommand>()
        val runner = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> "  SIM slot: [0] state: [REGISTERED] IMS Profile: [Name : VODAFONE Germany IMS VoLTE, pdn : ims]"
                    ShellCommand.WIFI_CALLING_SWITCH -> "1"
                    ShellCommand.TELEPHONY_REGISTRY -> passed("2026-10-31T11:39:11")
                    else -> ""
                }
                return read(output.lineSequence())
            }
        }
        val reading = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, null), runner)
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.WIFI_CALLING_SWITCH), ran)
        assertEquals(listOf(SetupRefusal(VerdictFeature.MOBILE_DATA, 33, subId = 3)), reading.refusals)
        val encoded = reading.encode()
        assertFalse("no APN name is kept", "ocdtest27" in encoded || "OCD-test" in encoded)
        assertFalse("no row ID is kept", "24962" in encoded)
    }
}
