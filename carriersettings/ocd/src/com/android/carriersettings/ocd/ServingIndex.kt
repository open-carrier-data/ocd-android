/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File

/**
 * The serving index of one dataset (spec 9.3), built once on the worker, off every binder:
 * - `meta.json`: commit, digests, registry types and forks, the resolved group A, B and X6
 *   sets, and the Google `default` and `no_sim` values;
 * - `net/<mcc>-<mnc>.json`: that network's profiles, each fill entry resolved inline;
 * - `records.json`: the dataset's records whose cell matches this phone;
 * - `complete`: written last, so a half-built index is never read.
 * A load reads `meta.json`, one network file and `records.json` (9.4).
 */
object ServingIndex {
    const val SCHEMA = "ocd-index/1"

    fun write(dataset: Dataset, dir: File, role: String, cell: BuildCell?, cellKey: String) {
        Files.deleteTree(dir)
        val net = File(dir, "net")
        net.mkdirs()
        val reg = dataset.profiles.keys
        val types = reg.mapValues { it.value.type }
        fun values(map: Map<String, Any>): Map<String, Any?> = map.entries.associateTo(LinkedHashMap()) { (k, v) -> k to Types.toJson(types.getValue(k), v) }
        val groupA = Rules.groupA(reg.keys, dataset.profiles.groups.aKeys, dataset.reads?.left.orEmpty())
        val reads = cellReads(dataset.reads, cell).map { r ->
            linkedMapOf(
                "read_id" to r.id, "key" to r.key, "value" to Types.toJson(types.getValue(r.key), r.value), "family" to r.family, "profile_id" to r.profileId,
                "rom_family" to r.romFamily, "ims_stack" to r.stack, "ims_stack_fork" to r.fork, "verdict" to r.verdict, "reader" to r.reader,
                "read_at" to r.readAt, "reference" to r.reference,
            )
        }
        val meta = linkedMapOf(
            "schema" to SCHEMA,
            "role" to role,
            "commit" to dataset.commit,
            "dataset_digest" to dataset.datasetDigest,
            "data_digest" to dataset.manifest.dataDigest,
            "generated_at" to dataset.manifest.generatedAt,
            "google_lane_source" to dataset.laneSource,
            "cell_key" to cellKey,
            "types" to types.mapValues { it.value.wire },
            "forks" to reg.filterValues { it.fork != null }.mapValues { it.value.fork },
            "group_a" to groupA.toList(),
            "group_b" to Rules.groupB(dataset.profiles.groups.bKeys).toList(),
            "x6_networks" to Rules.x6Networks(dataset.profiles.groups.x6Networks).toList(),
            "google_default" to values(dataset.lane.entries.getValue("default")),
            "google_no_sim" to values(dataset.lane.entries.getValue("no_sim")),
            "apn_xml_sha256" to dataset.manifest.entry(DatasetFile.APN_XML)!!.sha256,
            "group_a_reads" to reads,
        )
        for ((key, list) in dataset.profiles.networks) {
            val profiles = list.map { p ->
                linkedMapOf(
                    "id" to p.id,
                    "kind" to p.kind,
                    "name" to p.name,
                    "match" to p.match.toJson(),
                    "specificity" to p.specificity,
                    "fill" to p.fill.map { f ->
                        linkedMapOf(
                            "entry" to f.entry,
                            "when" to f.condition?.let { linkedMapOf("kind" to it.kind, "value" to it.value) },
                            "values" to dataset.lane.entries[f.entry]?.let { values(it) },
                        )
                    },
                    "values" to values(p.values),
                    "alternatives" to p.alternatives.mapValues { (k, alts) -> alts.map { linkedMapOf("value" to Types.toJson(types.getValue(k), it.value), "families" to it.families) } },
                    "apn_rows" to p.apnRows,
                )
            }
            Files.atomicWrite(File(net, "$key.json"), Json.write(linkedMapOf("network" to key, "profiles" to profiles)).toByteArray(Charsets.UTF_8))
        }
        val records = dataset.records.records.filter { cell != null && cell.matches(it) }.map { it.json }
        Files.atomicWrite(File(dir, "records.json"), Json.write(linkedMapOf("records" to records)).toByteArray(Charsets.UTF_8))
        Files.atomicWrite(File(dir, "meta.json"), Json.write(meta).toByteArray(Charsets.UTF_8))
        Files.atomicWrite(File(dir, "complete"), "${dataset.datasetDigest} ${dataset.commit}".toByteArray(Charsets.UTF_8))
    }

    /** The `proceed` and `hold` reads for this phone's ROM family and IMS stack (a fork-less read covers every fork). */
    fun cellReads(file: GroupAReadsFile?, cell: BuildCell?): List<GroupARead> {
        cell ?: return emptyList()
        return file?.reads.orEmpty().filter { it.romFamily == cell.romFamily && it.stack == cell.stackId && (it.fork == null || it.fork == cell.stackFork) }
    }

