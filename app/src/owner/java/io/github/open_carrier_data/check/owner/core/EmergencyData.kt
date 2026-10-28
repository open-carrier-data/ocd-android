package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/*
 * The emergency data test (OCD round 30, "emergencyDataTest"), owner build only. The app requests
 * the emergency data network (NET_CAPABILITY_EIMS) on the home mobile network with the SIM's
 * matched emergency APN row, sees whether the network sets it up, and releases it. There is no
 * call, no dialled number, no SIP, no IMS emergency registration, and nothing is sent.
 *
 * What a pass shows: this SIM, on this home network, ROM and IMS stack, gets an emergency data
 * connection over the mobile network with an IP type the row allows. It never shows that
 * emergency calls work, and no record says so.
 *
 * The rules here are the ruling's, merged from both judges: mobile network only; wait at most
 * 15 s and release within 5 s; due on a new SIM, a changed row, a relevant build or stack change
 * and before the 90-day record expires; one session per SIM per day, no retry on the same day, a
 * pause after three days on which both requests failed; never during a call, while one rings or
 * is held, within 10 minutes of any call or 60 minutes of an emergency call, in emergency callback
 * or emergency SMS mode, while roaming, when an EIMS network already exists, or over Wi-Fi; and the
 * stop list, each item of which stops the test until a judged round.
 */

/** One session's outcome for one SIM, kept for the due rules (no number, no identifier). */
@Serializable
data class EmergencySession(
    val subId: Int,
    val mccmnc: String,
    /** The emergency row's key ("a:..."), or null for Android's default "sos" profile. */
    val rowKey: String?,
    /** [EmergencyData.basis] at the session: ROM build, IMS stack and modem firmware. */
    val basis: String,
    /** The UTC day it ran. */
    val day: String,
    /** passed, failed (counted), default_won, both_failed, not_counted, not_meaningful, refused, stopped. */
    val outcome: String,
    val atMs: Long,
)

/** What one EIMS request showed, as the Android side read it. */
data class EimsAttempt(
    /** The request reached CONNECTED within 15 s. */
    val connected: Boolean,
    /** The connected network's transport was cellular; false: it came up on Wi-Fi (released at once, nothing recorded). */
    val cellular: Boolean?,
    /** Telephony's data profile for it is the SIM's emergency row (or Android's default where the profile has none). */
    val profileMatches: Boolean?,
    /** The link's address families: "ipv4", "ipv6". */
    val families: Set<String>,
    /** The fail cause Telephony reported for the emergency setup ([android.telephony.DataFailCause]); null without one. */
    val failCause: Int?,
    /** Ordinary mobile data worked during the attempt. */
    val dataWorks: Boolean?,
    /** Released within 5 s of CONNECTED (or at the timeout). */
    val releasedInTime: Boolean,
    /** A call state changed during the attempt: aborted and released at once, nothing counted. */
    val aborted: Boolean = false,
    val pcscf: Boolean? = null,
)

/**
 * The first use (the ruling's condition): once on the S20 with o2, then with LIDL, with `adb
 * logcat` streamed, run by hand. Only the owner can tell from the radio log whether the request
 * went out as an emergency PDN and nothing else happened, so the app never runs the test on its
 * own, and writes no record, until the owner records that the first use passed.
 */
@Serializable
data class EmergencyFirstUse(
    /** passed, or not_meaningful ((1) not shown: every result reads "not meaningful on this phone" and changes nothing). */
    val result: String,
    val atMs: Long,
) {
    val passed: Boolean get() = result == PASSED

    companion object {
        const val PASSED = "passed"
        const val NOT_MEANINGFUL = "not_meaningful"
    }
}

/** The checks after every attempt (the ruling's list); any failure is a stop event. */
data class EimsAfter(
    val imsUnchanged: Boolean?,
    val ecbm: Boolean?,
    val emergencyCallState: Boolean?,
    val callUp: Boolean?,
    val emergencyThrottle: Boolean?,
    val eimsGone: Boolean?,
    val serviceOk: Boolean?,
    val operatorMessage: Boolean = false,
)

