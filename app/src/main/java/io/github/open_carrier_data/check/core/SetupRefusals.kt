package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/*
 * OCD rule round 43, item 1 (decided 2026-10-31): a refused data, VoLTE or MMS setup whose cause
 * the phone's modem software hides. On Exynos Samsung phones the prebuilt RIL turns the permanent
 * 3GPP causes into -7 (and some setups into -8), and Android shows them as DataFailCause UNKNOWN
 * (0x10000), so "wrong settings", "not subscribed" and "barred" all look the same.
 *
 * Where the refusal is read: the passive listener cannot see it. TelephonyRegistry drops a
 * DISCONNECTED state from the states it replays to a new listener, and a refused setup ends
 * DISCONNECTED. The registry's dated local log (`dumpsys telephony.registry`, "local logs:")
 * keeps the line: each APN type's newest setup attempt is a DISCONNECTED state after a CONNECTING
 * state of the same profile. The owner and rom builds read that dump already (OwnerCommand.REGISTRY),
 * the read-only builds in their Shizuku deep check (ShellCommand.TELEPHONY_REGISTRY).
 *
 * Everything here is parsed in memory. A line's APN row ID is held only to match OCD's own record
 * (owner and rom) and the retry entries; only a class, a cause number and a time are kept, never
 * the APN name, a proxy, mvno_match_data or any other row field.
 */

/** One `notifyDataConnectionForSubscriber` line of telephony.registry's dated local log, in memory only. */
data class RegistryDataLine(
    val at: LocalDateTime,
    val phoneId: Int,
    val subId: Int,
    val state: String,
    val wlan: Boolean,
    /** The APN row ID the line prints; null when it could not be read. Never kept. */
    val rowId: Long?,
    /** The APN types, lower case ("default", "ims", "mms", ...). */
    val types: Set<String>,
    /** The row's edited state as the line prints it (UNEDITED, USER_EDITED, CARRIER_EDITED, ...). */
    val edited: String?,
    /** The DataFailCause, from the hex the line prints (0xfffffff9 is -7); null when unreadable. */
    val cause: Int?,
)

object RegistryDataLog {
    private const val DATE = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?"
    private val LINE = Regex(
        "^\\s*($DATE) - notifyDataConnectionForSubscriber: phoneId=(\\d+) subId=(-?\\d+)\\s+state: ([A-Z_]+), transport: (WLAN|WWAN), (.*)$",
    )

    /** The row ID: the field after the entry name, before the network code (empty for a row keyed by carrier ID). */
    val ROW = Regex("\\[ApnSetting\\] .*?, (-?\\d+), (?:\\d{5,6})?, ")
    private val TYPES = Regex(", -?\\d+, ([a-z_*]+(?: \\| [a-z_*]+)*), (?:IPV4V6|IPV6|IP|PPP|NON-IP|UNSTRUCTURED|UNKNOWN|null), ")
    private val EDITED = Regex(", ([A-Z_]*(?:EDITED|DELETED)[A-Z_]*), link properties:")
    private val CAUSE = Regex("fail cause: [A-Z][A-Z0-9_]{0,79}\\(0x([0-9a-fA-F]{1,8})\\)")

    fun parse(text: String?): List<RegistryDataLine> = text?.let { parse(it.lineSequence()) }.orEmpty()

    fun parse(lines: Sequence<String>): List<RegistryDataLine> = lines.mapNotNull(::line).toList()

    fun line(text: String): RegistryDataLine? {
        val m = LINE.find(text) ?: return null
        val at = time(m.groupValues[1]) ?: return null
        val rest = m.groupValues[6]
        val apn = rest.substringAfter("APN Setting: ", "")
        return RegistryDataLine(
            at = at,
            phoneId = m.groupValues[2].toIntOrNull() ?: return null,
            subId = m.groupValues[3].toIntOrNull() ?: return null,
            state = m.groupValues[4],
            wlan = m.groupValues[5] == "WLAN",
            rowId = ROW.find(apn)?.groupValues?.get(1)?.toLongOrNull(),
            types = TYPES.find(apn)?.groupValues?.get(1)?.split(" | ")?.map { it.trim() }?.toSet().orEmpty(),
            edited = EDITED.find(apn)?.groupValues?.get(1),
            cause = CAUSE.find(rest)?.groupValues?.get(1)?.let { hex(it) },
        )
    }

