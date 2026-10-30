package io.github.open_carrier_data.check.owner

import android.content.Context
import android.os.SystemClock
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.owner.core.ApnCandidateData
import io.github.open_carrier_data.check.owner.core.ApnCandidateGate
import io.github.open_carrier_data.check.owner.core.ApnCandidateJobs
import io.github.open_carrier_data.check.owner.core.ApnCandidateProtocol
import io.github.open_carrier_data.check.owner.core.ApnCandidates
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ApnRowKeys
import io.github.open_carrier_data.check.owner.core.ApnSelection
import io.github.open_carrier_data.check.owner.core.ApnSetCandidate
import io.github.open_carrier_data.check.owner.core.ApnTrial
import io.github.open_carrier_data.check.owner.core.ApnTrialReview
import io.github.open_carrier_data.check.owner.core.ApnTrialVerdict
import io.github.open_carrier_data.check.owner.core.CandidateInput
import io.github.open_carrier_data.check.owner.core.CapEvent
import io.github.open_carrier_data.check.owner.core.D7Age
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.JobClock
import io.github.open_carrier_data.check.owner.core.LeadFailure
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.Notice
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.ProfileApnRows
import io.github.open_carrier_data.check.owner.core.ProviderReason
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.TestCaps
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Round 32 (D8 and D7 rule 4) with the restore candidates of rounds 33, 34 and 36: after a measured
 * failure on a tester phone, the candidates for the failing lead, each as a non-persistent trial
 * through CarrierSettings's temporary set ([ApnCandidateProtocol]): set, the failed check again,
 * restore. A failure counts only when the candidate passes; a winner opens a source review and
 * never changes the data by itself. On an SM8-calibrated stack, at home, outside calls (CarrierSettings's
 * timing refusals) and within the caps only. MMS is checked only by the free MMSC probe; no MMS is
 * ever sent, and no emergency number is ever dialled.
 */
object OwnerApnCandidates {
    private const val SETTLE_MS = 120_000L

    /** CarrierSettings's jobs are followed on the elapsed clock (0.4.3, [ApnCandidateJobs]). */
    private val clock = object : JobClock {
        override fun uptimeMs(): Long = SystemClock.elapsedRealtime()
        override suspend fun sleep(ms: Long) = delay(ms)
    }