/** The inputs of the never-run conditions and the preconditions, in one pass. Nulls are unreadable and refuse. */
data class EimsInputs(
    val inCall: Boolean?,
    val lastCallSeenAtMs: Long?,
    val emergencySeenAtMs: Long?,
    val upMs: Long,
    val ecbm: Boolean?,
    val emergencySmsMode: Boolean?,
    val roaming: Boolean?,
    val eimsExists: Boolean?,
    /** The data network type of the home mobile network: lte, nr, or another. */
    val dataRat: String?,
    /** EMC bearer support from the network (LteVopsSupportInfo or NrVopsSupportInfo). */
    val emcSupported: Boolean?,
    val dataWorks: Boolean?,
    /** dumpsys: shouldHonorRetryTimerForEmergencyNetworkRequest. */
    val honorRetryTimer: Boolean?,
    /** AccessNetworksManager's preferred transport for emergency: WWAN or WLAN. */
    val emergencyTransport: String?,
    /** The app's or CarrierSettings's stop (a stop event of this test included). */
    val stopped: String?,
    val nowMs: Long,
    /** The call watch's record across restarts ([RestartWindow]); null: none, so the first hour after a restart refuses. */
    val callsSeen: CallsSeen? = null,
)

object EmergencyData {
    const val WAIT_MS = 15_000L
    const val RELEASE_MS = 5_000L
    val CALL_QUIET_MS: Long = TimeUnit.MINUTES.toMillis(10)
    val EMERGENCY_QUIET_MS: Long = TimeUnit.MINUTES.toMillis(60)
    const val RECORD_DAYS = 90L

    /** Due before the 90-day record expires: within this many days of its end. */
    const val RENEW_BEFORE_DAYS = 14L
    const val PAUSE_AFTER_FAILED_DAYS = 3
    const val MAX_SESSIONS = 120

    /** The non-transient causes about the PDN type, the APN or the service option that count as a failure. */
    val COUNTED_CAUSES = mapOf(
        0x1C to "unknown PDP address or PDN type",
        0x32 to "PDN type IPv4 only allowed",
        0x33 to "PDN type IPv6 only allowed",
        0x1B to "missing or unknown APN",
        0x20 to "service option not supported",
        0x21 to "service option not subscribed",
    )

    /** Operator barring: a carrier action, so a stop event. */
    const val OPERATOR_BARRED = 0x08

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** What decides "a build changes Telephony, ImsStack or the modem firmware": the ROM build key, the IMS stack and its version, the baseband. */
    fun basis(romBuild: String, imsStack: String, imsVersion: Long?, baseband: String?): String = listOf(romBuild, imsStack, imsVersion?.toString().orEmpty(), baseband.orEmpty()).joinToString("|")

    /**
     * Round 31's first use of the default emergency APN cycle (defaultEmergencyApnTest): the install of the
     * temporary set and its restore, with no EIMS request. Only the refusals that guard an APN install and the
     * phone's state; the EIMS request's own conditions (EMC support, the retry timer, the transport) do not apply.
     */
    fun firstUseRefusal(i: EimsInputs): String? = when {
        i.stopped != null -> "stopped: ${i.stopped}"
        i.inCall != false -> "a call is up, ringing, held or being set up, or the call state cannot be read"
        i.lastCallSeenAtMs != null && i.nowMs - i.lastCallSeenAtMs < CALL_QUIET_MS -> "a call ended less than 10 minutes ago"
        i.emergencySeenAtMs != null && i.nowMs - i.emergencySeenAtMs < EMERGENCY_QUIET_MS -> "an emergency call was seen less than 60 minutes ago"
        RestartWindow.emergencyAtMs(i.callsSeen)?.let { i.nowMs - it < EMERGENCY_QUIET_MS } == true -> "an emergency call was seen less than 60 minutes ago"
        i.upMs < EMERGENCY_QUIET_MS && !RestartWindow.clear(i.callsSeen, i.nowMs - i.upMs, i.nowMs, EMERGENCY_QUIET_MS) ->
            "the phone or its system started less than 60 minutes ago and the call watch cannot rule out an emergency call just before"
        i.ecbm != false -> "emergency callback mode, or it cannot be read"
        i.emergencySmsMode != false -> "emergency SMS mode, or it cannot be read"
        i.roaming != false -> "roaming (national roaming included), or it cannot be read"
        i.eimsExists != false -> "an emergency data network exists already, or it cannot be read"
        i.dataWorks != true -> "ordinary mobile data does not work"
        else -> null
    }

