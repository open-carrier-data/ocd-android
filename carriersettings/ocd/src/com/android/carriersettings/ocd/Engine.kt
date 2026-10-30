/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** A SIM in the phone, identifiers in memory only. */
class ActiveSim(val subId: Int, val slot: Int, val sim: SimId)

/**
 * The provider's one writer (round 31 SM5, a build parameter) and its state (R31.4): `enabled`,
 * `disabled` (the person disabled it, or removed it for the user), `absent` (not in the image)
 * or `unknown` (PackageManager could not say; nothing changes then).
 */
data class Writer(val pkg: String, val state: String) {
    /** Only an explicit disable or the app's absence counts; a crash, an update or hibernation change nothing. */
    val off: Boolean get() = state == "disabled" || state == "absent"
    val ownerFlavor: Boolean get() = pkg == Protocol.OWNER_PACKAGE
}

/** One key the device override sets: its type, value, and `lineageos_fixed` or `device_override`. */
data class OverrideValue(val type: KeyType, val value: Any, val source: String)

/** A SIM's preferred APN as TelephonyProvider answers it. */
sealed class PreferredRead {
    object None : PreferredRead()
    object Unreadable : PreferredRead()
    data class Row(val id: Long, val fields: Map<String, String>) : PreferredRead()
}

/**
 * Everything the engine needs from Android, so the engine runs off-device in tests. The
 * Android side is [OcdAndroid]; nothing here does network I/O.
 */
interface Platform {
    fun nowMs(): Long
    fun sleep(ms: Long)
    val store: Store
    /** `/system_ext/etc/OcdCarrierSettings/dataset`. */
    val bakedDir: File
    /** ocd-build.json checked against Build.DEVICE, Build.MODEL and ro.lineage.build.version; null when it does not fit this phone. */
    fun cell(): BuildCell?
    fun incremental(): String
    /** `build` of `status` (6.3). */
    fun buildInfo(): Map<String, Any?>
    /** The `version` attribute of android:xml/apns; null when unreadable. */
    fun platformApnVersion(): Int?
    fun androidDefault(key: String): DefaultType
    /** Null when the SIM list cannot be read. */
    fun activeSims(): List<ActiveSim>?
    fun phoneState(): PhoneState
    /** In-memory call sightings on the elapsed clock (a lost write or a clock jump loses none); the `waiting_for` or null. */
    fun recentCall(): String?
    /** An emergency call seen within the last 60 minutes, on the elapsed clock (in memory). */
    fun recentEmergency(): Boolean
    fun roaming(subId: Int, simMcc: String): Boolean?
    /** Any APN row named `… (OCD Check test)`; null when the query failed. */
    fun apnTrialInPlace(): Boolean?
    fun queryCarriers(): List<ApnLogic.DbRow>?
    /** Sends UPDATE_APN_DB for apn-out/[name]; false when it could not be sent. */
    fun sendApnUpdate(name: String, version: Long, requiredHash: String): Boolean
    fun notifyConfigChanged(subId: Int): Boolean
    /** carrier_config_version_string in effect; null when unreadable. */
    fun effectiveVersion(subId: Int): String?
    fun armRetry(delayMs: Long)
    fun armDaily()
    fun runOnWorker(task: () -> Unit)
    fun log(message: String)
    /** The writer app and its state (R31.4, R31.11). */
    fun writer(): Writer
    /**
     * What this phone applies last (R31.3): LineageOS's fixed key, the device override XML and an
     * AOSP CarrierConfig vendor.xml, for [sim] (null: the parts without carrier filters). Null
     * when they cannot be read.
     */
    fun deviceOverride(sim: SimId?): Map<String, OverrideValue>?
    fun preferredApn(subId: Int): PreferredRead
    fun setPreferredApn(subId: Int, rowId: Long): Boolean
    /** Every row of content://telephony/carriers: its `_id` and fields (user and password left out); null when unreadable. */
    fun carrierRows(): List<Pair<Long, Map<String, String>>>?
    /** IMS registration and its services for [subId] as one comparable string; null when unreadable. */
    fun imsSnapshot(subId: Int): String?
    /** [subId]'s mobile data validated; null when no cellular network of it is up to judge (on Wi-Fi). */
    fun mobileDataValidated(subId: Int): Boolean?
    /** A notification for the owner; best effort (status carries the same notice). */
    fun notifyOwner(title: String, text: String)
}

/** One install job (6.6). */
class Job(val id: String, val kind: String, val dir: File?, val f5Read: String?, val firstUse: String? = null) {
    @Volatile var state = "verifying"
    @Volatile var error: String? = null
    @Volatile var reason: String? = null
    @Volatile var waitingFor: String? = null
    val checks = CopyOnWriteArrayList<Verifier.Check>()
    @Volatile var commit: String? = null
    @Volatile var dataDigest: String? = null
    @Volatile var datasetDigest: String? = null
    @Volatile var generatedAt: String? = null
    @Volatile var changes: String? = null
    @Volatile var apn: String? = null
    @Volatile var laneSource: String? = null
    /** The default emergency APN test (R31.9): rows taken out, the set's row count, the preferred APN recorded. */
    @Volatile var removedRows: List<String>? = null
    @Volatile var rows: Int? = null
    @Volatile var preferredRecorded: Boolean? = null
    @Volatile var checksAfter: String? = null
    /** Round 32's APN candidate set: the candidate's ID, and the rows it writes anew and adds. */
    @Volatile var candidate: String? = null
    @Volatile var changedRows: List<String>? = null
    @Volatile var addedRows: List<String>? = null

    fun refuse(code: String, detail: String) {
        state = "refused"
        error = code
        reason = detail.take(240)
    }

    /** R31.9: a restore that did not come back as before; a stop event (state and error `restore_failed`). */
    fun failRestore(detail: String) {
        state = "restore_failed"
        error = "restore_failed"
        reason = detail.take(240)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "install_id" to id, "kind" to kind, "state" to state, "error" to error, "reason" to reason, "waiting_for" to waitingFor,
        "commit" to commit, "data_digest" to dataDigest, "dataset_digest" to datasetDigest, "generated_at" to generatedAt,
        "removed_rows" to removedRows, "rows" to rows, "preferred_apn_recorded" to preferredRecorded, "checks_after" to checksAfter,
        "candidate" to candidate, "changed_rows" to changedRows, "added_rows" to addedRows,
    )
}

/**
 * OcdCarrierSettings's dataset side (spec sections 5 to 9 with round 31's changes): the load,
 * the install jobs, the APN path, the boot and daily check, the app's on/off as the way back, the
 * voice-loss hold, the default emergency APN test, round 32's APN candidate trials, records, the
 * crash guard and the timing refusals. Decisions come from the pure [Verifier], [BundleBuilder],
 * [ApnLogic], [ApnCandidateSet] and [Guard].
 */
class Engine(private val p: Platform) {
    companion object {
        const val LOAD_LOCK_MS = 300L
        const val LOCK_MS = 15_000L
        val RETRY_MS: Long = TimeUnit.MINUTES.toMillis(2)
        const val CONFIRM_POLL_MS = 2_000L
        const val CONFIRM_MS = 120_000L
        const val PROOF_MS = 60_000L
        /** A temporary emergency APN set left in place longer than this is restored by the check as a stop event. */
        val APN_TEST_MAX_MS: Long = TimeUnit.MINUTES.toMillis(30)
        /** How long a restore waits for mobile data and IMS to come back as before. */
        const val AFTER_MS = 120_000L
        /**
         * Round 32: an APN candidate set is restored without the app this long after it went in
         * place (the app's trial takes about 5 minutes: the failed check again for up to 2, then
         * its restore), or this long plus [CANDIDATE_INSTALL_MS] after it was stored.
         */
        val CANDIDATE_MAX_MS: Long = TimeUnit.MINUTES.toMillis(10)
        /** The longest a candidate set's install takes: two sends with their confirmations and the rollback's. */
        const val CANDIDATE_INSTALL_MS = 3 * CONFIRM_MS
        /** The caps' backstop (D8 rule 4): a candidate costs two IMS re-registrations, its set and its restore, of 6 an hour and 30 a day. */
        const val CANDIDATES_PER_HOUR = 3
        const val CANDIDATES_PER_DAY = 15
        private const val APP_OFF = "the OCD app is disabled"
        /** The preferred row's fields a candidate may change: its type (set_types) and a fill-in's auth type and MTU (unset). */
        private val PREFERRED_LOOSE = setOf("type", "authtype", "mtu", "mtu_v4", "mtu_v6")
        private val X5 = Rules.X5_OFF.keys
    }

    private val lock = ReentrantLock()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val busy = AtomicReference<String?>(null)
    private val checkQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastJobId: String? = null
    private val cache = ConcurrentHashMap<String, Pair<String, ServingData>>()
    private val random = SecureRandom()

    private val store get() = p.store

