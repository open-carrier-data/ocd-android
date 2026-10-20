package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTargetsFile
import io.github.open_carrier_data.check.core.NetworkTarget
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.core.SpnTarget
import io.github.open_carrier_data.check.owner.core.CallDevice
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallsLevel
import io.github.open_carrier_data.check.owner.core.CallsOutDue
import io.github.open_carrier_data.check.owner.core.CallsOutMode
import io.github.open_carrier_data.check.owner.core.CallsOutPlan
import io.github.open_carrier_data.check.owner.core.EmergencySeen
import io.github.open_carrier_data.check.owner.core.MailboxCaller
import io.github.open_carrier_data.check.owner.core.MailboxGuard
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.MuteState
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PointerLedger
import io.github.open_carrier_data.check.owner.core.Refusal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 26 automatic check 4: the owner build places the "calls out" call
 * itself, to the SIM's own allowlisted mailbox only, under the emergency
 * check, the service-prefix refusal and the LB6 refusals, muted, at night,
 * and ends it itself after ACTIVE and about 15 s. Every guard is tested
 * against a phone whose registry plays the call, and a device that records
 * what it was asked to do. No real call is ever placed here.
 */
class CallsOutTest {
    private val table: CallTargetsFile = CallTargets.parse(File("src/main/assets/call-targets.json").readText())

    /** What the app asked the phone's Telecom and audio for. */
    class FakeDevice(private val phone: CallingPhone) : CallDevice {
        var emergencyAnswer: (String) -> Boolean = { it in setOf("112", "110") }
        var emergencyThrows = false
        var inEmergency: Boolean? = false

        /** Telecom says an emergency call is up from this many asks on. */
        var emergencyAfterAsks: Int? = null
        private var asks = 0
        var canMute = true
        var leave: String? = null
        val placed = mutableListOf<Pair<String, Int>>()
        var ends = 0
        val events = mutableListOf<String>()
        var restoredOk = true
        var deleted: Triple<String, Long, Long>? = null

        override suspend fun isEmergencyNumber(number: String, subId: Int): Boolean {
            if (emergencyThrows) error("no telephony")
            return emergencyAnswer(number)
        }

        override suspend fun inEmergencyCall(): Boolean? {
            asks++
            return if (emergencyAfterAsks?.let { asks >= it } == true) true else inEmergency
        }

        override suspend fun mute(): MuteState? {
            if (!canMute) return null
            events += "mute"
            return MuteState(volumeBefore = 4, mutedBefore = false, micMutedBefore = false, muted = true)
        }

        override suspend fun muteAgain(state: MuteState) {
            events += "mute again"
        }

        override suspend fun restore(state: MuteState): Boolean {
            events += "restore"
            return restoredOk
        }

        override suspend fun place(number: String, subId: Int): Boolean {
            events += "place $number"
            placed += number to subId
            phone.dial()
            return true
        }

        override suspend fun end(): Boolean {
            events += "end"
            ends++
            phone.hangUp()
            return true
        }

        override suspend fun deleteLogEntry(number: String, fromMs: Long, toMs: Long): Int {
            deleted = Triple(number, fromMs, toMs)
            return 1
        }

        override suspend fun leaveReason(): String? = leave
    }

    /**
     * The fake phone with a call in its registry: dialling, then ACTIVE after
     * [activeAfterMs] with PHH's call-quality reports ([rate] packets a second
     * each way), then idle with the disconnect cause the end gives.
     */
    class CallingPhone(store: MemoryStore = MemoryStore(OwnerState(rebootCleared = true))) : FakePhone(store) {
        var dialAt: Long? = null
        var hangUpAt: Long? = null
        var activeAfterMs = 2_000L
        var neverActive = false
        var rate = 50L

        /** The call type PHH reports; null: the bearer's own (18 on Wi-Fi, 13 on LTE). */
        var callTypeOverride: Int? = null

        /** Seconds after ACTIVE when the registry's emergency number gets set, or another call rings. */
        var emergencyAfterMs: Long? = null
        var ringAfterMs: Long? = null
        var endCause = "501 : CODE_USER_TERMINATED, 0, User terminated IMS call"

        fun dial() {
            dialAt = uptime
        }