    /** Round 31 (R31.9): what a refusal of `emergency_default_set` means for the session's result. */
    fun setRefusal(error: String?): String = when (error) {
        "user_edited_row" -> "not run: user-edited row"
        "no_emergency_row" -> "not run: the SIM selects no emergency row"
        "refused_now" -> "not run now: a call, an emergency state or another APN install"
        "voice_loss_hold", "voice_loss_stop" -> "not run: the voice-loss hold or stop"
        "changes_halted" -> "not run: changes are halted"
        OcdDataProtocol.UNKNOWN_METHOD -> "not run: this ROM build has no temporary emergency set (round 30)"
        else -> "not run: ${error ?: "no answer"}"
    }

    /** Why it never runs now, or null (the ruling's "It never runs" and "It needs all of these first"). */
    fun refusal(i: EimsInputs): String? = when {
        i.stopped != null -> "stopped: ${i.stopped}"
        i.inCall != false -> "a call is up, ringing, held or being set up, or the call state cannot be read"
        i.lastCallSeenAtMs != null && i.nowMs - i.lastCallSeenAtMs < CALL_QUIET_MS -> "a call ended less than 10 minutes ago"
        i.emergencySeenAtMs != null && i.nowMs - i.emergencySeenAtMs < EMERGENCY_QUIET_MS -> "an emergency call was seen less than 60 minutes ago"
        RestartWindow.emergencyAtMs(i.callsSeen)?.let { i.nowMs - it < EMERGENCY_QUIET_MS } == true -> "an emergency call was seen less than 60 minutes ago"
        i.upMs < EMERGENCY_QUIET_MS && !RestartWindow.clear(i.callsSeen, i.nowMs - i.upMs, i.nowMs, EMERGENCY_QUIET_MS) ->
            "the phone or its system started less than 60 minutes ago and the call watch cannot rule out an emergency call just before"
        i.ecbm != false -> "emergency callback mode, or it cannot be read"
        i.emergencySmsMode != false -> "emergency SMS mode, or it cannot be read"
        i.roaming != false -> "roaming (national roaming included), or it cannot be read"
        i.eimsExists != false -> "an emergency data network exists already, or it cannot be read"
        i.dataRat !in setOf("lte", "nr") -> "the home network is not on LTE or NR"
        i.emcSupported != true -> "not available on this network: emergency bearer services are not reported as supported"
        i.dataWorks != true -> "ordinary mobile data does not work"
        i.honorRetryTimer != false -> "shouldHonorRetryTimerForEmergencyNetworkRequest is not false on this build"
        i.emergencyTransport != "WWAN" -> "the preferred transport for emergency is not cellular"
        else -> null
    }

    /**
     * Why a session is due for this SIM now, or null. [rowKey]: the SIM's selected emergency row
     * (null: Android's default). One session per SIM per day; after three days on which both
     * requests failed, paused until the row or the build changes.
     */
    fun due(sessions: List<EmergencySession>, sim: SimKey, rowKey: String?, basis: String, nowMs: Long): String? {
        val mine = sessions.filter { it.subId == sim.subId && it.mccmnc == sim.mccmnc }
        val today = day(nowMs)
        if (mine.any { it.day == today && it.outcome !in setOf("refused") }) return null
        val sameCell = mine.filter { it.rowKey == rowKey && it.basis == basis }
        if (sameCell.filter { it.outcome == "both_failed" }.map { it.day }.distinct().size >= PAUSE_AFTER_FAILED_DAYS) return null
        if (sameCell.any { it.outcome == "stopped" }) return null
        val decided = sameCell.filter { it.outcome in setOf("passed", "default_won", "failed", "both_failed") }
        if (mine.isEmpty()) return "a SIM first seen"
        if (mine.none { it.rowKey == rowKey }) return "the SIM's emergency row changed"
        if (mine.none { it.basis == basis }) return "a new build, IMS stack or modem firmware"
        val last = decided.maxByOrNull { it.atMs } ?: return "no decided session yet"
        if (last.outcome == "both_failed") return "both requests failed on an earlier day"
        val ends = LocalDate.parse(last.day).plusDays(RECORD_DAYS)
        if (!LocalDate.parse(today).isBefore(ends.minusDays(RENEW_BEFORE_DAYS))) return "the 90-day record is about to expire"
        return null
    }

