package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A SIM slot, 0-based as SubscriptionManager and Samsung's IMS dump count them. */
class SimSlot private constructor(val index: Int) {
    /** 1-based, as Samsung's per-slot settings are named (wifi_call_enable1, wifi_call_enable2). */
    val number: Int get() = index + 1

    override fun equals(other: Any?) = other is SimSlot && other.index == index
    override fun hashCode() = index
    override fun toString() = "SimSlot($index)"

    companion object {
        fun of(index: Int?): SimSlot? = index?.takeIf { it in 0..3 }?.let(::SimSlot)
    }
}

/** The SIM's home network, from ASCII digits only: 3 for the MCC, 2 or 3 for the MNC. */
class HomeNetwork private constructor(val mcc: String, val mnc: String) {
    val mccmnc: String get() = mcc + mnc

    /** The 3GPP ePDG name, built as AOSP's EpdgSelector and phone_check.py `epdg_fqdn` build it. */
    val epdgFqdn: String get() = "epdg.epc.mnc${mnc.padStart(3, '0')}.mcc$mcc.pub.3gppnetwork.org"

    override fun equals(other: Any?) = other is HomeNetwork && other.mccmnc == mccmnc
    override fun hashCode() = mccmnc.hashCode()
    override fun toString() = "HomeNetwork($mccmnc)"

    companion object {
        private val shape = Regex("[0-9]{5,6}")

        fun of(mccmnc: String?): HomeNetwork? =
            mccmnc?.takeIf(shape::matches)?.let { HomeNetwork(it.take(3), it.drop(3)) }
    }
}

/** What the deep check runs for: one SIM slot and, when known, its home network. */
data class DeepCheckTarget(val slot: SimSlot, val home: HomeNetwork?)

/**
 * The deep check's whole command allowlist. Each entry builds its argv from
 * constants and the validated [DeepCheckTarget] only, so no other text can
 * reach a process. Every entry only reads.
 */
enum class ShellCommand(val timeoutSeconds: Long, val firstLineOnly: Boolean) {
    /** Samsung's IMS state. */
    SAMSUNG_IMS(60, false),

    /** Samsung's Wi-Fi calling switch for the slot. */
    WIFI_CALLING_SWITCH(10, true),

    /** Whether the carrier's standard ePDG name resolves. The first line tells; the rest is never read. */
    EPDG_LOOKUP(20, true),

    /** The APN rows for the home network, allowlisted columns only. */
    APN_LIST(30, false),

    /** Android telephony's own IMS state (phones without Samsung's IMS, for example LineageOS). */
    AOSP_IMS(60, false),

    /** The network's offer (VoPS), where the IMS data connection is, and the call state and call network type. */
    TELEPHONY_REGISTRY(20, false),

    /** The IWLAN service's lines in the log: the phone's own ePDG lookup, tunnel results and back-off. Dump only. */
    IWLAN_LOG(20, false),

    /** The connected Wi-Fi's DNS servers, read only to class them as local or not. */
    WIFI_DNS(20, false);

    /** The argv, or null when the target lacks what this command needs. */
    fun argv(target: DeepCheckTarget): List<String>? = when (this) {
        SAMSUNG_IMS -> listOf("dumpsys", "secims")
        WIFI_CALLING_SWITCH -> listOf("settings", "get", "system", "wifi_call_enable${target.slot.number}")
        EPDG_LOOKUP -> target.home?.let { listOf("ping", "-c1", "-W2", it.epdgFqdn) }
        APN_LIST -> target.home?.let {
            listOf(
                "content", "query",
                "--uri", "content://telephony/carriers",
                "--projection", ApnListParser.COLUMNS.joinToString(":"),
                "--where", "numeric='${it.mccmnc}'",
            )
        }
        AOSP_IMS -> listOf("dumpsys", "activity", "service", "com.android.phone")
        TELEPHONY_REGISTRY -> listOf("dumpsys", "telephony.registry")
        // -d dumps and exits; -s silences every other tag. The IWLAN tags carry the slot.
        IWLAN_LOG -> listOf(
            "logcat", "-d", "-v", "epoch", "-b", "main", "-b", "radio", "-s",
            "EpdgSelector:V", "IwlanDataService[${target.slot.index}]:V", "EpdgTunnelManager[${target.slot.index}]:V",
        )
        WIFI_DNS -> target.home?.let { listOf("dumpsys", "connectivity") }
    }
}

