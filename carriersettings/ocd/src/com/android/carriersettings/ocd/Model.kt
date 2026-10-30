/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * The dataset's files as the reader holds them (spec section 2), with strict parsers: every
 * member is known, required members are present, and shapes are checked. Structure errors are
 * `parse_error`; an unknown member or `schema_version` is `schema_unknown`; values are typed by
 * the registry in [Verifier], which knows which registry applies.
 */

/** The fixed file table of 2.1: local name, path on main, cap in bytes. */
enum class DatasetFile(val role: String, val local: String, val path: String, val cap: Long) {
    MANIFEST("manifest", "manifest.json", "generated/ocd-dataset/manifest.json", 256L * 1024),
    PROFILES("profiles", "profiles.json", "generated/ocd-dataset/profiles.json", 32L * 1024 * 1024),
    GOOGLE_LANE("google_lane", "google-lane.json", "generated/ocd-dataset/google-lane.json", 8L * 1024 * 1024),
    DEVICE_RECORDS("device_records", "device-records.json", "generated/devices/device-records.json", 4L * 1024 * 1024),
    APN_XML("apn_xml", "apns-conf.xml", "generated/android/apns-conf.xml", 16L * 1024 * 1024),
    APN_INDEX("apn_index", "apn-index.json", "generated/ocd-dataset/apn-index.json", 16L * 1024 * 1024),
    /** Optional (round 31, R31.7): the group A reads on main. */
    GROUP_A_READS("group_a_reads", "group-a-reads.json", "generated/ocd-dataset/group-a-reads.json", 4L * 1024 * 1024),
    PIN("pin", "pin.json", "", 64L * 1024);

    companion object {
        const val TOTAL_CAP = 64L * 1024 * 1024
        fun byLocal(name: String): DatasetFile? = entries.firstOrNull { it.local == name }
        fun byPath(path: String): DatasetFile? = entries.firstOrNull { it.path == path && it != PIN }
        fun byRole(role: String): DatasetFile? = entries.firstOrNull { it.role == role && it != PIN }
    }
}

/** Strict member access on one JSON object. */
class Obj(private val m: Map<String, Any?>, private val where: String) {
    companion object {
        fun of(json: Any?, where: String): Obj = Obj((json as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST")
            it as Map<String, Any?>
        } ?: refuse("parse_error", "$where is not an object"), where)
    }

    val keys: Set<String> get() = m.keys
    val raw: Map<String, Any?> get() = m

    /** Every member in [required] present, none outside [required] and [optional]. */
    fun members(required: Set<String>, optional: Set<String> = emptySet()): Obj {
        val unknown = m.keys - required - optional
        if (unknown.isNotEmpty()) refuse("schema_unknown", "$where has unknown member ${unknown.sorted().first()}")
        val missing = required - m.keys
        if (missing.isNotEmpty()) refuse("parse_error", "$where lacks ${missing.sorted().first()}")
        return this
    }

    fun has(name: String) = name in m
    fun isNull(name: String) = m[name] == null
    fun any(name: String): Any? = m[name]

    fun str(name: String): String = m[name] as? String ?: refuse("parse_error", "$where.$name is not a string")
    fun strOrNull(name: String): String? = if (m[name] == null) null else str(name)
    fun bool(name: String): Boolean = m[name] as? Boolean ?: refuse("parse_error", "$where.$name is not a boolean")
    fun long(name: String): Long = (m[name] as? Json.Num)?.longOrNull() ?: refuse("parse_error", "$where.$name is not an integer")
    fun longOrNull(name: String): Long? = if (m[name] == null) null else long(name)
    fun int(name: String): Int = long(name).also { if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) refuse("parse_error", "$where.$name is out of range") }.toInt()
    fun intOrNull(name: String): Int? = if (m[name] == null) null else int(name)
    fun obj(name: String): Obj = of(m[name], "$where.$name")
    fun objOrNull(name: String): Obj? = if (m[name] == null) null else obj(name)
    fun list(name: String): List<Any?> = m[name] as? List<Any?> ?: refuse("parse_error", "$where.$name is not an array")
    fun listOrNull(name: String): List<Any?>? = if (m[name] == null) null else list(name)
    fun strings(name: String): List<String> = list(name).mapIndexed { i, v -> v as? String ?: refuse("parse_error", "$where.$name[$i] is not a string") }
    fun schemaVersion() {
        val v = (m["schema_version"] as? Json.Num)?.longOrNull()
        if (v != 1L) refuse("schema_unknown", "$where.schema_version is ${m["schema_version"]}, this build reads 1")
    }
}

private val HEX40 = Regex("^[0-9a-f]{40}$")
private val HEX64 = Regex("^[0-9a-f]{64}$")
val DIGEST = Regex("^sha256:[0-9a-f]{64}$")
private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
private val TIME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

