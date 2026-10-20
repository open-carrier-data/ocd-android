package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/*
 * What a background run tests (the owner, 2026-10-20: automatic, but
 * silent; the schedule of the research report of the same day). Each SIM has
 * an entry per feature value ("volte:on", "volte:off", ...) and one per OCD
 * APN row the phone lacks ("apn:<key>"). The read-only check settles every
 * entry it can see without a change (the value the phone has, when IMS shows
 * it) and the static ones (VoNR on the S20's modem, video calling on PHH
 * IMS). The rest need a change, and those run at night, one feature a night:
 * at most one off value and two on values. An entry is due on a trigger (a
 * new SIM, a new data release, a new ROM or app build, a kept value failing,
 * a switch whose value changed under the app, a test that did not finish)
 * and otherwise in rotation once two weeks old. Two failures in a row pause
 * an entry for four weeks; after a ROM flash or a data release only on
 * values run for two nights.
 */

/** What a test result rests on. A change of any of the three tests every entry again. */
@Serializable
data class TestBasis(
    /** The data release tag and digest, or "bundled" before the first pull. */
    val release: String,
    /** Build.FINGERPRINT and the build time: a new ROM build. */
    val rom: String,
    /** The app's version and its APK on disk: a new app build. */
    val app: String,
) {
    fun changesFrom(old: TestBasis): List<String> = buildList {
        if (release != old.release) add(PlanWhy.NEW_RELEASE)
        if (rom != old.rom) add(PlanWhy.NEW_ROM_BUILD)
        if (app != old.app) add(PlanWhy.NEW_APP_BUILD)
    }
}

/** One entry's last result on one SIM. */
@Serializable
data class EntryRecord(
    val sim: SimKey,
    /** "<feature>:on", "<feature>:off" or "apn:<row key>". */
    val entry: String,
    val atMs: Long,
    val basis: TestBasis,
    /** True: the value reached its outcome (registered with its service; service gone for an off). Null: not decided now (skipped, a call, a failure). */
    val works: Boolean?,
    val outcome: String,
    /** The switch's effective value afterwards (a kept value or the phone's own); null for APN rows. */
    val effective: Boolean? = null,
    /** Failures in a row: a refusal in the middle, a revert or a baseline that did not come back. */
    val failures: Int = 0,
    /** Settled without a change: seen by the read-only check, or static. */
    val observed: Boolean = false,
) {
    val feature: OwnerFeature? get() = OwnerFeature.ofWire(entry.substringBefore(':'))
    val value: Boolean? get() = when (entry.substringAfter(':', "")) {
        "on" -> true
        "off" -> false
        else -> null
    }

    companion object {
        const val APN = "apn"
        const val MAX = 120
    }
}

/** The read-only check of one SIM: the switches now, what it settled without a change, how the kept values fare. No write. */
data class SimCheck(
    val sim: OwnerSim,
    val effective: Map<OwnerFeature, Boolean?>,
    /** Kept keys missing from the override layer: the reconcile applies them again. */
    val missingKeeps: List<String> = emptyList(),
    /** Kept values that IMS observably does not register with now. */
    val failingKeeps: List<OwnerFeature> = emptyList(),
    /** Entries seen without a change, and whether the value works. */
    val observed: Map<String, Boolean> = emptyMap(),
    /** Entries settled by a static check, with the reason. */
    val static: Map<String, String> = emptyMap(),
    /** OCD APN rows the phone lacks (their keys): each an entry that needs a change. */
    val apnToTest: List<String> = emptyList(),
    /** OCD APN rows the phone has (present, or the same row to the provider). */
    val apnPresent: Map<String, String> = emptyMap(),
    /** IMS registered for voice and mobile registered at home: the baseline a trial may start from. */
    val healthy: Boolean = true,
    val imsLine: String = "",
)

/** Why an entry is in a background run. */
object PlanWhy {
    const val NEW_SIM = "new_sim"
    const val NOT_TESTED = "not_tested_yet"
    const val NEW_RELEASE = "new_data_release"
    const val NEW_ROM_BUILD = "new_rom_build"
    const val NEW_APP_BUILD = "new_app_build"
    const val KEPT_FAILING = "kept_value_failing"
    const val VALUE_CHANGED = "value_changed"

