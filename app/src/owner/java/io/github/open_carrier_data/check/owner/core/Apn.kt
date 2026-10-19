package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.core.casefold
import kotlinx.serialization.Serializable

/*
 * OCD's APN rows for one SIM, from the release's apns-conf.xml (lookup.json
 * carries none), and the phone's own rows from the telephony provider. The
 * owner build tests OCD's internet (default-type) rows: it inserts a missing
 * one, makes it the preferred APN, measures whether mobile data comes up
 * validated, and removes it again unless it is the best working value.
 * Emergency rows are never touched.
 */

/** One `<apn .../>` element of OCD's apns-conf.xml, attributes as written. */
@Serializable
data class OcdApnRow(val attributes: Map<String, String>) {
    val mcc: String get() = attributes["mcc"].orEmpty()
    val mnc: String get() = attributes["mnc"].orEmpty()
    val numeric: String get() = mcc + mnc
    val apn: String get() = attributes["apn"].orEmpty()
    val name: String? get() = attributes["carrier"]
    val types: List<String> get() = attributes["type"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val protocol: String? get() = attributes["protocol"]?.uppercase()
    val roamingProtocol: String? get() = attributes["roaming_protocol"]?.uppercase()
    val mvnoType: String? get() = attributes["mvno_type"]?.lowercase()?.takeIf { it.isNotEmpty() }
    val mvnoMatchData: String? get() = attributes["mvno_match_data"]
    val carrierId: Long? get() = attributes["carrier_id"]?.toLongOrNull()

    /** Carries the internet connection: default, or the wildcard. */
    val isInternet: Boolean get() = "default" in types || "*" in types

    /** Comparison key with a phone row: APN name (case-insensitive), sorted types, protocol. */
    val key: String get() = apnKey(apn, types, protocol)

    /** A short label for logs and the report; no user name, password or server. */
    val label: String get() = "$apn [${types.joinToString(",")}] ${protocol ?: "-"}"
}

internal fun apnKey(apn: String, types: List<String>, protocol: String?): String =
    apn.lowercase() + "|" + types.map { it.lowercase() }.sorted().joinToString(",") + "|" + (protocol?.uppercase() ?: "")

/** How a SIM matches an OCD APN row. */
enum class ApnMatch { MATCHES, NOT_THIS_SIM, NOT_CHECKABLE }

object ApnConf {
    private val ELEMENT = Regex("<apn\\s+([^>]*?)/?>")
    private val ATTRIBUTE = Regex("([A-Za-z_][A-Za-z0-9_]*)=\"([^\"]*)\"")
    private val DIGEST = Regex("data_digest:\\s*(sha256:[0-9a-f]{64})")

    /** The data_digest in the file's header comment. */
    fun headerDigest(head: String): String? = DIGEST.find(head)?.groupValues?.get(1)

    fun parse(lines: Sequence<String>): Sequence<OcdApnRow> = lines.flatMap { line ->
        ELEMENT.findAll(line).map { element ->
            OcdApnRow(ATTRIBUTE.findAll(element.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) })
        }
    }

    /** The rows for one network code, kept small for the SIM in hand. */
    fun rowsFor(lines: Sequence<String>, mccmnc: String): List<OcdApnRow> = parse(lines).filter { it.numeric == mccmnc }.toList()

