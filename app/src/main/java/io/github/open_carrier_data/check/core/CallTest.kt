package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The guided call test (OCD round 19, first steps, item 3): read-only and
 * allowlisted. The app shows the SIM's own mailbox number from a bundled
 * copy of OCD Lab's call_targets.json and asks the person to dial it. The
 * app never places, answers or ends a call. While the call runs, the deep
 * check's process reads the call state and the call network type
 * (mCallNetworkType: 18 IWLAN, 13 LTE, 20 NR) from the registry once a
 * second. The person then says whether they heard the mailbox. Results stay
 * on the phone, apart from the report: round 19 keeps such results as a
 * separate observed status, and nothing here uploads.
 */

/** OCD Lab's call_targets.json, bundled as assets/call-targets.json. */
@Serializable
data class CallTargetsFile(
    val description: String,
    @SerialName("by_spn") val bySpn: List<SpnTarget> = emptyList(),
    @SerialName("by_mccmnc") val byMccMnc: Map<String, NetworkTarget> = emptyMap(),
)

@Serializable
data class SpnTarget(
    val spn: String,
    val mccmnc: List<String>,
    val brand: String,
    val mailbox: String,
    val verification: String,
)

@Serializable
data class NetworkTarget(val brand: String, val mailbox: String, val verification: String)

/** The mailbox the app may show for one SIM. */
data class Mailbox(val brand: String, val number: String, val verification: String) {
    /** The table marks a code that rests on one secondary source as unverified. */
    val unverified: Boolean get() = verification.startsWith("unverified")
}

/**
 * Which number the app may show, ported from OCD Lab's `mailbox_for` and
 * `check_auto_call`. A number is shown only when it is this SIM's own entry,
 * a plain 3 to 6 digit short code, not an emergency number (a fixed list and
 * Android's own answer), and not a service short code. Anything else gives
 * no number at all, never a fallback.
 */
object CallTargets {
    const val ASSET_NAME = "call-targets.json"

    /**
     * Emergency and public-service numbers that must never be shown, whatever a
     * table says (lab_calls.py EMERGENCY_NUMBERS): EU and German emergency,
     * other countries' emergency numbers a roaming phone might map, the German
     * ambulance number and the harmonised European 116 numbers.
     */
    val EMERGENCY_NUMBERS = setOf(
        "112", "110", "911", "999", "000", "08", "100", "101", "102", "103", "15", "17", "18", "118", "119",
        "19222", "115", "116000", "116006", "116111", "116116", "116117", "116123",
    )

    /** German special-service ranges: 11x short codes, every number starting with 0, and 19x. */
    val SERVICE_PREFIXES = listOf("11", "0", "19")
    private val MAILBOX_SHAPE = Regex("[1-9][0-9]{2,5}")

    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): CallTargetsFile = json.decodeFromString(CallTargetsFile.serializer(), text)

    /** True when [number] may be shown as a mailbox: shape, no emergency or service number. */
    fun safe(number: String, isEmergency: (String) -> Boolean): Boolean =
        MAILBOX_SHAPE.matches(number) &&
            number !in EMERGENCY_NUMBERS &&
            SERVICE_PREFIXES.none { number.startsWith(it) } &&
            !isEmergency(number)

    /**
     * This SIM's own mailbox: the SPN rule first, then the network code
     * (lab `mailbox_for`). [isEmergency] is Android's answer for the number;
     * when it throws or says yes, nothing is shown.
     */
    fun mailboxFor(file: CallTargetsFile, homeMccMnc: String?, spn: String?, isEmergency: (String) -> Boolean): Mailbox? {
        val code = homeMccMnc ?: return null
        val folded = spn?.casefold()?.trim().orEmpty()
        val bySpn = file.bySpn.firstOrNull { it.spn.casefold().trim() == folded && code in it.mccmnc }
        val entry = bySpn?.let { Mailbox(it.brand, it.mailbox, it.verification) }
            ?: file.byMccMnc[code]?.let { Mailbox(it.brand, it.mailbox, it.verification) }
            ?: return null
        val emergency = { number: String -> runCatching { isEmergency(number) }.getOrDefault(true) }
        return entry.takeIf { safe(it.number, emergency) }
    }
}

/** TelephonyManager.CALL_STATE_* as the registry prints mCallState. */
@Serializable
enum class CallState(val wire: String) {
    IDLE("idle"),
    RINGING("ringing"),
    OFFHOOK("offhook");

    companion object {
        fun fromCode(code: String?): CallState? = when (code) {
            "0" -> IDLE
            "1" -> RINGING
            "2" -> OFFHOOK
            else -> null
        }
    }
}

/** PreciseCallState's foreground call state, as the registry prints it (lab PRECISE_CALL_STATES). */
@Serializable
enum class PreciseCallState(val wire: String) {
    NOT_VALID("not_valid"),
    IDLE("idle"),
    ACTIVE("active"),
    HOLDING("holding"),
    DIALING("dialing"),
    ALERTING("alerting"),
    INCOMING("incoming"),
    WAITING("waiting"),
    DISCONNECTED("disconnected"),
    DISCONNECTING("disconnecting");

