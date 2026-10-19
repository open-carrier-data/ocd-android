package io.github.open_carrier_data.check.owner

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.Refusals
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Every owner job runs as WorkManager work, one short step at a time (a
 * worker has about 10 minutes): a test run is a chain of one step per SIM
 * and feature, then one per SIM for the APN rows. Each step leaves no trial
 * behind; a step that dies in the middle leaves a recorded trial that the
 * next cleanup reverts.
 */
class OwnerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // One owner job at a time in the process: a cleanup never runs into the middle of a test.
    override suspend fun doWork(): Result = LOCK.withLock { work() }

    private suspend fun work(): Result {
        val step = inputData.getString(KEY_STEP) ?: return Result.failure()
        val store = FileStateStore(applicationContext)
        val result = try {
            when (step) {
                STEP_WEEKLY -> weekly()
                STEP_CLEANUP -> cleanup(store)
                STEP_REVERT_ALL -> withPhone(store) { runner, sims -> runner.revertAll(sims); Result.success() }
                STEP_PULL -> {
                    val line = OwnerData(applicationContext, store).pull(force = inputData.getBoolean(KEY_FORCE, false))
                    store.update { it.logged(System.currentTimeMillis(), "pull", line) }
                    Result.success()
                }
                STEP_PROBE -> withPhone(store) { runner, sims ->
                    val sim = sims.firstOrNull() ?: throw SafetyException("no_sim", "no SIM, nothing to test")
                    runner.rebootProbe(sim)
                    Result.success()
                }
                STEP_FEATURE -> feature(store)
                STEP_APN -> apn(store)
                STEP_FINISH -> {
                    val id = inputData.getString(KEY_RUN)
                    store.update { s -> s.runs.firstOrNull { it.id == id }?.let { s.withRun(it.copy(endedMs = System.currentTimeMillis())) } ?: s }
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: SafetyException) {
            store.update { it.logged(System.currentTimeMillis(), "refused", "$step: ${e.message}") }
            if (step == STEP_CLEANUP) {
                store.update { s ->
                    s.copy(
                        lastCleanup = if (s.anyLive) {
                            "leftovers recorded, cleanup waits: ${e.message}"
                        } else {
                            "nothing recorded as live; the check of the APN table for marked rows waits for access (${e.message})"
                        },
                    )
                }
            }
            // Nothing recorded as live: no retry needed (a marked APN row is found on the next start with access).
            if (step == STEP_CLEANUP && store.read().anyLive && runAttemptCount < 8) Result.retry() else Result.success()
        } catch (e: Exception) {
            store.update { it.logged(System.currentTimeMillis(), "error", "$step: ${e.javaClass.simpleName}: ${e.message?.take(160)}") }
            if (step == STEP_PULL && runAttemptCount < 3) Result.retry() else Result.success()
        }
        OwnerNotifications.refresh(applicationContext, store.read())
        return result
    }

    private suspend fun <T> withPhone(store: FileStateStore, block: suspend (OwnerRunner, List<OwnerSim>) -> T): T {
        val handle = OwnerAccess.open(applicationContext)
        try {
            return block(OwnerRunner(handle.phone, store, device(applicationContext)), OwnerData.sims(applicationContext))
        } finally {
            handle.close()
        }
    }

    /** Leftover cleanup and the per-SIM re-apply; retried while a refusal holds (in a call, just booted, Shizuku not up yet). */
    private suspend fun cleanup(store: FileStateStore): Result {
        val summary = withPhone(store) { runner, sims -> runner.reconcile(sims) }
        return if ("refused" in summary || "waits" in summary) {
            if (runAttemptCount < 8) Result.retry() else Result.success()
        } else {
            Result.success()
        }
    }

    private fun options() = RunOptions(
        testOffs = inputData.getBoolean(KEY_OFFS, true),
        voiceCheckMs = inputData.getLong(KEY_VOICE_MS, 0),
        apns = inputData.getBoolean(KEY_APNS, true),
        scheduled = inputData.getBoolean(KEY_SCHEDULED, false),
    )

    private suspend fun feature(store: FileStateStore): Result {
        val feature = OwnerFeature.ofWire(inputData.getString(KEY_FEATURE).orEmpty()) ?: return Result.failure()
        val slot = inputData.getInt(KEY_SLOT, -1)
        val runId = inputData.getString(KEY_RUN) ?: return Result.failure()
        val data = OwnerData(applicationContext, store)
        val bundle = data.bundle()
        withPhone(store) { runner, sims ->
            val sim = sims.firstOrNull { it.slot == slot }
            val run = startRun(store, runId, sim, bundle)
            if (sim == null) {
                store.update { it.withRun(run.copy(refused = "no_sim", note = "no SIM, nothing to test")) }
                return@withPhone
            }
            val ocd = OwnerRunner.answers(bundle, sim, device(applicationContext)).getValue(feature)
            val result = runner.testFeature(sim, feature, ocd, options())
            store.update { s ->
                val current = s.runs.firstOrNull { it.id == runId } ?: run
                s.withRun(current.copy(features = current.features.filterNot { it.feature == feature } + result))
            }
        }
        return Result.success()
    }

    private suspend fun apn(store: FileStateStore): Result {
        val slot = inputData.getInt(KEY_SLOT, -1)
        val runId = inputData.getString(KEY_RUN) ?: return Result.failure()
        val data = OwnerData(applicationContext, store)
        withPhone(store) { runner, sims ->
            val sim = sims.firstOrNull { it.slot == slot } ?: return@withPhone
            val run = startRun(store, runId, sim, data.bundle())
            val rows = data.apnRows(sim)
            val results = if (rows.isEmpty() && data.currentDir() == null) {
                listOf(ApnResult("all rows", "", "no_release_pulled"))
            } else {
                runner.testApns(sim, rows, options())
            }
            store.update { s ->
                val current = s.runs.firstOrNull { it.id == runId } ?: run
                s.withRun(current.copy(apns = results))
            }
        }
        return Result.success()
    }

    private fun startRun(store: FileStateStore, runId: String, sim: OwnerSim?, bundle: OcdBundle): RunResult {
        val state = store.read()
        state.runs.firstOrNull { it.id == runId }?.let { return it }
        val device = device(applicationContext)
        val profile = sim?.let { Matcher.resolve(bundle, it.identity).mostSpecific }
        val run = RunResult(
            id = runId,
            startedMs = System.currentTimeMillis(),
            scheduled = inputData.getBoolean(KEY_SCHEDULED, false),
            sim = sim?.key,
            simName = sim?.name,
            profileId = profile?.profileId,
            releaseTag = state.release?.tag ?: "bundled ${bundle.source.commit.take(8)}",
            device = "${device.model} (${device.codename})",
            stack = "${device.romFamily.plain}, Android ${device.androidMajor}, ${Build.TYPE}",
        )
        store.update { it.withRun(run) }
        return run
    }

    /** The weekly job: pull an update, then queue the test chain under the same conditions. */
    private suspend fun weekly(): Result {
        val store = FileStateStore(applicationContext)
        if (!store.read().scheduleOn) return Result.success()
        runCatching { OwnerData(applicationContext, store).pull() }
            .onSuccess { line -> store.update { it.logged(System.currentTimeMillis(), "pull", "weekly: $line") } }
            .onFailure { e -> store.update { it.logged(System.currentTimeMillis(), "error", "weekly pull: ${e.message?.take(160)}") } }
        OwnerScheduler.runTests(applicationContext, scheduled = true, offs = true, voiceCheckMs = 0, apns = true)
        return Result.success()
    }

    companion object {
        const val KEY_STEP = "step"
        const val KEY_RUN = "run"
        const val KEY_SLOT = "slot"
        const val KEY_FEATURE = "feature"
        const val KEY_OFFS = "offs"
        const val KEY_VOICE_MS = "voice_ms"
        const val KEY_APNS = "apns"
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_FORCE = "force"

        const val STEP_WEEKLY = "weekly"
        const val STEP_CLEANUP = "cleanup"
        const val STEP_REVERT_ALL = "revert_all"
        const val STEP_PULL = "pull"
        const val STEP_FEATURE = "feature"
        const val STEP_APN = "apn"
        const val STEP_FINISH = "finish"
        const val STEP_PROBE = "probe"

        private val LOCK = Mutex()

        fun device(context: Context) = DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            codename = Build.DEVICE.orEmpty(),
            androidMajor = Build.VERSION.RELEASE.orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: 0,
            romFamily = RomFamily.detect(
                Build.DISPLAY, Build.FINGERPRINT, Build.PRODUCT, Build.TYPE, Build.TAGS,
                lineageFeature = context.packageManager.hasSystemFeature("org.lineageos.android"),
            ),
        )
    }
}