    /**
     * Whether a row applies to the SIM, as Android's ApnSettingUtils and
     * carrier ID matching see it: its carrier ID, if any, is the SIM's; an
     * spn row needs the SIM's SPN, a gid row the GID1 prefix. imsi and iccid
     * rows cannot be checked by an app.
     */
    fun match(row: OcdApnRow, sim: SimIdentity): ApnMatch {
        if (row.numeric != sim.mccmnc) return ApnMatch.NOT_THIS_SIM
        row.carrierId?.let { id ->
            val ids = sim.carrierIds ?: return ApnMatch.NOT_CHECKABLE
            if (id !in ids) return ApnMatch.NOT_THIS_SIM
        }
        val data = row.mvnoMatchData.orEmpty()
        return when (row.mvnoType) {
            null -> ApnMatch.MATCHES
            "spn" -> when (val spn = sim.spn) {
                null -> ApnMatch.NOT_CHECKABLE
                else -> if (spn.casefold() == data.casefold()) ApnMatch.MATCHES else ApnMatch.NOT_THIS_SIM
            }
            "gid" -> when (val gid = sim.gid1) {
                null -> ApnMatch.NOT_CHECKABLE
                else -> if (gid.lowercase().startsWith(data.lowercase())) ApnMatch.MATCHES else ApnMatch.NOT_THIS_SIM
            }
            else -> ApnMatch.NOT_CHECKABLE
        }
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

/** One row of the phone's APN table, the columns the owner build compares. */
@Serializable
data class PhoneApnRow(
    val id: Long,
    val name: String?,
    val apn: String,
    val types: List<String>,
    val protocol: String?,
    val mvnoType: String?,
) {
    val key: String get() = apnKey(apn, types, protocol)

    companion object {
        /** The projection the owner build asks for. No user name, password, server or proxy column. */
        val COLUMNS = listOf("_id", "name", "apn", "type", "protocol", "mvno_type")
        private val ROW = Regex("^Row: \\d+ (.*)$")
        private val SPLIT = Regex(", (?=(?:${COLUMNS.joinToString("|")})=)")

        /** `content query` output (`Row: N col=value, ...`); null when the tool gave no answer at all. */
        fun parseQuery(text: String?): List<PhoneApnRow>? {
            if (text == null) return null
            var answered = false
            val rows = mutableListOf<PhoneApnRow>()
            for (line in text.lineSequence()) {
                if (line.startsWith("No result found")) answered = true
                val body = ROW.find(line)?.groupValues?.get(1) ?: continue
                answered = true
                val fields = body.split(SPLIT).associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val id = fields["_id"]?.toLongOrNull() ?: continue
                rows += PhoneApnRow(
                    id = id,
                    name = fields["name"]?.takeIf { it != "NULL" },
                    apn = fields["apn"].orEmpty(),
                    types = fields["type"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                    protocol = fields["protocol"]?.takeIf { it != "NULL" }?.uppercase(),
                    mvnoType = fields["mvno_type"]?.takeIf { it.isNotEmpty() && it != "NULL" }?.lowercase(),
                )
            }
            return if (answered) rows else null
        }
    }
}

/** The preferred APN of one subscription: readable, and its row ID if one is set. */
@Serializable
data class PreferredApn(val readable: Boolean, val id: Long?)

/**
 * The columns an inserted OCD row may carry, as the telephony provider names
 * them (apns-conf.xml spells the entry name `carrier`). Anything else in the
 * XML is left out.
 */
object ApnColumns {
    val FROM_XML: Map<String, String> = linkedMapOf(
        "carrier" to "name",
        "apn" to "apn",
        "mcc" to "mcc",
        "mnc" to "mnc",
        "type" to "type",
        "protocol" to "protocol",
        "roaming_protocol" to "roaming_protocol",
        "user" to "user",
        "password" to "password",
        "authtype" to "authtype",
        "proxy" to "proxy",
        "port" to "port",
        "mmsc" to "mmsc",
        "mmsproxy" to "mmsproxy",
        "mmsport" to "mmsport",
        "mvno_type" to "mvno_type",
        "mvno_match_data" to "mvno_match_data",
        "mtu" to "mtu",
        "bearer_bitmask" to "bearer_bitmask",
        "network_type_bitmask" to "network_type_bitmask",
        "carrier_enabled" to "carrier_enabled",
        "carrier_id" to "carrier_id",
    )
    val INTEGER = setOf("authtype", "mtu", "carrier_enabled", "carrier_id", "bearer_bitmask", "network_type_bitmask")

    /** What marks a row the owner build inserted, so a leftover is found without its state file. */
    const val MARKER = " (OCD Check test)"

    /** Provider column to value for one OCD row, with the marker on the name; booleans as 1/0, bitmasks as Android's. */
    fun values(row: OcdApnRow): Map<String, String> {
        KeyGuard.checkApn(row)
        val out = LinkedHashMap<String, String>()
        for ((xml, column) in FROM_XML) {
            var value = row.attributes[xml] ?: continue
            if (column == "carrier_enabled") value = if (value == "true") "1" else if (value == "false") "0" else value
            if (column == "bearer_bitmask" || column == "network_type_bitmask") value = bitmask(value) ?: continue
            if (column in INTEGER && value.toLongOrNull() == null) continue
            out[column] = value
        }
        out["numeric"] = row.numeric
        out["name"] = (row.name ?: row.apn) + MARKER
        out["user_visible"] = "1"
        out["edited"] = "0"
        return out
    }

    /**
     * The telephony provider's UNIQUE columns of the carriers table, with the
     * value it stores when an insert leaves one out (TelephonyProvider
     * CARRIERS_UNIQUE_FIELDS_DEFAULTS, AOSP 16). An insert that matches an
     * existing row on all of them is not added: the provider merges it into
     * that row (name, types and the rest) and answers with no row. On the
     * S20 (2026-10-19) that renamed stock Vodafone rows with the test marker,
     * and the revert then deleted them. Name and type are not among these
     * columns, so a row that differs from a phone row only in its types is
     * the same row to the provider.
     */
    val UNIQUE: Map<String, String> = linkedMapOf(
        "numeric" to "", "mcc" to "", "mnc" to "", "apn" to "", "proxy" to "", "port" to "",
        "mmsproxy" to "", "mmsport" to "", "mmsc" to "", "carrier_enabled" to "1", "bearer" to "0",
        "mvno_type" to "", "mvno_match_data" to "", "profile_id" to "0", "protocol" to "IP",
        "roaming_protocol" to "IP", "user_editable" to "1", "owned_by" to "1", "apn_set_id" to "0",
        "carrier_id" to "-1", "infrastructure_bitmask" to "3", "esim_bootstrap_provisioning" to "0",
    )

    /**
     * The provider's own conflict lookup (selectConflictingRow) for an insert
     * of [values]: each UNIQUE column equal to the value the insert would
     * store. An insert through the general URI is always owned by others.
     */
    fun conflictMatch(values: Map<String, String>): List<Pair<String, String>> =
        UNIQUE.map { (column, default) -> column to if (column == "owned_by") default else values[column] ?: default }

    /** "1|2|13" to the provider's integer bitmask: bit (n - 1) for each network type n. */
    fun bitmask(value: String): String? {
        val parts = value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var mask = 0L
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 1..62) return null
            mask = mask or (1L shl (n - 1))
        }
        return mask.toString()
    }
}
