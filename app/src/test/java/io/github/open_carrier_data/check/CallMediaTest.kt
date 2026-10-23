package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallMediaParser
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.RtpFlow
import io.github.open_carrier_data.check.core.SimSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 26's shared readings (automatic check 2): the bearer proof and the
 * RTP counts, from `dumpsys telephony.registry` as the S20 prints it with
 * OCD's data-2026.10 loaded (2026-10-20): the ims row is named "Vodafone.de",
 * PHH IMS fills mCallQuality once a second, and the disconnect fields stay
 * until the next call ends.
 */
class CallMediaTest {
    /** One phone block in the registry's shape; identities are made up or left out. */
    private fun registry(
        callState: Int = 0,
        foreground: Int = -1,
        callType: Int = 0,
        imsTransport: String? = "WLAN",
        dataRat: String = "LTE",
        quality: CallQualityReading = CallQualityReading(),
        imsReason: String = "0 : CODE_UNSPECIFIED, 0, null",
        cause: Int = -1,
        emergency: String = "null",
    ): String {
        val ims = when (imsTransport) {
            "WLAN" -> "Pair{1 [ApnSetting] Vodafone.de, 22096, 26202, ims, , null, , null, null, 0, ims, IPV6, IP, true, 0}= state: CONNECTED, transport: WLAN, id: 104399, netId: 107, network type: IWLAN, APN Setting: [ApnSetting] Vodafone.de, 22096, 26202, ims, , null, , null, null, 0, ims, IPV6, IP, true, 0, false, 0, link properties: {InterfaceName: ipsec1 LinkAddresses: [ 2001:db8::1/64 ]}"
            "WWAN" -> "Pair{1 [ApnSetting] Vodafone.de, 22096, 26202, ims, , null, , null, null, 0, ims, IPV6, IP, true, 0}= state: CONNECTED, transport: WWAN, id: 2, netId: 107, network type: LTE, APN Setting: [ApnSetting] Vodafone.de, 22096, 26202, ims, , null, , null, null, 0, ims, IPV6, IP, true, 0, false, 0, link properties: {InterfaceName: rmnet1 LinkAddresses: [ 2001:db8::2/64 ]}"
            "BOTH" -> registry(imsTransport = "WLAN").lineSequence().first { "mPreciseDataConnectionStates" in it }.substringAfter("={") + ", " +
                registry(imsTransport = "WWAN").lineSequence().first { "mPreciseDataConnectionStates" in it }.substringAfter("={")
            else -> ""
        }
        return """
            |  Phone Id=0
            |    mCallState=$callState
            |    mPreciseCallState=Ringing call state: -1, Foreground call state: $foreground, Background call state: -1, Disconnect cause: -1, Precise disconnect cause: -1
            |    mCallDisconnectCause=$cause
            |    mCallIncomingNumber=
            |    mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), getRilDataRadioTechnology=18(IWLAN), mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WLAN registrationState=HOME networkRegistrationState=HOME roamingType=NOT_ROAMING accessNetworkTechnology=IWLAN rejectCause=0 emergencyEnabled=false availableServices=[DATA] cellIdentity=null}, NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=HOME networkRegistrationState=HOME roamingType=NOT_ROAMING accessNetworkTechnology=$dataRat rejectCause=0 emergencyEnabled=false availableServices=[DATA] cellIdentity=null}]}
            |    mImsCallDisconnectCause=ImsReasonInfo :: {$imsReason}
            |    mCallPreciseDisconnectCause=-1
            |    mCallQuality=CallQuality: {downlinkCallQualityLevel=0 uplinkCallQualityLevel=0 callDuration=${quality.durationMs} numRtpPacketsTransmitted=${quality.rtpSent} numRtpPacketsReceived=${quality.rtpReceived} numRtpPacketsTransmittedLost=0 numRtpPacketsNotReceived=0 averageRelativeJitter=0 maxRelativeJitter=0 averageRoundTripTime=0 codecType=0 rtpInactivityDetected=${quality.rtpInactive} txSilenceDetected=false rxSilenceDetected=false numVoiceFrames=0 numNoDataFrames=0 numDroppedRtpPackets=0 minPlayoutDelayMillis=0 maxPlayoutDelayMillis=0 numRtpSidPacketsReceived=0 numRtpDuplicatePackets=0}
            |    mCallNetworkType=$callType
            |    mPreciseDataConnectionStates={Pair{1 [ApnSetting] Vodafone.de, 22086, 26202, web.vodafone.de, , null, , null, null, 0, dun | hipri | default, IPV4V6, IP}= state: CONNECTED, transport: WWAN, id: 1, netId: 127, network type: LTE, APN Setting: [ApnSetting] Vodafone.de, 22086, 26202, web.vodafone.de, , null, , null, null, 0, dun | hipri | default, IPV4V6, IP, true, link properties: {InterfaceName: rmnet0}, $ims}
            |    mOutgoingCallEmergencyNumber=$emergency
            |  Phone Id=1
            |    mCallState=0
            """.trimMargin()
    }

