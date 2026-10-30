/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File

data class Halted(val sinceMs: Long, val reason: String)

/** The app's declaration whether dialled tests run on this phone (round 31 SM7, `set_tester`). */
data class TesterDecl(val active: Boolean, val sinceMs: Long, val reason: String)

/**
 * The voice-loss hold (round 31, safeMode (c)): only the build's dataset restored voice, so the
 * build serves, both parts, until the app's split releases it. [valuesDigest] fingerprints the
 * values that differed from the build's when voice was lost.
 */
data class VoiceHold(val sinceMs: Long, val reason: String, val datasetDigest: String, val valuesDigest: String)

/** A second voice loss under the same differing values: a stop event until `resume` or a newer build. */
data class VoiceStop(val sinceMs: Long, val reason: String, val datasetDigest: String, val incremental: String)

/** A SIM's preferred APN, recorded by its fields (never its row ID alone; user and password are not kept). */
data class PreferredApn(val subId: Int, val fields: Map<String, String>, val rowId: Long?)

/**
 * A temporary APN set, stored before it is sent, so a reboot with it in place restores the full
 * set. [kind] `emergency_default`: the default emergency APN test (round 31,
 * defaultEmergencyApnTest), the installed commit's composed file minus only [removedRows], this
 * SIM's matched emergency rows. [kind] `apn_candidate`: round 32's APN candidate trial (D8 and D7
 * rule 4, rounds 33, 34 and 36), the installed commit's file with one candidate's operations for
 * this SIM ([candidate]); [removedRows] are the rows it leaves out. One at a time, of either kind.
 */
data class ApnTestSet(
    val subId: Int,
    val sinceMs: Long,
    val state: String,
    val removedRows: List<String>,
    val sha512: String,
    val fullSha512: String,
    val datasetDigest: String,
    val rows: Int,
    val preferred: List<PreferredApn>,
    val ims: String?,
    val kind: String = EMERGENCY_DEFAULT,
    val candidate: CandidateTrial? = null,
) {
    val isCandidate: Boolean get() = kind == APN_CANDIDATE

    /** What status, explain and the log call it (the app reads "candidate" in it). */
    val label: String get() = if (isCandidate) "APN candidate test set" else "emergency default test set"

    companion object {
        const val EMERGENCY_DEFAULT = "emergency_default"
        const val APN_CANDIDATE = "apn_candidate"
    }
}

/**
 * Round 32's candidate trial in an [ApnTestSet]: the app's candidate ID, rule, kind and role, the
 * rows it writes anew and adds, and what CarrierSettings watches to end it without the app: the
 * SIM's slot and network code, the subscriptions in the phone when it went in, mobile data before
 * it, and the time it is restored by ([untilMs]). Subscription IDs only, no SIM identifier.
 */
data class CandidateTrial(
    val id: String,
    val rule: String,
    val kind: String,
    val role: String?,
    val changedRows: List<String>,
    val addedRows: List<String>,
    val slot: Int,
    val network: String,
    val subs: List<Int>,
    val dataBefore: Boolean?,
    val untilMs: Long,
)

/** The crash guard (8.3): the dataset it tripped on; [onBuild] when it tripped again on the build's own. */
data class CrashGuard(val datasetDigest: String, val sinceMs: Long, val onBuild: Boolean)

data class InstalledRef(
    val datasetDigest: String,
    val commit: String,
    val dataDigest: String,
    val generatedAt: String,
    val installedAtMs: Long,
    val laneSource: String,
)

/** The APN part as CarrierSettings last installed it (7.4): VERSION sent, SHA-512 of the file, confirmation. */
data class ApnState(
    val lastVersion: Long = 0,
    val sha512: String? = null,
    val datasetDigest: String? = null,
    val confirmedAtMs: Long = 0,
    val rows: Int = 0,
    val rawRows: Int = 0,
    val lastError: String? = null,
)

/** What a subscription was last served: the version string, the dataset, and whether SM7 applied. */
data class ServedRef(val version: String, val datasetDigest: String, val atMs: Long, val sm7: Boolean = false)

/**
 * CarrierSettings's own state (spec 9.2), `state.json` in device-protected storage. No IMSI,
 * ICCID, hash of either, or number: SIMs appear only as subscription IDs.
 */
