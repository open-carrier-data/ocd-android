package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Others' reports, the one way the adopted anti-fake design lets a phone use
 * them (the research "Make a fake report worth nothing" and the owner's
 * direction of 2026-10-20: a report points, phones prove). OCD's public
 * observed/summary.json (public PR #286) counts the valid OCD Check reports
 * per device × carrier profile × stack × entry, unverified. For this phone's
 * own cell, an entry that others reported working ("works", "signed in") or
 * contested (a clean measured failure) only moves earlier in the night's
 * rotation: it becomes due the next night, ahead of the plain rotation.
 *
 * What a report never does: add a test (only the plan's own entries, from
 * the OCD release, can move), skip, pause or remove one, change a kept
 * value or what a test keeps, or count toward "works" (nothing here reaches
 * the runner, the ladders, the headline or the ledger). The owner's own
 * reports, told by the hash the app showed when it opened them, are not
 * others' reports.
 */

object ObservedSource {
    private const val RAW = "https://raw.githubusercontent.com/${ReleaseSource.REPOSITORY}/main/observed"
    const val SUMMARY_URL = "$RAW/summary.json"
    const val SCHEMA_ASSET = "observed-summary.schema.json"

    /** The cap on summary.json, room for about 3,000 cells; the public one held none on 2026-10-20. */
    const val MAX_SUMMARY_BYTES = 2L * 1024 * 1024

    /** One report record (observed/reports/issue-N.json): a report is at most 30,000 characters. */
    const val MAX_RECORD_BYTES = 64L * 1024

    /** The report records read in one pull to tell the owner's own reports from others'. */
    const val MAX_RECORDS = 20

    fun recordUrl(issue: Int): String {
        require(issue in 1..999_999_999) { "issue number" }
        return "$RAW/reports/issue-$issue.json"
    }
}

class ObservedException(message: String) : Exception(message)

/** Round 19's cell: the phone (model and device name), the OCD profile the SIM matched, its network code, and the software stack. */
@Serializable
data class ObservedCell(
    val model: String,
    val codename: String,
    val carrierProfile: String?,
    val mccmnc: String,
    /** stock, lineageos or other. */
    val romFamily: String,
    /** phh_ims, samsung_ims, aosp_imsstack, other or not_known. */
    val imsStack: String,
    val androidMajor: Int,
) {
    companion object {
        /** The phone's own cell for one SIM, named as its test report names it (the public tool's cell_of). */
        fun of(cell: ReportCell, profileId: String?, mccmnc: String) =
            ObservedCell(cell.model, cell.codename, profileId, mccmnc, cell.romFamily, cell.imsStack, cell.androidMajor)
    }
}

/** What others reported for one entry of one cell, with the owner's own reports taken out. Counts only order tests. */
@Serializable
data class ReportPointer(
    val cell: ObservedCell,
    /** volte:on, vowifi:off, apn:<row key>, ...: an entry of a release's closed list, never a value. */
    val entry: String,
    val works: Int = 0,
    val signedIn: Int = 0,
    /** Others' counted reports of a clean measured failure. */
    val contested: Int = 0,
    /** The newest report of the entry, as the summary dates it (UTC). */
    val lastReport: String,
) {
    val moves: Boolean get() = works > 0 || signedIn > 0 || contested > 0
    val reportDay: LocalDate? get() = runCatching { LocalDate.parse(lastReport) }.getOrNull()
}

/** The last summary that passed its checks: when, its hash, its size, and what points at this phone model's entries. */
@Serializable
data class ObservedInfo(
    val fetchedAtMs: Long,
    /** sha256:<hex> of the summary as fetched. */
    val sha256: String,
    val cells: Int,
    /** The summary's counted reports (every phone). */
    val counted: Int,
    /** This phone model's entries that others reported working or contested. */
    val pointers: List<ReportPointer> = emptyList(),
    /** The owner's own counted reports taken out. */
    val own: Int = 0,
)

sealed interface ObservedPull {
    /** A summary that passed: the state takes [info], the app's folder [text]. [unchecked]: report records that could not be read to tell the owner's own. */
    data class Fetched(val info: ObservedInfo, val text: String, val unchecked: Int = 0) : ObservedPull

    /** Nothing taken: the previous copy stays. */
    data class Kept(val reason: String) : ObservedPull
}

@Serializable
private data class SummaryFile(val reports: SummaryCounts, val cells: List<SummaryCell>)

@Serializable
private data class SummaryCounts(val counted: Int)

@Serializable
private data class SummaryDevice(val model: String, val codename: String)

@Serializable
private data class SummaryStack(
    @SerialName("rom_family") val romFamily: String,
    @SerialName("ims_stack") val imsStack: String,
    @SerialName("android_major") val androidMajor: Int,
)

@Serializable
private data class SummaryReported(val results: Map<String, Int>, @SerialName("last_report") val lastReport: String, val issues: List<Int>)

@Serializable
private data class SummaryContested(val count: Int, @SerialName("last_report") val lastReport: String, val issues: List<Int>)

@Serializable
private data class SummaryCell(
    val device: SummaryDevice,
    @SerialName("carrier_profile") val carrierProfile: String? = null,
    val mccmnc: String,
    val stack: SummaryStack,
    val entry: String,
    val reported: SummaryReported? = null,
    val contested: SummaryContested? = null,
) {
    val cell: ObservedCell get() = ObservedCell(device.model, device.codename, carrierProfile, mccmnc, stack.romFamily, stack.imsStack, stack.androidMajor)
    val moves: Boolean
        get() = (reported?.results?.get(ObservedReports.WORKS) ?: 0) > 0 || (reported?.results?.get(ObservedReports.SIGNED_IN) ?: 0) > 0 || contested != null
    val issues: List<Int> get() = reported?.issues.orEmpty() + contested?.issues.orEmpty()
}

object ObservedReports {
    const val WORKS = "works"
    const val SIGNED_IN = "signed_in"
    const val LOG_KIND = "observed"

    /** The public report schema's entry: a feature switch and value, or apn: and a row key. */
    val ENTRY = Regex(
        "^((volte|vowifi|sms_over_ims|video_calling|vonr):(on|off)|apn:[a-z0-9][a-z0-9._-]{0,62}\\|[a-z_*]{1,16}(,[a-z_*]{1,16}){0,15}\\|(IP|IPV6|IPV4V6|PPP|NON-IP|UNSTRUCTURED)?)$",
    )
    private val HASH = Regex("sha256:[0-9a-f]{64}")
    private val json = Json { ignoreUnknownKeys = true }

    /** A report the app opened, as its log line names it (builds before the list in the state). */
    private val OPENED_LINE = Regex("opened the prefilled OCD Check report (sha256:[0-9a-f]{64})")

    /** The hashes of the reports this app opened: the owner's own reports. */
    fun ownHashes(state: OwnerState): Set<String> =
        state.reportsOpened.toSet() + state.log.filter { it.kind == "report" }.mapNotNull { OPENED_LINE.find(it.text)?.groupValues?.get(1) }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The summary's cells, after the size cap, a strict parse and its public schema. Throws [ObservedException]. */
    private fun parse(bytes: ByteArray, schema: JsonObject): SummaryFile {
        if (bytes.size > ObservedSource.MAX_SUMMARY_BYTES) throw ObservedException("larger than ${ObservedSource.MAX_SUMMARY_BYTES / 1024} KB")
        val text = utf8(bytes) ?: throw ObservedException("not UTF-8")
        val element = runCatching { Json.parseToJsonElement(text) }.getOrElse { throw ObservedException("not JSON") }
        val problems = SchemaSubset.problems(element, schema)
        if (problems.isNotEmpty()) throw ObservedException("does not fit its schema (${problems.size} problem(s), the first: ${problems.first().take(120)})")
        return json.decodeFromJsonElement(SummaryFile.serializer(), element)
    }

    /** One result of a report record: enough to take the owner's own report out of the counts. */
    private data class RecordResult(val entry: String, val result: String, val cleanFailure: Boolean)

    private data class IssueRecord(val issue: Int, val hash: String, val counted: Boolean, val results: List<RecordResult>)

    /** observed/reports/issue-N.json, the fields this needs; null when it is not that issue's record. */
    private fun record(bytes: ByteArray, issue: Int): IssueRecord? = runCatching {
        if (bytes.size > ObservedSource.MAX_RECORD_BYTES) return null
        val o = Json.parseToJsonElement(utf8(bytes) ?: return null).jsonObject
        if (o["issue"]?.jsonPrimitive?.intOrNull != issue) return null
        val hash = (o["report_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { HASH.matches(it) } ?: return null
        val status = (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val results = o.getValue("report").jsonObject.getValue("results").jsonArray.map { item ->
            val r = item.jsonObject
            val result = r.getValue("result").jsonPrimitive.content
            val reason = r["reason"]?.jsonPrimitive?.contentOrNull
            val restored = r["restored"]?.jsonPrimitive?.booleanOrNull
            RecordResult(r.getValue("entry").jsonPrimitive.content, result, result == Pointers.FAILED && reason !in Pointers.UNCLEAN && restored != false)
        }
        IssueRecord(issue, hash, status == "counted", results)
    }.getOrNull()

    /** The cells' entries others reported working or contested: the summary's counts less the owner's own counted reports. */
    private fun pointers(cells: List<SummaryCell>, own: List<IssueRecord>): List<ReportPointer> = cells.mapNotNull { c ->
        fun ownCount(issues: List<Int>?, test: (RecordResult) -> Boolean) =
            own.count { r -> issues.orEmpty().contains(r.issue) && r.results.any { it.entry == c.entry && test(it) } }
        val works = (c.reported?.results?.get(WORKS) ?: 0) - ownCount(c.reported?.issues) { it.result == WORKS }
        val signedIn = (c.reported?.results?.get(SIGNED_IN) ?: 0) - ownCount(c.reported?.issues) { it.result == SIGNED_IN }
        val contested = (c.contested?.count ?: 0) - ownCount(c.contested?.issues) { it.cleanFailure }
        val last = listOfNotNull(c.reported?.lastReport, c.contested?.lastReport).maxOrNull() ?: return@mapNotNull null
        ReportPointer(c.cell, c.entry, works.coerceAtLeast(0), signedIn.coerceAtLeast(0), contested.coerceAtLeast(0), last).takeIf { it.moves }
    }

    /**
     * Fetches observed/summary.json with [fetch] (a URL and a byte cap) and
     * checks it: the cap, strict UTF-8 JSON, OCD's public schema ([schema]).
     * Keeps the cells of this phone model ([model], [codename]) whose entry
     * is an entry of the closed list, and takes the owner's own counted
     * reports out (their records, by the hashes in [ownHashes]). Anything
     * that fails leaves [ObservedPull.Kept]: the previous copy stays.
     */
    fun pull(
        fetch: (url: String, maxBytes: Long) -> ByteArray,
        schema: JsonObject,
        model: String,
        codename: String,
        ownHashes: Set<String>,
        nowMs: Long,
    ): ObservedPull {
        val bytes = try {
            fetch(ObservedSource.SUMMARY_URL, ObservedSource.MAX_SUMMARY_BYTES)
        } catch (e: Exception) {
            return ObservedPull.Kept("not fetched (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(80) }.orEmpty()})")
        }
        val summary = try {
            parse(bytes, schema)
        } catch (e: ObservedException) {
            return ObservedPull.Kept(e.message ?: "not readable")
        } catch (e: Exception) {
            return ObservedPull.Kept("not readable (${e.javaClass.simpleName})")
        }
        val mine = summary.cells.filter { it.device.model == model && it.device.codename == codename && ENTRY.matches(it.entry) }
        val issues = if (ownHashes.isEmpty()) emptyList() else mine.filter { it.moves }.flatMap { it.issues }.distinct().sortedDescending()
        var unchecked = (issues.size - ObservedSource.MAX_RECORDS).coerceAtLeast(0)
        val own = mutableListOf<IssueRecord>()
        for (issue in issues.take(ObservedSource.MAX_RECORDS)) {
            val record = runCatching { record(fetch(ObservedSource.recordUrl(issue), ObservedSource.MAX_RECORD_BYTES), issue) }.getOrNull()
            when {
                // Not readable: counted as someone else's, which can only move a test earlier.
                record == null -> unchecked++
                record.counted && record.hash in ownHashes -> own += record
            }
        }
        val info = ObservedInfo(
            fetchedAtMs = nowMs,
            sha256 = sha256(bytes),
            cells = summary.cells.size,
            counted = summary.reports.counted,
            pointers = pointers(mine, own),
            own = own.size,
        )
        return ObservedPull.Fetched(info, utf8(bytes)!!, unchecked)
    }

    /** Saves a fetched summary as the app's copy; a summary that did not pass, or a save that failed, leaves the previous copy. */
    fun keep(pull: ObservedPull, files: ObservedStore): ObservedPull = when (pull) {
        is ObservedPull.Kept -> pull
        is ObservedPull.Fetched -> runCatching { files.save(pull.text) }.fold({ pull }, { ObservedPull.Kept("not saved (${it.message?.take(80)})") })
    }

    /** The log line of a pull. */
    fun line(pull: ObservedPull, model: String): String = when (pull) {
        is ObservedPull.Kept -> "observed/summary.json kept the previous copy: ${pull.reason}"
        is ObservedPull.Fetched -> {
            val info = pull.info
            val entries = info.pointers.size
            "observed/summary.json ${info.sha256.take(19)}: ${info.cells} cell(s), ${info.counted} counted report(s); " +
                (if (entries == 0) "nothing others reported for $model" else "$entries test(s) of $model others reported working or contested") +
                (if (info.own > 0) "; ${info.own} own report(s) left out" else "") +
                (if (pull.unchecked > 0) "; ${pull.unchecked} report(s) not checked for the owner's own" else "")
        }
    }

    /**
     * The state after a pull: a fetched summary replaces [OwnerState.observed]
     * and nothing else; a kept one leaves it. [verbose] (the owner's "Check
     * for update") logs every pull; the background logs only what is new.
     */
    fun applied(state: OwnerState, pull: ObservedPull, model: String, nowMs: Long, verbose: Boolean): OwnerState {
        val text = line(pull, model)
        val next = if (pull is ObservedPull.Fetched) state.copy(observed = pull.info) else state
        val news = when (pull) {
            is ObservedPull.Fetched -> state.observed?.sha256 != pull.info.sha256 || state.observed?.pointers != pull.info.pointers
            is ObservedPull.Kept -> state.log.lastOrNull { it.kind == LOG_KIND }?.text != text
        }
        return if (verbose || news) next.logged(nowMs, LOG_KIND, text) else next
    }

    /** This phone's pointers for one SIM's cell, by entry. */
    fun forCell(info: ObservedInfo?, cell: ObservedCell): Map<String, ReportPointer> =
        info?.pointers.orEmpty().filter { it.cell == cell && it.moves }.associateBy { it.entry }

    /**
     * The report is not answered yet: the phone has not tested the entry on a
     * later (UTC) day than the newest report, so the next night tests it
     * first. One test answers it; the entry then goes back to its rotation.
     */
    fun unanswered(pointer: ReportPointer, last: EntryRecord?): Boolean {
        if (!pointer.moves) return false
        val day = pointer.reportDay ?: return false
        if (last == null) return true
        return !Instant.ofEpochMilli(last.atMs).atZone(ZoneOffset.UTC).toLocalDate().isAfter(day)
    }

    /** A SIM's cell and the entries its OCD release has for it (the five switches' values and the SIM's OCD access point names). */
    data class SimCell(val sim: SimKey, val name: String, val cell: ObservedCell, val entries: Set<String>)

    /** "VoLTE on", "Wi-Fi calling off", "the access point name web.vodafone.de (default, dun)". */
    fun name(entry: String): String {
        if (entry.startsWith(EntryRecord.APN + ":")) {
            val parts = entry.substringAfter(':').split('|')
            return "the access point name ${parts[0]}" + (parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { " (${it.replace(",", ", ")})" }.orEmpty())
        }
        val feature = OwnerFeature.ofWire(entry.substringBefore(':')) ?: return entry
        val label = if (feature == OwnerFeature.VIDEO_CALLING) "video calling" else feature.label
        return "$label ${entry.substringAfter(':')}"
    }

    /**
     * Details: one plain line per entry others reported working or contested
     * on this phone model, and what the phone does about it, for example
     * "Others reported VoLTE on works on this phone model — tested first
     * tonight". Only entries of the SIM's own release list; the order and the
     * waits are the night plan's ([QuietPlan.plan]).
     */
    fun lines(cells: List<SimCell>, state: OwnerState, nowMs: Long): List<String> {
        data class Row(val c: SimCell, val pointer: ReportPointer, val last: EntryRecord?)
        val rows = cells.flatMap { c ->
            forCell(state.observed, c.cell).values.filter { it.entry in c.entries }
                .sortedBy { order(it.entry) }
                .map { p -> Row(c, p, state.entries.lastOrNull { it.sim == c.sim && it.entry == p.entry }) }
        }
        val onOnly = (state.onOnlyUntilMs ?: 0) > nowMs
        fun undone(r: Row) = state.undone.any { it.sim == r.c.sim && it.entry == r.pointer.entry }
        fun seen(r: Row) = r.last != null && r.last.observed && nowMs - r.last.atMs < DAY_MS
        fun paused(r: Row) = r.last != null && r.last.failures >= QuietPlan.PAUSE_AFTER_FAILURES && nowMs - r.last.atMs < QuietPlan.PAUSE_MS
        fun waiting(r: Row) = !undone(r) && !seen(r) && !paused(r) && unanswered(r.pointer, r.last)
        // The night takes one feature: the one tested longest ago goes first, as in the plan.
        val first = rows.filter { waiting(it) && !(onOnly && it.pointer.entry.endsWith(":off")) }.minByOrNull { it.last?.atMs ?: 0L }
            ?.let { it.c.sim to it.pointer.entry.substringBefore(':') }
        return rows.map { r ->
            val p = r.pointer
            val claim = when {
                p.works > 0 && p.contested > 0 -> "works, and others that it fails,"
                p.signedIn > 0 && p.contested > 0 -> "signs in, and others that it fails,"
                p.works > 0 -> "works"
                p.signedIn > 0 -> "signs in"
                else -> "fails"
            }
            val tail = when {
                undone(r) -> "not tested at night since you undid it"
                seen(r) -> "checked on this phone without a test"
                paused(r) -> "paused on this phone after two failed tests"
                !unanswered(p, r.last) -> if (r.last?.works == null) "tried on this phone since, not decided yet" else "tested on this phone since"
                !state.scheduleOn -> "tested when you tap Test now, night tests are off"
                first == r.c.sim to p.entry.substringBefore(':') && !(onOnly && p.entry.endsWith(":off")) -> "tested first tonight"
                else -> "tested on a later night"
            }
            (if (cells.size > 1) "${r.c.name}: " else "") + "Others reported ${name(p.entry)} $claim on this phone model — $tail"
        }
    }

    private fun order(entry: String): Int = QuietPlan.FEATURE_ENTRIES.indexOf(entry).let { if (it < 0) QuietPlan.FEATURE_ENTRIES.size else it }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/** The app's copy of the last summary that passed, in its private files folder; replaced only by one that passed, atomically. */
class ObservedStore(private val dir: File) {
    val file: File get() = File(dir, "summary.json")

    fun save(text: String) {
        dir.mkdirs()
        val tmp = File(dir, "summary.json.tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("observed summary not replaced")
    }
}