    /** A LocalLog time (LocalDateTime.toString: seconds and the fraction only when not zero). */
    fun time(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    /** "fffffff9" to -7: the hex of a 32-bit int. */
    fun hex(text: String): Int? = text.toLongOrNull(16)?.takeIf { it in 0..0xFFFFFFFFL }?.toInt()
}

/** The newest refused setup attempt of one APN type, in memory only. */
data class SetupAttempt(
    val at: LocalDateTime,
    val cause: Int,
    /** Held to match OCD's own record and the retry entries; never kept. */
    val rowId: Long,
    val edited: String?,
    val subId: Int,
)

object SetupAttempts {
    /** The APN type each WWAN feature uses: the internet APN, the cellular IMS APN, the MMS APN. */
    fun apnType(feature: VerdictFeature): String? = when (feature) {
        VerdictFeature.MOBILE_DATA -> "default"
        VerdictFeature.VOLTE -> "ims"
        VerdictFeature.MMS -> "mms"
        else -> null
    }

    /** The subscription of the newest data line for [phoneId]: the SIM in that slot now, for a reader that knows only the slot. */
    fun currentSubId(lines: List<RegistryDataLine>, phoneId: Int): Int? = lines.lastOrNull { it.phoneId == phoneId }?.subId

    /**
     * The newest setup attempt of [feature]'s APN type on WWAN for this SIM ([phoneId], [subId]): a
     * DISCONNECTED state after a CONNECTING state of the same profile (row ID). It counts only when
     * its line is newer than the last connected line for that APN type and no later line names that
     * profile, and when its cause is non-zero; else null. Emergency-type profiles never count.
     * Whether the feature is still down (mobile data on, no cellular internet network, IMS not
     * registered) is the caller's to check.
     */
    fun newest(lines: List<RegistryDataLine>, phoneId: Int, subId: Int, feature: VerdictFeature): SetupAttempt? {
        val type = apnType(feature) ?: return null
        val mine = lines.filter { it.phoneId == phoneId && it.subId == subId }
        val typed = mine.indices.filter { i -> mine[i].let { type in it.types && "emergency" !in it.types } }
        for (k in typed.indices.reversed()) {
            val i = typed[k]
            val line = mine[i]
            if (line.wlan || line.state != "DISCONNECTED" || line.rowId == null) continue
            val before = typed.subList(0, k).map { mine[it] }.lastOrNull { it.rowId == line.rowId && !it.wlan } ?: continue
            if (before.state != "CONNECTING") continue
            // The newest attempt. It counts only while nothing newer says otherwise.
            if (mine.drop(i + 1).any { it.rowId == line.rowId }) return null
            val lastConnected = typed.lastOrNull { mine[it].state == "CONNECTED" }
            if (lastConnected != null && lastConnected > i) return null
            val cause = line.cause?.takeIf { it != 0 } ?: return null
            return SetupAttempt(line.at, cause, line.rowId, line.edited, line.subId)
        }
        return null
    }
}

/**
 * One refused WWAN setup as the verdict reads it. Only a cause number, a class and (item 2) a time
 * are kept: never the APN name or a row field.
 */
@Serializable
data class SetupRefusal(
    val feature: VerdictFeature,
    /** The DataFailCause: 65536 for UNKNOWN, a raw value below -6 when Android passed it through, or a 3GPP code. */
    val cause: Int,
    /** Owner and rom: which access point it was ([ApnRowClass]); null in the read-only builds. */
    val apnRow: ApnRowClass? = null,
    /** The modem's raw value (-7, -8) from the phone service's dump when that dump was read; Details only. */
    val modemValue: Int? = null,
    /** The SIM's subscription (Android's index, not an identifier), for a reader that knows only the slot. */
    val subId: Int? = null,
    /** Item 2: the dated end of Android's wait before it tries this profile again, epoch ms ([RetryWaits]); null when none was read. */
    val waitUntilMs: Long? = null,
) {
    val hidden: Boolean get() = DataFailCauseClass.hidden(cause)

    /** The refusal the verdict reads: a readable code, or the hidden class (never with a code). */
    fun refusal(): Refusal? = when {
        hidden -> Refusal(CauseClass.HIDDEN, apnRow = apnRow)
        else -> DataFailCauseClass.of(cause).takeIf { it != CauseClass.UNKNOWN }?.let { cls ->
            Refusal(cls, code = cause, temporaryDefinite = cls == CauseClass.TEMPORARY && cause in setOf(26, 34, 38), apnRow = apnRow)
        }
    }
}

/** Round 43's Details for a hidden value: "Android cause UNKNOWN (0x10000), modem value -7", and (owner, rom) the access point. */
object HiddenDetails {
    fun cause(cause: Int): String =
        if (cause == DataFailCauseClass.UNKNOWN_CAUSE) "Android cause UNKNOWN (0x10000)" else "Android cause $cause"