    private fun parse(text: String) = RegistryParser.parse(text.lineSequence(), SimSlot.of(0)!!)!!

    @Test
    fun `the registry gives the bearer readings, RTP and the disconnect cause, never the message`() {
        val text = registry(
            callState = 2, foreground = 1, callType = 18, quality = CallQualityReading(4_000, 200, 198, false),
            imsReason = "501 : CODE_USER_TERMINATED, 0, User terminated IMS call",
        )
        val phone = parse(text)
        assertEquals(listOf(ImsTransport.IWLAN), phone.imsPdn)
        assertEquals("lte", phone.wwanDataRat)
        assertEquals(BearerReading(18, listOf(ImsTransport.IWLAN), "lte"), phone.call.bearer)
        // Round 28: every key is read by name; the silence flags are never read (no stack fills them).
        assertEquals(
            CallQualityReading(4_000, 200, 198, false, notReceived = 0, sidReceived = 0, duplicates = 0, codecType = 0, avgRttMs = 0),
            phone.call.quality,
        )
        assertEquals(DisconnectReading(501, "CODE_USER_TERMINATED", -1, -1), phone.call.disconnect)
        assertFalse(phone.call.disconnect.toString(), "User terminated" in phone.call.disconnect.toString())
        // The internet row on WWAN is not the ims data connection.
        assertEquals(listOf(ImsTransport.LTE), parse(registry(imsTransport = "WWAN")).imsPdn)
        assertEquals(emptyList<ImsTransport>(), parse(registry(imsTransport = null)).imsPdn)
    }

    @Test
    fun `the ims data connection is found by its APN types, the old entry name ims still works`() {
        assertTrue(CallMediaParser.isImsApn("Vodafone.de, 22096, 26202, ims, , null, , null, null, 0, ims, IPV6, IP"))
        assertTrue(CallMediaParser.isImsApn("IMS, 22095, 26202, ims, , null, , null, null, 0, ims | xcap, IPV4V6, IP"))
        assertFalse(CallMediaParser.isImsApn("Vodafone.de, 22086, 26202, web.vodafone.de, , null, , null, null, 0, dun | hipri | default, IPV4V6, IP"))
        // An APN named ims that carries internet is not the ims connection.
        assertFalse(CallMediaParser.isImsApn("odd, 1, 26202, ims, , null, , null, null, 0, default, IPV4V6, IP"))
        // OCD Lab's fixture of 2026-10-19 cut the line: the APN name stands in.
        assertTrue(CallMediaParser.isImsApn("ims, 5175, 26202, ims, <cut>"))
        assertEquals(listOf(ImsTransport.LTE), OwnerlessFixtures.labRegistryPdn())
    }

