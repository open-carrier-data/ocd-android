package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * Round 26 (OCD's private research/rule-decisions-20261006, round 26,
 * automatic check 2): what a call's bearer and media are read from, shared by
 * the read-only guided call test (T2), the owner build and, in Python, OCD
 * Lab (tools/ocd_lab/lab_calls.py bearer_proof and rtp_flow).
 *
 * Everything comes from one place, `dumpsys telephony.registry`, in the same
 * phone block as the call state:
 * - mCallNetworkType: the call type. On PHH IMS 13 (LTE) is its default, not
 *   a measurement, so a call type alone is never proof of the bearer;
 * - mPreciseDataConnectionStates: the transport of the ims data connection
 *   (WWAN or WLAN);
 * - the WWAN packet-switched NetworkRegistrationInfo: the cellular data RAT.
 *   ServiceState's combined data RAT reads IWLAN whenever Wi-Fi's IWLAN
 *   registration is in service, even for a call over LTE, so the WWAN
 *   registration's own RAT is the reading that can agree with an LTE call;
 * - mCallQuality: the IMS stack's CallQuality report, cumulative since the
 *   call started (RTP packets sent and received, the call's duration, RTP
 *   inactivity; on AOSP ImsMedia also SID packets received, sequence-gap
 *   losses, duplicates, the codec and the RTCP round trip). PHH IMS fills it
 *   once a second (SipHandler.buildCallQuality). AOSP ImsStack passes
 *   ImsMedia's report through unchanged: every 5 s
 *   (CALL_QUALITY_MONITORING_TIME) and whenever RTP inactivity starts or
 *   stops, inactivity meaning 4 s in a row without a received packet played.
 *   The registry resets it when the foreground call is no longer active.
 *   Neither stack ever sets rxSilenceDetected or txSilenceDetected (they
 *   always print false), and ImsMedia never sets numRtpPacketsTransmittedLost,
 *   so those three are never read (round 28). Round 28's media rule
 *   ([MediaRule]) judges the reports;
 * - mImsCallDisconnectCause, mCallDisconnectCause, mCallPreciseDisconnectCause:
 *   why the last call ended. They stay until the next call ends, so a reading
 *   is fresh only when it changed during the watch.
 *
 * No number, address or free text survives: the ImsReasonInfo message is
 * dropped, the APN setting gives only its types and name.
 */

/** The three bearer readings at one moment of a call. */
@Serializable
data class BearerReading(
    /** TelephonyManager.NETWORK_TYPE_* of the call (13 LTE, 18 IWLAN); null or 0 when not reported. */
    val callNetworkType: Int? = null,
    /** Transports with a CONNECTED ims data connection; empty when none or unread. */
    val imsPdn: List<ImsTransport> = emptyList(),
    /** The WWAN packet-switched registration's radio (lte, lte_ca, nr, umts, ...), when registered home or roaming. */
    val wwanDataRat: String? = null,
)

/** A bearer the proof can establish. */
@Serializable
enum class ProvenBearer(val wire: String, val plain: String, val callNetworkType: Int) {
    LTE("lte", "LTE", Radio.LTE),
    IWLAN("iwlan", "Wi-Fi", Radio.IWLAN);

    companion object {
        fun ofCallType(type: Int?): ProvenBearer? = when (type) {
            Radio.LTE -> LTE
            Radio.IWLAN -> IWLAN
            else -> null
        }
    }
}

/**
 * The bearer proof of one call: [counted] only when the readings at ACTIVE and
 * at hang-up both agree and name the same bearer. [why] lists what did not
 * agree; the result is then "not counted, retry", never "failed".
 */
@Serializable
data class BearerVerdict(
    val counted: Boolean,
    val bearer: ProvenBearer? = null,
    val why: List<String> = emptyList(),
)

object BearerProof {
    private val LTE_RATS = setOf("lte", "lte_ca")

