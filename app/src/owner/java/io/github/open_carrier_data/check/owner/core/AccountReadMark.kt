package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.AccountState
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * OCD rule round 42, items 4 and 5: the owner/rom build's last account-state read for a SIM, from a
 * balance USSD reply (item 4) or a carrier-SMS class (item 5). The verdict (item 7) pairs it with a
 * refusal class, but only while it is fresh: the ruling's condition is that a balance counts only on
 * the day it was read, and a carrier-SMS class until a later class replaces it or 31 days pass.
 *
 * It keeps a state, a source, a time and, for a balance, the already-masked figure. Never the SMS
 * text, the sender or a number.
 */
@Serializable
enum class AccountSource { BALANCE, SMS }

@Serializable
data class AccountReadMark(
    val sim: SimKey,
    val atMs: Long,
    val state: AccountState,
    val source: AccountSource,
    /** The already-masked balance figure to show (balance source only), or null. */
    val balance: String? = null,
) {
    companion object {
        const val MAX = 12
        val SMS_LIFE_MS: Long = TimeUnit.DAYS.toMillis(31)

        fun last(marks: List<AccountReadMark>, sim: SimKey): AccountReadMark? = marks.lastOrNull { it.sim.sameCard(sim) }

        /** A balance class replaces a balance; a carrier-SMS class replaces a carrier-SMS class, per SIM. */
        fun add(marks: List<AccountReadMark>, mark: AccountReadMark): List<AccountReadMark> =
            (marks.filterNot { it.sim.sameCard(mark.sim) && it.source == mark.source } + mark).takeLast(MAX)

        /**
         * The account state to feed the verdict for [sim] now, and the balance to show, honouring the
         * ruling's freshness: a balance only on the day it was read, a carrier-SMS class until 31 days.
         * When both a fresh balance and a fresh SMS class exist, the newer read wins.
         */
        fun fresh(marks: List<AccountReadMark>, sim: SimKey, nowMs: Long, zone: ZoneId): Pair<AccountState, String?> {
            val mine = marks.filter { it.sim.sameCard(sim) && isFresh(it, nowMs, zone) }
            val newest = mine.maxByOrNull { it.atMs } ?: return AccountState.UNKNOWN to null
            return newest.state to newest.balance
        }

        fun isFresh(mark: AccountReadMark, nowMs: Long, zone: ZoneId): Boolean = when (mark.source) {
            AccountSource.BALANCE -> sameDay(mark.atMs, nowMs, zone)
            AccountSource.SMS -> nowMs - mark.atMs in 0..SMS_LIFE_MS
        }

        private fun sameDay(aMs: Long, bMs: Long, zone: ZoneId): Boolean =
            Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
    }
}

/**
 * Round 42 follow-up fix (the S20 test of 0.4.7): a balance send that read nothing — a menu reply,
 * Android's onReceiveUssdResponseFailed (which is how a reply that asks for input arrives, without
 * its text) or no reply at all — stops further sends for that SIM until the next local day, so an
 * hourly check never sends the code again and again while the network's menu session waits to time
 * out. It keeps a time and a short reason, never the reply.
 */
@Serializable
data class BalanceHalt(val sim: SimKey, val atMs: Long, val why: String) {
    companion object {
        const val MAX = 8

        fun add(halts: List<BalanceHalt>, halt: BalanceHalt): List<BalanceHalt> =
            (halts.filterNot { it.sim.sameCard(halt.sim) } + halt).takeLast(MAX)

        /** Whether [sim] is halted now: a halt from the same local day, or from a clock that has since gone back. */
        fun halted(halts: List<BalanceHalt>, sim: SimKey, nowMs: Long, zone: ZoneId): Boolean = halts.any {
            it.sim.sameCard(sim) && (nowMs < it.atMs || Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
        }
    }
}
