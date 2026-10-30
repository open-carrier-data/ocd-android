/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File

/** What Android's getDefaultConfig holds for a key (2.5.1). */
sealed class DefaultType {
    object Absent : DefaultType()
    object Null : DefaultType()
    /** [type] null: a value no registry type carries (a long[] or a double[]). */
    data class Of(val type: KeyType?) : DefaultType()
}

/** One verified dataset, read from its folder (local names of 2.1). */
class Dataset(
    val dir: File,
    val pin: Pin,
    val manifest: Manifest,
    val profiles: ProfilesFile,
    val lane: GoogleLane,
    /** `dataset` (its own google-lane.json) or `baked` (the build's, 2.7). */
    val laneSource: String,
    val records: RecordsFile,
    val index: ApnIndex,
    val xml: ByteArray,
    val doc: ApnXml.Doc,
    /** The optional group A reads (round 31, R31.7); null when the dataset carries none. */
    val reads: GroupAReadsFile? = null,
) {
    val datasetDigest: String get() = manifest.datasetDigest
    val commit: String get() = pin.commit

    val rowProfiles: Map<String, List<String>> by lazy {
        val out = HashMap<String, MutableList<String>>()
        for (r in index.rows) out.getOrPut(r.rowKey) { ArrayList() }.addAll(r.profiles)
        out
    }

    val profileRows: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        for (list in profiles.networks.values) for (p in list) out[p.id] = p.apnRows
        out
    }
}

/** The baked dataset as an app dataset is checked against it. */
class Baked(val manifest: Manifest, val types: Map<String, KeyType>, val lane: GoogleLane?)

/**
 * The reader's checks of spec 2.12, in order. It stops at the first failure with the check's
 * code; [checks] records every check that ran, for `install_status`.
 */