    /**
     * Whether [dir] holds a complete index of this dataset at this commit (two pins of the same
     * files share a dataset_digest, but the version string names the served pin's commit).
     */
    fun complete(dir: File, datasetDigest: String?, cellKey: String?, commit: String? = null): Boolean {
        val marker = File(dir, "complete")
        if (!marker.isFile) return false
        val text = runCatching { marker.readText() }.getOrNull() ?: return false
        if (datasetDigest != null && text.substringBefore(' ') != datasetDigest) return false
        if (commit != null && text.substringAfter(' ') != commit) return false
        if (cellKey != null) {
            val meta = runCatching { Obj.of(Json.parseBytes(Files.readBounded(File(dir, "meta.json"), 4L * 1024 * 1024)), "meta.json") }.getOrNull() ?: return false
            if (meta.raw["cell_key"] != cellKey) return false
        }
        return true
    }

    /** The index in [dir] as a [ServingData]; network files are read when a load asks for them. */
    fun read(dir: File): ServingData {
        val meta = Obj.of(Json.parseBytes(Files.readBounded(File(dir, "meta.json"), 8L * 1024 * 1024)), "meta.json")
        if (meta.str("schema") != SCHEMA) refuse("storage", "index schema ${meta.str("schema")}")
        val to = meta.obj("types")
        val types = to.keys.associateWith { KeyType.of(to.str(it)) ?: refuse("storage", "index type of $it") }
        val fo = meta.obj("forks")
        val forks = fo.keys.associateWith { fo.str(it) }
        fun values(o: Obj): Map<String, Any> = o.keys.filter { it in types }.associateWithTo(LinkedHashMap()) { k -> Types.fromStored(k, types.getValue(k), o.any(k)) }
        val records = readRecords(File(dir, "records.json"), types)
        val reads = meta.listOrNull("group_a_reads").orEmpty().map { raw ->
            val r = Obj.of(raw, "group_a_reads")
            val key = r.str("key")
            GroupARead(
                r.str("read_id"), key, Types.fromStored(key, types[key] ?: refuse("storage", "index read of $key"), r.any("value")), r.str("family"), r.str("profile_id"),
                r.str("rom_family"), r.str("ims_stack"), r.strOrNull("ims_stack_fork"), r.str("verdict"), r.str("reader"), r.str("read_at"), r.str("reference"),
            )
        }
        val cache = java.util.concurrent.ConcurrentHashMap<String, List<ServedProfile>>()
        return ServingData(
            role = meta.str("role"),
            commit = meta.str("commit"),
            datasetDigest = meta.str("dataset_digest"),
            types = types,
            forks = forks,
            groupA = meta.strings("group_a").toSet(),
            groupB = meta.strings("group_b").toSet(),
            x6Networks = meta.strings("x6_networks").toSet(),
            googleDefault = values(meta.obj("google_default")),
            googleNoSim = values(meta.obj("google_no_sim")),
            records = records,
            network = { key ->
                cache[key] ?: run {
                    val f = File(File(dir, "net"), "$key.json")
                    if (!f.isFile) emptyList() else runCatching { readNetwork(f, types) }.getOrNull()
                }?.also { cache[key] = it }
            },
            groupAReads = reads,
        )
    }

    fun readRecords(file: File, types: Map<String, KeyType>): List<Record> {
        if (!file.isFile) return emptyList()
        val o = Obj.of(Json.parseBytes(Files.readBounded(file, 8L * 1024 * 1024)), file.name)
        return o.list("records").mapIndexed { i, r ->
            Record.parse(r, "${file.name}[$i]") { k, v -> Types.fromStored(k, types[k] ?: refuse("unknown_key", k), v) }
        }
    }

    private fun readNetwork(file: File, types: Map<String, KeyType>): List<ServedProfile> {
        val o = Obj.of(Json.parseBytes(Files.readBounded(file, 16L * 1024 * 1024), Json.DATASET), file.name)
        fun typed(k: String, v: Any?): Any = Types.fromStored(k, types[k] ?: refuse("unknown_key", k), v)
        return o.list("profiles").map { raw ->
            val p = Obj.of(raw, "profile")
            val fill = ArrayList<FillItem>()
            val fillValues = LinkedHashMap<String, Map<String, Any>?>()
            p.list("fill").forEach { f ->
                val fo = Obj.of(f, "fill").members(setOf("entry", "when", "values"))
                val w = fo.objOrNull("when")?.let { When(it.str("kind"), it.str("value")) }
                fill += FillItem(fo.str("entry"), w)
                fillValues[fo.str("entry")] = fo.objOrNull("values")?.let { v -> v.keys.filter { it in types }.associateWithTo(LinkedHashMap()) { k -> typed(k, v.any(k)) } }
            }
            val vo = p.obj("values")
            val ao = p.obj("alternatives")
            val profile = Profile(
                p.str("id"), p.str("kind"), p.str("name"), Match.parse(p.any("match"), p.str("id")), p.int("specificity"), fill,
                vo.keys.filter { it in types }.associateWithTo(LinkedHashMap()) { k -> typed(k, vo.any(k)) },
                emptyMap(),
                ao.keys.filter { it in types }.associateWith { k -> ao.list(k).map { a -> Obj.of(a, "alt").let { Alternative(typed(k, it.any("value")), it.strings("families")) } } },
                p.int("apn_rows"),
            )
            ServedProfile(profile, fillValues)
        }
    }