// --- pin.json (2.11) -------------------------------------------------------------------

data class PinEntry(val blob: String, val bytes: Long)
data class PinExtra(val sha256: String, val bytes: Long)

data class Pin(
    val role: String,
    val repository: String,
    val commit: String,
    val commitDate: String,
    val validate: Map<String, Any?>?,
    /** path on main -> blob and size */
    val files: Map<String, PinEntry>,
    val fetchedAt: String?,
    val fetchedBy: String?,
    /** local name -> SHA-256 and size; only google-lane.json, only for role build */
    val extra: Map<String, PinExtra>,
) {
    val sha7: String get() = commit.take(7)

    companion object {
        /** Check 1's own part: strict, commit 40 hex, only known paths, the manifest named, extra only for a build pin. */
        fun parse(text: String): Pin = try {
            val o = Obj.of(Json.parse(text, Json.DEFAULT), "pin.json")
                .members(setOf("schema", "role", "repository", "commit", "commit_date", "files"), setOf("validate", "fetched_at", "fetched_by", "extra"))
            if (o.str("schema") != "ocd-dataset-pin/1") refuse("pin_invalid", "pin.json schema is not ocd-dataset-pin/1")
            val role = o.str("role")
            if (role != "app" && role != "build") refuse("pin_invalid", "pin.json role is $role")
            val commit = o.str("commit")
            if (!HEX40.matches(commit)) refuse("pin_invalid", "pin.json commit is not 40 hex")
            val files = LinkedHashMap<String, PinEntry>()
            val fo = o.obj("files")
            for (path in fo.keys) {
                if (DatasetFile.byPath(path) == null) refuse("pin_invalid", "pin.json names an unknown file $path")
                val e = fo.obj(path).members(setOf("blob", "bytes"))
                val blob = e.str("blob")
                if (!HEX40.matches(blob)) refuse("pin_invalid", "pin.json blob of $path is not 40 hex")
                files[path] = PinEntry(blob, e.long("bytes"))
            }
            if (DatasetFile.MANIFEST.path !in files) refuse("pin_invalid", "pin.json does not name the manifest")
            val extra = LinkedHashMap<String, PinExtra>()
            o.objOrNull("extra")?.let { x ->
                if (role != "build") refuse("pin_invalid", "only a build pin may carry extra")
                for (name in x.keys) {
                    if (name != DatasetFile.GOOGLE_LANE.local) refuse("pin_invalid", "pin.json extra names $name")
                    val e = x.obj(name).members(setOf("sha256", "bytes"))
                    val sha = e.str("sha256")
                    if (!HEX64.matches(sha)) refuse("pin_invalid", "pin.json extra sha256 is not 64 hex")
                    extra[name] = PinExtra(sha, e.long("bytes"))
                }
            }
            if (DatasetFile.GOOGLE_LANE.path in files && extra.isNotEmpty()) refuse("pin_invalid", "the Google lane is both on main and extra")
            Pin(
                role, o.str("repository"), commit, o.str("commit_date"),
                o.objOrNull("validate")?.raw, files, o.strOrNull("fetched_at"), o.strOrNull("fetched_by"), extra,
            )
        } catch (e: Json.ParseException) {
            refuse("pin_invalid", "pin.json: ${e.message}")
        } catch (e: Refused) {
            if (e.code == "pin_invalid") throw e else refuse("pin_invalid", e.detail)
        }
    }
}

// --- manifest.json (2.4) ---------------------------------------------------------------

data class ManifestEntry(val role: String, val name: String, val path: String, val bytes: Long, val sha256: String)

