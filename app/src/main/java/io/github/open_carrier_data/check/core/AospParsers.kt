package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable
import java.net.InetAddress
import kotlin.math.ceil

/*
 * Parsers for Android's own telephony state, ported from OCD Lab
 * (tools/ocd_lab/lab_parsers.py in OCD's private repository, main 0f21d239):
 * parse_aosp_ims, ims_with_pdn, parse_registry_phone with the call fields,
 * parse_iwlan_log with iwlan_summary, and wifi_dns_servers. The lab's
 * fixtures from the S20 (LineageOS 23.2, PHH IMS, AOSP IWLAN, LIDL Connect
 * SIM) are the test inputs.
 *
 * Same rules as DeepParsers.kt: each parser reads its command's output line
 * by line inside the deep check's process and keeps only typed fields.
 * Identities, addresses, numbers and log text have no field to land in.
 */

/** What `dumpsys activity service com.android.phone` says about one phone's IMS (lab `parse_aosp_ims`). */
data class AospImsState(
    /** IMS registration: null when the dump gives no state for the phone. */
    val registered: Boolean?,
    /** not_registered, registering or registered (mImsMmTelRegistrationState), when printed. */
    val state: String?,
    /** The transport of the registration log's last line, when that line is a sign-in and IMS is registered. */
    val logTransport: ImsTransport?,
    val services: List<ImsService>,
    val lastDisconnect: ImsDisconnect?,
    /** The phone's own Wi-Fi calling switch (ImsManager isWfcEnabledByUser). */
    val wifiCallingByUser: Boolean?,
    /** The phone's own VoLTE switch for this SIM (ImsManager isEnhanced4gLteModeSettingEnabledByUser). */
    val volteByUser: Boolean? = null,
)

object AospImsParser {
    private val HEADER = Regex("^\\s*GsmCdmaPhone extends:\\s*$")
    private val PHONE_ID = Regex("mPhoneId=(\\d+)")
    private val STATE = Regex("mImsMmTelRegistrationState = (\\d)")
    private val MMTEL = Regex("IMS features registration state: MMTEL=(registered|not registered)")
    private val LEGACY = Regex("mImsRegistered=(true|false)")
    private val EVENT = Regex("onImsMmTel(Connected|Disconnected|Progressing) ims(?:TransportType|RadioTech)=(\\w+)")

    // ImsPhone's registration log prints the ImsReasonInfo after "imsRadioTech=": {code : NAME, extra, message}.
    private val REASON = Regex("onImsMmTelDisconnected imsRadioTech=ImsReasonInfo :: \\{(\\d+) : (\\w+)")
    private val CAPS = Regex("mMmTelCapabilities=MmTel Capabilities - \\[([^\\]]*)\\]")
    private val CAP = Regex("(\\w+): (true|false)")
    private val WFC_USER = Regex("\\bisWfcEnabledByUser = (true|false)\\b")
    private val VOLTE_USER = Regex("\\bisEnhanced4gLteModeSettingEnabledByUser = (true|false)\\b")
    private val STATES = mapOf("0" to "not_registered", "1" to "registering", "2" to "registered")