    /** The last test did not decide (no voice path then, a call, the owner's run without the off values, a failure). */
    const val RETRY = "not_decided_last_time"
    const val ROTATION = "rotation"
}

data class PlannedEntry(val slot: Int, val sim: SimKey, val entry: String, val why: String, val sinceMs: Long = 0) {
    val feature: OwnerFeature? get() = OwnerFeature.ofWire(entry.substringBefore(':'))
    val value: Boolean? get() = when (entry.substringAfter(':', "")) {
        "on" -> true
        "off" -> false
        else -> null
    }
    val apnKey: String? get() = if (entry.startsWith(EntryRecord.APN + ":")) entry.substringAfter(':') else null

    /** An on value or an APN row; an off value turns a working service off. */
    val isOn: Boolean get() = value != false
    val triggered: Boolean get() = why != PlanWhy.ROTATION
    val group: String get() = entry.substringBefore(':')
}

data class Plan(
    /** The night's change entries: one feature (or the APN rows), at most one off and two on values. */
    val entries: List<PlannedEntry>,
    /** Kept values that fail now: dropped in any silent window (the smart revert need not wait for the night). */
    val reverts: List<PlannedEntry> = emptyList(),
    val reapply: Boolean = false,
    /** Due entries left for later nights, and the ones paused after failures. */
    val waiting: Int = 0,
    val paused: List<String> = emptyList(),
) {
    val triggered: Boolean get() = entries.any { it.triggered }
    val all: List<PlannedEntry> get() = reverts + entries
    val line: String
        get() = if (all.isEmpty()) {
            "nothing due"
        } else {
            all.groupBy { it.why }.entries.joinToString("; ") { (why, list) -> "$why: ${list.joinToString { "${it.entry} (slot ${it.slot + 1})" }}" } +
                (if (waiting > 0) "; $waiting more on later nights" else "")
        }
}