data class Manifest(
    val dataDigest: String,
    val generatedAt: String,
    val generator: String,
    val apnDatabaseVersion: Int,
    val googleLaneIncluded: Boolean,
    val files: List<ManifestEntry>,
    val datasetDigest: String,
    val counts: Map<String, Long>,
) {
    fun entry(f: DatasetFile): ManifestEntry? = files.firstOrNull { it.role == f.role }

    /** SHA-256 over name, sha256 and bytes lines of each file in byte order of name (2.4). */
    fun recomputedDigest(): String = digestOf(files)

    companion object {
        fun digestOf(files: List<ManifestEntry>): String {
            val text = StringBuilder()
            for (f in files.sortedWith { a, b -> compareBytes(a.name, b.name) }) {
                text.append(f.name).append('\n').append(f.sha256).append('\n').append(f.bytes).append('\n')
            }
            return "sha256:" + Files.sha256(text.toString())
        }

        fun parse(bytes: ByteArray): Manifest {
            val o = Obj.of(Json.parseBytes(bytes, Json.DATASET), "manifest.json")
            o.schemaVersion()
            o.members(setOf("schema_version", "format", "data_digest", "generated_at", "generator", "apn_database_version", "google_lane", "files", "dataset_digest", "counts"))
            if (o.str("format") != "ocd-dataset") refuse("schema_unknown", "manifest format ${o.str("format")}")
            val digest = o.str("data_digest")
            if (!DIGEST.matches(digest)) refuse("parse_error", "manifest data_digest is not sha256:<64 hex>")
            val generatedAt = o.str("generated_at")
            if (!TIME.matches(generatedAt)) refuse("parse_error", "manifest generated_at is not ISO 8601 UTC seconds")
            val lane = when (o.str("google_lane")) {
                "included" -> true
                "absent" -> false
                else -> refuse("parse_error", "manifest google_lane is neither included nor absent")
            }
            val files = o.list("files").mapIndexed { i, f ->
                val e = Obj.of(f, "manifest.files[$i]").members(setOf("role", "name", "path", "bytes", "sha256"))
                val known = DatasetFile.byRole(e.str("role")) ?: refuse("schema_unknown", "manifest names role ${e.str("role")}")
                if (known == DatasetFile.MANIFEST || e.str("name") != known.local || e.str("path") != known.path) {
                    refuse("parse_error", "manifest file ${e.str("role")} has name ${e.str("name")} at ${e.str("path")}")
                }
                val sha = e.str("sha256")
                if (!HEX64.matches(sha)) refuse("parse_error", "manifest sha256 of ${e.str("name")} is not 64 hex")
                ManifestEntry(known.role, known.local, known.path, e.long("bytes"), sha)
            }
            if (files.map { it.role }.toSet().size != files.size) refuse("parse_error", "manifest lists a role twice")
            val want = setOf("profiles", "device_records", "apn_xml", "apn_index") + if (lane) setOf("google_lane") else emptySet()
            val roles = files.map { it.role }.toSet() - DatasetFile.GROUP_A_READS.role
            if (roles != want) refuse("parse_error", "manifest files are ${files.map { it.role }.sorted()}, expected ${want.sorted()} (and optionally group_a_reads)")
            val dsd = o.str("dataset_digest")
            if (!DIGEST.matches(dsd)) refuse("parse_error", "manifest dataset_digest is not sha256:<64 hex>")
            val c = o.obj("counts").members(setOf("networks", "profiles", "fill_only_profiles", "google_entries", "apn_rows", "records"))
            return Manifest(
                digest, generatedAt, o.str("generator"), o.int("apn_database_version"), lane, files, dsd,
                c.keys.associateWith { c.long(it) },
            )
        }
    }
}

/** Byte order of the UTF-8 encodings (2.4 and 4.2 sort "in byte order"). */
fun compareBytes(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
        val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return x.size - y.size
}

// --- profiles.json (2.5) ---------------------------------------------------------------

data class KeyInfo(val type: KeyType, val group: String?, val origin: List<String>, val fork: String?)

data class GroupMarking(
    val aPattern: String,
    val aNames: List<String>,
    val aKeys: List<String>,
    val bKeys: List<String>,
    val bMustContain: String,
    val x6Keys: List<String>,
    val x6Networks: List<String>,
)

data class When(val kind: String, val value: String)

data class FillItem(val entry: String, val condition: When?)

data class Alternative(val value: Any, val families: List<String>)

data class Network(val mcc: String, val mnc: String) {
    val key: String get() = "$mcc-$mnc"
}

