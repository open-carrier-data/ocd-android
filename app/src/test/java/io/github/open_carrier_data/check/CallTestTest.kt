package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CallBearer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallSample
import io.github.open_carrier_data.check.core.CallState
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTargetsFile
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CallTestRecord
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.ConfigOrigin
import io.github.open_carrier_data.check.core.T2Feature
import io.github.open_carrier_data.check.core.AudioAnswer
import io.github.open_carrier_data.check.core.NetworkTarget
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.SpnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guided call test (OCD round 19): only a SIM's own mailbox from the
 * bundled copy of OCD Lab's call_targets.json is ever shown, never an
 * emergency or service number; the watch reads the call state and call
 * network type from registry dumps; an emergency call discards the watch;
 * and the stored wording never says "works".
 */
class CallTestTest {
    private val asset = File("src/main/assets/${CallTargets.ASSET_NAME}")
    private val targets: CallTargetsFile by lazy { CallTargets.parse(asset.readText()) }
    private val notEmergency = { _: String -> false }

    @Test
    fun `the bundled table parses and every number in it may be shown`() {
        val numbers = targets.bySpn.map { it.mailbox } + targets.byMccMnc.values.map { it.mailbox }
        assertTrue(numbers.isNotEmpty())
        for (number in numbers) assertTrue(number, CallTargets.safe(number, notEmergency))
    }

    @Test
    fun `each SIM gets its own mailbox, the SPN rule first (lab mailbox_for)`() {
        val lidl = CallTargets.mailboxFor(targets, "26202", "LIDL Connect", notEmergency)!!
        assertEquals("5500", lidl.number)
        assertEquals("LIDL Connect (Vodafone network)", lidl.brand)
        assertFalse(lidl.unverified)
        assertEquals("5500", CallTargets.mailboxFor(targets, "26202", "", notEmergency)!!.number)
        assertEquals("333", CallTargets.mailboxFor(targets, "26203", "o2 - de", notEmergency)!!.number)
        assertEquals("3311", CallTargets.mailboxFor(targets, "26201", "Telekom.de", notEmergency)!!.number)
        assertEquals("333", CallTargets.mailboxFor(targets, "26223", null, notEmergency)!!.number)
        assertTrue(CallTargets.mailboxFor(targets, "26201", "congstar", notEmergency)!!.unverified)
        // An SPN rule binds only on its own networks; the case is ignored as in the lab.
        assertEquals("LIDL Connect (Vodafone network)", CallTargets.mailboxFor(targets, "26202", "lidl connect", notEmergency)!!.brand)
        assertEquals("Telekom", CallTargets.mailboxFor(targets, "26201", "LIDL Connect", notEmergency)!!.brand)
        assertNull(CallTargets.mailboxFor(targets, "99999", "LIDL Connect", notEmergency))
        assertNull(CallTargets.mailboxFor(targets, null, "LIDL Connect", notEmergency))
    }

    @Test
    fun `no emergency, service or odd number is ever shown, whatever the table says (lab check_auto_call)`() {
        val refused = listOf(
            "110", "112", "911", "999", "000", "08", "0800", "08001234567", "116117", "116000", "19222", "11880", "115",
            "*#06#", "**21*5500#", "+495500", "5500,1", "", "12", "1234567", "5 500",
        )
        for (number in refused) {
            assertFalse(number, CallTargets.safe(number, notEmergency))
            val poisoned = CallTargetsFile(
                description = "test",
                bySpn = listOf(SpnTarget("LIDL Connect", listOf("26202"), "x", number, "test")),
                byMccMnc = mapOf("26202" to NetworkTarget("y", number, "test")),
            )
            assertNull(number, CallTargets.mailboxFor(poisoned, "26202", "LIDL Connect", notEmergency))
        }
        // Android's own answer counts too, and a failing answer shows nothing.
        assertNull(CallTargets.mailboxFor(targets, "26202", "LIDL Connect") { it == "5500" })
        assertNull(CallTargets.mailboxFor(targets, "26202", "LIDL Connect") { error("no telephony") })
        // A poisoned SPN rule never falls back to the network's entry: no number at all.
        val poisonedSpn = targets.copy(bySpn = listOf(SpnTarget("LIDL Connect", listOf("26202"), "x", "112", "test")))
        assertNull(CallTargets.mailboxFor(poisonedSpn, "26202", "LIDL Connect", notEmergency))
    }

