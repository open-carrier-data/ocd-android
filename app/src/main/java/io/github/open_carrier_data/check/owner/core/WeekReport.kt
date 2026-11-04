package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.PrivacyScan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * OCD rule round 48, item 2: what leaves a phone by itself. Nothing does, unless the person said yes. After that one
 * yes, and only on a ROM install, the app sends one closed week report per SIM to OCD's relay, only on an unmetered
 * network and never while roaming. It holds no identifier, no free text, no day or time and no call details, and the
 * person can stop it at any time. A hand install never sends by itself. No result, sent or tapped, ever sets a value on
 * any phone.
 *
 * This file decides; OwnerWeekReport.kt only reads the phone and the network. The report's fields are closed
 * (assets/ocd-week-report.schema.json, schema ocd-week-report v1), and anything else is refused whole. It never holds a
 * plan, bearer, codename, build, firmware, SPN, GID1, Android carrier ID, data commit or digest, report hash, reason,
 * method, day, call count, direction, duration, disconnect code, serving network, roaming state or any ID.
 */

/** The ROM builder's switch: ask or off, and no on state. Only the person's own yes starts a send. */
enum class WeekSwitch {
    ASK, OFF;

    companion object {
        /** The resource `ocd_week_report`. Anything but "ask", "on" included, is off. */
        fun of(value: String?): WeekSwitch = if (value?.trim() == "ask") ASK else OFF
    }
}

/** The one place a report goes, and the flag that can only stop it. */
object Relay {
    /**
     * Compiled in, never read from data or a resource: one bad commit must not be able to redirect phones. A placeholder
     * until the relay exists: `.invalid` never resolves (RFC 2606), and [ready] keeps the send closed on it.
     */
    const val URL = "https://relay.invalid/v1/week-report"

    /** Constant, because Android's default names the phone's model and build. */
    const val USER_AGENT = "ocd-android"
    const val MAX_BODY_BYTES = 4096

    /**
     * Read once per week at the branch name, as [MainCheck.MANIFEST_URL] is: a flip changes no dataset digest, so a pull by
     * SHA would never see it. It can only stop a send, so an unvalidated commit gains nothing.
     */
    const val FLAG_URL = "${DataSource.RAW}/${DataSource.BRANCH}/community/relay.json"
    const val MAX_FLAG_BYTES = 1024L

    /** The phone held main's newest data this long ago at most; older means no send, so nobody has to renew the flag. */
    val MAX_MAIN_AGE_MS: Long = TimeUnit.DAYS.toMillis(30)

    fun ready(url: String = URL): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrEmpty() && !uri.host.endsWith(".invalid")
    }.getOrDefault(false)

    /** Exactly `"relay_open": true` opens. Absent, false, another type or a broken file stays closed. */
    fun open(text: String): Boolean = runCatching {
        val flag = (Json.parseToJsonElement(text) as JsonObject)["relay_open"] as? JsonPrimitive
        flag != null && !flag.isString && flag.booleanOrNull == true
    }.getOrDefault(false)
}

/** ISO weeks in UTC: a week names no day, and every phone agrees where one ends. */
object WeekClock {
    val WEEK_MS: Long = TimeUnit.DAYS.toMillis(7)

    private fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate()