data class State(
    val installed: InstalledRef? = null,
    /** What the CarrierConfig part serves: `installed` or `build`. */
    val active: String = "build",
    val servingReason: String = "no_dataset",
    val halted: Halted? = null,
    /** The writer app's state when CarrierSettings last looked: enabled, disabled, absent or unknown (R31.4). */
    val writerState: String = "unknown",
    val tester: TesterDecl? = null,
    val hold: VoiceHold? = null,
    /** The last released hold, for "a second loss under the same values". */
    val lastHold: VoiceHold? = null,
    val voiceStop: VoiceStop? = null,
    /** Dataset digests a second voice loss stopped; never installed again. */
    val blocked: Set<String> = emptySet(),
    val apnTest: ApnTestSet? = null,
    /** The last notice for the owner (a stop event CarrierSettings raised itself). */
    val notice: String? = null,
    val crashGuard: CrashGuard? = null,
    val stubLoads: Int = 0,
    val loading: Set<Int> = emptySet(),
    val pullAgain: Boolean = false,
    val apn: ApnState = ApnState(),
    val pending: Set<Int> = emptySet(),
    val pendingWhy: String = "",
    val pendingSinceMs: Long = 0,
    val emergencySeenAtMs: Long = 0,
    val emergencyUp: Boolean = false,
    val lastCallEndedAtMs: Long = 0,
    val lastNotifyMs: Map<Int, Long> = emptyMap(),
    val served: Map<Int, ServedRef> = emptyMap(),
    /** The X5 keys' last served values per subscription (5.4); null: the key was absent. */
    val x5Served: Map<Int, Map<String, Boolean?>> = emptyMap(),
    /** record_key -> valid_until (empty: no expiry): records once tested here as local copies (6.4). */
    val x5TestedHere: Map<String, String> = emptyMap(),
    val mismatches: Int = 0,
    val mismatchSinceMs: Long = 0,
    /** Subscriptions whose reload is not proven yet, with the version string expected (7.6). */
    val proving: Map<Int, String> = emptyMap(),
    val lastCheckMs: Long = 0,
    val lastCheck: String = "",
    /** The last install job, for status after a restart. */
    val lastJob: Map<String, Any?>? = null,
    /** CarrierSettings's own install (8.1) waits for this refusal to clear; the retry alarm runs it. */
    val ownInstall: String? = null,
    /** When APN candidate sets went in within the last day: the caps' backstop (round 32, D8 rule 4). */
    val candidateTrials: List<Long> = emptyList(),
) {
    val servingInstalled: Boolean get() = active == "installed" && installed != null && crashGuard == null && hold == null && voiceStop == null

    companion object {
        fun encode(s: State): String = Json.write(
            linkedMapOf(
                "schema" to "ocd-state/1",
                "installed" to s.installed?.let {
                    linkedMapOf("dataset_digest" to it.datasetDigest, "commit" to it.commit, "data_digest" to it.dataDigest, "generated_at" to it.generatedAt, "installed_at_ms" to it.installedAtMs, "google_lane_source" to it.laneSource)
                },
                "active" to s.active,
                "serving_reason" to s.servingReason,
                "halted" to s.halted?.let { linkedMapOf("since_ms" to it.sinceMs, "reason" to it.reason) },
                "writer_state" to s.writerState,
                "tester" to s.tester?.let { linkedMapOf("active" to it.active, "since_ms" to it.sinceMs, "reason" to it.reason) },
                "hold" to s.hold?.let(::holdJson),
                "last_hold" to s.lastHold?.let(::holdJson),
                "voice_stop" to s.voiceStop?.let { linkedMapOf("since_ms" to it.sinceMs, "reason" to it.reason, "dataset_digest" to it.datasetDigest, "incremental" to it.incremental) },
                "blocked" to s.blocked.sorted(),
                "apn_test" to s.apnTest?.let { t ->
                    linkedMapOf(
                        "sub_id" to t.subId, "since_ms" to t.sinceMs, "state" to t.state, "removed_rows" to t.removedRows, "sha512" to t.sha512,
                        "full_sha512" to t.fullSha512, "dataset_digest" to t.datasetDigest, "rows" to t.rows,
                        "preferred" to t.preferred.map { linkedMapOf("sub_id" to it.subId, "fields" to it.fields, "row_id" to it.rowId) }, "ims" to t.ims,
                        "kind" to t.kind,
                        "candidate" to t.candidate?.let { c ->
                            linkedMapOf(
                                "id" to c.id, "rule" to c.rule, "kind" to c.kind, "role" to c.role, "changed_rows" to c.changedRows, "added_rows" to c.addedRows,
                                "slot" to c.slot, "network" to c.network, "subs" to c.subs, "data_before" to c.dataBefore, "until_ms" to c.untilMs,
                            )
                        },
                    )
                },
                "notice" to s.notice,
                "crash_guard" to s.crashGuard?.let { linkedMapOf("dataset_digest" to it.datasetDigest, "since_ms" to it.sinceMs, "on_build" to it.onBuild) },
                "stub_loads" to s.stubLoads,
                "loading" to s.loading.sorted(),
                "pull_again" to s.pullAgain,
                "apn" to linkedMapOf(
                    "last_version" to s.apn.lastVersion, "sha512" to s.apn.sha512, "dataset_digest" to s.apn.datasetDigest,
                    "confirmed_at_ms" to s.apn.confirmedAtMs, "rows" to s.apn.rows, "raw_rows" to s.apn.rawRows, "last_error" to s.apn.lastError,
                ),
                "pending" to s.pending.sorted(),
                "pending_why" to s.pendingWhy,
                "pending_since_ms" to s.pendingSinceMs,
                "emergency_seen_at_ms" to s.emergencySeenAtMs,
                "emergency_up" to s.emergencyUp,
                "last_call_ended_at_ms" to s.lastCallEndedAtMs,
                "last_notify_ms" to s.lastNotifyMs.entries.associate { (k, v) -> k.toString() to v },
                "served" to s.served.entries.associate { (k, v) -> k.toString() to linkedMapOf("version" to v.version, "dataset_digest" to v.datasetDigest, "at_ms" to v.atMs, "sm7" to v.sm7) },
                "x5_served" to s.x5Served.entries.associate { (k, v) -> k.toString() to v },
                "x5_tested_here" to s.x5TestedHere,
                "mismatches" to s.mismatches,
                "mismatch_since_ms" to s.mismatchSinceMs,
                "proving" to s.proving.entries.associate { (k, v) -> k.toString() to v },
                "last_check_ms" to s.lastCheckMs,
                "last_check" to s.lastCheck,
                "last_job" to s.lastJob,
                "own_install" to s.ownInstall,
                "candidate_trials" to s.candidateTrials,
            ),
        )

        private fun holdJson(h: VoiceHold) = linkedMapOf("since_ms" to h.sinceMs, "reason" to h.reason, "dataset_digest" to h.datasetDigest, "values_digest" to h.valuesDigest)

        /**
         * Null when the text is not a state this code wrote; the store then fails safe. A state a
         * round 30 build wrote reads too: its safe mode, gate and cleared flag are gone, and a safe
         * mode that was on keeps the build serving (the installed dataset is dropped).
         */
        fun decode(text: String): State? = runCatching {
            val o = Obj.of(Json.parse(text), "state.json")
            if (o.str("schema") != "ocd-state/1") return null
            fun long(name: String, from: Obj = o) = from.longOrNull(name) ?: 0L
            fun hold(h: Obj) = VoiceHold(long("since_ms", h), h.str("reason"), h.str("dataset_digest"), h.str("values_digest"))
            val safeModeWasOn = o.objOrNull("safe_mode") != null
            fun ints(name: String) = o.listOrNull(name).orEmpty().map { (it as Json.Num).longOrNull()!!.toInt() }.toSet()
            fun intMap(name: String): Map<Int, Obj> = o.objOrNull(name)?.let { m -> m.keys.associate { it.toInt() to m.obj(it) } }.orEmpty()
            val apn = o.objOrNull("apn")
            State(
                installed = if (safeModeWasOn) null else o.objOrNull("installed")?.let { InstalledRef(it.str("dataset_digest"), it.str("commit"), it.str("data_digest"), it.str("generated_at"), long("installed_at_ms", it), it.str("google_lane_source")) },
                active = if (safeModeWasOn) "build" else o.strOrNull("active") ?: "build",
                servingReason = (o.strOrNull("serving_reason") ?: "no_dataset").let { if (safeModeWasOn || it == "safe_mode" || it == "cleared") "no_dataset" else it },
                halted = o.objOrNull("halted")?.let { Halted(long("since_ms", it), it.str("reason")) },
                writerState = o.strOrNull("writer_state") ?: "unknown",
                tester = o.objOrNull("tester")?.let { TesterDecl(it.bool("active"), long("since_ms", it), it.str("reason")) },
                hold = o.objOrNull("hold")?.let(::hold),
                lastHold = o.objOrNull("last_hold")?.let(::hold),
                voiceStop = o.objOrNull("voice_stop")?.let { VoiceStop(long("since_ms", it), it.str("reason"), it.str("dataset_digest"), it.str("incremental")) },
                blocked = o.listOrNull("blocked").orEmpty().map { it as String }.toSet(),
                apnTest = o.objOrNull("apn_test")?.let { t ->
                    ApnTestSet(
                        t.int("sub_id"), long("since_ms", t), t.str("state"), t.strings("removed_rows"), t.str("sha512"), t.str("full_sha512"),
                        t.str("dataset_digest"), t.int("rows"),
                        t.list("preferred").map { pa ->
                            val po = Obj.of(pa, "preferred")
                            val fo = po.obj("fields")
                            PreferredApn(po.int("sub_id"), fo.keys.associateWith { fo.str(it) }, po.longOrNull("row_id"))
                        },
                        t.strOrNull("ims"),
                        t.strOrNull("kind") ?: ApnTestSet.EMERGENCY_DEFAULT,
                        t.objOrNull("candidate")?.let { c ->
                            CandidateTrial(
                                c.str("id"), c.str("rule"), c.str("kind"), c.strOrNull("role"), c.strings("changed_rows"), c.strings("added_rows"),
                                c.int("slot"), c.str("network"), c.list("subs").map { (it as Json.Num).longOrNull()!!.toInt() },
                                c.raw["data_before"] as Boolean?, long("until_ms", c),
                            )
                        },
                    )
                },
                notice = o.strOrNull("notice"),
                crashGuard = o.objOrNull("crash_guard")?.let { CrashGuard(it.str("dataset_digest"), long("since_ms", it), it.bool("on_build")) },
                stubLoads = long("stub_loads").toInt(),
                loading = ints("loading"),
                pullAgain = o.raw["pull_again"] == true,
                apn = apn?.let { ApnState(long("last_version", it), it.strOrNull("sha512"), it.strOrNull("dataset_digest"), long("confirmed_at_ms", it), long("rows", it).toInt(), long("raw_rows", it).toInt(), it.strOrNull("last_error")) } ?: ApnState(),
                pending = ints("pending"),
                pendingWhy = o.strOrNull("pending_why").orEmpty(),
                pendingSinceMs = long("pending_since_ms"),
                emergencySeenAtMs = long("emergency_seen_at_ms"),
                emergencyUp = o.raw["emergency_up"] == true,
                lastCallEndedAtMs = long("last_call_ended_at_ms"),
                lastNotifyMs = o.objOrNull("last_notify_ms")?.let { m -> m.keys.associate { it.toInt() to m.long(it) } }.orEmpty(),
                served = intMap("served").mapValues { (_, v) -> ServedRef(v.str("version"), v.str("dataset_digest"), long("at_ms", v), v.raw["sm7"] == true) },
                x5Served = intMap("x5_served").mapValues { (_, v) -> v.keys.associateWith { k -> v.raw[k] as Boolean? } },
                x5TestedHere = o.objOrNull("x5_tested_here")?.let { m -> m.keys.associateWith { m.str(it) } }.orEmpty(),
                mismatches = long("mismatches").toInt(),
                mismatchSinceMs = long("mismatch_since_ms"),
                proving = o.objOrNull("proving")?.let { m -> m.keys.associate { it.toInt() to m.str(it) } }.orEmpty(),
                lastCheckMs = long("last_check_ms"),
                lastCheck = o.strOrNull("last_check").orEmpty(),
                lastJob = (o.raw["last_job"] as? Map<*, *>)?.entries?.associate { (k, v) -> k as String to v },
                ownInstall = o.strOrNull("own_install"),
                candidateTrials = o.listOrNull("candidate_trials").orEmpty().map { (it as Json.Num).longOrNull()!! },
            )
        }.getOrNull()
    }
}