data class Match(
    val mccmnc: List<Network>,
    val spn: List<String>? = null,
    val imsi: List<String>? = null,
    val gid1: List<String>? = null,
    val gid2: List<String>? = null,
    val iccid: List<String>? = null,
    val carrierIds: List<Int>? = null,
) {
    /** Dimensions used besides mccmnc (2.5.3). */
    val specificity: Int get() = listOfNotNull(spn, imsi, gid1, gid2, iccid, carrierIds).size

    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("mccmnc", mccmnc.map { linkedMapOf("mcc" to it.mcc, "mnc" to it.mnc) })
        spn?.let { put("spn", it) }
        imsi?.let { put("imsi_prefix_patterns", it) }
        gid1?.let { put("gid1_prefixes", it) }
        gid2?.let { put("gid2_prefixes", it) }
        iccid?.let { put("iccid_prefixes", it) }
        carrierIds?.let { put("android_carrier_ids", it) }
    }

    companion object {
        private val MCC = Regex("^[0-9]{3}$")
        private val MNC = Regex("^[0-9]{2,3}$")
        private val IMSI = Regex("^[0-9xX]{5,15}$")
        private val GID = Regex("^[0-9A-Fa-f]{1,32}$")
        private val ICCID = Regex("^[0-9]{5,20}$")

        fun parse(json: Any?, where: String): Match {
            val o = Obj.of(json, where).members(setOf("mccmnc"), setOf("spn", "imsi_prefix_patterns", "gid1_prefixes", "gid2_prefixes", "iccid_prefixes", "android_carrier_ids"))
            val nets = o.list("mccmnc").mapIndexed { i, n ->
                val e = Obj.of(n, "$where.mccmnc[$i]").members(setOf("mcc", "mnc"))
                val mcc = e.str("mcc")
                val mnc = e.str("mnc")
                if (!MCC.matches(mcc) || !MNC.matches(mnc)) refuse("invalid_value", "$where: bad mccmnc $mcc-$mnc")
                Network(mcc, mnc)
            }
            if (nets.isEmpty()) refuse("invalid_value", "$where: empty mccmnc")
            fun dim(name: String, re: Regex?): List<String>? {
                if (!o.has(name)) return null
                val v = o.strings(name)
                if (v.isEmpty()) refuse("invalid_value", "$where: empty $name")
                if (re != null && v.any { !re.matches(it) }) refuse("invalid_value", "$where: bad value in $name")
                return v
            }
            val ids = if (o.has("android_carrier_ids")) o.list("android_carrier_ids").map {
                val n = (it as? Json.Num)?.longOrNull() ?: refuse("invalid_value", "$where: bad carrier id")
                if (n < 0 || n > 1_000_000) refuse("invalid_value", "$where: carrier id out of range")
                n.toInt()
            }.also { if (it.isEmpty()) refuse("invalid_value", "$where: empty android_carrier_ids") } else null
            return Match(nets, dim("spn", null), dim("imsi_prefix_patterns", IMSI), dim("gid1_prefixes", GID), dim("gid2_prefixes", GID), dim("iccid_prefixes", ICCID), ids)
        }
    }
}

data class Profile(
    val id: String,
    val kind: String,
    val name: String,
    val match: Match,
    val specificity: Int,
    val fill: List<FillItem>,
    val values: Map<String, Any>,
    val sources: Map<String, List<String>>,
    val alternatives: Map<String, List<Alternative>>,
    val apnRows: Int,
) {
    val fillOnly: Boolean get() = kind == "fill_only"
}

/** profiles.json, parsed but with values still as JSON until [Verifier] types them. */
data class ProfilesFile(
    val digest: String,
    val keys: Map<String, KeyInfo>,
    val groups: GroupMarking,
    /** network key -> its profiles, in file order */
    val networks: Map<String, List<Profile>>,
) {
    companion object {
        private val ID = Regex("^(open|fill)\\.[0-9]{5,6}\\.[0-9a-f]{12}$")
        private val WHEN_KINDS = setOf("spn", "imsi", "gid1", "iccid")

        fun parseKeys(json: Any?): Map<String, KeyInfo> {
            val o = Obj.of(json, "profiles.keys")
            val out = LinkedHashMap<String, KeyInfo>()
            for (k in o.keys) {
                val e = o.obj(k).members(setOf("type", "group", "origin", "fork"))
                val type = KeyType.of(e.any("type")) ?: refuse("wrong_type", "registry type of $k is ${e.any("type")}")
                val group = e.strOrNull("group")
                if (group != null && group !in setOf("A", "B", "X6")) refuse("invalid_value", "registry group of $k is $group")
                out[k] = KeyInfo(type, group, e.strings("origin"), e.strOrNull("fork"))
            }
            return out
        }

        fun parseGroups(json: Any?): GroupMarking {
            val g = Obj.of(json, "profiles.groups").members(setOf("A", "B", "X6"))
            val a = g.obj("A").members(setOf("pattern", "names", "keys"))
            val b = g.obj("B").members(setOf("keys", "must_contain"))
            val x = g.obj("X6").members(setOf("keys", "networks"))
            return GroupMarking(a.str("pattern"), a.strings("names"), a.strings("keys"), b.strings("keys"), b.str("must_contain"), x.strings("keys"), x.strings("networks"))
        }

        fun parseFill(json: Any?, where: String): List<FillItem> = (json as? List<*> ?: refuse("parse_error", "$where.fill is not an array")).mapIndexed { i, it ->
            val f = Obj.of(it, "$where.fill[$i]").members(setOf("entry", "when"))
            val w = f.objOrNull("when")?.members(setOf("kind", "value"))?.let { w ->
                val kind = w.str("kind")
                if (kind !in WHEN_KINDS) refuse("invalid_value", "$where: fill kind $kind")
                When(kind, w.str("value"))
            }
            FillItem(f.str("entry"), w)
        }

        /** One profile; [typeOf] types values (it refuses unknown keys and wrong types). */
        fun parseProfile(json: Any?, where: String, typeOf: (String, Any?) -> Any): Profile {
            val p = Obj.of(json, where).members(setOf("id", "kind", "name", "match", "specificity", "fill", "values", "sources", "alternatives", "apn_rows"))
            val id = p.str("id")
            val kind = p.str("kind")
            if (kind != "ocd" && kind != "fill_only") refuse("invalid_value", "$where: kind $kind")
            if (!ID.matches(id) || (kind == "ocd") != id.startsWith("open.")) refuse("invalid_value", "$where: id $id does not fit kind $kind")
            val match = Match.parse(p.any("match"), "$id.match")
            val spec = p.int("specificity")
            if (spec != match.specificity) refuse("invalid_value", "$id: specificity $spec, recomputed ${match.specificity}")
            val values = LinkedHashMap<String, Any>()
            val vo = p.obj("values")
            for (k in vo.keys) values[k] = typeOf(k, vo.any(k))
            val sources = LinkedHashMap<String, List<String>>()
            val so = p.obj("sources")
            for (k in so.keys) sources[k] = so.strings(k)
            val alternatives = LinkedHashMap<String, List<Alternative>>()
            val ao = p.obj("alternatives")
            for (k in ao.keys) {
                alternatives[k] = ao.list(k).mapIndexed { i, a ->
                    val e = Obj.of(a, "$id.alternatives.$k[$i]").members(setOf("value", "families"))
                    Alternative(typeOf(k, e.any("value")), e.strings("families"))
                }
            }
            if (kind == "fill_only" && (values.isNotEmpty() || sources.isNotEmpty() || alternatives.isNotEmpty() || p.int("apn_rows") != 0)) {
                refuse("invalid_value", "$id: a fill-only profile carries values or rows")
            }
            val rows = p.int("apn_rows")
            if (rows < 0) refuse("invalid_value", "$id: apn_rows below 0")
            return Profile(id, kind, p.str("name"), match, spec, parseFill(p.any("fill"), id), values, sources, alternatives, rows)
        }
    }
}

