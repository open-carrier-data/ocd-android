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
 * The read-only builds' part of round 43: the hidden-refusal line only, where the Shizuku deep
 * check's own `dumpsys telephony.registry` read holds the refusal. No row naming, no gate, no new
 * command and no new permission.
 */
object HiddenLines {
    /** The newest refused WWAN setups of the SIM now in [phoneId]'s slot, from the registry dump's lines. */
    fun fromRegistry(lines: List<RegistryDataLine>, phoneId: Int): List<Pair<SetupAttempt, VerdictFeature>> {
        val subId = SetupAttempts.currentSubId(lines, phoneId) ?: return emptyList()
        return CauseVerdicts.HIDDEN_FEATURES.mapNotNull { f ->
            SetupAttempts.newest(lines, phoneId, subId, f)?.takeIf { DataFailCauseClass.hidden(it.cause) }?.let { it to f }
        }
    }

    /**
     * The hidden refusals to show for [subId]: mobile data only while it is still down (on for every
     * reason and no cellular internet network up for this SIM, [mobileDataDown]), VoLTE only while IMS
     * is not registered. Unreadable counts as not down: no line on a guess.
     */
    fun shown(refusals: List<SetupRefusal>, subId: Int?, mobileDataDown: Boolean?, imsRegistered: Boolean?): List<SetupRefusal> =
        refusals.filter { r ->
            r.hidden && subId != null && r.subId == subId && when (r.feature) {
                VerdictFeature.MOBILE_DATA -> mobileDataDown == true
                VerdictFeature.VOLTE -> imsRegistered == false
                VerdictFeature.MMS -> true
                else -> false
            }
        }

    /** The line for a hidden refusal, without row naming (the read-only builds never name a row). */
    fun line(r: SetupRefusal): String? =
        CauseVerdicts.of(r.feature, Refusal(CauseClass.HIDDEN), AccountState.UNKNOWN)?.line
}
