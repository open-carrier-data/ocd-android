package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 49, item 2: checks that need no call. They read what the phone already knows, send nothing, change
 * nothing and stay on the phone and the entry screen. A check never produces works or calls_out, never raises anything
 * above signed_in, disarms no watch, clears no hold, opens no trial and writes no measured failure, so nothing in the
 * dataset step, the watch or the week report reads this state. Dump text is parsed in memory for the named keys and
 * never stored, and a crash counter holds a tag and a count, never crash text or a trace.
 */

/** Three states: an unread input is unknown, shows no warning, and is never fine. */
@Serializable
enum class QosState { FINE, AT_RISK, UNKNOWN }

/** What a real LTE call showed on this phone: its IMS data network reported QoS sessions, or none. */
@Serializable
enum class QosRadio { REPORTED, EMPTY }

/** The radio fact, per ROM build: a new build can change what the RIL reports. */
@Serializable
data class QosFact(val rom: String, val radio: QosRadio)

@Serializable
data class SimQos(val sim: SimKey, val home: QosState, val roaming: QosState)

/** Crashes of one package in the last [NoCallChecks.CRASH_WINDOW_MS]; null: not readable. */
@Serializable
data class CrashCount(val tag: String, val count: Int?)

/** When IMS last reported this SIM registered on a transport, by the registration callback. */
@Serializable
data class BearerSeen(val sim: SimKey, val bearer: String, val atMs: Long)

@Serializable
data class NoCallState(
    val radio: QosFact? = null,
    val qos: List<SimQos> = emptyList(),
    val crashes: List<CrashCount> = emptyList(),
    val signedIn: List<BearerSeen> = emptyList(),
)

object NoCallChecks {
    const val MOBILE = "mobile"
    const val WIFI = "wifi"
    const val IWLAN_TAG = "Iwlan"
    const val STACK_TAG = "ImsStack"
    val CRASH_PACKAGES = linkedMapOf(IWLAN_TAG to "com.google.android.iwlan", STACK_TAG to "com.android.imsstack")
    val CRASH_WINDOW_MS: Long = java.util.concurrent.TimeUnit.DAYS.toMillis(7)

    /** A QoS session comes with the dedicated bearer, so the read waits until the call has been up this long. */
    const val RADIO_READ_AFTER_MS = 8_000L

    fun seen(list: List<BearerSeen>, sim: SimKey, bearer: String, atMs: Long): List<BearerSeen> =
        list.filterNot { it.sim.sameCard(sim) && it.bearer == bearer } + BearerSeen(sim, bearer, atMs)

    /** A real LTE call should read the radio: nothing is known for this build yet. */
    fun radioWanted(fact: QosFact?, rom: String): Boolean = fact?.rom != rom

    fun radioOf(fact: QosFact?, rom: String): QosRadio? = fact?.takeIf { it.rom == rom }?.radio
}

/** The stack's own config layers for one slot, from `### Carrier Configs` in ImsService.dump. Only [QosCheck.KEYS] are kept. */
data class StackConfig(val layers: Map<String, Map<String, Boolean>>) {
    fun has(layer: String): Boolean = layer in layers
}

object StackDump {
    private const val START = "### Carrier Configs"
    private const val END = "### System Interface"
    const val DEFAULT_INTERNAL = "DefaultInternalConfig"
    const val DEFAULT_PUBLIC = "DefaultPublicConfig"
    const val CARRIER_INTERNAL = "CarrierInternalConfig"
    const val CARRIER_PUBLIC = "CarrierPublicConfig"
    const val INTERNAL_OVERRIDE = "CarrierInternalOverrideConfig"
    const val TEST_OVERRIDE = "TestOverrideConfig"
    private val LAYERS = setOf(DEFAULT_INTERNAL, DEFAULT_PUBLIC, CARRIER_INTERNAL, CARRIER_PUBLIC, INTERNAL_OVERRIDE, TEST_OVERRIDE)
    private val SLOT = Regex("Slot([0-9]+):")
    private val SHARED = setOf(DEFAULT_INTERNAL, DEFAULT_PUBLIC)