    /** One attempt's class (the ruling's verdicts). */
    enum class Verdict(val wire: String) {
        PASS("pass"),
        COUNTED_FAIL("counted_fail"),
        BARRED("operator_barred"),
        WIFI("came_up_on_wifi"),
        NOT_COUNTED("not_counted"),
    }

    /** Whether [families] fit the row's protocol (IP, IPV6, IPV4V6). */
    fun familyAllowed(protocol: String, families: Set<String>): Boolean = when (protocol.uppercase()) {
        "IP" -> "ipv4" in families
        "IPV6" -> "ipv6" in families
        "IPV4V6", "" -> families.isNotEmpty()
        else -> false
    }

    fun classify(a: EimsAttempt, protocol: String): Verdict = when {
        a.aborted -> Verdict.NOT_COUNTED
        a.connected && a.cellular == false -> Verdict.WIFI
        a.connected && a.cellular == true && a.profileMatches == true && familyAllowed(protocol, a.families) -> Verdict.PASS
        a.failCause == OPERATOR_BARRED -> Verdict.BARRED
        !a.connected && a.failCause in COUNTED_CAUSES && a.dataWorks == true -> Verdict.COUNTED_FAIL
        else -> Verdict.NOT_COUNTED
    }

    /** The stop list after an attempt: an emergency mode, a bearer not released, service lost, a throttle, an operator message. Null: none. */
    fun stopEvent(a: EimsAttempt?, after: EimsAfter, verdict: Verdict?): String? = when {
        verdict == Verdict.BARRED -> "operator barring on the emergency request"
        after.ecbm != false -> "emergency callback mode entered (or unreadable) after the emergency data request"
        after.emergencyCallState != false -> "an emergency call state (or unreadable) after the emergency data request"
        a != null && !a.releasedInTime -> "the emergency bearer was not released within 5 s"
        after.eimsGone != true -> "the emergency data network is not gone after the release"
        after.emergencyThrottle != false -> "a throttle on the emergency data profile (or unreadable)"
        after.serviceOk != true -> "normal service lost after the release"
        after.imsUnchanged == false -> "IMS registration changed after the emergency data request"
        after.operatorMessage -> "the operator sent a message"
        else -> null
    }

    /**
     * The session's result from the row's attempt and, only after a counted failure, Android's
     * default request: the row passed, the default won (the row is left out of the installed APN
     * list and a source review opens), or both failed (nothing changes). Null: nothing counted.
     */
    fun result(row: Verdict, default: Verdict?): RecordResult? = when {
        // Android's default, tested only after a counted failure of the row (round 31: through the temporary set).
        row == Verdict.PASS -> RecordResult.EMERGENCY_DATA_ACCEPTED
        row == Verdict.COUNTED_FAIL && default == Verdict.PASS -> RecordResult.EMERGENCY_DEFAULT_WON
        row == Verdict.COUNTED_FAIL && default == Verdict.COUNTED_FAIL -> RecordResult.EMERGENCY_DATA_REFUSED
        else -> null
    }

    /** The plain basis of a record; it never says that emergency calls work. */
    fun recordBasis(result: RecordResult, rowName: String?, protocol: String, families: Set<String>, cause: Int?): String = when (result) {
        RecordResult.EMERGENCY_DATA_ACCEPTED -> "emergency data connection accepted on the home mobile network with ${rowName ?: "Android's default sos profile"} ($protocol, ${families.sorted().joinToString("+")}); no call, nothing sent, released within 5 s; says nothing about emergency calls"
        RecordResult.EMERGENCY_DEFAULT_WON -> "the emergency row was refused (${COUNTED_CAUSES[cause] ?: "counted cause"}) and Android's default IPV4V6 sos request was accepted; no call, nothing sent; says nothing about emergency calls"
        RecordResult.EMERGENCY_DATA_REFUSED -> "the network did not accept an emergency data connection from this phone (${COUNTED_CAUSES[cause] ?: "counted cause"}), with the row nor with Android's default; no call, nothing sent; says nothing about emergency calls"
        else -> ""
    }.take(500)

