package io.github.open_carrier_data.check.owner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.AccountRead
import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.BalanceChoice
import io.github.open_carrier_data.check.core.BalanceUssd
import io.github.open_carrier_data.check.owner.core.AccountReadMark
import io.github.open_carrier_data.check.owner.core.AccountSource
import io.github.open_carrier_data.check.owner.core.BalanceHalt
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * OCD rule round 42, item 4, on the owner (and rom) build: the balance USSD. One
 * TelephonyManager.sendUssdRequest of the exact free read-only code of the SIM's brand
 * ([BalanceUssd]), home network only, never roaming. The brand must be identified from the SIM's
 * own provider name (EF_SPN, [io.github.open_carrier_data.check.core.SimIdentity.spn]); an unknown or
 * ambiguous brand gets nothing sent and the record "balance code not sent: brand not identified"
 * with the documented codes to dial by hand. The reply is classified and masked
 * ([BalanceUssd.classify]); a menu reply is never answered — it is recorded as "menu: the balance is
 * one step down; pick it by hand". Android's public API answers a reply that asks for input with
 * onReceiveUssdResponseFailed, without the text; that is recorded as "balance not read: the carrier's
 * reply needs a menu step or failed", and a missing reply as "balance not read: no reply from the
 * carrier". After any send that read nothing, that SIM gets no further send until the next day
 * ([BalanceHalt]). A brand whose first reply is a menu (LIDL Connect) gets nothing sent at all, only
 * the code and the item's text for the owner to pick by hand. A small daily cap, off
 * by default, and on the rom build behind SM9's opt-in. The read-only builds have no CALL_PHONE and
 * never reach this (src/owner only). Its result feeds the verdict as an [AccountReadMark].
 */
@RequiresApi(Build.VERSION_CODES.O)
object OwnerBalance {
    private const val USSD_MS = 30_000L

    /** A small daily cap: repeated USSD can get a SIM throttled or barred. */
    const val DAILY_CAP = 3
    private val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** Whether the balance read may run in this build: the toggle on, and on rom the active-tests opt-in too. */
    fun allowed(state: OwnerState): Boolean =
        state.balanceUssdOn && (OwnerTester.build == TesterBuild.OWNER || state.activeTests.on)

    /** Sends within the daily cap when [atMs] is counted; the most recent [DAILY_CAP] within a day. */
    fun underCap(asked: List<Long>, nowMs: Long): Boolean =
        asked.count { nowMs - it in 0..DAY_MS } < DAILY_CAP

    /**
     * Reads the balance for [sim] when it is due (opt-in on, a measured failure, home network,
     * under the daily cap, no fresh balance today), records the masked result and the send, and
     * returns the account read, or null when it did not run.
     */
    @SuppressLint("MissingPermission")
    suspend fun readIfDue(
        context: Context,
        store: FileStateStore,
        sim: OwnerSim,
        measuredFailure: Boolean,
        roaming: Boolean?,
        nowMs: Long,
    ): AccountRead? {
        if (!measuredFailure) return null
        val state = store.read()
        if (!allowed(state)) return null
        if (roaming != false) return null // home network only, and a SIM whose roaming cannot be read is refused
        // Keyed by brand, never by network: an unidentified brand gets nothing sent, only the record and a hint.
        val choice = BalanceUssd.choose(sim.identity.mccmnc, sim.identity.spn)
        if (choice is BalanceChoice.NotSent) {
            val slot = "slot ${sim.slot + 1}: "
            val line = slot + choice.line
            // Recorded once while it stays the same, so an hourly check keeps a quiet log.
            if (store.read().log.lastOrNull { it.kind == "balance" && it.text.startsWith(slot) }?.text != line) {
                store.updateLocked { it.logged(nowMs, "balance", line) }
            }
            return null
        }
        if (!underCap(state.balanceAskedMs, nowMs)) return null
        val zone = java.time.ZoneId.systemDefault()
        // After a send that read nothing (a menu, a failure, no reply), nothing more for this SIM until the next day.
        if (BalanceHalt.halted(state.balanceHalts, sim.key, nowMs, zone)) return null
        // No second read on a day one was already read for this SIM.
        if (AccountReadMark.last(state.accountReads.filter { it.source == AccountSource.BALANCE }, sim.key)
                ?.let { AccountReadMark.isFresh(it, nowMs, zone) } == true
        ) {
            return null
        }
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return null
        store.updateLocked { it.copy(balanceAskedMs = (it.balanceAskedMs + nowMs).takeLast(16)) }
        val reply = read(context, sim) ?: return null
        val read = outcome(reply)
        if (read.state != AccountState.UNKNOWN || read.balance != null) {
            store.updateLocked {
                it.copy(accountReads = AccountReadMark.add(it.accountReads, AccountReadMark(sim.key, nowMs, read.state, AccountSource.BALANCE, read.balance)))
            }
        }
        store.updateLocked { it.logged(nowMs, "balance", "slot ${sim.slot + 1}: ${balanceLine(read)}") }
        // A send that read nothing halts this SIM's sends until the next day; the record above says why.
        if (halts(read)) {
            store.updateLocked { it.copy(balanceHalts = BalanceHalt.add(it.balanceHalts, BalanceHalt(sim.key, nowMs, read.note ?: BalanceUssd.REPLY_FAILED))) }
        }
        return read
    }