    companion object {
        fun fromCode(code: String?): PreciseCallState? = when (code) {
            "-1" -> NOT_VALID
            "0" -> IDLE
            "1" -> ACTIVE
            "2" -> HOLDING
            "3" -> DIALING
            "4" -> ALERTING
            "5" -> INCOMING
            "6" -> WAITING
            "7" -> DISCONNECTED
            "8" -> DISCONNECTING
            else -> null
        }
    }
}

/**
 * One reading of a phone's call: state, foreground precise state and
 * mCallNetworkType. No number, no cell. It never leaves the deep check's
 * process; only [CallWatchSummary] does.
 */
data class CallSample(
    val state: CallState? = null,
    val foreground: PreciseCallState? = null,
    /** TelephonyManager.NETWORK_TYPE_* (0 unknown, 13 LTE, 18 IWLAN, 20 NR); not the RIL numbers of ServiceState. */
    val networkType: Int? = null,
    /**
     * A hash of the registry's mOutgoingCallEmergencyNumber, null while it is
     * "null". Only a change during the watch is used: it means an emergency
     * call was placed, and the watch is then discarded.
     */
    val emergencyMark: Int? = null,
)

/** The call bearer a call network type stands for (lab CALL_BEARERS); 2G and 3G types are a circuit-switched call. */
@Serializable
enum class CallBearer(val wire: String, val plain: String, val capability: String?) {
    VOLTE("volte", "VoLTE (call over 4G)", "volte"),
    VOWIFI("vowifi", "Wi-Fi calling", "vowifi"),
    VONR("vonr", "VoNR (call over 5G)", "vonr"),
    CS("cs", "a 2G or 3G call (not over IMS)", null),
    UNKNOWN("unknown", "not reported by the phone", null);

    companion object {
        /** lab_calls.py `call_type_reading`. */
        fun of(networkType: Int?): CallBearer? = when (networkType) {
            null, 0 -> null
            Radio.LTE, Radio.LTE_CA -> VOLTE
            Radio.IWLAN -> VOWIFI
            Radio.NR -> VONR
            else -> CS
        }
    }
}

@Serializable
enum class CallDirection(val wire: String, val plain: String) {
    OUTGOING("outgoing", "outgoing"),
    INCOMING("incoming", "incoming"),
    UNKNOWN("unknown", "not known"),
}

/** What a watched call showed (lab `summarize_call`, plus the direction and the number of calls). */
@Serializable
data class CallWatchSummary(
    val samples: Int = 0,
    val unreadable: Int = 0,
    val statesSeen: List<CallState> = emptyList(),
    val foregroundSeen: List<PreciseCallState> = emptyList(),
    /** The watch began with the phone idle, so the whole call was seen. */
    val startedIdle: Boolean = false,
    /** How many times the phone went from idle to a call. */
    val callsSeen: Int = 0,
    val direction: CallDirection = CallDirection.UNKNOWN,
    /** The call went off-hook (lab "connected"). */
    val connected: Boolean = false,
    val wentActive: Boolean = false,
    /** Every call network type read while off-hook, apart from 0 (unknown). */
    val callNetworkTypes: List<Int> = emptyList(),
    /** The last one, preferably read while the call was active. */
    val callNetworkType: Int? = null,
    val bearer: CallBearer? = null,
    val ended: Boolean = false,
    val stoppedByLimit: Boolean = false,
    /** An emergency call showed during the watch. The app then keeps nothing from it. */
    val emergencySeen: Boolean = false,
) {
    fun encode(): String = wire.encodeToString(serializer(), this)

    /** The summary with only the emergency flag: what the app gets when an emergency call showed. */
    fun discarded(): CallWatchSummary = CallWatchSummary(samples = samples, emergencySeen = true)

    companion object {
        private val wire = Json { encodeDefaults = true }

        fun decode(text: String): CallWatchSummary = wire.decodeFromString(serializer(), text)
    }
}

/** Collects samples while a call is watched. Keeps counts and typed states only, never a sample list. */
class CallWatch {
    private var samples = 0
    private var unreadable = 0
    private val states = linkedSetOf<CallState>()
    private val foreground = linkedSetOf<PreciseCallState>()
    private var first: CallState? = null
    private var previous: CallState? = null
    private var calls = 0
    private val types = linkedSetOf<Int>()
    private var lastType: Int? = null
    private var lastActiveType: Int? = null
    private var stoppedByLimit = false
    private var emergencyAtStart: Int? = null
    private var emergencyRead = false
    private var emergencySeen = false

    @Synchronized
    fun add(sample: CallSample?) {
        samples++
        val state = sample?.state
        if (sample == null || state == null) {
            unreadable++
            return
        }
        if (!emergencyRead) {
            emergencyAtStart = sample.emergencyMark
            emergencyRead = true
        } else if (sample.emergencyMark != emergencyAtStart) {
            emergencySeen = true
        }
        if (first == null) first = state
        states += state
        sample.foreground?.let { foreground += it }
        if (state != CallState.IDLE && (previous == null || previous == CallState.IDLE)) calls++
        previous = state
        val type = sample.networkType?.takeIf { it != 0 }
        if (state == CallState.OFFHOOK && type != null) {
            types += type
            lastType = type
            if (sample.foreground == PreciseCallState.ACTIVE) lastActiveType = type
        }
    }

