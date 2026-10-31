package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ApnRowClass
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.CauseVerdicts
import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.HiddenDetails
import io.github.open_carrier_data.check.core.HiddenLines
import io.github.open_carrier_data.check.core.ModemValue
import io.github.open_carrier_data.check.core.Refusal
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.SetupAttempts
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.TestBlame
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * OCD rule round 43, item 1: the hidden-cause verdict's shared parts. The registry text is the S20's own
 * `dumpsys telephony.registry` local log of 2026-10-31 (build H, LIDL Connect on 262-02), masked with
 * S20_Project/scripts/mask-ids.sh: the MMS ladder's refusals (#55) at 11:30, provoked case 2 (a made-up
 * internet APN, refused twice with UNKNOWN(0x10000)) at 11:37, IMS moving to LTE and back at 11:39, and
 * the restore to web.vodafone.de at 11:41:46.
 */
class HiddenCauseTest {
    private val log = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()

    /** The log as a dump taken at [time] would hold it. */
    private fun upTo(time: String): String = log.lineSequence().filter { line ->
        val at = Regex("^\\s*(\\S+) - ").find(line)?.groupValues?.get(1)
        at == null || at <= time
    }.joinToString("\n")

    private fun lines(time: String = "9999") = RegistryDataLog.parse(upTo(time))

    @Test
    fun `the registry's local log lines are read as states, rows, types and causes`() {
        val all = lines()
        assertEquals(17, all.size)
        val refused = all.single { it.at == LocalDateTime.parse("2026-10-31T11:37:42.207922") }
        assertEquals("DISCONNECTED", refused.state)
        assertEquals(0, refused.phoneId)
        assertEquals(3, refused.subId)
        assertFalse(refused.wlan)
        assertEquals(24962L, refused.rowId)
        assertEquals(setOf("hipri", "default"), refused.types)
        assertEquals("CARRIER_EDITED", refused.edited)
        assertEquals(65536, refused.cause)
        val mms = all.first()
        assertEquals(setOf("mms"), mms.types)
        assertEquals(5740L, mms.rowId)
        assertEquals("UNEDITED", mms.edited)
        val teardown = all.single { it.at == LocalDateTime.parse("2026-10-31T11:37:38.754822") }
        assertEquals(0x10004, teardown.cause)
        assertEquals(setOf("dun", "hipri", "default"), teardown.types)
        assertTrue(all.single { it.at == LocalDateTime.parse("2026-10-31T11:39:39.440093") }.wlan)
        // LocalDateTime.toString leaves out zero seconds; the hex of a negative int is its 32 bits.
        assertEquals(LocalDateTime.parse("2026-10-31T11:37:00"), RegistryDataLog.time("2026-10-31T11:37"))
        assertEquals(-7, RegistryDataLog.hex("fffffff9"))
        assertEquals(-8, RegistryDataLog.hex("fffffff8"))
    }

    @Test
    fun `case 2 at the check, the newest internet setup was refused with a hidden cause`() {
        // The check-now of 11:39:11: the second setup of row 24962, CONNECTING then DISCONNECTED.
        val attempt = SetupAttempts.newest(lines("2026-10-31T11:39:11"), 0, 3, VerdictFeature.MOBILE_DATA)!!
        assertEquals(65536, attempt.cause)
        assertEquals(24962L, attempt.rowId)
        assertEquals(LocalDateTime.parse("2026-10-31T11:37:44.896174"), attempt.at)
        assertTrue(DataFailCauseClass.hidden(attempt.cause))
        // Still there after IMS moved to LTE and back: those lines are the IMS APN's, not the internet APN's.
        assertEquals(24962L, SetupAttempts.newest(lines("2026-10-31T11:40:00"), 0, 3, VerdictFeature.MOBILE_DATA)!!.rowId)
        // Another slot or another SIM in the same slot: nothing.
        assertNull(SetupAttempts.newest(lines("2026-10-31T11:39:11"), 1, 3, VerdictFeature.MOBILE_DATA))
        assertNull(SetupAttempts.newest(lines("2026-10-31T11:39:11"), 0, 4, VerdictFeature.MOBILE_DATA))
    }