    /**
     * The first GsmCdmaPhone section whose mPhoneId is the slot, as the lab's
     * `phone_sections` cuts it: from that header to the next one. Fields
     * printed once take their first value there, the registration log its last
     * line. Null when the dump has no section for the slot.
     */
    fun parse(lines: Sequence<String>, slot: SimSlot): AospImsState? {
        var awaitingId = false
        var sectionId: Int? = null
        var done = false
        var found = false
        var state: String? = null
        var mmtel: String? = null
        var legacy: String? = null
        var lastEvent: Pair<String, String>? = null
        var lastReason: ImsDisconnect? = null
        var caps: Map<String, Boolean>? = null
        var wfcUser: Boolean? = null
        var volteUser: Boolean? = null
        for (line in lines) {
            if (HEADER.matches(line)) {
                if (found) done = true
                awaitingId = true
                sectionId = null
                continue
            }
            if (done) continue
            if (awaitingId) {
                val id = PHONE_ID.find(line) ?: continue
                sectionId = id.groupValues[1].toIntOrNull()
                awaitingId = false
                if (sectionId == slot.index) found = true
                continue
            }
            if (sectionId != slot.index) continue
            if (state == null) STATE.find(line)?.let { state = it.groupValues[1] }
            if (mmtel == null) MMTEL.find(line)?.let { mmtel = it.groupValues[1] }
            if (legacy == null) LEGACY.find(line)?.let { legacy = it.groupValues[1] }
            if (caps == null) {
                CAPS.find(line)?.let { match ->
                    caps = CAP.findAll(match.groupValues[1]).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
                }
            }
            if (wfcUser == null) WFC_USER.find(line)?.let { wfcUser = it.groupValues[1] == "true" }
            if (volteUser == null) VOLTE_USER.find(line)?.let { volteUser = it.groupValues[1] == "true" }
            EVENT.find(line)?.let { lastEvent = it.groupValues[1] to it.groupValues[2] }
            REASON.find(line)?.let { match ->
                lastReason = match.groupValues[1].toIntOrNull()?.takeIf(ImsDisconnect::validCode)?.let { code ->
                    ImsDisconnect(code, match.groupValues[2].takeIf(ImsDisconnect::validName))
                }
            }
        }
        if (!found) return null
        val registered = when {
            state != null -> state == "2"
            mmtel != null -> mmtel == "registered"
            legacy != null -> legacy == "true"
            else -> null
        }
        val event = lastEvent
        val logTransport = if (registered == true && event != null && event.first == "Connected") {
            when (event.second) {
                "WLAN" -> ImsTransport.IWLAN
                "WWAN" -> ImsTransport.WWAN
                else -> ImsTransport.OTHER
            }
        } else {
            null
        }
        val services = caps.orEmpty().let { found ->
            listOfNotNull(
                ImsService.MMTEL.takeIf { found["Voice"] == true },
                ImsService.MMTEL_VIDEO.takeIf { found["Video"] == true },
                ImsService.SMSIP.takeIf { found["SMS"] == true },
            ).sortedBy { it.wire }
        }
        return AospImsState(
            registered = registered,
            state = state?.let(STATES::get),
            logTransport = logTransport,
            services = services,
            lastDisconnect = lastReason,
            wifiCallingByUser = wfcUser,
            volteByUser = volteUser,
        )
    }

    /**
     * The reading for the app: lab `ims_with_pdn`. The registration log's last
     * sign-in names a transport, but a later fallback does not always add a
     * line (S20, 2026-10-19: the log said Wi-Fi while the IMS data connection
     * had been on LTE for half an hour). When the IMS data connection is
     * connected on exactly one transport, that transport wins; the log's is
     * kept beside it when the two differ.
     */
    fun reading(state: AospImsState?, registry: RegistryPhone?, dumpRead: Boolean): ImsReading {
        if (!dumpRead) return ImsReading(ImsSource.UNREADABLE)
        if (state == null) return ImsReading(ImsSource.NOT_FOUND)
        val registered = state.registered
        val pdn = registry?.imsPdn?.singleOrNull()
        val transport = when {
            registered != true -> null
            pdn != null -> pdn
            else -> state.logTransport
        }
        val logDiffers = registered == true && pdn != null && state.logTransport != null &&
            state.logTransport.isWifi != pdn.isWifi
        return ImsReading(
            source = ImsSource.AOSP,
            registered = registered,
            state = state.state,
            transport = transport,
            services = if (registered == true) state.services else emptyList(),
            imsPdn = pdn,
            registrationLogTransport = state.logTransport.takeIf { logDiffers },
            lastDisconnect = state.lastDisconnect,
        )
    }
}