    /** A [ServingData] straight from a verified dataset (explain on a staged dataset, the checks, tests). */
    fun direct(dataset: Dataset, role: String, cell: BuildCell?): ServingData {
        val reg = dataset.profiles.keys
        return ServingData(
            role = role,
            commit = dataset.commit,
            datasetDigest = dataset.datasetDigest,
            types = reg.mapValues { it.value.type },
            forks = reg.filterValues { it.fork != null }.mapValues { it.value.fork!! },
            groupA = Rules.groupA(reg.keys, dataset.profiles.groups.aKeys, dataset.reads?.left.orEmpty()),
            groupB = Rules.groupB(dataset.profiles.groups.bKeys),
            x6Networks = Rules.x6Networks(dataset.profiles.groups.x6Networks),
            googleDefault = dataset.lane.entries.getValue("default"),
            googleNoSim = dataset.lane.entries.getValue("no_sim"),
            records = dataset.records.records.filter { cell != null && cell.matches(it) },
            network = { key -> dataset.profiles.networks[key].orEmpty().map { p -> ServedProfile(p, p.fill.associate { it.entry to dataset.lane.entries[it.entry] }) } },
            groupAReads = cellReads(dataset.reads, cell),
        )
    }

    /**
     * 9.4 point 2: the baked dataset's one network, streamed from `profiles.json` and
     * `google-lane.json` with every other network and entry skipped, when the build's index
     * is not built yet. The baked files were checked by the build; this read is not a check.
     */
    fun streamBaked(bakedDir: File, network: String, cell: BuildCell?): ServingData {
        val profilesText = Json.decodeUtf8(Files.readBounded(File(bakedDir, DatasetFile.PROFILES.local), DatasetFile.PROFILES.cap))
        val keepProfiles: (List<String>) -> Boolean = { p -> p[0] in setOf("keys", "groups") || (p[0] == "networks" && (p.size < 2 || p[1] == network)) }
        val tree = Obj.of(Json.parse(profilesText, Json.DATASET, keepProfiles), "profiles.json")
        val registry = ProfilesFile.parseKeys(tree.any("keys"))
        val groups = ProfilesFile.parseGroups(tree.any("groups"))
        val typeOf: (String, Any?) -> Any = { k, v -> Types.typed(k, registry[k]?.type ?: refuse("unknown_key", k), v) }
        val profiles = tree.objOrNull("networks")?.objOrNull(network)?.list("profiles")?.mapIndexed { i, p -> ProfilesFile.parseProfile(p, "networks.$network[$i]", typeOf) }.orEmpty()
        val wanted = profiles.flatMap { p -> p.fill.map { it.entry } }.toSet() + setOf("default", "no_sim")
        val laneText = Json.decodeUtf8(Files.readBounded(File(bakedDir, DatasetFile.GOOGLE_LANE.local), DatasetFile.GOOGLE_LANE.cap))
        val lane = Obj.of(Json.parse(laneText, Json.DATASET) { p -> p[0] == "entries" && (p.size < 2 || p[1] in wanted) }, "google-lane.json").obj("entries")
        val entries = lane.keys.associateWith { name -> lane.obj(name).obj("values").let { v -> v.keys.associateWithTo(LinkedHashMap()) { k -> typeOf(k, v.any(k)) } } }
        val pin = Pin.parse(Json.decodeUtf8(Files.readBounded(File(bakedDir, DatasetFile.PIN.local), DatasetFile.PIN.cap)))
        val manifest = Manifest.parse(Files.readBounded(File(bakedDir, DatasetFile.MANIFEST.local), DatasetFile.MANIFEST.cap))
        val types = registry.mapValues { it.value.type }
        val records = runCatching {
            RecordsFile.parse(Files.readBounded(File(bakedDir, DatasetFile.DEVICE_RECORDS.local), DatasetFile.DEVICE_RECORDS.cap), typeOf).records
        }.getOrDefault(emptyList()).filter { cell != null && cell.matches(it) }
        // A group A read that cannot be read admits nothing: the Google lane serves.
        val reads = manifest.entry(DatasetFile.GROUP_A_READS)?.let {
            runCatching { GroupAReadsFile.parse(Files.readBounded(File(bakedDir, DatasetFile.GROUP_A_READS.local), DatasetFile.GROUP_A_READS.cap), typeOf) }.getOrNull()
        }
        return ServingData(
            "build", pin.commit, manifest.datasetDigest, types, registry.filterValues { it.fork != null }.mapValues { it.value.fork!! },
            Rules.groupA(registry.keys, groups.aKeys, reads?.left.orEmpty()), Rules.groupB(groups.bKeys), Rules.x6Networks(groups.x6Networks),
            entries["default"] ?: refuse("google_lane_missing", "no default entry"), entries["no_sim"] ?: refuse("google_lane_missing", "no no_sim entry"),
            records,
            { key -> if (key == network) profiles.map { p -> ServedProfile(p, p.fill.associate { it.entry to entries[it.entry] }) } else null },
            cellReads(reads, cell),
        )
    }
}
