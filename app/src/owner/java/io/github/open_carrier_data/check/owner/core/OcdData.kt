package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The install interface between this app and OcdCarrierSettings (OCD round 30; the spec's section
 * 6): `content://com.android.carriersettings.ocddata`, method calls only, with round 29's pinned
 * writer unchanged (knownSigner with the owner certificate, a UID check on every call; adb may read
 * and clear, never write). CarrierSettings is the only installer: it checks the files again,
 * installs the APN part through UPDATE_APN_DB, and switches the CarrierConfig part to the same
 * commit. This app pulls, stages, picks the idle moment to activate, tests, and writes measured
 * records. Round 31 removed safe mode: the way back is disabling this app in Android's Settings,
 * after which CarrierSettings serves the build's dataset, both parts, without the app.
 *
 * Every answer is a Bundle with `ok`; complex values travel as JSON strings. The Android side turns
 * the Bundle into a JSON object ([DataAnswer]) so that everything here is plain and testable; a
 * nested Bundle and a JSON string read the same.
 *
 * On a ROM without the provider (the round 29 layer, or the switch off) every call answers absent
 * ([DatasetClient.Absent]): nothing is staged, no test of round 30 runs, and the app keeps to its
 * read-only checks and reports.
 *
 * Round 31 (the spec's "Round 31 changes", protocol 3): no safe mode; the voice-loss hold and its
 * release; the default emergency APN test's temporary set and its restore; the tester declaration
 * for SM7; the device override's keys; the first-use record with every hand-off. A protocol 2 ROM
 * (the round 30 build still on the phone until the next one) is served as before, without them:
 * every round 31 call there answers `unknown_method`, and the app goes on without that step.
 */

object OcdDataProtocol {
    const val AUTHORITY = "com.android.carriersettings.ocddata"
    const val URI = "content://$AUTHORITY"
    const val PERMISSION = "com.android.carriersettings.permission.WRITE_OCD_DATASET"
    /** The protocol this app speaks; [PROTOCOLS] are the ones it reads (2: the round 30 build). */
    const val PROTOCOL = 3
    val PROTOCOLS = setOf(2, 3)

    const val STATUS = "status"
    const val STAGE = "stage_dataset"
    const val INSTALL_STATUS = "install_status"
    const val ACTIVATE = "activate"
    const val CANCEL = "cancel"
    const val EXPLAIN = "explain"
    const val PUT_RECORD = "put_record"
    const val REMOVE_RECORD = "remove_record"
    const val LIST_RECORDS = "list_records"
    const val APPLY_APN_RECORDS = "apply_apn_records"
    const val STOP = "stop"
    const val RESUME = "resume"
    const val CHECK = "check"

    /** Round 31 (protocol 3): the tester declaration (R31.5), the voice-loss hold (R31.8), the default emergency APN test (R31.9). */
    const val SET_TESTER = "set_tester"
    const val VOICE_LOSS_HOLD = "voice_loss_hold"
    const val VOICE_LOSS_RELEASE = "voice_loss_release"
    const val EMERGENCY_DEFAULT_SET = "emergency_default_set"
    const val EMERGENCY_DEFAULT_RESTORE = "emergency_default_restore"

    const val OK = "ok"
    const val ERROR = "error"
    const val REASON = "reason"
    const val PIN = "pin"
    const val FILES = "files"
    const val FD_PREFIX = "fd."
    const val F5_READ = "f5_read"
    const val INSTALL_ID = "install_id"
    const val STATE = "state"
    const val WAITING_FOR = "waiting_for"
    const val SUB_ID = "sub_id"
    const val DATASET = "dataset"
    const val RECORD = "record"
    const val RECORD_KEY = "record_key"
    const val FIRST_USE = "first_use"
    const val OUTCOME = "outcome"
    const val KEEP = "keep"
    const val JOB_ID = "job_id"

    /** The answer of a provider that does not know a method (a protocol 2 ROM for every round 31 method). */
    const val UNKNOWN_METHOD = "unknown_method"

    /** `voice_loss_release`'s outcomes (R31.8). */
    const val OUTCOME_WINNERS = "winners"
    const val OUTCOME_NO_FAILING_VALUE = "no_failing_value"
}

/** The install job states (spec 6.6). */
object InstallState {
    const val VERIFYING = "verifying"
    const val VERIFIED = "verified"
    const val REFUSED = "refused"
    const val ACTIVATING = "activating"
    const val INSTALLED = "installed"
    const val ALREADY_INSTALLED = "already_installed"
    const val SUPERSEDED = "superseded"
    const val CANCELLED = "cancelled"
    const val APN_NOT_CONFIRMED = "apn_not_confirmed"
    const val RELOAD_NOT_PROVEN = "reload_not_proven"
    val ACTIVATION = setOf(ACTIVATING, "apn_installing", "apn_confirming", "switching", "proving")
    val FINAL = setOf(REFUSED, INSTALLED, ALREADY_INSTALLED, SUPERSEDED, CANCELLED, APN_NOT_CONFIRMED, RELOAD_NOT_PROVEN, "apn_rollback_failed")

    /** Round 31 (R31.9): the temporary emergency-less set's job states, and its restore's. */
    const val IN_PLACE = "in_place"
    const val RESTORED = "restored"
    const val KEPT = "kept"
    const val RESTORE_FAILED = "restore_failed"
    const val WAITING = "waiting"
    val SET_FINAL = setOf(IN_PLACE, REFUSED, APN_NOT_CONFIRMED)
    val RESTORE_FINAL = setOf(RESTORED, KEPT, RESTORE_FAILED, REFUSED)
}

/** A provider answer as JSON: the Bundle's keys with their values, JSON strings opened. [ok] false with [error] and [reason] on a refusal. */
data class DataAnswer(val o: JsonObject) {
    val ok: Boolean get() = bool("ok") == true
    val error: String? get() = str("error")
    val reason: String? get() = str("reason")?.take(240)

    fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun bool(key: String): Boolean? = (o[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    fun long(key: String): Long? = (o[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    /** A member as JSON, a JSON string opened (complex values travel as JSON strings, spec 6.2). */
    fun element(key: String): JsonElement? = when (val v = o[key]) {
        is JsonPrimitive -> if (v.isString) open(v.content) else v
        else -> v
    }
    fun obj(key: String): JsonObject? = element(key) as? JsonObject
    fun arr(key: String): JsonArray? = element(key) as? JsonArray

    /** A one-line description for the log: "refused (busy): another install job runs". */
    val line: String get() = if (ok) "ok" else "refused (${error ?: "no answer"})" + (reason?.let { ": $it" } ?: "")

    companion object {
        fun absent(why: String) = DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(ABSENT), "reason" to JsonPrimitive(why))))
        const val ABSENT = "absent"

        /** A string that holds a JSON object or array is that value; any other string stays a string. */
        fun open(value: String?): JsonElement = when {
            value == null -> JsonNull
            value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> runCatching { Json.parseToJsonElement(value) }.getOrDefault(JsonPrimitive(value))
            else -> JsonPrimitive(value)
        }
    }
}

/** "Changes halted": the round 29 stop flag, redefined (spec 8.4). It refuses installs and records, not serving. */
@Serializable
data class ChangesHalted(val on: Boolean, val since: String? = null, val reason: String? = null)

/**
 * Round 31's voice-loss hold as CarrierSettings reports it (R31.8): the build's dataset serves
 * until the app's split releases it; a second loss under the same differing values is a stop, and
 * that dataset is blocked.
 */
@Serializable
data class VoiceLossState(
    val hold: Boolean = false,
    val holdSince: String? = null,
    val holdReason: String? = null,
    val holdDatasetDigest: String? = null,
    val valuesDigest: String? = null,
    val stop: Boolean = false,
    val stopReason: String? = null,
    val blocked: List<String> = emptyList(),
)

/** Round 31 (R31.3): what the ROM's device override and LineageOS's fixed key set on this phone. */
@Serializable
data class DeviceOverride(val keys: Set<String> = emptySet(), val readable: Boolean? = null)

/** Round 31 (R31.5): the tester declaration as CarrierSettings keeps it, and whether SM7 applies now. */
@Serializable
data class TesterState(val active: Boolean? = null, val declared: Boolean? = null, val since: String? = null, val reason: String? = null)

/** A dataset as `status` names it: the build's pin or the installed one. */
@Serializable
data class DatasetRef(val commit: String = "", val dataDigest: String = "", val datasetDigest: String = "", val generatedAt: String = "", val installedAt: String? = null)

/** The phone's cell as CarrierSettings reads it from ocd-build.json and the build (spec 6.3 status `build`). Records must carry exactly this. */
@Serializable
data class BuildCell(
    val incremental: String = "",
    val device: String = "",
    val model: String = "",
    val romFamily: String = "",
    val romLine: String = "",
    val imsStackId: String = "",
    val imsStackFork: String? = null,
    val imsStackVersion: Long? = null,
) {
    val complete: Boolean get() = device.isNotEmpty() && model.isNotEmpty() && romFamily.isNotEmpty() && romLine.isNotEmpty() && imsStackId.isNotEmpty()
}

/** `status` (spec 6.3). [available] false: no provider on this ROM, or it refused this app; nothing else counts then. */
@Serializable
data class DatasetStatus(
    val available: Boolean,
    val why: String = "",
    val protocol: Int = 0,
    val build: BuildCell = BuildCell(),
    val buildPin: DatasetRef? = null,
    val installed: DatasetRef? = null,
    /** "installed" or "build". */
    val serving: String = "",
    val servingReason: String = "",
    val changesHalted: ChangesHalted = ChangesHalted(false),
    val pullAgain: Boolean = false,
    val stagedId: String? = null,
    val stagedState: String? = null,
    val stagedError: String? = null,
    val waitingFor: String? = null,
    val emergencyGate: String = "full",
    val readAtMs: Long = 0,
    /** Round 31 (R31.6): `build` when the build declares the device family's first use passed (the ROM builder's SM10 switch), else `none`. */
    val emergencyGateSource: String? = null,
    /** Round 31 (R31.4): the OCD app's state as CarrierSettings sees it: enabled, disabled, absent or unknown. */
    val appState: String? = null,
    val deviceOverride: DeviceOverride = DeviceOverride(),
    val tester: TesterState = TesterState(),
    /** SM7 applies now (no tester on this phone): X3 to X5 values only where they equal the Google lane. */
    val sm7: Boolean? = null,
    val voiceLoss: VoiceLossState = VoiceLossState(),
    /** Round 31 (R31.9): the temporary emergency-less APN set is in place ("emergency default test set"). */
    val apnTestSet: Boolean = false,
) {
    val servingInstalled: Boolean get() = serving == "installed"

    /** The provider speaks round 31 (protocol 3): the hold, the default emergency APN test and the tester declaration exist. */
    val round31: Boolean get() = available && protocol >= 3

    /** SM10: the ROM builder declared the device family's first use of the emergency data test passed. */
    val emergencyTestOn: Boolean get() = emergencyGateSource == "build" && emergencyGate == "narrow"

    companion object {
        fun absent(why: String, nowMs: Long = 0) = DatasetStatus(available = false, why = why, readAtMs = nowMs)

        fun parse(a: DataAnswer, nowMs: Long): DatasetStatus {
            if (!a.ok) return absent(if (a.error == DataAnswer.ABSENT) a.reason ?: "not on this ROM build" else a.line, nowMs)
            val protocol = a.long("protocol")?.toInt() ?: 0
            if (protocol !in OcdDataProtocol.PROTOCOLS) return absent("protocol $protocol; this app speaks ${OcdDataProtocol.PROTOCOLS.sorted().joinToString(" and ")}", nowMs)
            val b = a.obj("build")
            val staged = a.obj("staged")
            val voice = a.obj("voice_loss")
            val hold = voice?.get("hold") as? JsonObject
            val stop = voice?.get("stop") as? JsonObject
            val device = a.obj("device_override")
            val tester = a.obj("tester")
            return DatasetStatus(
                available = true,
                protocol = protocol,
                build = BuildCell(
                    incremental = s(b, "incremental").orEmpty(),
                    device = s(b, "device").orEmpty(),
                    model = s(b, "model").orEmpty(),
                    romFamily = s(b, "rom_family").orEmpty(),
                    romLine = s(b, "rom_line").orEmpty(),
                    imsStackId = s(b, "ims_stack_id").orEmpty(),
                    imsStackFork = s(b, "ims_stack_fork")?.takeIf { it.isNotEmpty() },
                    imsStackVersion = l(b, "ims_stack_version"),
                ),
                buildPin = ref(a.obj("build_pin")),
                installed = ref(a.obj("installed")),
                serving = (a.obj("serving")?.let { s(it, "serving") } ?: a.str("serving")).orEmpty(),
                servingReason = (a.str("serving_reason") ?: a.obj("serving")?.let { s(it, "serving_reason") ?: s(it, "reason") }).orEmpty(),
                changesHalted = a.obj("changes_halted")?.let { ChangesHalted(bool(it, "on") == true, s(it, "since"), s(it, "reason")) } ?: ChangesHalted(a.bool("changes_halted") == true),
                pullAgain = a.bool("pull_again") == true,
                stagedId = staged?.let { s(it, "install_id") },
                stagedState = staged?.let { s(it, "state") },
                stagedError = staged?.let { s(it, "error") },
                waitingFor = a.str("waiting_for") ?: a.arr("waiting_for")?.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() },
                emergencyGate = a.str("emergency_gate") ?: "full",
                readAtMs = nowMs,
                emergencyGateSource = a.str("emergency_gate_source"),
                appState = a.obj("app")?.let { s(it, "state") },
                deviceOverride = DeviceOverride(
                    keys = (device?.get("keys") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet() +
                        (device?.get("values") as? JsonObject)?.keys.orEmpty(),
                    readable = device?.let { bool(it, "readable") },
                ),
                tester = tester?.let { TesterState(bool(it, "active"), bool(it, "declared"), s(it, "since"), s(it, "reason")) } ?: TesterState(),
                sm7 = a.bool("sm7"),
                voiceLoss = VoiceLossState(
                    hold = hold != null,
                    holdSince = s(hold, "since"),
                    holdReason = s(hold, "reason"),
                    holdDatasetDigest = s(hold, "dataset_digest"),
                    valuesDigest = s(hold, "values_digest"),
                    stop = stop != null,
                    stopReason = s(stop, "reason"),
                    blocked = (voice?.get("blocked") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                ),
                apnTestSet = when (val t = a.element("apn_test_set")) {
                    null, JsonNull -> false
                    is JsonPrimitive -> t.booleanOrNull ?: t.contentOrNull?.let { it.isNotEmpty() && it != "false" } ?: false
                    is JsonObject -> bool(t, "in_place") ?: true
                    else -> true
                },
            )
        }

        private fun ref(o: JsonObject?): DatasetRef? = o?.let {
            DatasetRef(s(it, "commit").orEmpty(), s(it, "data_digest").orEmpty(), s(it, "dataset_digest").orEmpty(), s(it, "generated_at").orEmpty(), s(it, "installed_at"))
        }?.takeIf { it.commit.isNotEmpty() || it.datasetDigest.isNotEmpty() }

        internal fun s(o: JsonObject?, k: String): String? = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        internal fun l(o: JsonObject?, k: String): Long? = (o?.get(k) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        internal fun bool(o: JsonObject?, k: String): Boolean? = (o?.get(k) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    }
}

/** One served key whose value an install changes for a SIM (spec 6.3 `changes`). */
@Serializable
data class KeyChange(val key: String, val old: JsonElement? = null, val new: JsonElement? = null, val source: String = "")

/** A key CarrierSettings holds back (X5, spec 5.4): the data's off, and what it serves instead. */
@Serializable
data class X5Hold(val key: String, val dataValue: JsonElement?, val servedValue: JsonElement?)

/** What an install changes for one active subscription (spec 6.3 `install_status.changes`). */
@Serializable
data class SubChanges(
    val subId: Int,
    val profileBefore: String? = null,
    val profileAfter: String? = null,
    val keys: List<KeyChange> = emptyList(),
    val apnAdded: List<String> = emptyList(),
    val apnRemoved: List<String> = emptyList(),
    val couplingBefore: String? = null,
    val couplingAfter: String? = null,
    val x5Held: List<X5Hold> = emptyList(),
) {
    /** F4: a value or row of the SIM's matched profile changed, or the profile itself. */
    val touchesSim: Boolean get() = keys.isNotEmpty() || apnAdded.isNotEmpty() || apnRemoved.isNotEmpty() || profileBefore != profileAfter

    companion object {
        /**
         * `changes` as CarrierSettings writes it: a list of subscription entries, or an object
         * keyed by subscription ID. Keys as a list of {key, old, new, source} or an object
         * key -> {old, new, source}; X5 holds as a list or an object key -> {data_value,
         * served_value}. Unknown members are ignored here; CarrierSettings is the checker.
         */
        fun parse(changes: JsonElement?): List<SubChanges> {
            val entries: List<Pair<Int?, JsonObject>> = when (changes) {
                is JsonArray -> changes.mapNotNull { (it as? JsonObject)?.let { o -> null to o } }
                is JsonObject -> changes.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k.toIntOrNull() to it } }
                else -> emptyList()
            }
            return entries.mapNotNull { (keyed, o) ->
                val sub = DatasetStatus.l(o, "sub_id")?.toInt() ?: DatasetStatus.l(o, "subscription")?.toInt() ?: keyed ?: return@mapNotNull null
                SubChanges(
                    subId = sub,
                    profileBefore = DatasetStatus.s(o, "profile_before") ?: (o["serving"] as? JsonObject)?.let { DatasetStatus.s(it, "before") },
                    profileAfter = DatasetStatus.s(o, "profile_after") ?: (o["serving"] as? JsonObject)?.let { DatasetStatus.s(it, "after") },
                    keys = keys(o["keys"]),
                    apnAdded = strings(o["apn_added"] ?: (o["apn"] as? JsonObject)?.get("added")),
                    apnRemoved = strings(o["apn_removed"] ?: (o["apn"] as? JsonObject)?.get("removed")),
                    couplingBefore = DatasetStatus.s(o, "coupling_before") ?: (o["coupling"] as? JsonObject)?.let { DatasetStatus.s(it, "before") },
                    couplingAfter = DatasetStatus.s(o, "coupling_after") ?: (o["coupling"] as? JsonObject)?.let { DatasetStatus.s(it, "after") },
                    x5Held = holds(o["x5_held"] ?: o["x5_holds"]),
                )
            }
        }

        private fun keys(e: JsonElement?): List<KeyChange> = when (e) {
            is JsonArray -> e.mapNotNull { (it as? JsonObject)?.let { o -> DatasetStatus.s(o, "key")?.let { k -> KeyChange(k, o["old"].orNullValue(), o["new"].orNullValue(), DatasetStatus.s(o, "source").orEmpty()) } } }
            is JsonObject -> e.mapNotNull { (k, v) -> (v as? JsonObject)?.let { KeyChange(k, it["old"].orNullValue(), it["new"].orNullValue(), DatasetStatus.s(it, "source").orEmpty()) } }
            else -> emptyList()
        }

        private fun holds(e: JsonElement?): List<X5Hold> = when (e) {
            is JsonArray -> e.mapNotNull { (it as? JsonObject)?.let { o -> DatasetStatus.s(o, "key")?.let { k -> X5Hold(k, o["data_value"], o["served_value"]) } } }
            is JsonObject -> e.mapNotNull { (k, v) -> (v as? JsonObject)?.let { X5Hold(k, it["data_value"], it["served_value"]) } }
            else -> emptyList()
        }

        private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { o -> DatasetStatus.s(o, "row_key") } }.orEmpty()

        /** A JSON null stays "absent" (Android's default) rather than a value. */
        private fun JsonElement?.orNullValue(): JsonElement? = this?.takeIf { it !is JsonNull }
    }
}

/** `install_status` (spec 6.3) as this app keeps it. */
@Serializable
data class InstallView(
    val installId: String,
    val state: String,
    val error: String? = null,
    val reason: String? = null,
    val commit: String = "",
    val datasetDigest: String = "",
    val generatedAt: String = "",
    val changes: List<SubChanges> = emptyList(),
    val failedChecks: List<String> = emptyList(),
    /** Which CarrierSettings took the hand-over ([DatasetRetry.key]); empty in records before 2026-10-25. */
    val carrierSettings: String = "",
) {
    val final: Boolean get() = state in InstallState.FINAL

    companion object {
        fun parse(id: String, a: DataAnswer): InstallView {
            if (!a.ok && a.str("state") == null) return InstallView(id, InstallState.REFUSED, a.error ?: "no_answer", a.reason)
            val checks = a.arr("checks").orEmpty().mapNotNull { it as? JsonObject }
                .filter { DatasetStatus.bool(it, "ok") == false }
                .map { "${DatasetStatus.s(it, "check") ?: "?"}: ${DatasetStatus.s(it, "detail").orEmpty().take(120)}" }
            return InstallView(
                installId = a.str("install_id") ?: id,
                state = a.str("state") ?: InstallState.REFUSED,
                error = a.str("error"),
                reason = a.reason,
                commit = a.str("commit").orEmpty(),
                datasetDigest = a.str("dataset_digest").orEmpty(),
                generatedAt = a.str("generated_at").orEmpty(),
                changes = SubChanges.parse(a.element("changes")),
                failedChecks = checks,
            )
        }
    }
}

/**
 * When a dataset whose last install ended without serving goes to CarrierSettings again
 * (2026-10-25: a `storage` refusal of main a4675ce87e02 was a CarrierSettings bug, fixed in the
 * next ROM, and the app never handed the same commit over again). Again once for each new
 * CarrierSettings (its build incremental, provider protocol or build pin), and on every "Test now";
 * never in a loop for one and the same CarrierSettings.
 */
object DatasetRetry {
    /** The CarrierSettings that answered [status]: the build incremental, the provider protocol and the build pin. */
    fun key(status: DatasetStatus): String =
        listOf(status.build.incremental, status.protocol.toString(), status.buildPin?.commit.orEmpty(), status.buildPin?.datasetDigest.orEmpty()).joinToString("|")

    /**
     * Round 31 (R31.4): why [last], installed by this app, goes to CarrierSettings again although it ended: CarrierSettings
     * no longer has it installed (the person disabled the app and enabled it again, or the data was cleared), so the
     * build serves. Never for the crash guard's dataset or a voice-loss stop, which CarrierSettings refuses on its own.
     */
    fun dropped(last: InstallView, status: DatasetStatus): String? = when {
        last.state != InstallState.INSTALLED && last.state != InstallState.ALREADY_INSTALLED -> null
        status.installed?.datasetDigest == last.datasetDigest -> null
        status.servingReason in setOf("crash_guard", "voice_loss_stop", "voice_loss_hold") -> null
        last.datasetDigest in status.voiceLoss.blocked -> null
        else -> "CarrierSettings no longer has it installed (${status.servingReason.ifEmpty { "build serves" }.replace('_', ' ')}); every value that differs from the build's is tested"
    }

    /** Why [last] (the same dataset, ended) is handed over again now, or null. */
    fun again(last: InstallView, status: DatasetStatus, testNow: Boolean): String? = when {
        !last.final -> null
        testNow -> "your request"
        last.carrierSettings != key(status) -> "CarrierSettings changed since its ${last.state.replace('_', ' ')} of ${last.installId}"
        else -> null
    }
}

/** One key of `explain` (spec 6.3): its value, and where it came from (`ocd:<profile>`, `google_fill:<entry>`, `x5_hold`, ...). */
@Serializable
data class ExplainKey(val value: JsonElement?, val source: String)

/** One APN row TelephonyProvider will select for the SIM (spec 6.3 explain `apn.rows`). */
@Serializable
data class ExplainRow(val rowKey: String, val apn: String = "", val types: List<String> = emptyList(), val protocol: String = "", val roamingProtocol: String = "", val emergency: Boolean = false)

/** `explain` for one subscription and dataset (spec 6.3). Never holds an IMSI, ICCID or GID value. */
@Serializable
data class ExplainView(
    val subId: Int,
    val network: String = "",
    val stack: List<String> = emptyList(),
    val serving: String = "",
    val fillEntry: String? = null,
    val dataset: String = "",
    val commit: String = "",
    val versionString: String = "",
    val keys: Map<String, ExplainKey> = emptyMap(),
    val x5Held: List<X5Hold> = emptyList(),
    val recordsApplied: List<String> = emptyList(),
    val apnProfile: String? = null,
    val coupling: String? = null,
    val rows: List<ExplainRow> = emptyList(),
    /** Round 31 (R31.5): SM7 applies to this SIM's bundle now. */
    val sm7: Boolean? = null,
    /** Round 31 (R31.9): the temporary emergency-less APN set is in place. */
    val apnTestSet: Boolean = false,
) {
    /** The serving profile is the reserved `default` or a fill-only profile: a record for it is keyed by the SIM (sim_key). */
    val servedBySimKey: Boolean get() = serving == "default" || serving.startsWith("fill.")

    /** The SIM's selected emergency row (type `emergency`), if any. */
    val emergencyRow: ExplainRow? get() = rows.firstOrNull { it.emergency || "emergency" in it.types }

    companion object {
        fun parse(subId: Int, a: DataAnswer): ExplainView? {
            if (!a.ok) return null
            val e = a.obj("explain") ?: return null
            val stack = e["stack"]
            val stackIds = when (stack) {
                is JsonArray -> stack.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { o -> DatasetStatus.s(o, "id") } }
                is JsonObject -> (stack["ids"] as? JsonArray ?: stack["profiles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { o -> DatasetStatus.s(o, "id") } }.orEmpty()
                else -> emptyList()
            }
            val serving = (stack as? JsonObject)?.let { DatasetStatus.s(it, "serving") } ?: DatasetStatus.s(e, "serving") ?: stackIds.lastOrNull() ?: "default"
            val ds = e["dataset"]
            val apn = e["apn"] as? JsonObject
            return ExplainView(
                subId = subId,
                network = DatasetStatus.s(e, "network").orEmpty(),
                stack = stackIds,
                serving = serving,
                fillEntry = (e["fill"] as? JsonObject)?.let { DatasetStatus.s(it, "entry") } ?: DatasetStatus.s(e, "fill"),
                dataset = (ds as? JsonObject)?.let { DatasetStatus.s(it, "dataset") ?: DatasetStatus.s(it, "serving") } ?: (ds as? JsonPrimitive)?.contentOrNull.orEmpty(),
                commit = (ds as? JsonObject)?.let { DatasetStatus.s(it, "commit") } ?: DatasetStatus.s(e, "commit").orEmpty(),
                versionString = DatasetStatus.s(e, "version_string").orEmpty(),
                keys = (e["keys"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    (v as? JsonObject)?.let { k to ExplainKey(it["value"]?.takeIf { x -> x !is JsonNull }, DatasetStatus.s(it, "source").orEmpty()) }
                }.toMap(),
                x5Held = (e["x5_held"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonObject)?.let { X5Hold(k, it["data_value"], it["served_value"]) } },
                recordsApplied = (e["records_applied"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                apnProfile = apn?.let { DatasetStatus.s(it, "apn_profile") },
                coupling = apn?.let { DatasetStatus.s(it, "coupling") },
                sm7 = DatasetStatus.bool(e, "sm7"),
                apnTestSet = e["apn_test_set"]?.let { it !is JsonNull && (it as? JsonPrimitive)?.booleanOrNull != false } == true,
                rows = (apn?.get("rows") as? JsonArray).orEmpty().mapNotNull { r ->
                    val o = r as? JsonObject ?: return@mapNotNull null
                    val types = when (val t = o["types"]) {
                        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> t.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        else -> emptyList()
                    }
                    ExplainRow(
                        DatasetStatus.s(o, "row_key") ?: return@mapNotNull null, DatasetStatus.s(o, "apn").orEmpty(), types,
                        DatasetStatus.s(o, "protocol").orEmpty(), DatasetStatus.s(o, "roaming_protocol").orEmpty(),
                        DatasetStatus.bool(o, "emergency") == true,
                    )
                },
            )
        }
    }
}

/** One record as `list_records` lists it (spec 6.3): the record, its origin and its state. */
@Serializable
data class ListedRecord(val record: JsonObject, val origin: String, val state: String) {
    val recordKey: String get() = DatasetStatus.s(record, "record_key").orEmpty()
}

/**
 * The files of one stage call: local name to the file's path; the Android side opens each read-only.
 * [firstUse]: round 31's first-use record of the emergency data test (R31.6), re-sent with every hand-off.
 */
data class StageRequest(val pin: String, val names: List<String>, val paths: Map<String, String>, val firstUse: String? = null)

/**
 * How the app reaches the installer. [Absent] on a ROM without it. Every method returns the raw
 * answer; the planners read it.
 */
interface DatasetClient {
    fun status(): DataAnswer
    fun stage(request: StageRequest): DataAnswer
    fun installStatus(id: String): DataAnswer
    fun activate(id: String): DataAnswer
    fun cancel(id: String): DataAnswer
    fun explain(subId: Int, dataset: String? = null): DataAnswer
    fun putRecord(record: String): DataAnswer
    fun removeRecord(key: String): DataAnswer
    fun listRecords(subId: Int? = null): DataAnswer
    fun applyApnRecords(): DataAnswer
    fun stop(reason: String): DataAnswer
    fun resume(): DataAnswer
    fun check(): DataAnswer

    /** Round 31 (R31.5): whether dialled tests run on this phone (the opt-in, the stack calibrated). */
    fun setTester(on: Boolean, reason: String): DataAnswer

    /** Round 31 (R31.8): only the build's dataset restored voice; CarrierSettings holds it until [voiceLossRelease]. Answers `voice_loss` hold or stop. */
    fun voiceLossHold(reason: String): DataAnswer
    fun voiceLossRelease(outcome: String, reason: String): DataAnswer

    /** Round 31 (R31.9): the temporary set without this SIM's emergency rows, and its restore; both answer a `job_id` polled with [installStatus]. */
    fun emergencyDefaultSet(subId: Int): DataAnswer
    fun emergencyDefaultRestore(keep: Boolean): DataAnswer

    object Absent : DatasetClient {
        private val no = DataAnswer.absent("Open Carrier Data's dataset installer is not on this ROM build")
        override fun status() = no
        override fun stage(request: StageRequest) = no
        override fun installStatus(id: String) = no
        override fun activate(id: String) = no
        override fun cancel(id: String) = no
        override fun explain(subId: Int, dataset: String?) = no
        override fun putRecord(record: String) = no
        override fun removeRecord(key: String) = no
        override fun listRecords(subId: Int?) = no
        override fun applyApnRecords() = no
        override fun stop(reason: String) = no
        override fun resume() = no
        override fun check() = no
        override fun setTester(on: Boolean, reason: String) = no
        override fun voiceLossHold(reason: String) = no
        override fun voiceLossRelease(outcome: String, reason: String) = no
        override fun emergencyDefaultSet(subId: Int) = no
        override fun emergencyDefaultRestore(keep: Boolean) = no
    }
}

/** A refusal of `activate` that only means "not now" (spec 6.5 `refused_now`, `busy`): the app tries again in a later idle moment. */
object InstallRefusal {
    val NOT_NOW = setOf("refused_now", "busy")

    /** Round 31 (R31.2): the voice-loss hold or stop refuses installs and records until the release, Resume or a newer build. */
    val VOICE_LOSS = setOf("voice_loss_hold", "voice_loss_stop")

    /** What the screen and the log say for a `waiting_for` value. */
    fun waiting(what: String?): String = when (what) {
        "call" -> "a call is up"
        "call_ended_10min" -> "a call ended less than 10 minutes ago"
        "ecbm" -> "emergency callback mode"
        "emergency_sms_mode" -> "emergency SMS mode"
        "state_unreadable" -> "the call or emergency state cannot be read"
        "roaming" -> "the phone is roaming"
        "apn_trial_in_place" -> "an APN test row is in place"
        "apn_test_set_in_place" -> "the emergency default test set is in place"
        "emergency_call_60min" -> "an emergency call ended less than 60 minutes ago"
        null, "" -> "not now"
        else -> what.replace('_', ' ')
    }
}

/** The "Carrier data" section of the owner screen, in plain words (rounds 30 and 31): what serves, a pause, the tests, and the way back. */
data class DatasetLines(val title: String, val lines: List<String>, val halted: String?, val available: Boolean)

object DatasetWords {
    /**
     * Round 31's way back, in plain words: there is no safe mode; disabling the app in Android's
     * Settings makes CarrierSettings serve the ROM's own data, both parts, without the app.
     */
    const val WAY_BACK =
        "To go back to the ROM's own carrier data and access point names, disable Open Carrier Data in Android's Settings: " +
            "Apps, Open Carrier Data, Disable. This works even when this app is broken, while roaming and without a computer. " +
            "When you enable it again, the newest data comes back the next time the phone is idle, or at once with Test now, and is tested then"

    fun of(status: DatasetStatus?, state: OwnerState): DatasetLines {
        if (status == null) return DatasetLines("Carrier data", listOf("Not looked at yet"), halted = null, available = false)
        if (!status.available) {
            return DatasetLines(
                "Carrier data",
                listOf("This ROM has no installer for Open Carrier Data's dataset, so the ROM's own carrier data serves. Open Carrier Data checks and reports, and changes nothing"),
                halted = null, available = false,
            )
        }
        val lines = mutableListOf<String>()
        val installed = status.installed
        lines += when {
            status.servingInstalled && installed != null -> "The newest data serves: main ${installed.commit.take(7)}" + (installed.generatedAt.takeIf { it.isNotEmpty() }?.let { ", from ${it.take(10)}" } ?: "")
            else -> "The ROM's own data serves" + when (status.servingReason) {
                // Round 31: a cleared store is no reason of its own; it reads as no dataset installed.
                "no_dataset", "cleared", "" -> ""
                "corrupt" -> ": the installed data could not be read"
                "build_pin_newer" -> ": the ROM's data is newer"
                "crash_guard" -> ": the newest data did not load twice"
                "app_disabled" -> ": Open Carrier Data was disabled"
                "voice_loss_hold" -> ": calls came back only with it, and the values at fault are found when the phone is next idle at home"
                "voice_loss_stop" -> ": calls stopped twice with the same new values; tap Resume to try again, or wait for a newer ROM build"
                // A round 30 ROM still names its own switch; its build's data serves either way.
                "safe_mode" -> " (switched in this older ROM's own Settings entry)" // a round 30 ROM only
                else -> " (${status.servingReason.replace('_', ' ')})"
            }
        }
        if (status.apnTestSet) lines += "For a moment, the access point names leave out this SIM's emergency entry to test Android's own; they come back at once"
        state.datasetLine?.let { lines += it }
        state.pulled?.takeIf { it.datasetDigest != installed?.datasetDigest }?.let { lines += "Downloaded, waiting for the phone to be idle: main ${it.commit.take(7)}" }
        state.campaigns.firstOrNull { !it.done }?.let { lines += "Testing the changed values (${it.trials} test${if (it.trials == 1) "" else "s"} so far); it goes on when the phone is idle" }
        state.campaignEnds.lastOrNull()?.let { lines += "Last tests: ${it.note.take(140)}" }
        val halted = state.stop ?: status.changesHalted.reason?.takeIf { status.changesHalted.on } ?: "paused".takeIf { status.changesHalted.on }
        state.emergencyFirstUse?.let { lines += "Emergency data test: first use ${if (it.passed) "passed" else "not meaningful on this phone"}" }
        state.emergencyStop?.let { lines += "Emergency data test stopped until a judged round: $it" }
        return DatasetLines("Carrier data", lines, halted, available = true)
    }
}