/** One phone's block of `dumpsys telephony.registry` (lab `parse_registry_phone`), reduced to typed fields. */
data class RegistryPhone(
    val lteVops: LteVops?,
    val nrVops: NrVops?,
    /** Transports on which the IMS data connection is CONNECTED; a HANDOVERINPROGRESS entry is left out. */
    val imsPdn: List<ImsTransport>,
    val call: CallSample,
)

object RegistryParser {
    private val LTE_VOPS = Regex("LteVopsSupportInfo :\\s+mVopsSupport = (\\d)")
    private val NR_VOPS = Regex("NrVopsSupportInfo :\\s+mVopsSupport = (\\d)")
    private val IMS_PDN = Regex(
        "state: (\\w+), transport: (\\w+), id: -?\\d+, netId: -?\\d+, network type: (\\w+), APN Setting: \\[ApnSetting\\] ims,",
    )
    private val CALL_STATE = Regex("mCallState=(\\d)")
    private val CALL_NETWORK_TYPE = Regex("mCallNetworkType=(\\d+)")
    private val FOREGROUND = Regex("Foreground call state: (-?\\d+)")
    private val EMERGENCY_CALL = Regex("^\\s*mOutgoingCallEmergencyNumber=(.*)$")
    private val INCOMING_NUMBER = Regex("mCallIncomingNumber=([^\\s,]*)")
    private val NOT_DIGIT = Regex("\\D")
    private val ANY_PHONE = Regex("^\\s*Phone Id=\\d+\\s*$")

    /**
     * The `Phone Id=N` block of the "last known state" section: from that line
     * to the next Phone Id line or the next line that starts without a space.
     * Fields take their last value in the block. Null when there is no block.
     *
     * With [mailboxes] (short codes, digits only), the call sample says
     * whether the registry's incoming number is one of them (lab
     * `incoming_number_is`): digits compared, null when the registry shows
     * none. The number itself never leaves this function.
     */
    fun parse(lines: Sequence<String>, slot: SimSlot, mailboxes: Set<String> = emptySet()): RegistryPhone? {
        val start = Regex("^\\s*Phone Id=${slot.index}\\s*$")
        var found = false
        var lte: String? = null
        var nr: String? = null
        val pdn = linkedSetOf<ImsTransport>()
        var callState: String? = null
        var callType: String? = null
        var foreground: String? = null
        var emergency: Int? = null
        var incomingIsMailbox: Boolean? = null
        val codes = mailboxes.map { NOT_DIGIT.replace(it, "") }.filter { it.isNotEmpty() }.toSet()
        for (line in lines) {
            if (!found) {
                found = start.matches(line)
                continue
            }
            if (ANY_PHONE.matches(line) || (line.isNotEmpty() && !line[0].isWhitespace())) break
            LTE_VOPS.findAll(line).lastOrNull()?.let { lte = it.groupValues[1] }
            NR_VOPS.findAll(line).lastOrNull()?.let { nr = it.groupValues[1] }
            for (match in IMS_PDN.findAll(line)) {
                if (match.groupValues[1] != "CONNECTED") continue
                pdn += pdnTransport(match.groupValues[2], match.groupValues[3])
            }
            CALL_STATE.findAll(line).lastOrNull()?.let { callState = it.groupValues[1] }
            CALL_NETWORK_TYPE.findAll(line).lastOrNull()?.let { callType = it.groupValues[1] }
            FOREGROUND.findAll(line).lastOrNull()?.let { foreground = it.groupValues[1] }
            // Only a hash is kept, to see a change during a call watch; the number itself never is.
            EMERGENCY_CALL.find(line)?.let { match ->
                emergency = match.groupValues[1].trim().takeIf { it.isNotEmpty() && it != "null" }?.hashCode()
            }
            // Compared here and dropped: only whether it is a mailbox short code is kept.
            if (codes.isNotEmpty()) {
                INCOMING_NUMBER.findAll(line).lastOrNull()?.let { match ->
                    val digits = NOT_DIGIT.replace(match.groupValues[1], "")
                    incomingIsMailbox = if (digits.isEmpty()) null else digits in codes
                }
            }
        }
        if (!found) return null
        return RegistryPhone(
            lteVops = LteVops.fromCode(lte),
            nrVops = NrVops.fromCode(nr),
            // The lab counts transports (Wi-Fi or mobile); two mobile entries are one transport.
            imsPdn = pdn.distinctBy { it.isWifi },
            call = CallSample(
                state = CallState.fromCode(callState),
                foreground = PreciseCallState.fromCode(foreground),
                networkType = callType?.toIntOrNull(),
                emergencyMark = emergency,
                incomingIsMailbox = incomingIsMailbox,
            ),
        )
    }

