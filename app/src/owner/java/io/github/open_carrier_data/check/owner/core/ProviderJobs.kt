package io.github.open_carrier_data.check.owner.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/*
 * 0.4.4: every CarrierSettings job the app waits on is followed until CarrierSettings reports its
 * end, within a bound taken from CarrierSettings's own source (0.4.3 did this for the APN candidate
 * set, [ApnCandidateJobs]). 0.4.3 still waited a fixed time in two places:
 *
 * - The default emergency APN test's temporary set (round 31, R31.9): 180 s for an install that
 *   takes up to 360 s. A set that went in place later was never restored by the app (it was not
 *   "placed"), so it stayed without the SIM's emergency rows until CarrierSettings's check found it
 *   in place over APN_TEST_MAX_MS (30 minutes), which restores it as a stop event. Its restore was
 *   followed for 300 s, less than its install and its checks take.
 * - The dataset's activation: 300 s for a job that takes up to its APN install (360 s) and the
 *   reload proof (60 s). An activation that ended later was never followed by its tests.
 */

/**
 * OcdCarrierSettings's timing, as its Engine has it (carriersettings/ocd/src/com/android/carriersettings/ocd/Engine.kt;
 * ProviderJobsTest and ApnCandidateJobsTest hold these to that source).
 */
object ProviderTiming {
    /** Engine.CONFIRM_MS: how long one send waits to show in TelephonyProvider. */
    const val CONFIRM_MS = 120_000L
    /** Engine.installApn: two sends with their confirmations, and the rollback's (Engine.CANDIDATE_INSTALL_MS for a candidate set). */
    const val INSTALL_MS = 3 * CONFIRM_MS
    /** Engine.PROOF_MS: how long an activation waits for every subscription to show the new version string. */
    const val PROOF_MS = 60_000L
    /** Engine.AFTER_MS: how long a restore waits for mobile data and IMS to come back as before. */
    const val AFTER_MS = 120_000L
    /** Engine.CANDIDATE_MAX_MS: an APN candidate set is restored without the app this long after it went in place. */
    const val CANDIDATE_MAX_MS = 10 * 60_000L
    /** Engine.APN_TEST_MAX_MS: the emergency default test set in place this long after it was stored is restored by the check, as a stop event. */
    const val APN_TEST_MAX_MS = 30 * 60_000L
    /** What the app keeps back from a CarrierSettings deadline, so that its own step comes first. */
    const val MARGIN_MS = 60_000L
    /** How often the app asks `install_status`. */
    const val POLL_MS = 2_000L
}

/** The clock and the waits the jobs are followed with: the phone's elapsed clock and its sleep. */
interface JobClock {
    fun uptimeMs(): Long
    suspend fun sleep(ms: Long)
}

object ProviderJobs {
    /** `install_status` of a job the provider lost track of (its process restarted in the middle). */
    const val INTERRUPTED = "interrupted"

    /** A job followed until it ended or the wait ran out: the last answer, and how long the app waited. */
    data class Followed(val answer: DataAnswer?, val timedOut: Boolean, val waitedMs: Long) {
        val state: String? get() = answer?.str(OcdDataProtocol.STATE)

        /** The job's error (when it says more than its state) with its reason, cleaned ([ProviderReason]). */
        val why: String? get() = answer?.let { ProviderReason.of(it.error?.takeIf { e -> e != state }, it.str(OcdDataProtocol.REASON)) }

        /** The provider no longer knows the job: a refusal without a state, or `interrupted`. */
        val lost: Boolean get() = !timedOut && (state == null || state == INTERRUPTED)
    }

    /**
     * Asks `install_status` for [job] every [ProviderTiming.POLL_MS] until [ended] says so for its
     * state, the provider no longer knows the job (a refusal without a state, or `interrupted`), or
     * [maxMs] have passed on [clock].
     */
    suspend fun follow(client: DatasetClient, job: String, maxMs: Long, clock: JobClock, ended: (String?) -> Boolean): Followed {
        val start = clock.uptimeMs()
        var a: DataAnswer?
        while (true) {
            a = client.installStatus(job)
            val state = a.str(OcdDataProtocol.STATE)
            val waited = clock.uptimeMs() - start
            if (!a.ok && state == null) return Followed(a, false, waited)
            if (ended(state) || state == INTERRUPTED) return Followed(a, false, waited)
            if (waited >= maxMs) return Followed(a, true, waited)
            clock.sleep(minOf(ProviderTiming.POLL_MS, maxMs - waited))
        }
    }

    suspend fun follow(client: DatasetClient, job: String, final: Set<String>, maxMs: Long, clock: JobClock): Followed =
        follow(client, job, maxMs, clock) { it in final }
}