    fun line(feature: VerdictFeature, cause: Int, modemValue: Int? = null, apnRow: ApnRowClass? = null): String =
        "${feature.label}: ${cause(cause)}" + (modemValue?.let { ", modem value $it" } ?: "") + (apnRow?.let { "; access point: ${it.plain}" } ?: "")
}

/**
 * The modem's raw value for a refused setup, from the phone service's dump (`dumpsys activity service
 * com.android.phone`): the data network's own "DataCallResponse: { cause=UNKNOWN(0xfffffff9)" for the
 * refused profile. Android keeps a failed data network only briefly, so this is often absent. Only
 * a value below -6 is taken; nothing else of the dump is kept.
 */
object ModemValue {
    private val RESPONSE = Regex("DataCallResponse: \\{ cause=[A-Z][A-Z0-9_]{0,79}\\(0x([0-9a-fA-F]{1,8})\\)")
    private val PROFILE = Regex("mDataProfile=\\[DataProfile=(\\[ApnSetting\\] [^\\n]*)")
    private val SUB = Regex("(?m)^\\s*mSubId=(-?\\d+)\\s*$")
    private val TRANSPORT = Regex("(?m)^\\s*mTransport=(WWAN|WLAN)\\s*$")
    private val END = Regex("(?m)^\\s*-{10,}\\s*$")

    fun of(phoneDump: String?, subId: Int, rowId: Long): Int? {
        if (phoneDump.isNullOrEmpty()) return null
        return phoneDump.split("Tag: DN-").drop(1).mapNotNull { raw ->
            val block = raw.split(END, limit = 2)[0]
            if (SUB.find(block)?.groupValues?.get(1)?.toIntOrNull() != subId) return@mapNotNull null
            if (TRANSPORT.find(block)?.groupValues?.get(1) != "WWAN") return@mapNotNull null
            val profile = PROFILE.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            if (RegistryDataLog.ROW.find(profile)?.groupValues?.get(1)?.toLongOrNull() != rowId) return@mapNotNull null
            RESPONSE.findAll(block).mapNotNull { RegistryDataLog.hex(it.groupValues[1]) }.lastOrNull { it < -6 }
        }.lastOrNull()
    }
}

/**
 * The read-only builds' part of rounds 43 and 44: the refused-setup line only, where the Shizuku deep
 * check's own `dumpsys telephony.registry` read holds the refusal: a hidden cause (round 43) or, since
 * round 44, a coded one (round 42's items 1 and 7 already allow its class on display). No row naming,
 * no account clause, no gate, no new command and no new permission.
 */
object HiddenLines {
    /** The newest refused WWAN setups of the SIM now in [phoneId]'s slot, from the registry dump's lines: hidden or coded, never AOSP's -1 to -6. */
    fun fromRegistry(lines: List<RegistryDataLine>, phoneId: Int): List<Pair<SetupAttempt, VerdictFeature>> {
        val subId = SetupAttempts.currentSubId(lines, phoneId) ?: return emptyList()
        return CauseVerdicts.HIDDEN_FEATURES.mapNotNull { f ->
            SetupAttempts.newest(lines, phoneId, subId, f)?.takeIf { DataFailCauseClass.refusal(it.cause) }?.let { it to f }
        }
    }

    /**
     * The refusals to show for [subId], hidden or coded: mobile data only while it is still down (on for
     * every reason and no cellular internet network up for this SIM, [mobileDataDown]), VoLTE only while
     * IMS is not registered. Unreadable counts as not down: no line on a guess. The registry's local log
     * keeps old lines, so an hours-old #33 never shows after data came back.
     */
    fun shown(refusals: List<SetupRefusal>, subId: Int?, mobileDataDown: Boolean?, imsRegistered: Boolean?): List<SetupRefusal> =
        refusals.filter { r ->
            DataFailCauseClass.refusal(r.cause) && subId != null && r.subId == subId && when (r.feature) {
                VerdictFeature.MOBILE_DATA -> mobileDataDown == true
                VerdictFeature.VOLTE -> imsRegistered == false
                VerdictFeature.MMS -> true
                else -> false
            }
        }