/** Queues the owner's jobs. Unique names keep one of each at a time. */
object OwnerScheduler {
    const val WEEKLY = "owner-weekly"
    const val RUN = "owner-run"
    const val CLEANUP = "owner-cleanup"
    const val REVERT_ALL = "owner-revert-all"
    const val PULL = "owner-pull"

    /** Weekly, only while idle, charging and on Wi-Fi (unmetered). The worker itself refuses in a call or while roaming. */
    fun scheduledConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .build()

    fun ensureWeekly(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(WEEKLY)
            return
        }
        val request = PeriodicWorkRequestBuilder<OwnerWorker>(7, TimeUnit.DAYS)
            .setConstraints(scheduledConstraints())
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_WEEKLY))
            .addTag(WEEKLY)
            .build()
        wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun step(data: Data, constraints: Constraints?, expedite: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<OwnerWorker>().setInputData(data).apply {
            if (constraints != null) setConstraints(constraints)
            if (expedite) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }.build()

    /** Leftover cleanup and per-SIM re-apply; after a boot it waits out the 10 minutes in which a change is refused anyway. */
    fun cleanup(context: Context, afterBoot: Boolean = false, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val delay = if (afterBoot) (Refusals.EMERGENCY_WINDOW_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 30_000 else 0
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_CLEANUP))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CLEANUP, policy, request)
    }

    fun revertAll(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            REVERT_ALL, ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_REVERT_ALL), null, expedite = true),
        )
    }

    /** Round 25 first-use check 3, step one; the owner reboots afterwards. */
    fun rebootProbe(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-probe", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_PROBE), null, expedite = true),
        )
    }

    fun pull(context: Context, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_PULL, OwnerWorker.KEY_FORCE to force))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PULL, ExistingWorkPolicy.KEEP, request)
    }

    /** A test run: one step per SIM and feature, then the APN rows, then the end mark. */
    suspend fun runTests(context: Context, scheduled: Boolean, offs: Boolean, voiceCheckMs: Long, apns: Boolean): String {
        val sims = OwnerData.sims(context)
        val runId = UUID.randomUUID().toString().take(8)
        val constraints = if (scheduled) scheduledConstraints() else null
        val common = arrayOf(
            OwnerWorker.KEY_RUN to runId, OwnerWorker.KEY_OFFS to offs, OwnerWorker.KEY_VOICE_MS to voiceCheckMs,
            OwnerWorker.KEY_APNS to apns, OwnerWorker.KEY_SCHEDULED to scheduled,
        )
        val steps = mutableListOf<OneTimeWorkRequest>()
        for (sim in sims) {
            for (feature in OwnerFeature.entries) {
                steps += step(
                    workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FEATURE, OwnerWorker.KEY_SLOT to sim.slot, OwnerWorker.KEY_FEATURE to feature.wire),
                    constraints, expedite = false,
                )
            }
            if (apns) steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_APN, OwnerWorker.KEY_SLOT to sim.slot), constraints, false)
        }
        val store = FileStateStore(context)
        if (steps.isEmpty()) {
            store.update {
                it.withRun(RunResult(id = runId, startedMs = System.currentTimeMillis(), scheduled = scheduled, endedMs = System.currentTimeMillis(), refused = "no_sim", note = "no SIM, nothing to test"))
                    .logged(System.currentTimeMillis(), "run", "no SIM, nothing to test")
            }
            return "no SIM, nothing to test"
        }
        steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FINISH), null, false)
        var chain = WorkManager.getInstance(context).beginUniqueWork(RUN, ExistingWorkPolicy.KEEP, steps.first())
        for (next in steps.drop(1)) chain = chain.then(next)
        chain.enqueue()
        store.update { it.logged(System.currentTimeMillis(), "run", "queued run $runId: ${sims.size} SIM(s), ${steps.size - 1} steps${if (scheduled) ", scheduled" else ""}") }
        return "queued run $runId"
    }
}