/** Runs allowlisted commands. Implementations take a [ShellCommand], never a command string. */
interface CommandRunner {
    /** Runs [command] for [target] and hands its output lines to [read]; null when it did not run or timed out. */
    fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T?
}

@Serializable
enum class ImsSource(val wire: String) {
    SAMSUNG("samsung_ims"),

    /** Android telephony's own IMS state, as LineageOS and other AOSP-based builds print it. */
    AOSP("aosp_telephony"),
    NOT_FOUND("not_found"),
    UNREADABLE("unreadable"),
}

@Serializable
enum class ImsTransport(val wire: String, val plain: String) {
    LTE("lte", "4G (LTE)"),
    IWLAN("iwlan", "Wi-Fi (IWLAN)"),
    NR("nr", "5G (NR)"),

    /** The mobile network, without a known radio type (Android's IMS log names only WWAN). */
    WWAN("wwan", "the mobile network"),
    OTHER("other", "another network type");

    val isWifi: Boolean get() = this == IWLAN

    companion object {
        /** phone_check.py RAT_NAMES: Android's radio technology numbers in Samsung's IMS log. */
        fun fromRat(rat: String): ImsTransport = when (rat) {
            "13", "14", "19" -> LTE
            "18" -> IWLAN
            "20" -> NR
            else -> OTHER
        }
    }
}

/** IMS services by the names Samsung's IMS prints. A name not listed here is dropped. */
@Serializable
enum class ImsService(val wire: String, val plain: String) {
    MMTEL("mmtel", "calls"),
    MMTEL_VIDEO("mmtel-video", "video calls"),
    SMSIP("smsip", "SMS over IMS"),
    SS("ss", "call settings (supplementary services)"),
    CDPN("cdpn", "called party number"),
    OPTIONS("options", "capability exchange"),
    PRESENCE("presence", "presence"),
    IM("im", "chat"),
    FT("ft", "file transfer"),
    FT_HTTP("ft_http", "file transfer over HTTP"),
    SLM("slm", "standalone messaging"),
    IS("is", "image share"),
    VS("vs", "video share"),
    EC("ec", "enriched calling"),
    EUC("euc", "end user confirmation"),
    GLS("gls", "geolocation sharing"),
    LASTSEEN("lastseen", "last seen"),
    CHATBOT("chatbot-communication", "chatbots"),
    PLUG_IN("plug-in", "plug-ins"),
    DATACHANNEL("datachannel", "data channel");

    companion object {
        fun fromWire(name: String): ImsService? = entries.firstOrNull { it.wire == name }
    }
}

/** IMS state for one slot (Samsung's IMS or Android's own), typed. No identity, address or log text survives the parser. */
@Serializable
data class ImsReading(
    val source: ImsSource,
    /** null when the IMS service shows no task for the slot. */
    val registered: Boolean? = null,
    /** The state word, lowercased: Samsung's (registered, not_registered, ...) or Android's (not_registered, registering, registered). */
    val state: String? = null,
    /** Network of the current registration; only set while registered. On Android's IMS it is where the IMS data connection is. */
    val transport: ImsTransport? = null,
    val services: List<ImsService> = emptyList(),
    /** Samsung's name for the carrier table it applies, for example Vodafone_DE. */
    val mno: String? = null,
    /** Android's IMS: the transport the IMS data connection is connected on, when it is exactly one. */
    val imsPdn: ImsTransport? = null,
    /** Android's IMS: the registration log's transport, kept only where it differs from the IMS data connection's (a stale log line). */
    val registrationLogTransport: ImsTransport? = null,
    /** Android's IMS: the reason of the last sign-out in the registration log. */
    val lastDisconnect: ImsDisconnect? = null,
) {
    init {
        require(state == null || STATE.matches(state)) { "state shape" }
        require(mno == null || validMno(mno)) { "mno shape" }
    }

    val hasCalls: Boolean get() = ImsService.MMTEL in services

    companion object {
        val STATE = Regex("[a-z_]{2,32}")
        private val MNO = Regex("[A-Za-z0-9][A-Za-z0-9_.&:-]{0,47}")

        fun validMno(value: String): Boolean = MNO.matches(value) && safeToken(value)
    }
}

/** An ImsReasonInfo code and Android's name for it (for example 0, CODE_UNSPECIFIED). The free-text message is never read. */
@Serializable
data class ImsDisconnect(val code: Int, val name: String? = null) {
    init {
        require(validCode(code)) { "code range" }
        require(name == null || validName(name)) { "name shape" }
    }