    /** 2026-W45. */
    fun of(ms: Long): String {
        val date = date(ms)
        return "%04d-W%02d".format(Locale.ROOT, date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    /** Monday 00:00 UTC of the week of [ms]. */
    fun startMs(ms: Long): Long = date(ms).with(DayOfWeek.MONDAY).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The last closed week at [ms]: the only one a report names. */
    fun closed(ms: Long): String = of(startMs(ms) - 1)
}

/** One SIM's passive results of one week, each entry once. Clears after sending, on the week's roll, and at once on off. */
@Serializable
data class WeekBuffer(val week: String, val sim: SimKey, val carrier: String, val results: Map<String, String>)

/** What the last week's step built, exactly as it was sent or would have been, for the entry screen. */
@Serializable
data class WeekLast(val week: String, val reports: List<String>, val sent: Int = 0, val why: String? = null)

@Serializable
data class WeekState(
    /** The question went out, was shown, or could not be: it never comes again on this install. */
    val asked: Boolean = false,
    /** The person's own active yes: only [WeekReports.answered] sets it. */
    val yes: Boolean = false,
    val yesAtMs: Long? = null,
    val buffers: List<WeekBuffer> = emptyList(),
    /** The closed week [sendAtMs] was drawn for. */
    val closedWeek: String? = null,
    /** The random moment of the running week. A missed week is dropped. */
    val sendAtMs: Long? = null,
    /** The closed week that is finished: sent, dropped or empty. */
    val doneWeek: String? = null,
    val last: WeekLast? = null,
    /** When the phone last held main's newest data. */
    val mainHeldAtMs: Long? = null,
)

/** The phone's part of a report. */
data class WeekCell(val model: String, val romFamily: String, val androidMajor: Int, val imsStack: String, val appVersion: String)

object WeekReports {
    const val SCHEMA = "ocd-week-report"
    const val SCHEMA_VERSION = 1
    const val SCHEMA_ASSET = "ocd-week-report.schema.json"
    const val MODELS_ASSET = "ocd-models.txt.gzip"
    const val LOG_KIND = "week_report"
    const val OTHER_MODEL = "other"
    const val MAX_RESULTS = 16
    private const val MAX_MODEL = 96

    val RESULTS = setOf(Pointers.WORKS, Pointers.SIGNED_IN, Pointers.SERVICE_OFF, Pointers.NOT_POSSIBLE, Pointers.FAILED)

    /** SM8: a stack whose results do not count claims neither works nor failed. */
    val UNCALIBRATED_RESULTS = setOf(Pointers.SIGNED_IN, Pointers.SERVICE_OFF, Pointers.NOT_POSSIBLE)

    private val PROFILE = Regex("open\\.[0-9]{5,6}\\.[0-9a-f]{12}")
    private val MCCMNC = Regex("[0-9]{5,6}")

    /** The emergency type, and the wildcard that covers it: no such row is ever sent. */
    private val NEVER_TYPES = setOf("emergency", "sos", "*")

    /** The carrier field: the OCD profile, or the home network only when no profile matches. Null when neither has its shape. */
    fun carrier(profileId: String?, mccmnc: String): String? = profileId?.takeIf(PROFILE::matches) ?: mccmnc.takeIf(MCCMNC::matches)

    fun majorMinor(version: String): String = version.split('.').take(2).joinToString(".")

    /** The yes: the only way it is set. */
    fun answered(w: WeekState, yes: Boolean, nowMs: Long): WeekState =
        if (yes) w.copy(asked = true, yes = true, yesAtMs = nowMs) else off(w).copy(asked = true)

    /** Off stops the send at once and clears what waits. The last report stays on the screen: it is what already left. */
    fun off(w: WeekState): WeekState = w.copy(yes = false, yesAtMs = null, buffers = emptyList(), closedWeek = null, sendAtMs = null)

    /** At most one write a day: the 15-minute job runs 96 times a day, and the state file is large. */
    fun mainHeld(w: WeekState, nowMs: Long): WeekState {
        val last = w.mainHeldAtMs
        return if (last != null && nowMs >= last && nowMs - last < TimeUnit.DAYS.toMillis(1)) w else w.copy(mainHeldAtMs = nowMs)
    }

    /** What the light check saw. A value not seen working is no result: the phone may be off the network or a person's switch off. */
    fun passive(check: SimCheck): Map<String, String> = buildMap {
        for ((entry, seen) in check.observed) {
            if (seen) put(entry, if (entry.endsWith(":on")) Pointers.SIGNED_IN else Pointers.SERVICE_OFF)
        }
        for (entry in check.static.keys) if (entry !in this) put(entry, Pointers.NOT_POSSIBLE)
    }

    /** [seen] into the week's buffer, each entry keeping one result. Only after a yes; the caller drops what it saw while roaming. */
    fun noted(w: WeekState, week: String, sim: SimKey, carrier: String?, seen: Map<String, String>): WeekState {
        if (!w.yes || carrier == null || seen.isEmpty()) return w
        val old = w.buffers.firstOrNull { it.week == week && it.sim.sameCard(sim) }
        val results = old?.results.orEmpty().toMutableMap()
        for ((entry, result) in seen) if (result != Pointers.NOT_POSSIBLE || results[entry] !in Pointers.PASSED) results[entry] = result
        val next = WeekBuffer(week, sim, carrier, results)
        return if (next == old) w else w.copy(buffers = w.buffers.filterNot { it === old } + next)
    }

    /**
     * What this phone measured in [fromMs] until [toMs]: real and tapped calls, the tapped SMS and OCD's own rows inserted
     * in a test. A level is a flag here: no count, bearer, method or day goes on. Only calls recorded at home count, and
     * an emergency call never reaches the ledger ([CallRecord.of]). Calls out alone says nothing a passive signed_in does not.
     */
    fun measured(state: OwnerState, sim: SimKey, fromMs: Long, toMs: Long, rom: String): Map<String, String> = buildMap {
        val calls = state.calls.filter { it.roaming == false && it.atMs >= fromMs && it.atMs < toMs }
        for (feature in listOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI)) {
            val level = Settled.voice(calls, sim, feature, rom, fromMs, toMs - 1, ZoneOffset.UTC) ?: continue
            if (level.failed) put(QuietPlan.entry(feature, true), Pointers.FAILED)
            else if (level.level == Settled.L3) put(QuietPlan.entry(feature, true), Pointers.WORKS)
        }
        val sms = state.selfSms.filter { (it.receivedAtMs ?: it.sentAtMs).let { at -> at >= fromMs && at < toMs } }
        if (Settled.sms(sms, sim, rom, toMs - 1) != null) put(Settled.SMS_ON, Pointers.WORKS)
        for (r in state.entries) {
            if (!r.sim.sameCard(sim) || !r.entry.startsWith(EntryRecord.APN + ":") || r.outcome != "inserted" || r.atMs < fromMs || r.atMs >= toMs) continue
            when (r.works) {
                true -> put(r.entry, Pointers.WORKS)
                false -> put(r.entry, Pointers.FAILED)
                null -> Unit
            }
        }
    }

    /** An `apn:` entry for a row OCD's own data has for the SIM's network ([ocdRows]), and never one with the emergency type. */
    fun rowAllowed(entry: String, ocdRows: Set<String>): Boolean {
        val key = entry.removePrefix(EntryRecord.APN + ":")
        if (key == entry || !Pointers.APN_KEY.matches(key) || key !in ocdRows) return false
        return key.split('|')[1].split(',').none { it in NEVER_TYPES }
    }

    /**
     * The report of one SIM's closed [week], or null with nothing to say. Each entry appears once, the five switches first,
     * then OCD's rows up to [MAX_RESULTS] and the size limit (row keys are long).
     */
    fun build(week: String, cell: WeekCell, carrier: String, results: Map<String, String>, ocdRows: Set<String>, onList: (String) -> Boolean): JsonObject? {
        val allowed = if (StackCalibration.counted(cell.imsStack)) RESULTS else UNCALIBRATED_RESULTS
        val kept = results.filterValues { it in allowed }
        val features = QuietPlan.FEATURE_ENTRIES.filter { it in kept }
        val rows = kept.keys.filter { rowAllowed(it, ocdRows) }.sorted()
        var entries = (features + rows).take(MAX_RESULTS)
        val model = cell.model.takeIf { it.isNotEmpty() && it.length <= MAX_MODEL && onList(it) } ?: OTHER_MODEL
        while (entries.isNotEmpty()) {
            val report = buildJsonObject {
                put("schema", SCHEMA)
                put("schema_version", SCHEMA_VERSION)
                put("week", week)
                put("app_version", cell.appVersion)
                putJsonObject("device") {
                    put("model", model)
                    put("rom_family", cell.romFamily)
                    put("android_major", cell.androidMajor)
                    put("ims_stack", cell.imsStack)
                }
                put("carrier", carrier)
                putJsonArray("results") {
                    for (entry in entries) {
                        addJsonObject {
                            put("entry", entry)
                            put("result", kept.getValue(entry))
                        }
                    }
                }
            }
            if (text(report).toByteArray().size <= Relay.MAX_BODY_BYTES) return report
            entries = entries.dropLast(1)
        }
        return null
    }

    /** The body as sent and as the screen shows it: the same text. */
    fun text(report: JsonObject): String = IssueReport.text(report)

    /** The last look: the closed schema and the privacy scan. Anything else is refused whole. */
    fun clean(report: JsonObject, schema: JsonObject): Boolean =
        SchemaSubset.problems(report, schema).isEmpty() && PrivacyScan.scan(report, hex = setOf("report.carrier")).isEmpty()

    /** The week's roll: a new closed week draws its moment in the running week, and anything older is gone. */
    fun rolled(w: WeekState, nowMs: Long, draw: (Long) -> Long): WeekState {
        val closed = WeekClock.closed(nowMs)
        val current = WeekClock.of(nowMs)
        val buffers = w.buffers.filter { it.week == closed || it.week == current }
        return if (w.closedWeek == closed) w.copy(buffers = buffers) else w.copy(closedWeek = closed, sendAtMs = WeekClock.startMs(nowMs) + draw(WeekClock.WEEK_MS), buffers = buffers)
    }

    /** What holds a send back for now: an unreadable condition counts as a closed one. */
    fun blocked(net: MainNet, inCall: Boolean?): Boolean =
        net.unmetered != true || net.roaming != false || net.dataSaver != false || inCall != false

    /** Main was held within [Relay.MAX_MAIN_AGE_MS]. A clock that went back reads as old: this can only stop. */
    fun fresh(w: WeekState, nowMs: Long): Boolean = w.mainHeldAtMs?.let { nowMs >= it && nowMs - it <= Relay.MAX_MAIN_AGE_MS } == true
}

/** What the weekly step reads from the phone and the network. */
interface WeekDevice {
    val privileged: Boolean
    val switch: WeekSwitch
    val relayUrl: String