// --- the default emergency APN test's temporary set (R31.9) -----------------------------------

/** The emergency default test set's bounds, from [ProviderTiming]. */
object EmergencySetTiming {
    /** CarrierSettings's own deadline: the set in place [ProviderTiming.APN_TEST_MAX_MS] after it was stored goes back as a stop event. */
    const val PROVIDER_TIMEOUT_MS = ProviderTiming.APN_TEST_MAX_MS

    /**
     * The longest the app keeps the set in place before it asks for the restore: the sos profile
     * check (60 s at the first use), the refusals read again, the EIMS request and its release
     * ([EmergencyData.WAIT_MS], [EmergencyData.RELEASE_MS]), the readings after it, and the record.
     */
    const val SESSION_MS = 5 * 60_000L

    /** The longest the app follows the set's install: the deadline less the margin and the session, counted from the app's call. */
    const val SET_WAIT_MS = PROVIDER_TIMEOUT_MS - ProviderTiming.MARGIN_MS - SESSION_MS

    /** After giving up, a restore CarrierSettings refuses while it still installs is asked again until here (from the app's call). */
    const val RESTORE_UNTIL_MS = PROVIDER_TIMEOUT_MS - ProviderTiming.MARGIN_MS

    /** The longest the app follows a restore: its install and its checks' wait, with the margin. */
    const val RESTORE_WAIT_MS = ProviderTiming.INSTALL_MS + ProviderTiming.AFTER_MS + ProviderTiming.MARGIN_MS
}

object EmergencySetJobs {
    /** What came of `emergency_default_set`: in place or not, the job's last answer, and why not. [refusal]: the call itself was refused. */
    data class SetEnd(val inPlace: Boolean, val view: DataAnswer?, val notRun: String? = null, val refusal: DataAnswer? = null)

    /** What came of `emergency_default_restore`: refused ([refusal]) or followed ([view], [state]); [line] in plain words. */
    data class RestoreEnd(val refusal: DataAnswer?, val view: DataAnswer?, val state: String?, val line: String, val timedOut: Boolean = false) {
        val accepted: Boolean get() = refusal == null
    }

    /**
     * The set for [subId], followed until it ends, within [EmergencySetTiming.SET_WAIT_MS] of the
     * call. [placed] runs once CarrierSettings took the job. When the app gives up on a set that may
     * be in place (the time is up, CarrierSettings lost the job, or the work is stopped), it asks for
     * the restore at once through [restore], with the uptime until which a `busy` refusal (the set
     * still installing) is asked again.
     */
    suspend fun set(
        client: DatasetClient,
        subId: Int,
        clock: JobClock,
        placed: () -> Unit,
        restore: suspend (retryBusyUntil: Long) -> RestoreEnd,
    ): SetEnd {
        val start = clock.uptimeMs()
        val until = start + EmergencySetTiming.RESTORE_UNTIL_MS
        val a = client.emergencyDefaultSet(subId)
        if (!a.ok) return SetEnd(false, null, EmergencyData.setRefusal(a.error), refusal = a)
        val job = a.str(OcdDataProtocol.JOB_ID) ?: a.str(OcdDataProtocol.INSTALL_ID) ?: return SetEnd(false, null, "not run: no job ID")
        placed()
        val f = try {
            ProviderJobs.follow(client, job, InstallState.SET_FINAL, EmergencySetTiming.SET_WAIT_MS - (clock.uptimeMs() - start), clock)
        } catch (e: CancellationException) {
            // The work was stopped while the set was being installed: the restore is asked for now.
            withContext(NonCancellable) { runCatching { restore(until) } }
            throw e
        }
        val state = f.state
        return when {
            state == InstallState.IN_PLACE -> SetEnd(true, f.answer)
            f.timedOut -> SetEnd(
                false, f.answer,
                "not run: the set had not ended after ${f.waitedMs / 1000} s (${state ?: "no state"}), " +
                    "${(EmergencySetTiming.PROVIDER_TIMEOUT_MS - f.waitedMs) / 1000} s before CarrierSettings's own deadline; " +
                    "the app asked for the restore at once: ${restore(until).line}",
            )
            f.lost -> SetEnd(false, f.answer, "not run: CarrierSettings lost the set's job (${f.why ?: state ?: "no answer"}); the app asked for the restore at once: ${restore(until).line}")
            // refused: never installed; apn_not_confirmed: CarrierSettings put the full set back itself.
            else -> SetEnd(false, f.answer, "not run: the set did not go in place ($state${f.why?.let { ", $it" } ?: ""})")
        }
    }