    @Test
    fun `bearer proof - the call type must agree with the ims data connection and for LTE with the data RAT`() {
        val wifi = BearerReading(18, listOf(ImsTransport.IWLAN), "lte")
        val lte = BearerReading(13, listOf(ImsTransport.LTE), "lte")
        assertEquals(ProvenBearer.IWLAN to null, BearerProof.check(wifi))
        assertEquals(ProvenBearer.LTE to null, BearerProof.check(lte))
        assertEquals(ProvenBearer.LTE to null, BearerProof.check(lte.copy(wwanDataRat = "lte_ca")))
        // 13 is PHH's default call type: with the ims connection on Wi-Fi it proves nothing.
        assertEquals(ProvenBearer.LTE to "ims_data_connection_on_iwlan", BearerProof.check(lte.copy(imsPdn = listOf(ImsTransport.IWLAN))))
        assertEquals(ProvenBearer.LTE to "data_rat_nr", BearerProof.check(lte.copy(wwanDataRat = "nr")))
        assertEquals(ProvenBearer.LTE to "data_rat_unread", BearerProof.check(lte.copy(wwanDataRat = null)))
        assertEquals(ProvenBearer.IWLAN to "ims_data_connection_on_lte", BearerProof.check(wifi.copy(imsPdn = listOf(ImsTransport.LTE))))
        assertEquals(ProvenBearer.IWLAN to "ims_data_connection_on_lte_and_iwlan", BearerProof.check(wifi.copy(imsPdn = listOf(ImsTransport.LTE, ImsTransport.IWLAN))))
        assertEquals(ProvenBearer.IWLAN to "ims_data_connection_unreadable", BearerProof.check(wifi.copy(imsPdn = emptyList())))
        assertEquals(null to "call_type_16_proves_no_bearer", BearerProof.check(BearerReading(16, listOf(ImsTransport.LTE), "lte")))
        assertEquals(null to "call_type_0_proves_no_bearer", BearerProof.check(BearerReading(null, listOf(ImsTransport.LTE), "lte")))
        // Wi-Fi calling needs no data RAT: the cellular side may be anything.
        assertEquals(ProvenBearer.IWLAN to null, BearerProof.check(wifi.copy(wwanDataRat = null)))

        assertEquals(ProvenBearer.IWLAN, BearerProof.of(true, wifi, wifi).bearer)
        assertTrue(BearerProof.of(true, wifi, wifi).counted)
        assertEquals(listOf("never_active"), BearerProof.of(false, wifi, wifi).why)
        assertEquals(listOf("call_type_never_read"), BearerProof.of(true, null, wifi).why)
        // A handover in the middle: each reading is consistent, but the bearer changed.
        val handover = BearerProof.of(true, wifi, lte)
        assertFalse(handover.counted)
        assertNull(handover.bearer)
        assertEquals(listOf("bearer_changed_during_the_call"), handover.why)
        assertEquals(listOf("at_hangup:ims_data_connection_on_iwlan"), BearerProof.of(true, lte, lte.copy(imsPdn = listOf(ImsTransport.IWLAN))).why)
    }

    @Test
    fun `RTP - at least 300 packets each way per 10 s over a window of at least 10 s, and no inactivity`() {
        fun q(ms: Long, sent: Long, received: Long, inactive: Boolean = false) = CallQualityReading(ms, sent, received, inactive)
        val pass = RtpFlow.of(q(1_000, 50, 50), q(11_000, 350, 350), false)
        assertTrue(pass.why.toString(), pass.passed)
        assertEquals(300L, pass.sentPer10s)
        assertEquals(300L, pass.receivedPer10s)
        assertEquals(listOf("sent_299_per_10s_under_300"), RtpFlow.of(q(1_000, 50, 50), q(11_000, 349, 350), false).why)
        assertEquals(listOf("received_0_per_10s_under_300"), RtpFlow.of(q(1_000, 50, 50), q(11_000, 600, 50), false).why)
        // The rate counts, not the total: 300 in 20 s is too few.
        assertFalse(RtpFlow.of(q(1_000, 0, 0), q(21_000, 300, 600), false).passed)
        assertEquals(listOf("window_9s_under_10s"), RtpFlow.of(q(1_000, 0, 0), q(10_000, 500, 500), false).why)
        assertEquals(listOf("rtp_inactivity"), RtpFlow.of(q(1_000, 0, 0), q(12_000, 600, 600), true).why)
        assertFalse(RtpFlow.of(null, null, false).passed)
        assertEquals(listOf("no_call_quality_reports"), RtpFlow.of(null, null, false).why)
    }

