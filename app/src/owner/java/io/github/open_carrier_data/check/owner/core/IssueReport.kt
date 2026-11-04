package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.PrivacyScan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/**
 * The owner's "upload": a GitHub issue URL on OCD's public repository,
 * prefilled for its "OCD Android report" form with exactly one JSON block, a
 * closed pointer report. Each result names an entry of the OCD data's
 * closed list ([Pointers]: `volte:on`, `apn:<row key>`, ...) and the result
 * class the phone measured, never a value. The report follows the public
 * `schemas/ocd-check-report.schema.json` (`ocd-check-test-report`, version
 * 1) field for field; a copy of that schema is in this build's test
 * resources and `ReportSchemaTest` checks reports against it.
 *
 * The report names the data by its OCD commit and data digest (round 29
 * S13), the pinned main commit or the commit the bundled snapshot was built
 * from; a run recorded before round 29 still names its release tag.
 *
 * Since OCD's round 29 a result of volte:on, vowifi:on or sms_over_ims:on can
 * carry `settled` ([Settled]): the level, bearer, method and day this phone
 * measured under rounds 26 to 28, with the ROM build in `device.build`.
 *
 * This is the only way a result leaves the phone, on every build and for every
 * account (round 47, item 2): the person taps, reads the report ([ReportWords])
 * and submits the form in the browser under their own GitHub login, which the
 * app never holds. The app sends nothing itself. The public repository's bot checks
 * that the commit (or release) and every entry exist and records the report under
 * `observed/` as "reported, not checked by OCD": a report is a hint that
 * never moves an OCD value and never makes a phone skip its own test. It
 * holds typed values only, no IMSI, ICCID, IMEI, number, SIP URI, address,
 * time of day or free text, and [PrivacyScan] checks it again before the URL
 * is built. [hash] is the hash the bot posts for the same text.
 */
object IssueReport {
    const val NEW_ISSUE = "${DataSource.REPOSITORY_URL}/issues/new"
    const val TEMPLATE = "ocd-check-report.yml"

    /** The form's textarea the report goes in (`id: report`, rendered as JSON, so the issue shows it fenced). */
    const val FIELD = "report"
    const val TITLE_PREFIX = "[OCD Android report]"
    const val SCHEMA = "ocd-check-test-report"
    const val SCHEMA_VERSION = 1

    /**
     * GitHub answers 500 for a new-issue URL longer than about 7,000
     * characters, and, when the browser is not signed in, for the login page
     * whose return_to carries the URL encoded once more (measured 2026-10-20:
     * a 5,076-character report URL gave a 7,151-character login URL that
     * failed; 6,998 and 7,046 passed). Both stay under this; APN row results
     * are left out (and counted) to fit.
     */
    const val MAX_URL = 6_900
    private const val LOGIN = "https://github.com/login?return_to="

    /** The length of GitHub's login URL for [url], as a signed-out browser is sent there. */
    fun loginLength(url: String): Int = LOGIN.length + URLEncoder.encode(url, "UTF-8").length

    /** Values the privacy scan's phone heuristics skip: an all-digit hash tail or GID1 prefix is not a number. */
    private val HEX_FIELDS = PrivacyScan.hexFields + setOf(
        "report.ocd_profile", "report.sim.gid1_prefix", "report.release.data_digest", "report.release.commit",
        // "CP2A.260605.016 1792814506" has a phone number's shape; it is a fixed-shape field.
        "report.device.build",
    )

    private val DIGEST = Regex("sha256:[0-9a-f]{64}")

    fun title(run: RunResult, cell: ReportCell): String =
        "$TITLE_PREFIX ${cell.model} on ${run.sim?.mccmnc ?: "?"}" + (cell.spn?.let { " ($it)" } ?: "")

    /**
     * Every entry the run found: the five switches' on and off, then OCD's APN rows. [settled]:
     * the settled results by entry ([Settled.forRun]), attached as rounds 26 to 28 read them.
     */
    fun results(run: RunResult, calls: List<CallsTested>, settled: Map<String, SettledLevel> = emptyMap()): Pair<List<EntryResult>, Int> {
        val mccmnc = run.sim?.mccmnc
        val features = run.features.flatMap { f ->
            // Round 26 rule change 1: T2 is the machine evidence of L3 (calls both ways), measured by this phone.
            val t2 = f.feature in Ladders.CALL_TESTED && calls.any { it.feature == f.feature && it.level == CallsLevel.WORKS && (it.mccmnc == null || it.mccmnc == mccmnc) }
            Pointers.entries(f, t2).map { Settled.apply(it, settled[it.entry]) }
        }
        val rows = run.apns.filter { it.apnKey.isNotEmpty() }
        val apns = rows.mapNotNull(Pointers::apn).distinctBy { it.entry }
        return features + apns to (rows.size - apns.size)
    }