    private fun registry(): String = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_lte.txt")).readText()

    private val mailboxes = setOf("5500", "3311", "333")

    /**
     * One registry reading of phone 0. [incoming] is the line Android 16 prints
     * as mCallIncomingNumber (TelephonyRegistry.dump), placed after mCallState
     * as the dump orders it.
     */
    private fun sample(state: Int, foreground: Int, type: Int, emergency: String = "null", incoming: String? = null): CallSample {
        val text = registry()
            .replaceFirst("mCallState=0", "mCallState=$state" + (incoming?.let { "\n    mCallIncomingNumber=$it" } ?: ""))
            .replaceFirst("Foreground call state: 0", "Foreground call state: $foreground")
            .replaceFirst("mCallNetworkType=0", "mCallNetworkType=$type")
            .replaceFirst("    mSrvccState=-1", "    mOutgoingCallEmergencyNumber=$emergency\n    mSrvccState=-1")
        return RegistryParser.parse(text.lineSequence(), SimSlot.of(0)!!, mailboxes)!!.call
    }

    private fun watch(vararg samples: CallSample?): CallWatchSummary = CallWatch().apply { samples.forEach(::add) }.summary()

    @Test
    fun `an outgoing mailbox call over LTE (lab test_guided_call_summary, S20 2026-10-19)`() {
        val summary = watch(
            sample(0, 0, 0), sample(2, 3, 0), sample(2, 4, 13), sample(2, 1, 13), sample(2, 1, 13), sample(0, 0, 0),
        )
        assertEquals(listOf(CallState.IDLE, CallState.OFFHOOK), summary.statesSeen)
        assertEquals(CallDirection.OUTGOING, summary.direction)
        assertTrue(summary.connected && summary.wentActive && summary.ended && summary.startedIdle)
        assertEquals(13, summary.callNetworkType)
        assertEquals(listOf(13), summary.callNetworkTypes)
        assertEquals(CallBearer.VOLTE, summary.bearer)
        assertEquals(1, summary.callsSeen)
        assertFalse(summary.emergencySeen)
        assertEquals(summary, CallWatchSummary.decode(summary.encode()))
    }

    @Test
    fun `Wi-Fi calling is type 18, VoNR 20, and 2G or 3G a circuit-switched call`() {
        assertEquals(CallBearer.VOWIFI, watch(sample(0, 0, 0), sample(2, 1, 18), sample(0, 0, 0)).bearer)
        assertEquals(CallBearer.VONR, watch(sample(0, 0, 0), sample(2, 1, 20)).bearer)
        assertEquals(CallBearer.CS, watch(sample(0, 0, 0), sample(2, 1, 16)).bearer)
        // Off-hook but no type reported (an IMS stack without call quality): unknown, never guessed.
        assertEquals(CallBearer.UNKNOWN, watch(sample(0, 0, 0), sample(2, 1, 0)).bearer)
        // The type read while the call was active wins over a later one read while it ended.
        assertEquals(18, watch(sample(0, 0, 0), sample(2, 1, 18), sample(2, 8, 13)).callNetworkType)
    }

    @Test
    fun `an incoming call is told apart, and no call gives nothing to keep`() {
        val incoming = watch(sample(0, 0, 0), sample(1, 0, 0), sample(2, 1, 13), sample(0, 0, 0))
        assertEquals(CallDirection.INCOMING, incoming.direction)
        val none = watch(sample(0, 0, 0), sample(0, 0, 0))
        assertFalse(none.connected)
        assertNull(none.bearer)
        // A watch that began in the middle of a call cannot tell the direction.
        assertEquals(CallDirection.UNKNOWN, watch(sample(2, 1, 13), sample(0, 0, 0)).direction)
        val unreadable = watch(null, sample(0, 0, 0))
        assertEquals(1, unreadable.unreadable)
        assertEquals(2, watch(sample(0, 0, 0), sample(2, 1, 13), sample(0, 0, 0), sample(2, 1, 13)).callsSeen)
    }