    /** What one send got back: a reply's text (classified at once), Android's failure callback, or nothing in time. */
    sealed interface Reply {
        data class Text(val read: AccountRead) : Reply

        /** onReceiveUssdResponseFailed: how Android answers a reply that asks for input (a menu), without its text, or a failure. */
        data object Failed : Reply

        data object NoReply : Reply
    }

    /** The account read of a [Reply]: a failure or a missing reply is recorded as such, never dropped without a record. */
    fun outcome(reply: Reply): AccountRead = when (reply) {
        is Reply.Text -> reply.read
        Reply.Failed -> AccountRead(AccountState.UNKNOWN, note = BalanceUssd.REPLY_FAILED)
        Reply.NoReply -> AccountRead(AccountState.UNKNOWN, note = BalanceUssd.NO_REPLY)
    }

    /** Whether a read stops further sends for the SIM until the next day: it read no balance (a menu, a failure, no reply). */
    fun halts(read: AccountRead): Boolean = read.state == AccountState.UNKNOWN && read.balance == null

    /**
     * One sendUssdRequest of the code of [sim]'s identified brand; its one reply classified and masked.
     * Null when nothing was sent (no code for this SIM, or the request could not be made).
     */
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, sim: OwnerSim): Reply? {
        val code = (BalanceUssd.choose(sim.identity.mccmnc, sim.identity.spn) as? BalanceChoice.Send)?.code ?: return null
        if (!BalanceUssd.allowed(sim.identity.mccmnc, sim.identity.spn, code)) return null
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(sim.subId) }.getOrNull() ?: return null
        val done = CompletableDeferred<Reply>()
        val handler = Handler(Looper.getMainLooper())
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                // The raw reply is classified here and dropped; only a class and a masked figure survive.
                if (!done.isCompleted) done.complete(Reply.Text(BalanceUssd.classify(response.toString())))
            }

            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                // A menu reply arrives here, without its text (GsmMmiCode keeps it PENDING; onMMIDone answers USSD_RETURN_FAILURE).
                if (!done.isCompleted) done.complete(Reply.Failed)
            }
        }
        runCatching { tm.sendUssdRequest(code, callback, handler) }.onFailure { return null }
        return withTimeoutOrNull(USSD_MS) { done.await() } ?: Reply.NoReply
    }

    /** The plain log line for a read: never the raw reply, only the class and the masked figure. */
    fun balanceLine(read: AccountRead): String = read.note ?: when (read.state) {
        AccountState.BLOCKED -> "balance blocked${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.LOW -> "balance low${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.OK -> "balance ok${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.UNKNOWN -> "balance not read"
    }
}