object QuietPlan {
    val ROTATION_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)
    val RETRY_AFTER_MS: Long = TimeUnit.DAYS.toMillis(1)
    val PAUSE_MS: Long = TimeUnit.DAYS.toMillis(28)
    const val PAUSE_AFTER_FAILURES = 2
    const val MAX_OFF_PER_NIGHT = 1
    const val MAX_ON_PER_NIGHT = 2

    /** After a ROM flash or a data release, only on values run for this long. */
    val ON_ONLY_MS: Long = TimeUnit.DAYS.toMillis(2)

    /** A trigger that has waited this long for a night runs in the next silent window, night or not. */
    val NIGHT_GRACE_MS: Long = TimeUnit.DAYS.toMillis(3)

    fun entry(feature: OwnerFeature, value: Boolean): String = "${feature.wire}:${if (value) "on" else "off"}"
    fun apnEntry(key: String): String = "${EntryRecord.APN}:$key"

    val FEATURE_ENTRIES: List<String> = OwnerFeature.entries.flatMap { listOf(entry(it, true), entry(it, false)) }

    /** Skips that a later test would only repeat: recorded as results. */
    val STATIC_SKIPS = setOf("device_not_capable", OwnerRunner.NOT_AVAILABLE)

    /** "Not now" refusals: recorded without a failure, tried again after [RETRY_AFTER_MS]. */
    val NOT_NOW = setOf(
        OwnerRunner.NO_VOICE_PATH, OwnerRunner.IMS_ON_WIFI, OwnerRunner.FIRST_USE_PENDING, "off_tests_not_chosen", "wifi_not_connected",
        "wifi_calling_backoff", "call_active", "revert_waits_for_call", "roaming", "just_booted", "system_restarted", "emergency_call_recent",
    )

    /**
     * The entries due now ([Plan]). [known] are the SIMs the owner build has
     * tested before, so a SIM without records is "new" only when it is.
     * [onOnly]: after a ROM flash or a data release, off values wait.
     * [nightUsed]: tonight's change was made already; only reverts run.
     */
    fun plan(
        checks: List<SimCheck>,
        records: List<EntryRecord>,
        basis: TestBasis,
        nowMs: Long,
        known: Set<SimKey> = emptySet(),
        onOnly: Boolean = false,
        nightUsed: Boolean = false,
    ): Plan {
        val due = mutableListOf<PlannedEntry>()
        val reverts = mutableListOf<PlannedEntry>()
        val paused = mutableListOf<String>()
        for (check in checks) {
            val key = check.sim.key
            val mine = records.filter { it.sim == key }
            for (feature in check.failingKeeps) {
                val kept = check.effective[feature] ?: continue
                reverts += PlannedEntry(check.sim.slot, key, entry(feature, kept), PlanWhy.KEPT_FAILING)
            }
            for (entry in FEATURE_ENTRIES + check.apnToTest.map(::apnEntry)) {
                if (entry in check.observed || entry in check.static) continue
                if (reverts.any { it.sim == key && it.entry == entry }) continue
                val last = mine.lastOrNull { it.entry == entry }
                val feature = OwnerFeature.ofWire(entry.substringBefore(':'))
                val now = feature?.let { check.effective[it] }
                val why = when {
                    last == null -> if (mine.isEmpty() && key !in known) PlanWhy.NEW_SIM else PlanWhy.NOT_TESTED
                    else -> basis.changesFrom(last.basis).firstOrNull()
                        ?: PlanWhy.VALUE_CHANGED.takeIf { last.effective != null && now != null && now != last.effective }
                        ?: PlanWhy.RETRY.takeIf { last.works == null && nowMs - last.atMs >= RETRY_AFTER_MS }
                        ?: PlanWhy.ROTATION.takeIf { nowMs - last.atMs >= ROTATION_AGE_MS || nowMs < last.atMs }
                } ?: continue
                if (last != null && last.failures >= PAUSE_AFTER_FAILURES && nowMs - last.atMs < PAUSE_MS) {
                    paused += entry
                    continue
                }
                val planned = PlannedEntry(check.sim.slot, key, entry, why, last?.atMs ?: 0L)
                if (onOnly && !planned.isOn) continue
                due += planned
            }
        }
        if (nightUsed) return Plan(emptyList(), reverts, checks.any { it.missingKeeps.isNotEmpty() }, due.size, paused)
        // Triggered before rotation, the oldest first; then the rest of that one feature within the night's budget.
        val ordered = due.sortedWith(compareBy({ !it.triggered }, { it.sinceMs }))
        val first = ordered.firstOrNull()
        val night = mutableListOf<PlannedEntry>()
        if (first != null) {
            var offs = 0
            var ons = 0
            for (p in ordered.filter { it.slot == first.slot && it.group == first.group }) {
                if (p.isOn && ons < MAX_ON_PER_NIGHT) {
                    ons++
                    night += p
                } else if (!p.isOn && offs < MAX_OFF_PER_NIGHT) {
                    offs++
                    night += p
                }
            }
        }
        return Plan(night, reverts, checks.any { it.missingKeeps.isNotEmpty() }, due.size - night.size, paused)
    }

    /**
     * Trials wait for the night unless the plan is only reverts of failing
     * kept values (the smart revert should not wait a day) or a trigger has
     * waited [NIGHT_GRACE_MS].
     */
    fun nightOnly(plan: Plan, triggerSinceMs: Long?, nowMs: Long): Boolean =
        !(plan.entries.isEmpty() && plan.reverts.isNotEmpty()) &&
            !(plan.triggered && triggerSinceMs != null && nowMs - triggerSinceMs >= NIGHT_GRACE_MS)

    /** The records the read-only check writes: what it saw, and the static results. */
    fun observedRecords(check: SimCheck, basis: TestBasis, nowMs: Long): List<EntryRecord> =
        check.observed.map { (entry, works) ->
            EntryRecord(check.sim.key, entry, nowMs, basis, works, "observed", check.effective[OwnerFeature.ofWire(entry.substringBefore(':'))], observed = true)
        } + check.static.map { (entry, why) ->
            EntryRecord(check.sim.key, entry, nowMs, basis, false, why, check.effective[OwnerFeature.ofWire(entry.substringBefore(':'))], observed = true)
        } + check.apnPresent.map { (key, why) ->
            EntryRecord(check.sim.key, apnEntry(key), nowMs, basis, true, why, observed = true)
        }

    /** The records one feature test leaves: the value the phone had, the value tried, and what the same change also measured. */
    fun records(r: FeatureResult, sim: SimKey, basis: TestBasis, nowMs: Long, effective: Boolean?, existing: List<EntryRecord>): List<EntryRecord> {
        val baseline = r.baseline ?: return emptyList()
        val feature = r.feature
        fun failures(entry: String, failed: Boolean) = if (failed) (existing.lastOrNull { it.sim == sim && it.entry == entry }?.failures ?: 0) + 1 else 0
        val out = mutableListOf<EntryRecord>()
        // The value the phone has: on works when registered with its service; an off works when the service is gone.
        r.baselineWorks?.let { registered ->
            out += EntryRecord(sim, entry(feature, baseline), nowMs, basis, if (baseline) registered else !registered, r.method ?: "observed", effective, observed = r.method == null)
        }
        val other = !baseline
        val works = if (other) r.onWorks else r.offWorks
        val reason = if (other) r.onReason else r.offReason
        // A "not now" never overwrites a result of the same basis (a side result of the same run, say).
        val decided = existing.any { it.sim == sim && it.entry == entry(feature, other) && it.works != null && it.basis == basis }
        if (reason != null && !(reason in NOT_NOW && works == null && decided)) {
            val failed = r.restored == false
            out += EntryRecord(
                sim, entry(feature, other), nowMs, basis, works, reason, effective,
                failures = failures(entry(feature, other), failed), observed = reason in STATIC_SKIPS,
            )
        }
        // One change, two results: the Wi-Fi-calling-off override also shows VoLTE over LTE, and VoLTE's measurement shows Wi-Fi calling gone.
        r.sideVowifiOff?.let { out += EntryRecord(sim, entry(OwnerFeature.VOWIFI, false), nowMs, basis, it, "service_gone_during_volte_measurement") }
        r.sideVolteOn?.let { out += EntryRecord(sim, entry(OwnerFeature.VOLTE, true), nowMs, basis, it, "registered_over_lte_during_wfc_off") }
        return out
    }

    /** A test that failed in the middle: not decided, one more failure in a row. */
    fun failure(sim: SimKey, entry: String, basis: TestBasis, nowMs: Long, reason: String, existing: List<EntryRecord>): EntryRecord =
        EntryRecord(sim, entry, nowMs, basis, null, reason, failures = (existing.lastOrNull { it.sim == sim && it.entry == entry }?.failures ?: 0) + 1)

    /** One APN row's result. */
    fun apnRecord(sim: SimKey, r: ApnResult, basis: TestBasis, nowMs: Long, existing: List<EntryRecord>): EntryRecord {
        val entry = apnEntry(r.apnKey)
        val failed = r.status in OwnerRunner.STOPPING || r.status == "insert_failed"
        val decided = r.status in setOf("present", "inserted", OwnerRunner.SAME_AS_PHONE_ROW)
        return EntryRecord(
            sim, entry, nowMs, basis,
            works = if (decided) r.dataWorks ?: (r.status != "inserted") else null,
            outcome = r.status,
            failures = if (failed) (existing.lastOrNull { it.sim == sim && it.entry == entry }?.failures ?: 0) + 1 else 0,
        )
    }

    /** OCD's internet rows for the SIM that the owner build may test, one per key. */
    fun apnCandidates(rows: List<OcdApnRow>, sim: OwnerSim): List<OcdApnRow> =
        rows.filter { ApnConf.match(it, sim.identity) == ApnMatch.MATCHES && it.isInternet }
            .filter { runCatching { KeyGuard.checkApn(it) }.isSuccess }
            .distinctBy { it.key }
}

internal fun OwnerState.recorded(record: EntryRecord): OwnerState =
    copy(entries = (entries.filterNot { it.sim == record.sim && it.entry == record.entry } + record).takeLast(EntryRecord.MAX))
