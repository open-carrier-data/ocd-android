package io.github.open_carrier_data.check.owner.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/*
 * 0.4.3: how the app follows CarrierSettings's APN candidate jobs (round 32's temporary set).
 *
 * 0.4.2 polled the set for a fixed 180 s, while CarrierSettings's install of it may take up to
 * Engine.CANDIDATE_INSTALL_MS (two sends with their confirmations and the rollback's, 360 s). A set
 * that went in place after the app had stopped waiting stayed in place, unobserved, until
 * CarrierSettings's own timeout, and the trial said it never went in. Now the app follows each job
 * until CarrierSettings reports one of its end states, within CarrierSettings's own timeout less a
 * margin ([ApnCandidateTiming]). When it gives up (the time is up, the job is lost, or the work is
 * stopped) it asks for the restore at once instead of leaving the set to that timeout.
 *
 * 0.4.2 also said a refused job's error without its reason. CarrierSettings's reasons name row keys
 * only; the app still cleans each one ([ProviderReason]) before it logs or shows it.
 */

/**
 * CarrierSettings's timing for a candidate set, as OcdCarrierSettings's Engine has it
 * (carriersettings/ocd/src/com/android/carriersettings/ocd/Engine.kt; ApnCandidateJobsTest holds
 * these to that source).
 */
object ApnCandidateTiming {
    /** Engine.CONFIRM_MS: how long one send waits to show in TelephonyProvider. */
    const val CONFIRM_MS = 120_000L
    /** Engine.CANDIDATE_INSTALL_MS: the longest a candidate set's install takes (two sends with their confirmations, and the rollback's). */
    const val INSTALL_MS = 3 * CONFIRM_MS
    /** Engine.CANDIDATE_MAX_MS: CarrierSettings restores a set without the app this long after it went in place. */
    const val MAX_MS = 10 * 60_000L
    /** Engine.AFTER_MS: how long a restore waits for mobile data and IMS to come back as before. */
    const val AFTER_MS = 120_000L

    /**
     * CarrierSettings's own timeout for a set that is not in place yet: it stores the trial with
     * `untilMs = now + CANDIDATE_INSTALL_MS + CANDIDATE_MAX_MS` and restores the set itself then.
     */
    const val PROVIDER_TIMEOUT_MS = INSTALL_MS + MAX_MS

    /** What the app keeps back from that timeout, so that its own restore comes first. */
    const val MARGIN_MS = 60_000L

    /**
     * The longest the app follows the set: CarrierSettings's timeout less the margin, counted from
     * the app's call, which comes before CarrierSettings stores the set (so on the early side).
     */
    const val SET_WAIT_MS = PROVIDER_TIMEOUT_MS - MARGIN_MS

    /** The longest the app follows a restore: its install and its checks' wait, with the margin. */
    const val RESTORE_WAIT_MS = INSTALL_MS + AFTER_MS + MARGIN_MS

    /** How often the app asks `install_status`. */
    const val POLL_MS = 2_000L
}

/**
 * A provider reason, made fit for the log and the screen: credentials (a username or password with
 * its value), SIP and tel URIs, e-mail addresses, and runs of 7 or more digits (phone numbers, IMSIs,
 * ICCIDs), also with spaces, dots or dashes between them, are taken out. Row keys (`a:` and 16 hex
 * digits), which CarrierSettings's reasons name, stay as they are.
 */
object ProviderReason {
    private val ROW_KEY = Regex("a:[0-9a-f]{16}(?![0-9A-Za-z])")
    private val CREDENTIAL = Regex(
        "(?i)(\"?\\b(?:user(?:name)?|password|passwd|pwd|secret|token)\\b\"?\\s*[:=]\\s*)(\"[^\"]*\"?|'[^']*'?|[^\\s,;)\\]}]+)",
    )
    private val URI = Regex("(?i)\\b(?:sips?|tel):[^\\s,;)\\]}\"']+")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val DIGITS = Regex("\\+?\\d(?:[ ./()-]{0,2}\\d){6,}")
    const val CREDENTIAL_MARK = "<removed>"
    const val NUMBER_MARK = "<n>"