    /** The bearer [r]'s call type names, and why the reading does not prove it (null: it does). */
    fun check(r: BearerReading?): Pair<ProvenBearer?, String?> {
        if (r == null) return null to "no_reading"
        val bearer = ProvenBearer.ofCallType(r.callNetworkType) ?: return null to "call_type_${r.callNetworkType ?: 0}_proves_no_bearer"
        val pdn = r.imsPdn.distinct()
        if (pdn.isEmpty()) return bearer to "ims_data_connection_unreadable"
        val agrees = when (bearer) {
            ProvenBearer.LTE -> pdn.size == 1 && !pdn[0].isWifi
            ProvenBearer.IWLAN -> pdn == listOf(ImsTransport.IWLAN)
        }
        if (!agrees) return bearer to "ims_data_connection_on_${pdn.joinToString("_and_") { it.wire }}"
        if (bearer == ProvenBearer.LTE && r.wwanDataRat !in LTE_RATS) return bearer to "data_rat_${r.wwanDataRat ?: "unread"}"
        return bearer to null
    }

    /**
     * Round 26: the call type must agree with the ims data connection's
     * transport, and for LTE with the data RAT, at ACTIVE ([atActive], the
     * first active reading with a call type) and at hang-up ([atHangUp], the
     * last active reading).
     */
    fun of(wentActive: Boolean, atActive: BearerReading?, atHangUp: BearerReading?): BearerVerdict {
        if (!wentActive) return BearerVerdict(false, why = listOf("never_active"))
        if (atActive == null) return BearerVerdict(false, why = listOf("call_type_never_read"))
        val why = mutableListOf<String>()
        val (first, firstProblem) = check(atActive)
        val (last, lastProblem) = check(atHangUp)
        firstProblem?.let { why += "at_active:$it" }
        lastProblem?.let { why += "at_hangup:$it" }
        if (first != last) why += "bearer_changed_during_the_call"
        return if (why.isEmpty()) BearerVerdict(true, last) else BearerVerdict(false, null, why)
    }
}

/**
 * One reading of the registry's mCallQuality, cumulative since the call
 * started. Fields after [rtpInactive] are null when the report does not carry
 * the key; whether a stack really fills them is [MediaRule]'s question, never
 * this reading's. rxSilenceDetected, txSilenceDetected and
 * numRtpPacketsTransmittedLost are not read at all: no stack sets them, so a
 * printed false or 0 would read as a measurement it is not (round 28).
 */
@Serializable
data class CallQualityReading(
    val durationMs: Long = 0,
    val rtpSent: Long = 0,
    val rtpReceived: Long = 0,
    val rtpInactive: Boolean = false,
    /** numRtpPacketsNotReceived: RTP sequence gaps (ImsMedia's AudioJitterBuffer::CountLostFrames). */
    val notReceived: Long? = null,
    /** numRtpSidPacketsReceived: comfort-noise (SID) packets by their payload frame type. */
    val sidReceived: Long? = null,
    /** numRtpDuplicatePackets. */
    val duplicates: Long? = null,
    /** ImsStreamMediaProfile.AUDIO_QUALITY_* (1 AMR, 2 AMR-WB, 17 to 20 EVS). */
    val codecType: Int? = null,
    /** averageRoundTripTime, ms, from RTCP only; 0 means not measured. */
    val avgRttMs: Long? = null,
) {
    /** Received packets net of duplicates (round 28: "Received packets are counted after subtracting numRtpDuplicatePackets"). */
    val receivedNet: Long get() = rtpReceived - (duplicates ?: 0)
}

/**
 * RTP while the call was active (lab `rtp_flow`): the window runs from the
 * first call-quality report (duration above 0) to the last active reading,
 * and the counts are scaled to 10 s. [passed] is round 26's media test: a
 * window of at least 10 s, at least 300 packets each way per 10 s, and no RTP
 * inactivity in any report. Round 28 replaced that test with [MediaRule];
 * the flow stays as the call's plain per-10-s numbers, and [passed] judges
 * only ledger records made before round 28, which carry no [MediaWindow].
 */
