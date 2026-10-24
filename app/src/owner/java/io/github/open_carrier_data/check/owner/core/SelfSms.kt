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
 *
 * SMS both ways over IMS (round 29, the SMS class of the runtime layer and the
 * "re-tested by OCD" record) rests on Telephony's own word for each leg, read
 * on the S20 on 2026-10-24:
 * - out: the SMS_SENT result's "ims" extra, true when Telephony handed the SMS
 *   to the IMS service (SMSDispatcher.SmsTracker.mUsesImsServiceForIms);
 * - back: the inbound SMS handler's own record in `dumpsys isms`, where an SMS
 *   the IMS service received arrives as EVENT_INJECT_SMS (ImsSmsDispatcher
 *   injects it) and an SMS over CS or SGs as EVENT_NEW_SMS from the radio.
 * IMS registered with SMS at the send and at the receipt is recorded beside
 * them; it is the indirect signal and settles nothing on its own.
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
    /** How Telephony says it went out: ims (its SMS_SENT result's "ims" extra true), cs (false); null when it did not say. */
    val sentVia: String? = null,
    /** How Telephony says it came in: ims (injected by the IMS service), cs (a new SMS from the radio); null when its record did not tell. */
    val receivedVia: String? = null,
)

object SelfSms {
    /** The app's own port: the SMS reaches only this app's receiver, never an inbox. */
    const val PORT: Short = 16123
    const val MAGIC = "OCD1"
    const val IMS = "ims"
    const val CS = "cs"
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

    /**
     * The SMS with [nonce] came back at [atMs]: [imsNow] is IMS registered with SMS at that
     * moment, [via] Telephony's record of how it came in ([InboundSms.via]).
     */
    fun received(checks: List<SelfSmsCheck>, nonce: String, atMs: Long, imsNow: Boolean?, via: String? = null): List<SelfSmsCheck> =
        checks.map {
            if (it.nonce == nonce && it.result in setOf("sent", "not_received")) it.copy(result = "received", receivedAtMs = atMs, imsAtReceive = imsNow, receivedVia = via) else it
        }

    /**
     * Telephony's answer to the send of [nonce]: a failure turns a sent check into send_failed;
     * a success records the way out from its "ims" extra ([viaIms], null when absent).
     */
    fun sent(checks: List<SelfSmsCheck>, nonce: String, ok: Boolean, viaIms: Boolean?): List<SelfSmsCheck> =
        checks.map {
            when {
                it.nonce != nonce -> it
                !ok -> if (it.result == "sent") it.copy(result = "send_failed") else it
                else -> it.copy(sentVia = viaIms?.let { ims -> if (ims) IMS else CS })
            }
        }

    /**
     * SMS both ways over IMS: sent with IMS registered for SMS and handed to the IMS service, and
     * back through the IMS service, each by Telephony's own word. IMS registered at the receipt
     * is not needed on top: the inbound record is the stronger reading of the same moment.
     */
    fun bothWays(check: SelfSmsCheck): Boolean =
        check.result == "received" && check.imsAtSend == true && check.sentVia == IMS && check.receivedVia == IMS

    /** True when [after] settles SMS both ways for [nonce] and [before] had not. */
    fun settles(before: List<SelfSmsCheck>, after: List<SelfSmsCheck>, nonce: String): Boolean =
        before.none { it.nonce == nonce && bothWays(it) } && after.any { it.nonce == nonce && bothWays(it) }

    /** The log line for an SMS that came back: each leg as Telephony said it, and whether SMS both ways settled. */
    fun receivedLine(check: SelfSmsCheck?): String {
        check ?: return "the test SMS came back to this phone"
        val out = when (check.sentVia) {
            IMS -> "went out through the IMS service"
            CS -> "went out over CS"
            else -> "went out (Telephony did not say how)"
        }
        val back = when (check.receivedVia) {
            IMS -> "came back through the IMS service (Telephony: injected by IMS)"
            CS -> "came back over CS (Telephony: from the radio)"
            else -> "came back (Telephony's record did not tell how)"
        }
        val registered = when (check.imsAtReceive) {
            true -> "IMS registered with SMS at receipt"
            false -> "IMS not registered with SMS at receipt"
            null -> "IMS at receipt unreadable"
        }
        val settled = if (bothWays(check)) "SMS both ways over IMS: settled" else "SMS both ways over IMS: not settled"
        return "the test SMS $out and $back; $registered; $settled"
    }