    @Test
    fun `the refusal goes away once it is no longer the newest state`() {
        // The restore: web.vodafone.de connected at 11:41:46.313, newer than the refusal.
        assertNull(SetupAttempts.newest(lines(), 0, 3, VerdictFeature.MOBILE_DATA))
        // The retry in progress (CONNECTING of the same profile after the refusal) is a later state of that profile.
        val retrying = upTo("2026-10-31T11:39:11") + "\n" + log.lineSequence().first { "11:37:44.757382" in it }
            .replace("11:37:44.757382", "11:49:42.200000").replace("netId: 125", "netId: 126")
        assertNull(SetupAttempts.newest(RegistryDataLog.parse(retrying), 0, 3, VerdictFeature.MOBILE_DATA))
        // Before case 2: the teardown of web.vodafone.de (DISCONNECTING, then DISCONNECTED with LOST_CONNECTION) is no setup attempt.
        assertNull(SetupAttempts.newest(lines("2026-10-31T11:37:40"), 0, 3, VerdictFeature.MOBILE_DATA))
    }

    @Test
    fun `the MMS and IMS APNs have their own attempts, and an emergency profile never counts`() {
        val mms = SetupAttempts.newest(lines("2026-10-31T11:39:11"), 0, 3, VerdictFeature.MMS)!!
        assertEquals(55, mms.cause)
        assertFalse("#55 is no hidden cause", DataFailCauseClass.hidden(mms.cause))
        // IMS connected on LTE at 11:39:21 and on Wi-Fi at 11:39:39: no refused IMS setup.
        assertNull(SetupAttempts.newest(lines(), 0, 3, VerdictFeature.VOLTE))
        // Wi-Fi calling has no registry attempt here: its causes come from AOSP's IWLAN service.
        assertNull(SetupAttempts.newest(lines(), 0, 3, VerdictFeature.VOWIFI))
        val emergency = upTo("2026-10-31T11:39:11").replace("hipri | default", "emergency | default")
        assertNull(SetupAttempts.newest(RegistryDataLog.parse(emergency), 0, 3, VerdictFeature.MOBILE_DATA))
    }

    @Test
    fun `hidden keys on UNKNOWN and raw values below -6, never on AOSP's -1 to -6`() {
        assertTrue(DataFailCauseClass.hidden(65536))
        assertTrue(DataFailCauseClass.hidden(-7))
        assertTrue(DataFailCauseClass.hidden(-8))
        for (c in -6..-1) {
            assertFalse("$c", DataFailCauseClass.hidden(c))
            assertFalse("$c opens nothing", DataFailCauseClass.refusal(c))
        }
        assertFalse(DataFailCauseClass.hidden(0))
        assertFalse(DataFailCauseClass.hidden(27))
        assertTrue(DataFailCauseClass.refusal(27))
        assertTrue(DataFailCauseClass.refusal(65536))
        assertFalse("LOST_CONNECTION is no refusal", DataFailCauseClass.refusal(0x10004))
        assertFalse(DataFailCauseClass.refusal(0))
        // The class itself is never one of the three account or settings classes.
        assertEquals(CauseClass.HIDDEN, SetupRefusal(VerdictFeature.MOBILE_DATA, 65536).refusal()!!.cause)
        assertNull("no code is printed for a hidden value", SetupRefusal(VerdictFeature.MOBILE_DATA, -7).refusal()!!.code)
        assertEquals(CauseClass.WRONG_SETTINGS, SetupRefusal(VerdictFeature.MOBILE_DATA, 27).refusal()!!.cause)
    }