    companion object {
        private val NAME = Regex("CODE_[A-Z0-9_]{1,60}")

        fun validCode(value: Int): Boolean = value in 0..999_999

        fun validName(value: String): Boolean = NAME.matches(value)
    }
}

/** What the network offers on the serving cell, from the registry: voice over IMS (VoPS) on LTE and on 5G. */
@Serializable
data class NetworkOffer(val lteVops: LteVops? = null, val nrVops: NrVops? = null)

/** The phone's own ePDG lookup in the IWLAN log: whether it got an address, whether it asked for the SIM's standard name, and how long ago. */
@Serializable
data class EpdgPhoneLookup(val resolved: Boolean, val standardName: Boolean, val ageMinutes: Long) {
    /** A coarse age for the report, never a time of day. */
    val ageBucket: String
        get() = when {
            ageMinutes < 60 -> "under_1h"
            ageMinutes < 24 * 60 -> "under_1d"
            else -> "older"
        }
}

/** AOSP IWLAN's state from the log (lab `iwlan_summary`): the phone's lookup, the tunnel, and whether a back-off holds Wi-Fi calling. */
@Serializable
data class IwlanReading(
    val phoneLookup: EpdgPhoneLookup? = null,
    /** null when the log holds no tunnel line for the ims APN. */
    val tunnelUp: Boolean? = null,
    /** The error type of the last refused tunnel, for example IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED. */
    val lastRefusal: String? = null,
    val lastRefusalCode: Int? = null,
    /** The last refusal was the ePDG selector finding no server, and no tunnel came up after it. */
    val selectionFailed: Boolean = false,
    val heldBackBy: IwlanHold? = null,
    val heldBackMinutes: Long? = null,
) {
    init {
        require(lastRefusal == null || REFUSAL.matches(lastRefusal)) { "refusal shape" }
    }

    companion object {
        private val REFUSAL = Regex("IWLAN_[A-Z0-9_]{1,74}")
    }
}

@Serializable
enum class WifiCallingSwitch(val wire: String, val plain: String) {
    ON("on", "on"),
    OFF("off", "off"),
    NOT_SET("not_set", "never set on this phone"),
    UNREADABLE("unreadable", "could not be read"),
    NOT_CHECKED("not_checked", "not checked"),
}

@Serializable
enum class EpdgName(val wire: String, val plain: String) {
    RESOLVES("resolves", "resolves"),
    DOES_NOT_RESOLVE("does_not_resolve", "does not resolve"),
    UNKNOWN("unknown", "not known (the lookup gave no clear answer)"),
    NOT_CHECKED("not_checked", "not checked (no home network code)"),
}

/** The phone's default network when the ePDG name was looked up. Read by the app itself. */
@Serializable
enum class LookupNetwork(val wire: String, val plain: String) {
    WIFI("wifi", "Wi-Fi"),
    MOBILE("mobile", "mobile data"),
    OTHER("other", "another network (for example a VPN)"),
    NONE("none", "no network"),
    NOT_KNOWN("not_known", "not known"),
}

@Serializable
enum class ApnEdited(val wire: String) {
    NO("no"),
    BY_USER("user"),
    BY_CARRIER("carrier"),
    OTHER("other"),
}

/** One APN row: allowlisted columns, each checked for shape. No user name, password, server, proxy or MMSC column is read. */
@Serializable
data class ApnRow(
    val apn: String,
    val types: List<String>,
    val protocol: String? = null,
    val roamingProtocol: String? = null,
    val carrierEnabled: Boolean? = null,
    /** How the row is tied to an MVNO SIM: spn, imsi, gid or iccid. The match value itself is not read. */
    val mvnoType: String? = null,
    val edited: ApnEdited = ApnEdited.OTHER,
) {
    init {
        require(validApn(apn)) { "apn shape" }
        require(types.all { it in ApnListParser.TYPES }) { "apn types" }
        require(protocol == null || protocol in ApnListParser.PROTOCOLS) { "protocol" }
        require(roamingProtocol == null || roamingProtocol in ApnListParser.PROTOCOLS) { "roaming protocol" }
        require(mvnoType == null || mvnoType in ApnListParser.MVNO_TYPES) { "mvno type" }
    }

    companion object {
        private val APN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

        fun validApn(value: String): Boolean = APN.matches(value) && safeToken(value)
    }
}

@Serializable
data class ApnList(
    val readable: Boolean,
    val rows: List<ApnRow> = emptyList(),
    /** Rows not shown: odd shape, emergency, deleted, or past the row limit. */
    val leftOut: Int = 0,
) {
    companion object {
        val UNREADABLE = ApnList(readable = false)
    }
}