class Verifier(
    private val baked: Baked?,
    private val defaults: (String) -> DefaultType,
    private val platformApnVersion: Int?,
    private val crashGuardDigest: String?,
    private val sims: List<SimId>,
) {
    data class Check(val n: Int, val ok: Boolean, val detail: String)

    val checks = ArrayList<Check>()

    private inline fun <T> check(n: Int, ok: String, block: () -> T): T {
        try {
            return block().also { checks += Check(n, true, ok) }
        } catch (e: Refused) {
            checks += Check(n, false, "${e.code}: ${e.detail}")
            throw e
        } catch (e: Json.ParseException) {
            checks += Check(n, false, "parse_error: ${e.message}")
            throw Refused("parse_error", e.message.orEmpty())
        }
    }

    private fun file(dir: File, f: DatasetFile) = File(dir, f.local)

    /**
     * Checks 1 to 3, cheap enough for the provider's binder thread at `stage_dataset`: the pin
     * strict, every file present within its cap, and every blob SHA-1 (or the extra's SHA-256).
     */
    fun early(dir: File): Pin {
        val pin = check(1, "pin.json strict") {
            val pf = file(dir, DatasetFile.PIN)
            if (!pf.isFile) refuse("pin_invalid", "no pin.json")
            if (pf.length() > DatasetFile.PIN.cap) refuse("pin_invalid", "pin.json over its cap")
            Pin.parse(Json.decodeUtf8(Files.readBounded(pf, DatasetFile.PIN.cap)))
        }
        check(2, "every file present, within its cap, total within 64 MiB") {
            var total = file(dir, DatasetFile.PIN).length()
            val names = pin.files.keys.map { DatasetFile.byPath(it)!! } + pin.extra.keys.map { DatasetFile.byLocal(it)!! }
            for (f in names) {
                val local = file(dir, f)
                if (!local.isFile) refuse("bad_request", "${f.local} is missing")
                if (local.length() > f.cap) refuse("too_large", "${f.local} is over its cap of ${f.cap} bytes")
                total += local.length()
            }
            if (total > DatasetFile.TOTAL_CAP) refuse("too_large", "the set is over 64 MiB")
            val extraFiles = dir.listFiles().orEmpty().map { it.name }.filter { n -> DatasetFile.byLocal(n) != null && names.none { it.local == n } && n != DatasetFile.PIN.local }
            if (extraFiles.isNotEmpty()) refuse("bad_request", "${extraFiles.first()} is not in pin.json")
        }
        check(3, "every git blob SHA-1 equals pin.json") {
            for ((path, entry) in pin.files) {
                val f = DatasetFile.byPath(path)!!
                val d = Files.digests(file(dir, f))
                if (d.bytes != entry.bytes || d.blob != entry.blob) refuse("blob_mismatch", "${f.local}: blob ${d.blob.take(12)}, pin.json ${entry.blob.take(12)}")
            }
            for ((name, extra) in pin.extra) {
                val d = Files.digests(File(dir, name))
                if (d.bytes != extra.bytes || d.sha256 != extra.sha256) refuse("blob_mismatch", "$name: SHA-256 differs from pin.json's extra")
            }
        }
        return pin
    }

    /** Checks 1 to 13 for the dataset in [dir]. [role] `build` skips 11 to 13, which compare a dataset with the build's own. */
    fun verify(dir: File, role: String = "app"): Dataset {
        val pin = early(dir)
        val manifestBytes = Files.readBounded(file(dir, DatasetFile.MANIFEST), DatasetFile.MANIFEST.cap)
        val manifest = check(4, "manifest strict, schema_version 1") { Manifest.parse(manifestBytes) }
        check(1, "pin.json names exactly the manifest's files") {
            val want = manifest.files.map { it.path }.toSet() + DatasetFile.MANIFEST.path
            if (pin.files.keys != want) refuse("pin_invalid", "pin.json names ${pin.files.size} files, the manifest ${want.size}")
            if (pin.extra.isNotEmpty() && manifest.googleLaneIncluded) refuse("pin_invalid", "extra is allowed only while the manifest says google_lane absent")
        }
        check(5, "SHA-256 and sizes equal the manifest, dataset_digest recomputed") {
            for (e in manifest.files) {
                val d = Files.digests(File(dir, e.name))
                if (d.bytes != e.bytes || d.sha256 != e.sha256) refuse("sha256_mismatch", "${e.name} differs from the manifest")
            }
            if (manifest.recomputedDigest() != manifest.datasetDigest) refuse("dataset_digest_mismatch", "dataset_digest recomputed differs")
        }

        val xmlBytes = Files.readBounded(file(dir, DatasetFile.APN_XML), DatasetFile.APN_XML.cap)
        val trees = HashMap<DatasetFile, Map<String, Any?>>()
        check(6, "every file names the manifest's data_digest") {
            val jsonFiles = listOf(DatasetFile.PROFILES, DatasetFile.DEVICE_RECORDS, DatasetFile.APN_INDEX) +
                (if (manifest.googleLaneIncluded) listOf(DatasetFile.GOOGLE_LANE) else emptyList()) +
                (if (manifest.entry(DatasetFile.GROUP_A_READS) != null) listOf(DatasetFile.GROUP_A_READS) else emptyList())
            for (f in jsonFiles) {
                @Suppress("UNCHECKED_CAST")
                val tree = Json.parseBytes(Files.readBounded(file(dir, f), f.cap), Json.DATASET) as? Map<String, Any?>
                    ?: refuse("parse_error", "${f.local} is not an object")
                if (tree["based_on_data_digest"] != manifest.dataDigest) refuse("data_digest_disagree", "${f.local} names another data_digest")
                trees[f] = tree
            }
            val header = Regex("^\\s*data_digest:\\s*(sha256:[0-9a-f]{64})\\s*$", RegexOption.MULTILINE)
                .find(String(xmlBytes, 0, minOf(xmlBytes.size, 8192), Charsets.UTF_8))?.groupValues?.get(1)
            if (header != manifest.dataDigest) refuse("data_digest_disagree", "the apns-conf.xml header names another data_digest")
        }

        // 7 and 8: strict parse and the closed, typed registry. The registry comes first, so
        // every other file is typed by it as it is read.
        val profilesTree = trees.getValue(DatasetFile.PROFILES)
        val registry = check(7, "profiles.json registry strict") {
            Obj.of(profilesTree, "profiles.json").also { it.schemaVersion() }
                .members(setOf("schema_version", "based_on_data_digest", "keys", "groups", "reserved", "networks"))
            ProfilesFile.parseKeys(profilesTree["keys"])
        }
        check(8, "registry closed and typed") { checkRegistry(registry) }
        val typeOf: (String, Any?) -> Any = { key, json ->
            val info = registry[key] ?: refuse("unknown_key", "$key is not in the registry")
            Types.typed(key, info.type, json)
        }

        val lane: GoogleLane
        val laneSource: String
        if (manifest.googleLaneIncluded) {
            val own = check(7, "google-lane.json strict") { GoogleLane.parse(Files.readBounded(file(dir, DatasetFile.GOOGLE_LANE), DatasetFile.GOOGLE_LANE.cap), typeOf) }
            lane = baked?.lane?.let { own.filledFrom(it) } ?: own
            laneSource = "dataset"
        } else if (pin.extra.containsKey(DatasetFile.GOOGLE_LANE.local)) {
            // The baked private lane (2.1, 2.7): it names the build pin's digest.
            lane = check(7, "google-lane.json (private lane at the build's pin) strict") {
                GoogleLane.parse(Files.readBounded(file(dir, DatasetFile.GOOGLE_LANE), DatasetFile.GOOGLE_LANE.cap), typeOf).also {
                    if (it.state != "private_lane_at_build_pin") refuse("invalid_value", "the extra google-lane.json is not the private lane at the build's pin")
                }
            }
            laneSource = "dataset"
        } else {
            lane = baked?.lane ?: run {
                checks += Check(12, false, "google_lane_missing: no Google lane in the dataset or the build")
                refuse("google_lane_missing", "no Google lane in the dataset or the build")
            }
            laneSource = "baked"
        }

        val profiles = check(7, "profiles.json strict") {
            val o = Obj.of(profilesTree, "profiles.json")
            val groups = ProfilesFile.parseGroups(o.any("groups"))
            parseReserved(o.any("reserved"))
            val networks = LinkedHashMap<String, List<Profile>>()
            val net = o.obj("networks")
            for (key in net.keys) {
                val n = net.obj(key).members(setOf("mcc", "mnc", "profiles"))
                if ("${n.str("mcc")}-${n.str("mnc")}" != key) refuse("invalid_value", "network $key holds ${n.str("mcc")}-${n.str("mnc")}")
                networks[key] = n.list("profiles").mapIndexed { i, p -> ProfilesFile.parseProfile(p, "networks.$key.profiles[$i]", typeOf) }
            }
            ProfilesFile(o.str("based_on_data_digest"), withLaneKeys(registry, lane), groups, networks)
        }
        trees.remove(DatasetFile.PROFILES)
        check(8, "profile copies equal, networks listed, fill entries known") { checkProfiles(profiles, lane, laneSource) }
        val records = check(7, "device-records.json strict") {
            RecordsFile.parse(Files.readBounded(file(dir, DatasetFile.DEVICE_RECORDS), DatasetFile.DEVICE_RECORDS.cap), typeOf)
        }
        val index = check(7, "apn-index.json strict") { ApnIndex.parse(Files.readBounded(file(dir, DatasetFile.APN_INDEX), DatasetFile.APN_INDEX.cap)) }
        val reads = if (manifest.entry(DatasetFile.GROUP_A_READS) == null) null else check(7, "group-a-reads.json strict, values typed") {
            GroupAReadsFile.parse(Files.readBounded(file(dir, DatasetFile.GROUP_A_READS), DatasetFile.GROUP_A_READS.cap), typeOf)
        }
        trees.clear()

        val doc = check(9, "<apns version> equals the manifest's and the platform's") {
            val doc = ApnXml.parse(xmlBytes)
            if (doc.version != manifest.apnDatabaseVersion) refuse("apn_version", "apns-conf.xml version ${doc.version}, the manifest ${manifest.apnDatabaseVersion}")
            if (platformApnVersion == null) refuse("apn_version", "the platform's APN version could not be read")
            if (doc.version != platformApnVersion) refuse("apn_version", "apns-conf.xml version ${doc.version}, the platform's $platformApnVersion")
            doc
        }
        check(10, "apn-index.json agrees with the XML") {
            ApnXml.checkIndex(doc, index, manifest.entry(DatasetFile.APN_XML)!!.sha256)
        }
        val dataset = Dataset(dir, pin, manifest, profiles, lane, laneSource, records, index, xmlBytes, doc, reads)
        if (role == "build") return dataset

        check(11, "not older than the build's pin") {
            val b = baked?.manifest
            if (b != null && b.datasetDigest != manifest.datasetDigest && manifest.generatedAt <= b.generatedAt) {
                refuse("older_than_build", "generated ${manifest.generatedAt}, the build's dataset ${b.generatedAt}")
            }
        }
        check(12, "a Google lane available; every SIM's fill entries resolve") {
            for (sim in sims) {
                if (sim.noSim) continue
                val top = Matcher.stack(profiles.networks[sim.network].orEmpty(), sim).lastOrNull() ?: continue
                val pick = Matcher.pickFill(top.fill, sim) { it in lane.entries }
                if (pick.skippedMissing.isNotEmpty()) refuse("fill_missing_for_sim", "a SIM's fill entry ${pick.skippedMissing.first()} is not in the Google lane used")
            }
        }
        check(13, "not the dataset the crash guard tripped on") {
            if (crashGuardDigest != null && crashGuardDigest == manifest.datasetDigest) refuse("crash_guard_dataset", "the crash guard tripped on this dataset_digest")
        }
        return dataset
    }

    private fun parseReserved(json: Any?) {
        val o = Obj.of(json, "profiles.reserved").members(setOf("default", "no_sim"))
        for (name in listOf("default", "no_sim")) {
            val fill = ProfilesFile.parseFill(o.obj(name).members(setOf("fill")).any("fill"), "reserved.$name")
            if (fill != listOf(FillItem(name, null))) refuse("invalid_value", "reserved.$name is not the fixed fill")
        }
    }

    /**
     * The registry plus the keys the lane got from the build's lane ([GoogleLane.filledFrom]), with
     * the build's types: main's registry leaves out keys whose every value its privacy guard holds
     * back, so without them the serving index cannot type those values.
     */
    private fun withLaneKeys(registry: Map<String, KeyInfo>, lane: GoogleLane): Map<String, KeyInfo> {
        val missing = lane.entries.values.flatMap { it.keys }.filterTo(LinkedHashSet()) { it !in registry }
        if (missing.isEmpty()) return registry
        val types = baked?.types ?: refuse("unknown_key", "lane keys ${missing.first()} without a type")
        return LinkedHashMap(registry).apply {
            for (k in missing) put(k, KeyInfo(types[k] ?: refuse("unknown_key", "$k is in no registry"), null, listOf("google"), null))
        }
    }

    /** 2.5.1 point 2: every registry key is the build's with the same type, or Android's with that type. */
    private fun checkRegistry(registry: Map<String, KeyInfo>) {
        for ((key, info) in registry) {
            val bakedType = baked?.types?.get(key)
            if (baked == null || bakedType == info.type) continue
            when (val d = defaults(key)) {
                DefaultType.Absent -> if (bakedType != null) refuse("wrong_type", "$key is a ${info.type.wire}, the build's registry says ${bakedType.wire}") else refuse("unknown_key", "$key is neither in the build's registry nor Android's")
                DefaultType.Null -> if (info.type !in Types.NULLABLE) refuse("wrong_type", "$key is a ${info.type.wire}; Android's default is null")
                is DefaultType.Of -> if (d.type != info.type) refuse("wrong_type", "$key is a ${info.type.wire}; Android's default is a ${d.type?.wire ?: "other type"}")
            }
        }
    }

    private fun checkProfiles(profiles: ProfilesFile, lane: GoogleLane, laneSource: String) {
        val seen = HashMap<String, Profile>()
        for ((net, list) in profiles.networks) {
            for (p in list) {
                if (p.match.mccmnc.none { it.key == net }) refuse("invalid_value", "${p.id} is listed under $net, which it does not match")
                val bare = p.copy(fill = emptyList())
                val first = seen.putIfAbsent(p.id, bare)
                if (first != null && first != bare) refuse("invalid_value", "${p.id}: its copies under several networks differ")
                if (laneSource == "dataset") {
                    p.fill.firstOrNull { it.entry !in lane.entries }?.let { refuse("invalid_value", "${p.id}: fill entry ${it.entry} is not in google-lane.json") }
                }
            }
        }
        for ((id, p) in seen) {
            for (n in p.match.mccmnc) {
                if (profiles.networks[n.key]?.none { it.id == id } != false) refuse("invalid_value", "$id is missing under its network ${n.key}")
            }
        }
    }
}
