package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * The optional silent incoming-path check (the coordinator's refinement of
 * 2026-10-20; round 26 automatic check 10): off unless the owner turns it on
 * in Details, because an SMS to one's own number may cost an SMS on the
 * tariff. When on, now and then at night, the app sends one data SMS to the
 * phone's own number on its own port (the SMS manager's data message: always a
 * port, never a port-less text, never a Type 0 "silent" SMS) and waits for
 * it to come back. It arriving says "the network reaches this phone over
 * IMS", a partial signal only: it is never evidence for incoming calls and
 * never "works". The own number is read for the send and never kept.
 */

/** One data SMS to the phone's own number. */
@Serializable
data class SelfSmsCheck(
    val sim: SimKey,
    val sentAtMs: Long,
    /** Matches the SMS that comes back; random, not a number. */
    val nonce: String,
    /** sent, received, not_received, send_failed or refused_<why>. */
    val result: String,
    val receivedAtMs: Long? = null,
    /** IMS was registered for SMS over IMS when it was sent, and when it came back. */
    val imsAtSend: Boolean? = null,
    val imsAtReceive: Boolean? = null,
    /** The ROM build key it was sent on (the call ledger's), for SMS both ways per build (OCD round 29). */
    val build: String? = null,
    /** IMS ran over Wi-Fi when it was sent: the bearer of SMS over IMS. */
    val wifiAtSend: Boolean? = null,
)

object SelfSms {
    /** The app's own port: the SMS reaches only this app's receiver, never an inbox. */
    const val PORT: Short = 16123
    const val MAGIC = "OCD1"
    val EVERY_MS: Long = TimeUnit.DAYS.toMillis(7)
    val WAIT_MS: Long = TimeUnit.MINUTES.toMillis(10)
    private val NONCE = Regex("[0-9a-f]{16}")
    private val NUMBER = Regex("\\+?[0-9]{6,15}")

    /** The payload: the magic and the nonce, 20 bytes; nothing else. */
    fun payload(nonce: String): ByteArray {
        require(NONCE.matches(nonce)) { "nonce" }
        return (MAGIC + nonce).encodeToByteArray()
    }

    /** The nonce of a payload this app sent, else null. */
    fun nonceOf(payload: ByteArray?): String? {
        val text = payload?.takeIf { it.size == MAGIC.length + 16 }?.decodeToString() ?: return null
        return text.removePrefix(MAGIC).takeIf { text.startsWith(MAGIC) && NONCE.matches(it) }
    }

    /** Why no SMS is sent now, or null. */
    fun refusal(on: Boolean, ownNumber: String?, smsOverIms: Boolean?, roaming: Boolean?, port: Short = PORT): String? = when {
        !on -> "off"
        port <= 0 -> "no_port"
        roaming != false -> "roaming"
        smsOverIms != true -> "sms_over_ims_not_registered"
        ownNumber == null || !NUMBER.matches(ownNumber) -> "own_number_unknown"
        else -> null
    }

    fun due(checks: List<SelfSmsCheck>, sim: SimKey, nowMs: Long): Boolean {
        val last = checks.lastOrNull { it.sim.sameCard(sim) && !it.result.startsWith("refused_") } ?: return true
        return nowMs - last.sentAtMs >= EVERY_MS || nowMs < last.sentAtMs
    }

    /** A sent SMS that did not come back within [WAIT_MS] is "not received". */
    fun settle(checks: List<SelfSmsCheck>, nowMs: Long): List<SelfSmsCheck> =
        checks.map { if (it.result == "sent" && nowMs - it.sentAtMs >= WAIT_MS) it.copy(result = "not_received") else it }

    /** The SMS with [nonce] came back. */
    fun received(checks: List<SelfSmsCheck>, nonce: String, atMs: Long, imsNow: Boolean?): List<SelfSmsCheck> =
        checks.map { if (it.nonce == nonce && it.result in setOf("sent", "not_received")) it.copy(result = "received", receivedAtMs = atMs, imsAtReceive = imsNow) else it }

    fun add(checks: List<SelfSmsCheck>, check: SelfSmsCheck): List<SelfSmsCheck> = (checks + check).takeLast(20)

    fun line(check: SelfSmsCheck?, zone: ZoneId): String {
        check ?: return "Not sent yet"
        val day = Instant.ofEpochMilli(check.sentAtMs).atZone(zone).toLocalDate()
        return when (check.result) {
            "received" -> "A test SMS reached this phone over IMS, $day (a partial check; it says nothing about calls)"
            "sent" -> "A test SMS is on its way, $day"
            "not_received" -> "A test SMS did not come back, $day"
            "send_failed" -> "A test SMS could not be sent, $day"
            else -> "Not sent: ${check.result.removePrefix("refused_").replace('_', ' ')}"
        }
    }
}