/** Everything the deep check read, typed. */
@Serializable
data class DeepCheckReading(
    val ims: ImsReading,
    /** Samsung's switch on Samsung's IMS; the phone's own setting (isWfcEnabledByUser) on Android's IMS. */
    val wifiCallingSwitch: WifiCallingSwitch,
    val epdgName: EpdgName,
    /** null when not checked (no home network code). */
    val apnList: ApnList?,
    val lookupNetwork: LookupNetwork = LookupNetwork.NOT_KNOWN,
    /** Private DNS active on the default network during the lookup; null when not known. */
    val privateDnsActive: Boolean? = null,
    /** VoPS from the registry; null when the registry was not read. */
    val networkOffer: NetworkOffer? = null,
    /** AOSP IWLAN's log state; null on Samsung's IMS or when not read. */
    val iwlan: IwlanReading? = null,
    /** The class of the Wi-Fi's DNS resolver; null when not checked. */
    val wifiResolver: WifiResolver? = null,
) {
    fun encode(): String = wire.encodeToString(serializer(), this)

    companion object {
        private val wire = Json { encodeDefaults = true }

        /** Strict: unknown keys or a value of the wrong shape fail. */
        fun decode(text: String): DeepCheckReading = wire.decodeFromString(serializer(), text)
    }
}

/** Runs the allowlist for one target. Only [ShellCommand] entries run; their output goes straight into a parser. */
object DeepCheck {
    /** [now] is the phone's clock in epoch seconds, for the age of log lines. */
    fun run(
        target: DeepCheckTarget,
        runner: CommandRunner,
        now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    ): DeepCheckReading {
        val samsung = runner.run(ShellCommand.SAMSUNG_IMS, target) { SamsungImsParser.parse(it, target.slot) }
            ?: ImsReading(ImsSource.UNREADABLE)
        // Read for both stacks: VoPS is the network's, and Android's IMS takes its transport from here.
        val registry = runner.run(ShellCommand.TELEPHONY_REGISTRY, target) { lines -> Optional(RegistryParser.parse(lines, target.slot)) }
        var ims = samsung
        var iwlan: IwlanReading? = null
        val switch = when (samsung.source) {
            ImsSource.SAMSUNG -> runner.run(ShellCommand.WIFI_CALLING_SWITCH, target, WifiCallingSwitchParser::parse)
                ?: WifiCallingSwitch.UNREADABLE
            ImsSource.NOT_FOUND -> {
                // No Samsung IMS: read Android telephony's own IMS state (lab `parse_aosp_ims`).
                val aosp = runner.run(ShellCommand.AOSP_IMS, target) { lines -> Optional(AospImsParser.parse(lines, target.slot)) }
                ims = AospImsParser.reading(aosp?.value, registry?.value, dumpRead = aosp != null)
                iwlan = runner.run(ShellCommand.IWLAN_LOG, target) { IwlanLogParser.parse(it, target.home?.epdgFqdn, now()) }
                when (aosp?.value?.wifiCallingByUser) {
                    true -> WifiCallingSwitch.ON
                    false -> WifiCallingSwitch.OFF
                    null -> if (aosp == null) WifiCallingSwitch.NOT_CHECKED else WifiCallingSwitch.UNREADABLE
                }
            }
            else -> WifiCallingSwitch.NOT_CHECKED
        }
        val epdg = if (target.home == null) {
            EpdgName.NOT_CHECKED
        } else {
            runner.run(ShellCommand.EPDG_LOOKUP, target, EpdgLookupParser::parse) ?: EpdgName.UNKNOWN
        }
        val resolver = target.home?.let { runner.run(ShellCommand.WIFI_DNS, target, WifiResolverParser::parse) ?: WifiResolver.UNREADABLE }
        val apns = target.home?.let { runner.run(ShellCommand.APN_LIST, target, ApnListParser::parse) ?: ApnList.UNREADABLE }
        return DeepCheckReading(
            ims = ims,
            wifiCallingSwitch = switch,
            epdgName = epdg,
            apnList = apns,
            networkOffer = registry?.value?.let { NetworkOffer(it.lteVops, it.nrVops) },
            iwlan = iwlan,
            wifiResolver = resolver,
        )
    }

    /** A parser result that may be null, told apart from a command that did not run. */
    private class Optional<T>(val value: T?)
}