    /** [text] cleaned and cut to [max] characters; null for null or blank. */
    fun clean(text: String?, max: Int = 200): String? {
        if (text.isNullOrBlank()) return null
        val out = StringBuilder()
        var at = 0
        for (key in ROW_KEY.findAll(text)) {
            out.append(cleanPart(text.substring(at, key.range.first)))
            out.append(key.value)
            at = key.range.last + 1
        }
        out.append(cleanPart(text.substring(at)))
        val s = out.toString().trim()
        return if (s.length <= max) s else s.take(max - 1) + "…"
    }

    private fun cleanPart(s: String): String {
        var t = CREDENTIAL.replace(s) { it.groupValues[1] + CREDENTIAL_MARK }
        t = URI.replace(t, NUMBER_MARK)
        t = EMAIL.replace(t, NUMBER_MARK)
        return DIGITS.replace(t, NUMBER_MARK)
    }

    /** A provider answer's error with its reason, cleaned: "candidate_invalid: row a:… is not one of this SIM's rows". */
    fun of(error: String?, reason: String?, max: Int = 200): String? {
        val e = clean(error, 60)
        val r = clean(reason, max)
        return when {
            e == null -> r
            r == null -> e
            else -> "$e: $r"
        }
    }
}

/** The clock and the waits the jobs are followed with: the phone's elapsed clock and its sleep. */
interface JobClock {
    fun uptimeMs(): Long
    suspend fun sleep(ms: Long)
}

/** A provider's `install_status` lost track of a job (its process restarted in the middle). */
private const val INTERRUPTED = "interrupted"

object ApnCandidateJobs {
    /** A job followed until it ended or the wait ran out: the last answer, and how long the app waited. */
    data class Followed(val answer: DataAnswer?, val timedOut: Boolean, val waitedMs: Long) {
        val state: String? get() = answer?.str(OcdDataProtocol.STATE)

        /** The job's error (when it says more than its state) with its reason, cleaned ([ProviderReason]). */
        val why: String? get() = answer?.let { ProviderReason.of(it.error?.takeIf { e -> e != state }, it.str(OcdDataProtocol.REASON)) }
    }

    /**
     * Asks `install_status` for [job] every [ApnCandidateTiming.POLL_MS] until its state is in
     * [final], the provider no longer knows the job (a refusal without a state, or `interrupted`),
     * or [maxMs] have passed on [clock].
     */
    suspend fun follow(client: DatasetClient, job: String, final: Set<String>, maxMs: Long, clock: JobClock): Followed {
        val start = clock.uptimeMs()
        var a: DataAnswer? = null
        while (true) {
            a = client.installStatus(job)
            val state = a.str(OcdDataProtocol.STATE)
            val waited = clock.uptimeMs() - start
            if (!a.ok && state == null) return Followed(a, false, waited)
            if (state in final || state == INTERRUPTED) return Followed(a, false, waited)
            if (waited >= maxMs) return Followed(a, true, waited)
            clock.sleep(minOf(ApnCandidateTiming.POLL_MS, maxMs - waited))
        }
    }

    /** What came of `apn_candidate_set`. */
    sealed interface SetEnd {
        /** In place: the failed check runs next, then the restore. */
        data class InPlace(val job: String) : SetEnd

        /** Not run, with its line; [missingMethod]: this ROM build's CarrierSettings has no candidate set. */
        data class NotRun(val line: String, val missingMethod: Boolean = false) : SetEnd
    }

    /** What came of `apn_candidate_restore`: its line, and whether CarrierSettings reported `restore_failed` (a stop event). */
    data class RestoreEnd(val line: String, val accepted: Boolean, val failed: Boolean = false, val why: String? = null) {
        /** The line for a set the app gave up on: a refused restore leaves the set to CarrierSettings's own time. */
        fun said(): String = if (accepted) line else "$line; CarrierSettings restores it at its own time"
    }