    /**
     * `emergency_default_restore` ([keep]: the set without the row kept), followed until it ends,
     * within [EmergencySetTiming.RESTORE_WAIT_MS]. With [retryBusyUntil] (an uptime), a `busy`
     * refusal is asked again until then; what is still refused is left to CarrierSettings's check.
     */
    suspend fun restore(client: DatasetClient, clock: JobClock, keep: Boolean, accepted: () -> Unit, retryBusyUntil: Long? = null): RestoreEnd {
        var a = client.emergencyDefaultRestore(keep)
        var asked = 1
        while (!a.ok && a.error == "busy" && retryBusyUntil != null && clock.uptimeMs() < retryBusyUntil) {
            clock.sleep(minOf(ProviderTiming.POLL_MS, retryBusyUntil - clock.uptimeMs()))
            a = client.emergencyDefaultRestore(keep)
            asked++
        }
        if (!a.ok) {
            val left = if (retryBusyUntil == null) "" else
                "; asked $asked time(s); left to CarrierSettings: its check restores the set once it is in place over ${EmergencySetTiming.PROVIDER_TIMEOUT_MS / 60_000} minutes, as a stop event"
            return RestoreEnd(a, null, null, "restore refused (${ProviderReason.of(a.error ?: "no answer", a.str(OcdDataProtocol.REASON))})$left")
        }
        accepted()
        val job = a.str(OcdDataProtocol.JOB_ID) ?: a.str(OcdDataProtocol.INSTALL_ID) ?: return RestoreEnd(null, null, null, "restore asked, without a job ID")
        val f = ProviderJobs.follow(client, job, InstallState.RESTORE_FINAL, EmergencySetTiming.RESTORE_WAIT_MS, clock)
        val state = f.state
        val line = when {
            f.timedOut -> "restore not ended after ${f.waitedMs / 1000} s (${state ?: "no state"}" +
                (f.answer?.str(OcdDataProtocol.WAITING_FOR)?.let { ", waiting for ${InstallRefusal.waiting(it)}" } ?: "") + "); CarrierSettings finishes it"
            else -> (state ?: "no answer") + (f.why?.let { " ($it)" } ?: "")
        }
        return RestoreEnd(null, f.answer, state ?: "no answer", line, f.timedOut)
    }
}

// --- the dataset's hand-over: verification and activation (spec 6.3, 7) ----------------------

object DatasetJobTiming {
    /**
     * Engine.runActivation: the APN install ([ProviderTiming.INSTALL_MS]), the switch, and the
     * reload proof ([ProviderTiming.PROOF_MS]). CarrierSettings has no deadline of its own for a
     * running activation and no way to cancel one (`cancel` takes a verifying or verified job only),
     * so the app waits as long as the job can take, and the margin.
     */
    const val ACTIVATION_WAIT_MS = ProviderTiming.INSTALL_MS + ProviderTiming.PROOF_MS + ProviderTiming.MARGIN_MS

    /**
     * Verification sends nothing to Telephony and has no time of its own in CarrierSettings's
     * source; nothing is in place while it runs, and the next pass follows a job still verifying.
     */
    const val VERIFY_WAIT_MS = 180_000L
}

object DatasetJobs {
    /** A staged dataset's verification, followed until verified or final, within [DatasetJobTiming.VERIFY_WAIT_MS]. */
    suspend fun verification(client: DatasetClient, id: String, clock: JobClock): ProviderJobs.Followed =
        ProviderJobs.follow(client, id, DatasetJobTiming.VERIFY_WAIT_MS, clock) { it in InstallState.FINAL || it == InstallState.VERIFIED }

    /** An activation, followed until final, within [DatasetJobTiming.ACTIVATION_WAIT_MS]. */
    suspend fun activation(client: DatasetClient, id: String, clock: JobClock): ProviderJobs.Followed =
        ProviderJobs.follow(client, id, DatasetJobTiming.ACTIVATION_WAIT_MS, clock) { it in InstallState.FINAL }

    /** What the log says when the app stopped following a job before it ended (null when it ended or was lost). */
    fun left(f: ProviderJobs.Followed, activation: Boolean): String? {
        if (!f.timedOut) return null
        return if (activation) {
            "; still ${f.state ?: "running"} after ${f.waitedMs / 1000} s; left to CarrierSettings: a running activation cannot be cancelled " +
                "(cancel takes a verifying or verified job only), so it finishes by itself and the next pass reads its end"
        } else {
            "; still ${f.state ?: "verifying"} after ${f.waitedMs / 1000} s; left to CarrierSettings: nothing is in place while it verifies, and the next pass follows it"
        }
    }
}