    /** [text] parsed for [slot]: null without the section, the slot or its layers. Nothing but true or false of a named key is read. */
    fun parse(text: String?, slot: Int): StackConfig? {
        val section = text?.substringAfter(START, "")?.substringBefore(END)?.takeIf { it.isNotBlank() } ?: return null
        val layers = HashMap<String, MutableMap<String, Boolean>>()
        var inSlot: Int? = null
        var layer: String? = null
        var slotSeen = false
        for (raw in section.lineSequence()) {
            val line = raw.trim()
            val slotLine = SLOT.matchEntire(line)
            when {
                slotLine != null -> {
                    inSlot = slotLine.groupValues[1].toInt()
                    if (inSlot == slot) slotSeen = true
                    layer = null
                }
                line.endsWith(":") && line.dropLast(1) in LAYERS -> {
                    val name = line.dropLast(1)
                    layer = name.takeIf { it in SHARED || inSlot == slot }
                    if (layer != null) layers.getOrPut(name) { HashMap() }
                }
                layer != null && '=' in line -> {
                    val key = line.substringBefore('=')
                    val value = line.substringAfter('=')
                    if (key in QosCheck.KEYS && (value == "true" || value == "false")) layers.getValue(layer)[key] = value == "true"
                }
            }
        }
        return if (slotSeen && DEFAULT_INTERNAL in layers && CARRIER_INTERNAL in layers) StackConfig(layers) else null
    }
}

/** The chain of MtcPreconditionManager, with the stack's resolved values and one radio fact. */
object QosCheck {
    const val VOICE_ON_DEFAULT = "imsvoice.voice_on_default_bearer_supported_bool"
    const val ROAMING_WITHOUT_QOS = "imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool"
    const val PRECONDITION = "imsvoice.voice_qos_precondition_supported_bool"
    const val WAIT_QOS = "imsvoice.wait_qos_when_local_precondition_not_supported_bool"
    const val OVERRIDE_PUBLIC = "ims.override_public_config_bool"
    val KEYS = setOf(VOICE_ON_DEFAULT, ROAMING_WITHOUT_QOS, PRECONDITION, WAIT_QOS, OVERRIDE_PUBLIC)

    /**
     * [key] as ConfigAgent.updateCarrierConfig resolves it, top layer first: the test override, the two public layers when
     * ims.override_public_config_bool (default true) allows them, the internal override, CarrierConfigManager ([ccm]), the
     * carrier's internal config, the default. A key that reaches [ccm] null is unknown, because that layer was not read.
     * The stack also merges a hidden CarrierConfigManager bundle that its dump does not show.
     */
    fun resolved(config: StackConfig, ccm: Map<String, Boolean>?, key: String): Boolean? {
        fun layer(name: String) = config.layers[name]?.get(key)
        val lower = layer(StackDump.INTERNAL_OVERRIDE) ?: (ccm?.get(key)) ?: if (ccm == null) null else layer(StackDump.CARRIER_INTERNAL) ?: layer(StackDump.DEFAULT_INTERNAL)
        // The stack reads this one key before the public layers and the test override go on.
        if (key == OVERRIDE_PUBLIC) return lower
        val publicOn = resolved(config, ccm, OVERRIDE_PUBLIC) ?: if (ccm == null) return null else true
        val top = layer(StackDump.TEST_OVERRIDE)
        val public = if (publicOn) layer(StackDump.CARRIER_PUBLIC) ?: layer(StackDump.DEFAULT_PUBLIC) else null
        return top ?: public ?: lower
    }

