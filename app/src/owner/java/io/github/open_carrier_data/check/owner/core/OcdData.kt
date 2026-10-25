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
 * installs the APN part through UPDATE_APN_DB, switches the CarrierConfig part to the same commit,
 * and owns safe mode. This app pulls, stages, picks the idle moment to activate, tests, and writes
 * measured records.
 *
 * Every answer is a Bundle with `ok`; complex values travel as JSON strings. The Android side turns
 * the Bundle into a JSON object ([DataAnswer]) so that everything here is plain and testable; a
 * nested Bundle and a JSON string read the same.
 *
 * On a ROM without the provider (the round 29 layer, or the switch off) every call answers absent
 * ([DatasetClient.Absent]): nothing is staged, no test of round 30 runs, and the app keeps to its
 * read-only checks and reports.
 */

object OcdDataProtocol {
    const val AUTHORITY = "com.android.carriersettings.ocddata"
    const val URI = "content://$AUTHORITY"
    const val PERMISSION = "com.android.carriersettings.permission.WRITE_OCD_DATASET"
    const val PROTOCOL = 2

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
    const val SET_SAFE_MODE = "set_safe_mode"
    const val STOP = "stop"
    const val RESUME = "resume"
    const val CHECK = "check"

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
    const val BY = "by"

    /** set_safe_mode's `by` (spec 6.3). */
    const val BY_OWNER = "owner_tap"
    const val BY_VOICE_LOSS = "voice_loss"
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

/** Safe mode as CarrierSettings reports it (spec 8.2); CarrierSettings owns it, this app only shows and switches it. */
@Serializable
data class SafeMode(val on: Boolean, val since: String? = null, val by: String? = null, val reason: String? = null)

/** "Changes halted": the round 29 stop flag, redefined (spec 8.4). It refuses installs and records, not serving. */
@Serializable
data class ChangesHalted(val on: Boolean, val since: String? = null, val reason: String? = null)

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
    val safeMode: SafeMode = SafeMode(false),
    val changesHalted: ChangesHalted = ChangesHalted(false),
    val pullAgain: Boolean = false,
    val stagedId: String? = null,
    val stagedState: String? = null,
    val stagedError: String? = null,
    val waitingFor: String? = null,
    val emergencyGate: String = "full",
    val readAtMs: Long = 0,
) {
    val servingInstalled: Boolean get() = serving == "installed"

    companion object {
        fun absent(why: String, nowMs: Long = 0) = DatasetStatus(available = false, why = why, readAtMs = nowMs)

        fun parse(a: DataAnswer, nowMs: Long): DatasetStatus {
            if (!a.ok) return absent(if (a.error == DataAnswer.ABSENT) a.reason ?: "not on this ROM build" else a.line, nowMs)
            val protocol = a.long("protocol")?.toInt() ?: 0
            if (protocol != OcdDataProtocol.PROTOCOL) return absent("protocol $protocol; this app speaks ${OcdDataProtocol.PROTOCOL}", nowMs)
            val b = a.obj("build")
            val staged = a.obj("staged")
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
                safeMode = a.obj("safe_mode")?.let { SafeMode(bool(it, "on") == true, s(it, "since"), s(it, "by"), s(it, "reason")) } ?: SafeMode(a.bool("safe_mode") == true),
                changesHalted = a.obj("changes_halted")?.let { ChangesHalted(bool(it, "on") == true, s(it, "since"), s(it, "reason")) } ?: ChangesHalted(a.bool("changes_halted") == true),
                pullAgain = a.bool("pull_again") == true,
                stagedId = staged?.let { s(it, "install_id") },
                stagedState = staged?.let { s(it, "state") },
                stagedError = staged?.let { s(it, "error") },
                waitingFor = a.str("waiting_for") ?: a.arr("waiting_for")?.joinToString { (it as? JsonPrimitive)?.contentOrNull.orEmpty() },
                emergencyGate = a.str("emergency_gate") ?: "full",
                readAtMs = nowMs,
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

/** The files of one stage call: local name to the file's path; the Android side opens each read-only. */
data class StageRequest(val pin: String, val names: List<String>, val paths: Map<String, String>)

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
    fun setSafeMode(on: Boolean, by: String, reason: String): DataAnswer
    fun stop(reason: String): DataAnswer
    fun resume(): DataAnswer
    fun check(): DataAnswer

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
        override fun setSafeMode(on: Boolean, by: String, reason: String) = no
        override fun stop(reason: String) = no
        override fun resume() = no
        override fun check() = no
    }
}

/** A refusal of `activate` that only means "not now" (spec 6.5 `refused_now`, `busy`): the app tries again in a later idle moment. */
object InstallRefusal {
    val NOT_NOW = setOf("refused_now", "busy")
    val OWNER = setOf("safe_mode_on", "changes_halted")

    /** What the screen and the log say for a `waiting_for` value. */
    fun waiting(what: String?): String = when (what) {
        "call" -> "a call is up"
        "call_ended_10min" -> "a call ended less than 10 minutes ago"
        "ecbm" -> "emergency callback mode"
        "emergency_sms_mode" -> "emergency SMS mode"
        "state_unreadable" -> "the call or emergency state cannot be read"
        "roaming" -> "the phone is roaming"
        "apn_trial_in_place" -> "an APN test row is in place"
        null, "" -> "not now"
        else -> what.replace('_', ' ')
    }
}

/** The "Carrier data" section of the owner screen, in plain words (round 30): what serves, safe mode, a pause, the tests. */
data class DatasetLines(val title: String, val lines: List<String>, val safeModeOn: Boolean, val halted: String?, val available: Boolean)

object DatasetWords {
    fun of(status: DatasetStatus?, state: OwnerState): DatasetLines {
        if (status == null) return DatasetLines("Carrier data", listOf("Not looked at yet"), safeModeOn = false, halted = null, available = false)
        if (!status.available) {
            return DatasetLines(
                "Carrier data",
                listOf("This ROM has no installer for Open Carrier Data's dataset, so the ROM's own carrier data serves. Open Carrier Data checks and reports, and changes nothing"),
                safeModeOn = false, halted = null, available = false,
            )
        }
        val lines = mutableListOf<String>()
        val installed = status.installed
        lines += when {
            status.safeMode.on -> "The ROM's own data serves: safe mode is on" + (status.safeMode.by?.let { by -> " (${if (by == OcdDataProtocol.BY_VOICE_LOSS) "calls came back only with the ROM's data" else "turned on by you"})" } ?: "")
            status.servingInstalled && installed != null -> "The newest data serves: main ${installed.commit.take(7)}" + (installed.generatedAt.takeIf { it.isNotEmpty() }?.let { ", from ${it.take(10)}" } ?: "")
            else -> "The ROM's own data serves" + when (status.servingReason) {
                "no_dataset", "" -> ""
                "corrupt" -> ": the installed data could not be read"
                "build_pin_newer" -> ": the ROM's data is newer"
                "crash_guard" -> ": the newest data did not load twice"
                "cleared" -> ": the installed data was cleared"
                else -> " (${status.servingReason.replace('_', ' ')})"
            }
        }
        state.datasetLine?.let { lines += it }
        state.pulled?.takeIf { it.datasetDigest != installed?.datasetDigest }?.let { lines += "Downloaded, waiting for the phone to be idle: main ${it.commit.take(7)}" }
        state.campaigns.firstOrNull { !it.done }?.let { lines += "Testing the changed values (${it.trials} test${if (it.trials == 1) "" else "s"} so far); it goes on when the phone is idle" }
        state.campaignEnds.lastOrNull()?.let { lines += "Last tests: ${it.note.take(140)}" }
        if (status.safeMode.on) lines += "Tests are stopped while safe mode is on"
        val halted = state.stop ?: status.changesHalted.reason?.takeIf { status.changesHalted.on } ?: "paused".takeIf { status.changesHalted.on }
        state.emergencyFirstUse?.let { lines += "Emergency data test: first use ${if (it.passed) "passed" else "not meaningful on this phone"}" }
        state.emergencyStop?.let { lines += "Emergency data test stopped until a judged round: $it" }
        return DatasetLines("Carrier data", lines, status.safeMode.on, halted, available = true)
    }
}
