package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth

/*
 * The candidate trial (round 32, D8 and D7 rule 4, with the restore candidates of rounds 33, 34
 * and 36), through round 31 item 6's temporary-set path. The app asks CarrierSettings for the
 * candidate set of one SIM; CarrierSettings records the preferred APN by its fields, composes the
 * installed commit's file with the candidate's operations, checks it (APN version 8, the row count,
 * the SIM keeps its internet and IMS rows, its emergency rows unchanged, no other SIM's row
 * touched), stores it in its state, installs it and confirms it (`apn_candidate_set`). The app
 * runs the failed check again: mobile data validated, IMS registered, or the free MMSC probe, which
 * never sends an MMS. Then CarrierSettings restores the installed commit's file and the preferred
 * APN by its fields (`apn_candidate_restore`). A reboot with the set in place restores it at
 * LOCKED_BOOT_COMPLETED and is a stop event; a restore that does not show is a stop event too.
 *
 * A failure counts only when the candidate passes. A measured result never changes the file by
 * itself: on the owner's phones a winner opens a source review of that scope; on other phones it
 * stays on the phone (SM11). Both installs count toward the caps (6 OCD-caused IMS
 * re-registrations an hour, 30 a day). No emergency number is ever dialled and no MMS is ever sent.
 */

object ApnCandidateProtocol {
    private val json = Json { encodeDefaults = false }

    /** The candidate as CarrierSettings receives it (the `candidate` extra). */
    fun encode(c: ApnSetCandidate): String = json.encodeToString(ApnSetCandidate.serializer(), c)

    /** The answer of a client or provider that does not know the method: no candidate runs. */
    fun unknown(method: String) = DataAnswer(
        JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(OcdDataProtocol.UNKNOWN_METHOD), "reason" to JsonPrimitive("unknown method $method"))),
    )

    /** What a refusal of `apn_candidate_set` means for the trial, in plain words. */
    fun setRefusal(error: String?, reason: String?): String = when (error) {
        OcdDataProtocol.UNKNOWN_METHOD -> "not run: this ROM build's CarrierSettings has no temporary APN candidate set yet (apn_candidate_set)"
        DataAnswer.ABSENT -> "not run: no dataset installer on this ROM build"
        "refused_now", "busy" -> "not run now: ${InstallRefusal.waiting(reason)}; tried again at the next measured failure"
        "candidate_invalid" -> "not run: CarrierSettings refused the candidate (${reason?.take(160) ?: "invalid"})"
        null -> "not run: no answer"
        else -> "not run: $error" + (reason?.let { " (${it.take(160)})" } ?: "")
    }

    /** The job states of the set and of its restore (as R31.9's). */
    val SET_FINAL = setOf(InstallState.IN_PLACE, InstallState.REFUSED, InstallState.APN_NOT_CONFIRMED)
    val RESTORE_FINAL = setOf(InstallState.RESTORED, InstallState.RESTORE_FAILED, InstallState.REFUSED)
}

/** One candidate trial (or why it did not run), kept in the state: a candidate is tried once per installed commit and SIM. */
@Serializable
data class ApnTrial(
    val atMs: Long,
    val subId: Int,
    val mccmnc: String,
    val commit: String,
    val failure: LeadFailure,
    val candidate: String,
    val kind: String,
    val rule: String,
    val plain: String,
    /** passed, failed, not_run. */
    val outcome: String,
    val line: String,
) {
    companion object {
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val NOT_RUN = "not_run"
        /** The candidate of a look that found none to try. */
        const val NONE = "none"
        const val MAX = 40

        fun add(trials: List<ApnTrial>, t: ApnTrial): List<ApnTrial> = (trials + t).takeLast(MAX)

        /** A failure's candidates are looked at once a day at most per SIM, commit and lead (each look reads the profile at the commit). */
        fun lookedAtRecently(trials: List<ApnTrial>, sim: SimKey, commit: String, failure: LeadFailure, nowMs: Long): Boolean =
            trials.any { it.subId == sim.subId && it.mccmnc == sim.mccmnc && it.commit == commit && it.failure == failure && nowMs - it.atMs in 0 until DAY_MS }

        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Whether [candidate] still needs a trial for this SIM on [commit]: never tried there, or only not run. */
        fun due(trials: List<ApnTrial>, sim: SimKey, commit: String, candidate: String): Boolean =
            trials.none { it.subId == sim.subId && it.mccmnc == sim.mccmnc && it.commit == commit && it.candidate == candidate && it.outcome != NOT_RUN }
    }
}

object ApnTrialVerdict {
    /**
     * The trial's outcome: the candidate passed the check the installed set failed, so that failure
     * counts; or the candidate failed too, so the failure does not count (an outage, the network),
     * and the installed set stays. Null [passed]: the check could not be read, nothing counts.
     */
    fun outcome(passed: Boolean?): String = when (passed) {
        true -> ApnTrial.PASSED
        false -> ApnTrial.FAILED
        null -> ApnTrial.NOT_RUN
    }

    fun line(c: ApnSetCandidate, failure: LeadFailure, passed: Boolean?): String = when (passed) {
        true -> "the failure counts: with ${c.plain} ${failure.check} (${c.rule}); the installed set is back, and a source review opens"
        false -> "the candidate failed too (${c.plain}): the failure does not count, the installed set is back"
        null -> "the candidate's check could not be read (${c.plain}); nothing counts, the installed set is back"
    }
}

/** The measured failures of a campaign's last trial that open the APN candidates (TrialJudge's reasons). */
object ApnFailures {
    fun of(verdict: TrialVerdict?): List<LeadFailure> {
        if (verdict?.outcome != TrialOutcome.MEASURED_FAIL) return emptyList()
        return listOfNotNull(
            LeadFailure.DATA_VALIDATION.takeIf { "mobile data no longer validated" in verdict.reason },
            LeadFailure.IMS.takeIf { "IMS lost its registration" in verdict.reason },
        )
    }
}

/** D7's age: an MVNO scope whose newest entry is more than three years before the data is D7-class (only old builds state it). */
object D7Age {
    fun old(newestEntry: String?, dataDate: String?): Boolean {
        val entry = newestEntry?.let { runCatching { YearMonth.parse(it.take(7)) }.getOrNull() } ?: return false
        val data = dataDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return false
        return entry.atEndOfMonth().isBefore(data.minusYears(3))
    }
}

/** A winner's source review (D8 rule 8, D7 rule 4): the scope, the failure and the candidate that passed; never a SIM identifier. */
object ApnTrialReview {
    fun of(profile: String, c: ApnSetCandidate, failure: LeadFailure, commit: String, nowMs: Long) = SourceReview(
        "apn_candidate|$profile|${c.id}|${commit.take(12)}",
        "${SourceReview.TITLE_PREFIX}APN candidate passed: ${c.kind} for $profile",
        "The OCD Check owner build measured a failure on `$profile` (data commit `$commit`): ${failure.plain}. " +
            "As a non-persistent trial through CarrierSettings's temporary set, ${c.plain} passed: ${failure.check}. " +
            "The installed set was restored at once; nothing changed in the data.\n\n" +
            "- Rule: ${c.rule}\n- Candidate: `${c.id}` (${c.kind})\n" +
            "- Operations: ${c.ops.joinToString { op -> op.op + (op.rowKey?.let { " $it" } ?: "") + (op.types?.let { " type=$it" } ?: "") + (op.attributes?.let { a -> " ${a["apn"]} [${a["type"]}]" } ?: "") }}\n",
        nowMs,
    )
}