    @Test
    fun `an emergency call during the watch discards it, and a number never leaves the parser`() {
        val emergency = "[EmergencyNumber: 112, countryIso=de, mnc=, src=db , routing=unknown, categories=ambulance fire police , urns=]"
        val discarded = watch(sample(0, 0, 0), sample(2, 3, 13, emergency), sample(2, 1, 13, emergency), sample(0, 0, 0, emergency))
        assertTrue(discarded.emergencySeen)
        assertFalse(discarded.connected)
        assertNull(discarded.callNetworkType)
        assertEquals(CallWatchSummary(samples = 4, emergencySeen = true), discarded)
        assertFalse("112" in discarded.encode())
        // An older emergency number still in the registry from before the watch is not a new call.
        val older = watch(sample(0, 0, 0, emergency), sample(2, 1, 13, emergency), sample(0, 0, 0, emergency))
        assertFalse(older.emergencySeen)
        assertTrue(older.connected)
    }

    private fun record(
        summary: CallWatchSummary,
        heard: AudioAnswer = AudioAnswer.YES,
        airplane: Boolean? = false,
        intended: CallDirection? = null,
        secondLine: Boolean? = null,
    ) = CallTestRecord(
        date = "2026-10-19", model = "SM-G981B", codename = "x1s", romFamily = "lineageos", androidMajor = 16,
        imsStack = "aosp_telephony", homeMccMnc = "26202", spn = "LIDL Connect", ocdProfile = "open.26202.8cebd7550c67",
        brand = "LIDL Connect (Vodafone network)", mailbox = "5500", watch = summary, airplaneModeAtStart = airplane,
        wifiAtStart = true, heard = heard, intended = intended, secondLine = secondLine,
        feature = intended?.let { T2Feature.VOLTE }, session = intended?.let { 1 },
    )

    @Test
    fun `a kept test says what was measured, never that a feature works`() {
        val lte = record(watch(sample(0, 0, 0), sample(2, 1, 13), sample(0, 0, 0)))
        assertEquals(
            "An outgoing call, call network type 13 (LTE): VoLTE (call over 4G), went active; you heard the mailbox.",
            lte.plain,
        )
        assertTrue(lte.measuredOutgoing)
        val wifi = record(watch(sample(0, 0, 0), sample(2, 1, 18), sample(0, 0, 0)))
        assertTrue(wifi.plain, "not the airplane-mode test" in wifi.plain)
        assertFalse("airplane" in record(wifi.watch, airplane = true).plain)
        assertFalse(record(lte.watch, heard = AudioAnswer.NOT_SURE).measuredOutgoing)
        val banned = Regex("(?i)\\bworks\\b|is working|calls work")
        for (test in listOf(lte, wifi, record(watch(sample(0, 0, 0), sample(0, 0, 0)), AudioAnswer.NO))) {
            assertFalse(test.plain, banned.containsMatchIn(test.plain))
        }
    }

    @Test
    fun `an incoming call from the second line - rang, went active, and the number compared in the parser only`() {
        val second = "+491701234567"
        val summary = watch(
            sample(0, 0, 0), sample(1, 0, 0, incoming = second), sample(2, 1, 13, incoming = second),
            sample(2, 1, 13, incoming = second), sample(0, 0, 0, incoming = ""),
        )
        assertEquals(CallDirection.INCOMING, summary.direction)
        assertTrue(summary.ringingSeen && summary.wentActive && summary.connected)
        assertEquals(false, summary.incomingIsMailbox)
        assertEquals(listOf(13), summary.callNetworkTypes)
        // The number reaches neither the sample nor the summary nor its encoding.
        assertFalse(second in sample(1, 0, 0, incoming = second).toString())
        for (digits in listOf(second, "491701234567", "1701234567")) assertFalse(digits in summary.encode())
        val kept = record(summary, intended = CallDirection.INCOMING, secondLine = true)
        assertEquals(
            "An incoming call, call network type 13 (LTE): VoLTE (call over 4G), went active; both of you heard each " +
                "other; you said it came from your second line.",
            kept.plain,
        )
        assertFalse(second in kept.plain)
    }