// --- google-lane.json (2.7) ------------------------------------------------------------

data class Rule(val name: String, val mcc: String, val mnc: String, val mvno: When?)

data class GoogleLane(
    val digest: String,
    val provenance: Map<String, Any?>,
    val state: String,
    val rules: List<Rule>,
    /** canonical name -> typed values */
    val entries: Map<String, Map<String, Any>>,
) {
    /**
     * This lane with the keys it lacks filled from [baked], entry by entry. Main's lane leaves out
     * values its privacy guard holds back (voicemail numbers, conference factory URIs); the build's
     * private lane keeps them, and the ruling serves them from the build (round 30, step 3).
     */
    fun filledFrom(baked: GoogleLane): GoogleLane {
        val merged = LinkedHashMap<String, Map<String, Any>>()
        for ((name, values) in entries) {
            val extra = baked.entries[name] ?: emptyMap()
            merged[name] = if (extra.keys.all { it in values }) values else LinkedHashMap(extra).apply { putAll(values) }
        }
        return copy(entries = merged)
    }

    companion object {
        fun parse(bytes: ByteArray, typeOf: (String, Any?) -> Any): GoogleLane {
            val o = Obj.of(Json.parseBytes(bytes, Json.DATASET), "google-lane.json")
            o.schemaVersion()
            o.members(setOf("schema_version", "based_on_data_digest", "family", "provenance", "rules", "entries"))
            if (o.str("family") != "google") refuse("invalid_value", "google-lane.json family is ${o.str("family")}")
            val prov = o.obj("provenance").members(setOf("lane", "device", "build", "carrier_list_version", "others_version", "snapshot", "state"))
            val state = prov.str("state")
            if (state != "public_main" && state != "private_lane_at_build_pin") refuse("invalid_value", "google-lane.json state $state")
            val rules = o.list("rules").mapIndexed { i, r ->
                val e = Obj.of(r, "google-lane.rules[$i]").members(setOf("canonical_name", "mcc", "mnc", "mvno"))
                val mv = e.objOrNull("mvno")?.members(setOf("kind", "value"))?.let { When(it.str("kind"), it.str("value")) }
                if (mv != null && mv.kind !in setOf("spn", "imsi", "gid1", "iccid")) refuse("invalid_value", "google-lane rule kind ${mv.kind}")
                Rule(e.str("canonical_name"), e.str("mcc"), e.str("mnc"), mv)
            }
            val entries = LinkedHashMap<String, Map<String, Any>>()
            val eo = o.obj("entries")
            for (name in eo.keys) {
                val v = eo.obj(name).members(setOf("values")).obj("values")
                entries[name] = v.keys.associateWithTo(LinkedHashMap()) { k -> typeOf(k, v.any(k)) }
            }
            if ("default" !in entries || "no_sim" !in entries) refuse("invalid_value", "google-lane.json lacks default or no_sim")
            return GoogleLane(o.str("based_on_data_digest"), prov.raw, state, rules, entries)
        }
    }
}

