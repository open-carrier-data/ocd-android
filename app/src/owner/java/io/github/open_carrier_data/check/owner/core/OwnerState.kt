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
    /** A trial of the other value of a kept key: the kept value it displaced, put back by the revert. */
    val keptBefore: Boolean? = null,
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
    /** The test row's own name and carrier ID: how a crash leftover is told from every other row. */
    val testName: String? = null,
    val carrierId: Long? = null,
    /** A hash of every visible APN row before the insert: the revert proves the other rows are as they were. */
    val tableHash: String? = null,
)

/**
 * A change the owner undid ("Undo changes"): the night does not keep it again
 * for that SIM. "Test now" clears the list, since the owner then asks for a test.
 */
@Serializable
data class UndoneEntry(val sim: SimKey, val entry: String, val atMs: Long)

/** The last finished check or test, one line, for the Settings entry and the owner screen. */
@Serializable
data class LastResult(val atMs: Long, val text: String, val changed: Boolean = false, val failed: Boolean = false)

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
    /** How VoLTE was measured while IMS ran over Wi-Fi ("wfc_override"); null: seen as it was. */
    val method: String? = null,
    /** A fresh IMS REGISTER over LTE seen after the change (the stronger result); null: not watchable or not measured. */
    val freshRegister: Boolean? = null,
    /** After the revert the baseline came back within the window; false is a failure of the entry. */
    val restored: Boolean? = null,
    /** What the same change also showed: Wi-Fi calling's service gone (VoLTE's measurement), VoLTE over LTE (Wi-Fi calling off). */
    val sideVowifiOff: Boolean? = null,
    val sideVolteOn: Boolean? = null,
    /** The call network type ([io.github.open_carrier_data.check.core.Radio.wire]) of the owner's call that went active with the feature off. */
    val offCallNetwork: String? = null,
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

/**
 * What the test report needs to name the run's cell (round 19: device ×
 * carrier profile × stack), recorded when the run starts. Typed values only:
 * the SPN as the SIM shows it, and the GID1 prefix of the OCD profile it
 * matched, never the SIM's whole GID1. Null in runs before 2026-10-20; the
 * report then reads the same phone as it is now.
 */
@Serializable
data class ReportCell(
    val model: String,
    val codename: String,
    val androidMajor: Int,
    /** [io.github.open_carrier_data.check.core.RomFamily.wire]. */
    val romFamily: String,
    /** Build.TYPE: user, userdebug or eng. */
    val buildType: String,
    /** [ImsStack]: phh_ims, samsung_ims, aosp_imsstack, other or not_known. */
    val imsStack: String,
    val spn: String? = null,
    val gid1Prefix: String? = null,
    val appVersion: String,
    /** The data release the OCD values came from, in runs before round 29; null for a bundled snapshot that names none (the bot finds it by its digest). */
    val dataRelease: String? = null,
    /** The data's data digest (sha256:...), which the public bot checks against the commit or the release tag. */
    val dataDigest: String,
    /** pulled or bundled. */
    val dataSource: String,
    /**
     * The OCD commit the data was read at (round 29 S13): the pinned main
     * commit, or the commit the bundled snapshot was built from. The pointer
     * report names it with [dataDigest] instead of a release tag. Null in runs
     * before round 29, whose reports still name the release.
     */
    val dataCommit: String? = null,
    /**
     * The ROM build the run ran on, as the report names it (OCD round 29: levels are per build):
     * Build.ID and the build's UTC time in seconds, for example "CP2A.260605.016 1792814506".
     * Never the fingerprint, the incremental or the builder's user or host. Null in runs before.
     */
    val romBuild: String? = null,
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
    /** The data the run used, for the log: "main <commit> (<date>)", "bundled <commit>", or a release tag in runs before round 29. */
    val releaseTag: String? = null,
    val device: String? = null,
    val stack: String? = null,
    val features: List<FeatureResult> = emptyList(),
    val apns: List<ApnResult> = emptyList(),
    val refused: String? = null,
    val note: String? = null,
    /** A background run in the silent window, and why its entries were due. */
    val background: Boolean = false,
    val plan: String? = null,
    /** What the run changed on the phone and what failed, told at its end ([Notices]). */
    val changes: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    /** The phone, software and SIM facts the test report names. */
    val cell: ReportCell? = null,
)