    fun add(sessions: List<EmergencySession>, s: EmergencySession): List<EmergencySession> = (sessions + s).takeLast(MAX_SESSIONS)
}

/** Readings from the phone service's dump (`dumpsys activity service com.android.phone`) for the emergency data test. */
object EmergencyDump {
    private val HONOR = Regex("shouldHonorRetryTimerForEmergencyNetworkRequest=(true|false)")
    private val TRANSPORT = Regex("(?m)^\\s*emergency:\\s*(WWAN|WLAN|INVALID|\\d+)\\s*$")
    private val LTE_EMC = Regex("LteVopsSupportInfo :\\s+mVopsSupport = \\d+\\s+mEmcBearerSupport = (\\d+)")
    private val NR_EMC = Regex("NrVopsSupportInfo :\\s+mVopsSupport = \\d+\\s+mEmcSupport = (\\d+)")

    /** The section of [name] for [phoneId] ("DataConfigManager-0:"), up to the next header of the same or a lower indent. */
    fun section(text: String?, name: String, phoneId: Int): String? {
        text ?: return null
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "$name-$phoneId:" }.takeIf { it >= 0 } ?: return null
        val indent = lines[start].takeWhile { it == ' ' }.length
        val header = Regex("^\\s{0,$indent}[A-Za-z][A-Za-z0-9]*(-\\d+)?:\\s*$")
        val end = (start + 1 until lines.size).firstOrNull { header.matches(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    fun honorRetryTimer(text: String?, phoneId: Int): Boolean? = section(text, "DataConfigManager", phoneId)?.let { HONOR.find(it)?.groupValues?.get(1)?.toBooleanStrictOrNull() }

    fun emergencyTransport(text: String?, phoneId: Int): String? = section(text, "AccessNetworksManager", phoneId)?.let { TRANSPORT.find(it)?.groupValues?.get(1) }

    /** A throttling entry of DataRetryManager for an emergency profile; null when the section cannot be read. */
    fun emergencyThrottle(text: String?, phoneId: Int): Boolean? {
        val s = section(text, "DataRetryManager", phoneId) ?: return null
        val block = s.substringAfter("Throttling entries:", missingDelimiterValue = "").substringBefore("Local logs:")
        return block.lines().any { "DataThrottlingEntry" in it && it.contains("emergency", ignoreCase = true) }
    }

    private val APN_SETTING = Regex("\\[ApnSetting]\\s*([^,]*),\\s*(-?\\d+),\\s*(\\d*),\\s*([^,]*),")

    /**
     * Round 31 (R31.9, step 3): whether DataProfileManager shows Android's default emergency profile (APN `sos`,
     * type emergency) for [phoneId], read from its section of the phone service's dump. Null when the section cannot be
     * read. Android adds that profile when the SIM's APN list has no emergency row, which the temporary set makes so.
     */
    fun defaultSosProfile(text: String?, phoneId: Int): Boolean? {
        val s = section(text, "DataProfileManager", phoneId) ?: return null
        return s.lines().any { line ->
            val m = APN_SETTING.find(line) ?: return@any false
            m.groupValues[4].trim().equals("sos", ignoreCase = true) && line.contains("emergency", ignoreCase = true)
        }
    }

    /** EMC bearer support from the registry's slot block: LTE 2 = supported; NR any value but 0. Null: not reported. */
    fun emcSupported(registryBlock: String?, rat: String?): Boolean? {
        registryBlock ?: return null
        return when (rat) {
            "lte" -> LTE_EMC.find(registryBlock)?.groupValues?.get(1)?.toIntOrNull()?.let { it == 2 }
            "nr" -> NR_EMC.find(registryBlock)?.groupValues?.get(1)?.toIntOrNull()?.let { it != 0 }
            else -> null
        }
    }
}