// --- apn-index.json (2.9) --------------------------------------------------------------

data class IndexRow(val ordinal: Int, val line: Int, val rowKey: String, val profiles: List<String>)

data class ApnIndex(val digest: String, val xmlSha256: String, val rows: List<IndexRow>) {
    companion object {
        private val ROW_KEY = Regex("^a:[0-9a-f]{16}$")

        fun parse(bytes: ByteArray): ApnIndex {
            val o = Obj.of(Json.parseBytes(bytes, Json.DATASET), "apn-index.json")
            o.schemaVersion()
            o.members(setOf("schema_version", "based_on_data_digest", "apn_xml_sha256", "rows"))
            val rows = o.list("rows").mapIndexed { i, r ->
                val e = Obj.of(r, "apn-index.rows[$i]").members(setOf("ordinal", "line", "row_key", "profiles"))
                val key = e.str("row_key")
                if (!ROW_KEY.matches(key)) refuse("parse_error", "apn-index row $i: bad row_key")
                IndexRow(e.int("ordinal"), e.int("line"), key, e.strings("profiles"))
            }
            return ApnIndex(o.str("based_on_data_digest"), o.str("apn_xml_sha256"), rows)
        }
    }
}

// --- device-records.json (2.10) --------------------------------------------------------

data class Carrier(val scope: String, val profileId: String? = null, val mcc: String? = null, val mnc: String? = null, val gid1: String? = null, val spn: String? = null) {
    /** Line 8 of the record_key text. */
    val text: String get() = when (scope) {
        "all" -> "all"
        "profile" -> "profile:$profileId"
        else -> if (gid1 != null) "sim:$mcc-$mnc:gid1=${gid1.uppercase(java.util.Locale.ROOT)}" else "sim:$mcc-$mnc:spn=$spn"
    }
}

data class Target(val type: String, val key: String? = null, val rowKey: String? = null) {
    /** Line 9 of the record_key text. */
    val text: String get() = when (type) {
        "config" -> "config:$key"
        "apn" -> "apn:$rowKey"
        else -> "emergency_data:${rowKey ?: "default"}"
    }
}

data class SecondRead(val reader: String, val readAt: String, val basis: String, val reference: String)

