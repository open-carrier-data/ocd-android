package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.core.casefold
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/*
 * OCD's rule rounds 32 to 36: the APN set candidates a tester phone tries after a measured failure.
 *
 * Round 32, D8 rule 4: after a measured data-validation, attach or MMSC-probe failure on a lead
 * whose stored row carries a credential fill-in, the set without the filling row is the first
 * candidate; on a lead rule 1 left bare, the set with the left-out row restored. D7 rule 4: on a
 * SIM matched to a kept D7-class scope (an MVNO scope only old Samsung builds state), the plain
 * scope's rows; on a SIM whose scope rule 1 left out, the left-out scope's rows. Rounds 33, 34 and
 * 36 add their restore candidates to the same path: a type put back on a row the generator retyped
 * (MMS in round 33 and on 310/120's n.ispsn in round 36, IMS in round 34), a row it left out
 * restored (404/58's bsnlnet IMS row, 250/20's XCAP row), and the earlier attach or MMS order
 * (310/004).
 *
 * The candidates are computed from OCD's own published data, at the installed commit, generically:
 * - the installed apns-conf.xml and apn-index.json give the rows the generator wrote for the SIM's
 *   profile, each with its row key (apn-index's "a:" plus 16 hex of SHA-256 over the attributes);
 * - the profile's JSON (carriers/open/<profile>.json) gives every row its sources state, which the
 *   generator turns into XML rows exactly as [ProfileApnRows] does before any of its rules runs;
 * - a profile row no written row covers (same TelephonyProvider settings, [ApnRowKeys.settingsKey])
 *   was left out; a profile row whose settings a written row carries without one of its types was
 *   retyped. On public main 7247b602 that classifies 25,035 of the 26,003 profile rows as covered,
 *   548 as retyped (ia 496, emergency 35, mms 14, ims 3) and 420 as left out (emergency 354, mms 63,
 *   one xcap, one ims, one internet row), the generator's own omission counts.
 *
 * A candidate is a set of operations on the installed commit's file for one SIM ([ApnOp]); it never
 * touches an emergency row, never adds a wildcard or empty type, and leaves every other SIM's rows
 * alone. CarrierSettings composes it, checks it, installs it as a temporary set and restores the
 * installed commit's file (round 31 item 6's path, [ApnCandidateProtocol]). Everything here is pure.
 */

/** The lead a measured failure is on, by the APN type that serves it. */
@Serializable
enum class LeadRole(val wire: String, val type: String, val plain: String) {
    INTERNET("internet", "default", "mobile data"),
    ATTACH("attach", "ia", "the attach"),
    MMS("mms", "mms", "MMS"),
    IMS("ims", "ims", "IMS"),
    XCAP("xcap", "xcap", "XCAP"),
}

/** A measured failure that opens the candidate path, and the check a candidate must pass instead. */
@Serializable
enum class LeadFailure(val role: LeadRole, val wire: String, val plain: String, val check: String) {
    DATA_VALIDATION(LeadRole.INTERNET, "data_validation", "mobile data was not validated", "mobile data validated"),
    MMSC_PROBE(LeadRole.MMS, "mmsc_probe", "the MMS access point did not answer the free probe", "the MMS access point answers the free probe (no MMS is sent)"),
    IMS(LeadRole.IMS, "ims", "IMS lost its registration", "IMS registered with the same services"),
}

/**
 * One change of a candidate set, applied by CarrierSettings to the installed commit's apns-conf.xml.
 * - `omit`: leave out the row [rowKey];
 * - `insert`: add a row with [attributes] (as apns-conf.xml writes them), right before [before], or
 *   after the last row of the SIM's scope when [before] is null;
 * - `set_types`: write the row [rowKey] with the type attribute [types];
 * - `unset`: write the row [rowKey] without the attributes [fields];
 * - `order`: the rows [rowKeys] take this order among the places they hold.
 */
