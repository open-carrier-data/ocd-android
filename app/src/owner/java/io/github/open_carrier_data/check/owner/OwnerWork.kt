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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.DataBasis
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.PlanWhy
import io.github.open_carrier_data.check.owner.core.SchemaRefused
import io.github.open_carrier_data.check.owner.core.CallsOutDue
import io.github.open_carrier_data.check.owner.core.CallsOutMode
import io.github.open_carrier_data.check.owner.core.CallsOutPlan
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
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Plan
import io.github.open_carrier_data.check.owner.core.QuietBlock
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.QuietWindow
import io.github.open_carrier_data.check.owner.core.RunInterrupted
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.RunSummary
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.ServerClock
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
                STEP_CLEANUP -> cleanup(store)
                STEP_REVERT_ALL -> withPhone(store) { runner, sims, phone ->
                    runner.revertAll(sims)
                    // Undo changes also clears what is kept after restart (round 29 S11, S12); it works while roaming.
                    val line = OwnerLayerStep.undo(applicationContext, store, phone, sims, null, null)
                    if (!line.startsWith("no runtime layer") && line != "nothing kept after restart") store.update { it.logged(System.currentTimeMillis(), "layer", line) }
                    Result.success()
                }
                STEP_LAYER -> withPhone(store) { _, sims, phone ->
                    val line = OwnerLayerStep.run(applicationContext, store, phone, sims, null, quietOpen = false)
                    if (!line.startsWith("no runtime layer") && line != "layer: nothing to do") store.update { it.logged(System.currentTimeMillis(), "layer", line) }
                    Result.success()
                }
                STEP_LAYER_UNDO -> withPhone(store) { _, sims, phone ->
                    val slot = inputData.getInt(KEY_SLOT, -1).takeIf { it >= 0 }
                    val line = OwnerLayerStep.undo(applicationContext, store, phone, sims, slot, inputData.getString(KEY_LAYER_KEY))
                    store.update { it.logged(System.currentTimeMillis(), "layer", line) }
                    Result.success()
                }
                STEP_LAYER_PROBE -> withPhone(store) { _, sims, phone ->
                    val line = OwnerLayerStep.probe(applicationContext, store, phone, sims, inputData.getInt(KEY_SLOT, 0))
                    store.update { it.logged(System.currentTimeMillis(), "layer", line) }
                    Result.success()
                }
                STEP_PULL -> if (inputData.getBoolean(KEY_IN_RUN, false)) {
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
                }
                STEP_PROBE -> withPhone(store) { runner, sims, _ ->
                    val sim = sims.firstOrNull() ?: throw SafetyException("no_sim", "no SIM, nothing to test")
                    runner.rebootProbe(sim)
                    Result.success()
                }
                STEP_FEATURE -> feature(store)
                STEP_APN -> apn(store)
                STEP_CALLS_OUT -> callsOut(store)
                STEP_SELF_SMS -> selfSmsNow(store)
                STEP_DROP_KEEP -> dropKeep(store)
                STEP_FINISH -> finish(store)
                STEP_AUTO_REPORT -> try {
                    // Round 29: the settled results as report issues, with the owner's token; it logs what it sent, never the token.
                    OwnerAutoReports.run(applicationContext, store)
                    Result.success()
                } catch (e: java.io.IOException) {
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: CancellationException) {
            // JobScheduler stopped the work (the screen came on, the charger was pulled): the step reverted what it could, the cleanup takes the rest.
            withContext(NonCancellable) {
                val now = store.read()
                if (now.anyLive) OwnerScheduler.cleanup(applicationContext, policy = ExistingWorkPolicy.KEEP)
                if (!now.trialsLive()) RevertAlarm.disarm(applicationContext)
                OwnerNotifications.refresh(applicationContext, now)
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
        if (step !in CHAIN_STEPS && step != STEP_REVERT_ALL) Notices.of(before, after)?.let { OwnerNotifications.notice(applicationContext, it) }
        OwnerNotifications.refresh(applicationContext, after)
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

    /** Leftover cleanup and the per-SIM re-apply; retried while a refusal holds (in a call, just booted, Shizuku not up yet). */
    private suspend fun cleanup(store: FileStateStore): Result {
        val summary = withPhone(store) { runner, sims, _ -> runner.reconcile(sims) }
        if (!store.read().trialsLive()) RevertAlarm.disarm(applicationContext)
        return if ("refused" in summary || "waits" in summary) {
            if (runAttemptCount < 8) Result.retry() else Result.success()
        } else {
            Result.success()
        }
    }

    /**
     * The hourly quiet job: nothing while the screen is on or a call is up;
     * the light check every [CHECK_EVERY_MS] (and each hour of the night); the
     * night's one feature when the silent window is open. Writes the log only
     * when something is new, so a quiet phone keeps a quiet log.
     */
    private suspend fun quiet(store: FileStateStore): Result {
        val context = applicationContext
        var state = store.read()
        if (!state.scheduleOn || OwnerScheduler.runActive(context)) return Result.success()
        val handle = runCatching { OwnerAccess.open(context) }.getOrElse { e ->
            quietLog(store, "quiet", "no access now: ${e.message?.take(120)}")
            return Result.success()
        }
        try {
            val phone = handle.phone
            val reader = QuietReader(context)
            val look = reader.read(phone, sampleMs = 0)
            if (QuietWindow.checkBlocker(look) != null) return Result.success()
            val now = System.currentTimeMillis()
            val nowLocal = ZonedDateTime.now()
            val night = QuietWindow.night(look.minuteOfDay)
            val last = state.lastCheckAtMs
            val checkDue = !periodic || last == null || now - last >= CHECK_EVERY_MS || now < last || night || state.triggerSinceMs != null
            if (!checkDue) return Result.success()
            // main's newest validated commit, once a day on Wi-Fi (round 29); every six hours while nothing is pinned yet.
            val pullDue = state.lastPullAtMs == null || now - state.lastPullAtMs!! >= (if (state.pin == null) CHECK_EVERY_MS else PULL_EVERY_MS) || now < state.lastPullAtMs!!
            if (look.wifiConnected == true && pullDue) {
                store.update { it.copy(lastPullAtMs = now) }
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
            if (checks.any { it.missingKeeps.isNotEmpty() }) OwnerScheduler.cleanup(context, policy = ExistingWorkPolicy.KEEP)
            // Round 26: provisional keeps reverted when the night ended without calls out or 7 days passed without incoming.
            settleProvisional(context, store.read())
            messageChecks(store, phone, sims, look, night)
            // Round 29: the runtime layer. Reverts and renewals at every light check; what passed its class check goes in only in a silent window.
            val quietOpen = QuietWindow.trialBlocker(look, nightOnly = false, traffic = false) == null
            try {
                val line = OwnerLayerStep.run(context, store, phone, sims, checks, quietOpen)
                if (!line.startsWith("no runtime layer") && line != "layer: nothing to do") quietLog(store, "layer", line)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                quietLog(store, "layer", "the layer step failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
            // Round 29: settled results go out as report issues when the owner set a token (its own work, with a network).
            OwnerScheduler.autoReport(context)
            state = store.read()
            // Others' reports for each SIM's own cell: they only move an entry earlier (read only when there are any).
            val observed = state.observed
            val reports = if (observed?.pointers.isNullOrEmpty()) {
                emptyMap()
            } else {
                OwnerData.observedCells(context, device(context), sims, bundle).associate { (sim, cell) -> sim.key to ObservedReports.forCell(observed, cell) }
            }
            val plan = QuietPlan.plan(
                checks, state.entries, basis, now,
                known = state.runs.mapNotNull { it.sim }.toSet(),
                onOnly = (state.onOnlyUntilMs ?: 0) > now,
                nightUsed = state.trialNight == QuietWindow.nightOf(nowLocal),
                undone = state.undone,
                reports = reports,
                dataKeys = dataKeys,
            )
            val ims = checks.joinToString { "slot ${it.sim.slot + 1}: ${it.imsLine}" }
            val callsDue = callsOutDue(store, sims, phone, reportsBySim = reports)
            if (plan.all.isEmpty() && callsDue.isNotEmpty()) {
                // Only mailbox calls are due (a pointer, the weekly call of a kept voice entry): night only, the whole silent window.
                val block = QuietWindow.trialBlocker(look, nightOnly = true, traffic = false)
                    ?: QuietWindow.trialBlocker(reader.read(phone, QuietWindow.FIRST_SAMPLE_MS), nightOnly = true)
                if (block == null) {
                    val line = OwnerScheduler.runCallsOut(context)
                    store.update { it.copy(lastCheckAtMs = now).logged(now, "quiet", "silent window open; $line: ${callsDue.joinToString { "${it.feature.wire} over ${it.bearer.wire} (${it.why})" }}") }
                    return Result.success()
                }
                quietLog(store, "quiet_wait", "mailbox calls wait: ${block.wire}")
            }
            if (plan.all.isEmpty()) {
                val text = "checked, nothing changed" + if (plan.waiting > 0) " (${plan.waiting} due on later nights)" else ""
                store.update { it.copy(lastCheckAtMs = now, triggerSinceMs = null, lastResult = LastResult(now, text)) }
                quietLog(store, "check", "light check: $ims; ${plan.line}${if (plan.paused.isNotEmpty()) "; paused after failures: ${plan.paused.joinToString()}" else ""}")
                return Result.success()
            }
            if (plan.triggered && state.triggerSinceMs == null) store.update { it.copy(triggerSinceMs = now) }
            val nightOnly = QuietPlan.nightOnly(plan, store.read().triggerSinceMs, now)
            val unhealthy = plan.entries.isNotEmpty() && checks.any { !it.healthy }
            // The cheap conditions first; the minute of traffic sampling only when they hold.
            val block = QuietWindow.trialBlocker(look, nightOnly, traffic = false)
                ?: QuietBlock.NOT_HEALTHY.takeIf { unhealthy }
                ?: QuietWindow.trialBlocker(reader.read(phone, QuietWindow.FIRST_SAMPLE_MS), nightOnly)
            if (block != null) {
                store.update { it.copy(lastCheckAtMs = now, lastResult = LastResult(now, "checked; ${plan.all.size} test(s) wait: ${block.plain}")) }
                quietLog(store, "quiet_wait", "${plan.line}; waits: ${block.wire}")
                return Result.success()
            }
            val line = OwnerScheduler.runPlanned(context, plan, nightOnly)
            store.update { it.copy(lastCheckAtMs = now).logged(now, "quiet", "silent window open; $line: ${plan.line}") }
            return Result.success()
        } finally {
            handle.close()
        }
    }

    /**
     * The mailbox calls due now ([CallsOutPlan]): after an apply, a pointer of
     * others' reports, or weekly per kept voice entry, unless a real call of
     * the owner's showed calls out already.
     */
    private suspend fun callsOutDue(
        store: FileStateStore, sims: List<OwnerSim>, phone: OwnerPhone,
        reportsBySim: Map<io.github.open_carrier_data.check.owner.core.SimKey, Map<String, io.github.open_carrier_data.check.owner.core.ReportPointer>>? = null,
    ): List<CallsOutDue> {
        val context = applicationContext
        val state = store.read()
        val switches = sims.associate { sim ->
            sim.key to runCatching { OwnerSession(phone, store, sim).observe() }.getOrNull().let { o -> OwnerFeature.entries.associateWith { o?.switch(it) } }
        }
        val reports = reportsBySim ?: if (state.observed?.pointers.isNullOrEmpty()) {
            emptyMap()
        } else {
            OwnerData.observedCells(context, device(context), sims, OwnerData(context, store).bundle()).associate { (sim, cell) -> sim.key to ObservedReports.forCell(state.observed, cell) }
        }
        val pointed = reports.mapValues { (_, byEntry) -> byEntry.filterValues { it.moves }.keys }
        return CallsOutPlan.due(sims, state, switches, pointed, OwnerBasis.current(context, state).rom, QuietWindow.nightOf(ZonedDateTime.now()), System.currentTimeMillis())
    }

    /**
     * The message checks of the light check: the MMS access point probe (never
     * where MMS ended, every 14 days, no MMS sent) and, only when the owner
     * turned it on, the weekly data SMS to the phone's own number at night.
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
            val state = store.read()
            if (state.selfSmsOn && night && QuietWindow.trialBlocker(look, nightOnly = true, traffic = false) == null && SelfSms.due(state.selfSms, sim.key, now)) {
                // The check is in the ledger before the SMS goes out (OwnerSelfSms.send); only the log line here.
                val sent = runCatching { OwnerSelfSms.send(context, phone, sim, on = true) }.getOrNull() ?: continue
                store.update { it.logged(now, "self_sms", "slot ${sim.slot + 1}: ${sent.result}") }
            }
        }
    }

    /**
     * The owner's "Send a test SMS now": one data SMS to each SIM's own number, on the app's
     * port, under the owner build's refusals (no call, no emergency state, not roaming) and the
     * self-SMS refusals. The weekly switch stays as the owner set it.
     */
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
     * The night's mailbox calls (round 26 check 4), or a dry run on the
     * owner's request. Live calls run only in the night window, at most one
     * per feature and bearer a night; each call passes every guard of
     * [MailboxCaller] first.
     */
    private suspend fun callsOut(store: FileStateStore): Result {
        val context = applicationContext
        val dryRunRequest = inputData.getBoolean(KEY_DRY_RUN, false)
        return withPhone(store) { _, sims, phone ->
            val runId = inputData.getString(KEY_RUN)
            if (background && runId != null) gate(store, phone, runId, nightOnly = true)?.let { return@withPhone it }
            val state = store.read()
            val mode = if (dryRunRequest) CallsOutMode.DRY_RUN else state.callsOutMode
            var due = callsOutDue(store, sims, phone)
            if (dryRunRequest && due.isEmpty()) {
                // The owner's dry run: every SIM's voice features that are on, to show what the app would dial.
                due = sims.flatMap { sim ->
                    val o = runCatching { OwnerSession(phone, store, sim).observe() }.getOrNull()
                    listOf(OwnerFeature.VOWIFI, OwnerFeature.VOLTE).mapNotNull { f ->
                        CallLevels.bearerOf(f)?.takeIf { o?.switch(f) == true }?.let { CallsOutDue(sim.slot, sim.key, f, it, "dry_run_request") }
                    }
                }
            }
            if (due.isEmpty()) {
                store.update { it.logged(System.currentTimeMillis(), "calls_out", "no mailbox call due") }
                return@withPhone Result.success()
            }
            val minute = ZonedDateTime.now().let { it.hour * 60 + it.minute }
            val night = QuietWindow.night(minute)
            val caller = MailboxCaller(
                phone, store, AndroidCallDevice(context), OwnerBasis.current(context, state).rom, CallBuild.short(context),
                CallBuild.stack(context), device(context).model, CallBuild.cellOf(context),
            )
            val targets = CallBuild.targets(context)
            for (d in due.take(MAX_CALLS_PER_STEP)) {
                val sim = sims.firstOrNull { it.slot == d.slot && it.key.subId == d.sim.subId } ?: continue
                if (background && mode == CallsOutMode.LIVE) RevertAlarm.arm(context)
                val outcome = try {
                    caller.run(sim, d, targets, mode, night, QuietWindow.nightOf(ZonedDateTime.now()))
                } finally {
                    if (!store.read().trialsLive()) RevertAlarm.disarm(context)
                }
                OwnerScheduler.dropKeeps(context, outcome.reverts)
                // A scheduled dry run also uses the night's one call, so the log gets one line a night, not one an hour.
                if (outcome.result == "dry_run" && !dryRunRequest) {
                    store.update { s -> s.copy(callsOutNights = (s.callsOutNights + io.github.open_carrier_data.check.owner.core.CallsOutNight(d.sim, d.feature, d.bearer, QuietWindow.nightOf(ZonedDateTime.now()))).takeLast(MailboxCaller.MAX_NIGHTS)) }
                }
                if (outcome.result == "aborted") break
            }
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
        OwnerScheduler.autoReport(applicationContext)
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

        const val STEP_WEEKLY = "weekly"
        const val STEP_QUIET = "quiet"
        const val STEP_CLEANUP = "cleanup"
        const val STEP_REVERT_ALL = "revert_all"
        const val STEP_PULL = "pull"
        const val STEP_FEATURE = "feature"
        const val STEP_APN = "apn"
        const val STEP_FINISH = "finish"
        const val STEP_PROBE = "probe"
        const val STEP_CALLS_OUT = "calls_out"
        const val STEP_SELF_SMS = "self_sms"
        const val STEP_DROP_KEEP = "drop_keep"
        const val STEP_LAYER = "layer"
        const val STEP_LAYER_UNDO = "layer_undo"
        const val STEP_LAYER_PROBE = "layer_probe"
        const val STEP_AUTO_REPORT = "auto_report"
        const val KEY_LAYER_KEY = "layer_key"

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
    const val RUN = "owner-run"
    const val CLEANUP = "owner-cleanup"
    const val REVERT_ALL = "owner-revert-all"
    const val AUTO_REPORT = "owner-auto-report"
    const val PULL = "owner-pull"
    const val LAYER = "owner-layer"
    const val LAYER_UNDO = "owner-layer-undo"

    /** The quiet job runs while Android counts the phone idle (screen off about half an hour); the job checks the rest itself. */
    private fun quietConstraints(): Constraints = Constraints.Builder().setRequiresDeviceIdle(true).build()

    /** A night step: idle and charging, as the silent window wants; no network constraint (an APN trial moves mobile data). */
    private fun nightConstraints(): Constraints = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()

    /** The hourly quiet job (replaces the weekly job of the builds before 2026-10-20). */
    fun ensureQuiet(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WEEKLY)
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
     * Leftover cleanup and per-SIM re-apply; after a boot or the app's own
     * update it waits out the 10 minutes in which a change is refused anyway,
     * counted from the boot or from the system server's start, whichever is
     * later (a soft restart, 2026-10-20).
     */
    fun cleanup(context: Context, afterBoot: Boolean = false, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE, serverUpMs: Long? = null) {
        val delay = if (afterBoot) ServerClock.delayAfterWindowMs(SystemClock.elapsedRealtime(), serverUpMs ?: 0) else 0
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_CLEANUP))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
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
        val now = System.currentTimeMillis()
        FileStateStore(context).update { s ->
            s.runs.filter { it.endedMs == null }.fold(s) { acc, run -> acc.withRun(run.copy(endedMs = now, note = "stopped by the owner")) }
                .logged(now, "run", "the owner stopped the test")
        }
        cleanup(context, policy = ExistingWorkPolicy.KEEP)
    }

    fun revertAll(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            REVERT_ALL, ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_REVERT_ALL), null, expedite = true),
        )
    }

    /** The runtime layer's step on its own: reverts and renewals (after a measured call settles a voice entry). */
    fun layerStep(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            LAYER, ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_LAYER), null, expedite = false),
        )
    }

    /** Undo of what is kept after restart: one entry ([slot] and [key]) or all ([slot] null). Works while roaming. */
    fun layerUndo(context: Context, slot: Int?, key: String?) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            LAYER_UNDO, ExistingWorkPolicy.APPEND_OR_REPLACE,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_LAYER_UNDO, OwnerWorker.KEY_SLOT to (slot ?: -1), OwnerWorker.KEY_LAYER_KEY to key), null, expedite = true),
        )
    }

    /** Round 29 S15 check 2: the first-use probe entry, equal to the build's own value. */
    fun layerProbe(context: Context, slot: Int) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            LAYER_UNDO, ExistingWorkPolicy.APPEND_OR_REPLACE,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_LAYER_PROBE, OwnerWorker.KEY_SLOT to slot), null, expedite = true),
        )
    }

    /** Round 25 first-use check 3, step one; the owner reboots afterwards. */
    fun rebootProbe(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-probe", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_PROBE), null, expedite = true),
        )
    }

    /** Round 29: sends the settled results as report issues with the owner's token; nothing without one. */
    fun autoReport(context: Context) {
        if (!OwnerToken.isSet(context)) return
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_AUTO_REPORT))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(AUTO_REPORT, ExistingWorkPolicy.KEEP, request)
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

    /**
     * The night's chain from a [Plan]: the reverts of failing kept values,
     * then the one feature's entries (both values in one step when both are
     * due), or its APN rows; then the end mark. Every step is a background
     * step: it checks the silent window again and stops on an interruption.
     */
    fun runPlanned(context: Context, plan: Plan, nightOnly: Boolean): String {
        val runId = UUID.randomUUID().toString().take(8)
        val common = arrayOf(
            OwnerWorker.KEY_RUN to runId, OwnerWorker.KEY_OFFS to true, OwnerWorker.KEY_VOICE_MS to 0L, OwnerWorker.KEY_APNS to true,
            OwnerWorker.KEY_SCHEDULED to true, OwnerWorker.KEY_BACKGROUND to true, OwnerWorker.KEY_NIGHT_ONLY to nightOnly,
            OwnerWorker.KEY_SESSION_START to System.currentTimeMillis(), OwnerWorker.KEY_PLAN to plan.line.take(400),
        )
        val steps = mutableListOf<OneTimeWorkRequest>()
        for (revert in plan.reverts) {
            val feature = revert.feature ?: continue
            steps += step(
                workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FEATURE, OwnerWorker.KEY_SLOT to revert.slot, OwnerWorker.KEY_FEATURE to feature.wire, OwnerWorker.KEY_ONLY to revert.value, OwnerWorker.KEY_NIGHT_ONLY to false),
                nightConstraints(), expedite = false,
            )
        }
        for ((key, entries) in plan.entries.groupBy { it.slot to it.group }) {
            val (slot, group) = key
            val feature = OwnerFeature.ofWire(group)
            steps += if (feature != null) {
                val values = entries.mapNotNull { it.value }.toSet()
                val only = values.singleOrNull()
                step(
                    if (only != null) {
                        workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FEATURE, OwnerWorker.KEY_SLOT to slot, OwnerWorker.KEY_FEATURE to feature.wire, OwnerWorker.KEY_ONLY to only)
                    } else {
                        workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FEATURE, OwnerWorker.KEY_SLOT to slot, OwnerWorker.KEY_FEATURE to feature.wire)
                    },
                    nightConstraints(), expedite = false,
                )
            } else {
                step(
                    workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_APN, OwnerWorker.KEY_SLOT to slot, OwnerWorker.KEY_APN_KEYS to entries.mapNotNull { it.apnKey }.toTypedArray()),
                    nightConstraints(), expedite = false,
                )
            }
        }
        if (steps.isEmpty()) return "nothing to queue"
        // Round 26: a changed voice value kept tonight needs calls out before the night ends; the step looks what is due.
        if (plan.entries.any { it.feature?.voice == true }) {
            steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_CALLS_OUT), nightConstraints(), expedite = false)
        }
        steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FINISH), null, false)
        var chain = WorkManager.getInstance(context).beginUniqueWork(RUN, ExistingWorkPolicy.KEEP, steps.first())
        for (next in steps.drop(1)) chain = chain.then(next)
        chain.enqueue()
        return "queued background run $runId (${steps.size - 1} step(s))"
    }

    /** The night's mailbox calls alone (a pointer, the weekly call of a kept voice entry): one calls-out step and the end mark. */
    fun runCallsOut(context: Context): String {
        val runId = UUID.randomUUID().toString().take(8)
        val common = arrayOf(
            OwnerWorker.KEY_RUN to runId, OwnerWorker.KEY_SCHEDULED to true, OwnerWorker.KEY_BACKGROUND to true,
            OwnerWorker.KEY_NIGHT_ONLY to true, OwnerWorker.KEY_SESSION_START to System.currentTimeMillis(),
        )
        val first = step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_CALLS_OUT), nightConstraints(), expedite = false)
        WorkManager.getInstance(context).beginUniqueWork(RUN, ExistingWorkPolicy.KEEP, first).enqueue()
        return "queued mailbox calls $runId"
    }

    /** The owner's one test SMS to the phone's own number now ([OwnerWorker] STEP_SELF_SMS). */
    fun selfSmsNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "owner-self-sms-now", ExistingWorkPolicy.KEEP,
            step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_SELF_SMS), null, expedite = true),
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
        val sims = OwnerData.sims(context)
        val runId = UUID.randomUUID().toString().take(8)
        val common = arrayOf(
            OwnerWorker.KEY_RUN to runId, OwnerWorker.KEY_OFFS to offs, OwnerWorker.KEY_VOICE_MS to voiceCheckMs,
            OwnerWorker.KEY_APNS to apns, OwnerWorker.KEY_SCHEDULED to false,
        )
        val steps = mutableListOf<OneTimeWorkRequest>()
        // Round 29: "Test now" pulls main's newest validated commit first, so the test runs on the newest data.
        if (sims.isNotEmpty()) steps += step(workDataOf(OwnerWorker.KEY_STEP to OwnerWorker.STEP_PULL, OwnerWorker.KEY_IN_RUN to true), null, expedite = false)
        for (sim in sims) {
            for (feature in OwnerFeature.entries) {
                steps += step(
                    workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FEATURE, OwnerWorker.KEY_SLOT to sim.slot, OwnerWorker.KEY_FEATURE to feature.wire),
                    null, expedite = false,
                )
            }
            if (apns) steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_APN, OwnerWorker.KEY_SLOT to sim.slot), null, false)
        }
        val store = FileStateStore(context)
        if (steps.isEmpty()) {
            store.update {
                it.withRun(RunResult(id = runId, startedMs = System.currentTimeMillis(), scheduled = false, endedMs = System.currentTimeMillis(), refused = "no_sim", note = "no SIM, nothing to test"))
                    .logged(System.currentTimeMillis(), "run", "no SIM, nothing to test")
            }
            return "no SIM, nothing to test"
        }
        steps += step(workDataOf(*common, OwnerWorker.KEY_STEP to OwnerWorker.STEP_FINISH), null, false)
        var chain = WorkManager.getInstance(context).beginUniqueWork(RUN, ExistingWorkPolicy.KEEP, steps.first())
        for (next in steps.drop(1)) chain = chain.then(next)
        chain.enqueue()
        // The owner asks for a test: what he undid may be kept again if it tests better.
        store.update { it.copy(undone = emptyList()) }
        store.update { it.logged(System.currentTimeMillis(), "run", "queued run $runId: ${sims.size} SIM(s), a pull and ${steps.size - 2} steps${if (offs) ", off values too" else ", on values only"}") }
        return "queued run $runId"
    }

    /** Whether the work of a unique name is still to run. */
    fun pending(info: List<WorkInfo>): Int = info.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
}
