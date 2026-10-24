package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * Automatic reports (OCD rule round 29, "corrections into the database"; the plan judge's
 * version: "the owner build may submit its pointer reports itself, with a fine-grained token
 * that can only create issues on the public OCD repository"). Optional and owner build only:
 * - the owner creates the token on GitHub and enters it on his phone; the app holds it in
 *   credential-encrypted storage, encrypted with a key in Android Keystore ([OwnerToken]),
 *   never logs it, and the owner can remove it at any time (or revoke it on GitHub);
 * - without a token nothing is sent, and "Open report on GitHub" opens the prefilled form as
 *   before, which also stays with a token;
 * - it sends only settled results ([Settled]): the newest run of each SIM with the entries
 *   that carry `settled`, after the same privacy scan, as an issue on the "OCD Check report"
 *   form through GitHub's REST API;
 * - a settled result is sent once, and again only when its level or outcome changes or after
 *   [RENEW_MS], so a record in observed/retested.json is renewed well before its 90 days end;
 * - a refusal (an expired or revoked token, a token without the permission) stops sending
 *   until the token is entered again, and the screen says so.
 * The read-only builds have none of this: the code is in src/owner only.
 */

/** One settled result the app sent: its key ([AutoReport.key]), when, and the issue number GitHub gave it. */
@Serializable
data class SentResult(val key: String, val atMs: Long, val issue: Int? = null)

/** What GitHub answered to one issue the app sent. */
sealed interface PostResult {
    data class Created(val number: Int) : PostResult

    /** The token was refused or lacks the permission: 401, 403 without a rate limit, 404, 410, 422. */
    data class Refused(val code: Int) : PostResult

    /** Offline, a timeout, a 5xx or a rate limit: tried again later. */
    data class Failed(val why: String) : PostResult
}

/** Sends one issue with the owner's token; the Android side holds the token, this side never sees it. */
fun interface IssuePoster {
    fun post(title: String, body: String): PostResult
}

object AutoReport {
    val RENEW_MS: Long = TimeUnit.DAYS.toMillis(14)
    const val MAX_SENT = 200
    const val LABEL = "ocd-check-report"

    /** The key of one settled result: the run's cell (phone, ROM build, SIM, profile), the entry, and the level and outcome. */
    fun key(run: RunResult, cell: ReportCell, entry: String, s: SettledLevel): String =
        listOf(cell.model, cell.romBuild ?: "-", cell.imsStack, run.sim?.mccmnc ?: "-", run.sim?.carrierId ?: "-", run.profileId ?: "-", entry, s.key).joinToString("|")

    fun keys(run: RunResult, cell: ReportCell, settled: Map<String, SettledLevel>): List<String> = settled.map { (entry, s) -> key(run, cell, entry, s) }

    /** The keys of [keys] not sent within [RENEW_MS] (or sent in the future, by a clock that moved). */
    fun due(keys: List<String>, sent: List<SentResult>, nowMs: Long): List<String> =
        keys.filter { k -> sent.none { it.key == k && nowMs - it.atMs in 0 until RENEW_MS } }

    /** [sent] with [keys] recorded as sent now, the newest [MAX_SENT] kept. */
    fun record(sent: List<SentResult>, keys: List<String>, nowMs: Long, issue: Int?): List<SentResult> =
        (sent.filterNot { it.key in keys } + keys.map { SentResult(it, nowMs, issue) }).sortedBy { it.atMs }.takeLast(MAX_SENT)

    /** The newest run with test results for each SIM, newest first. */
    fun newestRuns(runs: List<RunResult>): List<RunResult> =
        runs.filter { it.features.isNotEmpty() && it.sim != null && it.cell != null }
            .groupBy { it.sim!!.subId to it.sim.mccmnc }
            .map { (_, list) -> list.maxBy { it.startedMs } }
            .sortedByDescending { it.startedMs }

    /** One issue to send: the run, its issue, and the keys it covers. */
    data class Due(val run: RunResult, val issue: IssueReport.Issue, val keys: List<String>)

    /**
     * What to send now: for each SIM's newest run, the automatic report of its settled results
     * ([settledOf]) when one of them is due. Nothing after GitHub refused the token
     * ([OwnerState.autoRefusedAtMs]): a refused token waits for the owner.
     */
    fun plan(state: OwnerState, calls: (RunResult) -> List<CallsTested>, settledOf: (RunResult) -> Map<String, SettledLevel>, nowMs: Long): List<Due> =
        if (state.autoRefusedAtMs != null) emptyList() else newestRuns(state.runs).mapNotNull { run ->
            val cell = run.cell ?: return@mapNotNull null
            val settled = settledOf(run).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val keys = keys(run, cell, settled)
            if (due(keys, state.autoSent, nowMs).isEmpty()) return@mapNotNull null
            val issue = IssueReport.automatic(run, cell, calls(run), settled) ?: return@mapNotNull null
            Due(run, issue, keys)
        }

    /** The state after [due] was sent with [result]: what is recorded, the screen's line and the log. Never the token. */
    fun after(state: OwnerState, due: Due, result: PostResult, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): OwnerState {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val entries = due.issue.text.let { text -> Regex("\"entry\":\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList() }.joinToString()
        return when (result) {
            is PostResult.Created -> state.copy(
                autoSent = record(state.autoSent, due.keys, nowMs, result.number),
                reportsOpened = (state.reportsOpened - due.issue.hash + due.issue.hash).takeLast(OwnerState.MAX_OPENED),
                autoLine = "Sent #${result.number} on $day",
            ).logged(nowMs, "report", "sent report #${result.number} automatically (${due.issue.hash}): $entries")
            is PostResult.Refused -> state.copy(
                autoLine = "GitHub refused the token (HTTP ${result.code}) on $day. Enter a new one; until then reports open in the browser",
                autoRefusedAtMs = nowMs,
            ).logged(nowMs, "report", "automatic report refused by GitHub: HTTP ${result.code}; nothing more is sent until a token is entered again")
            is PostResult.Failed -> state.copy(autoLine = "Not sent yet: ${result.why.take(80)}")
                .logged(nowMs, "report", "automatic report not sent, tried again later: ${result.why.take(120)}")
        }
    }
}

/**
 * The form of the owner's token: a fine-grained personal access token (github_pat_...). A
 * classic token (ghp_...) is refused, because it cannot be limited to one repository's issues.
 */
object TokenForm {
    private val FINE_GRAINED = Regex("github_pat_[A-Za-z0-9_]{20,255}")
    private val CLASSIC = Regex("(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,255}")

    /** Why [text] is not a usable token, or null. Never echoes the text. */
    fun problem(text: String): String? = when {
        text.isBlank() -> "Enter the token"
        CLASSIC.matches(text.trim()) -> "This is a classic token. Use a fine-grained token limited to Issues on open-carrier-data/open-carrier-data"
        !FINE_GRAINED.matches(text.trim()) -> "This doesn't look like a fine-grained GitHub token (github_pat_…)"
        else -> null
    }
}