    /**
     * Runs the candidates for [failure] on [sim]; returns the plain line for the log and the caller. Never throws.
     * The line is cleaned like a provider reason ([ProviderReason]): no credential, phone number, IMSI or ICCID.
     */
    suspend fun afterFailure(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, status: DatasetStatus, sim: OwnerSim, failure: LeadFailure): String {
        val line = try {
            run(context, store, phone, client, status, sim, failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "slot ${sim.slot + 1}: APN candidates not run (${e.javaClass.simpleName}: ${ProviderReason.clean(e.message, 120)})"
        }
        return (ProviderReason.clean(line, LINE_MAX) ?: line).also { l -> store.update { it.logged(System.currentTimeMillis(), "apn_candidate", l) } }
    }

    private const val LINE_MAX = 2_000

    private suspend fun run(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, status: DatasetStatus, sim: OwnerSim, failure: LeadFailure): String {
        val slot = "slot ${sim.slot + 1}"
        val state = store.read()
        val now = System.currentTimeMillis()
        val gate = ApnCandidateGate.Inputs(
            status = status,
            activeTests = OwnerTester.policy(state).activeTests,
            roaming = QuietReader.roaming(context),
            reregistrationsLeft = TestCaps.reregistrationsLeft(state.caps, sim.key, now),
            stop = state.stop,
            failure = failure,
            mmsEnded = MmsProbe.ended(OwnerMms.ended(context), sim.identity.mccmnc) != null,
        )
        ApnCandidateGate.refusal(gate)?.let { return "$slot: ${failure.plain}; APN candidates not run: $it" }
        val commit = status.installed?.commit ?: return "$slot: ${failure.plain}; APN candidates not run: no installed dataset"
        // A ROM build whose CarrierSettings has no candidate set: nothing is read or computed there.
        if (state.apnCandidateMissingOn != null && state.apnCandidateMissingOn == status.build.incremental) {
            return "$slot: ${failure.plain}; APN candidates ${ApnCandidateProtocol.setRefusal(OcdDataProtocol.UNKNOWN_METHOD, null)}"
        }
        if (ApnTrial.lookedAtRecently(state.apnTrials, sim.key, commit, failure, now)) return "$slot: ${failure.plain}; its APN candidates were looked at within the last day on this data"
        val input = withContext(Dispatchers.IO) { input(context, store, client, status, sim, failure, commit) }
            ?: return "$slot: ${failure.plain}; APN candidates not run: the installed commit's dataset is not on this phone"
        val (candidateInput, profileId) = input
        val all = ApnCandidates.forFailure(candidateInput)
        val due = all.filter { ApnTrial.due(store.read().apnTrials, sim.key, commit, it.id) }.take(ApnCandidateGate.MAX_PER_FAILURE)
        if (due.isEmpty()) {
            val why = if (all.isEmpty()) "OCD's data gives no APN candidate for ${failure.role.plain}" else "every APN candidate was tried on this data"
            // Kept, so the same failure is looked at again only after a day or on new data.
            store.update { s -> s.copy(apnTrials = ApnTrial.add(s.apnTrials, ApnTrial(System.currentTimeMillis(), sim.subId, sim.identity.mccmnc, commit, failure, ApnTrial.NONE, ApnTrial.NONE, "", why, ApnTrial.NOT_RUN, why))) }
            return "$slot: ${failure.plain}; $why"
        }
        val lines = mutableListOf<String>()
        for (c in due) {
            val left = TestCaps.reregistrationsLeft(store.read().caps, sim.key, System.currentTimeMillis())
            if (left < ApnCandidateGate.REREGISTRATIONS) {
                lines += "the re-registration cap is reached; the next candidate waits for the next measured failure"
                break
            }
            val (outcome, line) = trial(context, store, phone, client, sim, failure, c, status.build.incremental)
            store.update { s ->
                s.copy(apnTrials = ApnTrial.add(s.apnTrials, ApnTrial(System.currentTimeMillis(), sim.subId, sim.identity.mccmnc, commit, failure, c.id, c.kind, c.rule, c.plain, outcome, line)))
            }
            lines += line
            if (outcome == ApnTrial.PASSED) {
                val review = ApnTrialReview.of(profileId ?: sim.identity.mccmnc, c, failure, commit, System.currentTimeMillis())
                store.update { s -> if (s.pendingReviews.any { it.key == review.key } || review.key in s.reviewsSent) s else s.copy(pendingReviews = (s.pendingReviews + review).takeLast(40)) }
                break
            }
            // A provider without the method, or one that refused, runs no further candidate now.
            if (outcome == ApnTrial.NOT_RUN) break
        }
        OwnerDatasetStep.publish(context, store)
        return "$slot: ${failure.plain}; APN candidates: ${lines.joinToString("; ")}"
    }

    /** What the candidates are computed from: the installed commit's dataset on this phone, the profile's JSON and, for D7, the left-out scopes. */
    private fun input(context: Context, store: StateStore, client: DatasetClient, status: DatasetStatus, sim: OwnerSim, failure: LeadFailure, commit: String): Pair<CandidateInput, String?>? {
        val state = store.read()
        val pulled = state.pulled?.takeIf { it.commit == commit } ?: return null
        val dir = OwnerDatasetStep.dir(context, pulled) ?: return null
        val networkRows = File(dir, "apns-conf.xml").bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), sim.identity.mccmnc) }
        val selection = ApnSelection.of(networkRows, sim.identity)
        val explain = ExplainView.parse(sim.subId, client.explain(sim.subId))
        val data = OwnerData(context, store)
        val bundle = data.bundle()
        val resolution = Matcher.resolve(bundle, sim.identity)
        val profileId = explain?.apnProfile?.takeIf { ApnCandidateData.PROFILE_ID.matches(it) } ?: resolution.mostSpecific?.profileId
        val writtenKeys = profileId?.let { id -> runCatching { ApnCandidateData.writtenKeys(File(dir, "apn-index.json").readText(), id) }.getOrNull() }.orEmpty().toSet()
        val written = networkRows.filter { ApnRowKeys.rowKey(it.attributes) in writtenKeys }
        val fetch = data.fetch()
        val profileRows = profileId?.let { id -> runCatching { ApnCandidateData.profile(fetch, commit, id) }.getOrNull() }?.let(ProfileApnRows::records).orEmpty()
        val buildRows = ExplainView.parse(sim.subId, client.explain(sim.subId, "build"))?.rows.orEmpty()
        val profile = resolution.stack.firstOrNull { it.profileId == profileId } ?: resolution.mostSpecific
        val mvno = selection.any { it.mvnoType != null }
        val old = mvno && profile != null && profile.match.specificity > 0 && D7Age.old(profile.newestEntry, status.installed?.generatedAt ?: state.pin?.commitDate)
        // D7's left-out scope: only for a SIM on its plain scope that names a selector an old scope could have matched.
        val leftOut = if (!mvno && (sim.identity.spn != null || sim.identity.gid1 != null)) {
            runCatching { ApnCandidateData.leftOutScopes(fetch, commit, sim.identity.mccmnc, bundle.profilesFor(sim.identity.mccmnc).map { it.profileId }.toSet(), sim.identity) }.getOrDefault(emptyList())
        } else emptyList()
        return CandidateInput(
            role = failure.role,
            networkRows = networkRows,
            selection = selection,
            written = written,
            profileRows = profileRows,
            buildOrder = ApnCandidateData.buildOrder(buildRows, failure.role.type),
            oldMvnoScope = old,
            leftOutScopeRows = leftOut,
        ) to profileId
    }

    private fun count(store: StateStore, sim: OwnerSim) =
        store.update { it.copy(caps = TestCaps.add(it.caps, sim.key, CapEvent.REREGISTRATION, System.currentTimeMillis())) }

    /**
     * One candidate: the set, followed until CarrierSettings reports its end (0.4.3: within its own timeout less a
     * margin, and restored at once when the app gives up), the failed check again, the restore; the outcome and its line.
     */
    private suspend fun trial(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sim: OwnerSim, failure: LeadFailure, c: ApnSetCandidate, build: String): Pair<String, String> {
        val end = ApnCandidateJobs.set(client, sim.subId, ApnCandidateProtocol.encode(c), clock, placed = { count(store, sim) }, restore = { restore(context, store, client, sim) })
        if (end is ApnCandidateJobs.SetEnd.NotRun) {
            if (end.missingMethod) store.update { it.copy(apnCandidateMissingOn = build) }
            return ApnTrial.NOT_RUN to end.line
        }
        var passed: Boolean? = null
        try {
            passed = check(context, store, phone, sim, failure)
        } finally {
            withContext(NonCancellable) { restore(context, store, client, sim) }
        }
        return ApnTrialVerdict.outcome(passed) to ApnTrialVerdict.line(c, failure, passed)
    }

    /** The check the installed set failed, with the candidate in place: data validated, IMS registered, or the free MMSC probe (no MMS sent). */
    private suspend fun check(context: Context, store: StateStore, phone: OwnerPhone, sim: OwnerSim, failure: LeadFailure): Boolean? {
        if (failure == LeadFailure.MMSC_PROBE) {
            phone.sleep(10_000)
            val probe = OwnerMms.check(context, phone, sim)
            return when (probe.result) {
                "reachable" -> true
                "no_answer", "refused_no_mms_network", "no_mms_apn" -> false
                else -> null
            }
        }
        var waited = 0L
        while (true) {
            val o = TrialRunner.observe(phone, store, sim)
            val ok = when (failure) {
                LeadFailure.DATA_VALIDATION -> o.dataValidated
                LeadFailure.IMS -> o.imsRegistered
                LeadFailure.MMSC_PROBE -> null
            }
            if (ok == true || waited >= SETTLE_MS) return if (o.inService == false) null else ok
            phone.sleep(5_000)
            waited += 5_000
        }
    }

    /**
     * The restore, followed until it ends ([ApnCandidateJobs.restore]); anything but `restored` is logged. A restore that
     * does not show is a stop event (CarrierSettings put the last good set back).
     */
    private suspend fun restore(context: Context, store: StateStore, client: DatasetClient, sim: OwnerSim): ApnCandidateJobs.RestoreEnd {
        val r = ApnCandidateJobs.restore(client, clock) { count(store, sim) }
        if (r.line != InstallState.RESTORED) store.update { it.logged(System.currentTimeMillis(), "apn_candidate", "slot ${sim.slot + 1}: apn_candidate_restore ${r.line}") }
        if (r.failed) {
            store.update { it.copy(stop = it.stop ?: "an APN candidate set could not be restored").logged(System.currentTimeMillis(), "stop", "the APN candidate set could not be restored: ${r.why ?: InstallState.RESTORE_FAILED}") }
            OwnerNotifications.notice(context, Notice("Access point names restored by CarrierSettings", "A temporary access point name test did not come back as it was, so CarrierSettings installed the last good set again. Tests and changes are paused until you tap Resume.", failed = true))
        }
        return r
    }

    /** A candidate set left in place by a crash: restore it now (CarrierSettings restores one left by a reboot itself). */
    suspend fun clearLeftover(context: Context, store: StateStore, client: DatasetClient, status: DatasetStatus, sims: List<OwnerSim>): String? {
        if (!status.apnTestSet || status.apnTestSetKind?.contains("candidate") != true) return null
        val sim = sims.firstOrNull() ?: return null
        val r = restore(context, store, client, sim)
        return if (r.line == InstallState.RESTORED) "a leftover APN candidate set was restored" else "a leftover APN candidate set: apn_candidate_restore ${r.line}"
    }
}