    @Test
    fun `the exact words, with OCD's rows and the account clause`() {
        fun line(f: VerdictFeature, row: ApnRowClass? = null, account: AccountState = AccountState.UNKNOWN, balance: String? = null) =
            CauseVerdicts.of(f, Refusal(CauseClass.HIDDEN, apnRow = row), account, balance)?.line
        assertEquals("Mobile data refused by the network; this phone's modem software hides the reason", line(VerdictFeature.MOBILE_DATA))
        assertEquals("Mobile data refused by the network; this phone's modem software hides the reason", line(VerdictFeature.MOBILE_DATA, ApnRowClass.OTHER))
        assertEquals(
            "Mobile data refused by the network for the access point under test; this phone's modem software hides the reason",
            line(VerdictFeature.MOBILE_DATA, ApnRowClass.TEST),
        )
        assertEquals(
            "Mobile data refused by the network for OCD's access point; this phone's modem software hides the reason",
            line(VerdictFeature.MOBILE_DATA, ApnRowClass.OCD),
        )
        assertEquals("VoLTE refused by the network; this phone's modem software hides the reason", line(VerdictFeature.VOLTE))
        assertEquals("VoLTE refused by the network for OCD's access point; this phone's modem software hides the reason", line(VerdictFeature.VOLTE, ApnRowClass.OCD))
        assertEquals("MMS refused by the network for the access point under test; this phone's modem software hides the reason", line(VerdictFeature.MMS, ApnRowClass.TEST))
        // A fresh blocked account read is added after the words, never in their place.
        assertEquals(
            "Mobile data refused by the network; this phone's modem software hides the reason; the account also reads as blocked (balance 0.10 EUR)",
            line(VerdictFeature.MOBILE_DATA, account = AccountState.BLOCKED, balance = "0.10 EUR"),
        )
        assertEquals(
            "Mobile data refused by the network; this phone's modem software hides the reason; the account also reads as blocked",
            line(VerdictFeature.MOBILE_DATA, account = AccountState.BLOCKED),
        )
        // Only mobile data carries the account clause.
        assertEquals("VoLTE refused by the network; this phone's modem software hides the reason", line(VerdictFeature.VOLTE, account = AccountState.BLOCKED))
        // Wi-Fi calling never gets the line.
        assertNull(line(VerdictFeature.VOWIFI))
        val v = CauseVerdicts.of(VerdictFeature.MOBILE_DATA, Refusal(CauseClass.HIDDEN), AccountState.BLOCKED, "0.10 EUR")!!
        assertEquals(CauseClass.HIDDEN, v.cause)
        assertFalse("never confident", v.confident)
        assertFalse("never (code N)", "(code" in v.line)
    }

    @Test
    fun `a hidden refusal is never not counted`() {
        val hidden = Refusal(CauseClass.HIDDEN)
        assertFalse(TestBlame.notCounted(hidden, AccountState.BLOCKED))
        assertFalse(TestBlame.notCounted(hidden.copy(userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE), AccountState.BLOCKED))
        assertFalse(TestBlame.notCounted(hidden, AccountState.UNKNOWN))
    }

    @Test
    fun `Details carry the raw value, the modem value when read, and the access point class`() {
        assertEquals(
            "Mobile data: Android cause UNKNOWN (0x10000), modem value -7; access point: other",
            HiddenDetails.line(VerdictFeature.MOBILE_DATA, 65536, -7, ApnRowClass.OTHER),
        )
        assertEquals("Mobile data: Android cause UNKNOWN (0x10000); access point: OCD test row", HiddenDetails.line(VerdictFeature.MOBILE_DATA, 65536, null, ApnRowClass.TEST))
        assertEquals("VoLTE: Android cause -8", HiddenDetails.line(VerdictFeature.VOLTE, -8))
        assertEquals("Wi-Fi calling: Android cause UNKNOWN (0x10000)", HiddenDetails.line(VerdictFeature.VOWIFI, 65536))
    }