    /**
     * The pointer report of [run]. [cell] names the phone, software, SIM and
     * release (the run's own, or for older runs the same phone as it is now);
     * [calls] are this phone's measured call levels (round 26; works is T2). Null
     * without a SIM, without a data digest, or without a result.
     */
    fun report(
        run: RunResult,
        cell: ReportCell,
        calls: List<CallsTested>,
        leaveOutApns: Int = 0,
        settled: Map<String, SettledLevel> = emptyMap(),
        plan: ReportPlan = ReportPlan.Unknown,
    ): JsonObject? {
        val sim = run.sim ?: return null
        if (!DIGEST.matches(cell.dataDigest)) return null
        val (all, skipped) = results(run, calls, settled)
        val apnCount = all.count { it.entry.startsWith("apn:") }
        val drop = leaveOutApns.coerceAtMost(apnCount)
        val results = all.dropLast(drop)
        if (results.isEmpty()) return null
        return buildJsonObject {
            put("schema", SCHEMA)
            put("schema_version", SCHEMA_VERSION)
            putJsonObject("app") {
                put("name", "OCD Android")
                put("build", "owner")
                put("version", cell.appVersion)
            }
            put("tested_on", Instant.ofEpochMilli(run.startedMs).atOffset(ZoneOffset.UTC).toLocalDate().toString())
            putJsonObject("release") {
                // Round 29 S13: the data commit and its digest; a run before round 29 names its release tag.
                val commit = cell.dataCommit?.takeIf { DataSource.SHA.matches(it) }
                if (commit != null) put("commit", commit) else put("tag", cell.dataRelease?.takeIf { DataSource.RELEASE_TAG.matches(it) })
                put("data_digest", cell.dataDigest)
            }
            put("data_source", cell.dataSource)
            putJsonObject("device") {
                put("model", cell.model)
                put("codename", cell.codename)
                put("android_major", cell.androidMajor)
                put("rom_family", cell.romFamily)
                put("build_type", cell.buildType)
                put("ims_stack", cell.imsStack)
                cell.romBuild?.takeIf { Settled.BUILD.matches(it) }?.let { put("build", it) }
            }
            putJsonObject("sim") {
                put("mccmnc", sim.mccmnc)
                sim.carrierId?.takeIf { it > 0 }?.let { put("android_carrier_id", it) }
                cell.spn?.takeIf { it.isNotBlank() }?.let { put("spn", it) }
                if (run.profileId != null) cell.gid1Prefix?.let { put("gid1_prefix", it) }
            }
            put("ocd_profile", run.profileId)
            // Round 47 item 4: the person's own choice, default unknown. Never a key: nothing but this report reads it.
            put("plan", plan.wire)
            putJsonArray("results") {
                for (r in results) {
                    addJsonObject {
                        put("entry", r.entry)
                        // Without a profile OCD has no value to point at.
                        put("ocd", r.ocd && (run.profileId != null || r.entry.startsWith("apn:")))
                        put("phone", r.phone)
                        put("how", if (r.tested) "tested" else "observed")
                        put("result", r.result)
                        // Empty fields are left out, so the issue fits a URL.
                        r.reason?.let { put("reason", code(it)) }
                        if (r.calls.isNotEmpty()) {
                            putJsonArray("call_types") {
                                for ((test, network) in r.calls) {
                                    addJsonObject {
                                        put("test", test)
                                        put("network", network)
                                    }
                                }
                            }
                        }
                        if (r.kept && r.tested && r.passed) put("kept", true)
                        r.method?.let { put("method", code(it)) }
                        r.restored?.let { put("restored", it) }
                        r.settled?.let { s ->
                            putJsonObject("settled") {
                                put("level", s.level)
                                s.bearer?.let { put("bearer", it) }
                                put("method", s.method)
                                put("measured_on", s.measuredOn)
                            }
                        }
                    }
                }
            }
            if (skipped + drop > 0) put("results_left_out", skipped + drop)
        }
    }

    /**
     * The report as the form shows it: one top-level key per line, and one
     * result per line. Compact enough for a URL, readable on the issue.
     */
    fun text(report: JsonObject): String = buildString {
        append("{\n")
        report.entries.forEachIndexed { index, (key, value) ->
            append("  ").append(JsonPrimitive(key)).append(": ")
            if (value is JsonArray && value.isNotEmpty() && value.all { it is JsonObject }) {
                append("[\n")
                value.forEachIndexed { i, element ->
                    append("    ").append(compact(element))
                    append(if (i < value.size - 1) ",\n" else "\n")
                }
                append("  ]")
            } else {
                append(compact(value))
            }
            append(if (index < report.size - 1) ",\n" else "\n")
        }
        append("}")
    }