        fun hangUp() {
            if (hangUpAt == null) hangUpAt = uptime
        }

        private fun activeAt(): Long? = dialAt?.let { it + activeAfterMs }?.takeIf { !neverActive }

        override suspend fun registryText(): String {
            var text = super.registryText()
            val reason = Regex("mImsCallDisconnectCause=ImsReasonInfo :: \\{[^}]*\\}")
            val dial = dialAt ?: return text.replaceFirst(reason, "mImsCallDisconnectCause=ImsReasonInfo :: {510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier}")
            val ended = hangUpAt?.let { uptime >= it + 500 } == true
            val active = activeAt()?.let { uptime >= it } == true
            val onWifi = "state: CONNECTED, transport: WLAN" in text
            val type = callTypeOverride ?: if (onWifi) 18 else 13
            if (ended) {
                return text.replaceFirst(Regex("mImsCallDisconnectCause=ImsReasonInfo :: \\{[^}]*\\}"), "mImsCallDisconnectCause=ImsReasonInfo :: {$endCause}")
            }
            val since = activeAt()?.let { uptime - it } ?: 0
            val ring = ringAfterMs?.let { active && since >= it } == true
            text = text.replaceFirst("mCallState=0", "mCallState=${if (ring) 1 else 2}")
                .replaceFirst("Foreground call state: 0", "Foreground call state: ${if (active) 1 else 3}")
            if (active) {
                val seconds = since / 1000
                text = text.replaceFirst(
                    "mCallNetworkType=0",
                    "mCallNetworkType=${if (seconds >= 1) type else 0}\n    mCallQuality=CallQuality: {downlinkCallQualityLevel=0 uplinkCallQualityLevel=0 callDuration=${since} " +
                        "numRtpPacketsTransmitted=${seconds * rate} numRtpPacketsReceived=${seconds * rate} rtpInactivityDetected=false txSilenceDetected=false rxSilenceDetected=false}",
                )
            }
            if (emergencyAfterMs?.let { active && since >= it } == true) text = text.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=112")
            // The disconnect cause of an earlier call stays until this one ends.
            return text.replaceFirst(Regex("mImsCallDisconnectCause=ImsReasonInfo :: \\{[^}]*\\}"), "mImsCallDisconnectCause=ImsReasonInfo :: {510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier}")
        }
    }

    private val sim = OwnerFixtures.SIM
    private val wifiDue = CallsOutDue(0, sim.key, OwnerFeature.VOWIFI, ProvenBearer.IWLAN, CallsOutPlan.AFTER_APPLY)

    /** The S20 at home: privileged route, IMS registered for calls over Wi-Fi with Wi-Fi calling on. */
    private fun phone(): CallingPhone = CallingPhone().apply {
        privileged = true
        imsOverWifi = true
        wifi = true
        defaultApp = mapOf("carrier_wfc_ims_available_bool" to "true", "carrier_volte_available_bool" to "true")
    }

    private fun caller(phone: CallingPhone, device: FakeDevice) =
        MailboxCaller(phone, phone.store, device, "rom-1", "TQ1 eng, phh_ims", "phh_ims", "SM-G981B", { PointerLedger.cell(io.github.open_carrier_data.check.core.DeviceInfo("samsung", "SM-G981B", "x1s", 16, io.github.open_carrier_data.check.core.RomFamily.LINEAGEOS), it) })

    private fun run(phone: CallingPhone, device: FakeDevice, mode: CallsOutMode = CallsOutMode.LIVE, night: Boolean = true, due: CallsOutDue = wifiDue) =
        runBlocking { caller(phone, device).run(sim, due, table, mode, night, "2026-10-21") }

    // --- only the SIM's own mailbox, never an emergency or service number ---