    fun add(checks: List<SelfSmsCheck>, check: SelfSmsCheck): List<SelfSmsCheck> = (checks + check).takeLast(20)

    fun line(check: SelfSmsCheck?, zone: ZoneId): String {
        check ?: return "Not sent yet"
        val day = Instant.ofEpochMilli(check.sentAtMs).atZone(zone).toLocalDate()
        return when (check.result) {
            "received" -> when {
                bothWays(check) -> "A test SMS went out and came back over IMS, $day (a partial check; it says nothing about calls)"
                check.receivedVia == CS -> "A test SMS came back over CS, not over IMS, $day"
                check.receivedVia == IMS -> {
                    val out = if (check.sentVia == CS) "it went out over CS" else "the way out is not proven"
                    "A test SMS reached this phone over IMS, $day; $out (a partial check; it says nothing about calls)"
                }
                else -> "A test SMS came back, $day; whether over IMS is not known"
            }
            "sent" -> "A test SMS is on its way, $day"
            "not_received" -> "A test SMS did not come back, $day"
            "send_failed" -> "A test SMS could not be sent, $day"
            else -> "Not sent: ${check.result.removePrefix("refused_").replace('_', ' ')}"
        }
    }
}

/**
 * Telephony's own record of how an SMS came in: the inbound SMS handler's state machine log in
 * `dumpsys isms` (DUMP). For each SMS it handles, the handler logs one record in its delivering
 * state, `rec[n]: time=MM-dd HH:mm:ss.SSS processed=DeliveringState ... what=<event>`:
 * - EVENT_INJECT_SMS: ImsSmsDispatcher injected it when the IMS service handed it an SMS
 *   (ImsSmsImplBase.onSmsReceived, `isOverIms` true). The only other source of that event is
 *   SmsManager.injectSmsPdu, which only a carrier-privileged app may call;
 * - EVENT_NEW_SMS: it came from the radio, over CS or SGs.
 * Only the kind and the time of those records are read, and only in the SIM's own section;
 * nothing else of the dump is kept.
 */
object InboundSms {
    /** Arrivals this far outside the send and the receipt still count: the record and the app read the same clock, in milliseconds. */
    const val SLACK_MS = 1_000L

    data class Arrival(val atMs: Long, val via: String)

    private val SECTION = Regex("^\\s*SmsManager for subId = (-?\\d+):\\s*$")
    private val RECORD = Regex(
        "\\brec\\[\\d+]: time=(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) processed=DeliveringState org=\\S+ dest=\\S+ what=(EVENT_INJECT_SMS|EVENT_NEW_SMS)(?![A-Z_])",
    )

    /**
     * The arrivals in [subId]'s section of [text]; the records carry no year, so each takes the
     * year that puts it nearest [nearMs]. Null when the dump or the section is missing.
     */
    fun parse(text: String?, subId: Int, zone: ZoneId, nearMs: Long): List<Arrival>? {
        text ?: return null
        var inSection = false
        var found = false
        val out = mutableListOf<Arrival>()
        val near = Instant.ofEpochMilli(nearMs).atZone(zone)
        for (line in text.lineSequence()) {
            val section = SECTION.find(line)
            if (section != null) {
                inSection = section.groupValues[1].toIntOrNull() == subId
                found = found || inSection
                continue
            }
            if (!inSection) continue
            val m = RECORD.find(line) ?: continue
            val f = m.groupValues.subList(1, 7).map { it.toInt() }
            val at = (near.year - 1..near.year + 1).mapNotNull { year ->
                runCatching { java.time.ZonedDateTime.of(year, f[0], f[1], f[2], f[3], f[4], f[5] * 1_000_000, zone).toInstant().toEpochMilli() }.getOrNull()
            }.minByOrNull { kotlin.math.abs(it - nearMs) } ?: continue
            out += Arrival(at, if (m.groupValues[7] == "EVENT_INJECT_SMS") SelfSms.IMS else SelfSms.CS)
        }
        return if (found) out else null
    }

    /**
     * How the SMS sent at [sentAtMs] and back at [receivedAtMs] came in: ims or cs when every
     * arrival between the two took that way, else null (none recorded, or another SMS arrived
     * the other way and it cannot be told which was this one).
     */
    fun via(arrivals: List<Arrival>?, sentAtMs: Long, receivedAtMs: Long): String? =
        arrivals.orEmpty().filter { it.atMs in (sentAtMs - SLACK_MS)..(receivedAtMs + SLACK_MS) }.map { it.via }.toSet().singleOrNull()
}
