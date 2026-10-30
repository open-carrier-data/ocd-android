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
 * SIM's MMS network, sends one HTTP GET to the MMSC from the phone's MMS APN
 * row (through its MMS proxy, when the row names one) and reads the status
 * line. Any HTTP answer is "MMS access point reachable", never "MMS works".
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
    /** ended, reachable, no_answer, no_mms_apn, mmsc_not_probed or refused_<why>. */
    val result: String,
    val httpStatus: Int? = null,
    /** For ended: who ended MMS, and when. */
    val operator: String? = null,
    val ended: String? = null,
)

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
    private val HOST = Regex("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]")
    private val PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/?-]{0,400}")
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): MmsEndedFile = json.decodeFromString(MmsEndedFile.serializer(), text)

    /** The bundled file's networks, the shrink-only fallback of the published list. */
    fun bundled(file: MmsEndedFile?): List<BundledMmsEnded> = file?.networks.orEmpty().map { BundledMmsEnded(it.operator, it.mccmnc, it.ended) }

    /** The MMS-ended list's entry when [mccmnc] ended MMS ([io.github.open_carrier_data.check.core.MmsEndedList.of]). */
    fun ended(list: List<MmsEndedEntry>, mccmnc: String): MmsEndedEntry? = list.firstOrNull { it.mccmnc == mccmnc }

    /**
     * The GET for [mmsc] (http only; an https MMSC is not probed), through
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
            return MmsRequest(proxyHost, pPort, "GET $url HTTP/1.1", authority)
        }
        return MmsRequest(host, mmscPort, "GET $path HTTP/1.1", authority)
    }

    /** The HTTP status of an answer's first line; null when it is not an HTTP answer. */
    fun status(firstLine: String?): Int? = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})\\b").find(firstLine.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** Whether [sim] needs an MMS check now: never where MMS ended; else on a new SIM or every 14 days. */
    fun due(checks: List<MmsCheck>, sim: SimKey, nowMs: Long): Boolean {
        val last = checks.lastOrNull { it.sim.sameCard(sim) } ?: return true
        return nowMs - last.atMs >= EVERY_MS || nowMs < last.atMs
    }

    /** The plain line for Details. */
    fun line(check: MmsCheck?, ended: MmsEndedEntry?, zone: ZoneId): String {
        if (ended != null) return "MMS: not checked, the network no longer offers MMS (${ended.plain})"
        check ?: return "MMS: not checked yet"
        val day = Instant.ofEpochMilli(check.atMs).atZone(zone).toLocalDate()
        return when (check.result) {
            "reachable" -> "MMS access point reachable, checked $day (no MMS was sent)"
            "no_answer" -> "MMS access point did not answer, checked $day (no MMS was sent)"
            "no_mms_apn" -> "MMS: no MMS access point name on the phone for this SIM"
            "mmsc_not_probed" -> "MMS: the MMS server's address can't be checked without sending"
            else -> "MMS: not checked (${check.result.removePrefix("refused_").replace('_', ' ')})"
        }
    }

    fun add(checks: List<MmsCheck>, check: MmsCheck): List<MmsCheck> =
        (checks.filterNot { it.sim.sameCard(check.sim) } + check).takeLast(8)
}
