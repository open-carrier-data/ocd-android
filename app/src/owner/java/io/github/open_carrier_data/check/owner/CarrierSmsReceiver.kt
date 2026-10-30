package io.github.open_carrier_data.check.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import io.github.open_carrier_data.check.core.CarrierSms
import io.github.open_carrier_data.check.owner.core.AccountReadMark
import io.github.open_carrier_data.check.owner.core.AccountSource
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OCD rule round 42, item 5, on the owner (and rom) build: read the carrier's own plan SMS as a
 * free, passive account-state signal. Only a message from a sender on the SIM carrier's per-network
 * list ([CarrierSms.SENDERS]) is classified, in memory, into a fixed class set; every other message
 * is dropped after the sender check and touched no further. Only the class, the network and the time
 * are kept ([AccountReadMark]); never the text, the sender or a number, and nothing is forwarded.
 * The reader is strictly read-only: it never sends or auto-replies to any SMS (round 42 item 8).
 *
 * Opt-in, off by default; on the rom build behind SM9's opt-in. The read-only builds hold no SMS
 * permission and never register this (src/owner only).
 */
class CarrierSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val store = FileStateStore(context)
        // Off by default; on rom behind the active-tests opt-in (SM9).
        if (!allowed(store.read())) return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        // The sender is read now; the body is read only after the sender check passes, below.
        val sender = messages.firstNotNullOfOrNull { it.displayOriginatingAddress ?: it.originatingAddress } ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val sims = runCatching { OwnerData.sims(context) }.getOrNull().orEmpty()
                val sim = sims.firstOrNull { it.subId == subId } ?: sims.singleOrNull() ?: return@launch
                // The sender check: a message not from the carrier's own senders is dropped, untouched further.
                if (!CarrierSms.fromSender(sim.identity.mccmnc, sender)) return@launch
                // In memory: the body is classified and dropped at once; only the class survives.
                val planClass = CarrierSms.classify(body) ?: return@launch
                val now = System.currentTimeMillis()
                store.updateLocked {
                    it.copy(accountReads = AccountReadMark.add(it.accountReads, AccountReadMark(sim.key, now, planClass.account, AccountSource.SMS)))
                        .logged(now, "carrier_sms", "slot ${sim.slot + 1}: ${planClass.wire}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun allowed(state: OwnerState): Boolean =
        state.carrierSmsOn && (OwnerTester.build == TesterBuild.OWNER || state.activeTests.on)
}
