package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The owner build's own record of what it changed on the phone. Every change
 * is written here before its command runs (lab rule 3, "registered first"),
 * so a crash, a kill or a reboot in the middle leaves a record that the next
 * start finds and reverts. The file holds no IMSI, ICCID, IMEI, number or
 * address: a SIM is known by its subscription ID, network code and carrier ID.
 */

/** A trial lasts one test; a keep stays until the owner reverts it, a SIM swap, or a reboot (then it is re-applied). */
@Serializable
enum class Hold { TRIAL, KEPT }

/** Which SIM a value belongs to: Android's subscription ID (new for every new SIM card), network code and carrier ID. */
@Serializable
data class SimKey(val subId: Int, val mccmnc: String, val carrierId: Long?) {
    val label: String get() = "$mccmnc (subscription $subId)"
}

@Serializable
data class LiveOverride(
    val slot: Int,
    val sim: SimKey,
    val key: String,
    val value: Boolean,
    val hold: Hold,
    val sinceMs: Long,
    /** The effective value before the owner build touched the key. */
    val before: String? = null,
    /** Why it is kept: the test that picked it. */
    val why: String? = null,
)

@Serializable
data class LiveApn(
    val sim: SimKey,
    /** The provider row ID once known; null between the record and the insert. */
    val rowId: Long?,
    val label: String,
    val apnKey: String,
    val hold: Hold,
    val sinceMs: Long,
    /** The preferred APN before the test, to restore; null when none was set. */
    val preferredBefore: Long?,
    val preferredReadable: Boolean,
    val madePreferred: Boolean,
    /** The highest row ID before the insert: a row at or below it is the phone's own and is never deleted as ours. */
    val maxIdBefore: Long? = null,
)

/** One event of the local log (LB9 standard: redacted typed fields only). */
@Serializable
data class LogEvent(val atMs: Long, val kind: String, val text: String)

/** One feature's test result for one SIM. */
@Serializable
data class FeatureResult(
    val feature: OwnerFeature,
    val baseline: Boolean?,
    val baselineWorks: Boolean?,
    val ocd: OcdAnswer,
    /** null: not tested; true: reached its outcome (registered for on, service gone for off). */
    val onWorks: Boolean? = null,
    val onReason: String? = null,
    val offWorks: Boolean? = null,
    val offReason: String? = null,
    /** Voice measured with the feature off: a call went active while the off was live. */
    val offVoiceMeasured: Boolean? = null,
    val best: Boolean? = null,
    val bestWhy: String? = null,
    val applied: Boolean = false,
    val refused: String? = null,
)

@Serializable
data class ApnResult(
    val label: String,
    val apnKey: String,
    /** present: the phone already has the row; inserted: the test inserted it; refused or not tested. */
    val status: String,
    val dataWorks: Boolean? = null,
    val kept: Boolean = false,
)

@Serializable
data class RunResult(
    val id: String,
    val startedMs: Long,
    val endedMs: Long? = null,
    val scheduled: Boolean,
    val sim: SimKey? = null,
    val simName: String? = null,
    val profileId: String? = null,
    val releaseTag: String? = null,
    val device: String? = null,
    val stack: String? = null,
    val features: List<FeatureResult> = emptyList(),
    val apns: List<ApnResult> = emptyList(),
    val refused: String? = null,
    val note: String? = null,
)

@Serializable
data class OwnerState(
    val overrides: List<LiveOverride> = emptyList(),
    val apns: List<LiveApn> = emptyList(),
    /** Set by a stop event (a failed or unprovable revert, lost voice, a value after reboot); checked before every write. */
    val stop: String? = null,
    val emergencySeen: EmergencySeen? = null,
    val release: ReleaseInfo? = null,
    val runs: List<RunResult> = emptyList(),
    val log: List<LogEvent> = emptyList(),
    val scheduleOn: Boolean = true,
    val lastCleanup: String? = null,
    /**
     * Round 25 first-use check 3: one override was left live through a reboot
     * and was gone afterwards. Until then nothing is kept, no off is tested,
     * and no scheduled run changes anything.
     */
    val rebootCleared: Boolean = false,
    /** When the reboot check's override was applied; null when none is pending. */
    val rebootProbeAtMs: Long? = null,
    /**
     * Set before the owner build switches Wi-Fi off for VoLTE's measurement,
     * cleared once Wi-Fi is back on: a crash in between leaves the record, and
     * the cleanup switches Wi-Fi on again.
     */
    val wifiOffSinceMs: Long? = null,
) {
    val anyLive: Boolean get() = overrides.isNotEmpty() || apns.isNotEmpty() || wifiOffSinceMs != null

    fun kept(slot: Int): Map<String, Boolean> =
        overrides.filter { it.slot == slot && it.hold == Hold.KEPT }.associate { it.key to it.value }

    fun logged(atMs: Long, kind: String, text: String): OwnerState =
        copy(log = (log + LogEvent(atMs, kind, text)).takeLast(MAX_LOG))

    fun withRun(run: RunResult): OwnerState = copy(runs = (runs.filterNot { it.id == run.id } + run).takeLast(MAX_RUNS))

    fun encode(): String = wire.encodeToString(serializer(), this)

    companion object {
        const val MAX_LOG = 400
        const val MAX_RUNS = 30
        private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): OwnerState = wire.decodeFromString(serializer(), text)
    }
}

/** Where the state lives. The app's version writes atomically to its private files folder. */
interface StateStore {
    fun read(): OwnerState
    fun write(state: OwnerState)
}

class MemoryStore(var state: OwnerState = OwnerState()) : StateStore {
    override fun read(): OwnerState = state
    override fun write(state: OwnerState) {
        this.state = state
    }
}

inline fun StateStore.update(change: (OwnerState) -> OwnerState): OwnerState = change(read()).also(::write)