    /** WLAN is Wi-Fi; on WWAN the network type tells LTE from 5G. */
    private fun pdnTransport(transport: String, networkType: String): ImsTransport = when (transport) {
        "WLAN" -> ImsTransport.IWLAN
        "WWAN" -> when (networkType) {
            "LTE", "LTE_CA" -> ImsTransport.LTE
            "NR" -> ImsTransport.NR
            else -> ImsTransport.WWAN
        }
        else -> ImsTransport.OTHER
    }
}

/** LteVopsSupportInfo: 1 not available, 2 supported, 3 not supported (the lab fixed phone_check.py's reading of 1). */
@Serializable
enum class LteVops(val wire: String, val plain: String) {
    SUPPORTED("supported", "offered"),
    NOT_SUPPORTED("not_supported", "not offered"),
    NOT_AVAILABLE("not_available", "not reported by the cell");

    companion object {
        fun fromCode(code: String?): LteVops? = when (code) {
            "1" -> NOT_AVAILABLE
            "2" -> SUPPORTED
            "3" -> NOT_SUPPORTED
            else -> null
        }
    }
}

/** NrVopsSupportInfo uses another scale: 0 not supported, 1 over 3GPP access, 2 over non-3GPP access. */
@Serializable
enum class NrVops(val wire: String, val plain: String) {
    NOT_SUPPORTED("not_supported", "not offered"),
    SUPPORTED_3GPP("supported_3gpp", "offered over 5G"),
    SUPPORTED_NON_3GPP("supported_non_3gpp", "offered over non-3GPP access");

    companion object {
        fun fromCode(code: String?): NrVops? = when (code) {
            "0" -> NOT_SUPPORTED
            "1" -> SUPPORTED_3GPP
            "2" -> SUPPORTED_NON_3GPP
            else -> null
        }
    }
}

/** The IWLAN tunnel and ePDG lines of AOSP's IWLAN service in logcat (lab `parse_iwlan_log`), as typed events. */
sealed interface IwlanEvent {
    val at: Double

    data class BackoffTimer(override val at: Double, val seconds: Long) : IwlanEvent
    data class TunnelClosed(override val at: Double, val errorType: String?, val errorCode: Int?) : IwlanEvent
    data class TunnelOpened(override val at: Double) : IwlanEvent

    /** The phone's own ePDG lookup: whether it was the standard name for the SIM, and how many addresses came back. */
    data class EpdgDns(override val at: Double, val standardName: Boolean, val addresses: Int) : IwlanEvent
    data class LocalRetryWait(override val at: Double) : IwlanEvent
    data class DataCallFailed(override val at: Double, val cause: String, val retryMs: Long) : IwlanEvent
}

object IwlanLogParser {
    private val LOG_LINE = Regex("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]+?)\\s*:\\s(.*)$")
    private val BACKOFF = Regex("Backoff Timer: (\\d+)")
    private val CLOSED = Regex("Tunnel closed! APN: (\\w+), Error: TYPE: (\\w+)(?: ERR: (-?\\d+))?")
    private val DNS = Regex("^(epdg\\.\\S+\\.3gppnetwork\\.org): \\[(.*)\\]\\s*$")
    private val OPENED = Regex("Tunnel opened! APN: (\\w+)")
    private val FAILED = Regex("cause=(\\w+)\\(0x[0-9a-fA-F]+\\) retry=(-?\\d+)")
    private val ERROR_NAME = Regex("[A-Z][A-Z0-9_]{0,79}")