    @Test
    fun `the modem value comes from the refused profile's data network in the phone service's dump`() {
        // The data network block as the S20's phone service prints it (DN-128 of 2026-10-31, masked), with
        // the response a refused setup of row 24962 leaves while its data network is still kept.
        val block = """
            |          Tag: DN-125-C
            |            mSubId=3
            |            mOnPreferredDataPhone=true
            |            mTransport=WWAN
            |            mDataProfile=[DataProfile=[ApnSetting] OCD-test-wrong-APN, 24962, 26202, ocdtest27.invalid, , null, , null, null, 0, hipri | default, IPV4V6, IP, true, 0, false, 0, 0, 0, 0, 0, null, , false, UNKNOWN, UNKNOWN, 0, -1, -1, false, 3, 961, false, CARRIER_EDITED, TrafficDescriptor={mDnn=ocdtest27.invalid, mOsAppId=null, mConnectionCapability=8}, preferred=true]
            |            mFailCause=UNKNOWN(0x10000)
            |            Local logs:
            |              2026-10-31T11:37:44.888174 - ConnectingState: onSetupResponse: resultCode=RESULT_SUCCESS, response=DataCallResponse: { cause=UNKNOWN(0xfffffff8) retry=717242 cid=1 linkStatus=0 protocolType=0 ifname=rmnet0 addresses=[] dnses=[] gateways=[] pcscf=[] mtu=1500 }
            |          ---------------
            |          Tag: DN-128-C
            |            mSubId=3
            |            mTransport=WWAN
            |            mDataProfile=[DataProfile=[ApnSetting] Vodafone.de, 5737, 26202, web.vodafone.de, , null, , null, null, 0, dun | hipri | default, IPV4V6, IP, true]
            |            mDataCallResponse=DataCallResponse: { cause=NONE(0x0) retry=-1 cid=1 linkStatus=1 }
            |          ---------------
        """.trimMargin()
        assertEquals(-8, ModemValue.of(block, 3, 24962))
        assertNull("another profile", ModemValue.of(block, 3, 5737))
        assertNull("another SIM", ModemValue.of(block, 4, 24962))
        assertNull("not read", ModemValue.of(null, 3, 24962))
        assertNull("a response of AOSP's own range is no modem value", ModemValue.of(block.replace("0xfffffff8", "0xfffffffd"), 3, 24962))
    }

    @Test
    fun `read-only shows the line only, while the feature is still down, without a row`() {
        val found = HiddenLines.fromRegistry(lines("2026-10-31T11:39:11"), 0)
        assertEquals(listOf(VerdictFeature.MOBILE_DATA), found.map { it.second })
        val refusals = found.map { (a, f) -> SetupRefusal(f, a.cause, subId = a.subId) }
        assertNull("the read-only builds never name a row", refusals.single().apnRow)
        assertEquals(refusals, HiddenLines.shown(refusals, 3, mobileDataDown = true, imsRegistered = true))
        // Mobile data on with a network up, off, or unreadable: no line. Another SIM: no line.
        assertTrue(HiddenLines.shown(refusals, 3, mobileDataDown = false, imsRegistered = true).isEmpty())
        assertTrue(HiddenLines.shown(refusals, 3, mobileDataDown = null, imsRegistered = true).isEmpty())
        assertTrue(HiddenLines.shown(refusals, 4, mobileDataDown = true, imsRegistered = true).isEmpty())
        // VoLTE only while IMS is not registered; a readable code is not the read-only part.
        val volte = SetupRefusal(VerdictFeature.VOLTE, 65536, subId = 3)
        assertTrue(HiddenLines.shown(listOf(volte), 3, null, imsRegistered = true).isEmpty())
        assertTrue(HiddenLines.shown(listOf(volte), 3, null, imsRegistered = null).isEmpty())
        assertEquals(listOf(volte), HiddenLines.shown(listOf(volte), 3, null, imsRegistered = false))
        assertTrue(HiddenLines.shown(listOf(SetupRefusal(VerdictFeature.MOBILE_DATA, 27, subId = 3)), 3, true, true).isEmpty())
        assertEquals("Mobile data refused by the network; this phone's modem software hides the reason", HiddenLines.line(refusals.single()))
        // After the restore nothing is found.
        assertTrue(HiddenLines.fromRegistry(lines(), 0).isEmpty())
    }

    @Test
    fun `the deep check reads it from the registry dump it already reads, with no new command`() {
        val ran = mutableListOf<ShellCommand>()
        val runner = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> "  SIM slot: [0] state: [REGISTERED] IMS Profile: [Name : VODAFONE Germany IMS VoLTE, pdn : ims]"
                    ShellCommand.WIFI_CALLING_SWITCH -> "1"
                    ShellCommand.TELEPHONY_REGISTRY -> upTo("2026-10-31T11:39:11")
                    else -> ""
                }
                return read(output.lineSequence())
            }
        }
        val reading = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, null), runner)
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.WIFI_CALLING_SWITCH), ran)
        assertEquals(listOf(SetupRefusal(VerdictFeature.MOBILE_DATA, 65536, subId = 3)), reading.refusals)
        val encoded = reading.encode()
        assertFalse("no APN name is kept", "ocdtest27" in encoded || "OCD-test" in encoded)
        assertFalse("no row ID is kept", "24962" in encoded)
        assertEquals(reading, DeepCheckReading.decode(encoded))
    }
}