data class Record(
    val key: String,
    val kind: String,
    val codename: String,
    val model: String,
    val romFamily: String,
    val romLine: String,
    val stackId: String,
    val stackFork: String?,
    val carrier: Carrier,
    val target: Target,
    val valueKind: String,
    val value: Any?,
    val claim: String,
    val label: String?,
    val result: String?,
    val firstMeasured: String?,
    val lastMeasured: String?,
    val validUntil: String?,
    val secondRead: SecondRead?,
    /** The record as received, for list_records. */
    val json: Map<String, Any?>,
    /** `owner`, or `local` for a record only this phone's app measured and keeps (round 31). */
    val tester: String = "owner",
) {
    val measured: Boolean get() = kind == "measured"

    companion object {
        val MEMBERS = setOf(
            "record_key", "kind", "device", "rom", "ims_stack", "carrier", "target", "value_kind", "value", "claim", "label", "basis",
            "level", "result", "method", "bearer", "n", "first_measured", "last_measured", "valid_until", "data", "tester",
            "differs_between_sims", "second_agent_read",
        )
        private val STACKS = setOf("aosp_imsstack", "phh_ims", "samsung_ims", "other")
        private val RESULTS = setOf("works", "calls_out", "sms_both_ways", "no_regression_passed", "winner_after_failure", "no_value_passed", "emergency_data_accepted", "emergency_data_refused", "emergency_default_won")
        private val LEVELS = setOf("L2", "L3", "sms_both_ways", "no_regression", "emergency_data")
        private val KEY = Regex("^r:[0-9a-f]{16}$")
        private val ROW_KEY = Regex("^a:[0-9a-f]{16}$")

        /** SHA-256 over the nine lines of 2.10. */
        fun computeKey(kind: String, codename: String, model: String, family: String, line: String, stack: String, fork: String?, carrier: Carrier, target: Target): String {
            val text = listOf(kind, codename, model.uppercase(java.util.Locale.ROOT), family, line, stack, fork.orEmpty(), carrier.text, target.text).joinToString("\n")
            return "r:" + Files.sha256(text).take(16)
        }

        /**
         * One record. [typeOf] types a config target's value by the registry (null: the key is
         * not in it). Failures are `invalid_value` (`record_invalid` at put_record). [localOk]:
         * a record of this phone's own app may say `tester: local` (round 31: a local-only
         * tester; such a record stays on the phone); a dataset's records are the owner's only.
         */
        fun parse(json: Any?, where: String, localOk: Boolean = false, typeOf: (String, Any?) -> Any): Record {
            val o = Obj.of(json, where).members(MEMBERS)
            fun bad(what: String): Nothing = refuse("invalid_value", "$where: $what")
            val kind = o.str("kind")
            if (kind != "configured_device" && kind != "measured") bad("kind $kind")
            val device = o.obj("device").members(setOf("codename", "model"))
            val rom = o.obj("rom").members(setOf("family", "line"))
            val stack = o.obj("ims_stack").members(setOf("id", "fork", "version"))
            if (stack.str("id") !in STACKS) bad("ims_stack ${stack.str("id")}")
            val c = o.obj("carrier")
            val carrier = when (c.str("scope")) {
                "all" -> c.members(setOf("scope")).let { Carrier("all") }
                "profile" -> c.members(setOf("scope", "profile_id")).let { Carrier("profile", profileId = it.str("profile_id")) }
                "sim_key" -> {
                    c.members(setOf("scope", "mcc", "mnc"), setOf("gid1", "spn"))
                    val gid1 = c.strOrNull("gid1")
                    val spn = c.strOrNull("spn")
                    if ((gid1 == null) == (spn == null)) bad("a sim_key carrier needs exactly one of gid1 and spn")
                    Carrier("sim_key", mcc = c.str("mcc"), mnc = c.str("mnc"), gid1 = gid1, spn = spn)
                }
                else -> bad("carrier scope ${c.str("scope")}")
            }
            val t = o.obj("target")
            val target = when (t.str("type")) {
                "config" -> Target("config", key = t.members(setOf("type", "key")).str("key"))
                "apn" -> {
                    t.members(setOf("type", "row_key", "op"))
                    if (t.str("op") != "omit") bad("apn op ${t.str("op")}")
                    Target("apn", rowKey = t.str("row_key").also { if (!ROW_KEY.matches(it)) bad("bad row_key") })
                }
                "emergency_data" -> Target("emergency_data", rowKey = t.members(setOf("type", "row_key")).strOrNull("row_key")?.also { if (!ROW_KEY.matches(it)) bad("bad row_key") })
                else -> bad("target type ${t.str("type")}")
            }
            val valueKind = o.str("value_kind")
            val value: Any? = when (valueKind) {
                "value" -> {
                    if (target.type != "config") bad("only a config target holds a value")
                    typeOf(target.key!!, o.any("value"))
                }
                "android_default" -> {
                    if (target.type != "config" || !o.isNull("value")) bad("android_default holds no value")
                    null
                }
                "none" -> {
                    if (!o.isNull("value")) bad("value_kind none holds no value")
                    null
                }
                else -> bad("value_kind $valueKind")
            }
            if (target.type != "config" && valueKind != "none") bad("an APN or emergency data record holds no value")
            val claim = o.str("claim")
            if (claim != "configured" && claim != "measured") bad("claim $claim")
            o.strOrNull("level")?.let { if (it !in LEVELS) bad("level $it") }
            val result = o.strOrNull("result")?.also { if (it !in RESULTS) bad("result $it") }
            if (target.type == "emergency_data" && result !in setOf("emergency_data_accepted", "emergency_data_refused", "emergency_default_won")) bad("an emergency data record needs an emergency data result")
            if (o.str("basis").length > 500) bad("basis over 500 characters")
            val tester = o.str("tester")
            if (tester != "owner" && !(localOk && tester == "local")) bad("tester $tester")
            val dates = listOf("first_measured", "last_measured", "valid_until").map { name -> o.strOrNull(name)?.also { if (!DATE.matches(it)) bad("$name is not a date") } }
            if (kind == "configured_device" && dates[2] != null) bad("a configured_device record does not expire")
            if (kind == "measured" && dates[2] == null) bad("a measured record needs valid_until")
            o.obj("data").members(setOf("commit", "data_digest"))
            o.listOrNull("differs_between_sims")?.forEachIndexed { i, d -> Obj.of(d, "$where.differs_between_sims[$i]").members(setOf("value", "last_measured")) }
            val read = o.objOrNull("second_agent_read")?.let { r ->
                r.members(setOf("reader", "read_at", "basis", "reference"))
                SecondRead(r.str("reader"), r.str("read_at"), r.str("basis"), r.str("reference"))
            }
            val key = o.str("record_key")
            if (!KEY.matches(key)) bad("bad record_key")
            val computed = computeKey(kind, device.str("codename"), device.str("model"), rom.str("family"), rom.str("line"), stack.str("id"), stack.strOrNull("fork"), carrier, target)
            if (computed != key) bad("record_key $key, recomputed $computed")
            return Record(
                key, kind, device.str("codename"), device.str("model"), rom.str("family"), rom.str("line"), stack.str("id"), stack.strOrNull("fork"),
                carrier, target, valueKind, value, claim, o.strOrNull("label"), result, dates[0], dates[1], dates[2], read, o.raw, tester,
            )
        }
    }
}