/**
 * CarrierSettings's device-protected storage for OCD (spec 9.2), rooted at
 * `/data/user_de/0/com.android.carriersettings/files/ocd/`:
 * `datasets/<digest12>/`, `staging/<install_id>/`, `index/<digest12>-<cell8>/`, `state.json`,
 * `records-local.json`, `served/<sub>.json` and `apn-out/`. Every write is a temp file, an
 * fsync, a rename and a folder fsync.
 */
class Store(val root: File) {
    val stateFile = File(root, "state.json")
    val localRecordsFile = File(root, "records-local.json")
    val datasets = File(root, "datasets")
    val staging = File(root, "staging")
    val indexes = File(root, "index")
    val served = File(root, "served")
    val apnOut = File(root, "apn-out")

    fun datasetDir(digest: String) = File(datasets, digest.removePrefix("sha256:").take(12))

    fun indexDir(digest: String, cellKey: String) = File(indexes, digest.removePrefix("sha256:").take(12) + "-" + cellKey.take(8))

    /**
     * The state. A missing file is a fresh state. A file this code cannot read fails safe:
     * the build serves (`corrupt`) and an emergency call is assumed to have just happened, so
     * nothing reloads for 10 minutes.
     */
    fun readState(nowMs: Long): State {
        if (!stateFile.isFile) return State()
        val text = runCatching { String(Files.readBounded(stateFile, 2L * 1024 * 1024), Charsets.UTF_8) }.getOrNull()
        return text?.let(State::decode) ?: State(active = "build", servingReason = "corrupt", emergencySeenAtMs = nowMs)
    }

