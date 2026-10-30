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
 * ([BalanceUssd.classify]); a menu reply is never answered — it ends the session and is recorded as
 * "the balance needs a menu step; not read", for the owner to read by hand. A small daily cap, off
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
        // No second read on a day one was already read for this SIM.
        val zone = java.time.ZoneId.systemDefault()
        if (AccountReadMark.last(state.accountReads.filter { it.source == AccountSource.BALANCE }, sim.key)
                ?.let { AccountReadMark.isFresh(it, nowMs, zone) } == true
        ) {
            return null
        }
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return null
        store.updateLocked { it.copy(balanceAskedMs = (it.balanceAskedMs + nowMs).takeLast(16)) }
        val read = read(context, sim) ?: return null
        if (read.state != AccountState.UNKNOWN || read.balance != null) {
            store.updateLocked {
                it.copy(accountReads = AccountReadMark.add(it.accountReads, AccountReadMark(sim.key, nowMs, read.state, AccountSource.BALANCE, read.balance)))
            }
        }
        store.updateLocked { it.logged(nowMs, "balance", "slot ${sim.slot + 1}: ${balanceLine(read)}") }
        return read
    }

    /** One sendUssdRequest of the code of [sim]'s identified brand; its one reply classified and masked. */
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, sim: OwnerSim): AccountRead? {
        val code = (BalanceUssd.choose(sim.identity.mccmnc, sim.identity.spn) as? BalanceChoice.Send)?.code ?: return null
        if (!BalanceUssd.allowed(sim.identity.mccmnc, sim.identity.spn, code)) return null
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(sim.subId) }.getOrNull() ?: return null
        val done = CompletableDeferred<AccountRead?>()
        val handler = Handler(Looper.getMainLooper())
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                // The raw reply is classified here and dropped; only a class and a masked figure survive.
                if (!done.isCompleted) done.complete(BalanceUssd.classify(response.toString()))
            }

            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                if (!done.isCompleted) done.complete(null)
            }
        }
        return runCatching {
            tm.sendUssdRequest(code, callback, handler)
            withTimeoutOrNull(USSD_MS) { done.await() }
        }.getOrNull()
    }

    /** The plain log line for a read: never the raw reply, only the class and the masked figure. */
    fun balanceLine(read: AccountRead): String = read.note ?: when (read.state) {
        AccountState.BLOCKED -> "balance blocked${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.LOW -> "balance low${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.OK -> "balance ok${read.balance?.let { " ($it)" } ?: ""}"
        AccountState.UNKNOWN -> "balance not read"
    }
}