data class RecordsFile(val digest: String, val expireDays: Int, val records: List<Record>) {
    companion object {
        fun parse(bytes: ByteArray, typeOf: (String, Any?) -> Any): RecordsFile {
            val o = Obj.of(Json.parseBytes(bytes, Json.DATASET), "device-records.json")
            o.schemaVersion()
            o.members(setOf("schema_version", "based_on_data_digest", "expire_days", "records"))
            val records = o.list("records").mapIndexed { i, r -> Record.parse(r, "device-records.records[$i]", typeOf = typeOf) }
            if (records.map { it.key }.toSet().size != records.size) refuse("invalid_value", "device-records.json lists a record_key twice")
            return RecordsFile(o.str("based_on_data_digest"), o.int("expire_days"), records)
        }
    }
}

// --- group-a-reads.json (round 31, R31.7) ------------------------------------------------

/**
 * One group A read on main: an agent other than the value's builder read the key's effect for
 * this ROM family and IMS stack. Only `proceed` admits the value; `hold` keeps the Google lane.
 */
data class GroupARead(
    val id: String,
    val key: String,
    val value: Any,
    val family: String,
    val profileId: String,
    val romFamily: String,
    val stack: String,
    val fork: String?,
    val verdict: String,
    val reader: String,
    val readAt: String,
    val reference: String,
)

/** A read's finding that a key changes only what is shown or logged: with `proceed` the key leaves group A. */
data class KeyFinding(val key: String, val finding: String, val verdict: String, val reader: String, val readAt: String, val reference: String)

data class GroupAReadsFile(val digest: String, val reads: List<GroupARead>, val findings: List<KeyFinding>) {
    /** The keys a `proceed` display-only finding takes out of group A (the never-leave keys stay; [Rules.groupA]). */
    val left: Set<String> get() = findings.filter { it.verdict == "proceed" && it.finding == "display_only" }.map { it.key }.toSortedSet()

    companion object {
        private val ID = Regex("^gar:[0-9a-f]{16}$")
        private val VERDICTS = setOf("proceed", "hold")

        fun parse(bytes: ByteArray, typeOf: (String, Any?) -> Any): GroupAReadsFile {
            val o = Obj.of(Json.parseBytes(bytes, Json.DATASET), "group-a-reads.json")
            o.schemaVersion()
            o.members(setOf("schema_version", "based_on_data_digest", "reads", "key_findings"))
            val reads = o.list("reads").mapIndexed { i, r ->
                val e = Obj.of(r, "group-a-reads.reads[$i]").members(
                    setOf("read_id", "key", "value", "family", "profile_id", "rom_family", "ims_stack", "ims_stack_fork", "verdict", "reader", "read_at", "reference"),
                )
                val key = e.str("key")
                val value = typeOf(key, e.any("value"))
                val verdict = e.str("verdict")
                if (verdict !in VERDICTS) refuse("invalid_value", "group-a-reads.reads[$i]: verdict $verdict")
                if (e.str("reader").isBlank() || e.str("read_at").isBlank()) refuse("invalid_value", "group-a-reads.reads[$i]: no reader or read_at")
                val id = e.str("read_id")
                if (!ID.matches(id)) refuse("invalid_value", "group-a-reads.reads[$i]: bad read_id")
                GroupARead(id, key, value, e.str("family"), e.str("profile_id"), e.str("rom_family"), e.str("ims_stack"), e.strOrNull("ims_stack_fork"), verdict, e.str("reader"), e.str("read_at"), e.str("reference"))
            }
            if (reads.map { it.id }.toSet().size != reads.size) refuse("invalid_value", "group-a-reads.json lists a read_id twice")
            val findings = o.list("key_findings").mapIndexed { i, f ->
                val e = Obj.of(f, "group-a-reads.key_findings[$i]").members(setOf("key", "finding", "verdict", "reader", "read_at", "reference"))
                if (e.str("finding") != "display_only") refuse("invalid_value", "group-a-reads.key_findings[$i]: finding ${e.str("finding")}")
                if (e.str("verdict") !in VERDICTS) refuse("invalid_value", "group-a-reads.key_findings[$i]: verdict ${e.str("verdict")}")
                if (e.str("reader").isBlank()) refuse("invalid_value", "group-a-reads.key_findings[$i]: no reader")
                KeyFinding(e.str("key"), e.str("finding"), e.str("verdict"), e.str("reader"), e.str("read_at"), e.str("reference"))
            }
            return GroupAReadsFile(o.str("based_on_data_digest"), reads, findings)
        }
    }
}