@Serializable
data class ApnOp(
    val op: String,
    @SerialName("row_key") val rowKey: String? = null,
    val attributes: Map<String, String>? = null,
    val before: String? = null,
    val types: String? = null,
    val fields: List<String>? = null,
    @SerialName("row_keys") val rowKeys: List<String>? = null,
) {
    companion object {
        const val OMIT = "omit"
        const val INSERT = "insert"
        const val SET_TYPES = "set_types"
        const val UNSET = "unset"
        const val ORDER = "order"
    }
}

/** One non-persistent candidate set for one SIM. [id] is stable for the same data, so a candidate is tried once per data. */
@Serializable
data class ApnSetCandidate(
    val id: String,
    /** Which ruling adds it: "round 32 D8 rule 4", "round 33 step 6", "round 34 step 7", "round 36", "round 32 D7 rule 4". */
    val rule: String,
    val kind: String,
    val role: LeadRole,
    val plain: String,
    val ops: List<ApnOp>,
) {
    companion object {
        const val WITHOUT_FILL = "without_fill"
        const val RESTORE_LEFT_OUT = "restore_left_out"
        const val TYPE_BACK = "type_back"
        const val EARLIER_ORDER = "earlier_order"
        const val PLAIN_SCOPE = "plain_scope"
        const val LEFT_OUT_SCOPE = "left_out_scope"
    }
}