    /**
     * The set for [subId] with [candidate] (an [ApnSetCandidate] as JSON), followed until it ends,
     * within [ApnCandidateTiming.SET_WAIT_MS] of the call. [placed] runs once CarrierSettings took
     * the job (it counts toward the caps). When the app gives up on a set that may be in place (the
     * time is up, CarrierSettings lost the job, or the work is stopped), it asks for the restore at
     * once ([restore]) instead of leaving it to CarrierSettings's own timeout.
     */
    suspend fun set(
        client: DatasetClient,
        subId: Int,
        candidate: String,
        clock: JobClock,
        placed: () -> Unit,
        restore: suspend () -> RestoreEnd,
    ): SetEnd {
        val start = clock.uptimeMs()
        val a = client.apnCandidateSet(subId, candidate)
        if (!a.ok) {
            return SetEnd.NotRun(
                ApnCandidateProtocol.setRefusal(a.error, a.str(OcdDataProtocol.WAITING_FOR) ?: ProviderReason.clean(a.str(OcdDataProtocol.REASON))),
                missingMethod = a.error == OcdDataProtocol.UNKNOWN_METHOD,
            )
        }
        val job = a.str(OcdDataProtocol.JOB_ID) ?: a.str(OcdDataProtocol.INSTALL_ID) ?: return SetEnd.NotRun("not run: no job ID")
        placed()
        val followed = try {
            follow(client, job, ApnCandidateProtocol.SET_FINAL, ApnCandidateTiming.SET_WAIT_MS - (clock.uptimeMs() - start), clock)
        } catch (e: CancellationException) {
            // The work was stopped while the set was being installed: the restore is asked for now, not at CarrierSettings's time.
            withContext(NonCancellable) { runCatching { restore() } }
            throw e
        }
        val state = followed.state
        return when {
            state == InstallState.IN_PLACE -> SetEnd.InPlace(job)
            followed.timedOut -> {
                val r = restore()
                SetEnd.NotRun(
                    "not run: the candidate set had not ended after ${followed.waitedMs / 1000} s (${state ?: "no state"}), " +
                        "${ApnCandidateTiming.MARGIN_MS / 1000} s before CarrierSettings's own timeout; the app asked for the restore at once: ${r.said()}",
                )
            }
            state == null || state == INTERRUPTED -> {
                val r = restore()
                SetEnd.NotRun("not run: CarrierSettings lost the candidate set's job (${followed.why ?: state ?: "no answer"}); the app asked for the restore at once: ${r.said()}")
            }
            // refused: CarrierSettings never installed it; apn_not_confirmed: it put the installed set back itself.
            else -> SetEnd.NotRun("not run: the candidate set did not go in place ($state${followed.why?.let { ", $it" } ?: ""})")
        }
    }

    /** `apn_candidate_restore`, followed until it ends, within [ApnCandidateTiming.RESTORE_WAIT_MS]. [accepted] runs when CarrierSettings took it. */
    suspend fun restore(client: DatasetClient, clock: JobClock, accepted: () -> Unit): RestoreEnd {
        val a = client.apnCandidateRestore()
        if (!a.ok) return RestoreEnd("refused (${ProviderReason.of(a.error ?: "no answer", a.str(OcdDataProtocol.REASON))})", accepted = false)
        accepted()
        val job = a.str(OcdDataProtocol.JOB_ID) ?: a.str(OcdDataProtocol.INSTALL_ID) ?: return RestoreEnd("asked, without a job ID", accepted = true)
        val f = follow(client, job, ApnCandidateProtocol.RESTORE_FINAL, ApnCandidateTiming.RESTORE_WAIT_MS, clock)
        val state = f.state
        return when {
            state == InstallState.RESTORE_FAILED -> RestoreEnd("restore_failed" + (f.why?.let { " ($it)" } ?: ""), accepted = true, failed = true, why = f.why)
            f.timedOut -> RestoreEnd(
                "not ended after ${f.waitedMs / 1000} s ($state" +
                    (f.answer?.str(OcdDataProtocol.WAITING_FOR)?.let { ", waiting for ${InstallRefusal.waiting(it)}" } ?: "") + "); CarrierSettings finishes it",
                accepted = true,
            )
            state == InstallState.RESTORED -> RestoreEnd(state, accepted = true)
            else -> RestoreEnd((state ?: "no answer") + (f.why?.let { " ($it)" } ?: ""), accepted = true, why = f.why)
        }
    }
}
