package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.BundledMmsEnded
import io.github.open_carrier_data.check.core.MmsEndedEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * MMS (round 26 rule change 6 and the coordinator's refinement of
 * 2026-10-20). Where a network ended MMS, MMS is never tested: "MMS: not
 * checked, the network no longer offers MMS". Since OCD's round 32 (MMS
 * override rule 6) the list is OCD's published one (mms_ended_networks of
 * generated/operators/statements.json at the pinned commit), and the bundled
 * assets/mms-ended.json keeps only the networks the published list does not
 * cover yet; it only shrinks ([MmsEndedList]). Everywhere else MMS stays a test target,
 * but the app never sends an MMS (it costs money). It only checks, silently
 * and for free, that the MMS access point answers: it asks Android for the
 * SIM's MMS network, sends one HTTP GET of a made-up message path to the MMSC
 * host of the phone's MMS APN row (through its MMS proxy, when the row names
 * one) and reads the status line. Any HTTP answer is "MMS access point
 * reachable", never "MMS works".
 */

@Serializable
data class MmsEnded(val operator: String, val mccmnc: List<String>, val ended: String)

@Serializable
data class MmsEndedFile(val description: String, val source: String, val networks: List<MmsEnded>)

/** One MMS check of one SIM. */
@Serializable
data class MmsCheck(
    val sim: SimKey,
    val atMs: Long,
    /** ended, alive, refuses_subscriber, degraded, reachable, no_answer, no_mms_apn, mmsc_not_probed or refused_<why>. */
    val result: String,
    val httpStatus: Int? = null,
    /** For ended: who ended MMS, and when. */
    val operator: String? = null,
    val ended: String? = null,
    /**
     * Round 42, item 6, the ladder's stage: m0_ended, m1_apn, m2_dns, m3_tcp, m4_get, or null.
     * The farthest step that ran; on a failure it names where the ladder stopped. On an MMS-ended
     * network m0_ended means the probe did not run (roaming, mobile data off, no MMS APN row).
     */
    val stage: String? = null,
    /**
     * The MMS APN's DataFailCause of this attempt (M1), when the MMS network failed to come up; null when
     * Android reported none. An integer only.
     */
    val apnCause: Int? = null,
    /**
     * Round 42, item 6, corroboration on an MMS-ended network: no_answer_supports_ended, or
     * answer_flags_for_reread when the MMSC answered. It never flips X6 or enabledMMS; an answer
     * only flags the operator statement for a fresh hand read.
     */
    val corroboration: String? = null,
)

/**
 * Round 42, item 6: the MMS ladder M0-M4 classifier. Pure: it turns the GET status into the ladder's
 * reading (404 the MMSC is alive, 403 it refuses this subscriber, 5xx degraded, any other answer
 * reachable, none no answer), and it reads a corroboration probe on an MMS-ended network without ever
 * flipping the value. It never sends an MMS; the runner only ever GETs a made-up message path.
 */
object MmsLadder {
    /** M4: the reading of the GET status of a made-up message path. */
    fun classifyStatus(status: Int?): String = when {
        status == null -> "no_answer"
        status == 404 -> "alive"
        status == 403 -> "refuses_subscriber"
        status in 500..599 -> "degraded"
        else -> "reachable"
    }

    /** The corroboration an answer or its absence gives on an MMS-ended network. An answer never flips the value. */
    fun corroboration(status: Int?): String = if (status == null) "no_answer_supports_ended" else "answer_flags_for_reread"
}

/** One GET to an MMSC, through a proxy or straight to it. */
data class MmsRequest(val connectHost: String, val connectPort: Int, val requestLine: String, val host: String) {
    /** The bytes sent: a GET with no body. Never a POST, never an m-send-req. */
    fun bytes(): ByteArray =
        "$requestLine\r\nHost: $host\r\nUser-Agent: OCD-Check\r\nAccept: */*\r\nConnection: close\r\n\r\n".encodeToByteArray()
}

object MmsProbe {
    const val ASSET_NAME = "mms-ended.json"
    val EVERY_MS: Long = TimeUnit.DAYS.toMillis(14)
    const val TIMEOUT_MS = 30_000L