    /** Whether a call arms the 8-second bearer timer. Null: an input it needs was not read. Each step reads only what the code reaches. */
    fun arms(value: (String) -> Boolean?, roaming: Boolean): Boolean? {
        val onDefault = value(VOICE_ON_DEFAULT) ?: return null
        val allowed = onDefault && (!roaming || (value(ROAMING_WITHOUT_QOS) ?: return null))
        if (allowed) return false
        val precondition = value(PRECONDITION) ?: return null
        return precondition || (value(WAIT_QOS) ?: return null)
    }

    fun state(armed: Boolean?, radio: QosRadio?): QosState = when {
        armed == null -> QosState.UNKNOWN
        !armed || radio == QosRadio.REPORTED -> QosState.FINE
        radio == QosRadio.EMPTY -> QosState.AT_RISK
        else -> QosState.UNKNOWN
    }

    fun of(sim: SimKey, config: StackConfig?, ccm: Map<String, Boolean>?, radio: QosRadio?): SimQos {
        fun at(roaming: Boolean) = state(config?.let { c -> arms({ resolved(c, ccm, it) }, roaming) }, radio)
        return SimQos(sim, at(false), at(true))
    }
}

/** The IMS data network's QoS sessions in the phone dump, read during a real LTE call. */
object QosDump {
    private const val BLOCK = "DataNetwork:"
    private const val SESSIONS = "mQosBearerSessions="

    /** REPORTED when a cellular IMS network holds a session, EMPTY when each one holds none, null without one. The text is not kept. */
    fun radio(text: String?): QosRadio? {
        var transport: String? = null
        var ims = false
        var empty = false
        for (raw in text.orEmpty().lineSequence()) {
            val line = raw.trim()
            when {
                line == BLOCK -> {
                    transport = null
                    ims = false
                }
                line.startsWith("mTransport=") -> transport = line.substringAfter('=')
                line.startsWith("mNetworkCapabilities=") -> ims = Regex("Capabilities: ([A-Z_]+&)*IMS[& \\]]").containsMatchIn(line)
                line.startsWith(SESSIONS) && transport == "WWAN" && ims -> {
                    if (line.substringAfter('=') != "[]") return QosRadio.REPORTED
                    empty = true
                }
            }
        }
        return if (empty) QosRadio.EMPTY else null
    }
}

/** The lines on the entry. Unknown says nothing: it is no warning and no pass. */
object NoCallWords {
    const val SECTION = "Checks without a call"
    const val NOTE = "These read what the phone already knows. No call was tested, and they say nothing about whether calls work."
    private const val AT_RISK_HOME = "Calls on LTE may drop after about 8 seconds: this phone's settings wait for a voice bearer, and a real call showed the radio reports none"
    private const val AT_RISK_ROAMING = "While roaming, calls on LTE may drop after about 8 seconds: this phone's settings wait for a voice bearer, and a real call showed the radio reports none"
    private const val FINE = "The settings for calls on LTE don't wait for a voice bearer the radio can't report"

    fun lines(state: NoCallState, nameOf: (SimKey) -> String?, date: (Long) -> String): List<String> = buildList {
        for (s in state.qos) {
            val prefix = nameOf(s.sim)?.let { "$it: " }.orEmpty()
            if (s.home == QosState.AT_RISK) add(prefix + AT_RISK_HOME)
            if (s.roaming == QosState.AT_RISK) add(prefix + AT_RISK_ROAMING)
            if (s.home == QosState.FINE && s.roaming == QosState.FINE) add(prefix + FINE)
        }
        for (s in state.signedIn.sortedBy { it.bearer }) {
            add(nameOf(s.sim)?.let { "$it: " }.orEmpty() + "Last signed in over ${if (s.bearer == NoCallChecks.WIFI) "Wi-Fi" else "the mobile network"}: ${date(s.atMs)}")
        }
        val known = state.crashes.filter { it.count != null }
        if (known.isNotEmpty()) add("Crashes in the last 7 days: " + known.joinToString { "${it.tag} ${it.count}" })
    }
}