@Serializable
data class RtpFlow(
    val readable: Boolean,
    val windowMs: Long? = null,
    val sentPer10s: Long? = null,
    val receivedPer10s: Long? = null,
    val sentTotal: Long? = null,
    val receivedTotal: Long? = null,
    val inactive: Boolean? = null,
    /** The call's duration by its last report, ms. */
    val callDurationMs: Long? = null,
    val why: List<String> = emptyList(),
) {
    val passed: Boolean get() = readable && why.isEmpty()

    companion object {
        const val MIN_PACKETS = 300L
        const val WINDOW_MS = 10_000L

        /** [first] and [last]: the first and last active reports; [inactiveSeen]: any report flagged inactivity. */
        fun of(first: CallQualityReading?, last: CallQualityReading?, inactiveSeen: Boolean, sampleWindowMs: Long? = null): RtpFlow {
            if (first == null || last == null) return RtpFlow(readable = false, why = listOf("no_call_quality_reports"))
            var window = last.durationMs - first.durationMs
            if (window <= 0 && sampleWindowMs != null) window = sampleWindowMs
            val sent = last.rtpSent - first.rtpSent
            val received = last.rtpReceived - first.rtpReceived
            val sentPer10s = if (window > 0) sent * WINDOW_MS / window else null
            val receivedPer10s = if (window > 0) received * WINDOW_MS / window else null
            val why = buildList {
                if (window < WINDOW_MS) add("window_${window / 1000}s_under_10s")
                if (sentPer10s != null && sentPer10s < MIN_PACKETS) add("sent_${sentPer10s}_per_10s_under_$MIN_PACKETS")
                if (receivedPer10s != null && receivedPer10s < MIN_PACKETS) add("received_${receivedPer10s}_per_10s_under_$MIN_PACKETS")
                if (inactiveSeen) add("rtp_inactivity")
            }
            return RtpFlow(
                readable = true, windowMs = window, sentPer10s = sentPer10s, receivedPer10s = receivedPer10s,
                sentTotal = last.rtpSent, receivedTotal = last.rtpReceived, inactive = inactiveSeen,
                callDurationMs = last.durationMs, why = why,
            )
        }
    }
}

/** Why the last call of a phone ended: the ImsReasonInfo code and name (never its message) and the DisconnectCause numbers. */
@Serializable
data class DisconnectReading(
    val imsCode: Int? = null,
    val imsName: String? = null,
    /** android.telephony.DisconnectCause. */
    val cause: Int? = null,
    /** android.telephony.PreciseDisconnectCause. */
    val precise: Int? = null,
) {
    /** A SIP failure (ImsReasonInfo CODE_SIP_*, 321 to 399). */
    val sipFailure: Boolean get() = imsName?.startsWith("CODE_SIP_") == true || (imsCode != null && imsCode in 321..399)

    /** This phone ended the call (ImsReasonInfo.CODE_USER_TERMINATED, or DisconnectCause.LOCAL without an IMS code). */
    val byThisPhone: Boolean get() = imsCode == IMS_USER_TERMINATED || ((imsCode == null || imsCode == 0) && cause == CAUSE_LOCAL)

    /** Normal clearing by either side; anything else that ended an active call is abnormal. */
    val normal: Boolean get() = imsCode == IMS_USER_TERMINATED || imsCode == IMS_USER_TERMINATED_BY_REMOTE ||
        ((imsCode == null || imsCode == 0) && (cause == CAUSE_NORMAL || cause == CAUSE_LOCAL))

    /** A media failure end (ImsReasonInfo CODE_MEDIA_*, 401 to 404). */
    val mediaFailure: Boolean get() = imsName?.startsWith("CODE_MEDIA_") == true || (imsCode != null && imsCode in 401..404)

    val plain: String get() = listOfNotNull(imsName?.let { "$it ($imsCode)" }, cause?.let { "cause $it" }).joinToString(", ").ifEmpty { "not reported" }

    companion object {
        const val IMS_USER_TERMINATED = 501
        const val IMS_USER_TERMINATED_BY_REMOTE = 510
        const val CAUSE_NORMAL = 2
        const val CAUSE_LOCAL = 3
    }
}