    /**
     * The line for a refused setup, without row naming (the read-only builds never name a row) and with no
     * account read: a hidden one gets round 43's words, a #33 the not-told words (VoLTE the tariff words),
     * another code round 42's.
     */
    fun line(r: SetupRefusal): String? =
        r.copy(apnRow = null).refusal()?.let { CauseVerdicts.of(r.feature, it, AccountState.UNKNOWN)?.line }
}

/*
 * OCD rule round 43, item 2: the wait Android holds before it tries a refused profile again. No app
 * API exposes it (ThrottleStatus goes only to the qualified-networks service); DataRetryManager's
 * section of the phone service's dump (`dumpsys activity service com.android.phone`) holds it as
 * "Retry entries" and "Throttling entries". The owner and rom builds read that dump through the
 * privileged OwnerCommand.PHONE read they already have; the read-only builds only where their deep
 * check already read it (ShellCommand.AOSP_IMS). The dump prints each time only as a time of day
 * (HH:mm:ss.SSS) and keeps expired entries, so each one is dated from DataRetryManager's own dated
 * local log line, or from the failure's dated registry line. Parsed in memory: only the dated instant
 * is kept, never the dump text, the profile text, the APN name or the request list's package names.
 */
object RetryWaits {
    private const val DATE = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?"
    private const val TIME = "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|never)"
    private val SECTION_END = Regex("(?m)^\\s*-{10,}\\s*$")
    private val RETRY = Regex("\\[DataSetupRetryEntry: delay=(-?\\d+)ms, retry time[:=]\\s*$TIME, (.*)$")
    private val THROTTLE = Regex("\\[DataThrottlingEntry: dataProfile=(.*), transport=(WWAN|WLAN), expiration time[:=]\\s*$TIME\\]")
    private val LOG = Regex("^\\s*($DATE) - (Add throttling entry|Scheduled data retry|onEvaluateDataSetupRetry:) (.*)$")
    private val ENTRY_TRANSPORT = Regex("\\], transport=(WWAN|WLAN)")
    private val STATE = Regex(", state=([A-Z_]+), timestamp=")
    private val EVALUATE = Regex("\\], transport=(WWAN|WLAN), cause=.*?, retryDelayMillis=(-?\\d+)ms")

    /** Times printed twice (the list and the log) differ by a millisecond or so: DataUtils works out the offset at each print. */
    private const val SAME_MS = 2_000L

    /** A time of day earlier than its dating line by more than this is the next day; closer is the print's jitter. */
    private const val JITTER_S = 60L

    private data class Retry(val rowId: Long?, val wwan: Boolean, val time: java.time.LocalTime?, val delayMs: Long?, val state: String?)
    private data class Throttle(val rowId: Long?, val wwan: Boolean, val time: java.time.LocalTime?)
    private data class Dated(val at: LocalDateTime, val kind: String, val rowId: Long?, val wwan: Boolean, val time: java.time.LocalTime?, val delayMs: Long?)

    /**
     * The dated end of the wait for the refused profile [rowId] on WWAN of [phoneId], as epoch ms, or
     * null. A throttle counts only when it has not expired and is dated by its "Add throttling entry"
     * line (a throttle whose delay was "never", Long.MAX_VALUE, does not count); a retry entry only
     * when it has not started (NOT_RETRIED), dated by its "Scheduled data retry" line or, without one,
     * by [failureAt], the failure's registry line. A running throttle wins: Android tries nothing
     * before it ends; else the earliest pending retry. Whether under a minute remains is [RetryWait]'s.
     */
    fun untilMs(phoneDump: String?, phoneId: Int, rowId: Long, failureAt: LocalDateTime?, zone: java.time.ZoneId, nowMs: Long): Long? {
        val section = section(phoneDump, phoneId) ?: return null
        val retries = mutableListOf<Retry>()
        val throttles = mutableListOf<Throttle>()
        val logs = mutableListOf<Dated>()
        var mode = ""
        for (line in section.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed == "Retry entries:" -> mode = "retry"
                trimmed == "Throttling entries:" -> mode = "throttle"
                trimmed == "Local logs:" -> mode = "log"
                mode == "retry" -> retry(trimmed)?.let { retries += it }
                mode == "throttle" -> throttle(trimmed)?.let { throttles += it }
                mode == "log" -> dated(line)?.let { logs += it }
            }
        }
        val throttleEnd = throttles.filter { it.rowId == rowId && it.wwan && it.time != null }.mapNotNull { t ->
            val add = logs.lastOrNull { it.kind == "Add throttling entry" && it.rowId == rowId && it.wwan && same(it.time, t.time) } ?: return@mapNotNull null
            val evaluated = logs.lastOrNull { it.kind == "onEvaluateDataSetupRetry:" && it.rowId == rowId && it.wwan && !it.at.isAfter(add.at) }
            if (evaluated?.delayMs == Long.MAX_VALUE) return@mapNotNull null
            instant(t.time!!, add.at, zone)
        }.filter { it > nowMs }.maxOrNull()
        if (throttleEnd != null) return throttleEnd
        return retries.filter { it.rowId == rowId && it.wwan && it.state == "NOT_RETRIED" && it.time != null && it.delayMs != null && it.delayMs in 0 until Long.MAX_VALUE }
            .mapNotNull { r ->
                val at = logs.lastOrNull { it.kind == "Scheduled data retry" && it.rowId == rowId && it.wwan && same(it.time, r.time) }?.at ?: failureAt ?: return@mapNotNull null
                instant(r.time!!, at, zone)
            }.filter { it > nowMs }.minOrNull()
    }