    fun writeState(state: State) = Files.atomicWrite(stateFile, State.encode(state).toByteArray(Charsets.UTF_8))

    fun readLocalRecords(types: Map<String, KeyType>): List<Record> {
        if (!localRecordsFile.isFile) return emptyList()
        return runCatching {
            val o = Obj.of(Json.parseBytes(Files.readBounded(localRecordsFile, 4L * 1024 * 1024)), "records-local.json")
            o.list("records").mapNotNull { r ->
                runCatching { Record.parse(r, "records-local.json", localOk = true) { k, v -> Types.fromStored(k, types[k] ?: refuse("unknown_key", k), v) } }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun writeLocalRecords(records: List<Record>) =
        Files.atomicWrite(localRecordsFile, Json.write(linkedMapOf("schema" to "ocd-local-records/1", "records" to records.map { it.json })).toByteArray(Charsets.UTF_8))

    /** What a subscription was last served, whole, so a load during a refusal can serve the same (S6). */
    fun writeServed(sub: Int, built: Built) {
        val values = built.values.entries.associateTo(LinkedHashMap()) { (k, v) -> k to linkedMapOf("type" to built.types.getValue(k).wire, "value" to Types.toJson(built.types.getValue(k), v)) }
        Files.atomicWrite(File(served, "$sub.json"), Json.write(linkedMapOf("version" to built.version, "values" to values)).toByteArray(Charsets.UTF_8))
    }

    fun readServed(sub: Int): Pair<LinkedHashMap<String, Any>, Map<String, KeyType>>? = runCatching {
        val o = Obj.of(Json.parseBytes(Files.readBounded(File(served, "$sub.json"), 4L * 1024 * 1024)), "served")
        val v = o.obj("values")
        val values = LinkedHashMap<String, Any>()
        val types = HashMap<String, KeyType>()
        for (k in v.keys) {
            val e = v.obj(k)
            val t = KeyType.of(e.str("type"))!!
            values[k] = Types.fromStored(k, t, e.any("value"))
            types[k] = t
        }
        values to types
    }.getOrNull()

    /** 9.2: at first start the round 29 files go (the layer, its state, baselines and the ICCID salt). */
    fun deleteRound29(filesDir: File) {
        filesDir.listFiles().orEmpty().forEach { f ->
            if (f.name == "ocd-layer" || f.name == "ocd-layer.tmp" || f.name == "ocd-layer-state.json" || f.name == "ocd-layer-salt" ||
                (f.name.startsWith("ocd-baseline-") && f.name.endsWith(".json"))
            ) {
                f.delete()
            }
        }
    }

    /** Removes datasets, staging folders and indexes not named in [keep] (digest prefixes and folder names). */
    fun prune(keepDatasets: Set<String>, keepIndexes: Set<String>, keepStaging: Set<String>) {
        datasets.listFiles().orEmpty().filter { it.name !in keepDatasets }.forEach(Files::deleteTree)
        indexes.listFiles().orEmpty().filter { it.name !in keepIndexes }.forEach(Files::deleteTree)
        staging.listFiles().orEmpty().filter { it.name !in keepStaging }.forEach(Files::deleteTree)
    }
}
