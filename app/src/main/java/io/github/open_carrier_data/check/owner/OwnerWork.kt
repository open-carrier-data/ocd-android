package io.github.open_carrier_data.check.owner

import android.content.Context
import android.os.Build
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.EpdgW1
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.ModemValue
import io.github.open_carrier_data.check.core.RegistryDataLog
import io.github.open_carrier_data.check.core.RetryWait
import io.github.open_carrier_data.check.core.RetryWaits
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SetupAttempts
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.ApnRows
import io.github.open_carrier_data.check.owner.core.DataBasis
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import io.github.open_carrier_data.check.owner.core.DiagnosisMark
import io.github.open_carrier_data.check.owner.core.EpdgRunMark
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.PlanWhy
import io.github.open_carrier_data.check.owner.core.SchemaRefused
import io.github.open_carrier_data.check.owner.core.CallsOutDue
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.MailboxCaller
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.provisionalSettled
import io.github.open_carrier_data.check.owner.core.LastResult
import io.github.open_carrier_data.check.owner.core.Notices
import io.github.open_carrier_data.check.owner.core.ObservedReports
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.DataException
import io.github.open_carrier_data.check.owner.core.HeldSet
import io.github.open_carrier_data.check.owner.core.MainCheck
import io.github.open_carrier_data.check.owner.core.MainNet
import io.github.open_carrier_data.check.owner.core.ManifestHint
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Plan
import io.github.open_carrier_data.check.owner.core.QuietBlock
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.ProbeRunMark
import io.github.open_carrier_data.check.owner.core.QuietWindow
import io.github.open_carrier_data.check.owner.core.RunInterrupted
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.RunSummary
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import io.github.open_carrier_data.check.owner.core.WeekClock
import io.github.open_carrier_data.check.owner.core.WeekReports
import io.github.open_carrier_data.check.owner.core.recorded
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Every owner job runs as WorkManager work, one short step at a time (a
 * worker has about 10 minutes): a test run is a chain of steps, each leaving
 * no trial behind; a step that dies in the middle leaves a recorded trial
 * that the next cleanup (or the revert alarm) reverts.
 *
 * The background (2026-10-20, automatic and silent): the hourly quiet job
 * looks whether the phone lies unused, does the light read-only check every
 * few hours (and each hour of the night), and queues the night's one feature
 * when the silent window is open. Each step of that chain checks the window
 * again, arms the revert alarm, and stops at once on the screen, a call, the
 * audio mode or the charger. Routine work posts no notification.
 */
class OwnerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // One owner job at a time in the process: a cleanup never runs into the middle of a test.
    override suspend fun doWork(): Result = LOCK.withLock { work() }

    private val background: Boolean get() = inputData.getBoolean(KEY_BACKGROUND, false)

    private suspend fun work(): Result {
        val step = inputData.getString(KEY_STEP) ?: return Result.failure()
        val store = FileStateStore(applicationContext)
        val before = store.read()
        val result = try {
            when (step) {
                // A registration of a build before 2026-10-20: replaced by the quiet job.
                STEP_WEEKLY -> {
                    OwnerScheduler.ensureQuiet(applicationContext, before.scheduleOn)
                    Result.success()
                }
                STEP_QUIET -> quiet(store)
                STEP_MAIN_CHECK -> mainCheck(store)
                STEP_CLEANUP -> cleanup(store)
                // Round 30: stage, activate (in the idle moment, or at once for "Test now"), test, record; also the voice-loss trigger.
                STEP_DATASET, STEP_VOICE_CHECK -> withPhone(store) { _, sims, phone ->
                    val testNow = inputData.getBoolean(KEY_TEST_NOW, false)
                    // "Test now" in the device log too, so a script over adb can follow it (no identifier in these lines).
                    if (testNow) runCatching { android.util.Log.i(OwnerActivity.TEST_NOW_TAG, "dataset step started, ${android.os.SystemClock.elapsedRealtime() / 1000} s after boot") }
                    val line = OwnerDatasetStep.run(applicationContext, store, phone, sims, testNow)
                    if (testNow) runCatching { android.util.Log.i(OwnerActivity.TEST_NOW_TAG, "dataset step ended: ${line.take(900)}") }
                    if (!line.startsWith("no dataset installer")) quietLog(store, "dataset", (if (testNow) "test now: " else "") + line)
                    Result.success()
                }
                // The emergency data test by hand (its first use, with adb logcat streamed): one session on the SIM in the slot.
                STEP_EMERGENCY_DATA -> withPhone(store) { _, sims, phone ->
                    val sim = sims.firstOrNull { it.slot == inputData.getInt(KEY_SLOT, 0) } ?: throw SafetyException("no_sim", "no SIM in that slot")
                    val line = OwnerEmergencyData.session(applicationContext, store, phone, OwnerDatasetStep.client(applicationContext), sim)
                    store.update { it.logged(System.currentTimeMillis(), "emergency_data", line ?: "nothing to do") }
                    Result.success()
                }
                // Round 31: the default emergency APN cycle's first use by hand (adb logcat streamed): install and restore, no EIMS request.
                STEP_EMERGENCY_DEFAULT_FIRST_USE -> withPhone(store) { _, sims, phone ->
                    val sim = sims.firstOrNull { it.slot == inputData.getInt(KEY_SLOT, 0) } ?: throw SafetyException("no_sim", "no SIM in that slot")
                    val line = OwnerEmergencyData.defaultFirstUse(applicationContext, store, phone, OwnerDatasetStep.client(applicationContext), sim)
                    store.update { it.logged(System.currentTimeMillis(), "emergency_data", line) }
                    Result.success()
                }
                STEP_PULL -> (if (inputData.getBoolean(KEY_IN_RUN, false)) {
                    // "Test now" pulls first (round 29): the test runs on whatever data the pull leaves, and a failed pull never holds it up.
                    val line = try {
                        OwnerData(applicationContext, store).pull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "pull failed, the test uses the data it has: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
                    }
                    store.update { it.logged(System.currentTimeMillis(), "pull", "before the test: $line") }
                    Result.success()
                } else {
                    val line = OwnerData(applicationContext, store).pull(force = inputData.getBoolean(KEY_FORCE, false))
                    store.update { it.logged(System.currentTimeMillis(), "pull", line) }
                    Result.success()
                }).also {
                    // Round 30: a new dataset is handed over soon and activated in the next idle moment ("Test now" chains its own step).
                    if (!inputData.getBoolean(KEY_IN_RUN, false) && store.read().datasetStatus?.available == true) OwnerScheduler.dataset(applicationContext, delayMs = TimeUnit.MINUTES.toMillis(5))
                }
                STEP_PROBE -> {
                    requireTrials(store)
                    withPhone(store) { runner, sims, _ ->
                        val sim = sims.firstOrNull() ?: throw SafetyException("no_sim", "no SIM, nothing to test")
                        runner.rebootProbe(sim)
                        Result.success()
                    }
                }
                STEP_FEATURE -> feature(store)
                STEP_APN -> apn(store)
                STEP_CALLS_OUT -> callsOut(store)
                STEP_SELF_SMS -> selfSmsNow(store)
                STEP_BALANCE -> balanceNow(store)
                STEP_DROP_KEEP -> dropKeep(store)
                STEP_FINISH -> finish(store)
                else -> Result.failure()
            }
        } catch (e: CancellationException) {
            // JobScheduler stopped the work (the screen came on, the charger was pulled): the step reverted what it could, the cleanup takes the rest.
            withContext(NonCancellable) {
                val now = store.read()
                if (now.anyLive) OwnerScheduler.cleanup(applicationContext, policy = ExistingWorkPolicy.KEEP)
                if (!now.trialsLive()) RevertAlarm.disarm(applicationContext)
                OwnerNotifications.refresh(applicationContext)
            }
            throw e
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
            // A format this build does not know (round 29 F3) stays unknown on a retry.
            if (step == STEP_PULL && e !is SchemaRefused && runAttemptCount < 3) Result.retry() else Result.success()
        }
        val after = store.read()
        // Routine work is silent; a change or a failure is told. A chain's changes wait for its end (STEP_FINISH).
        if (step !in CHAIN_STEPS) Notices.of(before, after)?.takeIf { it.failed }?.let { OwnerNotifications.notice(applicationContext, it) }
        OwnerNotifications.refresh(applicationContext)
        return result
    }

    private suspend fun <T> withPhone(store: FileStateStore, block: suspend (OwnerRunner, List<OwnerSim>, OwnerPhone) -> T): T {
        val handle = OwnerAccess.open(applicationContext)
        // The route in the log whenever it changes (privileged system app, or the Shizuku fallback).
        val route = handle.phone.route.plain
        if (store.read().log.lastOrNull { it.kind == "route" }?.text != route) store.update { it.logged(System.currentTimeMillis(), "route", route) }
        try {
            return block(OwnerRunner(handle.phone, store, device(applicationContext)), OwnerData.sims(applicationContext), handle.phone)
        } finally {
            handle.close()
        }
    }

    /**
     * SM8 and round 47: a trial step (the restart check) starts nothing without the provider or on an IMS stack the tests are
     * not calibrated on. Reverts and cleanups never ask: they only take a change back.
     */
    private fun requireTrials(store: FileStateStore) {
        OwnerTester.trialRefusal(applicationContext, store.read())?.let { throw SafetyException(it.code, it.plain) }
    }

    /** Leftover cleanup and the per-SIM re-apply; retried while a refusal holds (in a call, a call just before a restart, Shizuku not up yet). */
    private suspend fun cleanup(store: FileStateStore): Result {
        val summary = withPhone(store) { runner, listed, phone ->
            // Something to clear needs the slot's config in place first: the bounded readiness wait, as a test does.
            val sims = if (store.read().let { it.trial30 != null || it.anyLive }) {
                val (ready, present) = OwnerReady.await(applicationContext, phone, store)
                if (!ready.ok) return@withPhone "waits: not ready after ${ready.waitedMs / 1000} s (${ready.line})"
                present.ifEmpty { listed }
            } else {
                listed
            }
            // Round 30: a trial a crash left goes first; then the keeps of earlier builds go once (values come from the dataset now).
            val leftover = OwnerDatasetStep.clearLeftoverTrial(applicationContext, store, phone, sims)
            if (!store.read().keepsDropped && store.read().stop == null) {
                val anyKept = store.read().let { s -> s.overrides.any { it.hold == io.github.open_carrier_data.check.owner.core.Hold.KEPT } || s.apns.any { it.hold == io.github.open_carrier_data.check.owner.core.Hold.KEPT } }
                val dropped = if (anyKept) runner.revertAll(sims) else "none"
                if (store.read().overrides.none { it.hold == io.github.open_carrier_data.check.owner.core.Hold.KEPT } && store.read().apns.none { it.hold == io.github.open_carrier_data.check.owner.core.Hold.KEPT }) {
                    store.update { it.copy(keepsDropped = true, undone = emptyList(), provisional = emptyList()).logged(System.currentTimeMillis(), "round30", "round 30 keeps no override; the keeps of earlier builds: $dropped") }
                }
            }
            listOfNotNull(leftover, runner.reconcile(sims)).joinToString("; ")
        }
        if (!store.read().trialsLive()) RevertAlarm.disarm(applicationContext)
        return if ("refused" in summary || "waits" in summary) {
            if (runAttemptCount < 8) Result.retry() else Result.success()
        } else {
            Result.success()
        }
    }

    /**
     * Round 47: the 15-minute job on a ROM with skip-notify ([MainCheck]). No idle, screen or night condition: a look at
     * main's manifest, the normal pull by SHA only for a difference, then the dataset step when a pulled set waits.
     * Without skip-notify it does nothing, and the quiet job keeps its daily pull and its timing.
     */
    private suspend fun mainCheck(store: FileStateStore): Result {
        val context = applicationContext
        if (OwnerScheduler.runActive(context)) return Result.success()
        // Round 48: the weekly step rides on this job on every ROM. A failure of it never holds up the data.
        try {
            OwnerWeekReport.step(context, store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            quietLog(store, WeekReports.LOG_KIND, "the weekly step failed: ${e.javaClass.simpleName}")
        }
        val status = OwnerDatasetStep.status(context, store)
        if (!status.skipsUnchanged) return Result.success()
        val now = System.currentTimeMillis()
        val net = MainNet(QuietReader.unmetered(context), QuietReader.roaming(context), QuietReader.dataSaver(context))
        val data = OwnerData(context, store)
        val state = store.read()
        fun held(s: OwnerState) = HeldSet(
            pinned = s.pin != null,
            pulled = s.pulled?.let { ManifestHint(it.datasetDigest, it.dataDigest) },
            installed = status.installed?.let { ManifestHint(it.datasetDigest, it.dataDigest) },
        )
        if (MainCheck.mayRead(state.mainCheck, net, now)) {
            if (net.unmetered != true) store.update { it.copy(mainCheck = it.mainCheck.copy(lastMeteredCheckAtMs = now)) }
            // A failed read tries again next run; a 403 or 429 has set its wait in the read.
            val read = try {
                data.mainManifest(state.mainCheck.mark?.etag)
            } catch (e: java.io.IOException) {
                null
            } catch (e: DataException) {
                null
            }
            if (read != null) {
                val pull = MainCheck.pullDue(read, state.mainCheck, held(state), net, status.pullAgain, state.lastPullAtMs, state.lastMeteredPullAtMs, now)
                if (pull) {
                    store.update { it.copy(lastPullAtMs = now, lastMeteredPullAtMs = if (net.unmetered == true) it.lastMeteredPullAtMs else now) }
                    try {
                        val line = data.pull(verbose = false)
                        if (!line.startsWith("up to date")) quietLog(store, "pull", "background: $line")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        quietLog(store, "pull", "background pull failed: ${e.message?.take(120)}")
                    }
                }
                // Only a change is written: on an unmetered network the job runs 96 times a day, and the state file is large.
                val before = store.read()
                val settled = MainCheck.settled(before.mainCheck, read, held(before), pull, now)
                if (settled != before.mainCheck) store.update { it.copy(mainCheck = settled) }
                if (MainCheck.holds(read, before.mainCheck, held(before))) data.heldMain(now)
            }
        }
        val after = store.read()
        if (MainCheck.activationWaits(after.pulled, after.install, status, after.stop != null, QuietReader.screenOn(context), net.roaming)) OwnerScheduler.dataset(context)
        return Result.success()
    }

    /**
     * The hourly quiet job: nothing while the screen is on or a call is up;
     * the light check every [CHECK_EVERY_MS] (and each hour of the night); the
     * night's one feature when the silent window is open (on the rom build only
     * after the person's active-tests opt-in). Writes the log only when
     * something is new, so a quiet phone keeps a quiet log.
     */
    private suspend fun quiet(store: FileStateStore): Result {
        val context = applicationContext
        var state = store.read()
        // The owner's tap ("check now") checks at once; only the background run keeps to "Test automatically".
        val tap = !periodic
        if ((!state.scheduleOn && !tap) || OwnerScheduler.runActive(context)) return Result.success()
        val handle = runCatching { OwnerAccess.open(context) }.getOrElse { e ->
            quietLog(store, "quiet", "no access now: ${e.message?.take(120)}")
            return Result.success()
        }
        try {
            val phone = handle.phone
            val reader = QuietReader(context)
            val look = reader.read(phone, sampleMs = 0)
            // A background run waits for the screen off and no call; the owner's tap runs at once with the screen on, never in a call.
            QuietWindow.checkBlocker(look, ownerTap = tap)?.let { block ->
                if (tap) quietLog(store, "check", "check now not run: ${block.plain}")
                return Result.success()
            }
            val checkOnly = QuietWindow.checkOnly(tap, look, state.scheduleOn)
            val now = System.currentTimeMillis()
            val nowLocal = ZonedDateTime.now()
            val night = QuietWindow.night(look.minuteOfDay)
            val last = state.lastCheckAtMs
            val checkDue = !periodic || last == null || now - last >= CHECK_EVERY_MS || now < last || night || state.triggerSinceMs != null
            if (!checkDue) return Result.success()
            // main's newest validated commit, once a day on Wi-Fi (round 29); every six hours while nothing is pinned yet.
            // Round 47: not on a ROM with skip-notify, where the 15-minute job pulls (mainCheck).
            val pullDue = state.lastPullAtMs == null || now - state.lastPullAtMs!! >= (if (state.pin == null) CHECK_EVERY_MS else PULL_EVERY_MS) || now < state.lastPullAtMs!!
            // Round 30: on metered data at most one pull a day, and only with the dataset installer; other pulls wait for Wi-Fi.
            val meteredOk = look.wifiConnected != true && state.datasetStatus?.available == true &&
                (state.lastMeteredPullAtMs == null || now - state.lastMeteredPullAtMs!! >= TimeUnit.DAYS.toMillis(1) || now < state.lastMeteredPullAtMs!!)
            if (MainCheck.quietJobPulls(state.datasetStatus) && (look.wifiConnected == true || meteredOk) && (pullDue || state.datasetStatus?.pullAgain == true)) {
                store.update { it.copy(lastPullAtMs = now, lastMeteredPullAtMs = if (meteredOk) now else it.lastMeteredPullAtMs) }
                runCatching { OwnerData(context, store).pull(verbose = false) }
                    .onSuccess { line -> if (!line.startsWith("up to date")) store.update { it.logged(now, "pull", "background: $line") } }
                    .onFailure { e -> quietLog(store, "pull", "background pull failed: ${e.message?.take(120)}") }
            }
            val sims = OwnerData.sims(context)
            if (sims.isEmpty()) return Result.success()
            state = store.read()
            val data = OwnerData(context, store)
            val bundle = data.bundle()
            val rows = sims.associate { it.key to data.apnRows(it) }
            // Round 29 F4: each SIM's entries rest on its own OCD values, not on the commit; a pin that changes none triggers nothing.
            val dataKeys = OwnerBasis.dataKeys(context, bundle, sims) { rows[it.key].orEmpty() }
            val basis = OwnerBasis.current(context, state).copy(release = DataBasis.joined(dataKeys))
            val seen = state.basisSeen
            if (seen != basis) {
                // A ROM flash or new data values for a SIM it had before: only on values for two nights (the report of 2026-10-20).
                val newValues = seen != null && DataBasis.changed(seen.release, basis.release)
                val onOnly = seen != null && (seen.rom != basis.rom || newValues)
                val what = listOfNotNull(
                    PlanWhy.NEW_DATA_VALUES.takeIf { newValues },
                    PlanWhy.NEW_ROM_BUILD.takeIf { seen != null && seen.rom != basis.rom },
                    PlanWhy.NEW_APP_BUILD.takeIf { seen != null && seen.app != basis.app },
                )
                val text = when {
                    seen == null -> "basis recorded"
                    what.isEmpty() -> "basis recorded again: a SIM came or went, or data from before round 29"
                    else -> "new ${what.joinToString()}${if (onOnly) "; only on values for two nights" else ""}"
                }
                store.update { it.copy(basisSeen = basis, onOnlyUntilMs = if (onOnly) now + QuietPlan.ON_ONLY_MS else it.onOnlyUntilMs).logged(now, "basis", text) }
            }
            val runner = OwnerRunner(phone, store, device(context))
            val video = VideoStatic.reasons(context)
            val checks = try {
                sims.map { sim -> runner.check(sim, video, rows[sim.key].orEmpty()) }
            } catch (e: SafetyException) {
                quietLog(store, "check", "light check could not read the phone: ${e.reason}")
                return Result.success()
            }
            store.update { s -> checks.flatMap { QuietPlan.observedRecords(it, basis.forSim(dataKeys, it.sim.key), now) }.fold(s) { acc, r -> acc.recorded(r) } }
            // Round 48: after a yes, what this look saw goes into the week's buffer. Results measured while roaming are dropped; unknown counts as roaming.
            if (look.roaming == false && state.week.yes) {
                store.update { s ->
                    s.copy(week = checks.fold(s.week) { w, c -> WeekReports.noted(w, WeekClock.of(now), c.sim.key, WeekReports.carrier(Matcher.resolve(bundle, c.sim.identity).mostSpecific?.profileId, c.sim.identity.mccmnc), WeekReports.passive(c)) })
                }
            }
            if (!checkOnly) {
                if (checks.any { it.missingKeeps.isNotEmpty() }) OwnerScheduler.cleanup(context, policy = ExistingWorkPolicy.KEEP)
                // Round 26: provisional keeps reverted when the night ended without calls out or 7 days passed without incoming.
                settleProvisional(context, store.read())
            }
            messageChecks(store, phone, sims, look, night)
            diagnoseCauses(store, phone, sims)
            // The owner's tap with the screen on (or with "Test automatically" off) is the check only: the dataset step, the
            // emergency data test and the night's tests stay with the background run. lastCheckAtMs stays, so that run is not put off.
            if (checkOnly) {
                quietLog(store, "check", "check now: checked at once; ${checks.joinToString { "slot ${it.sim.slot + 1}: ${it.imsLine}" }}")
                return Result.success()
            }
            // Round 30: the dataset installer. With it, the dataset serves, the idle moment decides when a newer one goes in,
            // and the tests of the changed values replace the night's trials; without it (the switch off, a round 29 ROM)
            // the app keeps to its checks, its night tests and its reports, and keeps no value.
            val round30 = try {
                val line = OwnerDatasetStep.run(context, store, phone, sims)
                if (!line.startsWith("no dataset installer")) quietLog(store, "dataset", line)
                store.read().datasetStatus?.available == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                quietLog(store, "dataset", "the dataset step failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                store.read().datasetStatus?.available == true
            }
            // An app older than the ROM's installer tests nothing at night either: the light check only.
            io.github.open_carrier_data.check.owner.core.OlderApp.refusal(store.read().datasetStatus)?.let { why ->
                store.update { it.copy(lastCheckAtMs = now, triggerSinceMs = null, lastResult = LastResult(now, "checked; nothing tested: $why")) }
                quietLog(store, "check", "light check: no tests: $why")
                return Result.success()
            }
            // Round 47: nothing else runs by itself. The dataset step above holds the passive checks and any repair; without the
            // installer there is nothing to repair with. No mailbox call, no plan, no chain.
            val line = if (round30) store.read().datasetLine ?: "checked" else "checked; ${OwnerTester.trialRefusal(context, store.read())?.plain ?: "nothing tested"}"
            store.update { it.copy(lastCheckAtMs = now, triggerSinceMs = null, lastResult = LastResult(now, line)) }
            return Result.success()
        } finally {
            handle.close()
        }
    }

    /**
     * The message check of the light check: the MMS access point probe (never
     * where MMS ended, every 14 days, no MMS sent), the only routine probe.
     */
    private suspend fun messageChecks(store: FileStateStore, phone: OwnerPhone, sims: List<OwnerSim>, look: io.github.open_carrier_data.check.owner.core.QuietInputs, night: Boolean) {
        val context = applicationContext
        val now = System.currentTimeMillis()
        store.update { it.copy(selfSms = SelfSms.settle(it.selfSms, now)) }
        for (sim in sims) {
            if (MmsProbe.due(store.read().mms, sim.key, now)) {
                val check = runCatching { OwnerMms.check(context, phone, sim) }.getOrNull() ?: continue
                store.update { it.copy(mms = MmsProbe.add(it.mms, check)) }
                quietLog(store, "mms", "slot ${sim.slot + 1}: ${MmsProbe.line(check, MmsProbe.ended(OwnerMms.ended(context), sim.identity.mccmnc), java.time.ZoneId.systemDefault())}")
            }
        }
    }

    /**
     * OCD round 42 (items 1/3/7): the passive cause read and the plain verdict. For each SIM it reads
     * the fail causes and the user-plane state ([OwnerPassiveRead]), the IMS sign-out reason
     * ([ImsReasonReader]) and whether MMS ended, assembles the verdict ([Diagnoses]) and logs it when
     * it differs from the last one. The probe ladder (item 2: D1-D5 and W1) sends its few requests
     * only within its bounds; nothing here changes a value or starts a candidate. The balance USSD is
     * the person's tap (round 47).
     */
    private suspend fun diagnoseCauses(store: FileStateStore, phone: OwnerPhone, sims: List<OwnerSim>) {
        // The fail causes it reads need Android 12. Older Android records that the verdict did not run, never silence.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            for (sim in sims) quietLog(store, "diagnosis:${sim.slot}", "slot ${sim.slot + 1}: $DIAGNOSIS_NOT_RUN_OLD_ANDROID")
            return
        }
        val context = applicationContext
        val ended = runCatching { OwnerMms.ended(context) }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        for (sim in sims) {
            val diagnosis = runCatching {
                val roamingNow = QuietReader.roaming(context)
                val reason = ImsReasonReader.read(context, sim.subId)
                val mmsEnded = MmsProbe.ended(ended, sim.identity.mccmnc) != null
                var passive = OwnerPassiveRead.read(context, sim.subId, roamingNow == true, mmsEnded = mmsEnded).copy(
                    imsRegistered = reason.registered,
                    imsReasonCode = reason.imsReasonCode,
                    sipResponseCode = reason.sipResponseCode,
                )
                // Round 43: the newest refused setups of this SIM from telephony.registry's dated local log, which the
                // passive listener cannot see (Android drops a DISCONNECTED state from the states it replays).
                passive = refusedSetups(passive, phone, sim, store.read().apns)
                // Items 2 and 4 run only after a measured cellular failure, never under a safety refusal (an
                // emergency state, an active call, roaming). The balance USSD is also home-network only and capped.
                // A measured failure is a non-zero cause, an attach reject, or the SIM's cellular network up but not
                // validated (the silent block, which leaves every cause 0); a working connection's cause 0 is none.
                val measuredFailure = passive.measuredDataFailure
                // The owner's "check now" also runs the ladder on demand, within the same refusals and a short cooldown.
                val checkNow = !periodic
                // A safety refusal that cannot be read counts as a refusal: nothing is sent then.
                val safe: suspend () -> Boolean = { runCatching { OwnerSession(phone, store, sim).safety().refusal == null }.getOrDefault(false) }
                if ((measuredFailure || checkNow) && safe()) {
                    // Item 2: the cellular probe ladder, automatic at most once per SIM per 6 hours, or on "check now" ([OwnerProbe]).
                    val run = runCatching {
                        OwnerProbe.runIfDue(
                            context, store, sim,
                            measuredFailure = measuredFailure,
                            checkNow = checkNow,
                            roaming = roamingNow,
                            dataOff = passive.dataOffReason != null,
                            nowMs = now,
                        )
                    }.getOrNull()
                    if (run != null) {
                        quietLog(store, "probe:${sim.slot}", "slot ${sim.slot + 1}: ${run.line}")
                        passive = passive.copy(userPlane = run.userPlane, privateDnsFails = run.results?.privateDnsFails())
                    }
                }
                // The latest ladder run of this SIM, under 6 hours old, feeds the verdict while the measured failure
                // lasts, so the hourly checks between two runs keep the verdict instead of flipping back and forth.
                if (passive.userPlane == null && measuredFailure) {
                    ProbeRunMark.fresh(store.read().probeRuns, sim.key, now)?.let { passive = passive.copy(userPlane = it.userPlane, privateDnsFails = it.results?.privateDnsFails()) }
                }
                // Item 2, W1: the ePDG name lookup after a measured Wi-Fi-calling failure (the Wi-Fi resolver, plus the
                // cellular one only when cellular data fails or is the default), or on the owner's "check now" (both).
                // Never roaming, with mobile data off or under a safety refusal; automatic at most once per SIM per 6 hours.
                val epdg = runCatching {
                    OwnerProbe.epdgIfDue(
                        context, store, sim,
                        checkNow = !periodic,
                        roaming = roamingNow,
                        dataOff = passive.dataOffReason != null,
                        imsRegistered = reason.registered,
                        iwlanCause = passive.iwlanDataCause,
                        cellularDataFailing = measuredFailure,
                        nowMs = now,
                        safe = safe,
                    )
                }.getOrNull()
                if (epdg != null) quietLog(store, "epdg:${sim.slot}", "slot ${sim.slot + 1}: ePDG name (W1): ${EpdgW1.record(epdg.wifi, epdg.cellular)}")
                // The latest W1 run of this SIM, while under 6 hours old, goes into the Wi-Fi-calling verdict.
                EpdgRunMark.fresh(store.read().epdgRuns, sim.key, now)?.let { passive = passive.copy(epdgWifi = it.wifi, epdgCellular = it.cellular) }
                // Items 4/5: the freshest account-state read for this SIM (balance same day, carrier-SMS class up to 31 days).
                val (account, balance) = io.github.open_carrier_data.check.owner.core.AccountReadMark
                    .fresh(store.read().accountReads, sim.key, now, zone)
                Diagnoses.of(passive.copy(account = account, balance = balance))
            }.getOrNull() ?: continue
            // Round 43: the screen's current verdict and Details for this SIM, replaced at every check.
            store.update { it.copy(diagnoses = DiagnosisMark.put(it.diagnoses, sim.slot, sim.subId, diagnosis, now)) }
            // The verdict and its phone-side factors (strict Private DNS that cannot reach its server), in one line, with
            // round 43's wait clause after the verdict. Whether the line changed ignores the clause: Android retries at
            // growing intervals, so the minutes differ at every check.
            val line = diagnosis.shown(now) ?: continue
            val tag = "diagnosis:${sim.slot}"
            val last = store.read().log.lastOrNull { it.kind == tag }?.text
            if (last != null && RetryWait.strip(last) == RetryWait.strip(line)) continue
            store.update { it.logged(System.currentTimeMillis(), tag, line) }
        }
    }

    /**
     * OCD round 43: each WWAN feature's newest refused setup for [sim] in telephony.registry's dated local log
     * ([SetupAttempts]), with a refusal cause only (a classified code or a hidden one, never AOSP's -1 to -6),
     * and which access point it was by its row ID in OCD's own record ([ApnRows]). The dump is the one the
     * owner build already reads (DUMP); it is parsed in memory and only the cause and the class are kept.
     */
    private suspend fun refusedSetups(passive: DiagnosisInputs, phone: OwnerPhone, sim: OwnerSim, apns: List<io.github.open_carrier_data.check.owner.core.LiveApn>): DiagnosisInputs {
        val lines = RegistryDataLog.parse(runCatching { phone.registryText() }.getOrNull())
        if (lines.isEmpty()) return passive
        val attempts = listOf(VerdictFeature.MOBILE_DATA, VerdictFeature.VOLTE, VerdictFeature.MMS).mapNotNull { f ->
            SetupAttempts.newest(lines, sim.slot, sim.subId, f)?.takeIf { DataFailCauseClass.refusal(it.cause) }?.let { f to it }
        }.toMap()
        if (attempts.isEmpty()) return passive
        // Item 2: the phone service's dump (the privileged OwnerCommand.PHONE read, DUMP and PACKAGE_USAGE_STATS), read
        // only when a refused setup is in the log: DataRetryManager's entries give the wait, and the refused profile's
        // data network, when Android still keeps it, the modem value for Details. Parsed in memory, never kept.
        val dump = runCatching { phone.telephonyText() }.getOrNull()
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        fun refusal(feature: VerdictFeature): SetupRefusal? = attempts[feature]?.let { a ->
            SetupRefusal(
                feature, a.cause, ApnRows.classOf(a.rowId, a.edited, apns, sim.subId),
                modemValue = ModemValue.of(dump, sim.subId, a.rowId),
                subId = sim.subId,
                waitUntilMs = RetryWaits.untilMs(dump, sim.slot, a.rowId, a.at, zone, now),
            )
        }
        return passive.copy(
            dataSetup = refusal(VerdictFeature.MOBILE_DATA),
            imsSetup = refusal(VerdictFeature.VOLTE),
            mmsSetup = refusal(VerdictFeature.MMS),
        )
    }

    /** The ladder's D2c as the verdict reads it: the strict Private DNS server did not answer; null when it was not asked. */
    private fun io.github.open_carrier_data.check.core.ProbeResults.privateDnsFails(): Boolean? = if (privateDnsOn) privateDnsOk == false else null

    /**
     * The owner's "Send a test SMS now": one data SMS to each SIM's own number, on the app's
     * port, under the owner build's refusals (no call, no emergency state, not roaming) and the
     * self-SMS refusals. The weekly switch stays as the owner set it.
     */
    /** The person's "Read the plan balance": each SIM once, under the balance read's own limits and the refusals. */
    private suspend fun balanceNow(store: FileStateStore): Result = withPhone(store) { _, sims, phone ->
        val context = applicationContext
        for (sim in sims) {
            if (OwnerSession(phone, store, sim).safety().refusal != null) continue
            runCatching { OwnerBalance.readOnTap(context, store, sim, QuietReader.roaming(context), System.currentTimeMillis()) }
        }
        Result.success()
    }

    private suspend fun selfSmsNow(store: FileStateStore): Result = withPhone(store) { _, sims, phone ->
        if (sims.isEmpty()) throw SafetyException("no_sim", "no SIM, nothing to test")
        for (sim in sims) {
            val refusal = OwnerSession(phone, store, sim).safety().refusal
            val line = if (refusal != null) {
                "slot ${sim.slot + 1}: not sent: ${refusal.plain}"
            } else {
                // The check is in the ledger before the SMS goes out (OwnerSelfSms.send).
                "slot ${sim.slot + 1}: ${OwnerSelfSms.send(applicationContext, phone, sim, on = true).result} (your request)"
            }
            store.update { it.logged(System.currentTimeMillis(), "self_sms", line) }
        }
        Result.success()
    }

    /**
     * The mailbox call on the person's tap, or a dry run of it (round 47: nothing dials by itself). One call,
     * on the first SIM with a voice feature on; it passes every guard of [MailboxCaller] first.
     */
    private suspend fun callsOut(store: FileStateStore): Result {
        val context = applicationContext
        val dryRun = inputData.getBoolean(KEY_DRY_RUN, false)
        return withPhone(store) { _, sims, phone ->
            val state = store.read()
            val due = sims.flatMap { sim ->
                val o = runCatching { OwnerSession(phone, store, sim).observe() }.getOrNull()
                listOf(OwnerFeature.VOWIFI, OwnerFeature.VOLTE).mapNotNull { f ->
                    CallLevels.bearerOf(f)?.takeIf { o?.switch(f) == true }?.let { CallsOutDue(sim.slot, sim.key, f, it, if (dryRun) "dry_run_request" else "tap_request") }
                }
            }.firstOrNull()
            if (due == null) {
                store.update { it.logged(System.currentTimeMillis(), "calls_out", "no voice feature is on, so there is nothing to call over") }
                return@withPhone Result.success()
            }
            val sim = sims.first { it.slot == due.slot && it.key.subId == due.sim.subId }
            val caller = MailboxCaller(
                phone, store, AndroidCallDevice(context), OwnerBasis.current(context, state).rom, CallBuild.short(context),
                CallBuild.stack(context), device(context).model, CallBuild.cellOf(context),
            )
            val outcome = caller.run(sim, due, CallBuild.targets(context), dryRun)
            OwnerScheduler.dropKeeps(context, outcome.reverts)
            settleProvisional(context, store.read())
            Result.success()
        }
    }

    /** Drops one provisional keep the call evidence reverted (round 26 check 6); the guards decide when. */
    private suspend fun dropKeep(store: FileStateStore): Result {
        val feature = OwnerFeature.ofWire(inputData.getString(KEY_FEATURE).orEmpty()) ?: return Result.failure()
        val slot = inputData.getInt(KEY_SLOT, -1)
        val reason = inputData.getString(KEY_REASON) ?: "provisional keep reverted"
        return withPhone(store) { _, sims, phone ->
            val sim = sims.firstOrNull { it.slot == slot } ?: return@withPhone Result.success()
            val kept = store.read().overrides.any { it.slot == slot && it.key == feature.key && it.hold == io.github.open_carrier_data.check.owner.core.Hold.KEPT }
            if (!kept) {
                store.update { it.provisionalSettled() }
                return@withPhone Result.success()
            }
            val session = OwnerSession(phone, store, sim, device(applicationContext).model)
            try {
                session.guard(callWaitMs = 0)
            } catch (e: SafetyException) {
                store.update { it.logged(System.currentTimeMillis(), "provisional", "${feature.wire}: the revert waits (${e.reason})") }
                return@withPhone if (runAttemptCount < 20) Result.retry() else Result.success()
            }
            val ok = session.dropKeep(feature, reason)
            store.update {
                it.provisionalSettled().logged(System.currentTimeMillis(), "provisional", "${feature.wire} on slot ${slot + 1} back to your own setting: ${reason.replace('_', ' ')}${if (ok) "" else "; the revert was not proven"}")
            }
            Result.success()
        }
    }

    /** The hourly run keeps to [CHECK_EVERY_MS] outside the night; the owner screen's "check now" always checks. */
    private val periodic: Boolean get() = inputData.getBoolean(KEY_PERIODIC, false)

    /** A log line only when it differs from the last of its kind: a quiet phone keeps a quiet log. */
    private fun quietLog(store: FileStateStore, kind: String, text: String) {
        if (store.read().log.lastOrNull { it.kind == kind }?.text == text) return
        store.update { it.logged(System.currentTimeMillis(), kind, text) }
    }

    /** [sim]'s results are recorded against its own data key ([OwnerBasis.forSim], round 29 F4). */
    private fun options(store: FileStateStore, phone: OwnerPhone?, ocd: OcdAnswer?, sim: OwnerSim, bundle: OcdBundle, rows: List<OcdApnRow>): RunOptions {
        val video = VideoStatic.reasons(applicationContext).let { if (it.isNotEmpty() && ocd?.value != true) it + "no_carrier_offer" else it }
        return RunOptions(
            testOffs = inputData.getBoolean(KEY_OFFS, true),
            voiceCheckMs = if (background) 0 else inputData.getLong(KEY_VOICE_MS, 0),
            apns = inputData.getBoolean(KEY_APNS, true),
            scheduled = inputData.getBoolean(KEY_SCHEDULED, false),
            only = if (inputData.keyValueMap.containsKey(KEY_ONLY)) inputData.getBoolean(KEY_ONLY, true) else null,
            apnKeys = inputData.getStringArray(KEY_APN_KEYS)?.toSet(),
            maxApnRows = if (background) QuietPlan.MAX_ON_PER_NIGHT else Int.MAX_VALUE,
            keepValues = false,
            interrupt = if (background) AndroidInterrupt(applicationContext) else null,
            basis = OwnerBasis.forSim(applicationContext, store.read(), bundle, sim, rows),
            videoUnavailable = video,
        )
    }

    /**
     * A background step's own look at the silent window (the job that queued
     * it may be minutes old): closed, or the night's 20 minutes over, stops
     * the chain here; the quiet job tries again later.
     */
    private suspend fun gate(store: FileStateStore, phone: OwnerPhone, runId: String, nightOnly: Boolean = inputData.getBoolean(KEY_NIGHT_ONLY, true)): Result? {
        val started = inputData.getLong(KEY_SESSION_START, 0)
        val over = started > 0 && System.currentTimeMillis() - started > QuietWindow.SESSION_MS
        val block = if (over) null else QuietWindow.trialBlocker(QuietReader(applicationContext).read(phone, QuietWindow.STEP_SAMPLE_MS), nightOnly)
        if (!over && block == null) return null
        val why = if (over) "the night's 20 minutes are over" else block!!.plain
        store.update { s ->
            val run = s.runs.firstOrNull { it.id == runId }
            (if (run != null) s.withRun(run.copy(endedMs = System.currentTimeMillis(), note = "stopped before a step: $why; tried again later")) else s)
                .logged(System.currentTimeMillis(), "quiet_wait", "background run $runId stopped before a step: $why")
        }
        return Result.failure()
    }

    /** A background step met an interruption: the trial is reverted; the rest of the chain stops and the quiet job tries again later. */
    private fun interrupted(store: FileStateStore, runId: String, reason: String): Result {
        store.update { s ->
            val run = s.runs.firstOrNull { it.id == runId }
            (if (run != null) s.withRun(run.copy(endedMs = System.currentTimeMillis(), note = "stopped: ${reason.removePrefix(RunInterrupted.INTERRUPTED)}; tried again later")) else s)
        }
        if (store.read().anyLive) OwnerScheduler.cleanup(applicationContext, policy = ExistingWorkPolicy.KEEP)
        return Result.failure()
    }

    /** A chain step's changes go into its run (told at the end); a failure is told at once. */
    private fun account(store: FileStateStore, runId: String, before: OwnerState) {
        val after = store.read()
        val changes = Notices.changes(before, after)
        val failures = Notices.failures(before, after)
        if (changes.isNotEmpty() || failures.isNotEmpty()) {
            store.update { s -> s.runs.firstOrNull { it.id == runId }?.let { s.withRun(it.copy(changes = it.changes + changes, failures = it.failures + failures)) } ?: s }
        }
        Notices.of(before, after)?.takeIf { it.failed }?.let { OwnerNotifications.notice(applicationContext, it) }
        if (background) store.update { it.copy(trialNight = QuietWindow.nightOf(ZonedDateTime.now())) }
    }

    private suspend fun feature(store: FileStateStore): Result {
        requireTrials(store)
        val feature = OwnerFeature.ofWire(inputData.getString(KEY_FEATURE).orEmpty()) ?: return Result.failure()
        val slot = inputData.getInt(KEY_SLOT, -1)
        val runId = inputData.getString(KEY_RUN) ?: return Result.failure()
        val data = OwnerData(applicationContext, store)
        val bundle = data.bundle()
        return withPhone(store) { runner, sims, phone ->
            val sim = sims.firstOrNull { it.slot == slot }
            val run = startRun(store, runId, sim, bundle)
            if (sim == null) {
                store.update { it.withRun(run.copy(refused = "no_sim", note = "no SIM, nothing to test")) }
                return@withPhone Result.success()
            }
            if (background) gate(store, phone, runId)?.let { return@withPhone it }
            val ocd = OwnerRunner.answers(bundle, sim, device(applicationContext)).getValue(feature)
            val options = options(store, phone, ocd, sim, bundle, data.apnRows(sim))
            val before = store.read()
            if (background) RevertAlarm.arm(applicationContext)
            val result = try {
                runner.testFeature(sim, feature, ocd, options)
            } finally {
                if (background && !store.read().trialsLive()) RevertAlarm.disarm(applicationContext)
            }
            store.update { s ->
                val current = s.runs.firstOrNull { it.id == runId } ?: run
                s.withRun(current.copy(features = current.features.filterNot { it.feature == feature } + result))
            }
            if (background && result.refused?.startsWith(RunInterrupted.INTERRUPTED) == true) return@withPhone interrupted(store, runId, result.refused)
            account(store, runId, before)
            Result.success()
        }
    }

    private suspend fun apn(store: FileStateStore): Result {
        requireTrials(store)
        val slot = inputData.getInt(KEY_SLOT, -1)
        val runId = inputData.getString(KEY_RUN) ?: return Result.failure()
        val data = OwnerData(applicationContext, store)
        return withPhone(store) { runner, sims, phone ->
            val sim = sims.firstOrNull { it.slot == slot } ?: return@withPhone Result.success()
            val bundle = data.bundle()
            val run = startRun(store, runId, sim, bundle)
            if (background) gate(store, phone, runId)?.let { return@withPhone it }
            val rows = data.apnRows(sim)
            val before = store.read()
            if (background) RevertAlarm.arm(applicationContext)
            val results = try {
                if (rows.isEmpty() && data.currentDir() == null) {
                    listOf(ApnResult("all rows", "", "no_release_pulled"))
                } else {
                    runner.testApns(sim, rows, options(store, phone, null, sim, bundle, rows))
                }
            } finally {
                if (background && !store.read().trialsLive()) RevertAlarm.disarm(applicationContext)
            }
            store.update { s ->
                val current = s.runs.firstOrNull { it.id == runId } ?: run
                s.withRun(current.copy(apns = results))
            }
            val stopped = results.firstOrNull { it.status.startsWith("refused_" + RunInterrupted.INTERRUPTED) }
            if (background && stopped != null) return@withPhone interrupted(store, runId, stopped.status.removePrefix("refused_"))
            account(store, runId, before)
            Result.success()
        }
    }

    /** The end of a chain: the run's one line for the Settings entry, and a notification only when it changed something. */
    private fun finish(store: FileStateStore): Result {
        val id = inputData.getString(KEY_RUN)
        val run = store.read().runs.firstOrNull { it.id == id } ?: return Result.success()
        val ended = run.copy(endedMs = System.currentTimeMillis())
        val line = RunSummary.line(ended)
        store.update { it.withRun(ended).copy(lastResult = LastResult(System.currentTimeMillis(), line, changed = ended.changes.isNotEmpty(), failed = ended.failures.isNotEmpty())) }
        // Failures were told when they happened; the changes are told now.
        if (ended.changes.isNotEmpty()) OwnerNotifications.notice(applicationContext, Notices.changed(ended.changes))
        return Result.success()
    }

    private fun startRun(store: FileStateStore, runId: String, sim: OwnerSim?, bundle: OcdBundle): RunResult {
        val state = store.read()
        state.runs.firstOrNull { it.id == runId }?.let { return it }
        val device = device(applicationContext)
        val profile = sim?.let { Matcher.resolve(bundle, it.identity).mostSpecific }
        val pulled = OwnerData(applicationContext, store).currentDir() != null
        val run = RunResult(
            id = runId,
            startedMs = System.currentTimeMillis(),
            scheduled = inputData.getBoolean(KEY_SCHEDULED, false),
            sim = sim?.key,
            simName = sim?.name,
            profileId = profile?.profileId,
            releaseTag = state.pin?.takeIf { pulled }?.label ?: "bundled ${bundle.source.commit.take(8)}",
            device = "${device.model} (${device.codename})",
            stack = "${device.romFamily.plain}, Android ${device.androidMajor}, ${Build.TYPE}",
            background = background,
            plan = inputData.getString(KEY_PLAN),
            cell = OwnerData.reportCell(applicationContext, device, sim, profile, pulled, bundle),
        )
        store.update { it.withRun(run) }
        return run
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
        const val KEY_IN_RUN = "in_run"
        const val KEY_BACKGROUND = "background"
        const val KEY_ONLY = "only"
        const val KEY_APN_KEYS = "apn_keys"
        const val KEY_NIGHT_ONLY = "night_only"
        const val KEY_SESSION_START = "session_start"
        const val KEY_PLAN = "plan"
        const val KEY_PERIODIC = "periodic"
        const val KEY_REASON = "reason"
        const val KEY_DRY_RUN = "dry_run"

        /** Round 42: the verdict line on Android 10 and 11, where the cause read cannot run. */
        const val DIAGNOSIS_NOT_RUN_OLD_ANDROID = "cause diagnosis not run: needs Android 12 or newer"

        const val STEP_WEEKLY = "weekly"
        const val STEP_QUIET = "quiet"
        const val STEP_CLEANUP = "cleanup"
        const val STEP_PULL = "pull"
        const val STEP_FEATURE = "feature"
        const val STEP_APN = "apn"
        const val STEP_FINISH = "finish"
        const val STEP_PROBE = "probe"
        const val STEP_CALLS_OUT = "calls_out"
        const val STEP_SELF_SMS = "self_sms"
        const val STEP_BALANCE = "balance"
        const val STEP_DROP_KEEP = "drop_keep"
        const val STEP_DATASET = "dataset"
        const val STEP_MAIN_CHECK = "main_check"
        const val STEP_VOICE_CHECK = "voice_check"
        const val STEP_EMERGENCY_DATA = "emergency_data"
        const val STEP_EMERGENCY_DEFAULT_FIRST_USE = "emergency_default_first_use"
        const val KEY_TEST_NOW = "test_now"

        /** At most this many mailbox calls in one step: one per bearer. */
        const val MAX_CALLS_PER_STEP = 2

        private val CHAIN_STEPS = setOf(STEP_FEATURE, STEP_APN, STEP_FINISH, STEP_CALLS_OUT)

        /** The light check outside the night: every six hours. */
        val CHECK_EVERY_MS: Long = TimeUnit.HOURS.toMillis(6)
        val PULL_EVERY_MS: Long = TimeUnit.HOURS.toMillis(20)

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
    const val QUIET = "owner-quiet"
    const val MAIN_CHECK = "owner-main-check"
    const val RUN = "owner-run"
    const val CLEANUP = "owner-cleanup"
    const val PULL = "owner-pull"
    const val DATASET = "owner-dataset"
    const val VOICE_CHECK = "owner-voice-check"
    const val EMERGENCY_DATA = "owner-emergency-data"

    /** The tag of a "Test now"'s pull and dataset step (round 30). */
    const val TEST_NOW = "owner-test-now"

    /** The quiet job runs while Android counts the phone idle (screen off about half an hour); the job checks the rest itself. */
    private fun quietConstraints(): Constraints = Constraints.Builder().setRequiresDeviceIdle(true).build()

    /** A night step: idle and charging, as the silent window wants; no network constraint (an APN trial moves mobile data). */
    private fun nightConstraints(): Constraints = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()

    /**
     * The hourly quiet job (replaces the weekly job of the builds before 2026-10-20), and the 15-minute data job.
     * "Test automatically" is [on]: it holds the quiet job only. Round 47: data pulls continue, so the data job runs either way.
     */
    fun ensureQuiet(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WEEKLY)
        ensureMainCheck(wm)
        if (!on) {
            wm.cancelUniqueWork(QUIET)
            return
        }
        val request = PeriodicWorkRequestBuilder<OwnerWorker>(1, TimeUnit.HOURS)
            .setConstraints(quietConstraints())
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_QUIET, OwnerWorker.KEY_PERIODIC to true))
            .addTag(QUIET)
            .build()
        wm.enqueueUniquePeriodicWork(QUIET, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Round 47: the 15-minute job ([MainCheck]), at WorkManager's floor. It needs a network and nothing else; the flex window and the first delay are its jitter. */
    private fun ensureMainCheck(wm: WorkManager) {
        val request = PeriodicWorkRequestBuilder<OwnerWorker>(MainCheck.PERIOD_MINUTES, TimeUnit.MINUTES, MainCheck.FLEX_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Random.nextLong(TimeUnit.MINUTES.toMillis(MainCheck.PERIOD_MINUTES)), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_MAIN_CHECK))
            .addTag(MAIN_CHECK)
            .build()
        wm.enqueueUniquePeriodicWork(MAIN_CHECK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Whether a test chain is queued or running. */
    fun runActive(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(RUN).get().any { !it.state.isFinished }
    }.getOrDefault(false)

    private fun step(data: Data, constraints: Constraints?, expedite: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<OwnerWorker>().setInputData(data).apply {
            if (constraints != null) setConstraints(constraints)
            if (expedite) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }.build()

    /**
     * Leftover cleanup and per-SIM re-apply, at once. After a boot it no longer waits out a fixed 10 minutes
     * (2026-10-25): the step waits for the SIM, CarrierSettings's config and IMS ([OwnerReady]) when something
     * is recorded as live, and the refusals decide; a refusal retries it with a 2-minute linear back-off.
     */
    fun cleanup(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_CLEANUP))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CLEANUP, policy, request)
    }

    /**
     * The owner's "Stop test": cancels the queued and running steps (a
     * cancelled step reverts its trial, [OwnerWorker]), ends the run's record,
     * and queues the cleanup for whatever is left. Changes Open Carrier Data
     * keeps stay; "Undo changes" removes those.
     */
    fun stopTests(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(RUN)
        // Round 30's "Test now": a cancelled dataset step clears its trial overrides on the way out (TrialRunner.endChain).
        WorkManager.getInstance(context).cancelAllWorkByTag(TEST_NOW)
        val now = System.currentTimeMillis()
        FileStateStore(context).update { s ->
            s.runs.filter { it.endedMs == null }.fold(s) { acc, run -> acc.withRun(run.copy(endedMs = now, note = "stopped by the owner")) }
                .logged(now, "run", "the owner stopped the test")
        }
        cleanup(context, policy = ExistingWorkPolicy.KEEP)
    }

    /**
     * Round 30: the dataset step (stage, activate, test). A background step waits for the idle moment; [testNow] is
     * "Test now", which pulls, hands over, installs and tests in this moment, under the refusals only. Returns false
     * when a "Test now" is already under way (it is never cancelled by a second press).
     */
    fun dataset(context: Context, testNow: Boolean = false, delayMs: Long = 0): Boolean {
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_DATASET, OwnerWorker.KEY_TEST_NOW to testNow))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .apply { if (testNow) addTag(TEST_NOW) }
            .build()
        if (!testNow) {
            wm.enqueueUniqueWork(DATASET, ExistingWorkPolicy.KEEP, request)
            return true
        }
        if (testNowActive(context)) return false
        // "Test now": the pull first, then the hand-over, the install and the tests. No network constraint: a pull that
        // fails offline never holds the test up, and the test runs on the data the phone has.
        val pull = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_PULL, OwnerWorker.KEY_IN_RUN to true))
            .addTag(TEST_NOW)
            .build()
        wm.beginUniqueWork(DATASET, ExistingWorkPolicy.REPLACE, pull).then(request).enqueue()
        return true
    }

    /** The steps of a "Test now" still to run or running. */
    fun testNowSteps(context: Context): Int = runCatching { pending(WorkManager.getInstance(context).getWorkInfosByTag(TEST_NOW).get()) }.getOrDefault(0)

    fun testNowActive(context: Context): Boolean = testNowSteps(context) > 0

    /** The voice-loss trigger a few minutes after a change (an install, a record, a carrier config change). */
    fun voiceCheck(context: Context, delayMs: Long = TimeUnit.MINUTES.toMillis(3)) {
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_VOICE_CHECK))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(VOICE_CHECK, ExistingWorkPolicy.KEEP, request)
    }

    /** The emergency data test by hand on the SIM in [slot] (its first use, with adb logcat streamed). */
    fun emergencyData(context: Context, slot: Int) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            EMERGENCY_DATA, ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_EMERGENCY_DATA, OwnerWorker.KEY_SLOT to slot), null, expedite = true),
        )
    }

    /** Round 31: the default emergency APN cycle's first use on the SIM in [slot]: the temporary set and its restore, no EIMS request. */
    fun emergencyDefaultFirstUse(context: Context, slot: Int) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            EMERGENCY_DATA, ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_EMERGENCY_DEFAULT_FIRST_USE, OwnerWorker.KEY_SLOT to slot), null, expedite = true),
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

    /** The owner's "check now": the light check at once, without waiting for idle. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-check-now", ExistingWorkPolicy.REPLACE,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_QUIET), null, expedite = false),
        )
    }

    /** The owner's one test SMS to the phone's own number now ([OwnerWorker] STEP_SELF_SMS). */
    fun selfSmsNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-self-sms-now", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_SELF_SMS), null, expedite = true),
        )
    }

    /** Round 47: one mailbox call on the person's own tap; every guard of the call holds. */
    fun callMailboxNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-call-mailbox-now", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_CALLS_OUT), null, expedite = true),
        )
    }

    /** Round 47: one balance read on the person's own tap. */
    fun balanceNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-balance-now", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_BALANCE), null, expedite = true),
        )
    }

    /** The owner's dry run of the mailbox call: every guard, then a log line of what it would dial; nothing is dialled. */
    fun dryRunCallsOut(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-calls-out-dry-run", ExistingWorkPolicy.REPLACE,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_CALLS_OUT, OwnerWorker.KEY_DRY_RUN to true), null, expedite = true),
        )
    }

    /** The owner's "Test now": one step per SIM and feature, then the APN rows, then the end mark. */
    suspend fun runTests(context: Context, offs: Boolean, voiceCheckMs: Long, apns: Boolean): String {
        // Round 30: "Test now" pulls, hands over, installs and tests every changed value together, now (only the refusals stop it).
        val status = OwnerDatasetStep.status(context, FileStateStore(context))
        // An app older than the ROM's installer (a newer protocol) hands nothing over and runs no trial chain either.
        io.github.open_carrier_data.check.owner.core.OlderApp.refusal(status)?.let { why ->
            FileStateStore(context).update { it.logged(System.currentTimeMillis(), "run", "test now refused: $why") }
            runCatching { android.util.Log.i(OwnerActivity.TEST_NOW_TAG, "test now refused: $why") }
            return "refused: $why"
        }
        if (status.available) {
            if (!dataset(context, testNow = true)) return "queued already: a test is under way"
            FileStateStore(context).update { it.copy(undone = emptyList()).logged(System.currentTimeMillis(), "run", "test now: pull, hand over, install and test, now") }
            return "queued: pull, install and test"
        }
        // Without the installer there is nothing to test with: setting trials need the provider and a calibrated stack.
        OwnerTester.trialRefusal(context, FileStateStore(context).read())?.let { why ->
            FileStateStore(context).update { it.logged(System.currentTimeMillis(), "run", "test now refused: ${why.plain}") }
            return if (why.code == TesterPolicy.NOT_CALIBRATED) why.plain else "off: ${why.plain}"
        }
        return "off: this phone has no dataset installer"
    }

    /** Whether the work of a unique name is still to run. */
    fun pending(info: List<WorkInfo>): Int = info.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
}
