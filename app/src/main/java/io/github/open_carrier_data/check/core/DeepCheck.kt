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
    APN_LIST(30, false);

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
    NOT_FOUND("not_found"),
    UNREADABLE("unreadable"),
}

@Serializable
enum class ImsTransport(val wire: String, val plain: String) {
    LTE("lte", "4G (LTE)"),
    IWLAN("iwlan", "Wi-Fi (IWLAN)"),
    NR("nr", "5G (NR)"),
    OTHER("other", "another network type");

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

/** Samsung's IMS state for one slot, typed. No identity, address or log text survives the parser. */
@Serializable
data class ImsReading(
    val source: ImsSource,
    /** null when the IMS service shows no task for the slot. */
    val registered: Boolean? = null,
    /** Samsung's state word for the slot, lowercased, for example registered or not_registered. */
    val state: String? = null,
    /** Network of the current registration; only set while registered. */
    val transport: ImsTransport? = null,
    val services: List<ImsService> = emptyList(),
    /** Samsung's name for the carrier table it applies, for example Vodafone_DE. */
    val mno: String? = null,
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

@Serializable
enum class WifiCallingSwitch(val wire: String, val plain: String) {
    ON("on", "on"),
    OFF("off", "off"),
    NOT_SET("not_set", "never set on this phone"),
    UNREADABLE("unreadable", "could not be read"),
    NOT_CHECKED("not_checked", "not checked (Samsung's switch only)"),
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
    val wifiCallingSwitch: WifiCallingSwitch,
    val epdgName: EpdgName,
    /** null when not checked (no home network code). */
    val apnList: ApnList?,
    val lookupNetwork: LookupNetwork = LookupNetwork.NOT_KNOWN,
    /** Private DNS active on the default network during the lookup; null when not known. */
    val privateDnsActive: Boolean? = null,
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
    fun run(target: DeepCheckTarget, runner: CommandRunner): DeepCheckReading {
        val ims = runner.run(ShellCommand.SAMSUNG_IMS, target) { SamsungImsParser.parse(it, target.slot) }
            ?: ImsReading(ImsSource.UNREADABLE)
        val switch = if (ims.source == ImsSource.SAMSUNG) {
            runner.run(ShellCommand.WIFI_CALLING_SWITCH, target, WifiCallingSwitchParser::parse)
                ?: WifiCallingSwitch.UNREADABLE
        } else {
            WifiCallingSwitch.NOT_CHECKED
        }
        val epdg = if (target.home == null) {
            EpdgName.NOT_CHECKED
        } else {
            runner.run(ShellCommand.EPDG_LOOKUP, target, EpdgLookupParser::parse) ?: EpdgName.UNKNOWN
        }
        val apns = target.home?.let { runner.run(ShellCommand.APN_LIST, target, ApnListParser::parse) ?: ApnList.UNREADABLE }
        return DeepCheckReading(ims, switch, epdg, apns)
    }
}