    @Test
    fun `the only number is the SIM's own allowlisted mailbox`() = runBlocking {
        val device = FakeDevice(CallingPhone())
        assertEquals("5500", MailboxGuard.target(table, sim, device).first?.number)
        // Another network's SIM gets its own entry, never LIDL's.
        val telekom = OwnerSim(0, 5, SimIdentity("26201", "Telekom.de", null, emptySet()), "Telekom", 1)
        assertEquals("3311", MailboxGuard.target(table, telekom, device).first?.number)
        // A SIM the table does not know gets no number at all, never a fallback.
        val uk = OwnerSim(0, 6, SimIdentity("23410", "O2 - UK", null, emptySet()), "O2", 2)
        assertEquals(null to "no_mailbox_for_this_sim", MailboxGuard.target(table, uk, device))
        // A code the table marks unverified is never dialled automatically.
        val congstar = OwnerSim(0, 7, SimIdentity("26201", "congstar", null, emptySet()), "congstar", 3)
        assertEquals(null to "mailbox_unverified", MailboxGuard.target(table, congstar, device))
        assertEquals(null to "mailbox_table_unreadable", MailboxGuard.target(null, sim, device))
    }

    @Test
    fun `a poisoned table never gets an emergency, service or odd number dialled`() = runBlocking {
        val device = FakeDevice(CallingPhone())
        for ((number, why) in listOf(
            "112" to "not_allowed", "110" to "not_allowed", "911" to "not_allowed", "116117" to "not_allowed", "19222" to "not_allowed",
            "11880" to "not_allowed", "0800123" to "not_allowed", "*100#" to "not_allowed", "+495500" to "not_allowed", "55" to "not_allowed",
        )) {
            val poisoned = CallTargetsFile("poisoned", listOf(SpnTarget("LIDL Connect", listOf("26202"), "LIDL", number, "test")), mapOf("26202" to NetworkTarget("Vodafone", number, "test")))
            val (mailbox, reason) = MailboxGuard.target(poisoned, sim, device)
            assertNull(number, mailbox)
            assertTrue("$number: $reason", reason == why || reason == "no_mailbox_for_this_sim")
        }
        for (number in listOf("112", "110", "999", "116117", "19222")) assertEquals(number, "emergency_number", MailboxGuard.refusal(number))
        for (number in listOf("11880", "0800", "1955")) assertTrue(number, MailboxGuard.refusal(number) in setOf("service_prefix", "emergency_number", "not_a_short_code"))
        assertEquals("not_a_short_code", MailboxGuard.refusal("*100#"))
        assertNull(MailboxGuard.refusal("5500"))
    }

    @Test
    fun `Android's emergency answer is asked, and a yes or no answer refuses`() = runBlocking {
        val device = FakeDevice(CallingPhone())
        device.emergencyAnswer = { it == "5500" }
        assertEquals(null to "android_says_emergency_number", MailboxGuard.target(table, sim, device))
        device.emergencyThrows = true
        assertEquals(null to "emergency_check_unreadable", MailboxGuard.target(table, sim, device))
    }

    // --- the call itself ---

    @Test
    fun `a muted mailbox call over Wi-Fi goes ACTIVE, is measured, ended by the phone, and counts as calls out`() {
        val phone = phone()
        val device = FakeDevice(phone)
        val outcome = run(phone, device)
        assertEquals(outcome.reason, "passed", outcome.result)
        assertEquals(listOf("5500" to sim.subId), device.placed)
        // Muted before the dial, again once up, given back at the end; the app ended the call itself, once.
        assertEquals(listOf("mute", "place 5500", "mute again", "end", "restore"), device.events)
        val record = outcome.record!!
        assertEquals(CallMethod.MAILBOX_CALL, record.method)
        assertEquals(CallDirection.OUTGOING, record.direction)
        assertEquals(ProvenBearer.IWLAN, record.bearer?.bearer)
        assertTrue(record.rtp!!.passed)
        assertEquals(true, record.hungUpByPhone)
        assertEquals(501, record.disconnect?.imsCode)
        assertEquals(true, record.disconnectFresh)
        // Held about 15 s after ACTIVE: the packet window is at least 10 s.
        assertTrue(record.rtp!!.windowMs!! >= 10_000)
        // Exactly this call's log entry, by number and time.
        assertEquals("5500", device.deleted?.first)
        val state = phone.store.state
        assertEquals(listOf(record), state.calls)
        assertEquals(1, state.callsOutNights.size)
        assertEquals(CallsLevel.CALLS_OUT, state.retested.single().level)
        assertEquals("vowifi:on", state.retested.single().entry)
        assertTrue(state.retested.single().line(java.time.ZoneOffset.UTC).contains("incoming not tested"))
        // Never the number in the record.
        assertFalse(state.calls.toString().contains("5500"))
        assertTrue(state.log.last().text, "calls out work (audio from packet counts); incoming not tested" in state.log.last().text)
    }

