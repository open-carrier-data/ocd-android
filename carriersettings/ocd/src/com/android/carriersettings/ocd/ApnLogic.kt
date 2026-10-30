/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/** The APN part: composing the file (7.3), confirming it in TelephonyProvider (7.5), and the install checks 14 to 17. */
object ApnLogic {
    class Composed(val bytes: ByteArray, val sha512: String, val doc: ApnXml.Doc, val omitted: List<String>) {
        val rows: Int get() = doc.rows.size
        val sha512Prefix: String get() = sha512.take(12)
    }

    /**
     * 7.3: the dataset's bytes, minus the line of every row a valid omit targets. Each removed
     * line must parse to the targeted row_key. Without omits the bytes are the published ones.
     */
    fun compose(xml: ByteArray, doc: ApnXml.Doc, omit: Set<String>): Composed {
        if (omit.isEmpty()) return Composed(xml, Files.sha512(xml), doc, emptyList())
        val drop = HashSet<Int>()
        val omitted = ArrayList<String>()
        for (row in doc.rows) {
            if (row.rowKey in omit) {
                drop += row.line
                omitted += row.rowKey
            }
        }
        if (drop.isEmpty()) return Composed(xml, Files.sha512(xml), doc, emptyList())
        // The kept lines, joined by the same '\n' the file was split on (a '\r' stays with its line).
        val rebuilt = doc.lines.filterIndexed { i, _ -> (i + 1) !in drop }.joinToString("\n")
        val bytes = rebuilt.toByteArray(Charsets.UTF_8)
        val again = ApnXml.parse(bytes)
        if (again.rows.size != doc.rows.size - drop.size) refuse("apn_index_mismatch", "the composed file does not re-parse to the expected rows")
        return Composed(bytes, Files.sha512(bytes), again, omitted.sorted())
    }

    /**
     * The row keys valid APN omits of this cell target: `apn` omits, and `emergency_default_won`
     * (7.3 point 2). Only measured records with a carrier key count (round 31).
     */
    fun omits(records: List<Record>, cell: BuildCell?, today: String, rowKeys: Set<String>): Set<String> {
        cell ?: return emptySet()
        return records.filter { r ->
            r.measured && r.carrier.scope != "all" &&
                cell.matches(r) && !BundleBuilder.expired(r, today) && r.target.rowKey != null && r.target.rowKey in rowKeys &&
                (r.target.type == "apn" || (r.target.type == "emergency_data" && r.result == "emergency_default_won"))
        }.mapNotNull { it.target.rowKey }.toSortedSet()
    }

    // --- 7.5 confirmation ----------------------------------------------------------------

    /** One row of content://telephony/carriers as the confirmation reads it. */
    data class DbRow(
        val mcc: String?,
        val mnc: String?,
        val apn: String?,
        val type: String?,
        val mvnoType: String?,
        val mvnoMatchData: String?,
        val protocol: String?,
        val roamingProtocol: String?,
        val carrierId: Int?,
        val edited: Int?,
        val name: String? = null,
    )

    /**
     * The row key of 7.5 without the type list. TelephonyProvider merges rows equal on
     * CARRIERS_UNIQUE_FIELDS (protocol and roaming protocol are among them, the type is not)
     * and unions their types (mergeFieldsAndUpdateDb); OCD's file has 732 such groups with
     * differing types on 2026-10-25, so a key with the type list would never show. The types
     * are compared as unions per key instead ([typesCover]).
     */
    fun key(mcc: String?, mnc: String?, apn: String?, mvnoType: String?, mvnoData: String?, protocol: String?, roaming: String?, carrierId: Int?): String =
        listOf(mcc.orEmpty(), mnc.orEmpty(), apn.orEmpty(), mvnoType.orEmpty(), mvnoData.orEmpty(), protocol.takeUnless { it.isNullOrEmpty() } ?: "IP", roaming.takeUnless { it.isNullOrEmpty() } ?: "IP", (carrierId ?: -1).toString())
            .joinToString("\u001f")

    fun key(row: ApnXml.Row): String {
        val a = row.attrs
        return key(a["mcc"], a["mnc"], a["apn"], a["mvno_type"], a["mvno_match_data"], a["protocol"], a["roaming_protocol"], a["carrier_id"]?.toIntOrNull())
    }