    /** At most this many events are kept, the newest. */
    const val MAX_EVENTS = 500

    /**
     * Events from `logcat -v epoch` lines. Only the ims APN counts; emergency
     * tunnels are ignored. Of the phone's own ePDG lookup only whether it
     * asked for [standardName] and the count of addresses are kept.
     */
    fun events(lines: Sequence<String>, standardName: String?): List<IwlanEvent> {
        val events = ArrayDeque<IwlanEvent>()
        for (line in lines) {
            val match = LOG_LINE.matchEntire(line) ?: continue
            val at = match.groupValues[1].toDoubleOrNull() ?: continue
            val tag = match.groupValues[2]
            val message = match.groupValues[3]
            val event: IwlanEvent? = run {
                BACKOFF.find(message)?.let { return@run IwlanEvent.BackoffTimer(at, it.groupValues[1].toLongOrNull() ?: 0) }
                CLOSED.find(message)?.let {
                    // A selector failure prints no ERR (IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED).
                    return@run if (it.groupValues[1] == "ims") {
                        IwlanEvent.TunnelClosed(at, it.groupValues[2].takeIf(ERROR_NAME::matches), it.groupValues[3].toIntOrNull())
                    } else {
                        null
                    }
                }
                if ("EpdgSelector" in tag) {
                    DNS.find(message)?.let { found ->
                        val addresses = found.groupValues[2].split(',', ' ').count { it.isNotBlank() }
                        return@run IwlanEvent.EpdgDns(at, found.groupValues[1] == standardName, addresses)
                    }
                }
                OPENED.find(message)?.let { return@run if (it.groupValues[1] == "ims") IwlanEvent.TunnelOpened(at) else null }
                if ("retry time has not passed" in message) return@run IwlanEvent.LocalRetryWait(at)
                if ("IwlanDataService" in tag) {
                    FAILED.find(message)?.let { found ->
                        val cause = found.groupValues[1]
                        val retry = found.groupValues[2].toLongOrNull() ?: 0
                        if (cause != "NONE" && ERROR_NAME.matches(cause)) return@run IwlanEvent.DataCallFailed(at, cause, retry)
                    }
                }
                null
            }
            if (event != null) {
                events.addLast(event)
                if (events.size > MAX_EVENTS) events.removeFirst()
            }
        }
        return events.toList()
    }

    /**
     * Whether Wi-Fi calling may be tried now (lab `iwlan_summary`). A network
     * back-off timer and the data service's retry time each hold it back
     * until they run out (TS 24.302 7.2.2.2); a tunnel that came up after a
     * hold started ends it. [now] is the phone's clock in epoch seconds.
     */
    fun summary(events: List<IwlanEvent>, now: Double): IwlanReading {
        val past = events.filter { it.at <= now }
        val tunnel = past.lastOrNull { it is IwlanEvent.TunnelOpened || it is IwlanEvent.TunnelClosed }
        val tunnelUp = tunnel?.let { it is IwlanEvent.TunnelOpened }
        val refusal = past.lastOrNull { it is IwlanEvent.TunnelClosed } as IwlanEvent.TunnelClosed?
        val lookup = past.lastOrNull { it is IwlanEvent.EpdgDns } as IwlanEvent.EpdgDns?
        val holds = past.mapNotNull { event ->
            when (event) {
                is IwlanEvent.BackoffTimer -> if (event.seconds > 0) Triple(event.at, event.at + event.seconds, IwlanHold.NETWORK_BACKOFF_TIMER) else null
                is IwlanEvent.DataCallFailed -> if (event.retryMs > 0) Triple(event.at, event.at + event.retryMs / 1000.0, IwlanHold.DATA_SERVICE_RETRY) else null
                else -> null
            }
        }
        val opened = past.filterIsInstance<IwlanEvent.TunnelOpened>().map { it.at }
        val active = holds.filter { (started, until, _) -> until > now && opened.none { it > started } }
        // The network's own timer is the cause to name; the data service's retry time usually mirrors it.
        val heldBy = when {
            active.isEmpty() -> null
            active.any { it.third == IwlanHold.NETWORK_BACKOFF_TIMER } -> IwlanHold.NETWORK_BACKOFF_TIMER
            else -> IwlanHold.DATA_SERVICE_RETRY
        }
        return IwlanReading(
            phoneLookup = lookup?.let {
                EpdgPhoneLookup(
                    resolved = it.addresses > 0,
                    standardName = it.standardName,
                    ageMinutes = ((now - it.at) / 60).toLong().coerceAtLeast(0),
                )
            },
            tunnelUp = tunnelUp,
            lastRefusal = refusal?.errorType,
            lastRefusalCode = refusal?.errorCode,
            selectionFailed = refusal != null && "SERVER_SELECTION_FAILED" in (refusal.errorType ?: "") && tunnelUp != true,
            heldBackBy = heldBy,
            heldBackMinutes = active.maxOfOrNull { it.second }?.let { ceil((it - now) / 60).toLong() },
        )
    }