/** The generator's keys for an APN row, from its attributes as apns-conf.xml writes them (XML-unescaped). */
object ApnRowKeys {
    /** apn-index.json's row key: "a:" and the first 16 hex digits of SHA-256 over the attributes sorted by name, each name=value, joined with a newline. */
    fun rowKey(attributes: Map<String, String>): String {
        val text = attributes.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return "a:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** TelephonyProvider's column defaults (the generator's ANDROID_APN_DEFAULTS): an attribute equal to one stores nothing different. */
    val SETTINGS_DEFAULTS: Map<String, String> = mapOf(
        "protocol" to "IP", "roaming_protocol" to "IP", "authtype" to "-1", "carrier_enabled" to "true", "bearer" to "0",
        "profile_id" to "0", "user_visible" to "true", "user_editable" to "true", "apn_set_id" to "0", "skip_464xlat" to "-1",
        "mtu" to "0", "mtu_v4" to "0", "mtu_v6" to "0", "max_conns" to "0", "max_conns_time" to "0", "wait_time" to "0",
        "modem_cognitive" to "false", "always_on" to "false", "esim_bootstrap_provisioning" to "false",
    )

    /** Everything TelephonyProvider stores for a row except its label and types (android_settings_key): rows with the same key are one APN. */
    fun settingsKey(attributes: Map<String, String>): String = attributes.toSortedMap().entries
        .filter { (k, v) -> k != "carrier" && k != "type" && !(SETTINGS_DEFAULTS[k]?.equals(v, ignoreCase = true) ?: false) }
        .joinToString("\u0001") { "${it.key}=${it.value}" }

    /** TelephonyProvider's unique fields with their defaults (ANDROID_UNIQUE_DEFAULTS): rows equal on them are stored as one row. */
    val UNIQUE_DEFAULTS: Map<String, String> = linkedMapOf(
        "mcc" to "", "mnc" to "", "apn" to "", "proxy" to "", "port" to "", "mmsproxy" to "", "mmsport" to "", "mmsc" to "",
        "carrier_enabled" to "1", "bearer" to "0", "mvno_type" to "", "mvno_match_data" to "", "profile_id" to "0",
        "protocol" to "IP", "roaming_protocol" to "IP", "user_editable" to "1", "apn_set_id" to "0", "carrier_id" to "-1",
        "infrastructure_bitmask" to "3", "esim_bootstrap_provisioning" to "0",
    )
    private val INFRASTRUCTURE_BITS = mapOf("cellular" to 1, "satellite" to 2)

    /** The row's unique-field values as TelephonyProvider compares them (android_unique_key). */
    fun uniqueKey(attributes: Map<String, String>): List<String> {
        val mvno = "mvno_type" in attributes && "mvno_match_data" in attributes
        return UNIQUE_DEFAULTS.map { (field, default) ->
            val value = attributes[field]?.takeIf { mvno || field !in setOf("mvno_type", "mvno_match_data") }
            when {
                value == null -> default
                field == "infrastructure_bitmask" -> value.split('|').sumOf { INFRASTRUCTURE_BITS[it.trim()] ?: 0 }.toString()
                field in setOf("carrier_enabled", "user_editable", "esim_bootstrap_provisioning") -> when (value.lowercase()) {
                    "true" -> "1"
                    "false" -> "0"
                    else -> value
                }
                else -> value
            }
        }
    }

    fun types(attributes: Map<String, String>): List<String> = attributes["type"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}

/**
 * The generator's apn_records for one profile JSON (carriers/open/<id>.json): every row its sources
 * state, as an apns-conf.xml row before any of the generator's rules (ranking, leave-outs, retypes).
 * The label is the row's name, else the profile's display name (120 characters at most); the
 * network codes and the MVNO selector come from the profile's match; a row's own selector must be
 * the profile's; a carrier ID row is written once per ID.
 */
object ProfileApnRows {
    private val COPIED = listOf(
        "mmsc", "mmsproxy", "mmsport", "protocol", "roaming_protocol", "user", "password", "authtype", "proxy", "port", "server",
        "bearer", "bearer_bitmask", "network_type_bitmask", "lingering_network_type_bitmask", "infrastructure_bitmask", "mtu",
        "mtu_v4", "mtu_v6", "user_visible", "user_editable", "carrier_enabled", "profile_id", "apn_set_id", "skip_464xlat",
        "modem_cognitive", "always_on", "esim_bootstrap_provisioning", "max_conns", "max_conns_time", "wait_time",
    )

    private fun strings(o: JsonObject?, key: String): List<String> =
        (o?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it.isNotEmpty() }

    private fun text(p: JsonPrimitive): String = if (p.isString) p.content else p.booleanOrNull?.let { if (it) "true" else "false" } ?: p.content

    /** The profile's MVNO selectors as apns-conf.xml writes them; [listOf(null)] for a plain scope, empty when no row can be written. */
    fun mvnos(match: JsonObject?): List<Pair<String, String>?> {
        if (strings(match, "gid2_prefixes").isNotEmpty()) return emptyList()
        val dims = listOf(
            strings(match, "imsi_prefix_patterns").map { "imsi" to it.lowercase() },
            strings(match, "spn").map { "spn" to it },
            strings(match, "gid1_prefixes").map { "gid" to it.uppercase() },
            strings(match, "iccid_prefixes").map { "iccid" to it },
        ).filter { it.isNotEmpty() }
        return when (dims.size) {
            0 -> listOf(null)
            1 -> dims.single()
            else -> emptyList()
        }
    }

    fun records(profile: JsonObject): List<Map<String, String>> {
        val match = profile["match"] as? JsonObject
        val profileMvnos = mvnos(match)
        if (profileMvnos.isEmpty()) return emptyList()
        val carrierIds = (match?.get("android_carrier_ids") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull }.distinct().sorted()
        val codes = strings(match, "mccmnc").filter { it.length == 5 || it.length == 6 }
        val display = (profile["display_name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(120)
        val out = mutableListOf<Map<String, String>>()
        for (element in (profile["android_apns"] as? JsonArray).orEmpty()) {
            val apn = element as? JsonObject ?: continue
            val cidElement = apn["carrier_id"] as? JsonPrimitive
            if (cidElement != null && (cidElement.isString || cidElement.booleanOrNull != null || cidElement.longOrNull == null)) continue
            val apnCarrierId = cidElement?.longOrNull
            val selector = apnCarrierId?.takeIf { it >= 0 }
            if (selector != null && carrierIds.isNotEmpty() && selector !in carrierIds) continue
            val effective = if (selector != null) listOf(selector) else carrierIds
            val name = (apn["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val apnName = (apn["apn"] as? JsonPrimitive)?.contentOrNull ?: continue
            val types = (apn["types"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val base = linkedMapOf("carrier" to (if (name.isNotEmpty()) name.take(120) else display), "apn" to apnName, "type" to types.joinToString(","))
            for (k in COPIED) (apn[k] as? JsonPrimitive)?.let { base[k] = text(it) }
            if (apnCarrierId == -1L) base["carrier_id"] = "-1"
            if (codes.isEmpty()) {
                for (id in effective) out += base + ("carrier_id" to id.toString())
                continue
            }
            val rowMvno = (apn["mvno_type"] as? JsonPrimitive)?.contentOrNull
            val rowData = (apn["mvno_match_data"] as? JsonPrimitive)?.contentOrNull
            val mvnos: List<Pair<String, String>?> = when {
                rowMvno == null && rowData == null -> profileMvnos
                rowMvno == null || rowData == null -> emptyList()
                profileMvnos == listOf(null) -> listOf(rowMvno to rowData)
                profileMvnos.filterNotNull().none { it.first.casefold() == rowMvno.casefold() && it.second.casefold() == rowData.casefold() } -> emptyList()
                else -> listOf(rowMvno to rowData)
            }
            for (code in codes) for (mvno in mvnos) {
                val record = LinkedHashMap(base)
                record["mcc"] = code.take(3)
                record["mnc"] = code.drop(3)
                if (mvno != null) {
                    record["mvno_type"] = mvno.first
                    record["mvno_match_data"] = mvno.second
                }
                if (effective.isEmpty()) out += record else for (id in effective) out += record + ("carrier_id" to id.toString())
            }
        }
        return out
    }
}

/** Android's APN selection for a SIM: the MVNO rows that match it when any do, else the network's plain rows. */
object ApnSelection {
    fun of(rows: List<OcdApnRow>, sim: SimIdentity): List<OcdApnRow> {
        val mine = rows.filter { it.numeric == sim.mccmnc && ApnConf.match(it, sim) == ApnMatch.MATCHES }
        val mvno = mine.filter { it.mvnoType != null }
        return mvno.ifEmpty { mine.filter { it.mvnoType == null } }
    }
}

/** What the candidates are computed from, for one SIM and one failing lead. */
data class CandidateInput(
    val role: LeadRole,
    /** Every row of the installed apns-conf.xml for the SIM's network code, in file order. */
    val networkRows: List<OcdApnRow>,
    /** The SIM's selection among them ([ApnSelection]). */
    val selection: List<OcdApnRow>,
    /** The rows the generator wrote for the SIM's profile (apn-index), in file order. */
    val written: List<OcdApnRow>,
    /** The profile's rows before the generator's rules ([ProfileApnRows]); empty when its JSON was not read. */
    val profileRows: List<Map<String, String>> = emptyList(),
    /** The SIM's role rows in the order the build's dataset selects them (explain with dataset=build), by row key; null when unknown. */
    val buildOrder: List<String>? = null,
    /** The SIM's profile is an MVNO scope old enough to be D7-class (its newest entry over three years before the data). */
    val oldMvnoScope: Boolean = false,
    /** The rows of profiles the data left out (D7 rule 1, old_selector_superseded) whose selector this SIM matches. */
    val leftOutScopeRows: List<Map<String, String>> = emptyList(),
)

object ApnCandidates {
    /** The fields a fill-in carries (D8 class (a)). */
    val FILL_FIELDS = listOf("user", "password", "authtype", "mtu")

    /** Never part of a candidate: an emergency row (S1-A and the emergency data test), the wildcard, an empty type. */
    fun touchable(attributes: Map<String, String>): Boolean {
        val types = ApnRowKeys.types(attributes)
        return types.isNotEmpty() && "*" !in types && "emergency" !in types && attributes["carrier_enabled"]?.lowercase() != "false"
    }

    fun serves(attributes: Map<String, String>, type: String): Boolean = type in ApnRowKeys.types(attributes)

    private fun id(kind: String, ops: List<ApnOp>): String {
        val text = kind + "|" + Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApnOp.serializer()), ops)
        return "c:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun candidate(rule: String, kind: String, role: LeadRole, plain: String, ops: List<ApnOp>) = ApnSetCandidate(id(kind, ops), rule, kind, role, plain, ops)

    /**
     * D8 rule 4's first candidate: the SIM's rows that TelephonyProvider stores as one row serving
     * [role] (equal unique fields, the last row's values written over the earlier ones), where the
     * last row sets no username, password, auth type or MTU and an earlier row does. The candidate
     * leaves out each filling row whose only effect is those fields, and drops just those fields from
     * a filling row that also adds a type. Null when the stored lead row carries no fill-in.
     */
    fun withoutFill(input: CandidateInput): ApnSetCandidate? {
        val groups = input.selection.groupBy { ApnRowKeys.uniqueKey(it.attributes) }.values
        val ops = mutableListOf<ApnOp>()
        val labels = mutableListOf<String>()
        for (group in groups) {
            if (group.size < 2 || group.none { serves(it.attributes, input.role.type) }) continue
            if (group.any { !touchable(it.attributes) }) continue
            val best = group.last()
            val filled = FILL_FIELDS.filter { f -> f !in best.attributes && group.dropLast(1).any { f in it.attributes } }
            if (filled.isEmpty()) continue
            for (row in group.dropLast(1).filter { r -> filled.any { it in r.attributes } }) {
                val others = (group - row).flatMap { ApnRowKeys.types(it.attributes) }.toSet()
                val onlyFill = ApnRowKeys.types(row.attributes).all { it in others }
                ops += if (onlyFill) ApnOp(ApnOp.OMIT, rowKey = ApnRowKeys.rowKey(row.attributes))
                else ApnOp(ApnOp.UNSET, rowKey = ApnRowKeys.rowKey(row.attributes), fields = filled.filter { it in row.attributes })
            }
            labels += "${best.apn} (${filled.joinToString("/")} filled in)"
        }
        if (ops.isEmpty()) return null
        return candidate("round 32 D8 rule 4", ApnSetCandidate.WITHOUT_FILL, input.role, "the set without the filling row: ${labels.joinToString()}", ops)
    }

    /**
     * The profile's rows serving [role] that no written row covers (left out by the generator): D8
     * rule 1's superseded Google copies, round 33's MMS rows, round 34's 404/58 bsnlnet IMS row and
     * 250/20's XCAP row. Each is restored where TelephonyProvider would store it: right before the
     * written row it shares the unique fields with, so that row's values still win, else after the
     * scope's last row. One candidate per left-out row.
     */
    fun restoreLeftOut(input: CandidateInput): List<ApnSetCandidate> {
        val covered = input.written.groupBy { ApnRowKeys.settingsKey(it.attributes) }.mapValues { (_, rows) -> rows.flatMap { ApnRowKeys.types(it.attributes) }.toSet() }
        val present = input.networkRows.map { ApnRowKeys.rowKey(it.attributes) }.toSet()
        val out = mutableListOf<ApnSetCandidate>()
        for (record in input.profileRows.distinctBy { ApnRowKeys.rowKey(it) }) {
            if (!touchable(record) || !serves(record, input.role.type)) continue
            if (ApnRowKeys.settingsKey(record) in covered || ApnRowKeys.rowKey(record) in present) continue
            val unique = ApnRowKeys.uniqueKey(record)
            val anchor = input.selection.lastOrNull { ApnRowKeys.uniqueKey(it.attributes) == unique }
            val credentials = FILL_FIELDS.filter { it in record }
            val rule = when {
                anchor != null && credentials.isNotEmpty() && input.role == LeadRole.INTERNET -> "round 32 D8 rule 4"
                input.role == LeadRole.MMS -> "round 33 step 6"
                else -> "round 34 step 7"
            }
            val ops = listOf(ApnOp(ApnOp.INSERT, attributes = record.toSortedMap(), before = anchor?.let { ApnRowKeys.rowKey(it.attributes) }))
            out += candidate(rule, ApnSetCandidate.RESTORE_LEFT_OUT, input.role, "the left-out row restored: ${record["apn"]} [${record["type"]}]" + if (credentials.isNotEmpty()) " with its ${credentials.joinToString("/")}" else "", ops)
        }
        return out
    }

    /**
     * A written row of the SIM's selection whose profile row carries [role]'s type that the generator
     * took off it (round 33's MMS, round 34's IMS, round 36's 310/120 n.ispsn MMS): the type put back.
     */
    fun typeBack(input: CandidateInput): List<ApnSetCandidate> {
        val type = input.role.type
        val wanted = input.profileRows.filter { touchable(it) && serves(it, type) }.map { ApnRowKeys.settingsKey(it) }.toSet()
        val out = mutableListOf<ApnSetCandidate>()
        for (row in input.selection) {
            if (!touchable(row.attributes) || serves(row.attributes, type)) continue
            if (row !in input.written || ApnRowKeys.settingsKey(row.attributes) !in wanted) continue
            val types = (ApnRowKeys.types(row.attributes) + type).distinct().joinToString(",")
            val ops = listOf(ApnOp(ApnOp.SET_TYPES, rowKey = ApnRowKeys.rowKey(row.attributes), types = types))
            val rule = when (input.role) {
                LeadRole.MMS -> if (row.numeric == "310120") "round 36" else "round 33 step 6"
                else -> "round 34 step 7"
            }
            out += candidate(rule, ApnSetCandidate.TYPE_BACK, input.role, "${input.role.plain} put back on ${row.apn} [${row.types.joinToString(",")}]", ops)
        }
        return out
    }

    /** Round 34's earlier order (310/004's attach and MMS order): the SIM's rows serving [role] in the order the build's dataset gives them, where the installed file orders the same rows otherwise. */
    fun earlierOrder(input: CandidateInput): ApnSetCandidate? {
        val build = input.buildOrder ?: return null
        val now = input.selection.filter { serves(it.attributes, input.role.type) && touchable(it.attributes) }.map { ApnRowKeys.rowKey(it.attributes) }
        if (now.size < 2 || build.toSet() != now.toSet() || build == now) return null
        return candidate("round 34 step 7", ApnSetCandidate.EARLIER_ORDER, input.role, "the earlier ${input.role.plain} order, as the ROM's own data gives it", listOf(ApnOp(ApnOp.ORDER, rowKeys = build)))
    }

    /**
     * D7 rule 4 for a kept D7-class scope: the plain scope's rows under this SIM's selector. The MVNO
     * scope's rows leave, except its emergency rows, which stay as they are; each plain row serving a
     * type the scope served is added with the scope's selector. Null when the SIM is on a plain scope.
     */
    fun plainScope(input: CandidateInput): ApnSetCandidate? {
        if (!input.oldMvnoScope) return null
        val scope = input.selection.filter { it.mvnoType != null }
        if (scope.isEmpty()) return null
        val selector = scope.first().let { it.attributes["mvno_type"] to it.attributes["mvno_match_data"] }
        val plain = input.networkRows.filter { it.mvnoType == null && touchable(it.attributes) && it.carrierId == null }
        if (plain.none { serves(it.attributes, input.role.type) }) return null
        val ops = scope.filter { touchable(it.attributes) }.map { ApnOp(ApnOp.OMIT, rowKey = ApnRowKeys.rowKey(it.attributes)) } +
            plain.map { row -> ApnOp(ApnOp.INSERT, attributes = (row.attributes + mapOf("mvno_type" to selector.first.orEmpty(), "mvno_match_data" to selector.second.orEmpty())).toSortedMap()) }
        return candidate("round 32 D7 rule 4", ApnSetCandidate.PLAIN_SCOPE, input.role, "the plain scope's rows under this SIM's selector", ops)
    }

    /** D7 rule 4 for a SIM whose own scope the data left out: that scope's rows restored, without any emergency row of its own. */
    fun leftOutScope(input: CandidateInput): ApnSetCandidate? {
        val rows = input.leftOutScopeRows.filter { touchable(it) }.distinctBy { ApnRowKeys.rowKey(it) }
        if (rows.none { serves(it, input.role.type) }) return null
        // The SIM's emergency rows stay the same: copies under the restored selector, so Android keeps selecting them.
        val selector = rows.first().let { it["mvno_type"] to it["mvno_match_data"] }
        val emergency = input.selection.filter { "emergency" in ApnRowKeys.types(it.attributes) && it.mvnoType == null }
            .map { (it.attributes + mapOf("mvno_type" to selector.first.orEmpty(), "mvno_match_data" to selector.second.orEmpty())).toSortedMap() }
        val ops = (rows.map { it.toSortedMap() } + emergency).map { ApnOp(ApnOp.INSERT, attributes = it) }
        return candidate("round 32 D7 rule 4", ApnSetCandidate.LEFT_OUT_SCOPE, input.role, "the left-out scope's rows restored (${selector.first} ${selector.second})", ops)
    }

    /**
     * Every candidate for one measured failure, in the order the rulings give: the SIM's left-out
     * scope or its plain scope first (D7), then the set without the filling row (D8, when the
     * stored lead carries a fill-in), then the left-out rows restored, the types put back, and the
     * earlier order. Never two candidates with the same operations.
     */
    fun forFailure(input: CandidateInput): List<ApnSetCandidate> =
        (listOfNotNull(leftOutScope(input), plainScope(input), withoutFill(input)) + restoreLeftOut(input) + typeBack(input) + listOfNotNull(earlierOrder(input)))
            .distinctBy { it.id }
}

/** Whether a candidate may run now (D8 and D7 rule 4: tester phones only, within the caps), or why not. */
object ApnCandidateGate {
    /** A candidate costs two OCD-caused IMS re-registrations: the set and its restore. */
    const val REREGISTRATIONS = 2

    /** At most this many candidates per measured failure in one run. */
    const val MAX_PER_FAILURE = 3

    data class Inputs(
        val status: DatasetStatus,
        val activeTests: Boolean,
        val roaming: Boolean?,
        val reregistrationsLeft: Int,
        val stop: String?,
        val failure: LeadFailure,
        /** The SIM's network is on the MMS-ended list (an MMSC probe failure there opens nothing). */
        val mmsEnded: Boolean = false,
    )

    fun refusal(i: Inputs): String? = when {
        !i.status.round31 -> "this ROM build has no installer for temporary APN sets"
        StackCalibration.notCounted(i.status.build.imsStackId) != null -> "the tests are not calibrated on this IMS stack (SM8); a failure here starts no candidate"
        !i.activeTests -> "the active tests are off on this phone"
        i.roaming != false -> if (i.roaming == null) "roaming is not readable" else "the phone is roaming; candidates run only on the home network"
        i.stop != null -> "tests and changes are paused: ${i.stop}"
        i.status.changesHalted.on -> "CarrierSettings halted changes"
        i.status.voiceLoss.hold || i.status.voiceLoss.stop -> "the voice-loss hold or stop is in place"
        i.status.apnTestSet -> "a temporary APN set is already in place"
        !i.status.servingInstalled -> "the ROM's own data serves; the candidates are for the installed data"
        i.failure == LeadFailure.MMSC_PROBE && i.mmsEnded -> "the network ended MMS"
        i.reregistrationsLeft < REREGISTRATIONS -> "the hourly or daily cap of IMS re-registrations is reached"
        else -> null
    }
}