    fun key(row: DbRow): String = key(row.mcc, row.mnc, row.apn, row.mvnoType, row.mvnoMatchData, row.protocol, row.roamingProtocol, row.carrierId)

    /** A type union: null means every type (an empty type string, as TelephonyProvider merges it). */
    private fun types(type: String?): Set<String>? =
        type?.split(',')?.map { it.trim().lowercase(java.util.Locale.ROOT) }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() && "*" !in it }

    private fun union(a: Set<String>?, b: Set<String>?, first: Boolean): Set<String>? = if (first) b else if (a == null || b == null) null else a + b

    class Shows(val ok: Boolean, val expected: Int, val raw: Int, val detail: String)

    /** U ⊆ E and E ⊆ K over the keys, and per key the composed types covered by the table's. */
    fun shows(composed: List<ApnXml.Row>, table: List<DbRow>): Shows {
        val e = HashMap<String, Set<String>?>()
        for (r in composed) {
            val k = key(r)
            e[k] = union(e[k], types(r.attrs["type"]), k !in e)
        }
        val k = HashMap<String, Set<String>?>()
        val editedKeys = HashSet<String>()
        val u = HashSet<String>()
        for (r in table) {
            val key = key(r)
            k[key] = union(k[key], types(r.type), key !in k)
            if ((r.edited ?: 0) == 0) u += key else editedKeys += key
        }
        val extra = u.filter { it !in e }
        if (extra.isNotEmpty()) return Shows(false, e.size, table.size, "${extra.size} unedited rows are not in the file")
        val missing = e.keys.filter { it !in k }
        if (missing.isNotEmpty()) return Shows(false, e.size, table.size, "${missing.size} rows of the file are not in TelephonyProvider")
        val uncovered = e.filter { (key, want) ->
            key !in editedKeys && !typesCover(k[key], want)
        }
        if (uncovered.isNotEmpty()) return Shows(false, e.size, table.size, "${uncovered.size} rows lack types of the file")
        return Shows(true, e.size, table.size, "the file shows: ${e.size} distinct rows, ${table.size} in the table")
    }

    /**
     * Round 32's candidate sets: [shows] lets the table's types cover the file's, so a set that
     * only adds a type would read as gone while it is still in place. For the rows of one network
     * ([numeric], the candidate's) the table's type union per key must equal the file's; keys with
     * a user-edited row are left out, as in [shows]. Null when equal, else what differs.
     */
    fun typesDiffer(composed: List<ApnXml.Row>, table: List<DbRow>, numeric: String): String? {
        val e = HashMap<String, Set<String>?>()
        for (r in composed) {
            if (r.numeric != numeric) continue
            val k = key(r)
            e[k] = union(e[k], types(r.attrs["type"]), k !in e)
        }
        val k = HashMap<String, Set<String>?>()
        val edited = HashSet<String>()
        for (r in table) {
            if (r.mcc.orEmpty() + r.mnc.orEmpty() != numeric) continue
            val key = key(r)
            k[key] = union(k[key], types(r.type), key !in k)
            if ((r.edited ?: 0) != 0) edited += key
        }
        val differ = (e.keys + k.keys).filter { it !in edited && e[it] != k[it] }
        return if (differ.isEmpty()) null else "${differ.size} rows of the network carry other types than the file"
    }

    fun typesCover(have: Set<String>?, want: Set<String>?): Boolean = when {
        have == null -> true
        want == null -> false
        else -> have.containsAll(want)
    }

    // --- 8.5 emergency gate --------------------------------------------------------------

    /** SHA-256 over the sorted row_keys of every row typed `emergency`. */
    fun emergencyDigest(rows: List<ApnXml.Row>): String =
        Files.sha256(rows.filter { "emergency" in it.types }.map { it.rowKey }.sortedWith(::compareBytes).joinToString("\n"))

    /** For the narrow gate: the selected emergency rows' APN name and roaming protocol for one SIM. */
    fun emergencyFields(selected: List<ApnXml.Row>): Set<String> =
        selected.filter { "emergency" in it.types }.map { "${it.attrs["apn"].orEmpty()}|${it.attrs["roaming_protocol"] ?: "IP"}" }.toSortedSet()

    /** The F5 read record (x1s-ocd-f5-read.v1) for a runtime install; null when valid, else why not. */
    fun f5ReadProblem(json: String?, baseCommit: String?, headCommit: String): String? {
        json ?: return "no f5_read"
        val o = runCatching { Obj.of(Json.parse(json), "f5_read") }.getOrNull() ?: return "f5_read is not a JSON object"
        val raw = o.raw
        if (raw["schema"] != null && raw["schema"] != "x1s-ocd-f5-read.v1") return "f5_read schema is not x1s-ocd-f5-read.v1"
        if (raw["base_commit"] != baseCommit) return "f5_read base_commit is not the installed commit"
        if (raw["head_commit"] != headCommit) return "f5_read head_commit is not the staged commit"
        if (raw["verdict"] != "proceed") return "f5_read verdict is not proceed"
        val reader = (raw["reader"] as? String)?.trim().orEmpty()
        if (reader.isEmpty()) return "f5_read names no reader"
        val r = reader.lowercase(java.util.Locale.ROOT)
        if (r == "app" || r.contains("open_carrier_data.check") || r.contains("ocd check") || r.contains("ocd-check")) return "f5_read's reader is the app"
        if ((raw["read_at"] as? String).isNullOrBlank()) return "f5_read has no read_at"
        return null
    }

    // --- R31.6 the app's first-use record -------------------------------------------------

    /**
     * The app's first-use record of the emergency data test (`ocd-emergency-first-use/1`); null
     * when it says the first use passed on this phone's device and IMS stack, else why not. It
     * can keep a gate full, never narrow one the build does not declare.
     */
    fun firstUseProblem(json: String, cell: BuildCell): String? {
        val o = runCatching { Obj.of(Json.parse(json), "first_use") }.getOrNull() ?: return "first_use is not a JSON object"
        val raw = o.raw
        if (raw["schema"] != "ocd-emergency-first-use/1") return "first_use schema is not ocd-emergency-first-use/1"
        val device = raw["device"] as? Map<*, *> ?: return "first_use names no device"
        if (device["codename"] != cell.codename || (device["model"] as? String)?.equals(cell.model, ignoreCase = true) != true) return "first_use is for another device"
        val stack = raw["ims_stack"] as? Map<*, *> ?: return "first_use names no IMS stack"
        if (stack["id"] != cell.stackId) return "first_use is for another IMS stack"
        if (raw["result"] != "passed") return "the app's first use reads ${raw["result"]}"
        return null
    }

    // --- R31.9 the preferred APN by its fields --------------------------------------------

    /** The columns that identify a preferred APN row (never `_id` alone, never user or password). */
    val PREFERRED_FIELDS = listOf(
        "name", "apn", "type", "mcc", "mnc", "numeric", "mvno_type", "mvno_match_data", "protocol", "roaming_protocol", "carrier_id",
        "network_type_bitmask", "bearer_bitmask", "proxy", "port", "mmsc", "mmsproxy", "mmsport", "authtype", "server", "carrier_enabled",
        "profile_id", "mtu", "mtu_v4", "mtu_v6", "apn_set_id", "skip_464xlat", "always_on", "infrastructure_bitmask",
    )

    fun preferredFields(all: Map<String, String>): Map<String, String> = all.filterKeys { it in PREFERRED_FIELDS }.toSortedMap()

    /** The row with every recorded field equal; the recorded row ID breaks a tie. Null: none. */
    fun matchPreferred(fields: Map<String, String>, rows: List<Pair<Long, Map<String, String>>>, hint: Long?): Long? {
        if (fields.isEmpty()) return null
        val matching = rows.filter { (_, f) -> fields.all { (k, v) -> f[k] == v } }
        return (matching.firstOrNull { it.first == hint } ?: matching.firstOrNull())?.first
    }

    /**
     * Round 32: while a candidate set is in place the preferred row may carry another type or no
     * fill-in ([loose]); then the one row equal on every other recorded field. Null: none, or more
     * than one.
     */
    fun matchPreferredLoosely(fields: Map<String, String>, rows: List<Pair<Long, Map<String, String>>>, loose: Set<String>): Long? {
        val rest = fields - loose
        if (rest.isEmpty() || loose.isEmpty()) return null
        return rows.filter { (_, f) -> rest.all { (k, v) -> f[k] == v } }.singleOrNull()?.first
    }
}