    fun parse(lines: Sequence<String>, standardName: String?, now: Double): IwlanReading = summary(events(lines, standardName), now)
}

/** What holds Wi-Fi calling back after a failed attempt. */
@Serializable
enum class IwlanHold(val wire: String, val plain: String) {
    NETWORK_BACKOFF_TIMER("network_backoff_timer", "the network's back-off timer"),
    DATA_SERVICE_RETRY("data_service_retry", "Android's retry wait after a failed attempt"),
}

/** The DNS resolver the connected Wi-Fi hands the phone, as a class only; the addresses never leave the parser. */
@Serializable
enum class WifiResolver(val wire: String, val plain: String) {
    LOCAL("local_network", "a resolver in the local network (usually the router)"),
    PUBLIC("public", "a resolver outside the local network"),
    MIXED("mixed", "local and public resolvers"),
    NONE("none", "none given"),
    NOT_ON_WIFI("not_on_wifi", "not on Wi-Fi"),
    UNREADABLE("unreadable", "could not be read"),
}

/** `dumpsys connectivity`: the connected Wi-Fi's DNS servers (lab `wifi_dns_servers`), classed and dropped. */
object WifiResolverParser {
    private val WLAN_DNS = Regex("InterfaceName: wlan\\d+ .*?DnsAddresses: \\[([^\\]]*)\\]")
    private val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val IPV6 = Regex("[0-9a-fA-F:]{2,39}")

    fun parse(lines: Sequence<String>): WifiResolver {
        var any = false
        for (line in lines) {
            any = true
            if ("ni{WIFI CONNECTED" !in line) continue
            val found = WLAN_DNS.find(line) ?: continue
            val servers = found.groupValues[1].split(',').map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }
            return classify(servers)
        }
        return if (any) WifiResolver.NOT_ON_WIFI else WifiResolver.UNREADABLE
    }

    fun classify(servers: List<String>): WifiResolver {
        if (servers.isEmpty()) return WifiResolver.NONE
        val local = servers.map(::isLocal)
        return when {
            local.all { it } -> WifiResolver.LOCAL
            local.none { it } -> WifiResolver.PUBLIC
            else -> WifiResolver.MIXED
        }
    }

    /** Private, link-local, loopback or unique-local: an address inside the local network. */
    fun isLocal(address: String): Boolean {
        if (!IPV4.matches(address) && !(':' in address && IPV6.matches(address))) return false
        // A numeric literal only: getByName never looks a name up here.
        val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        val uniqueLocal = ip.address.size == 16 && (ip.address[0].toInt() and 0xfe) == 0xfc
        return ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isLoopbackAddress || uniqueLocal
    }
}