    @Test
    fun `a mailbox short code as the incoming number is flagged, never kept (lab incoming_number_is)`() {
        val mailbox = watch(sample(0, 0, 0), sample(1, 0, 0, incoming = "5500"), sample(2, 1, 13, incoming = "5500"), sample(0, 0, 0, incoming = ""))
        assertEquals(true, mailbox.incomingIsMailbox)
        assertTrue("mailbox short code" in record(mailbox, intended = CallDirection.INCOMING, secondLine = true).plain)
        // Digits only are compared; an empty number gives no answer; a watch without the codes compares nothing.
        assertEquals(true, sample(1, 0, 0, incoming = "55-00").incomingIsMailbox)
        assertEquals(false, sample(1, 0, 0, incoming = "+4917055000").incomingIsMailbox)
        assertNull(sample(1, 0, 0, incoming = "").incomingIsMailbox)
        assertNull(sample(1, 0, 0).incomingIsMailbox)
        val text = registry().replaceFirst("mCallState=0", "mCallState=1\n    mCallIncomingNumber=5500")
        assertNull(RegistryParser.parse(text.lineSequence(), SimSlot.of(0)!!)!!.call.incomingIsMailbox)
        // An outgoing call shows no incoming number: nothing to compare.
        assertNull(watch(sample(0, 0, 0), sample(2, 3, 0, incoming = ""), sample(2, 1, 13, incoming = "")).incomingIsMailbox)
        // Every code in the bundled table is one the watch compares with (lab allowed_mailboxes).
        assertEquals(listOf("3311", "333", "5500"), CallTargets.allMailboxes(targets))
    }

    @Test
    fun `the stored log keeps the newest 50 and survives a broken file`() {
        val one = record(watch(sample(0, 0, 0), sample(2, 1, 13), sample(0, 0, 0)))
        var log = CallTestLog()
        repeat(55) { log = log.plus(one.copy(date = "2026-10-${(it % 28 + 1).toString().padStart(2, '0')}")) }
        assertEquals(CallTestLog.MAX, log.tests.size)
        assertEquals(log, CallTestLog.decode(log.encode()))
        assertEquals(CallTestLog(), CallTestLog.decode("not json"))
        assertEquals(CallTestLog(), CallTestLog.decode("""{"schema":"other","tests":[]}"""))
    }

    @Test
    fun `a version 1 file from before T2 still reads, as version 2`() {
        val v1 = """{"schema":"ocd-check-call-tests.v1","tests":[{"date":"2026-10-19","model":"SM-G981B","codename":"x1s",""" +
            """"romFamily":"lineageos","androidMajor":16,"imsStack":"aosp_telephony","homeMccMnc":"26202","spn":"LIDL Connect",""" +
            """"ocdProfile":"open.26202.8cebd7550c67","brand":"LIDL Connect (Vodafone network)","mailbox":"5500",""" +
            """"watch":{"samples":5,"connected":true,"wentActive":true,"direction":"OUTGOING","callNetworkTypes":[13],""" +
            """"callNetworkType":13,"bearer":"VOLTE","callsSeen":1},"airplaneModeAtStart":false,"wifiAtStart":true,"heard":"YES"}]}"""
        val log = CallTestLog.decode(v1)
        assertEquals(CallTestLog.SCHEMA, log.schema)
        assertEquals(1, log.tests.size)
        val test = log.tests.single()
        assertNull(test.intended)
        assertNull(test.feature)
        assertEquals(ConfigOrigin.AS_FOUND, test.configOrigin)
        assertTrue(test.measuredOutgoing)
        assertEquals(1, log.nextSession())
    }

    @Test
    fun `the call watch's command is the registry dump, read-only`() {
        // The watch runs ShellCommand.TELEPHONY_REGISTRY only; AllowlistTest pins its argv.
        val service = File("src/main/java/io/github/open_carrier_data/check/phone/DeepCheckService.kt").readText()
        val watcher = service.substringAfter("internal object CallWatcher").substringBefore("/** Starts the argv")
        assertEquals(listOf("ShellCommand.TELEPHONY_REGISTRY"), Regex("ShellCommand\\.[A-Z_]+").findAll(watcher).map { it.value }.toList())
    }
}
