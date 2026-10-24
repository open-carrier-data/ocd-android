package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ProvenBearer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * OCD's runtime layer, the owner app's side (OCD round 29, item 3 "oneComponent"; the
 * ruling's S1 to S15). The ROM's CarrierSettings (OcdCarrierSettingsLayer, vendor/extra
 * carriersettings/layer) serves a per-SIM layer from its own device-protected storage, after
 * the static carrier file and before LineageOS's fixed key and the device override. This app
 * is its only writer, through the provider `content://com.android.carriersettings.ocdlayer`
 * (S3), and writes an entry only after the entry's class check passed on this phone, SIM, build
 * and stack. Trials stay non-persistent overrides; only checked values enter the layer, so
 * they survive restarts without any override.
 *
 * CarrierSettings checks everything again on its own (allowlist, safety class, X2 to X6,
 * leases, scope, call and emergency refusals, the dead-man check, the crash guard), computes
 * the SIM scope (a salted ICCID hash) itself, and reloads only through its own
 * notifyConfigChangedForSubId (S4). This app never handles the ICCID or its hash: it names a
 * SIM by its subscription. Every apply and revert is proven here against the effective
 * `dumpsys carrier_config` (S4, S11).
 *
 * On a ROM without the layer the provider is absent: [LayerStatus.available] is false and
 * nothing here runs; the non-persistent keeps work as before.
 */

/** The provider's protocol, version 1 (CarrierSettings's OcdLayerProtocol, mirrored). */
object LayerProtocol {
    const val AUTHORITY = "com.android.carriersettings.ocdlayer"
    const val URI = "content://$AUTHORITY"
    const val PERMISSION = "com.android.carriersettings.permission.WRITE_OCD_LAYER"
    const val VERSION = 1

    const val STATUS = "status"
    const val LIST = "list"
    const val PUT = "put"
    const val REMOVE = "remove"
    const val RENEW = "renew"
    const val LIVENESS = "liveness"
    const val STOP = "stop"
    const val CLEAR = "clear"
    const val CLEAR_STOP = "clear_stop"
    const val CHECK = "check"

    const val OK = "ok"
    const val REASON = "reason"
    const val SUB_ID = "sub_id"
    const val ENTRIES = "entries"
    const val KEYS = "keys"
    const val PROFILE = "profile"
    const val DATA_COMMIT = "data_commit"
    const val DATA_DIGEST = "data_digest"
    const val DATA_DATE = "data_date"
    const val LEASE_UNTIL_MS = "lease_until_ms"
    const val LIVENESS_UNTIL_MS = "liveness_until_ms"
    const val SUFFIX = "suffix"
    const val RELOADED = "reloaded"
    const val PENDING = "pending"
    const val WAITING_FOR = "waiting_for"
    const val REMOVED = "removed"
    const val RENEWED = "renewed"
    const val REFUSED = "refused"
    const val PROTOCOL = "protocol"
    const val STOP_FLAG = "stop"
    const val FIRST_USE = "first_use"
    const val OWNER_OK = "owner_ok"
    const val OWNER_REASON = "owner_reason"
    const val FILE = "file"
    const val SEQ = "seq"
    const val ENTRY_COUNT = "entry_count"
    const val PENDING_SUBS = "pending_subs"
    const val LAST_CHECK = "last_check"
    const val BUILD = "build"
    const val SIM = "sim"
    const val OTHER_SIMS = "other_sims"
    const val SERVED_SUFFIX = "served_suffix"
}

/** The layer's state as CarrierSettings reports it. [available] false: not on this ROM (or not reachable); nothing else counts then. */
@Serializable
data class LayerStatus(
    val available: Boolean,
    val why: String = "",
    val stop: String? = null,
    val firstUse: Set<Int> = emptySet(),
    val ownerOk: Boolean = false,
    val ownerReason: String = "",
    val file: String = "",
    val seq: Long = -1,
    val livenessUntilMs: Long = 0,
    val dataCommit: String = "",
    val entryCount: Int = 0,
    val pendingSubs: Set<Int> = emptySet(),
    val waitingFor: String = "",
    val lastCheck: String = "",
) {
    /** S15: until first-use checks 1 to 4 passed, the layer holds no entry that differs from the baseline. */
    val firstUseDone: Boolean get() = firstUse.containsAll(LayerFirstUse.REQUIRED)

    companion object {
        fun absent(why: String) = LayerStatus(available = false, why = why)
    }
}

object LayerFirstUse {
    val REQUIRED = setOf(1, 2, 3, 4)

    /** The method of the first-use probe entry (S15 check 2): equal to the baseline, so it changes nothing. */
    const val PROBE = "first_use_probe"
}

/** One entry of one SIM as the provider lists it, with its baseline and state. */
@Serializable
data class LayerEntryView(
    val key: String,
    val type: String,
    val value: JsonElement,
    val cls: String,
    val origin: String,
    val level: String,
    val sinceMs: Long,
    val leaseUntilMs: Long,
    val voiceMeasured: Boolean = false,
    val smsMeasured: Boolean = false,
    val bearers: List<String> = emptyList(),
    val method: String = "",
    val ocdValue: JsonElement? = null,
    val build: String = "",
    val stack: String = "",
    val carriedOver: Boolean = false,
    val baseline: JsonElement? = null,
    val served: Boolean = false,
    val state: String = "",
)

/** One SIM's part of the layer as the provider lists it. */
@Serializable
data class LayerSimView(
    val entries: List<LayerEntryView> = emptyList(),
    val servedSuffix: String = "",
    val reason: String = "",
    val pending: Boolean = false,
    val otherSims: Int = 0,
    val file: String = "",
    val stop: String? = null,
)

/** A provider call's outcome. [pending]: written, and the reload waits for a call or emergency state to clear ([waitingFor]). */
data class LayerResult(
    val ok: Boolean,
    val reason: String? = null,
    val reloaded: Boolean = false,
    val pending: Boolean = false,
    val waitingFor: String? = null,
    val suffix: String? = null,
    val count: Int = 0,
    val refused: Map<String, String> = emptyMap(),
)

/** One entry as this app hands it to the provider; the provider stamps the build and IMS stack. */
data class LayerPut(
    val key: String,
    val type: String,
    val value: JsonElement,
    val cls: LayerClass,
    val origin: String,
    val level: LayerLevel,
    val sinceMs: Long,
    val leaseUntilMs: Long,
    val voiceMeasured: Boolean = false,
    val smsMeasured: Boolean = false,
    val bearers: List<String> = emptyList(),
    val method: String = "",
    val ocdValue: JsonElement? = null,
) {
    /** The provider's entry object (CarrierSettings's LayerFile.entryJson field for field). */
    fun json(): JsonObject = JsonObject(
        linkedMapOf(
            "key" to JsonPrimitive(key),
            "type" to JsonPrimitive(type),
            "value" to value,
            "class" to JsonPrimitive(cls.wire),
            "origin" to JsonPrimitive(origin),
            "level" to JsonPrimitive(level.wire),
            "since_ms" to JsonPrimitive(sinceMs),
            "lease_until_ms" to JsonPrimitive(leaseUntilMs),
            "voice_measured" to JsonPrimitive(voiceMeasured),
            "sms_measured" to JsonPrimitive(smsMeasured),
            "bearers" to JsonArray(bearers.map(::JsonPrimitive)),
            "method" to JsonPrimitive(method),
            "ocd_value" to (ocdValue ?: JsonNull),
            "build" to JsonPrimitive(""),
            "stack" to JsonPrimitive(""),
        ),
    )
}

/** The OCD data commit the values come from (the pin of round 29; S13's commit and data_digest). */
data class LayerData(val commit: String, val digest: String, val date: String) {
    companion object {
        fun of(pin: DataPin?): LayerData = pin?.let { LayerData(it.commit, it.dataDigest.takeIf { d -> d.startsWith("sha256:") }.orEmpty(), it.commitDate.take(10)) }
            ?: LayerData("", "", "")
    }
}

/** How the app reaches the layer: the provider in CarrierSettings ([LayerClient.Absent] on a ROM without it). */
interface LayerClient {
    fun status(): LayerStatus
    fun list(subId: Int): LayerSimView?
    fun put(subId: Int, profile: String, data: LayerData, entries: List<LayerPut>, livenessUntilMs: Long): LayerResult
    fun remove(subId: Int, keys: List<String>?, reason: String): LayerResult
    fun renew(subId: Int, keys: List<String>, untilMs: Long): LayerResult
    fun liveness(untilMs: Long): LayerResult
    fun stop(reason: String): LayerResult
    fun clearAll(reason: String): LayerResult
    fun clearStop(): LayerResult

    object Absent : LayerClient {
        private val no = LayerResult(false, "the runtime layer is not on this ROM build")
        override fun status() = LayerStatus.absent("not on this ROM build")
        override fun list(subId: Int): LayerSimView? = null
        override fun put(subId: Int, profile: String, data: LayerData, entries: List<LayerPut>, livenessUntilMs: Long) = no
        override fun remove(subId: Int, keys: List<String>?, reason: String) = no
        override fun renew(subId: Int, keys: List<String>, untilMs: Long) = no
        override fun liveness(untilMs: Long) = no
        override fun stop(reason: String) = no
        override fun clearAll(reason: String) = no
        override fun clearStop() = no
    }
}

/** The class an entry is checked under (round 29 item 3's class checks). */
@Serializable
enum class LayerClass(val wire: String, val plain: String) {
    VOICE_IMS("voice_ims", "calls"),
    SMS("sms", "SMS over IMS"),
    VIDEO("video", "video calling"),
    OTHER("other", "no regression");

    companion object {
        fun ofWire(wire: String): LayerClass? = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
enum class LayerLevel(val wire: String, val plain: String) {
    /** Kept for now: a voice value after calls out, which still needs incoming (round 26 rule 7). */
    PROVISIONAL("provisional", "kept for now"),
    DURABLE("durable", "kept");

    companion object {
        fun ofWire(wire: String): LayerLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The app's own guard for layer keys, before CarrierSettings's: never a safety-class key
 * (emergency, e911, ECBM, WPS, TTY, RTT, iwlan.*), never the two keys on the emergency path
 * CarrierSettings also refuses, never VoNR (a changed VoNR value has no bearer proof), and each
 * key's class.
 */
object LayerKeys {
    val SAFETY = Regex("emergency|e911|ecbm|(^|[._])ecm([._]|$)|wps|tty|rtt|iwlan\\.", RegexOption.IGNORE_CASE)
    val EMERGENCY_PATH = setOf("call_forwarding_blocks_while_roaming_string_array", "ims_reasoninfo_mapping_string_array")
    val NEVER = setOf(OwnerFeature.VONR.key)

    /** Keys of OCD's closed list that the IMS stack reads for calls (with the VoLTE and Wi-Fi calling switches and ims.* / imsvoice.* / imswfc.*). */
    private val IMS_KEYS = setOf(
        "carrier_volte_override_wfc_provisioning_bool", "carrier_volte_provisioned_bool", "carrier_volte_provisioning_required_bool",
        "carrier_wfc_supports_wifi_only_bool", "carrier_default_wfc_ims_enabled_bool", "carrier_default_wfc_ims_mode_int",
        "carrier_default_wfc_ims_roaming_enabled_bool", "carrier_default_wfc_ims_roaming_mode_int", "carrier_ims_gba_required_bool",
        "carrier_supports_ss_over_ut_bool", "carrier_allow_turnoff_ims_bool", "enhanced_4g_lte_on_by_default_bool",
        "editable_enhanced_4g_lte_bool", "hide_enhanced_4g_lte_bool", "editable_wfc_mode_bool", "editable_wfc_roaming_mode_bool",
        "carrier_promote_wfc_on_call_fail_bool", "volte_replacement_rat_int", "carrier_allow_transfer_ims_call_bool",
        "support_ims_conference_call_bool", "ims_conference_size_limit_int", "is_ims_conference_size_enforced_bool",
        "support_ims_conference_event_package_bool", "allow_hold_in_ims_call", "wifi_calls_can_be_hd_audio",
        "allow_merge_wifi_calls_when_vowifi_off_bool",
    )

    fun guard(key: String): String? = when {
        SAFETY.containsMatchIn(key) -> "safety-class key"
        key in EMERGENCY_PATH -> "on the emergency path"
        key in NEVER -> "a changed VoNR value is never kept (no bearer proof)"
        else -> null
    }

    /** The class of [key]; null for a key that never enters the layer. */
    fun classOf(key: String): LayerClass? = when {
        guard(key) != null -> null
        key == OwnerFeature.VIDEO_CALLING.key -> LayerClass.VIDEO
        key.startsWith("imssms.") -> LayerClass.SMS
        key == OwnerFeature.VOLTE.key || key == OwnerFeature.VOWIFI.key -> LayerClass.VOICE_IMS
        key.startsWith("ims.") || key.startsWith("imsvoice.") || key.startsWith("imswfc.") || key in IMS_KEYS -> LayerClass.VOICE_IMS
        else -> LayerClass.OTHER
    }

    /**
     * The bearers a voice and IMS entry affects: VoLTE's is LTE, Wi-Fi calling's is Wi-Fi; any
     * other IMS key affects the bearers voice runs on now. Empty for the other classes.
     */
    fun bearers(key: String, switches: Map<OwnerFeature, Boolean?>): Set<ProvenBearer> = when {
        classOf(key) != LayerClass.VOICE_IMS -> emptySet()
        key == OwnerFeature.VOLTE.key -> setOf(ProvenBearer.LTE)
        key == OwnerFeature.VOWIFI.key -> setOf(ProvenBearer.IWLAN)
        else -> buildSet {
            if (switches[OwnerFeature.VOLTE] == true) add(ProvenBearer.LTE)
            if (switches[OwnerFeature.VOWIFI] == true) add(ProvenBearer.IWLAN)
        }
    }
}

/** Typed values in the provider's JSON and in the carrier config dump. */
object LayerValues {
    fun bool(v: Boolean): JsonElement = JsonPrimitive(v)

    fun asBool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** How `dumpsys carrier_config` prints a value: true, 5, text, [a, b]. */
    fun dumpForm(v: JsonElement?): String? = when (v) {
        null, JsonNull -> null
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> v.joinToString(", ", "[", "]") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        else -> null
    }

    /** "on" / "off" for a switch, else the value as the dump prints it. */
    fun plain(v: JsonElement?): String = asBool(v)?.let { if (it) "on" else "off" } ?: dumpForm(v) ?: "none"
}

/** The class checks (round 29 item 3): when a value may enter the layer, and at which level. */
object LayerCheck {
    /**
     * Voice and IMS keys: provisional after calls out (L2) on each bearer they affect, durable
     * after works (L3) on each, measured by this phone on this SIM and ROM build since [sinceMs].
     */
    fun voice(calls: List<CallRecord>, sim: SimKey, bearers: Set<ProvenBearer>, build: String, sinceMs: Long, nowMs: Long): LayerLevel? {
        if (bearers.isEmpty()) return null
        val levels = bearers.map { CallLevels.level(calls, sim, CallLevels.featureOf(it), build, sinceMs, nowMs) }
        return when {
            levels.all { it == CallsLevel.WORKS } -> LayerLevel.DURABLE
            levels.all { it != null } -> LayerLevel.PROVISIONAL
            else -> null
        }
    }

    /** How the voice level was measured, per bearer: mailbox_call, real_call, or both legs. */
    fun voiceMethod(calls: List<CallRecord>, sim: SimKey, bearers: Set<ProvenBearer>, build: String, sinceMs: Long, nowMs: Long): String =
        bearers.sortedBy { it.wire }.mapNotNull { b ->
            CallLevels.callsOutEvidence(calls, sim, b, build, sinceMs, nowMs)?.let { out ->
                Retested.method(out, CallLevels.incomingEvidence(calls, sim, b, build, sinceMs, nowMs))
            }
        }.distinct().joinToString("+").take(40)

    /**
     * SMS over IMS on: provisional after an SMS sent over IMS, durable after one received over
     * IMS (the optional data SMS to the phone's own number). Off: kept only with an SMS
     * delivered while off (the plan's rule), and then durable with that measurement.
     */
    fun sms(checks: List<SelfSmsCheck>, sim: SimKey, value: Boolean, sinceMs: Long): LayerLevel? {
        val mine = checks.filter { it.sim.sameCard(sim) && it.sentAtMs >= sinceMs }
        if (!value) return LayerLevel.DURABLE.takeIf { mine.any { it.result == "received" } }
        if (mine.any { it.result == "received" && it.imsAtReceive == true }) return LayerLevel.DURABLE
        if (mine.any { it.imsAtSend == true && it.result in setOf("sent", "received", "not_received") }) return LayerLevel.PROVISIONAL
        return null
    }

    /** Video calling: IMS signed in with video while voice registration stays as it was. */
    fun video(videoWorks: Boolean?, voiceRegistered: Boolean): LayerLevel? = if (videoWorks == true && voiceRegistered) LayerLevel.DURABLE else null

    /**
     * Every other allowed key: no regression, meaning IMS registered with the same services on
     * the same bearer as before, and mobile data validated.
     */
    fun noRegression(before: ImsShape?, after: ImsShape?, dataValidated: Boolean?): Boolean =
        before != null && after != null && after.registered == before.registered && after.wifi == before.wifi &&
            after.services.containsAll(before.services) && dataValidated == true
}

/** IMS as the no-regression check compares it: registered, on Wi-Fi or not, and its services. */
@Serializable
data class ImsShape(val registered: Boolean?, val wifi: Boolean?, val services: Set<String>) {
    companion object {
        fun of(o: Observation): ImsShape = ImsShape(o.ims.registered, o.ims.transport?.isWifi, o.ims.services.map { it.wire }.toSet())
    }
}

/**
 * This app's own record of an entry it put into the layer: what it is, why, and since when
 * the value is in effect (the 7 days of a provisional voice value count from there). No ICCID,
 * no hash: the SIM is its subscription, network code and carrier ID.
 */
@Serializable
data class LayerRecord(
    val slot: Int,
    val sim: SimKey,
    val key: String,
    val value: JsonElement,
    val cls: LayerClass,
    val level: LayerLevel,
    val origin: String,
    /** When the value first took effect on this SIM (as a keep, before it entered the layer). */
    val appliedAtMs: Long,
    val putAtMs: Long,
    val bearers: List<ProvenBearer> = emptyList(),
    val method: String = "",
    val ocdValue: JsonElement? = null,
    val voiceMeasured: Boolean = false,
    val smsMeasured: Boolean = false,
    /** When the entry was first seen checked on another ROM build or IMS stack (S8); null when it is current. */
    val carriedAtMs: Long? = null,
    /** S15 check 2's probe: equal to the baseline, kept until Undo. */
    val probe: Boolean = false,
    /** A removal written whose reload waits (a call, an emergency state); the screen says it waits. */
    val removing: String? = null,
) {
    val feature: OwnerFeature? get() = OwnerFeature.ofKey(key)
    val boolValue: Boolean? get() = LayerValues.asBool(value)

    /** The plan's entry name, for the night's list and the undo memory ("volte:on"). */
    val entry: String? get() = feature?.let { f -> boolValue?.let { QuietPlan.entry(f, it) } }
}

/** What the planner asks the writer to do. */
sealed interface LayerAction {
    val slot: Int
    val sim: SimKey

    data class Put(override val slot: Int, override val sim: SimKey, val subId: Int, val profile: String, val entries: List<LayerPut>, val why: String, val records: List<LayerRecord>, val dropKeeps: List<OwnerFeature> = emptyList()) : LayerAction
    data class Remove(override val slot: Int, override val sim: SimKey, val subId: Int, val keys: List<String>, val why: String, val failure: Boolean = false) : LayerAction
    data class Renew(override val slot: Int, override val sim: SimKey, val subId: Int, val keys: List<String>, val untilMs: Long) : LayerAction
}

/** One SIM as the planner sees it, all read in one pass. */
data class LayerSimInput(
    val sim: OwnerSim,
    /** The provider's list for the SIM; null when it could not be read (then nothing is decided for it). */
    val view: LayerSimView?,
    /** The SIM's own carrier config below the override layers, as the dump prints it (the baseline of a keep). */
    val belowOverrides: Map<String, String>,
    val switches: Map<OwnerFeature, Boolean?>,
    /** The light check's observed entries ("volte:on" to whether it works now). */
    val observed: Map<String, Boolean>,
    /** IMS registered for voice and mobile at home. */
    val healthy: Boolean,
    /** No voice now: IMS not registered for voice, and no circuit-switched service. Null: unreadable. */
    val voiceLost: Boolean?,
    /** The refusal of this slot now (call, emergency, roaming, just booted, unreadable); null: none. */
    val refusal: Refusal?,
    val answers: Map<OwnerFeature, OcdAnswer>,
    val profileId: String?,
)

data class LayerInputs(
    val sims: List<LayerSimInput>,
    val state: OwnerState,
    val status: LayerStatus,
    /** The ROM build ([TestBasis.rom]): call levels count per build. */
    val build: String,
    val nowMs: Long,
    /** The silent window is open: an apply (a reload, IMS signs in again) may run now. */
    val quietOpen: Boolean,
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class LayerPlan(
    val actions: List<LayerAction> = emptyList(),
    /** Renew the app's liveness lease until then (each light check, S5 and the plan's dead-man). */
    val livenessUntilMs: Long? = null,
    /** A stop event of this app's: the whole layer goes and CarrierSettings's stop flag is set (S9). */
    val stop: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * The layer's planner (pure): from the light check, the keeps, the call and SMS ledgers and the
 * provider's list, what goes in, what is renewed, what is reverted. Rules:
 * - in: a keep whose class check passed (voice: calls out on its bearer, then works; SMS:
 *   sent over IMS, then received; video: signed in with video, voice unchanged), at home,
 *   outside any refusal, in the silent window, with no stop flag, and only an entry that differs
 *   from the build's value unless first-use checks 1 to 4 are recorded (S15);
 * - out, at once and also while roaming (S7): a measured failure (the light check sees the
 *   feature not working, a provisional voice value's incoming failure or 7 days without
 *   incoming), voice lost under a voice off (X5), a carried-over entry not checked again in
 *   time (S8), an entry the build now carries, an entry whose lease ran out;
 * - renewed: only at home, by a light check that saw the feature working (a voice value also by
 *   a passing call on its bearer within 7 days), never a provisional voice value, never a
 *   carried-over one (S5, S7, S8).
 */
object LayerPlanner {
    val LEASE_MS: Long = TimeUnit.DAYS.toMillis(7)

    /** A lease is renewed once less than this is left, so a light check every few hours writes at most once a day. */
    val RENEW_BELOW_MS: Long = TimeUnit.DAYS.toMillis(6)
    val CALL_EVIDENCE_MS: Long = TimeUnit.DAYS.toMillis(7)

    fun plan(input: LayerInputs): LayerPlan {
        val status = input.status
        if (!status.available) return LayerPlan(notes = listOf("no runtime layer: ${status.why}"))
        val state = input.state
        val now = input.nowMs
        // S9: a stop event of this app's takes the whole layer off and sets CarrierSettings's flag.
        if (state.stop != null && status.stop == null && (status.entryCount > 0 || state.layer.isNotEmpty())) {
            return LayerPlan(stop = state.stop)
        }
        val actions = mutableListOf<LayerAction>()
        val notes = mutableListOf<String>()
        for (s in input.sims) {
            val view = s.view ?: continue
            val sim = s.sim
            val mine = state.layer.filter { it.sim.sameCard(sim.key) }
            val home = s.refusal == null
            val removals = linkedMapOf<String, String>()
            var failure = false
            val renewals = mutableListOf<String>()
            val puts = mutableListOf<Pair<LayerPut, LayerRecord>>()
            for (e in view.entries) {
                val record = mine.firstOrNull { it.key == e.key }
                val entry = OwnerFeature.ofKey(e.key)?.let { f -> LayerValues.asBool(e.value)?.let { QuietPlan.entry(f, it) } }
                val works = entry?.let { s.observed[it] }
                val probe = record?.probe == true || e.method == LayerFirstUse.PROBE
                when {
                    e.state == "lease ran out" -> removals[e.key] = "lease ran out"
                    !probe && e.baseline != null && LayerValues.dumpForm(e.baseline) == LayerValues.dumpForm(e.value) -> removals[e.key] = "the build serves this value already"
                    probe -> Unit
                    // X5: voice lost while a voice off is served reverts at once, at home or roaming.
                    LayerValues.asBool(e.value) == false && e.key in setOf(OwnerFeature.VOLTE.key, OwnerFeature.VOWIFI.key) && s.voiceLost == true -> {
                        removals[e.key] = "voice lost with the voice off in effect"
                        failure = true
                    }
                    works == false -> {
                        removals[e.key] = "the light check saw it not working"
                        failure = true
                    }
                    else -> {
                        val decision = decide(e, record, s, input)
                        when (decision) {
                            is Decision.Revert -> {
                                removals[e.key] = decision.reason
                                failure = failure || decision.failure
                            }
                            is Decision.Promote -> if (home && input.quietOpen && state.stop == null) puts += decision.put to decision.record
                            Decision.Renew -> if (home && e.leaseUntilMs - now < RENEW_BELOW_MS) renewals += e.key
                            Decision.Hold -> Unit
                        }
                    }
                }
            }
            if (removals.isNotEmpty()) {
                actions += LayerAction.Remove(sim.slot, sim.key, sim.subId, removals.keys.toList(), removals.entries.joinToString("; ") { "${it.key}: ${it.value}" }, failure)
            }
            if (renewals.isNotEmpty()) actions += LayerAction.Renew(sim.slot, sim.key, sim.subId, renewals, now + LEASE_MS)
            // Keeps whose class check passed, into the layer.
            val keepPuts = if (home && input.quietOpen && state.stop == null) candidates(s, input, view) else emptyList()
            val allPuts = (puts + keepPuts.map { it.first to it.second }).filterNot { it.first.key in removals }
            if (allPuts.isNotEmpty()) {
                actions += LayerAction.Put(
                    sim.slot, sim.key, sim.subId, s.profileId.orEmpty(), allPuts.map { it.first },
                    allPuts.joinToString { "${it.first.key}=${LayerValues.plain(it.first.value)} (${it.first.level.plain})" },
                    allPuts.map { it.second },
                    dropKeeps = keepPuts.mapNotNull { (put, _) -> OwnerFeature.ofKey(put.key) },
                )
            }
            if (!home && (keepPuts.isNotEmpty() || puts.isNotEmpty())) notes += "slot ${sim.slot + 1}: nothing goes in now (${s.refusal?.plain})"
        }
        // The liveness lease: renewed by each light check, also while roaming (it is not an entry's renewal).
        val liveness = if (status.livenessUntilMs - now < RENEW_BELOW_MS && (status.entryCount > 0 || actions.any { it is LayerAction.Put })) now + LEASE_MS else null
        return LayerPlan(actions, liveness, null, notes)
    }

    private sealed interface Decision {
        data object Hold : Decision
        data object Renew : Decision
        data class Revert(val reason: String, val failure: Boolean) : Decision
        data class Promote(val put: LayerPut, val record: LayerRecord) : Decision
    }

    /** One served entry's next step from its class: hold, renew, revert, or put again (promoted, demoted, checked again after a carry-over). */
    private fun decide(e: LayerEntryView, record: LayerRecord?, s: LayerSimInput, input: LayerInputs): Decision {
        val now = input.nowMs
        val state = input.state
        val cls = LayerClass.ofWire(e.cls) ?: return Decision.Revert("unknown class", false)
        val level = LayerLevel.ofWire(e.level) ?: return Decision.Revert("unknown level", false)
        val applied = record?.appliedAtMs ?: e.sinceMs
        val bearers = e.bearers.mapNotNull { b -> ProvenBearer.entries.firstOrNull { it.wire == b } }.toSet()
            .ifEmpty { LayerKeys.bearers(e.key, s.switches) }
        // S8: checked on another ROM build or IMS stack: provisional, calls out within the first eligible night, works within 7 days.
        if (e.carriedOver) {
            val since = record?.carriedAtMs ?: now
            if (now - since >= LEASE_MS) return Decision.Revert("not checked again within 7 days of a new build or IMS stack", false)
            val again = when (cls) {
                LayerClass.VOICE_IMS -> LayerCheck.voice(state.calls, s.sim.key, bearers, input.build, since, now)
                LayerClass.SMS -> LayerValues.asBool(e.value)?.let { LayerCheck.sms(state.selfSms, s.sim.key, it, since) }
                LayerClass.VIDEO -> LayerCheck.video(s.observed[QuietPlan.entry(OwnerFeature.VIDEO_CALLING, true)], s.healthy)
                LayerClass.OTHER -> null
            }
            if (again != null) {
                val putLevel = again
                val lease = if (putLevel == LayerLevel.PROVISIONAL) since + LEASE_MS else now + LEASE_MS
                return Decision.Promote(
                    put(e, putLevel, minOf(lease, now + LEASE_MS), method = methodFor(cls, state, s, bearers, input, since)),
                    recordFor(e, record, s, putLevel, applied, bearers, now).copy(carriedAtMs = null),
                )
            }
            if (cls == LayerClass.VOICE_IMS && firstNightOver(state, s.sim.key, bearers, since, input)) {
                return Decision.Revert("no calls out on the first eligible night after a new build or IMS stack", false)
            }
            return Decision.Hold
        }
        if (cls == LayerClass.VOICE_IMS && LayerValues.asBool(e.value) == true) {
            if (level == LayerLevel.PROVISIONAL) {
                // Round 26 rule 7 on each bearer: a measured incoming failure reverts, works promotes, 7 days without incoming reverts.
                val decisions = bearers.map { b ->
                    val feature = CallLevels.featureOf(b)
                    Provisional.decide(
                        ProvisionalKeep(s.sim.slot, s.sim.key, feature, true, b, applied, Provisional.nightEnd(applied, input.zone), callsOutAtMs = applied),
                        state.calls, input.build, now, input.zone,
                    )
                }
                decisions.filterIsInstance<ProvisionalDecision.Revert>().firstOrNull()?.let { return Decision.Revert(it.reason, true) }
                if (decisions.isNotEmpty() && decisions.all { it is ProvisionalDecision.Promote }) {
                    return Decision.Promote(
                        put(e, LayerLevel.DURABLE, now + LEASE_MS, method = methodFor(cls, state, s, bearers, input, applied)),
                        recordFor(e, record, s, LayerLevel.DURABLE, applied, bearers, now),
                    )
                }
                return Decision.Hold
            }
            // Durable: falls back to provisional when its "works" is older than 90 days (round 26 rule 9).
            val nowLevel = LayerCheck.voice(state.calls, s.sim.key, bearers, input.build, 0, now)
            if (nowLevel == null) return Decision.Revert("no calls measured on its bearer within 90 days", false)
            if (nowLevel == LayerLevel.PROVISIONAL) {
                return Decision.Promote(put(e, LayerLevel.PROVISIONAL, now + LEASE_MS, method = e.method), recordFor(e, record, s, LayerLevel.PROVISIONAL, now, bearers, now))
            }
        }
        return if (renewable(e, cls, s, bearers, input)) Decision.Renew else Decision.Hold
    }

    /** S5: a light check at home saw the entry's feature working (a voice value also by a passing call on its bearer within 7 days). */
    private fun renewable(e: LayerEntryView, cls: LayerClass, s: LayerSimInput, bearers: Set<ProvenBearer>, input: LayerInputs): Boolean {
        val feature = OwnerFeature.ofKey(e.key)
        val value = LayerValues.asBool(e.value)
        val seen = if (feature != null && value != null) s.observed[QuietPlan.entry(feature, value)] == true else false
        return when (cls) {
            LayerClass.VOICE_IMS -> seen || (bearers.isNotEmpty() && bearers.all { b ->
                CallLevels.callsOutEvidence(input.state.calls, s.sim.key, b, input.build, input.nowMs - CALL_EVIDENCE_MS, input.nowMs) != null
            }) || (value == false && s.voiceLost == false)
            LayerClass.SMS, LayerClass.VIDEO -> seen
            LayerClass.OTHER -> s.healthy
        }
    }

    /** The first night after [since] on which a calls-out attempt ran for one of [bearers] is over. */
    private fun firstNightOver(state: OwnerState, sim: SimKey, bearers: Set<ProvenBearer>, since: Long, input: LayerInputs): Boolean {
        val sinceNight = QuietWindow.nightOf(Instant.ofEpochMilli(since).atZone(input.zone))
        val tonight = QuietWindow.nightOf(Instant.ofEpochMilli(input.nowMs).atZone(input.zone))
        return state.callsOutNights.any { it.sim.sameCard(sim) && it.bearer in bearers && it.night > sinceNight && it.night < tonight }
    }

    private fun methodFor(cls: LayerClass, state: OwnerState, s: LayerSimInput, bearers: Set<ProvenBearer>, input: LayerInputs, since: Long): String = when (cls) {
        LayerClass.VOICE_IMS -> LayerCheck.voiceMethod(state.calls, s.sim.key, bearers, input.build, since, input.nowMs)
        LayerClass.SMS -> "self_sms"
        LayerClass.VIDEO, LayerClass.OTHER -> "observed"
    }

    private fun put(e: LayerEntryView, level: LayerLevel, lease: Long, method: String): LayerPut = LayerPut(
        key = e.key, type = e.type, value = e.value, cls = LayerClass.ofWire(e.cls) ?: LayerClass.OTHER, origin = e.origin, level = level,
        sinceMs = e.sinceMs, leaseUntilMs = lease, voiceMeasured = e.voiceMeasured, smsMeasured = e.smsMeasured, bearers = e.bearers,
        method = method, ocdValue = e.ocdValue,
    )

    private fun recordFor(e: LayerEntryView, record: LayerRecord?, s: LayerSimInput, level: LayerLevel, applied: Long, bearers: Set<ProvenBearer>, now: Long): LayerRecord =
        (record ?: LayerRecord(s.sim.slot, s.sim.key, e.key, e.value, LayerClass.ofWire(e.cls) ?: LayerClass.OTHER, level, e.origin, applied, now))
            .copy(level = level, putAtMs = now, bearers = bearers.toList(), method = e.method)

    /**
     * The keeps of [s] that passed their class check now: each a put with its record. A keep
     * equal to the build's value needs no entry; VoNR never enters; nothing that differs from
     * the build's value goes in before first-use checks 1 to 4 (S15).
     */
    private fun candidates(s: LayerSimInput, input: LayerInputs, view: LayerSimView): List<Pair<LayerPut, LayerRecord>> {
        val state = input.state
        val now = input.nowMs
        val out = mutableListOf<Pair<LayerPut, LayerRecord>>()
        for (keep in state.overrides.filter { it.hold == Hold.KEPT && it.slot == s.sim.slot && it.sim.sameCard(s.sim.key) }) {
            val feature = OwnerFeature.ofKey(keep.key) ?: continue
            val cls = LayerKeys.classOf(keep.key) ?: continue
            if (view.entries.any { it.key == keep.key }) continue
            val baseline = s.belowOverrides[keep.key].asBool()
            if (baseline == keep.value) continue
            if (!input.status.firstUseDone) continue
            if (state.undone.any { it.sim.sameCard(s.sim.key) && it.entry == QuietPlan.entry(feature, keep.value) }) continue
            val bearers = LayerKeys.bearers(keep.key, s.switches)
            val provisional = state.provisional.firstOrNull { it.sim.sameCard(s.sim.key) && it.feature == feature && it.value == keep.value }
            val applied = provisional?.appliedAtMs ?: keep.sinceMs
            var voiceMeasured = false
            var smsMeasured = false
            val level: LayerLevel? = when (cls) {
                LayerClass.VOICE_IMS -> if (keep.value) {
                    // A changed voice value enters after calls out on its bearer (its provisional keep has it), durable after works.
                    val measured = LayerCheck.voice(state.calls, s.sim.key, bearers, input.build, applied, now)
                    measured ?: LayerLevel.PROVISIONAL.takeIf { provisional?.callsOutAtMs != null }
                } else {
                    // X5: a voice off only with a call that went ACTIVE while it was off.
                    voiceMeasured = state.runs.asReversed().flatMap { it.features }.firstOrNull { it.feature == feature && it.applied && it.best == false }?.offVoiceMeasured == true
                    LayerLevel.PROVISIONAL.takeIf { voiceMeasured }
                }
                LayerClass.SMS -> LayerCheck.sms(state.selfSms, s.sim.key, keep.value, applied).also { smsMeasured = !keep.value && it != null }
                LayerClass.VIDEO -> LayerCheck.video(s.observed[QuietPlan.entry(feature, keep.value)], s.healthy)
                LayerClass.OTHER -> null
            }
            level ?: continue
            val answer = s.answers[feature]
            val lease = if (cls == LayerClass.VOICE_IMS && level == LayerLevel.PROVISIONAL && keep.value) minOf(applied + LEASE_MS, now + LEASE_MS) else now + LEASE_MS
            if (lease <= now) continue
            val method = when (cls) {
                LayerClass.VOICE_IMS -> if (keep.value) LayerCheck.voiceMethod(state.calls, s.sim.key, bearers, input.build, applied, now) else "real_call"
                LayerClass.SMS -> "self_sms"
                else -> "observed"
            }
            val put = LayerPut(
                key = keep.key, type = "bool", value = LayerValues.bool(keep.value), cls = cls,
                origin = if (answer?.value == keep.value) "ocd" else "correction", level = level, sinceMs = applied, leaseUntilMs = lease,
                voiceMeasured = voiceMeasured, smsMeasured = smsMeasured, bearers = bearers.map { it.wire }.sorted(), method = method,
                ocdValue = answer?.value?.let(LayerValues::bool),
            )
            out += put to LayerRecord(
                s.sim.slot, s.sim.key, keep.key, put.value, cls, level, put.origin, applied, now, bearers.toList(), method, put.ocdValue,
                voiceMeasured = voiceMeasured, smsMeasured = smsMeasured,
            )
        }
        return out
    }

    /** The first-use probe (S15 check 2): SMS over IMS at the value the build serves, which changes nothing. Null when the SIM's value is unreadable. */
    fun probe(sim: OwnerSim, belowOverrides: Map<String, String>, answers: Map<OwnerFeature, OcdAnswer>, profileId: String?, nowMs: Long): LayerAction.Put? {
        val key = OwnerFeature.SMS_OVER_IMS.key
        val value = belowOverrides[key].asBool() ?: return null
        val put = LayerPut(
            key, "bool", LayerValues.bool(value), LayerClass.SMS, if (answers[OwnerFeature.SMS_OVER_IMS]?.value == value) "ocd" else "correction",
            LayerLevel.DURABLE, nowMs, nowMs + LEASE_MS, method = LayerFirstUse.PROBE, ocdValue = answers[OwnerFeature.SMS_OVER_IMS]?.value?.let(LayerValues::bool),
        )
        val record = LayerRecord(sim.slot, sim.key, key, put.value, LayerClass.SMS, LayerLevel.DURABLE, put.origin, nowMs, nowMs, method = LayerFirstUse.PROBE, ocdValue = put.ocdValue, probe = true)
        return LayerAction.Put(sim.slot, sim.key, sim.subId, profileId.orEmpty(), listOf(put), "first-use probe: SMS over IMS at the build's own value", listOf(record))
    }

    /**
     * The app's records against the provider's lists: a record whose entry is gone from the
     * layer (a lease or the liveness lease ran out, adb cleared it, a stop) is forgotten, and an
     * entry listed as checked on another build or stack is marked carried over (S8) the first
     * time. Only SIMs whose list was read are touched.
     */
    fun reconcile(records: List<LayerRecord>, views: Map<SimKey, LayerSimView>, nowMs: Long): List<LayerRecord> =
        markCarried(records.filter { r -> views.entries.firstOrNull { it.key.sameCard(r.sim) }?.value?.entries?.any { it.key == r.key } ?: true }, views, nowMs)

    /** Records carried over (S8): the first time an entry is listed as checked on another build or stack. */
    fun markCarried(records: List<LayerRecord>, views: Map<SimKey, LayerSimView>, nowMs: Long): List<LayerRecord> = records.map { r ->
        val e = views.entries.firstOrNull { it.key.sameCard(r.sim) }?.value?.entries?.firstOrNull { it.key == r.key }
        when {
            e == null -> r
            e.carriedOver && r.carriedAtMs == null -> r.copy(carriedAtMs = nowMs)
            !e.carriedOver && r.carriedAtMs != null -> r.copy(carriedAtMs = null)
            else -> r
        }
    }
}

/** The dump proofs of S4: an apply shows in CarrierSettings's own bundle with its suffix, a revert brings the baseline back. */
object LayerProof {
    private val SUFFIX = Regex("\\+ocd([0-9]+)(?:-([0-9a-f]{7}))?$")

    /** The layer suffix of CarrierSettings's bundle for [slot] ("+ocd2-1a2b3c4"), or null. */
    fun suffix(dump: CcDump, slot: Int): String? {
        val version = dump.phones[slot]?.get(CcLayer.DEFAULT_APP)?.get("carrier_config_version_string") ?: return null
        return SUFFIX.find(version.substringBefore('\n').trim())?.value
    }

    /** Every put value is in CarrierSettings's own bundle for the slot, and the suffix is the one the provider named. */
    fun applied(dump: CcDump, slot: Int, entries: List<LayerPut>, suffix: String?): Boolean {
        val bundle = dump.phones[slot]?.get(CcLayer.DEFAULT_APP) ?: return false
        return entries.all { bundle[it.key]?.trim() == LayerValues.dumpForm(it.value) } && (suffix == null || suffix(dump, slot) == suffix)
    }

    /** Every removed key is back at its baseline (absent when the baseline has none) in CarrierSettings's own bundle. */
    fun removed(dump: CcDump, slot: Int, keys: List<String>, baselines: Map<String, JsonElement?>): Boolean {
        val bundle = dump.phones[slot]?.get(CcLayer.DEFAULT_APP) ?: return false
        return keys.all { key ->
            val base = LayerValues.dumpForm(baselines[key])
            val now = bundle[key]?.trim()
            if (base == null) now == null || now == dump.defaults[key]?.trim() else now == base
        }
    }
}

/**
 * Carries out a [LayerPlan] through the provider and proves each apply and revert against the
 * dump (S4). A revert that is written but cannot be proven is a stop event (round 23); an apply
 * that does not show is reverted. A reload that waits (CarrierSettings refused it for a call
 * or an emergency state) is recorded, and the next light check proves it.
 */
class LayerWriter(
    private val phone: OwnerPhone,
    private val store: StateStore,
    private val layer: LayerClient,
    private val model: String = "",
) {
    private fun log(kind: String, text: String) = store.update { it.logged(phone.nowMs(), kind, text) }

    suspend fun run(plan: LayerPlan, sims: List<OwnerSim>, data: LayerData): List<String> {
        val notes = plan.notes.toMutableList()
        plan.stop?.let { reason ->
            val r = layer.stop(reason)
            log("layer", "stop event: the runtime layer was cleared and its stop flag set (${if (r.ok) if (r.pending) "the reload waits: ${r.waitingFor}" else "reloaded" else r.reason})")
            if (r.ok) store.update { it.copy(layer = emptyList()) }
            notes += "layer stopped"
            return notes
        }
        plan.livenessUntilMs?.let { until ->
            val r = layer.liveness(until)
            if (!r.ok) log("layer", "liveness lease not renewed: ${r.reason}")
        }
        for (action in plan.actions) {
            val sim = sims.firstOrNull { it.slot == action.slot && it.key.sameCard(action.sim) } ?: continue
            notes += when (action) {
                is LayerAction.Remove -> remove(sim, action)
                is LayerAction.Put -> put(sim, action, data)
                is LayerAction.Renew -> renew(action)
            }
        }
        return notes
    }

    private suspend fun remove(sim: OwnerSim, a: LayerAction.Remove): String {
        val baselines = layer.list(a.subId)?.entries?.associate { it.key to it.baseline }.orEmpty()
        val r = layer.remove(a.subId, a.keys, a.why)
        if (!r.ok) {
            log("layer", "slot ${sim.slot + 1}: removal of ${a.keys.joinToString()} refused: ${r.reason}")
            return "removal refused"
        }
        val failure = if (a.failure) " (a failure)" else ""
        if (r.pending || !r.reloaded) {
            store.update { s -> s.copy(layer = s.layer.map { if (it.sim.sameCard(a.sim) && it.key in a.keys) it.copy(removing = r.waitingFor ?: "the reload waits") else it }) }
            log("layer", "slot ${sim.slot + 1}: ${a.keys.joinToString()} removed from the layer$failure; the reload waits (${r.waitingFor ?: "not sent"}): ${a.why}")
            return "removal waits"
        }
        val proven = waitFor { LayerProof.removed(it, sim.slot, a.keys, baselines) }
        if (!proven) {
            // A revert that cannot be proven is a stop event (round 23); the whole layer goes with it (S9).
            OwnerSession(phone, store, sim, model).stop("layer_revert_not_proven on slot ${sim.slot}: ${a.keys.joinToString()}")
            layer.stop("the OCD app could not prove a revert in the carrier config")
            store.update { it.copy(layer = emptyList()) }
            return "STOP: removal not proven"
        }
        store.update { s -> s.copy(layer = s.layer.filterNot { it.sim.sameCard(a.sim) && it.key in a.keys }) }
        log("layer", "slot ${sim.slot + 1}: ${a.keys.joinToString()} back at the build's value, proven in the carrier config$failure: ${a.why}")
        return "removed ${a.keys.size}"
    }

    private suspend fun put(sim: OwnerSim, a: LayerAction.Put, data: LayerData): String {
        // The guards again, at the moment of the change: stop flag, calls, emergencies, roaming, a fresh boot (fail closed).
        val session = OwnerSession(phone, store, sim, model)
        try {
            session.guard(callWaitMs = 0)
        } catch (e: SafetyException) {
            log("layer", "slot ${sim.slot + 1}: nothing goes into the layer now (${e.reason})")
            return "put refused: ${e.reason}"
        }
        for (p in a.entries) LayerKeys.guard(p.key)?.let { why ->
            log("layer", "slot ${sim.slot + 1}: ${p.key} never enters the layer ($why)")
            return "put refused: ${p.key}"
        }
        val r = layer.put(a.subId, a.profile, data, a.entries, phone.nowMs() + LayerPlanner.LEASE_MS)
        if (!r.ok) {
            log("layer", "slot ${sim.slot + 1}: ${a.why} not put: ${r.reason}")
            return "put refused: ${r.reason}"
        }
        store.update { s -> s.copy(layer = s.layer.filterNot { old -> old.sim.sameCard(a.sim) && a.records.any { it.key == old.key } } + a.records) }
        if (r.pending || !r.reloaded) {
            log("layer", "slot ${sim.slot + 1}: ${a.why} written; CarrierSettings's reload waits (${r.waitingFor ?: "not sent"}); proven at the next check")
            return "put waits"
        }
        val proven = waitFor { LayerProof.applied(it, sim.slot, a.entries, r.suffix) }
        if (!proven) {
            log("layer", "slot ${sim.slot + 1}: ${a.why} did not show in the carrier config; reverted")
            val back = layer.remove(a.subId, a.entries.map { it.key }, "the apply did not show in the carrier config")
            if (back.ok) store.update { s -> s.copy(layer = s.layer.filterNot { old -> old.sim.sameCard(a.sim) && a.records.any { it.key == old.key } }) }
            return "put not proven"
        }
        log("layer", "slot ${sim.slot + 1}: kept after restart: ${a.why}; proven in the carrier config (${r.suffix})")
        // The layer serves these now; the non-persistent keeps go, and the revert proof shows the value now comes from below the overrides.
        for (feature in a.dropKeeps) {
            if (!session.dropKeep(feature, "now kept after restart")) log("layer", "slot ${sim.slot + 1}: the override of ${feature.wire} could not be dropped with proof")
        }
        if (a.dropKeeps.isNotEmpty()) store.update { it.provisionalSettled() }
        return "put ${a.entries.size}"
    }

    private fun renew(a: LayerAction.Renew): String {
        val r = layer.renew(a.subId, a.keys, a.untilMs)
        if (!r.ok) log("layer", "renewal refused: ${r.reason}")
        if (r.refused.isNotEmpty()) log("layer", "renewal refused for ${r.refused.entries.joinToString { "${it.key} (${it.value})" }}")
        return "renewed ${r.count}"
    }

    /** Polls the dump for up to [PROOF_MS] until [ok] holds. */
    private suspend fun waitFor(ok: (CcDump) -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            val dump = phone.carrierConfigText()?.takeIf { "Phone Id = " in it }?.let(CcDump::parse)
            if (dump != null && ok(dump)) return true
            if (waited >= PROOF_MS) return false
            phone.sleep(POLL_MS)
            waited += POLL_MS
        }
    }

    /**
     * Undo (S11, S12): one entry, or every entry of every SIM. Works while roaming; the reload
     * still waits for a call or an emergency state to clear. Remembered until "Test now".
     */
    suspend fun undo(sims: List<OwnerSim>, slot: Int?, key: String?): String {
        val now = phone.nowMs()
        val records = store.read().layer.filter { slot == null || (it.slot == slot && (key == null || it.key == key)) }
        store.update { s ->
            val undone = records.mapNotNull { r -> r.entry?.let { UndoneEntry(r.sim, it, now) } }
            s.copy(undone = (s.undone.filterNot { u -> undone.any { it.sim == u.sim && it.entry == u.entry } } + undone).takeLast(OwnerRunner.MAX_UNDONE))
        }
        if (slot == null) {
            val r = layer.clearAll("Undo all in the OCD app")
            if (!r.ok) return "Undo all refused: ${r.reason}".also { log("layer", it) }
            store.update { s -> s.copy(layer = if (r.pending) s.layer.map { it.copy(removing = r.waitingFor ?: "the reload waits") } else emptyList()) }
            log("layer", "Undo all: ${r.count} entr${if (r.count == 1) "y" else "ies"} removed from the layer${if (r.pending) "; the reload waits (${r.waitingFor})" else ""}")
            if (!r.pending) {
                // Each SIM in the phone: no layer suffix left in CarrierSettings's bundle.
                val proven = waitFor { dump -> sims.all { LayerProof.suffix(dump, it.slot) == null } }
                if (!proven) log("layer", "Undo all: the carrier config still shows a layer suffix; CarrierSettings's own check reloads it")
            }
            return "Undo all done"
        }
        val sim = sims.firstOrNull { it.slot == slot } ?: return "no SIM in slot ${slot + 1}"
        val keys = records.map { it.key }.ifEmpty { listOfNotNull(key) }
        return remove(sim, LayerAction.Remove(slot, sim.key, sim.subId, keys, "Undo in the OCD app"))
    }

    companion object {
        const val PROOF_MS = 30_000L
        const val POLL_MS = 2_000L

        /** One entry's lines for the owner screen (S11): the key in plain words, values, class, level, method, bearer, build, dates, state. */
        fun describe(e: LayerEntryView, record: LayerRecord?, zone: ZoneId): LayerLine {
            val feature = OwnerFeature.ofKey(e.key)
            val name = feature?.label ?: e.key
            val day = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString() }
            val origin = if (e.origin == "ocd") "Open Carrier Data's value" else "this phone's correction"
            val cls = LayerClass.ofWire(e.cls)?.plain ?: e.cls
            val level = LayerLevel.ofWire(e.level)?.plain ?: e.level
            val details = listOfNotNull(
                "$origin; Open Carrier Data's value: ${LayerValues.plain(e.ocdValue)}; your phone's own: ${LayerValues.plain(e.baseline)}",
                "$level, checked by $cls" + (e.method.takeIf { it.isNotEmpty() }?.let { " (${it.replace('_', ' ')})" } ?: "") +
                    (e.bearers.takeIf { it.isNotEmpty() }?.let { " over ${it.joinToString(" and ") { b -> if (b == "iwlan") "Wi-Fi" else "LTE" }}" } ?: ""),
                "since ${day(e.sinceMs)}, renewed until ${day(e.leaseUntilMs)}; build ${e.build.take(24)}",
                if (e.carriedOver) "checked on another build or IMS stack; checked again before it is renewed" else null,
            )
            val state = when {
                record?.removing != null -> "Waiting to go: ${record.removing}"
                e.state.startsWith("waiting") -> "Waiting: ${e.state.removePrefix("waiting: ")}"
                e.served -> "In effect"
                else -> "Not in effect: ${e.state.removePrefix("not served: ")}"
            }
            return LayerLine(e.key, "$name: ${LayerValues.plain(e.value)}", details, state, probe = e.method == LayerFirstUse.PROBE)
        }

        val KILL_SWITCH_NOTE = "These stay after a restart. Neither a restart nor `cmd phone cc clear-values` removes them. " +
            "Without this screen: Undo changes in the notification, or `adb shell pm clear com.android.carriersettings` and a restart"

        private val json = Json { ignoreUnknownKeys = true }

        /** The provider's list as [LayerSimView]: [sim] is its SIM object (JSON), null when the SIM has no entries. */
        fun view(sim: String?, servedSuffix: String, otherSims: Int, file: String, stop: String?): LayerSimView {
            val o = sim?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }?.takeIf { it is JsonObject }?.jsonObject
                ?: return LayerSimView(servedSuffix = servedSuffix, otherSims = otherSims, file = file, stop = stop)
            fun str(m: JsonObject, k: String) = (m[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun long(m: JsonObject, k: String) = (m[k] as? JsonPrimitive)?.longOrNull ?: 0L
            fun bool(m: JsonObject, k: String) = (m[k] as? JsonPrimitive)?.booleanOrNull ?: false
            val entries = runCatching { o["entries"]!!.jsonArray }.getOrDefault(JsonArray(emptyList())).mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                LayerEntryView(
                    key = str(m, "key"), type = str(m, "type"), value = m["value"] ?: JsonNull, cls = str(m, "class"), origin = str(m, "origin"),
                    level = str(m, "level"), sinceMs = long(m, "since_ms"), leaseUntilMs = long(m, "lease_until_ms"),
                    voiceMeasured = bool(m, "voice_measured"), smsMeasured = bool(m, "sms_measured"),
                    bearers = runCatching { m["bearers"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
                    method = str(m, "method"), ocdValue = m["ocd_value"]?.takeIf { it != JsonNull }, build = str(m, "build"), stack = str(m, "stack"),
                    carriedOver = bool(m, "carried_over"), baseline = m["baseline"]?.takeIf { it != JsonNull }, served = bool(m, "served"), state = str(m, "state"),
                )
            }
            return LayerSimView(entries, servedSuffix, str(o, "reason"), bool(o, "pending"), otherSims, file, stop)
        }
    }
}

/** One line of the "Kept after restart" list. */
data class LayerLine(val key: String, val title: String, val details: List<String>, val state: String, val probe: Boolean = false)

/** Whether voice service is gone on this slot (X5): IMS not registered for voice and no circuit-switched service. Null: unreadable. */
fun Observation.voiceLost(): Boolean? {
    val imsVoice = registeredWith(ImsService.MMTEL)
    val circuit = cs?.state
    return when {
        imsVoice == true -> false
        circuit in setOf("home", "roaming") -> false
        imsVoice == false && circuit != null -> true
        imsVoice == false && circuit == null -> true
        else -> null
    }
}