    /**
     * M4's path (round 42, item 6: "M4 is only a GET of a made-up message path"): fixed here, never taken
     * from a reply, so a live MMSC answers 404. The row's own MMSC path is never requested.
     */
    const val MADE_UP_PATH = "/ocd-check/no-such-message"
    private val HOST = Regex("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]")
    private val PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/?-]{0,400}")
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): MmsEndedFile = json.decodeFromString(MmsEndedFile.serializer(), text)

    /** The bundled file's networks, the shrink-only fallback of the published list. */
    fun bundled(file: MmsEndedFile?): List<BundledMmsEnded> = file?.networks.orEmpty().map { BundledMmsEnded(it.operator, it.mccmnc, it.ended) }

    /** The MMS-ended list's entry when [mccmnc] ended MMS ([io.github.open_carrier_data.check.core.MmsEndedList.of]). */
    fun ended(list: List<MmsEndedEntry>, mccmnc: String): MmsEndedEntry? = list.firstOrNull { it.mccmnc == mccmnc }

    /**
     * The GET of [MADE_UP_PATH] on [mmsc]'s host (http only; an https MMSC is not probed), through
     * [proxy]:[proxyPort] when the APN row names a proxy. Null when a field
     * is not a plain URL, host or port: nothing is sent then.
     */
    fun request(mmsc: String?, proxy: String?, proxyPort: Int?): MmsRequest? {
        val url = mmsc?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 && it.none { c -> c.isWhitespace() || c.code < 0x20 } } ?: return null
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val rest = url.substring("http://".length)
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        if (!PATH.matches(path) || "@" in authority) return null
        val host = if (authority.startsWith("[")) authority.substringBefore("]") + "]" else authority.substringBefore(':')
        val port = if (authority.startsWith("[")) authority.substringAfter("]").removePrefix(":").ifEmpty { "80" } else authority.substringAfter(':', "80")
        val mmscPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (!HOST.matches(host)) return null
        val proxyHost = proxy?.trim()?.takeIf { it.isNotEmpty() }
        if (proxyHost != null) {
            if (!HOST.matches(proxyHost)) return null
            val pPort = (proxyPort ?: 80).takeIf { it in 1..65535 } ?: return null
            return MmsRequest(proxyHost, pPort, "GET http://$authority$MADE_UP_PATH HTTP/1.1", authority)
        }
        return MmsRequest(host, mmscPort, "GET $MADE_UP_PATH HTTP/1.1", authority)
    }

    /** The HTTP status of an answer's first line; null when it is not an HTTP answer. */
    fun status(firstLine: String?): Int? = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})\\b").find(firstLine.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** Whether [sim] needs an MMS check now: never where MMS ended; else on a new SIM or every 14 days. */
    fun due(checks: List<MmsCheck>, sim: SimKey, nowMs: Long): Boolean {
        val last = checks.lastOrNull { it.sim.sameCard(sim) } ?: return true
        return nowMs - last.atMs >= EVERY_MS || nowMs < last.atMs
    }

    /**
     * The plain line for Details. On an MMS-ended network it adds the corroboration probe's reading when the
     * last check carries one: no answer agrees with the statement; an answer flags it for a fresh read.
     */
    fun line(check: MmsCheck?, ended: MmsEndedEntry?, zone: ZoneId): String {
        if (ended != null) {
            val head = "MMS: not checked, the network no longer offers MMS (${ended.plain})"
            val probe = check?.takeIf { it.result == "ended" } ?: return head
            val day = Instant.ofEpochMilli(probe.atMs).atZone(zone).toLocalDate()
            return when (probe.corroboration) {
                "no_answer_supports_ended" -> head + "; the free probe on $day " +
                    (if (probe.stage == "m1_apn") "could not open the MMS connection${code(probe.apnCause)}" else "got no answer from the MMS access point") +
                    ", which agrees (no MMS was sent)"
                "answer_flags_for_reread" -> "$head; but the MMS access point answered the free probe on $day (no MMS was sent), so the statement is flagged for a fresh read"
                else -> head
            }
        }
        check ?: return "MMS: not checked yet"
        val day = Instant.ofEpochMilli(check.atMs).atZone(zone).toLocalDate()
        return when (check.result) {
            "reachable" -> "MMS access point reachable, checked $day (no MMS was sent)"
            "alive" -> "MMS access point reachable (the MMSC is alive), checked $day (no MMS was sent)"
            "refuses_subscriber" -> "MMS access point reachable, but the MMSC refuses this SIM, checked $day (no MMS was sent)"
            "degraded" -> "MMS access point reachable but degraded (server error), checked $day (no MMS was sent)"
            "no_answer" -> "MMS access point did not answer, checked $day (no MMS was sent)"
            "no_mms_apn" -> "MMS: no MMS access point name on the phone for this SIM"
            "mmsc_not_probed" -> "MMS: the MMS server's address can't be checked without sending"
            else -> "MMS: not checked (${check.result.removePrefix("refused_").replace('_', ' ')})"
        }
    }

    /** " (code N)" for a 3GPP cause number (1-255); nothing for no cause or an Android-internal value. */
    private fun code(cause: Int?): String = cause?.takeIf { it in 1..255 }?.let { " (code $it)" } ?: ""

    fun add(checks: List<MmsCheck>, check: MmsCheck): List<MmsCheck> =
        (checks.filterNot { it.sim.sameCard(check.sim) } + check).takeLast(8)
}