    fun <T> locked(waitMs: Long = LOCK_MS, block: () -> T): T? {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun state(): State = store.readState(p.nowMs())

    /**
     * Not `internal`: Kotlin gives an internal member a JVM name with Soong's module name in it,
     * which Soong takes from the module's directory, so the dex would differ with where a ROM puts
     * this module (OCD rule round 37, step 3). The tests call it.
     */
    fun update(block: (State) -> State): State = locked {
        val next = block(store.readState(p.nowMs()))
        store.writeState(next)
        next
    } ?: throw Refusal("busy", "CarrierSettings's state is busy; try again")

    fun today(): String = Instant.ofEpochMilli(p.nowMs()).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun cellKey(): String = Files.sha256(p.incremental() + "|" + p.cell().toString()).take(8)

    // --- the build's own dataset ----------------------------------------------------------

    private fun bakedManifest(): Manifest? = runCatching {
        Manifest.parse(Files.readBounded(File(p.bakedDir, DatasetFile.MANIFEST.local), DatasetFile.MANIFEST.cap))
    }.getOrNull()

    private fun bakedPin(): Pin? = runCatching {
        Pin.parse(Json.decodeUtf8(Files.readBounded(File(p.bakedDir, DatasetFile.PIN.local), DatasetFile.PIN.cap)))
    }.getOrNull()

    private fun buildIndexDir(): File? = bakedManifest()?.let { store.indexDir(it.datasetDigest, cellKey()) }

    private fun cached(dir: File): ServingData? {
        val marker = File(dir, "complete")
        if (!marker.isFile) return null
        val stamp = (runCatching { marker.readText() }.getOrNull() ?: return null) + ":" + marker.lastModified() + ":" + dir.path
        cache[dir.path]?.let { if (it.first == stamp) return it.second }
        return ServingIndex.read(dir).also { cache[dir.path] = stamp to it }
    }

    /** The build's serving index, when it is built (the load path never builds it). */
    fun buildServing(): ServingData? = runCatching { buildIndexDir()?.let(::cached) }.getOrNull()

    /** 8.1 step 1: the build's serving index for this build and the baked dataset; built when missing (worker only). */
    fun ensureBuildIndex(): ServingData? {
        val manifest = bakedManifest() ?: run { p.log("the build's dataset has no readable manifest"); return null }
        val dir = store.indexDir(manifest.datasetDigest, cellKey())
        if (ServingIndex.complete(dir, manifest.datasetDigest, cellKey(), bakedPin()?.commit)) return cached(dir)
        return try {
            val verifier = Verifier(null, p::androidDefault, p.platformApnVersion(), null, emptyList())
            val dataset = verifier.verify(p.bakedDir, role = "build")
            ServingIndex.write(dataset, dir, "build", p.cell(), cellKey())
            p.log("built the serving index of the build's dataset ${dataset.commit.take(12)}")
            cached(dir)
        } catch (e: Refused) {
            p.log("the build's dataset fails its checks: ${e.code}: ${e.detail}")
            null
        }
    }

    private fun bakedRef(): Baked? {
        val manifest = bakedManifest() ?: return null
        val build = ensureBuildIndex() ?: return Baked(manifest, emptyMap(), null)
        val lane = runCatching {
            GoogleLane.parse(Files.readBounded(File(p.bakedDir, DatasetFile.GOOGLE_LANE.local), DatasetFile.GOOGLE_LANE.cap)) { k, v ->
                Types.typed(k, build.types[k] ?: refuse("unknown_key", k), v)
            }
        }.getOrNull()
        return Baked(manifest, build.types, lane)
    }

    private fun installedServing(state: State): ServingData? {
        val installed = state.installed ?: return null
        val dir = store.indexDir(installed.datasetDigest, cellKey())
        return if (ServingIndex.complete(dir, installed.datasetDigest, null, installed.commit)) cached(dir) else null
    }

    // --- the load (9.4) -----------------------------------------------------------------

    /** What the CarrierConfig part serves now, and whether local records count. */
    private class Choice(val data: ServingData?, val local: Boolean, val crashGuardBuild: Boolean, val label: String)

    private fun writerNow(): Writer = runCatching { p.writer() }.getOrDefault(Writer(Protocol.OWNER_PACKAGE, "unknown"))

    /** The installed dataset serves only while the person has not disabled the OCD app (R31.4). */
    private fun choose(state: State, writer: Writer = writerNow()): Choice {
        val cg = state.crashGuard
        if (state.servingInstalled && !writer.off) {
            val installed = runCatching { installedServing(state) }.getOrNull()
            if (installed != null) return Choice(installed, true, false, "installed")
        }
        val build = buildServing()
        return Choice(build, false, cg?.onBuild == true, "build")
    }

    /**
     * Round 31 SM7 and SM8: dialled tests run here only with the writer app enabled, on a
     * calibrated IMS stack, and with the app's declaration (`set_tester`); without one the owner
     * flavor counts as a tester and any other writer does not.
     */
    fun testerActive(state: State, writer: Writer = writerNow()): Boolean {
        if (writer.state != "enabled") return false
        val cell = p.cell() ?: return false
        if (cell.stackId !in Rules.CALIBRATED_STACKS) return false
        return state.tester?.active ?: writer.ownerFlavor
    }

    fun sm7(state: State, writer: Writer = writerNow()): Boolean = !testerActive(state, writer)

    /** R31.6: narrow only with the build's declaration; an app record of a first use that did not pass on this phone keeps it full. */
    fun gate(firstUse: String? = null): String {
        val cell = p.cell() ?: return "full"
        if (cell.firstUsePassedOn == null) return "full"
        if (firstUse != null && ApnLogic.firstUseProblem(firstUse, cell) != null) return "full"
        return "narrow"
    }

    private fun localRecords(types: Map<String, KeyType>): List<Record> = runCatching { store.readLocalRecords(types) }.getOrDefault(emptyList())

    private fun x5Reference(state: State, sub: Int, sim: SimId, build: ServingData?): (String) -> Any? {
        val served = state.x5Served[sub]
        var fromBuild: Map<String, Any?>? = null
        return { key ->
            if (served != null) {
                served[key]
            } else {
                if (fromBuild == null) {
                    fromBuild = build?.let {
                        runCatching { BundleBuilder.build(BuildInput(sim, it, emptyList(), emptySet(), { null }, today(), p.cell(), rawOnly = true)).x5Data }.getOrNull()
                    } ?: emptyMap()
                }
                fromBuild!![key]
            }
        }
    }

    private fun buildFor(sim: SimId, sub: Int, state: State, choice: Choice, sm7: Boolean = sm7(state)): Built {
        val data = choice.data ?: refuse("storage", "no serving index")
        val local = if (choice.local) localRecords(data.types) else emptyList()
        return BundleBuilder.build(
            BuildInput(
                sim, data, local, state.x5TestedHere.keys, x5Reference(state, sub, sim, buildServing()), today(), p.cell(),
                crashGuardBuild = choice.crashGuardBuild, sm7 = sm7,
            ),
        )
    }

    /** Why an install or reload may not run now (8.4), from the services and the stored and in-memory sightings. */
    fun callRefusal(state: State): String? =
        (if (p.recentEmergency()) "emergency_call_60min" else null) ?: p.recentCall() ?: Guard.callRefusal(p.phoneState(), state.emergencySeenAtMs, state.lastCallEndedAtMs, p.nowMs())

    /** The restore of the temporary emergency set waits for less (R31.9): no 10 minutes after an ordinary call. */
    fun restoreRefusal(state: State): String? =
        (if (p.recentEmergency()) "emergency_call_60min" else null) ?: Guard.restoreRefusal(p.phoneState(), state.emergencySeenAtMs, p.nowMs())

    /**
     * onLoadConfig's work (9.4). Never throws. During a refusal a load serves what this
     * subscription was served before (S6). With the installed index failing it serves the
     * build's; without the build's index it streams the baked files; failing that, steps 5
     * and A only.
     */
    fun load(sub: Int, sim: SimId): Built {
        val now = p.nowMs()
        val state = store.readState(now)
        // Round 32: a load while an APN candidate set is in place may be a new SIM; the check restores the set then.
        if (state.apnTest?.isCandidate == true) queueCheck("a load while the APN candidate test set is in place")
        try {
            val last = state.served[sub]
            if (last != null && !sim.noSim) {
                val refusal = runCatching { callRefusal(state) }.getOrDefault("state_unreadable")
                if (refusal != null) {
                    store.readServed(sub)?.let { (values, types) ->
                        p.log("sub $sub: unchanged while $refusal")
                        return Built(values, types, emptyMap(), last.version, "unchanged", emptyList(), null, emptyList(), emptyMap(), emptyList(), emptySet(), emptyMap())
                    }
                }
            }
            locked(LOAD_LOCK_MS) { store.writeState(store.readState(now).let { it.copy(loading = it.loading + sub) }) }
            val writer = writerNow()
            val sm7 = sm7(state, writer)
            if (writer.off && (state.installed != null || state.writerState != writer.state)) queueCheck("the OCD app is ${writer.state}")
            val choice = choose(state, writer)
            var built: Built? = null
            var digest = choice.data?.datasetDigest
            if (choice.data != null) {
                built = runCatching { buildFor(sim, sub, state, choice, sm7) }.onFailure { p.log("sub $sub: ${choice.label} dataset failed: ${it.message}") }.getOrNull()
            }
            if (built == null && choice.label == "installed") {
                val build = buildServing()
                digest = build?.datasetDigest
                built = build?.let { b -> runCatching { buildFor(sim, sub, state, Choice(b, false, false, "build"), sm7) }.getOrNull() }
                queueCheck("a load failed on the installed dataset")
            }
            if (built == null) {
                built = runCatching {
                    val streamed = ServingIndex.streamBaked(p.bakedDir, sim.network, p.cell())
                    digest = streamed.datasetDigest
                    buildFor(sim, sub, state, Choice(streamed, false, state.crashGuard?.onBuild == true, "build"), sm7)
                }.onFailure { p.log("sub $sub: the baked dataset could not be streamed: ${it.message}") }.getOrNull()
                queueCheck("the build's index was missing at a load")
            }
            if (built == null) built = minimal()
            val result = built
            val x5 = X5.associateWith { result.values[it] as? Boolean }
            locked(LOAD_LOCK_MS) {
                val s = store.readState(now)
                store.writeState(
                    s.copy(
                        loading = s.loading - sub,
                        served = if (sim.noSim) s.served else s.served + (sub to ServedRef(result.version, digest ?: "", now, result.sm7)),
                        x5Served = if (sim.noSim || result.serving == "fallback") s.x5Served else s.x5Served + (sub to x5),
                    ),
                )
                if (!sim.noSim) store.writeServed(sub, result)
            }
            p.log("sub $sub: ${result.version} (${choice.label}${if (result.x5Held.isNotEmpty()) ", X5 held ${result.x5Held.keys.sorted()}" else ""}${if (result.sm7Held.isNotEmpty()) ", SM7 held ${result.sm7Held.keys.sorted()}" else ""})")
            return result
        } catch (t: Throwable) {
            p.log("sub $sub: load failed: $t")
            return runCatching { minimal() }.getOrElse { BundleBuilder.minimal("0000000") }
        }
    }

    /** One boot check queued at a time from the load path, however many loads fail. */
    private fun queueCheck(why: String) {
        if (!checkQueued.compareAndSet(false, true)) return
        p.runOnWorker {
            checkQueued.set(false)
            runCatching { bootCheck(why) }
        }
    }

    /** 9.4 point 3: no dataset readable; the device override still applies after it in the service. */
    private fun minimal(): Built = BundleBuilder.minimal(bakedPin()?.sha7 ?: "0000000")

    // --- status (6.3) ---------------------------------------------------------------------

    fun status(): Map<String, Any?> {
        val s = state()
        val bm = bakedManifest()
        val bp = bakedPin()
        val writer = writerNow()
        val staged = lastJobId?.let { jobs[it] }?.takeIf { it.kind == "install" && it.state !in setOf("installed", "superseded", "cancelled") }
        val override = runCatching { p.deviceOverride(null) }.getOrNull()
        val sm7 = sm7(s, writer)
        fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
        return linkedMapOf(
            Protocol.PROTOCOL to Protocol.VERSION,
            "build" to p.buildInfo(),
            "build_pin" to linkedMapOf("commit" to bp?.commit, "data_digest" to bm?.dataDigest, "dataset_digest" to bm?.datasetDigest, "generated_at" to bm?.generatedAt),
            "installed" to s.installed?.let {
                linkedMapOf("commit" to it.commit, "data_digest" to it.dataDigest, "dataset_digest" to it.datasetDigest, "generated_at" to it.generatedAt, "installed_at" to iso(it.installedAtMs), "google_lane_source" to it.laneSource)
            },
            "serving" to (if (s.servingInstalled && !writer.off) "installed" else "build"),
            "serving_reason" to (if (writer.off) "app_disabled" else s.servingReason),
            "app" to linkedMapOf("package" to writer.pkg, "state" to writer.state),
            "tester" to linkedMapOf("active" to testerActive(s, writer), "declared" to s.tester?.active, "since" to s.tester?.let { iso(it.sinceMs) }, "reason" to s.tester?.reason),
            "sm7" to sm7,
            "voice_loss" to linkedMapOf(
                "hold" to s.hold?.let { linkedMapOf("since" to iso(it.sinceMs), "reason" to it.reason, "dataset_digest" to it.datasetDigest, "values_digest" to it.valuesDigest) },
                "stop" to s.voiceStop?.let { linkedMapOf("since" to iso(it.sinceMs), "reason" to it.reason, "dataset_digest" to it.datasetDigest) },
                "blocked" to s.blocked.sorted(),
            ),
            "changes_halted" to linkedMapOf("on" to (s.halted != null), "since" to s.halted?.let { iso(it.sinceMs) }, "reason" to s.halted?.reason),
            "crash_guard" to s.crashGuard?.let { linkedMapOf("dataset_digest" to it.datasetDigest, "since" to iso(it.sinceMs), "crash_guard_build" to it.onBuild) },
            "pull_again" to s.pullAgain,
            "staged" to staged?.let { linkedMapOf("install_id" to it.id, "state" to it.state, "error" to it.error) },
            "pending_subs" to s.pending.sorted(),
            "waiting_for" to (s.ownInstall ?: s.pendingWhy.ifEmpty { null }),
            "emergency_gate" to gate(),
            "emergency_gate_source" to (if (p.cell()?.firstUsePassedOn != null) "build" else "none"),
            "device_override" to linkedMapOf(
                "readable" to (override != null),
                "keys" to override?.keys?.sorted(),
                "values" to override?.entries?.associate { (k, v) -> k to linkedMapOf("value" to Types.toJson(v.type, v.value), "source" to v.source) },
            ),
            "apn_test_set" to s.apnTest?.let { t ->
                linkedMapOf<String, Any?>(
                    "status" to t.label, "kind" to t.kind, "state" to t.state, "sub_id" to t.subId, "since" to iso(t.sinceMs), "removed_rows" to t.removedRows,
                    "rows" to t.rows, "preferred_apn_recorded" to t.preferred.isNotEmpty(),
                ).apply {
                    t.candidate?.let { c ->
                        put("candidate", c.id)
                        put("rule", c.rule)
                        put("role", c.role)
                        put("changed_rows", c.changedRows)
                        put("added_rows", c.addedRows)
                        put("until", iso(c.untilMs))
                    }
                }
            },
            "notice" to s.notice,
            "apn" to linkedMapOf("version" to s.apn.lastVersion, "sha512_prefix" to s.apn.sha512?.take(12), "confirmed_at" to s.apn.confirmedAtMs.takeIf { it > 0 }?.let(::iso), "rows" to s.apn.rows, "raw_rows" to s.apn.rawRows, "error" to s.apn.lastError),
            "last_check" to linkedMapOf("at" to s.lastCheckMs.takeIf { it > 0 }?.let(::iso), "result" to s.lastCheck),
        )
    }

    // --- staging (6.3) ---------------------------------------------------------------------

    private fun newId(): String = Files.hex(ByteArray(8).also { random.nextBytes(it) })

    /** Refusals of the app's changes: a stop event (changes halted), a voice-loss stop, and for installs a voice-loss hold. */
    private fun refusals(state: State, forApp: Boolean, install: Boolean = false) {
        if (forApp && state.halted != null) throw Refusal("changes_halted", "changes are halted: ${state.halted.reason}")
        if (forApp && state.voiceStop != null) throw Refusal("voice_loss_stop", "voice was lost twice under the same values; nothing is installed or tested until resume or a newer build")
        if (forApp && install && state.hold != null) throw Refusal("voice_loss_hold", "the build's dataset holds after a voice loss until the split releases it")
    }

    /**
     * `stage_dataset`: copies each file into a fresh staging folder within the caps, runs checks
     * 1 to 3 and returns at once; checks 4 to 17 and the serving index run on the worker.
     * [firstUse] is the app's first-use record of the emergency data test, re-sent with every
     * hand-off (R31.6).
     */
    fun stage(pinText: String?, names: List<String>, files: Map<String, InputStream>, f5Read: String?, firstUse: String? = null): Map<String, Any?> {
        try {
            val state = state()
            refusals(state, forApp = true)
            busy.get()?.let { throw Refusal("busy", "install job $it runs") }
            if (pinText == null) throw Refusal("bad_request", "no pin")
            if (pinText.length > DatasetFile.PIN.cap) throw Refusal("too_large", "pin.json over its cap")
            if (names.isEmpty() || names.toSet().size != names.size) throw Refusal("bad_request", "files must name each file once")
            for (n in names) {
                val f = DatasetFile.byLocal(n)
                if (f == null || f == DatasetFile.PIN) throw Refusal("bad_request", "unknown file name $n")
                if (n !in files) throw Refusal("bad_request", "no fd.$n")
            }
            if (files.keys.any { it !in names }) throw Refusal("bad_request", "an fd for a file not named in files")
            val id = newId()
            val dir = File(store.staging, id)
            Files.deleteTree(dir)
            dir.mkdirs()
            try {
                var total = pinText.toByteArray(Charsets.UTF_8).size.toLong()
                Files.atomicWrite(File(dir, DatasetFile.PIN.local), pinText.toByteArray(Charsets.UTF_8))
                for (n in names) {
                    val f = DatasetFile.byLocal(n)!!
                    val copied = files.getValue(n).use { Files.copyBounded(it, File(dir, n), minOf(f.cap, DatasetFile.TOTAL_CAP - total)) }
                    if (copied < 0) throw Refusal("too_large", "$n is over its cap or the set over 64 MiB")
                    total += copied
                }
            } finally {
                files.values.forEach { runCatching { it.close() } }
            }
            val job = Job(id, "install", dir, f5Read, firstUse)
            val verifier = Verifier(null, p::androidDefault, null, null, emptyList())
            try {
                verifier.early(dir)
            } catch (e: Refused) {
                Files.deleteTree(dir)
                throw Refusal(e.code, e.detail)
            } finally {
                job.checks.addAll(verifier.checks)
            }
            val manifest = runCatching { Manifest.parse(Files.readBounded(File(dir, DatasetFile.MANIFEST.local), DatasetFile.MANIFEST.cap)) }.getOrNull()
            if (manifest != null && manifest.datasetDigest in state.blocked) {
                Files.deleteTree(dir)
                throw Refusal("dataset_blocked", "voice was lost twice under this dataset's values; it is not installed again, a newer one is")
            }
            if (manifest != null && manifest.datasetDigest == state.crashGuard?.datasetDigest) {
                Files.deleteTree(dir)
                throw Refusal("crash_guard_dataset", "the crash guard tripped on this dataset_digest; it waits for another")
            }
            if (manifest != null && manifest.datasetDigest == state.installed?.datasetDigest) {
                Files.deleteTree(dir)
                return linkedMapOf(Protocol.INSTALL_ID to id, Protocol.STATE to "already_installed")
            }
            for (old in jobs.values) {
                if (old.kind == "install" && old.state in setOf("verifying", "verified")) {
                    old.state = "superseded"
                    old.dir?.let(Files::deleteTree)
                }
            }
            jobs[id] = job
            lastJobId = id
            p.runOnWorker { verifyJob(job) }
            return linkedMapOf(Protocol.INSTALL_ID to id, Protocol.STATE to "verifying")
        } catch (e: java.io.IOException) {
            throw Refusal("storage", "staging failed: ${e.message}")
        }
    }

    private fun verifyJob(job: Job) {
        if (job.state != "verifying") return
        if (!busy.compareAndSet(null, job.id)) {
            job.refuse("busy", "another install job runs")
            return
        }
        try {
            val state = state()
            val sims = p.activeSims().orEmpty()
            val verifier = Verifier(bakedRef(), p::androidDefault, p.platformApnVersion(), state.crashGuard?.datasetDigest, sims.map { it.sim })
            val dataset = try {
                verifier.verify(job.dir!!)
            } finally {
                job.checks.clear()
                job.checks.addAll(verifier.checks)
            }
            job.commit = dataset.commit
            job.dataDigest = dataset.manifest.dataDigest
            job.datasetDigest = dataset.datasetDigest
            job.generatedAt = dataset.manifest.generatedAt
            job.laneSource = dataset.laneSource
            if (job.state != "verifying") return
            val index = store.indexDir(dataset.datasetDigest, cellKey())
            ServingIndex.write(dataset, index, "installed", p.cell(), cellKey())
            val staged = cached(index) ?: refuse("storage", "the new serving index cannot be read")
            installChecks(job, state, dataset, staged, sims)
            if (job.state == "verifying") job.state = "verified"
        } catch (e: Refused) {
            job.refuse(e.code, e.detail)
        } catch (e: Throwable) {
            job.refuse("storage", e.toString())
        } finally {
            busy.compareAndSet(job.id, null)
            update { it.copy(lastJob = job.toMap()) }
        }
    }

    /** The APN view of one dataset: its composed file and the row-to-profile map. */
    class ApnView(val composed: ApnLogic.Composed, val rowProfiles: Map<String, List<String>>)

    private fun apnView(dir: File, records: List<Record>): ApnView {
        val xml = Files.readBounded(File(dir, DatasetFile.APN_XML.local), DatasetFile.APN_XML.cap)
        val doc = ApnXml.parse(xml)
        val index = ApnIndex.parse(Files.readBounded(File(dir, DatasetFile.APN_INDEX.local), DatasetFile.APN_INDEX.cap))
        val rows = HashMap<String, MutableList<String>>()
        for (r in index.rows) rows.getOrPut(r.rowKey) { ArrayList() }.addAll(r.profiles)
        val omit = ApnLogic.omits(records, p.cell(), today(), rows.keys)
        return ApnView(ApnLogic.compose(xml, doc, omit), rows)
    }

    private fun apnView(dataset: Dataset, records: List<Record>): ApnView {
        val omit = ApnLogic.omits(records, p.cell(), today(), dataset.rowProfiles.keys)
        return ApnView(ApnLogic.compose(dataset.xml, dataset.doc, omit), dataset.rowProfiles)
    }

    /** The active dataset's folder (installed or baked), its serving data, and whether local records count. */
    private fun active(state: State): Triple<File, ServingData?, Boolean> =
        if (state.servingInstalled) Triple(store.datasetDir(state.installed!!.datasetDigest), installedServing(state), true) else Triple(p.bakedDir, buildServing(), false)

    private fun recordsFor(data: ServingData?, local: Boolean): List<Record> =
        BundleBuilder.effectiveRecords(data?.records.orEmpty(), if (local && data != null) localRecords(data.types) else emptyList())

    private fun simApn(view: ApnView, built: Built, sim: SimId): Triple<List<ApnXml.Row>, String?, String> {
        val selected = ApnSelect.select(view.composed.doc.rows, sim)
        val apnProfile = ApnSelect.apnProfile(built.stack.map { it.id }) { id -> built.stack.firstOrNull { it.id == id }?.apnRows ?: 0 }
        val coupling = ApnSelect.coupling(selected, apnProfile) { view.rowProfiles[it.rowKey].orEmpty() }
        return Triple(selected, apnProfile, coupling)
    }

    /** Checks 14 to 17 and the `changes` list, against the active dataset. */
    private fun installChecks(job: Job, state: State, dataset: Dataset, staged: ServingData, sims: List<ActiveSim>) {
        val (dir, activeData, activeLocal) = active(state)
        val oldView = apnView(dir, recordsFor(activeData, activeLocal))
        val newLocal = localRecords(staged.types)
        val newView = apnView(dataset, BundleBuilder.effectiveRecords(staged.records, newLocal))
        fun check(n: Int, ok: String, block: () -> Unit) {
            try {
                block()
                job.checks += Verifier.Check(n, true, ok)
            } catch (e: Refused) {
                job.checks += Verifier.Check(n, false, "${e.code}: ${e.detail}")
                throw e
            }
        }
        check(14, "the composed APN file keeps at least 95 % of the installed rows") {
            if (newView.composed.rows.toLong() * 100 < oldView.composed.rows.toLong() * 95) {
                refuse("apn_shrink", "${newView.composed.rows} rows against ${oldView.composed.rows} installed")
            }
        }
        val changes = ArrayList<Map<String, Any?>>()
        val oldChoice = Choice(activeData, activeLocal, false, "active")
        val newChoice = Choice(staged, true, false, "staged")
        var narrowNeedsRead = false
        for (s in sims) {
            if (s.sim.noSim) continue
            val oldBuilt = runCatching { buildFor(s.sim, s.subId, state, oldChoice) }.getOrNull()
            val newBuilt = buildFor(s.sim, s.subId, state, newChoice)
            val (oldRows, _, oldCoupling) = if (oldBuilt != null) simApn(oldView, oldBuilt, s.sim) else Triple(emptyList(), null, "same")
            val (newRows, newApnProfile, newCoupling) = simApn(newView, newBuilt, s.sim)
            check(15, "every SIM keeps an internet row, and an IMS row where it had one") {
                if (newRows.none { it.serves("default") }) refuse("sim_loses_internet", "the SIM in slot ${s.slot + 1} would have no internet row")
                if (oldRows.any { it.serves("ims") } && newRows.none { it.serves("ims") }) refuse("sim_loses_ims", "the SIM in slot ${s.slot + 1} would lose its IMS row")
            }
            check(16, "no SIM's APN coupling goes from same to mismatch") {
                if (oldCoupling == "same" && newCoupling == "mismatch") refuse("apn_coupling_lost", "the SIM in slot ${s.slot + 1} would lose its APN coupling")
            }
            if (ApnLogic.emergencyFields(oldRows) != ApnLogic.emergencyFields(newRows)) narrowNeedsRead = true
            val keys = (oldBuilt?.values?.keys.orEmpty() + newBuilt.values.keys).filter { it != Types.VERSION_KEY }.sorted()
            val changed = keys.filter { oldBuilt?.values?.get(it) != newBuilt.values[it] }.map { k ->
                linkedMapOf(
                    "key" to k,
                    "old" to oldBuilt?.values?.get(k)?.let { Types.toJson(oldBuilt.types.getValue(k), it) },
                    "new" to newBuilt.values[k]?.let { Types.toJson(newBuilt.types.getValue(k), it) },
                    "source" to newBuilt.sources[k],
                )
            }
            val oldKeys = oldRows.map { it.rowKey }.toSet()
            val newKeys = newRows.map { it.rowKey }.toSet()
            changes += linkedMapOf(
                "subscription" to s.subId,
                "slot" to s.slot,
                "network" to s.sim.network,
                "profile_before" to oldBuilt?.serving,
                "profile_after" to newBuilt.serving,
                "keys" to changed,
                "apn_added" to (newKeys - oldKeys).sorted(),
                "apn_removed" to (oldKeys - newKeys).sorted(),
                "coupling_before" to oldCoupling,
                "coupling_after" to newCoupling,
                "apn_profile_after" to newApnProfile,
                "x5_held" to newBuilt.x5Held.entries.associate { (k, v) -> k to linkedMapOf("data_value" to v.first, "served_value" to v.second) },
            )
        }
        val gate = gate(job.firstUse)
        check(17, "the emergency gate ($gate)") {
            val needsRead = if (gate == "narrow") narrowNeedsRead else ApnLogic.emergencyDigest(oldView.composed.doc.rows) != ApnLogic.emergencyDigest(newView.composed.doc.rows)
            if (needsRead) {
                val base = if (state.servingInstalled) state.installed!!.commit else bakedPin()?.commit
                ApnLogic.f5ReadProblem(job.f5Read, base, dataset.commit)?.let { refuse("emergency_gate", "emergency rows change: $it") }
            }
        }
        job.changes = Json.write(changes)
    }

    fun installStatus(id: String?): Map<String, Any?> {
        val job = id?.let { jobs[it] } ?: run {
            val last = state().lastJob
            if (last != null && last["install_id"] == id) {
                val final = setOf("installed", "refused", "apn_not_confirmed", "reload_not_proven", "in_place", "restored", "kept", "restore_failed")
                return LinkedHashMap(last).apply { if (this["state"] !in final) put("state", "interrupted") }
            }
            throw Refusal("not_found", "no install job $id")
        }
        return LinkedHashMap(job.toMap()).apply {
            put(Protocol.CHECKS, Json.write(job.checks.map { linkedMapOf("check" to it.n, "ok" to it.ok, "detail" to it.detail) }))
            job.changes?.let { put(Protocol.CHANGES, it) }
            job.apn?.let { put(Protocol.APN, it) }
        }
    }

    fun cancel(id: String?) {
        val job = id?.let { jobs[it] } ?: throw Refusal("not_found", "no install job $id")
        if (job.state !in setOf("verifying", "verified")) throw Refusal("not_verified", "install job $id is ${job.state}")
        job.state = "cancelled"
        job.dir?.let(Files::deleteTree)
    }

    // --- timing refusals of the app's installs (8.4) ----------------------------------------

    private fun appRefusal(state: State, sims: List<ActiveSim>?): String? {
        callRefusal(state)?.let { return it }
        sims ?: return "state_unreadable"
        for (s in sims) {
            if (s.sim.noSim) continue
            when (p.roaming(s.subId, s.sim.mcc)) {
                null -> return "state_unreadable"
                true -> return "roaming"
                false -> Unit
            }
        }
        if (state.apnTest != null) return "apn_test_set_in_place"
        when (p.apnTrialInPlace()) {
            null -> return "state_unreadable"
            true -> return "apn_trial_in_place"
            false -> Unit
        }
        return null
    }

    // --- activate (6.3, 7) ------------------------------------------------------------------

    fun activate(id: String?): Map<String, Any?> {
        val job = id?.let { jobs[it] } ?: throw Refusal("not_found", "no install job $id")
        if (job.state != "verified") throw Refusal("not_verified", "install job $id is ${job.state}")
        val state = state()
        refusals(state, forApp = true, install = true)
        busy.get()?.let { throw Refusal("busy", "install job $it runs") }
        val sims = p.activeSims()
        appRefusal(state, sims)?.let { throw Refusal("refused_now", "not now: $it", it) }
        if (!busy.compareAndSet(null, job.id)) throw Refusal("busy", "another install job runs")
        job.state = "activating"
        p.runOnWorker {
            try {
                runActivation(job, sims.orEmpty())
            } catch (e: Throwable) {
                if (job.state !in setOf("installed", "apn_not_confirmed", "apn_rollback_failed", "reload_not_proven")) job.refuse("storage", e.toString())
            } finally {
                busy.compareAndSet(job.id, null)
                runCatching { update { it.copy(lastJob = job.toMap()) } }
            }
        }
        return linkedMapOf(Protocol.STATE to "activating")
    }

    private fun runActivation(job: Job, sims: List<ActiveSim>) {
        val digest = job.datasetDigest!!
        val state = state()
        val staged = cached(store.indexDir(digest, cellKey())) ?: refuse("storage", "the staged serving index is gone")
        val newRecords = BundleBuilder.effectiveRecords(staged.records, localRecords(staged.types))
        val view = apnView(job.dir!!, newRecords)
        val (activeDir, activeData, activeLocal) = active(state)
        val rollback = { apnView(activeDir, recordsFor(activeData, activeLocal)).composed }
        val outcome = installApn(view.composed, digest, rollback, job)
        job.apn = Json.write(outcome.toMap())
        if (!outcome.ok) {
            job.state = outcome.code ?: "apn_not_confirmed"
            job.error = outcome.code
            job.reason = outcome.detail
            return
        }
        // Switch the CarrierConfig part to the same commit.
        job.state = "switching"
        val target = store.datasetDir(digest)
        if (target.exists() && target != job.dir) Files.deleteTree(target)
        target.parentFile?.mkdirs()
        if (!job.dir.renameTo(target)) refuse("storage", "the dataset could not be moved into place")
        val ref = InstalledRef(digest, job.commit!!, job.dataDigest!!, job.generatedAt!!, p.nowMs(), job.laneSource ?: "dataset")
        update {
            it.copy(installed = ref, active = "installed", servingReason = "installed")
        }
        job.state = "proving"
        val unproven = notifyAndProve(sims)
        update { s -> if (s.crashGuard != null && s.crashGuard.datasetDigest != digest && unproven.isEmpty()) s.copy(crashGuard = null, pullAgain = false) else s }
        prune()
        job.state = if (unproven.isEmpty()) "installed" else "reload_not_proven"
        if (unproven.isNotEmpty()) {
            job.error = "reload_not_proven"
            job.reason = "subscriptions ${unproven.sorted()} did not show the new version string within 60 s; they wait as pending"
        }
    }

    private fun prune() {
        val s = state()
        val keepDatasets = setOfNotNull(s.installed?.datasetDigest?.removePrefix("sha256:")?.take(12))
        val keepIdx = HashSet<String>()
        s.installed?.let { keepIdx += store.indexDir(it.datasetDigest, cellKey()).name }
        buildIndexDir()?.let { keepIdx += it.name }
        val live = jobs.values.filter { it.state in setOf("verifying", "verified", "activating") }
        live.mapNotNull { it.datasetDigest }.forEach { keepIdx += store.indexDir(it, cellKey()).name }
        store.prune(keepDatasets, keepIdx, live.mapNotNull { it.dir?.name }.toSet())
        // apn-out keeps the installed file only (the rollback recomposes from the dataset).
        store.apnOut.listFiles().orEmpty().filter { f -> s.apn.sha512 == null || !f.name.startsWith(s.apn.sha512.take(12)) }.forEach { it.delete() }
    }

    // --- the APN path (7.4, 7.5) ------------------------------------------------------------

    class ApnOutcome(val ok: Boolean, val code: String?, val detail: String, val sent: Boolean, val expected: Int, val raw: Int, val version: Long) {
        fun toMap(): Map<String, Any?> = linkedMapOf("ok" to ok, "error" to code, "detail" to detail, "sent" to sent, "expected_rows" to expected, "rows" to raw, "version" to version)
    }

    /** apn-out file name for a composed file. */
    fun apnName(c: ApnLogic.Composed) = "${c.sha512Prefix}.xml"

    /** The apn-out file the APN provider serves. */
    fun apnFile(name: String): File = File(store.apnOut, name)

    /**
     * 7.4 and 7.5: sends the composed file through UPDATE_APN_DB and confirms it in
     * TelephonyProvider. No broadcast when the file equals the confirmed installed one. A
     * failure is retried once with REQUIRED_HASH `NONE`, then rolled back to [rollback]'s file.
     * Every SIM's preferred APN is recorded by its fields first ([preferred], or read now) and set
     * again afterwards, since a table rewrite drops it (round 31, defaultEmergencyApnTest).
     */
    fun installApn(
        composed: ApnLogic.Composed,
        datasetDigest: String,
        rollback: (() -> ApnLogic.Composed?)?,
        job: Job?,
        preferred: List<PreferredApn>? = null,
        /** Round 32's candidate sets: this network's types must show exactly ([ApnLogic.typesDiffer]), and the preferred row may have changed. */
        exactNetwork: String? = null,
    ): ApnOutcome {
        val loose = if (exactNetwork != null) PREFERRED_LOOSE else emptySet()
        val state = state()
        if (state.apn.sha512 == composed.sha512 && state.apn.confirmedAtMs > 0) {
            if (state.apn.datasetDigest != datasetDigest) update { it.copy(apn = it.apn.copy(datasetDigest = datasetDigest)) }
            return ApnOutcome(true, null, "the APN file is the confirmed installed one; nothing sent", false, state.apn.rows, state.apn.rawRows, state.apn.lastVersion)
        }
        val prefs = preferred ?: recordPreferred()
        job?.state = "apn_installing"
        var first = send(composed, state.apn.sha512 ?: "NONE")
        job?.state = "apn_confirming"
        var shown = confirm(composed, exactNetwork)
        if (!shown.ok && first.second != "NONE") {
            first = send(composed, "NONE")
            shown = confirm(composed, exactNetwork)
        }
        if (shown.ok) {
            update { it.copy(apn = ApnState(first.first, composed.sha512, datasetDigest, p.nowMs(), shown.expected, shown.raw, null)) }
            restorePreferred(prefs, loose)
            return ApnOutcome(true, null, shown.detail, true, shown.expected, shown.raw, first.first)
        }
        p.log("APN file ${composed.sha512Prefix} did not show: ${shown.detail}")
        val back = rollback?.let { runCatching { it() }.getOrNull() }
        if (back == null) {
            update { it.copy(apn = it.apn.copy(lastError = "apn_not_confirmed")) }
            return ApnOutcome(false, "apn_not_confirmed", "the file did not show (${shown.detail}); nothing to roll back to", true, shown.expected, shown.raw, first.first)
        }
        val v = send(back, "NONE")
        val again = confirm(back, exactNetwork)
        return if (again.ok) {
            update { it.copy(apn = ApnState(v.first, back.sha512, it.apn.datasetDigest, p.nowMs(), again.expected, again.raw, "apn_not_confirmed")) }
            restorePreferred(prefs, loose)
            ApnOutcome(false, "apn_not_confirmed", "the file did not show (${shown.detail}); the previous file was installed again", true, shown.expected, shown.raw, first.first)
        } else {
            update { it.copy(apn = it.apn.copy(sha512 = null, confirmedAtMs = 0, lastError = "apn_rollback_failed")) }
            ApnOutcome(false, "apn_rollback_failed", "neither the new file nor the rollback showed (${again.detail})", true, shown.expected, shown.raw, first.first)
        }
    }

    /** Every active SIM's preferred APN, by its fields; SIMs without one or unreadable are left out. */
    private fun recordPreferred(): List<PreferredApn> = p.activeSims().orEmpty().filter { !it.sim.noSim }.mapNotNull { s ->
        when (val r = runCatching { p.preferredApn(s.subId) }.getOrDefault(PreferredRead.Unreadable)) {
            is PreferredRead.Row -> PreferredApn(s.subId, ApnLogic.preferredFields(r.fields), r.id)
            else -> null
        }
    }

    /**
     * Sets each recorded preferred APN again, to the row with the recorded fields (with [loose],
     * a candidate set's: else to the one row equal on the other fields). Answers the subscriptions
     * whose preferred APN is back.
     */
    private fun restorePreferred(prefs: List<PreferredApn>, loose: Set<String> = emptySet()): Set<Int> {
        if (prefs.isEmpty()) return emptySet()
        val rows = p.carrierRows() ?: run { p.log("TelephonyProvider could not be read; the preferred APN is not set again"); return emptySet() }
        val back = HashSet<Int>()
        for (pa in prefs) {
            val id = ApnLogic.matchPreferred(pa.fields, rows, pa.rowId) ?: ApnLogic.matchPreferredLoosely(pa.fields, rows, loose)
            if (id == null) {
                p.log("sub ${pa.subId}: its preferred APN is no longer in the table; Telephony picks one")
                continue
            }
            val now = runCatching { p.preferredApn(pa.subId) }.getOrDefault(PreferredRead.Unreadable)
            if ((now is PreferredRead.Row && now.id == id) || p.setPreferredApn(pa.subId, id)) back += pa.subId
        }
        return back
    }

    /** Whether each recorded preferred APN is set to a row with the recorded fields now. */
    private fun preferredBack(prefs: List<PreferredApn>): Boolean = prefs.all { pa ->
        val now = runCatching { p.preferredApn(pa.subId) }.getOrDefault(PreferredRead.Unreadable)
        now is PreferredRead.Row && pa.fields.all { (k, v) -> now.fields[k] == v }
    }

    /** Writes apn-out and sends the broadcast; VERSION is stored before sending. Answers (VERSION, REQUIRED_HASH). */
    private fun send(composed: ApnLogic.Composed, requiredHash: String): Pair<Long, String> {
        Files.atomicWrite(File(store.apnOut, apnName(composed)), composed.bytes)
        val nowSec = p.nowMs() / 1000
        var version = 0L
        update { s ->
            version = maxOf(nowSec, s.apn.lastVersion + 1)
            s.copy(apn = s.apn.copy(lastVersion = version))
        }
        if (version > Int.MAX_VALUE) p.log("VERSION $version no longer fits Integer.parseInt; the receiver refuses it")
        if (!p.sendApnUpdate(apnName(composed), version, requiredHash)) p.log("UPDATE_APN_DB could not be sent")
        return version to requiredHash
    }

    private fun confirm(composed: ApnLogic.Composed, exactNetwork: String? = null): ApnLogic.Shows {
        val deadline = p.nowMs() + CONFIRM_MS
        var last = ApnLogic.Shows(false, 0, 0, "TelephonyProvider could not be read")
        while (true) {
            p.sleep(CONFIRM_POLL_MS)
            val rows = p.queryCarriers()
            if (rows != null) {
                last = ApnLogic.shows(composed.doc.rows, rows)
                if (last.ok && exactNetwork != null) ApnLogic.typesDiffer(composed.doc.rows, rows, exactNetwork)?.let { last = ApnLogic.Shows(false, last.expected, last.raw, it) }
                if (last.ok) return last
            }
            if (p.nowMs() >= deadline) return last
        }
    }

    // --- reloads, proof, pending (7.6, 8.4) -------------------------------------------------

    /**
     * 7.6: notifies every active subscription and polls the version string for up to 60 s
     * until it is what CarrierSettings would serve now; the others wait as pending.
     */
    fun notifyAndProve(sims: List<ActiveSim>): Set<Int> {
        val state = state()
        val choice = choose(state)
        val expected = sims.filter { !it.sim.noSim }.associate { s ->
            s.subId to (runCatching { buildFor(s.sim, s.subId, state, choice).version }.getOrNull() ?: "")
        }
        val sent = HashSet<Int>()
        for (sub in expected.keys) if (p.notifyConfigChanged(sub)) sent += sub
        update { s -> s.copy(lastNotifyMs = s.lastNotifyMs + sent.associateWith { p.nowMs() }) }
        val left = expected.keys.toMutableSet()
        val deadline = p.nowMs() + PROOF_MS
        while (left.isNotEmpty() && p.nowMs() < deadline) {
            p.sleep(CONFIRM_POLL_MS)
            left.removeAll { sub -> p.effectiveVersion(sub) == expected[sub] }
        }
        if (left.isNotEmpty()) {
            update { s ->
                s.copy(
                    pending = s.pending + left,
                    pendingWhy = "reload_not_proven",
                    pendingSinceMs = if (s.pending.isEmpty()) p.nowMs() else s.pendingSinceMs,
                    proving = s.proving + left.associateWith { expected.getValue(it) },
                )
            }
            p.armRetry(RETRY_MS)
        } else {
            update { s -> s.copy(proving = s.proving - expected.keys) }
        }
        return left
    }

    /** A guarded reload through CarrierSettings's own notify (S4, S6). Answers the subscriptions reloaded and the refusal. */
    fun reload(requested: Set<Int>, why: String): Pair<Set<Int>, String?> {
        if (requested.isEmpty()) return emptySet<Int>() to null
        val sims = p.activeSims()
        val subs = if (sims == null) requested else requested intersect sims.map { it.subId }.toSet()
        if (subs.isEmpty()) return emptySet<Int>() to null
        val state = state()
        val refusal = if (sims == null) "state_unreadable" else if (state.apnTest != null) "apn_test_set_in_place" else callRefusal(state)
        if (refusal != null) {
            update { s -> s.copy(pending = s.pending + subs, pendingWhy = refusal, pendingSinceMs = if (s.pending.isEmpty()) p.nowMs() else s.pendingSinceMs) }
            p.armRetry(RETRY_MS)
            p.log("reload of ${subs.sorted()} waits ($why): $refusal")
            return emptySet<Int>() to refusal
        }
        val done = subs.filter { p.notifyConfigChanged(it) }.toSet()
        val failed = subs - done
        update { s ->
            val left = (s.pending - done) + failed
            s.copy(
                pending = left,
                pendingWhy = if (left.isEmpty()) "" else s.pendingWhy.ifEmpty { "the reload could not be sent" },
                pendingSinceMs = if (left.isEmpty()) 0 else s.pendingSinceMs,
                lastNotifyMs = s.lastNotifyMs + done.associateWith { p.nowMs() },
            )
        }
        if (failed.isNotEmpty()) p.armRetry(RETRY_MS)
        p.log("reloaded ${done.sorted()} ($why)")
        return done to if (failed.isNotEmpty()) "the reload could not be sent" else null
    }

    /** The retry alarm and every call end: waiting reloads, and CarrierSettings's own waiting install. */
    fun processPending(why: String) {
        val s = state()
        if (s.apnTest != null && s.apnTest.state in setOf("restore_waiting", "keep_waiting")) {
            restoreTestSet(restoreJob, keep = s.apnTest.state == "keep_waiting", stopReason = null)
            return
        }
        // Round 32: an APN candidate set goes at its time, on a SIM change or with the app disabled; nothing else installs meanwhile.
        if (s.apnTest?.isCandidate == true) {
            p.log("retry ($why): " + candidateCheck(s.apnTest, "retry"))
            return
        }
        if (s.ownInstall != null) {
            bootCheck("retry: $why")
            return
        }
        if (s.pending.isNotEmpty()) reload(s.pending, why)
        val after = state()
        if (after.pending.isNotEmpty() || after.ownInstall != null) {
            val waited = p.nowMs() - after.pendingSinceMs
            p.armRetry(if (after.pendingSinceMs > 0 && waited > TimeUnit.HOURS.toMillis(6)) TimeUnit.MINUTES.toMillis(30) else RETRY_MS)
        }
    }

    // --- call sightings (8.4) -------------------------------------------------------------------

    fun onCallState(idle: Boolean, emergency: Boolean?) {
        val now = p.nowMs()
        locked {
            val s = store.readState(now)
            val next = when {
                !idle && emergency != false -> s.copy(emergencySeenAtMs = now, emergencyUp = true)
                idle && s.emergencyUp -> s.copy(emergencySeenAtMs = now, emergencyUp = false, lastCallEndedAtMs = now)
                idle -> s.copy(lastCallEndedAtMs = now)
                else -> s
            }
            if (next != s) store.writeState(next)
        } ?: p.log("call state not recorded (busy); the in-memory record holds")
        if (idle) {
            p.armRetry(Guard.WINDOW_MS + RETRY_MS)
            // A temporary set's restore waits for the call only, not for the 10 minutes after it (R31.9): it runs now.
            val t = runCatching { state().apnTest }.getOrNull()
            if (t != null && t.state in setOf("restore_waiting", "keep_waiting")) p.runOnWorker { runCatching { processPending("the call ended") } }
        }
    }

    // --- crash guard (8.3) ----------------------------------------------------------------------

    /** CARRIER_CONFIG_CHANGED for a loaded SIM [sim] of [sub]. */
    fun onConfigChanged(sub: Int, sim: SimId) {
        val effective = p.effectiveVersion(sub) ?: return
        val state = state()
        val expected = runCatching { buildFor(sim, sub, state, choose(state)).version }.getOrNull()
        var action = GuardAction.NONE
        var tripped = false
        locked {
            val s = store.readState(p.nowMs())
            val (next0, a) = Guard.onConfigChanged(s, sub, effective, expected, p.nowMs())
            var next = next0
            action = a
            if (a == GuardAction.TRIP) {
                val served = s.served[sub]?.datasetDigest ?: (if (s.servingInstalled) s.installed!!.datasetDigest else bakedManifest()?.datasetDigest.orEmpty())
                next = Guard.trip(next, served, bakedManifest()?.datasetDigest, p.nowMs())
                tripped = true
            } else if (a == GuardAction.LATER) {
                next = next.copy(pending = next.pending + sub, pendingWhy = "a reload went out less than 2 minutes ago", pendingSinceMs = if (next.pending.isEmpty()) p.nowMs() else next.pendingSinceMs)
            }
            if (next != s) store.writeState(next)
        } ?: return
        when (action) {
            GuardAction.NONE -> Unit
            GuardAction.LATER -> p.armRetry(RETRY_MS)
            GuardAction.RELOAD -> reload(setOf(sub), "the effective config is not what CarrierSettings serves")
            GuardAction.TRIP -> {
                p.log("crash guard: two loads in a row without +ocd; the build's dataset serves and the app is asked to pull again")
                if (tripped) p.runOnWorker { runCatching { bootCheck("crash guard") } }
            }
        }
    }

    // --- the boot and daily check (8.1) -------------------------------------------------------

    /**
     * At LOCKED_BOOT_COMPLETED, at BOOT_COMPLETED, once a day, on `check`, and when the OCD app's
     * state changes, without the app: the build's index, the target of 8.1's table (round 31:
     * the app disabled serves the build), and when the target's CarrierConfig part is not active
     * or its APN file not the confirmed one, 7.3 to 7.6 for the target. These are CarrierSettings's
     * own installs: they wait for the call and emergency refusals and for the temporary emergency
     * APN set, and ignore roaming and the APN trial (a newer build pin found while roaming waits).
     */
    fun bootCheck(why: String): String {
        val result = runCatching { bootCheckInner(why) }.getOrElse { "failed: $it" }
        runCatching { update { it.copy(lastCheckMs = p.nowMs(), lastCheck = "$why: $result".take(400)) } }
        p.armDaily()
        p.log("check ($why): $result")
        return result
    }

    private fun isBoot(why: String) = why == "locked_boot_completed" || why == "boot_completed"

    private fun bootCheckInner(why: String): String {
        // A temporary APN set first. The emergency test's (R31.9): after a reboot, or left in place too
        // long, the full set comes back and it is a stop event. A candidate set: [candidateCheck].
        // While either is in place nothing else installs.
        state().apnTest?.let { t ->
            if (t.isCandidate) return candidateCheck(t, why)
            val stale = p.nowMs() - t.sinceMs > APN_TEST_MAX_MS || p.nowMs() < t.sinceMs
            return when {
                isBoot(why) -> "the emergency default test set was in place at a reboot: " + restoreTestSet(null, keep = false, stopReason = "rebooted with the emergency default test set in place")
                stale && t.state == "in_place" -> "the emergency default test set was left in place: " + restoreTestSet(null, keep = false, stopReason = "the emergency default test set was left in place over ${APN_TEST_MAX_MS / 60_000} minutes")
                t.state in setOf("restore_waiting", "keep_waiting") -> restoreTestSet(restoreJob, keep = t.state == "keep_waiting", stopReason = null)
                else -> "the emergency default test set is in place; nothing else installs"
            }
        }
        if (!busy.compareAndSet(null, "check")) return "an install job runs; checked again later"
        try {
            val build = ensureBuildIndex() ?: return "the build's dataset is unreadable; loads stream it or serve the fallback"
            val bakedM = bakedManifest()!!
            val writer = writerNow()
            var state = state()
            if (writer.state != "unknown" && writer.state != state.writerState) state = update { it.copy(writerState = writer.state) }
            // A newer build ends a voice-loss stop (round 31, safeMode (c)).
            state.voiceStop?.let { vs -> if (vs.incremental != p.incremental()) state = update { it.copy(voiceStop = null) } }
            val installed = state.installed
            var installedOk = true
            if (installed != null && !writer.off) {
                installedOk = runCatching {
                    Verifier(null, p::androidDefault, p.platformApnVersion(), null, emptyList()).verify(store.datasetDir(installed.datasetDigest), role = "build")
                    true
                }.getOrElse { p.log("the installed dataset fails its checks: $it"); false }
                if (installedOk && installedServing(state) == null) {
                    // The index is gone (a storage hiccup): rebuild it from the dataset.
                    runCatching {
                        val d = Verifier(bakedRef(), p::androidDefault, p.platformApnVersion(), null, emptyList()).verify(store.datasetDir(installed.datasetDigest), role = "build")
                        ServingIndex.write(d, store.indexDir(installed.datasetDigest, cellKey()), "installed", p.cell(), cellKey())
                    }.onFailure { installedOk = false }
                }
            }
            val bakedNewer = installed != null && Guard.newer(bakedM.datasetDigest, bakedM.generatedAt, installed.datasetDigest, installed.generatedAt, xIsBaked = true)
            val sims = p.activeSims()
            val roaming: Boolean? = if (sims == null) null else sims.filter { !it.sim.noSim }.map { p.roaming(it.subId, it.sim.mcc) }.let { r -> if (r.any { it == null }) null else r.any { it == true } }
            val target = Guard.bootTarget(state, installedOk, bakedNewer, roaming, writerOff = writer.off)
            if (target.dropInstalled) {
                state = update { it.copy(installed = null) }
                prune()
            }
            val targetData = if (target.target == "installed") installedServing(state) ?: return "the installed index is unreadable" else build
            val targetDir = if (target.target == "installed") store.datasetDir(state.installed!!.datasetDigest) else p.bakedDir
            val local = target.target == "installed"
            val view = apnView(targetDir, recordsFor(targetData, local))
            val active = sims.orEmpty().filter { !it.sim.noSim }
            val sm7Now = sm7(state, writer)
            val ccStale = state.active != target.target || active.any { sub ->
                state.served[sub.subId]?.let { it.datasetDigest != targetData.datasetDigest || it.sm7 != sm7Now } == true
            }
            val apnStale = state.apn.sha512 != view.composed.sha512 || state.apn.confirmedAtMs == 0L
            if (!ccStale && !apnStale) {
                update { it.copy(servingReason = target.reason, ownInstall = null) }
                return "target ${target.target} (${target.reason}); nothing to install" + if (target.waitsForHome) "; a newer build pin waits until home" else ""
            }
            callRefusal(state)?.let { refusal ->
                update { it.copy(servingReason = target.reason, ownInstall = refusal) }
                p.armRetry(RETRY_MS)
                return "target ${target.target} (${target.reason}) waits: $refusal"
            }
            update { it.copy(active = target.target, servingReason = target.reason, ownInstall = null) }
            val outcome = if (apnStale) installApn(view.composed, targetData.datasetDigest, null, null) else null
            // An APN change alone needs no CarrierConfig reload (it would cost an IMS re-registration).
            val unproven = if (ccStale) notifyAndProve(active) else emptySet()
            prune()
            return "target ${target.target} (${target.reason})" +
                (outcome?.let { if (it.ok) "; APN ${if (it.sent) "installed" else "unchanged"} (${it.expected} rows)" else "; APN ${it.code}: ${it.detail}" } ?: "") +
                (if (unproven.isNotEmpty()) "; reload of ${unproven.sorted()} not proven yet" else "")
        } finally {
            busy.compareAndSet("check", null)
        }
    }

    /**
     * The light check (a 15-minute alarm, PACKAGE_CHANGED while the process runs, every load): when
     * the OCD app's state changed, the full check runs. Disabling the app serves the build's
     * dataset, both parts; enabling it again keeps the build until the app installs (R31.4).
     */
    fun appCheck(why: String): String? {
        val w = writerNow()
        val s = state()
        if (w.state == "unknown" || w.state == s.writerState) return null
        p.log("the OCD app is ${w.state} ($why)")
        return bootCheck("the OCD app is ${w.state}")
    }

    // --- changes halted, tester, clear (8.4, 6.3, R31.5) -----------------------------------------

    fun stop(reason: String) {
        update { it.copy(halted = it.halted ?: Halted(p.nowMs(), reason.take(240).ifEmpty { "stop event" })) }
    }

    /** `resume` ends a stop event, a voice-loss stop included (its dataset stays blocked). */
    fun resume() {
        update { it.copy(halted = null, voiceStop = null, notice = null) }
    }

    /** `set_tester`: the app's declaration whether dialled tests run on this phone (SM7). A change reloads the SIMs. */
    fun setTester(on: Boolean, reason: String): Map<String, Any?> {
        val before = state()
        update { it.copy(tester = TesterDecl(on, p.nowMs(), reason.take(240))) }
        if (before.tester?.active != on) p.runOnWorker { bootCheck("the tester is ${if (on) "on" else "off"}") }
        val after = state()
        return linkedMapOf("tester" to testerActive(after), "sm7" to sm7(after))
    }

    /** adb `clear_dataset`: the installed and staged datasets and the local records go; serving returns to the build (`no_dataset`). */
    fun clearDataset() {
        for (j in jobs.values) if (j.state in setOf("verifying", "verified")) {
            j.state = "cancelled"
            j.dir?.let(Files::deleteTree)
        }
        update { it.copy(installed = null, active = "build", servingReason = "no_dataset") }
        store.localRecordsFile.delete()
        prune()
        p.runOnWorker { bootCheck("cleared over adb") }
    }

    fun check(why: String): String {
        p.runOnWorker { bootCheck(why) }
        val s = state()
        return "check started; serving ${if (s.servingInstalled) "installed" else "build"} (${s.servingReason})"
    }

    /** A stop event CarrierSettings raises itself: changes halted, a notice in status, and a notification. */
    private fun stopEvent(reason: String) {
        val again = state().notice == reason.take(240)
        update { it.copy(halted = it.halted ?: Halted(p.nowMs(), reason.take(240)), notice = reason.take(240)) }
        p.log("stop event: $reason")
        if (!again) runCatching { p.notifyOwner("Open Carrier Data: stop event", reason) }
    }

    // --- the voice-loss hold (round 31, safeMode (c)) -------------------------------------------

    /**
     * The values that differ from the build's dataset now, for every SIM in the phone: served
     * keys and selected APN rows, fingerprinted. A second voice loss under the same fingerprint is
     * a stop event.
     */
    private fun valuesDigest(state: State): String {
        val installedData = installedServing(state) ?: return ""
        val buildData = buildServing() ?: return ""
        val (dir, _, _) = active(state)
        val instView = apnView(dir, recordsFor(installedData, true))
        val buildView = apnView(p.bakedDir, recordsFor(buildData, false))
        val sm7 = sm7(state)
        val lines = ArrayList<String>()
        for (s in p.activeSims().orEmpty().filter { !it.sim.noSim }) {
            val a = buildFor(s.sim, s.subId, state, Choice(installedData, true, false, "installed"), sm7)
            val b = buildFor(s.sim, s.subId, state, Choice(buildData, false, false, "build"), sm7)
            val tag = "${s.sim.network}|${a.serving}"
            for (k in (a.values.keys + b.values.keys).filter { it != Types.VERSION_KEY }.toSortedSet()) {
                val av = a.values[k]
                if (av != b.values[k]) lines += "$tag|$k=" + (av?.let { Json.write(Types.toJson(a.types.getValue(k), it)) } ?: "android_default")
            }
            val ar = ApnSelect.select(instView.composed.doc.rows, s.sim).map { it.rowKey }.toSet()
            val br = ApnSelect.select(buildView.composed.doc.rows, s.sim).map { it.rowKey }.toSet()
            (ar - br).forEach { lines += "$tag|apn+$it" }
            (br - ar).forEach { lines += "$tag|apn-$it" }
        }
        return "sha256:" + Files.sha256(lines.sortedWith(::compareBytes).joinToString("\n"))
    }

    /**
     * `voice_loss_hold`: only the build's dataset restored voice. The build serves, both parts,
     * until the split releases the hold. Lost again under the same differing values: a stop
     * event; that dataset is dropped and never installed again (a newer one is).
     */
    fun voiceLossHold(reason: String): Map<String, Any?> {
        val state = state()
        state.voiceStop?.let { return linkedMapOf("voice_loss" to "stop", "dataset_digest" to it.datasetDigest) }
        state.hold?.let { return linkedMapOf("voice_loss" to "hold", "values_digest" to it.valuesDigest) }
        val installed = state.installed
        if (installed == null || !state.servingInstalled) return linkedMapOf("voice_loss" to "no_difference")
        val digest = valuesDigest(state)
        val now = p.nowMs()
        val last = state.lastHold
        if (last != null && last.datasetDigest == installed.datasetDigest && last.valuesDigest == digest) {
            update {
                it.copy(
                    voiceStop = VoiceStop(now, reason.take(240).ifEmpty { "voice lost again" }, installed.datasetDigest, p.incremental()),
                    blocked = it.blocked + installed.datasetDigest, installed = null, active = "build", servingReason = "voice_loss_stop",
                    hold = null, lastHold = null, notice = "voice was lost twice under the same values; the build's dataset serves until resume or a newer build",
                )
            }
            runCatching { p.notifyOwner("Open Carrier Data: stop event", "Voice was lost twice under the same values. This build's carrier data serves until you resume or a newer build arrives.") }
            p.runOnWorker { bootCheck("voice lost twice under the same values") }
            return linkedMapOf("voice_loss" to "stop", "values_digest" to digest, "dataset_digest" to installed.datasetDigest)
        }
        update { it.copy(hold = VoiceHold(now, reason.take(240), installed.datasetDigest, digest), active = "build", servingReason = "voice_loss_hold") }
        p.runOnWorker { bootCheck("voice-loss hold") }
        return linkedMapOf("voice_loss" to "hold", "values_digest" to digest)
    }

    /** `voice_loss_release`: the split is done (winners put as records, or no failing value); the newest dataset serves again. */
    fun voiceLossRelease(outcome: String?, reason: String): Map<String, Any?> {
        if (outcome != "winners" && outcome != "no_failing_value") throw Refusal("bad_request", "outcome must be winners or no_failing_value")
        val state = state()
        val hold = state.hold ?: throw Refusal("not_found", "no voice-loss hold is on")
        update { it.copy(hold = null, lastHold = hold) }
        p.runOnWorker { bootCheck("voice-loss hold released ($outcome${if (reason.isNotEmpty()) ": ${reason.take(120)}" else ""})") }
        return linkedMapOf("voice_loss" to "released")
    }

    // --- the default emergency APN test (round 31, defaultEmergencyApnTest) ---------------------

    @Volatile private var restoreJob: Job? = null

    /**
     * `emergency_default_set`: installs the installed commit's APN file without this SIM's
     * matched emergency rows, so the app can test Android's default emergency request. The set is
     * stored before it is sent; while it is in place nothing else installs or reloads.
     */
    fun emergencyDefaultSet(subId: Int?): String {
        val sub = subId ?: throw Refusal("bad_request", "no sub_id")
        val state = state()
        refusals(state, forApp = true, install = true)
        state.apnTest?.let { throw Refusal("refused_now", "the ${it.label} is in place", "apn_test_set_in_place") }
        val sims = p.activeSims()
        appRefusal(state, sims)?.let { throw Refusal("refused_now", "not now: $it", it) }
        val sim = sims?.firstOrNull { it.subId == sub && !it.sim.noSim } ?: throw Refusal("not_found", "no active SIM with subscription $sub")
        val id = newId()
        val job = Job(id, "emergency_default_set", null, null)
        if (!busy.compareAndSet(null, id)) throw Refusal("busy", "another install job runs")
        job.state = "composing"
        jobs[id] = job
        p.runOnWorker {
            try {
                runTestSet(job, sim)
            } catch (e: Refused) {
                job.refuse(e.code, e.detail)
            } catch (e: Throwable) {
                if (job.state !in setOf("in_place", "apn_not_confirmed", "apn_rollback_failed")) job.refuse("storage", e.toString())
            } finally {
                busy.compareAndSet(id, null)
                runCatching { update { it.copy(lastJob = job.toMap()) } }
            }
        }
        return id
    }

    private fun runTestSet(job: Job, sim: ActiveSim) {
        val state = state()
        val (dir, data, local) = active(state)
        data ?: refuse("storage", "no serving index")
        val full = apnView(dir, recordsFor(data, local)).composed
        if (state.apn.sha512 != full.sha512 || state.apn.confirmedAtMs == 0L) {
            refuse("apn_not_confirmed", "TelephonyProvider does not hold the installed commit's confirmed APN file; no test set is made from another")
        }
        val selected = ApnSelect.select(full.doc.rows, sim.sim)
        val emergency = selected.filter { "emergency" in it.types }
        if (emergency.isEmpty()) refuse("no_emergency_row", "the SIM selects no emergency row of its own")
        val table = p.queryCarriers() ?: refuse("storage", "TelephonyProvider could not be read")
        val keys = emergency.map { ApnLogic.key(it) }.toSet()
        if (table.any { ApnLogic.key(it) in keys && (it.edited ?: 0) != 0 }) refuse("user_edited_row", "not run: user-edited row")
        val remove = emergency.map { it.rowKey }.toSortedSet()
        val temp = ApnLogic.compose(full.bytes, full.doc, remove)
        val removedCount = full.doc.rows.count { it.rowKey in remove }
        if (temp.doc.version != 8 || temp.doc.version != full.doc.version) refuse("apn_version", "the test set's version is ${temp.doc.version}")
        if (temp.rows != full.rows - removedCount) refuse("apn_index_mismatch", "the test set has ${temp.rows} rows, expected ${full.rows - removedCount}")
        val after = ApnSelect.select(temp.doc.rows, sim.sim)
        if (after.none { it.serves("default") }) refuse("sim_loses_internet", "the SIM would have no internet row")
        if (selected.any { it.serves("ims") } && after.none { it.serves("ims") }) refuse("sim_loses_ims", "the SIM would lose its IMS row")
        val prefs = recordPreferred()
        val ims = runCatching { p.imsSnapshot(sim.subId) }.getOrNull()
        job.removedRows = remove.toList()
        job.rows = temp.rows
        job.preferredRecorded = prefs.any { it.subId == sim.subId }
        // Stored before it is sent: a reboot with the set in place restores the full set.
        update { it.copy(apnTest = ApnTestSet(sim.subId, p.nowMs(), "installing", remove.toList(), temp.sha512, full.sha512, data.datasetDigest, temp.rows, prefs, ims)) }
        val outcome = installApn(temp, data.datasetDigest, { full }, job, preferred = prefs)
        job.apn = Json.write(outcome.toMap())
        if (!outcome.ok) {
            job.state = outcome.code ?: "apn_not_confirmed"
            job.error = outcome.code
            job.reason = outcome.detail
            if (outcome.code == "apn_rollback_failed") {
                job.state = "refused"
                // The retry alarm restores the full set (or the build's APN file) once this job has ended.
                stopEvent("the emergency default test set did not show, and neither did the full set")
                update { it.copy(apnTest = it.apnTest?.copy(state = "restore_waiting")) }
                p.armRetry(RETRY_MS)
            } else {
                update { it.copy(apnTest = null) }
            }
            return
        }
        update { it.copy(apnTest = it.apnTest?.copy(state = "in_place")) }
        job.state = "in_place"
        p.log("emergency default test set in place for sub ${sim.subId}: ${remove.size} row(s) out, ${temp.rows} rows")
    }

    /**
     * `emergency_default_restore`: the full set back, or with [keep] the set without the row kept
     * (Android's default won; its `emergency_default_won` record must be stored first).
     */
    fun emergencyDefaultRestore(keep: Boolean): String {
        val state = state()
        val t = state.apnTest ?: throw Refusal("apn_test_set_not_in_place", "no emergency default test set is in place")
        if (t.isCandidate) throw Refusal("apn_test_set_not_in_place", "no emergency default test set is in place (the APN candidate test set is)")
        if (t.state == "installing") throw Refusal("busy", "the test set is still being installed")
        if (t.state == "restoring") throw Refusal("busy", "the restore runs")
        if (keep) {
            val (dir, data, local) = active(state)
            val target = apnView(dir, recordsFor(data, local)).composed
            if (target.doc.rows.any { it.rowKey in t.removedRows }) {
                throw Refusal("record_invalid", "keep needs the emergency_default_won record of the removed row stored first (put_record)")
            }
        }
        busy.get()?.let { throw Refusal("busy", "install job $it runs") }
        val id = newId()
        val job = Job(id, "emergency_default_restore", null, null)
        job.state = "restoring"
        jobs[id] = job
        restoreJob = job
        p.runOnWorker {
            try {
                restoreTestSet(job, keep, stopReason = null)
            } catch (e: Throwable) {
                job.refuse("storage", e.toString())
            } finally {
                runCatching { update { it.copy(lastJob = job.toMap()) } }
            }
        }
        return id
    }

    /**
     * The restore of a temporary set (R31.9, and round 32's candidate set the same way): waits only
     * for a call, emergency callback or emergency SMS mode and 60 minutes after an emergency call;
     * installs the installed commit's file again (or keeps the emergency test's set without the
     * row); sets the preferred APN again; checks the rows, the emergency rows, the preferred APN,
     * mobile data and IMS. A restore that does not show, or a reboot with the set in place
     * ([stopReason]), is a stop event: the last good set again, else the build's APN file through
     * the last resort, and a notification either way. A failed restore ends `restore_failed` with
     * the five checks. [note] says why CarrierSettings restores a candidate set by itself.
     */
    private fun restoreTestSet(job: Job?, keep: Boolean, stopReason: String?, note: String? = null): String {
        state().apnTest ?: return "nothing in place"
        if (!busy.compareAndSet(null, "restore")) {
            p.armRetry(RETRY_MS)
            return "an install job runs; the restore follows"
        }
        try {
            if (stopReason != null) stopEvent(stopReason)
            val state = state()
            val t = state.apnTest ?: return "nothing in place"
            restoreRefusal(state)?.let { refusal ->
                update { it.copy(apnTest = t.copy(state = if (keep) "keep_waiting" else "restore_waiting")) }
                job?.state = "waiting"
                job?.waitingFor = refusal
                p.armRetry(RETRY_MS)
                return "the restore waits: $refusal"
            }
            update { it.copy(apnTest = t.copy(state = "restoring")) }
            job?.state = "restoring"
            job?.waitingFor = null
            val (dir, data, local) = active(state)
            val target = apnView(dir, recordsFor(data, local)).composed
            val keepNow = !t.isCandidate && keep && target.doc.rows.none { it.rowKey in t.removedRows }
            val buildFile = { runCatching { apnView(p.bakedDir, recordsFor(buildServing(), false)).composed }.getOrNull() }
            val prefs = preferredNow(t)
            val outcome = installApn(target, data?.datasetDigest.orEmpty(), buildFile, job, preferred = prefs, exactNetwork = t.candidate?.network)
            job?.apn = Json.write(outcome.toMap())
            if (!outcome.ok) {
                update { it.copy(apnTest = null) }
                val why = (if (t.isCandidate) "the restore after the ${t.label} did not show" else "the restore of the full APN set did not show") + " (${outcome.detail.take(120)})"
                stopEvent(why)
                if (outcome.code == "apn_not_confirmed" && state().apn.sha512 != null) {
                    // The build's APN file went in: serve the build's dataset, both parts (the last resort).
                    update { it.copy(installed = null, active = "build", servingReason = "no_dataset") }
                    notifyAndProve(p.activeSims().orEmpty())
                } else if (outcome.code == "apn_rollback_failed") {
                    // Nothing showed: CarrierSettings's own install tries again at the retry alarm, so the set does not stay.
                    update { it.copy(ownInstall = "apn_rollback_failed") }
                    p.armRetry(RETRY_MS)
                }
                val after = checksAfter(t, target, outcome, keepNow, prefs, wait = false)
                job?.let { j ->
                    j.checksAfter = after.summary
                    j.checks.clear()
                    j.checks += after.checks
                    j.failRestore(why)
                }
                processPendingReloads()
                return "restore_failed: ${outcome.detail}"
            }
            restorePreferred(prefs)
            val after = checksAfter(t, target, outcome, keepNow, prefs, wait = true)
            job?.checksAfter = after.summary
            job?.checks?.let { c ->
                c.clear()
                c += after.checks
            }
            update { it.copy(apnTest = null) }
            processPendingReloads()
            if (after.notes.isNotEmpty()) {
                val why = "after the ${t.label}: ${after.notes.joinToString("; ")}"
                stopEvent(why)
                job?.failRestore(why)
                return "restore_failed: $why"
            }
            val result = if (keepNow) "kept" else "restored"
            job?.state = result
            p.log("${t.label} $result for sub ${t.subId}" + (note?.let { " ($it)" } ?: ""))
            return result
        } finally {
            busy.compareAndSet("restore", null)
        }
    }

    /** The preferred APNs to set again: a candidate set's only for the SIMs still in the phone (a SIM change ends it). */
    private fun preferredNow(t: ApnTestSet): List<PreferredApn> {
        if (!t.isCandidate) return t.preferred
        val sims = p.activeSims() ?: return t.preferred
        return t.preferred.filter { pa -> sims.any { it.subId == pa.subId } }
    }

    private class After(val checks: List<Verifier.Check>, val notes: List<String>, val summary: String)

    /**
     * The five checks after a restore (R31.9): 1 rows, 2 the emergency rows, 3 the preferred APN,
     * 4 mobile data validated, 5 IMS registered as before. A candidate set is checked against how
     * the SIM was before it, since a measured failure opened it: mobile data that was not validated
     * before passes, and IMS passes with at least the services it had; a SIM no longer in the
     * phone is not measurable and passes 4 and 5. Check 2 for a candidate set: every emergency row
     * of the SIM's network in the installed file shows in TelephonyProvider. [wait]: up to 2 minutes
     * for data and IMS to come back.
     */
    private fun checksAfter(t: ApnTestSet, target: ApnLogic.Composed, outcome: ApnOutcome, keepNow: Boolean, prefs: List<PreferredApn>, wait: Boolean): After {
        val c = t.candidate
        val simGone = c != null && p.activeSims()?.none { it.subId == t.subId } == true
        val rows = p.queryCarriers()
        val wanted = if (c != null) target.doc.rows.filter { it.numeric == c.network && "emergency" in it.types } else target.doc.rows.filter { it.rowKey in t.removedRows }
        val emergencyBack = keepNow || (rows != null && wanted.all { r -> rows.any { ApnLogic.key(it) == ApnLogic.key(r) } })
        fun dataOk(d: Boolean?) = d != false || simGone || (c != null && c.dataBefore != true)
        fun imsOk(s: String?) = t.ims == null || simGone || s == t.ims || (c != null && ApnCandidateSet.imsNoWorse(t.ims, s))
        var data: Boolean? = if (simGone) null else runCatching { p.mobileDataValidated(t.subId) }.getOrNull()
        var ims: String? = if (simGone) null else runCatching { p.imsSnapshot(t.subId) }.getOrNull()
        if (wait) {
            val deadline = p.nowMs() + AFTER_MS
            while ((!dataOk(data) || !imsOk(ims)) && p.nowMs() < deadline) {
                p.sleep(CONFIRM_POLL_MS)
                data = runCatching { p.mobileDataValidated(t.subId) }.getOrNull()
                ims = runCatching { p.imsSnapshot(t.subId) }.getOrNull()
            }
        }
        val prefBack = preferredBack(prefs)
        val dataText = when {
            simGone -> "not measurable (the SIM is no longer in the phone)"
            data == null -> "not measurable (no cellular network up)"
            data == false && c != null && c.dataBefore != true -> "false, as before the set"
            else -> data.toString()
        }
        val imsText = when {
            t.ims == null -> "IMS was not readable before"
            simGone -> "IMS not measurable (the SIM is no longer in the phone)"
            c != null && ims != t.ims && imsOk(ims) -> "IMS registered with at least the services it had before the set"
            else -> "IMS registered with the same services"
        }
        val checks = listOf(
            Verifier.Check(1, outcome.ok, "rows: ${outcome.detail.take(160)}"),
            Verifier.Check(2, emergencyBack, if (keepNow) "the emergency row stays out for this cell" else if (c != null) "the emergency rows of the SIM's network are as before" else "the emergency row is back"),
            Verifier.Check(3, prefBack, if (prefs.isEmpty()) "no preferred APN was set" else "the preferred APN is back by its fields"),
            Verifier.Check(4, dataOk(data), "mobile data validated: $dataText"),
            Verifier.Check(5, imsOk(ims), imsText),
        )
        val notes = ArrayList<String>()
        if (!emergencyBack) notes += if (c != null) "the emergency rows are not as before" else "the emergency row is not back"
        if (!prefBack) notes += "the preferred APN is not back"
        if (!dataOk(data)) notes += "mobile data is not validated"
        if (!imsOk(ims)) notes += "IMS is not registered with the same services"
        val summary = Json.write(
            linkedMapOf(
                "rows" to outcome.expected, "emergency_row_back" to (if (keepNow) "kept out" else emergencyBack), "preferred_apn_back" to prefBack,
                "mobile_data_validated" to (data ?: dataText), "ims_same" to (if (t.ims == null) "not readable before" else imsOk(ims)),
            ),
        )
        return After(checks, notes, summary)
    }

    // --- round 32: the APN candidate trials (D8 and D7 rule 4; rounds 33, 34 and 36) -------------

    /**
     * `apn_candidate_set`, extras `sub_id` and `candidate` (the app's ApnSetCandidate as JSON):
     * after a measured failure, one candidate set for one SIM as a non-persistent trial through
     * round 31 item 6's path. Refused like the emergency test's set (changes halted, a voice-loss
     * hold or stop, another job or temporary set, the timing refusals with the call window,
     * roaming, an APN test row), and also without the tester (SM7 and SM8), when the build's
     * dataset serves, and at the caps' backstop. The job composes the installed commit's file with
     * the candidate and checks it ([ApnCandidateSet.compose]), records the preferred APNs, IMS and
     * mobile data, stores the set before sending it, installs and confirms it. The set goes again
     * on `apn_candidate_restore`, [CANDIDATE_MAX_MS] after it went in, on a SIM change, with the
     * app disabled, and at boot.
     */
    fun apnCandidateSet(subId: Int?, candidateJson: String?): String {
        val sub = subId ?: throw Refusal("bad_request", "no sub_id")
        val candidate = try {
            ApnCandidateSet.parse(candidateJson)
        } catch (e: Refused) {
            throw Refusal(e.code, e.detail)
        }
        val state = state()
        refusals(state, forApp = true, install = true)
        state.apnTest?.let { throw Refusal("refused_now", "the ${it.label} is in place", "apn_test_set_in_place") }
        if (!testerActive(state)) {
            throw Refusal("not_tester", "APN candidates run only with the tester: the OCD app enabled, a calibrated IMS stack (SM8) and the tester declared (SM7 applies here)")
        }
        if (!state.servingInstalled) throw Refusal("not_serving_installed", "the build's dataset serves; candidates are tried on the installed dataset")
        candidateCap(state)?.let { throw Refusal("refused_now", it, "candidate_cap") }
        val sims = p.activeSims()
        appRefusal(state, sims)?.let { throw Refusal("refused_now", "not now: $it", it) }
        val sim = sims?.firstOrNull { it.subId == sub && !it.sim.noSim } ?: throw Refusal("not_found", "no active SIM with subscription $sub")
        val id = newId()
        val job = Job(id, "apn_candidate_set", null, null)
        job.candidate = candidate.id
        if (!busy.compareAndSet(null, id)) throw Refusal("busy", "another install job runs")
        job.state = "composing"
        jobs[id] = job
        p.runOnWorker {
            try {
                runCandidateSet(job, sim, sims, candidate)
            } catch (e: Refused) {
                job.refuse(e.code, e.detail)
            } catch (e: Throwable) {
                if (job.state !in setOf("in_place", "apn_not_confirmed", "apn_rollback_failed")) job.refuse("storage", e.toString())
            } finally {
                busy.compareAndSet(id, null)
                runCatching { update { it.copy(lastJob = job.toMap()) } }
            }
        }
        return id
    }

    private fun runCandidateSet(job: Job, sim: ActiveSim, sims: List<ActiveSim>, candidate: ApnCandidateSet.Candidate) {
        val state = state()
        if (!state.servingInstalled) refuse("not_serving_installed", "the build's dataset serves; candidates are tried on the installed dataset")
        val (dir, data, local) = active(state)
        data ?: refuse("storage", "no serving index")
        val full = apnView(dir, recordsFor(data, local)).composed
        if (state.apn.sha512 != full.sha512 || state.apn.confirmedAtMs == 0L) {
            refuse("apn_not_confirmed", "TelephonyProvider does not hold the installed commit's confirmed APN file; no candidate set is made from another")
        }
        val others = sims.filter { it.subId != sim.subId && !it.sim.noSim }.map { it.sim }
        val result = ApnCandidateSet.compose(full, sim.sim, others, candidate)
        val table = p.queryCarriers() ?: refuse("storage", "TelephonyProvider could not be read")
        if (table.any { ApnLogic.key(it) in result.touchedKeys && (it.edited ?: 0) != 0 }) refuse("user_edited_row", "not run: user-edited row")
        val prefs = recordPreferred()
        val ims = runCatching { p.imsSnapshot(sim.subId) }.getOrNull()
        val dataBefore = runCatching { p.mobileDataValidated(sim.subId) }.getOrNull()
        val temp = result.composed
        job.removedRows = result.omitted
        job.changedRows = result.changed
        job.addedRows = result.added
        job.rows = temp.rows
        job.preferredRecorded = prefs.any { it.subId == sim.subId }
        val now = p.nowMs()
        val trial = CandidateTrial(
            candidate.id, candidate.rule, candidate.kind, candidate.role, result.changed, result.added, sim.slot, sim.sim.mcc + sim.sim.mnc,
            sims.map { it.subId }.sorted(), dataBefore, now + CANDIDATE_INSTALL_MS + CANDIDATE_MAX_MS,
        )
        // Stored before it is sent: a reboot, a SIM change or the time restore the installed set.
        update {
            it.copy(
                apnTest = ApnTestSet(sim.subId, now, "installing", result.omitted, temp.sha512, full.sha512, data.datasetDigest, temp.rows, prefs, ims, ApnTestSet.APN_CANDIDATE, trial),
                candidateTrials = it.candidateTrials.filter { t -> kotlin.math.abs(now - t) < TimeUnit.DAYS.toMillis(1) } + now,
            )
        }
        p.armRetry(trial.untilMs - now)
        val outcome = installApn(temp, data.datasetDigest, { full }, job, preferred = prefs, exactNetwork = trial.network)
        job.apn = Json.write(outcome.toMap())
        if (!outcome.ok) {
            job.state = outcome.code ?: "apn_not_confirmed"
            job.error = outcome.code
            job.reason = outcome.detail
            if (outcome.code == "apn_rollback_failed") {
                job.state = "refused"
                // The retry alarm restores the installed set (or the build's APN file) once this job has ended.
                stopEvent("the APN candidate test set did not show, and neither did the installed set")
                update { it.copy(apnTest = it.apnTest?.copy(state = "restore_waiting")) }
                p.armRetry(RETRY_MS)
            } else {
                update { it.copy(apnTest = null) }
            }
            return
        }
        val until = p.nowMs() + CANDIDATE_MAX_MS
        update { s -> s.copy(apnTest = s.apnTest?.let { t -> t.copy(state = "in_place", candidate = t.candidate?.copy(untilMs = until)) }) }
        p.armRetry(CANDIDATE_MAX_MS + 1_000)
        job.state = "in_place"
        p.log(
            "APN candidate test set in place for sub ${sim.subId}: candidate ${candidate.id} (${candidate.rule}), " +
                "${result.omitted.size} row(s) out, ${result.changed.size} written anew, ${result.added.size} added, ${temp.rows} rows",
        )
    }

    /** `apn_candidate_restore`: the installed commit's set back, with the five checks (R31.9's restore). */
    fun apnCandidateRestore(): String {
        val t = state().apnTest ?: throw Refusal("apn_test_set_not_in_place", "no APN candidate test set is in place")
        if (!t.isCandidate) throw Refusal("apn_test_set_not_in_place", "no APN candidate test set is in place (the emergency default test set is)")
        if (t.state == "installing") throw Refusal("busy", "the candidate set is still being installed")
        if (t.state == "restoring") throw Refusal("busy", "the restore runs")
        busy.get()?.let { throw Refusal("busy", "install job $it runs") }
        val id = newId()
        val job = Job(id, "apn_candidate_restore", null, null)
        job.candidate = t.candidate?.id
        job.state = "restoring"
        jobs[id] = job
        restoreJob = job
        p.runOnWorker {
            try {
                restoreTestSet(job, keep = false, stopReason = null)
            } catch (e: Throwable) {
                job.refuse("storage", e.toString())
            } finally {
                runCatching { update { it.copy(lastJob = job.toMap()) } }
            }
        }
        return id
    }

    /**
     * A candidate set without the app's restore: at boot it goes as a stop event (as R31.9's set);
     * at its time, on a SIM change and with the app disabled it goes without one (a failed restore
     * still is one). Otherwise it stays, and the retry alarm comes back at its time.
     */
    private fun candidateCheck(t: ApnTestSet, why: String): String {
        if (isBoot(why)) return "the ${t.label} was in place at a reboot: " + restoreTestSet(null, keep = false, stopReason = "rebooted with the ${t.label} in place")
        if (t.state in setOf("restore_waiting", "keep_waiting")) return restoreTestSet(restoreJob, keep = false, stopReason = null)
        candidateDue(t)?.let { due ->
            val result = restoreTestSet(null, keep = false, stopReason = null, note = due)
            // With the app disabled the build's dataset serves next (R31.4).
            if (due == APP_OFF && result == "restored") p.runOnWorker { runCatching { bootCheck("the OCD app is ${writerNow().state}") } }
            return "the ${t.label} goes ($due): $result"
        }
        t.candidate?.let { c -> p.armRetry(maxOf(1_000L, c.untilMs - p.nowMs())) }
        return "the ${t.label} is in place; nothing else installs"
    }

    /** Why a candidate set goes now without the app, or null. */
    private fun candidateDue(t: ApnTestSet): String? {
        if (t.state !in setOf("installing", "in_place")) return null
        val c = t.candidate ?: return "the trial is not recorded"
        val now = p.nowMs()
        if (now >= c.untilMs || now < t.sinceMs) return "its time is up (${CANDIDATE_MAX_MS / 60_000} minutes in place)"
        if (writerNow().off) return APP_OFF
        val sims = p.activeSims() ?: return null
        if (sims.map { it.subId }.sorted() != c.subs) return "the SIMs in the phone changed"
        val sim = sims.firstOrNull { it.subId == t.subId } ?: return "the SIM is no longer in the phone"
        if (sim.sim.mcc + sim.sim.mnc != c.network) return "the SIM changed"
        return null
    }

    /** The caps' backstop: 3 candidate sets an hour, 15 a day (two IMS re-registrations each, of 6 and 30); the app counts the rest. */
    private fun candidateCap(state: State): String? {
        val now = p.nowMs()
        val day = state.candidateTrials.filter { kotlin.math.abs(now - it) < TimeUnit.DAYS.toMillis(1) }
        val hour = day.filter { kotlin.math.abs(now - it) < TimeUnit.HOURS.toMillis(1) }
        if (hour.size >= CANDIDATES_PER_HOUR) return "$CANDIDATES_PER_HOUR APN candidate sets within the hour (6 IMS re-registrations, the cap)"
        if (day.size >= CANDIDATES_PER_DAY) return "$CANDIDATES_PER_DAY APN candidate sets within the day (30 IMS re-registrations, the cap)"
        return null
    }

    /** CARRIER_CONFIG_CHANGED for any SIM or none: with a candidate set in place, the check sees whether its SIM is still there. */
    fun onSimChange(why: String) {
        val t = runCatching { state().apnTest }.getOrNull() ?: return
        if (!t.isCandidate) return
        p.runOnWorker { runCatching { bootCheck(why) } }
    }

    private fun processPendingReloads() {
        val s = state()
        if (s.pending.isNotEmpty()) p.runOnWorker { runCatching { reload(s.pending, "the temporary APN set is gone") } }
    }

    // --- records (6.4) ------------------------------------------------------------------------

    private fun activeServing(state: State): ServingData = (if (state.servingInstalled) installedServing(state) else null) ?: buildServing()
        ?: throw Refusal("storage", "no serving index yet")

    fun putRecord(json: String?): Map<String, Any?> {
        val state = state()
        refusals(state, forApp = true)
        val data = activeServing(state)
        val record = try {
            Record.parse(Json.parse(json ?: throw Refusal("bad_request", "no record")), "record", localOk = true) { k, v ->
                Types.typed(k, data.types[k] ?: refuse("unknown_key", "$k is not in the registry"), v)
            }
        } catch (e: Refused) {
            throw Refusal("record_invalid", e.detail)
        } catch (e: Json.ParseException) {
            throw Refusal("record_invalid", e.message.orEmpty())
        }
        if (record.kind != "measured") throw Refusal("record_invalid", "only measured records are written on the phone")
        // Round 31: a writer other than the owner flavor is a local-only tester; its records never claim the owner.
        if (!writerNow().ownerFlavor && record.tester != "local") throw Refusal("record_invalid", "this ROM's OCD app writes tester local records only")
        val cell = p.cell() ?: throw Refusal("record_wrong_cell", "ocd-build.json does not fit this phone")
        if (!cell.matches(record)) throw Refusal("record_wrong_cell", "the record is for another device, ROM or IMS stack")
        if (BundleBuilder.expired(record, today())) throw Refusal("record_expired", "valid_until has passed")
        val sims = p.activeSims().orEmpty().filter { !it.sim.noSim }
        if (record.carrier.scope == "all") throw Refusal("record_invalid", "a measured record names a carrier profile or a SIM key")
        val choice = choose(state)
        val builts = sims.map { it to buildFor(it.sim, it.subId, state, choice) }
        val served = builts.filter { (s, b) ->
            when (record.carrier.scope) {
                "profile" -> b.serving == record.carrier.profileId
                else -> (b.serving == "default" || b.stack.lastOrNull()?.fillOnly == true) && record.carrier.mcc == s.sim.mcc && record.carrier.mnc == s.sim.mnc &&
                    (if (record.carrier.gid1 != null) record.carrier.gid1.equals(s.sim.gid1, ignoreCase = true) else Matcher.eqIgnoreCase(record.carrier.spn, s.sim.spn))
            }
        }
        var profileEntry: ServedProfile? = null
        if (record.carrier.scope == "profile") {
            val pid = record.carrier.profileId!!
            val net = pid.split('.').getOrNull(1).orEmpty()
            val network = if (net.length >= 5) "${net.take(3)}-${net.drop(3)}" else ""
            profileEntry = data.network(network)?.firstOrNull { it.profile.id == pid }
            if (profileEntry == null && served.isEmpty()) throw Refusal("record_unknown_profile", "profile $pid is not in the installed dataset")
        }
        when (record.target.type) {
            "config" -> {
                val key = record.target.key!!
                if (key in data.groupA) throw Refusal("record_group_a", "$key is in group A; only a read record enters")
                if (key in data.groupB) throw Refusal("record_group_b", "$key is in group B; it is never served from a record")
                val overrides = (listOf<SimId?>(null) + sims.map { it.sim }).mapNotNull { runCatching { p.deviceOverride(it) }.getOrNull() }
                if (overrides.any { key in it }) throw Refusal("record_device_override", "$key is set by this phone's device override; no record is written for it")
                if (record.valueKind == "value") {
                    val candidate = if (served.isNotEmpty()) {
                        served.any { (s, b) -> candidates(data, b, s.sim, key).contains(record.value) }
                    } else {
                        offPhoneCandidates(data, record, profileEntry, key).contains(record.value)
                    }
                    if (!candidate) throw Refusal("record_not_candidate", "the value is not a candidate for $key")
                }
            }
            "apn", "emergency_data" -> {
                val (dir, activeData, local) = active(state)
                val view = apnView(dir, recordsFor(activeData, local))
                val rowKey = record.target.rowKey ?: throw Refusal("record_apn_row_unknown", "the record names no row")
                if (served.isEmpty()) throw Refusal("record_apn_row_unknown", "no SIM in the phone is served by this record's carrier")
                for ((s, _) in served) {
                    val selected = ApnSelect.select(view.composed.doc.rows, s.sim)
                    val row = selected.firstOrNull { it.rowKey == rowKey } ?: throw Refusal("record_apn_row_unknown", "the row is not in the SIM's selection")
                    if (record.target.type == "emergency_data") {
                        if (record.result == "emergency_default_won" && "emergency" !in row.types) throw Refusal("record_apn_row_unknown", "the row is not the SIM's emergency row")
                    } else if (selected.filter { it.rowKey != rowKey }.none { it.serves("default") }) {
                        throw Refusal("record_apn_would_remove_internet", "the omit would leave the SIM no internet row")
                    }
                }
            }
        }
        val published = data.records.firstOrNull { it.key == record.key }
        val superseded = published != null && (published.lastMeasured ?: "") >= (record.lastMeasured ?: "")
        locked {
            val local = localRecords(data.types).filter { it.key != record.key }
            store.writeLocalRecords(if (superseded) local else local + record)
            val s = store.readState(p.nowMs())
            store.writeState(s.copy(x5TestedHere = s.x5TestedHere + (record.key to record.validUntil.orEmpty())))
        } ?: throw Refusal("busy", "CarrierSettings's state is busy; try again")
        val effect = when {
            state.hold != null -> "stored_not_served:voice_loss_hold"
            writerNow().off -> "stored_not_served:app_disabled"
            state.crashGuard != null -> "stored_not_served:crash_guard"
            superseded -> "stored_not_served:superseded"
            record.target.type != "config" -> "apn_recompose_needed"
            record.valueKind == "none" -> "stored_not_served:no_value"
            else -> {
                val subs = served.map { it.first.subId }.toSet()
                val (done, refusal) = reload(subs, "a measured record")
                if (refusal == null && done.isNotEmpty()) "reload_sent" else "reload_pending"
            }
        }
        return linkedMapOf(Protocol.RECORD_KEY to record.key, Protocol.EFFECT to effect)
    }

    /** 6.4's candidate set for [key] on this SIM: the stack's value, its alternatives, the Google value, a bool's negation. */
    private fun candidates(data: ServingData, built: Built, sim: SimId, key: String): Set<Any> {
        val out = HashSet<Any>()
        var resolved: Any? = null
        for (prof in built.stack) prof.values[key]?.let { resolved = it }
        resolved?.let { out += it }
        for (prof in built.stack) prof.alternatives[key]?.forEach { out += it.value }
        val fillValues = built.fill?.let { f -> data.network(sim.network)?.firstOrNull { it.profile.id == built.serving }?.fillValues?.get(f.entry) }
        (fillValues?.get(key) ?: data.googleDefault[key])?.let { out += it }
        (built.values[key] as? Boolean)?.let { out += !it }
        if (data.types[key] == KeyType.BOOL && built.values[key] == null) {
            out += true
            out += false
        }
        return out
    }

    /**
     * The candidate set when no SIM in the phone is served by the record's carrier: the
     * profile's network-wide parents and itself as the stack, their alternatives, the Google
     * value, and both values of a bool (the served one is unknown).
     */
    private fun offPhoneCandidates(data: ServingData, record: Record, entry: ServedProfile?, key: String): Set<Any> {
        val out = HashSet<Any>()
        if (data.types[key] == KeyType.BOOL) {
            out += true
            out += false
        }
        if (entry == null) {
            data.googleDefault[key]?.let { out += it }
            return out
        }
        val network = entry.profile.match.mccmnc.first().key
        val stack = data.network(network).orEmpty().filter { it.profile.specificity == 0 && it.profile.id != entry.profile.id } + entry
        var resolved: Any? = null
        for (s in stack) s.profile.values[key]?.let { resolved = it }
        resolved?.let { out += it }
        for (s in stack) s.profile.alternatives[key]?.forEach { out += it.value }
        val fill = entry.profile.fill.firstOrNull { it.condition == null }?.let { entry.fillValues[it.entry] }
        (fill?.get(key) ?: data.googleDefault[key])?.let { out += it }
        return out
    }

    fun removeRecord(key: String?) {
        val state = state()
        val data = activeServing(state)
        val local = localRecords(data.types)
        val record = local.firstOrNull { it.key == key } ?: throw Refusal("not_found", "no local record $key")
        locked { store.writeLocalRecords(local - record) } ?: throw Refusal("busy", "CarrierSettings's state is busy; try again")
        if (record.target.type == "config") {
            p.activeSims()?.let { sims -> reload(sims.map { it.subId }.toSet(), "a measured record was removed") }
        }
    }

    fun listRecords(subId: Int?): List<Map<String, Any?>> {
        val state = state()
        val data = activeServing(state)
        val cell = p.cell()
        val today = today()
        val local = localRecords(data.types)
        val published = runCatching {
            val dir = active(state).first
            RecordsFile.parse(Files.readBounded(File(dir, DatasetFile.DEVICE_RECORDS.local), DatasetFile.DEVICE_RECORDS.cap)) { k, v ->
                Types.typed(k, data.types[k] ?: refuse("unknown_key", k), v)
            }.records
        }.getOrDefault(data.records)
        val effective = BundleBuilder.effectiveRecords(published.filter { cell?.matches(it) == true }, local).map { it.key }.toSet()
        val sim = subId?.let { id -> p.activeSims()?.firstOrNull { it.subId == id } }
        val built = sim?.let { runCatching { buildFor(it.sim, it.subId, state, choose(state)) }.getOrNull() }
        fun stateOf(r: Record, origin: String): String = when {
            cell == null || !cell.matches(r) -> "other_cell"
            !r.measured || r.carrier.scope == "all" -> "other"
            BundleBuilder.expired(r, today) -> "expired"
            r.key !in effective || (origin == "local" && (state.hold != null || state.crashGuard != null || !state.servingInstalled)) -> "superseded"
            r.target.key in data.groupA -> "held"
            r.target.key in data.groupB -> "other"
            built != null && r.target.type == "config" && r.key !in built.recordsApplied -> "other"
            else -> "served"
        }
        val out = ArrayList<Map<String, Any?>>()
        for (r in local) out += linkedMapOf("record" to r.json, "origin" to "local", "state" to stateOf(r, "local"))
        for (r in published) out += linkedMapOf("record" to r.json, "origin" to "dataset", "state" to stateOf(r, "dataset"))
        return out
    }

    /** `apply_apn_records`: recomposes the active dataset's APN file with the current records and installs it. */
    fun applyApnRecords(): String {
        val state = state()
        refusals(state, forApp = true, install = true)
        val sims = p.activeSims()
        appRefusal(state, sims)?.let { throw Refusal("refused_now", "not now: $it", it) }
        val id = newId()
        val job = Job(id, "apn_records", null, null)
        if (!busy.compareAndSet(null, id)) throw Refusal("busy", "another install job runs")
        job.state = "activating"
        jobs[id] = job
        p.runOnWorker {
            try {
                val (dir, data, local) = active(state)
                val view = apnView(dir, recordsFor(data, local))
                val previous = state.apn.sha512?.let { sha -> File(store.apnOut, "${sha.take(12)}.xml").takeIf { it.isFile } }
                val rollback = previous?.let { f -> { runCatching { val b = f.readBytes(); ApnLogic.compose(b, ApnXml.parse(b), emptySet()) }.getOrNull() } }
                val outcome = installApn(view.composed, data?.datasetDigest.orEmpty(), rollback, job)
                job.apn = Json.write(outcome.toMap())
                job.state = if (outcome.ok) "installed" else outcome.code ?: "apn_not_confirmed"
                if (!outcome.ok) {
                    job.error = outcome.code
                    job.reason = outcome.detail
                }
            } catch (e: Throwable) {
                job.refuse("storage", e.toString())
            } finally {
                busy.compareAndSet(id, null)
            }
        }
        return id
    }

    // --- explain (6.3) --------------------------------------------------------------------------

    fun explain(subId: Int, datasetSel: String?): String {
        val state = state()
        val sims = p.activeSims().orEmpty()
        val active = sims.firstOrNull { it.subId == subId }
        val sim = if (subId == -1) SimId.NO_SIM else active?.sim ?: throw Refusal("not_found", "no active SIM with subscription $subId")
        val (choice, dir, label) = when (datasetSel) {
            null, "served" -> {
                val c = choose(state)
                Triple(c, if (c.label == "installed") store.datasetDir(state.installed!!.datasetDigest) else p.bakedDir, if (c.label == "installed") "installed" else "build")
            }
            "build" -> Triple(Choice(buildServing(), false, false, "build"), p.bakedDir, "build")
            // The installed dataset whatever serves now: during a voice-loss hold the app's split reads it (R31.8).
            "installed" -> {
                val inst = state.installed ?: throw Refusal("not_found", "no dataset is installed")
                val data = installedServing(state) ?: throw Refusal("storage", "the installed index is unreadable")
                Triple(Choice(data, true, false, "installed"), store.datasetDir(inst.datasetDigest), "installed")
            }
            else -> {
                val known = jobs[datasetSel]
                // After a restart the job of the installed dataset is gone; its ID is still the last job's.
                val installedJob = known == null && state.installed != null && state.lastJob?.get("install_id") == datasetSel &&
                    state.lastJob?.get("dataset_digest") == state.installed.datasetDigest
                if (installedJob || (known != null && known.state == "installed" && known.datasetDigest == state.installed?.datasetDigest)) {
                    val data = installedServing(state) ?: throw Refusal("storage", "the installed index is unreadable")
                    return explainWith(subId, sim, active, state, Choice(data, true, false, "installed"), store.datasetDir(state.installed!!.datasetDigest), "installed")
                }
                val job = known ?: throw Refusal("not_found", "no install job $datasetSel")
                val data = job.datasetDigest?.let { cached(store.indexDir(it, cellKey())) } ?: throw Refusal("not_verified", "install job $datasetSel is ${job.state}")
                Triple(Choice(data, true, false, "staged"), job.dir?.takeIf { it.exists() } ?: store.datasetDir(job.datasetDigest!!), "staged")
            }
        }
        return explainWith(subId, sim, active, state, choice, dir, label)
    }

    private fun explainWith(subId: Int, sim: SimId, active: ActiveSim?, state: State, choice: Choice, dir: File, label: String): String {
        val built = buildFor(sim, subId, state, choice)
        val data = choice.data!!
        val keys = LinkedHashMap<String, Any?>()
        // R31.3: what the phone applies last, after OCD's bundle (LineageOS's fixed key, the device override XML).
        val override = runCatching { p.deviceOverride(if (sim.noSim) null else sim) }.getOrNull().orEmpty()
        for (k in (built.values.keys + built.sources.keys + built.groupA + override.keys).toSortedSet()) {
            val v = built.values[k]
            val entry = linkedMapOf<String, Any?>("value" to v?.let { Types.toJson(built.types.getValue(k), it) }, "source" to (built.sources[k] ?: "android_default"))
            override[k]?.let { o ->
                entry["under_override"] = linkedMapOf("value" to entry["value"], "source" to entry["source"])
                entry["value"] = Types.toJson(o.type, o.value)
                entry["source"] = o.source
            }
            keys[k] = entry
        }
        val apn: Map<String, Any?> = if (sim.noSim) emptyMap() else runCatching {
            val view = apnView(dir, recordsFor(data, choice.local))
            val (rows, apnProfile, coupling) = simApn(view, built, sim)
            linkedMapOf(
                "apn_profile" to apnProfile,
                "coupling" to coupling,
                "rows" to rows.map { r ->
                    linkedMapOf("row_key" to r.rowKey, "apn" to r.attrs["apn"], "types" to r.types, "protocol" to (r.attrs["protocol"] ?: "IP"), "roaming_protocol" to (r.attrs["roaming_protocol"] ?: "IP"), "mvno_type" to r.attrs["mvno_type"], "emergency" to ("emergency" in r.types))
                },
            )
        }.getOrElse { linkedMapOf("error" to it.message) }
        val out = linkedMapOf(
            "subscription" to subId,
            "slot" to active?.slot,
            "network" to if (sim.noSim) null else sim.network,
            "stack" to built.stack.map { linkedMapOf("id" to it.id, "specificity" to it.specificity) },
            "serving" to built.serving,
            "fill" to built.fill?.let { linkedMapOf("entry" to it.entry, "when" to it.condition?.let { w -> linkedMapOf("kind" to w.kind, "value" to w.value) }) },
            "fill_missing" to built.fillSkipped,
            "dataset" to label,
            "commit" to data.commit,
            "dataset_digest" to data.datasetDigest,
            "version_string" to built.version,
            "keys" to keys,
            "x5_held" to built.x5Held.entries.associate { (k, v) -> k to linkedMapOf("data_value" to v.first, "served_value" to v.second) },
            "sm7" to built.sm7,
            "sm7_held" to built.sm7Held.entries.associate { (k, v) ->
                k to linkedMapOf("data_value" to v.first?.let { Types.toJson(data.types.getValue(k), it) }, "served_value" to v.second?.let { Types.toJson(data.types.getValue(k), it) })
            },
            "records_applied" to built.recordsApplied,
            "apn_test_set" to state.apnTest?.label,
            "apn" to apn,
        )
        return Json.write(out)
    }
}
