package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.PrivacyScan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * Measured records (OCD round 30, "perDevice"; the spec's 2.10 and 6.4): a correction measured on
 * this phone becomes a record keyed by device, carrier profile, ROM family and IMS stack. The phone
 * serves it at once through CarrierSettings's put_record, and the app publishes it to main through
 * the data side's owner-only intake (an issue opened with the owner's token). Records state only
 * what was measured; they never hold a phone number, IMSI, ICCID, IMEI, SIP URI or IP address, and
 * an emergency data result never says that emergency calls work.
 */

/** The groups the app keeps away from tests and records (spec 5.1 to 5.4, with the owner's decisions of 2026-10-25). */
object Groups {
    /** CarrierSettings's own group A pattern, case-insensitive (spec 5.1). */
    val A_PATTERN = Regex("emergency|e911|ecbm|(^|[._])ecm([._]|$)|wps|tty|rtt|sos|^imsemergency\\.", RegexOption.IGNORE_CASE)

    /** The two emergency-path names of round 29 (spec 5.1). */
    val A_NAMES = setOf("call_forwarding_blocks_while_roaming_string_array", "ims_reasoninfo_mapping_string_array")

    /** The eight emergency-looking keys of spec 11.4, which the owner put into group A on 2026-10-25. */
    val A_OWNER_2026_10_25 = setOf(
        "use_only_dialed_sim_ecc_list_bool",
        "rilext.carrier_ignore_clir_when_ecc", "rilext.carrier_ignore_sms_for_eims",
        "rilext.exiting_ecc_mode_after_epdn_disconnected_enable", "rilext.ims_prefer_apm_normal_ecc_enable",
        "gps.es_extension_sec",
        "dial_string_replace_string_array", "international_roaming_dial_string_replace_string_array",
    )

    /** Group B: the metered lists, never measured, never recorded (spec 5.2). */
    val B = setOf("carrier_metered_apn_types_strings", "carrier_metered_roaming_apn_types_strings")

    /** No value may be set for this key (spec 2.3). */
    const val VERSION_KEY = "carrier_config_version_string"

    /** X5 (spec 5.4): the keys whose "off" CarrierSettings holds until a test on this phone, with their off value. */
    val X5_OFF: Map<String, Boolean> = linkedMapOf(
        "carrier_volte_available_bool" to false,
        "carrier_wfc_ims_available_bool" to false,
        "imssms.sms_over_ims_supported_bool" to false,
        "carrier_volte_provisioning_required_bool" to true,
        "carrier_volte_provisioned_bool" to false,
    )

    /** Group A for [key]: the pattern, the names, the owner's eight, and the dataset's own marking ([datasetA]); never fewer than CarrierSettings's own. */
    fun isA(key: String, datasetA: Set<String> = emptySet()): Boolean =
        A_PATTERN.containsMatchIn(key) || key in A_NAMES || key in A_OWNER_2026_10_25 || key in datasetA

    fun isB(key: String): Boolean = key in B

    /** A key the app never tests, overrides or records: groups A and B and the version string. */
    fun untestable(key: String, datasetA: Set<String> = emptySet()): String? = when {
        isA(key, datasetA) -> "group A: these settings matter only during an emergency, TTY, RTT or priority call, which the phone never makes in a test"
        isB(key) -> "group B: the phone cannot see what a plan costs"
        key == VERSION_KEY -> "the version string is CarrierSettings's own"
        else -> null
    }

    /** The dataset's group A keys from profiles.json `groups.A.keys`. */
    fun datasetA(groups: JsonObject?): Set<String> =
        ((groups?.get("A") as? JsonObject)?.get("keys") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
}

/** A typed value as the dataset carries it (spec 2.3): the registry type and the plain JSON value. */
object TypedValues {
    val TYPES = setOf("bool", "int", "long", "double", "string", "string_array", "int_array", "bundle")

    /** The type a key's name suggests when no registry is at hand (Android's suffix convention); null when it does not tell. */
    fun typeBySuffix(key: String): String? = when {
        key.endsWith("_bool") -> "bool"
        key.endsWith("_int") -> "int"
        key.endsWith("_long") -> "long"
        key.endsWith("_double") -> "double"
        key.endsWith("_string") -> "string"
        key.endsWith("_string_array") || key.endsWith("_strings") -> "string_array"
        key.endsWith("_int_array") -> "int_array"
        key.endsWith("_bundle") -> "bundle"
        else -> null
    }