    private fun compact(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)

    /**
     * The report's hash as the public bot computes it: SHA-256 of the text
     * with Unix line ends and no outer blank space (observed_reports.py
     * `report_hash`). The owner can compare it with the bot's comment.
     */
    fun hash(text: String): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The prefilled report: its URL and the JSON text in it. */
    data class Prefilled(val url: String, val text: String) {
        val hash: String get() = hash(text)
    }

    /** The prefilled issue, or null without a SIM, a data digest or a result, or when the privacy scan finds an identifier. */
    fun prefill(run: RunResult, cell: ReportCell, calls: List<CallsTested>, settled: Map<String, SettledLevel> = emptyMap(), plan: ReportPlan = ReportPlan.Unknown): Prefilled? {
        val title = title(run, cell)
        if (PrivacyScan.scanValue("title", title).isNotEmpty()) return null
        var leaveOut = 0
        while (true) {
            val report = report(run, cell, calls, leaveOut, settled, plan) ?: return null
            if (PrivacyScan.scan(report, hex = HEX_FIELDS).isNotEmpty()) return null
            val text = text(report)
            val url = NEW_ISSUE + "?" + linkedMapOf("template" to TEMPLATE, "title" to title, FIELD to text).entries
                .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            if (url.length <= MAX_URL && loginLength(url) <= MAX_URL) return Prefilled(url, text)
            if (leaveOut >= run.apns.size) return null
            leaveOut++
        }
    }

    fun url(run: RunResult, cell: ReportCell, calls: List<CallsTested>): String? = prefill(run, cell, calls)?.url

    /** A fixed code, as the schema wants it: lower case letters, digits and underscores. */
    internal fun code(text: String): String {
        val clean = text.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(64).trimEnd('_')
        return when {
            clean.isEmpty() -> "unknown"
            clean[0] in 'a'..'z' -> clean
            else -> ("r_$clean").take(64)
        }
    }
}

/**
 * The words around a report, one set for every build (round 47, item 2). The section says that nothing
 * goes by itself and how to send a report; the form is what the person reads next to the report itself
 * before the browser opens. OCD's public issue form says the same again above the prefilled report.
 */
object ReportWords {
    const val SECTION = "Reports"
    const val SECTION_TITLE = "Nothing is sent by itself"
    const val SECTION_TEXT = "Results stay on this phone. To send one, tap Open report on GitHub, read the report, and submit the form that opens in your browser. " +
        "It becomes a public issue under your own GitHub account. Without a GitHub account you can read the report but not send it"
    const val BUTTON = "Open report on GitHub"
    const val BUTTON_TEXT = "You read it first. Nothing is sent until you submit it"
    const val FORM_TITLE = "Read this report"
    const val FORM_TEXT = "This is everything the report says. The next step opens OCD's form in your browser with this report filled in, and nothing is sent until you submit it there. " +
        "The issue is public, under your own GitHub account: anyone can link your GitHub name to the carrier and any plan in this report. This app never holds your login. " +
        "If you delete the issue later, the report leaves OCD's current files at the next rebuild, but older versions stay in git history. " +
        "A report reads as \"reported, not checked by OCD\" and changes nothing on any other phone"
    const val PLAN_TITLE = "Your plan"
    const val PLAN_UNKNOWN = "Unknown"
    const val PLAN_TEXT = "Your own choice from the carrier's published list, for this report only. It changes nothing on this phone or any other"
    const val PLAN_NO_LIST = "This carrier has no published offer list yet, so unknown is the only choice"
    const val FORM_OPEN = "Open on GitHub"
    const val FORM_CANCEL = "Not now"
    const val HELD_BACK = "Report held back: it may contain private data, or the test has no data digest"
}

/**
 * Which IMS service the phone runs, from the packages that implement
 * android.telephony.ims.ImsService: PHH IMS (me.phh.ims), Samsung's
 * (com.sec.imsservice), Google's AOSP ImsStack (com.android.imsstack, the
 * S20 on LineageOS 24.0 since 2026-10-22), another one, or not readable. The
 * values are the public schemas' `ims_stack` enum.
 */
object ImsStack {
    fun of(packages: List<String>): String = when {
        packages.any { it.startsWith("me.phh.ims") } -> "phh_ims"
        packages.any { it == "com.sec.imsservice" } -> "samsung_ims"
        packages.any { it == "com.android.imsstack" } -> "aosp_imsstack"
        packages.isNotEmpty() -> "other"
        else -> "not_known"
    }
}