    /** The call ledger's build key ([TestBasis.rom]). */
    val rom: String
    val schema: JsonObject
    fun net(): MainNet
    fun inCall(): Boolean?
    fun cell(): WeekCell

    /** The model is on OCD's public list. */
    fun onList(model: String): Boolean

    /** The keys of OCD's own rows for the SIM's network; empty when the SIM left or nothing is pinned. */
    suspend fun ocdRows(sim: SimKey): Set<String>
    suspend fun relayOpen(): Boolean

    /** One POST; true only on a 2xx. */
    suspend fun post(body: ByteArray): Boolean
}

/**
 * The small weekly step of the 15-minute job. One closed week, one try: the week is marked finished before anything
 * goes out, so nothing is queued or sent twice. Any answer but a 2xx drops the week and shows nothing.
 */
class WeekStep(private val store: StateStore, private val device: WeekDevice, private val draw: (Long) -> Long) {
    /** The line for the log when the week was decided; null otherwise. */
    suspend fun run(nowMs: Long): String? {
        if (!device.privileged) return null
        val start = store.read().week
        if (device.switch != WeekSwitch.ASK) {
            if (start.yes) store.update { it.copy(week = WeekReports.off(it.week)) }
            return null
        }
        if (!start.yes) return null
        val w = WeekReports.rolled(start, nowMs, draw)
        if (w != start) store.update { it.copy(week = it.week.copy(closedWeek = w.closedWeek, sendAtMs = w.sendAtMs, buffers = w.buffers)) }
        val closed = WeekClock.closed(nowMs)
        if (w.doneWeek == closed || nowMs < (w.sendAtMs ?: Long.MAX_VALUE)) return null
        if (WeekReports.blocked(device.net(), device.inCall())) return null

        val to = WeekClock.startMs(nowMs)
        val from = maxOf(to - WeekClock.WEEK_MS, w.yesAtMs ?: 0L)
        val cell = device.cell()
        val state = store.read()
        val reports = w.buffers.filter { it.week == closed }.mapNotNull { b ->
            val results = b.results + WeekReports.measured(state, b.sim, from, to, device.rom)
            WeekReports.build(closed, cell, b.carrier, results, device.ocdRows(b.sim), device::onList)
                ?.takeIf { WeekReports.clean(it, device.schema) }?.let(WeekReports::text)
        }
        val why = when {
            reports.isEmpty() -> null
            !Relay.ready(device.relayUrl) -> "this build has no relay address yet"
            !WeekReports.fresh(w, nowMs) -> "this phone has not reached OCD's data for 30 days"
            !device.relayOpen() -> "OCD's relay is closed"
            else -> null
        }
        store.update { s ->
            val week = s.week.copy(doneWeek = closed, buffers = s.week.buffers.filterNot { it.week == closed })
            s.copy(week = if (reports.isEmpty()) week else week.copy(last = WeekLast(closed, reports, why = why)))
        }
        if (reports.isEmpty()) return "$closed: nothing to report"
        if (why != null) return "$closed: not sent, $why"
        var sent = 0
        for (report in reports) {
            // Off stops it at once, also between two SIMs.
            if (!store.read().week.yes) break
            if (device.post(report.toByteArray())) sent++
        }
        val failed = if (sent < reports.size) "the relay did not take it" else null
        store.update { s -> s.copy(week = s.week.copy(last = s.week.last?.copy(sent = sent, why = failed))) }
        return "$closed: $sent of ${reports.size} sent"
    }
}

/** The one-time question, word for word as OCD's rule round 48 has it. */
object WeekQuestion {
    const val TITLE = "Help improve carrier settings?"
    const val BODY = "Once a week, on Wi-Fi and never while roaming, this phone can send Open Carrier Data which carrier features and data settings signed in or worked, " +
        "with your phone model, ROM family, Android version, IMS software and carrier. It sends no name, number or SIM ID, no location, no times and no call details. " +
        "Your internet address reaches OCD's relay at Cloudflare (USA) while sending and OCD does not keep it. " +
        "Results are published only for groups of phones and hold no ID, so what was sent cannot be taken back. " +
        "You can stop any time in Settings > About phone > Open Carrier Data."
    const val DETAILS = "Details"
    const val NO = "No"
    const val YES = "Yes"
    const val DETAILS_TEXT = "A report holds the week, the app version, your phone model (or \"other\" when it is not on OCD's public list), ROM family, Android version, IMS software and " +
        "your carrier as OCD names it (or its network code when OCD has no profile for it). Below that are at most 16 lines. Each says whether VoLTE, Wi-Fi calling, SMS over IMS, " +
        "video calling, VoNR or one of OCD's own access point names worked, signed in, was off, was not possible or failed.\n\n" +
        "It never holds your plan, ROM build, SPN or GID1, an ID of any kind, a day or time, or how many calls you made, in which direction or for how long. " +
        "Results from roaming and from emergency calls never count. It goes only on a network that is not metered, never while roaming, with Data Saver on or during a call, " +
        "and it shows no notification.\n\n" +
        "OCD's relay only adds to counters. It keeps no report, address or log, and the counters are deleted after 8 weeks. A result is published only with at least 3 reports in one week, " +
        "shown by month, as automatic, reported by phones, not checked by OCD. It never sets a value on any phone.\n\n" +
        "If you say No or ignore the question, nothing is sent and the question does not come back."