    @Test
    fun `the dry run passes every guard, logs what it would dial, and dials nothing`() {
        val phone = phone()
        val device = FakeDevice(phone)
        val outcome = run(phone, device, mode = CallsOutMode.DRY_RUN)
        assertEquals("dry_run", outcome.result)
        assertTrue(device.events.isEmpty())
        assertTrue(phone.writes.isEmpty())
        val line = phone.store.state.log.last { it.kind == "calls_out" }.text
        assertTrue(line, line.startsWith("dry run: would dial the LIDL Connect (Vodafone network) mailbox 5500 on slot 1 for Wi-Fi calling over Wi-Fi"))
        assertTrue(line, "every guard passed" in line && "nothing dialled" in line)
        // A dry run outside the night names the refusal it would meet, and still dials nothing.
        val day = run(phone, device, mode = CallsOutMode.DRY_RUN, night = false)
        assertEquals("dry_run", day.result)
        assertTrue(phone.store.state.log.last().text, "outside_the_night_window" in phone.store.state.log.last().text)
        assertTrue(device.placed.isEmpty())
    }

    @Test
    fun `round 25's LB6 refusals, the stop flag, the night and an unreadable emergency state each refuse before any dial`() {
        val cases = listOf<Pair<String, (CallingPhone, FakeDevice) -> Unit>>(
            Refusal.CALL_ACTIVE.wire to { p, _ -> p.call = true },
            Refusal.EMERGENCY_CALLBACK_MODE.wire to { p, _ -> p.ecbm = true },
            Refusal.EMERGENCY_SMS_MODE.wire to { p, _ -> p.emergencySms = true },
            Refusal.JUST_BOOTED.wire to { p, _ -> p.uptime = 60_000 },
            Refusal.ROAMING.wire to { p, _ -> p.roaming = true },
            Refusal.EMERGENCY_STATE_UNREADABLE.wire to { p, _ -> p.emergencyApiAnswers = false },
            Refusal.EMERGENCY_CALL_RECENT.wire to { p, _ -> p.store.state = p.store.state.copy(emergencySeen = EmergencySeen(42, p.now - 60_000)) },
            Refusal.STOPPED.wire to { p, _ -> p.store.state = p.store.state.copy(stop = "lost_voice") },
            "emergency_call_unreadable" to { _, d -> d.inEmergency = null },
            "emergency_call_active" to { _, d -> d.inEmergency = true },
            "ims_not_on_wifi" to { p, _ -> p.imsOverWifi = false },
        )
        for ((why, setUp) in cases) {
            val phone = phone()
            val device = FakeDevice(phone)
            setUp(phone, device)
            val outcome = run(phone, device)
            assertEquals(why, "refused", outcome.result)
            assertTrue("$why in ${outcome.reason}", why in outcome.reason.orEmpty())
            assertTrue(why, device.placed.isEmpty())
            assertTrue(why, device.events.isEmpty())
        }
        val phone = phone()
        val device = FakeDevice(phone)
        assertEquals("outside_the_night_window", run(phone, device, night = false).reason)
        assertTrue(device.placed.isEmpty())
        // A stream that cannot be muted: no call.
        val noMute = FakeDevice(phone).apply { canMute = false }
        assertEquals("voice_stream_not_muted", run(phone, noMute).reason)
        assertTrue(noMute.placed.isEmpty())
    }

    @Test
    fun `an emergency mark during the call aborts at once - the sound back, no call touched, nothing kept`() {
        val phone = phone().apply { emergencyAfterMs = 3_000 }
        val device = FakeDevice(phone)
        val outcome = run(phone, device)
        assertEquals("aborted", outcome.result)
        assertEquals("emergency", outcome.reason)
        assertEquals(0, device.ends)
        assertEquals(listOf("mute", "place 5500", "mute again", "restore"), device.events)
        assertTrue(phone.store.state.calls.isEmpty())
        assertNull(device.deleted)
        // Telecom's own emergency call flag does the same.
        val phone2 = phone()
        val device2 = FakeDevice(phone2).apply { emergencyAfterAsks = 4 }
        val aborted = run(phone2, device2)
        assertEquals("aborted", aborted.result)
        assertEquals(0, device2.ends)
        assertTrue(phone2.store.state.calls.isEmpty())
    }