    @Test
    fun `the call watch keeps the bearer at ACTIVE and at hang-up, the RTP flow and how the call ended`() {
        val watch = CallWatch()
        var t = 0L
        fun add(text: String) {
            watch.add(parse(text).call.copy(atMs = t))
            t += 1_000
        }
        add(registry(imsReason = "510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier call"))
        add(registry(callState = 2, foreground = 3, imsReason = "510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier call"))
        // ACTIVE before the first call-quality report: no call type yet.
        add(registry(callState = 2, foreground = 1, imsReason = "510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier call"))
        for (second in 1..13) {
            add(
                registry(
                    callState = 2, foreground = 1, callType = 18, quality = CallQualityReading(second * 1_000L, second * 50L, second * 49L),
                    imsReason = "510 : CODE_USER_TERMINATED_BY_REMOTE, 0, earlier call",
                ),
            )
        }
        add(registry(imsReason = "501 : CODE_USER_TERMINATED, 0, User terminated IMS call", cause = 3))
        val summary = watch.summary()
        assertEquals(CallDirection.OUTGOING, summary.direction)
        assertTrue(summary.wentActive)
        assertEquals(BearerReading(18, listOf(ImsTransport.IWLAN), "lte"), summary.bearerAtActive)
        assertEquals(summary.bearerAtActive, summary.bearerAtHangUp)
        assertEquals(BearerReading(0, listOf(ImsTransport.IWLAN), "lte"), summary.bearerAtStart)
        assertTrue(summary.bearerProof!!.counted)
        assertEquals(ProvenBearer.IWLAN, summary.bearerProof!!.bearer)
        val rtp = summary.rtp!!
        assertTrue(rtp.why.toString(), rtp.passed)
        assertEquals(500L, rtp.sentPer10s)
        assertEquals(490L, rtp.receivedPer10s)
        assertEquals(13_000L, summary.activeMs)
        assertEquals(DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE", -1, -1), summary.disconnectBefore)
        assertEquals(DisconnectReading(501, "CODE_USER_TERMINATED", 3, -1), summary.disconnectAfter)
        assertEquals(true, summary.disconnectFresh)
        assertTrue(summary.disconnectAfter!!.byThisPhone)
        assertTrue(summary.disconnectAfter!!.normal)
        assertFalse(summary.disconnectAfter!!.sipFailure)
        assertTrue(CallWatchSummaryRoundTrip.same(summary))
    }

    @Test
    fun `PHH's default 13 on a call whose ims data connection is on Wi-Fi is not counted, and an emergency call discards everything`() {
        val watch = CallWatch()
        watch.add(parse(registry()).call)
        for (second in 1..12) {
            watch.add(parse(registry(callState = 2, foreground = 1, callType = 13, quality = CallQualityReading(second * 1_000L, second * 50L, second * 50L))).call)
        }
        watch.add(parse(registry()).call)
        val proof = watch.summary().bearerProof!!
        assertFalse(proof.counted)
        assertEquals(listOf("at_active:ims_data_connection_on_iwlan", "at_hangup:ims_data_connection_on_iwlan"), proof.why)

        val emergency = CallWatch()
        emergency.add(parse(registry()).call)
        emergency.add(parse(registry(callState = 2, foreground = 1, callType = 18, quality = CallQualityReading(1_000, 50, 50))).call)
        emergency.add(parse(registry(callState = 2, foreground = 1, callType = 18, quality = CallQualityReading(2_000, 100, 100), emergency = "112")).call)
        val discarded = emergency.summary()
        assertTrue(discarded.emergencySeen)
        assertNull(discarded.rtp)
        assertNull(discarded.bearerProof)
        assertFalse(discarded.wentActive)
    }

    @Test
    fun `a SIP failure, a normal end and this phone's own hang-up are told apart`() {
        assertTrue(DisconnectReading(361, "CODE_SIP_USER_REJECTED").sipFailure)
        assertTrue(DisconnectReading(338, "CODE_SIP_BUSY").sipFailure)
        assertFalse(DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE").sipFailure)
        assertTrue(DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE").normal)
        assertFalse(DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE").byThisPhone)
        assertTrue(DisconnectReading(0, "CODE_UNSPECIFIED", cause = 3).byThisPhone)
        assertFalse(DisconnectReading(1510, "CODE_MEDIA_NO_DATA").normal)
        assertFalse(DisconnectReading(null, null, cause = 36).normal)
    }
}

/** The lab's fixture of 2026-10-19 (the APN setting cut after its name), read by the shared parser. */
private object OwnerlessFixtures {
    fun labRegistryPdn(): List<ImsTransport> {
        val text = requireNotNull(javaClass.getResourceAsStream("/lab/registry_s20_lidl_lte.txt")).use { it.readBytes().decodeToString() }
        return RegistryParser.parse(text.lineSequence(), SimSlot.of(0)!!)!!.imsPdn
    }
}

/** A summary survives its wire form (the read-only build passes it between processes). */
private object CallWatchSummaryRoundTrip {
    fun same(summary: io.github.open_carrier_data.check.core.CallWatchSummary): Boolean =
        io.github.open_carrier_data.check.core.CallWatchSummary.decode(summary.encode()) == summary
}