    /** The person may answer here: a ROM install on a build that asks. */
    fun open(privileged: Boolean, switch: WeekSwitch): Boolean = privileged && switch == WeekSwitch.ASK

    /** The notification goes out: once per install, and only with a SIM. */
    fun due(w: WeekState, privileged: Boolean, switch: WeekSwitch, sim: Boolean): Boolean = open(privileged, switch) && !w.asked && sim
}

/** The words around the switch on the entry's screen. */
object WeekWords {
    const val SECTION = "Weekly summary"
    const val SWITCH = "Send OCD a weekly summary"
    const val ON = "Once a week, on Wi-Fi and never while roaming. Turning it off stops it at once and clears what waits to be sent"
    const val OFF = "Nothing is sent. Turning it on shows what it sends first"
    const val BUILD_OFF = "This ROM's builder turned the weekly summary off. Nothing is sent by itself"
    const val LAST = "Last week report"
    const val NONE = "None yet"

    fun line(last: WeekLast): String = when {
        last.sent == last.reports.size -> "Week ${last.week}: sent"
        last.sent > 0 -> "Week ${last.week}: ${last.sent} of ${last.reports.size} sent, the rest dropped"
        else -> "Week ${last.week}: not sent${last.why?.let { ", $it" }.orEmpty()}"
    }
}