/** Parsers for the registry's call-media lines, inside one phone block. */
object CallMediaParser {
    private val QUALITY = Regex("mCallQuality=CallQuality: \\{([^}]*)\\}?")
    private val FIELD = Regex("(\\w+)=(-?\\w+)")
    private val IMS_REASON = Regex("mImsCallDisconnectCause=ImsReasonInfo :: \\{(-?\\d+) : (\\w+)")
    private val CAUSE = Regex("mCallDisconnectCause=(-?\\d+)")
    private val PRECISE = Regex("mCallPreciseDisconnectCause=(-?\\d+)")
    private val PS_WWAN = Regex(
        "NetworkRegistrationInfo\\{ domain=PS transportType=WWAN registrationState=(\\w+).*?accessNetworkTechnology=(\\w+)",
    )

    /** The data connection entries of mPreciseDataConnectionStates; the APN setting runs to the link properties' brace. */
    val DATA_CONNECTION = Regex(
        "state: (\\w+), transport: (\\w+), id: -?\\d+, netId: -?\\d+, network type: (\\w+), APN Setting: \\[ApnSetting\\] ([^{}\\n]*)",
    )

    /**
     * The last mCallQuality report of a line, read by key: missing keys give
     * null (or 0 for the three round 26 counters), unknown keys are ignored,
     * and the line may end before the closing brace.
     */
    fun quality(line: String): CallQualityReading? {
        val body = QUALITY.findAll(line).lastOrNull()?.groupValues?.get(1) ?: return null
        val fields = FIELD.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        fun long(name: String) = fields[name]?.toLongOrNull()
        return CallQualityReading(
            durationMs = long("callDuration") ?: 0L,
            rtpSent = long("numRtpPacketsTransmitted") ?: 0L,
            rtpReceived = long("numRtpPacketsReceived") ?: 0L,
            rtpInactive = fields["rtpInactivityDetected"] == "true",
            notReceived = long("numRtpPacketsNotReceived"),
            sidReceived = long("numRtpSidPacketsReceived"),
            duplicates = long("numRtpDuplicatePackets"),
            codecType = fields["codecType"]?.toIntOrNull(),
            avgRttMs = long("averageRoundTripTime"),
        )
    }

    /** ImsReasonInfo code and name of a line, the message dropped. */
    fun imsReason(line: String): Pair<Int, String>? =
        IMS_REASON.findAll(line).lastOrNull()?.let { it.groupValues[1].toInt() to it.groupValues[2] }

    fun cause(line: String): Int? = CAUSE.findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    fun precise(line: String): Int? = PRECISE.findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** The WWAN PS registration's RAT, lower case, when it is registered (home or roaming). */
    fun wwanDataRat(line: String): String? = PS_WWAN.findAll(line).lastOrNull()?.let { m ->
        m.groupValues[2].lowercase().takeIf { m.groupValues[1] == "HOME" || m.groupValues[1] == "ROAMING" }
    }

    /**
     * Whether an APN setting (ApnSetting.toString from its entry name on) is
     * the ims APN: its APN types (the 11th field, joined by " | ") include ims;
     * where a dump cut the types off, the APN name ims (TS 23.003) stands in.
     * With OCD's data-2026.10 on the S20 the ims row is named "Vodafone.de",
     * so the entry name alone finds nothing (lab, 2026-10-20).
     */
    fun isImsApn(setting: String): Boolean {
        val parts = setting.substringBefore("<cut>").split(", ").map { it.trim() }
        val types = parts.getOrNull(10)?.takeIf { it.isNotEmpty() }
        if (types != null) return types.split('|').any { it.trim() == "ims" }
        return parts.getOrNull(3).equals("ims", ignoreCase = true) || parts.getOrNull(0).equals("ims", ignoreCase = true)
    }
}