    /** Whether [value] has [type] (spec 2.3's JSON forms); a bundle is checked one level. */
    fun fits(type: String, value: JsonElement?): Boolean {
        if (value == null || value is JsonNull) return false
        return when (type) {
            "bool" -> (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true
            "int" -> (value as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toIntOrNull() != null } == true
            "long" -> (value as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toLongOrNull() != null } == true
            "double" -> (value as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toDoubleOrNull()?.isFinite() == true } == true
            "string" -> (value as? JsonPrimitive)?.isString == true
            "string_array" -> (value as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true
            "int_array" -> (value as? JsonArray)?.all { (it as? JsonPrimitive)?.let { p -> !p.isString && p.contentOrNull?.toIntOrNull() != null } == true } == true
            "bundle" -> value is JsonObject && value.values.all { it is JsonObject && "type" in it && "value" in it }
            else -> false
        }
    }

    /** Equal as values: JSON-equal after normalising numbers (5 and 5.0 are not the same type, so they differ). */
    fun same(a: JsonElement?, b: JsonElement?): Boolean = norm(a) == norm(b)

    private fun norm(e: JsonElement?): JsonElement? = when (e) {
        null, JsonNull -> null
        else -> e
    }

    fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}

/** The cell a record is keyed by (spec 2.10), from CarrierSettings's `status.build`. */
data class RecordCell(val codename: String, val model: String, val romFamily: String, val romLine: String, val imsStack: String, val fork: String?, val stackVersion: Long?) {
    companion object {
        val STACKS = setOf("aosp_imsstack", "phh_ims", "samsung_ims", "other")

        fun of(build: BuildCell): RecordCell? = if (!build.complete || build.imsStackId !in STACKS) null else
            RecordCell(build.device, build.model, build.romFamily, build.romLine, build.imsStackId, build.imsStackFork, build.imsStackVersion)
    }
}

/** A record's carrier scope (spec 2.10): the serving OCD profile, or the SIM's network with its GID1 or SPN for `default` and fill-only profiles. */
sealed interface RecordCarrier {
    val text: String
    fun json(): JsonObject

    /** Valid for every carrier: the configured device records of the data side (this app never writes one). */
    data object All : RecordCarrier {
        override val text get() = "all"
        override fun json() = JsonObject(mapOf("scope" to JsonPrimitive("all")))
    }

    data class Profile(val profileId: String) : RecordCarrier {
        override val text get() = "profile:$profileId"
        override fun json() = JsonObject(linkedMapOf("scope" to JsonPrimitive("profile"), "profile_id" to JsonPrimitive(profileId)))
    }

    data class SimKeyed(val mcc: String, val mnc: String, val gid1: String?, val spn: String?) : RecordCarrier {
        init {
            require((gid1 == null) != (spn == null)) { "exactly one of gid1 and spn" }
        }

        override val text get() = if (gid1 != null) "sim:$mcc-$mnc:gid1=${gid1.uppercase(Locale.ROOT)}" else "sim:$mcc-$mnc:spn=$spn"
        override fun json() = JsonObject(
            linkedMapOf<String, JsonElement>("scope" to JsonPrimitive("sim_key"), "mcc" to JsonPrimitive(mcc), "mnc" to JsonPrimitive(mnc)).apply {
                if (gid1 != null) put("gid1", JsonPrimitive(gid1.uppercase(Locale.ROOT))) else put("spn", JsonPrimitive(spn))
            },
        )
    }

    companion object {
        private val HEX = Regex("[0-9A-Fa-f]{1,32}")

        /**
         * The scope for a SIM [explain] serves: the profile, or for `default` and fill-only the SIM
         * key with its SPN (a brand name) or else its GID1 (a group identifier). Null when the SIM
         * gives neither: no record is keyed by the network alone.
         */
        fun of(explain: ExplainView, mcc: String, mnc: String, spn: String?, gid1: String?): RecordCarrier? = when {
            !explain.servedBySimKey -> Profile(explain.serving)
            !spn.isNullOrBlank() -> SimKeyed(mcc, mnc, null, spn)
            gid1 != null && HEX.matches(gid1) -> SimKeyed(mcc, mnc, gid1, null)
            else -> null
        }
    }
}

/** A record's target (spec 2.10). */
sealed interface RecordTarget {
    val text: String
    fun json(): JsonObject

    data class Config(val key: String) : RecordTarget {
        override val text get() = "config:$key"
        override fun json() = JsonObject(linkedMapOf("type" to JsonPrimitive("config"), "key" to JsonPrimitive(key)))
    }

    data class EmergencyData(val rowKey: String?) : RecordTarget {
        override val text get() = "emergency_data:${rowKey ?: "default"}"
        override fun json() = JsonObject(linkedMapOf("type" to JsonPrimitive("emergency_data"), "row_key" to (rowKey?.let(::JsonPrimitive) ?: JsonNull)))
    }
}

/** What a settled result is (spec 2.10 `result`). */
enum class RecordResult(val wire: String) {
    WORKS("works"), CALLS_OUT("calls_out"), SMS_BOTH_WAYS("sms_both_ways"), NO_REGRESSION_PASSED("no_regression_passed"),
    WINNER_AFTER_FAILURE("winner_after_failure"), NO_VALUE_PASSED("no_value_passed"),
    EMERGENCY_DATA_ACCEPTED("emergency_data_accepted"), EMERGENCY_DATA_REFUSED("emergency_data_refused"), EMERGENCY_DEFAULT_WON("emergency_default_won"),
}

/** The value a record serves (spec 2.10 `value_kind`). */
sealed interface RecordValue {
    data class Value(val value: JsonElement) : RecordValue
    data object AndroidDefault : RecordValue
    data object None : RecordValue
}

/** Everything a measured record needs; [MeasuredRecord.build] makes the record. */
data class RecordInput(
    val cell: RecordCell,
    val carrier: RecordCarrier,
    val target: RecordTarget,
    val value: RecordValue,
    val result: RecordResult,
    /** L2, L3, sms_both_ways, no_regression or emergency_data. */
    val level: String,
    /** mailbox_call, real_call, self_sms, observed, override_trial or eims_request. */
    val method: String,
    val bearer: String?,
    val n: Int?,
    val firstMeasuredMs: Long,
    val lastMeasuredMs: Long,
    val basis: String,
    val dataCommit: String,
    val dataDigest: String,
)

object MeasuredRecord {
    val VALID_DAYS = 90L
    val LEVELS = setOf("L2", "L3", "sms_both_ways", "no_regression", "emergency_data")
    val METHODS = setOf("mailbox_call", "real_call", "mailbox_call+real_call", "self_sms", "observed", "override_trial", "eims_request")
    val BEARERS = setOf("lte", "nr", "iwlan")
    val KEY = Regex("r:[0-9a-f]{16}")

    /** The members of a record, in the spec's order. */
    val MEMBERS = listOf(
        "record_key", "kind", "device", "rom", "ims_stack", "carrier", "target", "value_kind", "value", "claim", "label", "basis",
        "level", "result", "method", "bearer", "n", "first_measured", "last_measured", "valid_until", "data", "tester",
        "differs_between_sims", "second_agent_read",
    )

    /** record_key (spec 2.10): "r:" and 16 hex digits of SHA-256 over the nine lines. */
    fun key(kind: String, cell: RecordCell, carrier: RecordCarrier, target: RecordTarget): String {
        val text = listOf(
            kind, cell.codename, cell.model.uppercase(Locale.ROOT), cell.romFamily, cell.romLine, cell.imsStack, cell.fork.orEmpty(),
            carrier.text, target.text,
        ).joinToString("\n")
        return "r:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex().take(16)
    }

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    fun build(i: RecordInput): JsonObject {
        require(i.level in LEVELS) { "level ${i.level}" }
        require(i.method in METHODS) { "method ${i.method}" }
        require(i.bearer == null || i.bearer in BEARERS) { "bearer ${i.bearer}" }
        val last = LocalDate.parse(day(i.lastMeasuredMs))
        val (kind, value) = when (val v = i.value) {
            is RecordValue.Value -> "value" to v.value
            RecordValue.AndroidDefault -> "android_default" to JsonNull
            RecordValue.None -> "none" to JsonNull
        }
        val m = linkedMapOf<String, JsonElement>(
            "record_key" to JsonPrimitive(key("measured", i.cell, i.carrier, i.target)),
            "kind" to JsonPrimitive("measured"),
            "device" to JsonObject(linkedMapOf("codename" to JsonPrimitive(i.cell.codename), "model" to JsonPrimitive(i.cell.model))),
            "rom" to JsonObject(linkedMapOf("family" to JsonPrimitive(i.cell.romFamily), "line" to JsonPrimitive(i.cell.romLine))),
            "ims_stack" to JsonObject(
                linkedMapOf(
                    "id" to JsonPrimitive(i.cell.imsStack),
                    "fork" to (i.cell.fork?.let(::JsonPrimitive) ?: JsonNull),
                    "version" to (i.cell.stackVersion?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ),
            "carrier" to i.carrier.json(),
            "target" to i.target.json(),
            "value_kind" to JsonPrimitive(kind),
            "value" to value,
            "claim" to JsonPrimitive("measured"),
            "label" to JsonNull,
            "basis" to JsonPrimitive(i.basis.take(500)),
            "level" to JsonPrimitive(i.level),
            "result" to JsonPrimitive(i.result.wire),
            "method" to JsonPrimitive(i.method),
            "bearer" to (i.bearer?.let(::JsonPrimitive) ?: JsonNull),
            "n" to (i.n?.let(::JsonPrimitive) ?: JsonNull),
            "first_measured" to JsonPrimitive(day(i.firstMeasuredMs)),
            "last_measured" to JsonPrimitive(last.toString()),
            "valid_until" to JsonPrimitive(last.plusDays(VALID_DAYS).toString()),
            "data" to JsonObject(linkedMapOf("commit" to JsonPrimitive(i.dataCommit), "data_digest" to JsonPrimitive(i.dataDigest))),
            "tester" to JsonPrimitive("owner"),
            "differs_between_sims" to JsonNull,
            "second_agent_read" to JsonNull,
        )
        return JsonObject(m)
    }

    fun text(record: JsonObject): String = Json.encodeToString(JsonObject.serializer(), record)

    fun recordKey(record: JsonObject): String = (record["record_key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun targetKey(record: JsonObject): String? = ((record["target"] as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull
    fun lastMeasured(record: JsonObject): String = (record["last_measured"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun result(record: JsonObject): String = (record["result"] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/** What may become a record, checked here before CarrierSettings checks it again (spec 6.4). */
object RecordCheck {
    /** The fixed-shape hex fields of a record, which skip the privacy scan's phone heuristics. */
    val HEX_FIELDS = setOf("record.record_key", "record.data.commit", "record.data.data_digest", "record.target.row_key", "record.carrier.gid1")

    /**
     * Why [record] must not be written, or null. [candidates]: the values a config record may
     * hold for its key (the stack's resolved value, the stack profiles' alternatives, the Google
     * value, a bool's other value); `android_default` and `none` need none. [datasetA]: the
     * dataset's own group A marking.
     */
    fun problem(record: JsonObject, candidates: List<JsonElement>, type: String?, datasetA: Set<String>, todayMs: Long): String? {
        if (record.keys.toList() != MeasuredRecord.MEMBERS) return "the record's members are not the spec's"
        if ((record["kind"] as? JsonPrimitive)?.contentOrNull != "measured") return "only measured records are written"
        if (!MeasuredRecord.KEY.matches(MeasuredRecord.recordKey(record))) return "record_key shape"
        val target = record["target"] as? JsonObject ?: return "no target"
        val valueKind = (record["value_kind"] as? JsonPrimitive)?.contentOrNull
        when ((target["type"] as? JsonPrimitive)?.contentOrNull) {
            "config" -> {
                val key = MeasuredRecord.targetKey(record) ?: return "no key"
                Groups.untestable(key, datasetA)?.let { return it }
                when (valueKind) {
                    "value" -> {
                        val v = record["value"]
                        if (type != null && !TypedValues.fits(type, v)) return "the value is not a $type"
                        if (candidates.none { TypedValues.same(it, v) }) return "the value is not one OCD knows for this key and profile"
                    }
                    "android_default", "none" -> if (record["value"] !is JsonNull) return "$valueKind holds no value"
                    else -> return "value_kind $valueKind"
                }
            }
            "emergency_data" -> if (valueKind != "none") return "an emergency data record holds no value"
            else -> return "this app writes config and emergency data records only"
        }
        val until = (record["valid_until"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "no valid_until"
        if (until.isBefore(LocalDate.parse(MeasuredRecord.day(todayMs)))) return "expired"
        val hits = PrivacyScan.scan(record, "record", HEX_FIELDS)
        if (hits.isNotEmpty()) return "the privacy scan refused ${hits.joinToString { "${it.path} (${it.rule})" }}"
        if (((record["data"] as? JsonObject)?.get("commit") as? JsonPrimitive)?.contentOrNull?.let(DataSource.SHA::matches) != true) return "no data commit"
        return null
    }
}

/** A record this app sent to main, for "send again only when it changed, or every 14 days". */
@Serializable
data class SentRecord(val recordKey: String, val lastMeasured: String, val result: String, val atMs: Long, val issue: Int? = null)

/**
 * The issue a measured record goes to main in: one record per issue, opened with the owner's
 * token, labelled for the data side's owner-only intake. The record is the JSON block, exactly as
 * put_record took it; the data side folds it into generated/devices/device-records.json.
 */
object RecordReport {
    const val LABEL = "ocd-device-record"
    const val TITLE_PREFIX = "[OCD device record] "
    val RENEW_MS: Long = TimeUnit.DAYS.toMillis(14)
    const val MAX_SENT = 200

    data class Issue(val title: String, val body: String, val recordKey: String)

    fun issue(record: JsonObject): Issue {
        val target = (record["target"] as? JsonObject)
        val what = MeasuredRecord.targetKey(record) ?: (target?.get("type") as? JsonPrimitive)?.contentOrNull ?: "record"
        val carrier = record["carrier"] as? JsonObject
        val scope = (carrier?.get("profile_id") as? JsonPrimitive)?.contentOrNull
            ?: listOfNotNull((carrier?.get("mcc") as? JsonPrimitive)?.contentOrNull, (carrier?.get("mnc") as? JsonPrimitive)?.contentOrNull).joinToString("-")
        val model = ((record["device"] as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val title = "$TITLE_PREFIX${MeasuredRecord.result(record)}: $what for $scope on $model".take(240)
        val body = buildString {
            append("### Record\n\n```json\n")
            append(MeasuredRecord.text(record))
            append("\n```\n\n### Privacy check\n\n")
            append("- [X] The OCD Check app's privacy scan passed: the record holds no IMSI, ICCID, IMEI, phone number, SIP URI or IP address.\n")
        }
        return Issue(title, body, MeasuredRecord.recordKey(record))
    }

    /** Records not sent yet, sent with another result or measurement day, or sent more than 14 days ago. */
    fun due(records: List<JsonObject>, sent: List<SentRecord>, nowMs: Long): List<JsonObject> = records.filter { r ->
        val key = MeasuredRecord.recordKey(r)
        sent.none { it.recordKey == key && it.lastMeasured == MeasuredRecord.lastMeasured(r) && it.result == MeasuredRecord.result(r) && nowMs - it.atMs in 0 until RENEW_MS }
    }

    fun record(sent: List<SentRecord>, r: JsonObject, nowMs: Long, issue: Int?): List<SentRecord> =
        (sent.filterNot { it.recordKey == MeasuredRecord.recordKey(r) } + SentRecord(MeasuredRecord.recordKey(r), MeasuredRecord.lastMeasured(r), MeasuredRecord.result(r), nowMs, issue))
            .sortedBy { it.atMs }.takeLast(MAX_SENT)
}

/**
 * A source review the app opens on OCD's public repository with the owner's token (spec 12.3): a
 * "no value passed", an APN shrink refusal, or a coupling mismatch. Plain words, the profile, the
 * key or row, the data commit; never a SIM identifier. Opened once per [key].
 */
@Serializable
data class SourceReview(val key: String, val title: String, val body: String, val atMs: Long) {
    companion object {
        const val LABEL = "source-review"
        const val TITLE_PREFIX = "[source review] "
        const val MAX = 40

        fun noValuePassed(profile: String, key: String, failing: JsonElement?, tried: List<Candidate>, commit: String, reason: String, nowMs: Long) = SourceReview(
            "no_value_passed|$profile|$key|${commit.take(12)}",
            "${TITLE_PREFIX}no value passed: $key for $profile",
            "The OCD Check owner build measured a failure with OCD's value of `$key` for `$profile` (data commit `$commit`), " +
                "and no other value passed under the same conditions, so the newest value stays and the test repeats in the next idle moment.\n\n" +
                "- Value served: `${failing ?: "Android's default"}`\n- Failure: ${reason.take(300)}\n" +
                "- Values tried: ${tried.joinToString { "`${it.plain}` (${it.origin})" }.ifEmpty { "none known" }}\n",
            nowMs,
        )

        fun install(code: String, detail: String, commit: String, nowMs: Long) = SourceReview(
            "install|$code|${commit.take(12)}",
            "${TITLE_PREFIX}$code at main ${commit.take(12)}",
            "CarrierSettings refused the dataset of main `$commit` with `$code`: ${detail.take(600)}\n",
            nowMs,
        )

        fun coupling(profile: String, commit: String, nowMs: Long) = SourceReview(
            "coupling|$profile|${commit.take(12)}",
            "${TITLE_PREFIX}APN coupling mismatch for $profile",
            "For a SIM served by `$profile` at main `$commit`, the APN rows TelephonyProvider selects do not all belong to that profile (coupling `mismatch`, spec 4.6).\n",
            nowMs,
        )
    }
}