    /** DataRetryManager-[phoneId]'s section, up to the dashes that end it. */
    private fun section(text: String?, phoneId: Int): String? {
        if (text.isNullOrEmpty()) return null
        val start = Regex("(?m)^\\s*DataRetryManager-$phoneId:\\s*$").find(text) ?: return null
        val rest = text.substring(start.range.last + 1)
        val end = listOfNotNull(SECTION_END.find(rest)?.range?.first, Regex("(?m)^\\s*DataRetryManager-\\d+:\\s*$").find(rest)?.range?.first).minOrNull()
        return if (end != null) rest.substring(0, end) else rest
    }

    private fun retry(text: String): Retry? {
        val m = RETRY.find(text) ?: return null
        val rest = m.groupValues[3]
        val profiled = rest.startsWith("[DataProfile=")
        return Retry(
            rowId = if (profiled) RegistryDataLog.ROW.find(rest)?.groupValues?.get(1)?.toLongOrNull() else null,
            wwan = ENTRY_TRANSPORT.find(rest)?.groupValues?.get(1) == "WWAN",
            time = time(m.groupValues[2]),
            delayMs = m.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE.takeIf { m.groupValues[1].length > 18 },
            state = STATE.findAll(rest).lastOrNull()?.groupValues?.get(1),
        )
    }

    private fun throttle(text: String): Throttle? {
        val m = THROTTLE.find(text) ?: return null
        return Throttle(RegistryDataLog.ROW.find(m.groupValues[1])?.groupValues?.get(1)?.toLongOrNull(), m.groupValues[2] == "WWAN", time(m.groupValues[3]))
    }

    private fun dated(line: String): Dated? {
        val m = LOG.find(line) ?: return null
        val at = RegistryDataLog.time(m.groupValues[1]) ?: return null
        val kind = m.groupValues[2]
        val rest = m.groupValues[3]
        return when (kind) {
            "Add throttling entry" -> throttle(rest)?.let { Dated(at, kind, it.rowId, it.wwan, it.time, null) }
            "Scheduled data retry" -> retry(rest)?.let { Dated(at, kind, it.rowId, it.wwan, it.time, it.delayMs) }
            else -> EVALUATE.find(rest)?.let { e ->
                val delay = e.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE.takeIf { e.groupValues[2].length > 18 }
                Dated(at, kind, RegistryDataLog.ROW.find(rest)?.groupValues?.get(1)?.toLongOrNull(), e.groupValues[1] == "WWAN", null, delay)
            }
        }
    }

    private fun time(text: String): java.time.LocalTime? = if (text == "never") null else try {
        java.time.LocalTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun same(a: java.time.LocalTime?, b: java.time.LocalTime?): Boolean {
        if (a == null || b == null) return false
        val d = Math.floorMod(a.toNanoOfDay() / 1_000_000 - b.toNanoOfDay() / 1_000_000, DAY_MS)
        return minOf(d, DAY_MS - d) <= SAME_MS
    }

    /**
     * The time of day [time] dated by the dated line [at]: the line's day, or the next day when the
     * time of day is earlier than the line (by more than the print's jitter). A time that would fall
     * almost a whole day after the line is the jitter across midnight, so the line's day before.
     */
    fun instant(time: java.time.LocalTime, at: LocalDateTime, zone: java.time.ZoneId): Long {
        var t = LocalDateTime.of(at.toLocalDate(), time)
        if (t.isBefore(at.minusSeconds(JITTER_S))) t = t.plusDays(1)
        else if (t.isAfter(at.plusDays(1).minusSeconds(JITTER_S))) t = t.minusDays(1)
        return t.atZone(zone).toInstant().toEpochMilli()
    }

    private const val DAY_MS = 86_400_000L
}