    @Synchronized
    fun markStoppedByLimit() {
        stoppedByLimit = true
    }

    @Synchronized
    fun summary(): CallWatchSummary {
        val connected = CallState.OFFHOOK in states
        val dialed = PreciseCallState.DIALING in foreground || PreciseCallState.ALERTING in foreground
        val rang = CallState.RINGING in states || PreciseCallState.INCOMING in foreground || PreciseCallState.WAITING in foreground
        val startedIdle = first == CallState.IDLE
        // Only an outgoing call dials or alerts; an incoming one rings first. A
        // short dialing phase can fall between two samples, so a call seen from
        // idle that went off-hook without ringing also counts as outgoing.
        val direction = when {
            dialed && !rang -> CallDirection.OUTGOING
            rang && !dialed -> CallDirection.INCOMING
            !dialed && !rang && connected && startedIdle -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }
        val type = lastActiveType ?: lastType
        return CallWatchSummary(
            samples = samples,
            unreadable = unreadable,
            statesSeen = states.toList(),
            foregroundSeen = foreground.toList(),
            startedIdle = startedIdle,
            callsSeen = calls,
            direction = direction,
            connected = connected,
            wentActive = PreciseCallState.ACTIVE in foreground,
            callNetworkTypes = types.toList(),
            callNetworkType = type,
            bearer = CallBearer.of(type) ?: if (connected) CallBearer.UNKNOWN else null,
            ended = connected && previous == CallState.IDLE,
            stoppedByLimit = stoppedByLimit,
        ).let { if (emergencySeen) it.discarded() else it }
    }
}

/** The person's answer after the call. */
@Serializable
enum class HeardMailbox(val wire: String, val plain: String) {
    YES("yes", "you heard the mailbox"),
    NO("no", "you did not hear the mailbox"),
    NOT_SURE("not_sure", "you were not sure"),
}

/**
 * One finished call test, kept in the app's private storage on this phone.
 * The phone and SIM fields are the cell key round 19 names (device, carrier
 * profile, software stack); there is no number other than the mailbox short
 * code, no time of day and no location.
 */
@Serializable
data class CallTestRecord(
    val date: String,
    val model: String,
    val codename: String,
    val romFamily: String,
    val androidMajor: Int,
    /** samsung_ims, aosp_telephony, or not_known when no deep check ran. */
    val imsStack: String,
    val homeMccMnc: String?,
    val spn: String?,
    val ocdProfile: String?,
    val brand: String,
    val mailbox: String,
    val watch: CallWatchSummary,
    val airplaneModeAtStart: Boolean?,
    val wifiAtStart: Boolean?,
    val heard: HeardMailbox,
) {
    /** What was measured, in plain words; never "works". */
    val plain: String
        get() = buildString {
            append(
                when {
                    !watch.connected -> "No call was seen while the app watched"
                    watch.direction == CallDirection.OUTGOING -> "An outgoing call"
                    watch.direction == CallDirection.INCOMING -> "An incoming call (not the mailbox test)"
                    else -> "A call of unknown direction"
                },
            )
            if (watch.connected) {
                val type = watch.callNetworkType
                append(
                    if (type == null) {
                        ", call network type not reported"
                    } else {
                        ", call network type $type (${Radio.wire(type).uppercase()}): ${watch.bearer?.plain}"
                    },
                )
                append(if (watch.wentActive) ", went active" else ", did not go active")
            }
            append("; ").append(heard.plain).append('.')
            if (watch.callsSeen > 1) append(" ${watch.callsSeen} calls were seen during the watch.")
            if (watch.bearer == CallBearer.VOWIFI && airplaneModeAtStart != true) {
                append(" Airplane mode was off, so for Wi-Fi calling this is not the airplane-mode test round 19 asks for.")
            }
        }

    /** The test round 19's evidence needs: an outgoing call with a measured IMS call type and the person's yes. */
    val measuredOutgoing: Boolean
        get() = watch.direction == CallDirection.OUTGOING && watch.wentActive &&
            watch.bearer?.capability != null && heard == HeardMailbox.YES
}

/** The stored call tests, newest last. */
@Serializable
data class CallTestLog(val schema: String = SCHEMA, val tests: List<CallTestRecord> = emptyList()) {
    fun plus(record: CallTestRecord): CallTestLog = copy(tests = (tests + record).takeLast(MAX))

    fun encode(): String = wire.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = "ocd-check-call-tests.v1"
        const val MAX = 50
        private val wire = Json { encodeDefaults = true }

        /** An unreadable or foreign file gives an empty log, never a crash. */
        fun decode(text: String): CallTestLog =
            runCatching { wire.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.schema == SCHEMA } ?: CallTestLog()
    }
}