    @Test
    fun `PHH's default 13 while IMS runs over Wi-Fi is not counted, and thin RTP or no ACTIVE fails`() {
        val mismatch = phone().apply { callTypeOverride = 13 }
        val notCounted = run(mismatch, FakeDevice(mismatch))
        assertEquals("not_counted_retry", notCounted.result)
        assertFalse(notCounted.record!!.bearer!!.counted)

        val thin = phone().apply { rate = 29 }
        val failed = run(thin, FakeDevice(thin))
        assertEquals("failed", failed.result)
        assertTrue(failed.reason, "rtp:sent_290_per_10s_under_300" in failed.reason.orEmpty())

        val never = phone().apply { neverActive = true }
        val device = FakeDevice(never)
        val noActive = run(never, device)
        assertEquals("failed", noActive.result)
        assertTrue(noActive.reason, "not_active" in noActive.reason.orEmpty())
        assertEquals(1, device.ends)
    }

    @Test
    fun `another call ringing ends the app's own call and the result does not count`() {
        val phone = phone().apply { ringAfterMs = 4_000 }
        val device = FakeDevice(phone)
        val outcome = run(phone, device)
        assertEquals("not_counted_retry", outcome.result)
        assertTrue(outcome.reason, "another_call" in outcome.reason.orEmpty())
        assertTrue(device.ends >= 1)
    }

    @Test
    fun `a pulled charger ends the call early and it does not count`() {
        val phone = phone()
        val device = FakeDevice(phone).apply { leave = "unplugged" }
        val outcome = run(phone, device)
        assertTrue(outcome.reason, "unplugged" in outcome.reason.orEmpty())
        assertTrue(outcome.result != "passed")
    }

    @Test
    fun `VoLTE's call while IMS runs over Wi-Fi moves IMS to LTE inside the non-persistent override, and reverts it with proof`() {
        val phone = phone()
        val device = FakeDevice(phone)
        val lteDue = CallsOutDue(0, sim.key, OwnerFeature.VOLTE, ProvenBearer.LTE, CallsOutPlan.WEEKLY)
        val outcome = run(phone, device, due = lteDue)
        assertTrue(phone.writes.toString(), "set carrier_wfc_ims_available_bool=false" in phone.writes)
        assertEquals("clear", phone.writes.last { it == "clear" })
        assertTrue("the override went with the revert", phone.overrides.orEmpty().isEmpty())
        assertTrue(phone.store.state.overrides.isEmpty())
        // On LTE the call type 13 meets an ims connection on WWAN and the LTE data RAT: proven.
        assertEquals(outcome.reason, "passed", outcome.result)
        assertEquals(ProvenBearer.LTE, outcome.record?.bearer?.bearer)
    }

    @Test
    fun `at most one call per feature and bearer a night`() {
        val phone = phone()
        // A call that did not count still uses the night's one call.
        phone.callTypeOverride = 13
        assertEquals("not_counted_retry", run(phone, FakeDevice(phone)).result)
        val state = phone.store.state.copy(
            provisional = listOf(io.github.open_carrier_data.check.owner.core.ProvisionalKeep(0, sim.key, OwnerFeature.VOWIFI, true, ProvenBearer.IWLAN, 0, Long.MAX_VALUE)),
        )
        val switches = mapOf(sim.key to mapOf(OwnerFeature.VOWIFI to true))
        assertTrue(CallsOutPlan.due(listOf(sim), state, switches, emptyMap(), "rom-1", "2026-10-21", phone.now).isEmpty())
        assertEquals(listOf(CallsOutPlan.AFTER_APPLY), CallsOutPlan.due(listOf(sim), state, switches, emptyMap(), "rom-1", "2026-10-22", phone.now).map { it.why })
    }
}