@Serializable
data class OwnerState(
    val overrides: List<LiveOverride> = emptyList(),
    val apns: List<LiveApn> = emptyList(),
    /** Set by a stop event (a failed or unprovable revert, lost voice, a value after reboot); checked before every write. */
    val stop: String? = null,
    val emergencySeen: EmergencySeen? = null,
    /**
     * The pinned main commit the OCD data comes from (round 29 F1); null
     * until the first pull, when the bundled snapshot is used. Builds before
     * round 29 kept a tagged release here as `release`, which is no longer
     * read: its folder is removed at the first pin.
     */
    val pin: DataPin? = null,
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
     * Builds before 2026-10-20 switched Wi-Fi off for VoLTE's measurement and
     * recorded it here. This build never switches Wi-Fi; the cleanup drops an
     * old record.
     */
    val wifiOffSinceMs: Long? = null,
    /** Each entry's last result per SIM, for the triggers and the rotation ([QuietPlan]). */
    val entries: List<EntryRecord> = emptyList(),
    /** The first sighting of the running system_server ([ServerClock]). */
    val serverSeen: ServerSighting? = null,
    /** The last finished check or test, for the Settings entry. */
    val lastResult: LastResult? = null,
    /** The last read-only check of the background job. */
    val lastCheckAtMs: Long? = null,
    /** The last pull attempt of the background job (once a day, on Wi-Fi). */
    val lastPullAtMs: Long? = null,
    /** Since when a triggered entry waits for a night window; after [QuietPlan.NIGHT_GRACE_MS] any silent window does. */
    val triggerSinceMs: Long? = null,
    /** The basis the background job last saw; a new ROM build or new data values for a SIM start [onOnlyUntilMs]. */
    val basisSeen: TestBasis? = null,
    /** After a ROM flash or new data values for a SIM, only on values are tried until then ([QuietPlan.ON_ONLY_MS]). */
    val onOnlyUntilMs: Long? = null,
    /** The night (its local date) whose one change was made already. */
    val trialNight: String? = null,
    /** What the owner undid; the night skips these entries for their SIM ([QuietPlan.plan]). */
    val undone: List<UndoneEntry> = emptyList(),
    /** The private pointer ledger: how often each entry passed or failed here, by what pointed at it ([PointerLedger]). */
    val ledger: List<LedgerRow> = emptyList(),
    /**
     * Others' reports from the last observed/summary.json that passed its
     * checks ([ObservedReports]): they only move this phone's entries earlier
     * in the night's rotation. A failed fetch keeps it.
     */
    val observed: ObservedInfo? = null,
    /** The hashes of the reports the owner opened ("Open report on GitHub"): his own reports are not others'. */
    val reportsOpened: List<String> = emptyList(),
    /** Round 26: every measured call, the app's own mailbox calls and the owner's real calls, never a number ([CallRecord]). */
    val calls: List<CallRecord> = emptyList(),
    /** Changed voice values kept for now: past the night only after calls out, then until incoming is seen ([Provisional]). */
    val provisional: List<ProvisionalKeep> = emptyList(),
    /** "Re-tested by OCD" from this phone's own measured calls, 90 days each ([Retested]). */
    val retested: List<RetestedEntry> = emptyList(),
    /** Whether the app dials its own mailbox calls or only logs what it would dial ([CallsOutMode]). */
    val callsOutMode: CallsOutMode = CallsOutMode.DRY_RUN,
    /** The mailbox calls of the recent nights: at most one per feature and bearer a night. */
    val callsOutNights: List<CallsOutNight> = emptyList(),
    /** The MMS access point checks per SIM ([MmsProbe]); never an MMS. */
    val mms: List<MmsCheck> = emptyList(),
    /** The optional data SMS to the phone's own number (off unless the owner turns it on; it may cost an SMS). */
    val selfSmsOn: Boolean = false,
    val selfSms: List<SelfSmsCheck> = emptyList(),
    /**
     * OCD round 29: the settled results the app sent itself as report issues, with the owner's
     * token ([AutoReport]); a result is sent again only after [AutoReport.RENEW_MS].
     */
    val autoSent: List<SentResult> = emptyList(),
    /** The last automatic report's outcome for the screen: sent, refused, or why nothing was sent. */
    val autoLine: String? = null,
    /** GitHub refused the token: nothing is sent until the owner enters a token again. */
    val autoRefusedAtMs: Long? = null,
    /**
     * OCD round 30: what CarrierSettings's dataset installer last reported (`status`); null before
     * the first look. [DatasetStatus.available] false on a ROM without it.
     */
    val datasetStatus: DatasetStatus? = null,
    /** The whole dataset this app pulled last, checked and in its folder ([DatasetCheck.pull]). */
    val pulled: PulledDataset? = null,
    /** The last install job this app staged, as `install_status` reported it. */
    val install: InstallView? = null,
    /** The dataset step's last line, for the screen. */
    val datasetLine: String? = null,
    /** The last pull over a metered network: at most one a day there (round 30). */
    val lastMeteredPullAtMs: Long? = null,
    /** Open test campaigns, one per SIM at most ([Campaign]): carried across idle moments when a cap stops one. */
    val campaigns: List<Campaign> = emptyList(),
    val campaignEnds: List<CampaignEnd> = emptyList(),
    /** What the caps count: IMS re-registrations caused by OCD and dialled test calls ([TestCaps]). */
    val caps: List<CapEvent> = emptyList(),
    /** A round 30 override trial in place, registered before it is set; the cleanup clears it after a crash. */
    val trial30: Trial30? = null,
    /** Measured records put into CarrierSettings and waiting to go to main with the owner's token (JSON). */
    val pendingRecords: List<String> = emptyList(),
    val recordsSent: List<SentRecord> = emptyList(),
    /** Source reviews to open (no value passed, an APN shrink, a coupling mismatch), and the ones opened. */
    val pendingReviews: List<SourceReview> = emptyList(),
    val reviewsSent: List<String> = emptyList(),
    /** The emergency data test's sessions, its first use, and its own stop event. */
    val emergencySessions: List<EmergencySession> = emptyList(),
    val emergencyFirstUse: EmergencyFirstUse? = null,
    val emergencyStop: String? = null,
    /** The last time the phone saw a call ringing or up (any call), for the idle moment's 10 minutes. */
    val lastCallSeenAtMs: Long? = null,
    /** Round 30 keeps no override: the keeps of earlier builds were dropped once. */
    val keepsDropped: Boolean = false,
    /** Round 31 (SM9): the person's opt-in for the active tests in a ROM build; off by default, unused in the owner build. */
    val activeTests: ActiveTestsOptIn = ActiveTestsOptIn(),
    /** Round 31 (R31.5): the last tester declaration sent to CarrierSettings, so the same one goes at most once a day. */
    val testerDeclared: TesterDeclaration? = null,
    /** Round 32 (D8 and D7 rule 4, rounds 33 to 36): the APN candidate trials after measured failures, each once per commit and SIM. */
    val apnTrials: List<ApnTrial> = emptyList(),
    /** The ROM build (its incremental) whose CarrierSettings answered `unknown_method` for the candidate set: no candidate is computed there. */
    val apnCandidateMissingOn: String? = null,
    /** Round 42 (item 2): when the mobile-data probe ladder last ran for a SIM, so an automatic run keeps to once per SIM per 6 hours. */
    val probeRuns: List<ProbeRunMark> = emptyList(),
    /** Round 42 (item 2, W1): when the ePDG name lookup last ran for a SIM and each resolver's class, never an address. */
    val epdgRuns: List<EpdgRunMark> = emptyList(),
    /** Round 42 (items 4/5): the last account-state read per SIM (a balance USSD reply or a carrier-SMS class), fed into the verdict while fresh. */
    val accountReads: List<AccountReadMark> = emptyList(),
    /** Round 42 (item 4): the owner's opt-in for the balance USSD read; off by default, and on rom also behind the active-tests opt-in (SM9). */
    val balanceUssdOn: Boolean = false,
    /** Round 42 (item 4): when a balance USSD was last sent, for the small daily cap. */
    val balanceAskedMs: List<Long> = emptyList(),
    /** Round 42 (item 4, follow-up fix): a send that read nothing (a menu, a failure, no reply) halts that SIM's sends until the next day. */
    val balanceHalts: List<BalanceHalt> = emptyList(),
    /** Round 42 (item 5): the person's opt-in for reading the carrier's own plan SMS; off by default, and on rom behind SM9's opt-in. */
    val carrierSmsOn: Boolean = false,
) {
    val anyLive: Boolean get() = overrides.isNotEmpty() || apns.isNotEmpty() || wifiOffSinceMs != null

    /** Changes Open Carrier Data holds now: live overrides and APN rows, or a round 30 trial. */
    val anyKept: Boolean get() = anyLive || trial30 != null

    /** Every switch value Open Carrier Data holds on a SIM as a kept override (none in round 30, but those of earlier builds until they are dropped). */
    fun heldSwitches(): List<HeldSwitch> = overrides.filter { it.hold == Hold.KEPT }.map { HeldSwitch(it.slot, it.sim, it.key, it.value) }

    fun kept(slot: Int): Map<String, Boolean> =
        overrides.filter { it.slot == slot && it.hold == Hold.KEPT }.associate { it.key to it.value }

    /** The keeps the slot's trials displaced, back as keeps: what a revert re-applies along with the other keeps. */
    fun keepsRestored(slot: Int): OwnerState = copy(
        overrides = overrides.map {
            if (it.slot == slot && it.hold == Hold.TRIAL && it.keptBefore != null) it.copy(value = it.keptBefore, hold = Hold.KEPT, keptBefore = null) else it
        },
    )

    fun logged(atMs: Long, kind: String, text: String): OwnerState =
        copy(log = (log + LogEvent(atMs, kind, text)).takeLast(MAX_LOG))

    fun withRun(run: RunResult): OwnerState = copy(runs = (runs.filterNot { it.id == run.id } + run).takeLast(MAX_RUNS))

    fun encode(): String = wire.encodeToString(serializer(), this)

    companion object {
        const val MAX_LOG = 400
        const val MAX_RUNS = 30
        const val MAX_OPENED = 100
        private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): OwnerState = wire.decodeFromString(serializer(), text)
    }
}

/** A switch value Open Carrier Data holds as a kept override. */
data class HeldSwitch(val slot: Int, val sim: SimKey, val key: String, val value: Boolean)

/** A round 30 override trial in place on one slot: the keys set, since when (registered before the set). */
@Serializable
data class Trial30(val slot: Int, val subId: Int, val mccmnc: String, val keys: List<String>, val sinceMs: Long, val label: String = "")

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
