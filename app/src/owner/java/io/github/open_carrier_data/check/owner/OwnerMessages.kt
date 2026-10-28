package io.github.open_carrier_data.check.owner

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.owner.core.InboundSms
import io.github.open_carrier_data.check.owner.core.MmsCheck
import io.github.open_carrier_data.check.owner.core.MmsEndedFile
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.MmsRequest
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.SelfSmsCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.ZoneId

/*
 * The two message checks of round 26 as the coordinator refined them on
 * 2026-10-20: the MMS access point probe (silent, free, never an MMS) and the
 * optional data SMS to the phone's own number (off by default; it may cost
 * an SMS). Neither runs in the read-only builds.
 */
object OwnerMms {
    fun ended(context: Context): MmsEndedFile? = runCatching {
        context.assets.open(MmsProbe.ASSET_NAME).use { MmsProbe.parse(it.readBytes().decodeToString()) }
    }.getOrNull()

    /** The phone's own MMS APN row for the SIM: its MMSC, MMS proxy and port. Read here only, never stored. */
    private fun mmsRow(context: Context, sim: OwnerSim): Triple<String?, String?, Int?>? = runCatching {
        val uri = Uri.parse("content://telephony/carriers")
        context.contentResolver.query(uri, arrayOf("type", "mmsc", "mmsproxy", "mmsport", "carrier_id"), "numeric = ?", arrayOf(sim.identity.mccmnc), null)?.use { c ->
            var found: Triple<String?, String?, Int?>? = null
            while (c.moveToNext()) {
                val types = c.getString(0).orEmpty().split(',').map { it.trim().lowercase() }
                if ("mms" !in types && "*" !in types) continue
                val mmsc = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val carrier = if (c.isNull(4)) null else c.getLong(4)
                val row = Triple(mmsc, c.getString(2)?.takeIf { it.isNotBlank() }, c.getString(3)?.toIntOrNull())
                // The SIM's own carrier ID first.
                if (found == null || (sim.carrierId != null && carrier == sim.carrierId)) found = row
            }
            found
        }
    }.getOrNull()

    /**
     * One MMS access point check of [sim]: never where MMS ended; else the
     * SIM's MMS network from Android, one GET to the MMSC through the MMS
     * proxy, and the status line. Any HTTP answer is "reachable".
     */
    @SuppressLint("MissingPermission")
    suspend fun check(context: Context, phone: OwnerPhone, sim: OwnerSim): MmsCheck {
        val now = System.currentTimeMillis()
        MmsProbe.ended(ended(context), sim.identity.mccmnc)?.let { return MmsCheck(sim.key, now, "ended", operator = it.operator, ended = it.ended) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MmsCheck(sim.key, now, "refused_android_too_old")
        if (QuietReader.roaming(context) != false) return MmsCheck(sim.key, now, "refused_roaming")
        if (phone.mobileData(sim.subId).enabled != true) return MmsCheck(sim.key, now, "refused_mobile_data_off")
        val row = mmsRow(context, sim) ?: return MmsCheck(sim.key, now, "no_mms_apn")
        val request = MmsProbe.request(row.first, row.second, row.third) ?: return MmsCheck(sim.key, now, "mmsc_not_probed")
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return MmsCheck(sim.key, now, "refused_no_connectivity")
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(sim.subId).build())
            .build()
        val available = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onUnavailable() {
                available.complete(null)
            }
        }
        return try {
            cm.requestNetwork(networkRequest, callback, MmsProbe.TIMEOUT_MS.toInt())
            val network = withTimeoutOrNull(MmsProbe.TIMEOUT_MS + 2_000) { available.await() } ?: return MmsCheck(sim.key, now, "refused_no_mms_network")
            val status = withContext(Dispatchers.IO) { get(network, request) }
            MmsCheck(sim.key, now, if (status != null) "reachable" else "no_answer", httpStatus = status)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /** One GET on [network], the status line read and the socket closed; null without an HTTP answer. */
    private fun get(network: Network, request: MmsRequest): Int? = runCatching {
        val address = network.getAllByName(request.connectHost.removePrefix("[").removeSuffix("]")).first()
        network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(address, request.connectPort), 15_000)
            socket.getOutputStream().apply {
                write(request.bytes())
                flush()
            }
            val first = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
            MmsProbe.status(first)
        }
    }.getOrNull()
}

/** The optional data SMS to the phone's own number on the app's port; the number is read for the send and never kept. */
object OwnerSelfSms {
    const val ACTION_SENT = "io.github.open_carrier_data.check.owner.SELF_SMS_SENT"

    /** The SMS_SENT result's extra that says Telephony handed the SMS to the IMS service (SMSDispatcher.SmsTracker). */
    const val EXTRA_IMS = "ims"

    /** The receiver's work at receipt stays inside the SMS broadcast's 10 s: the readings, then the wait for Telephony's answer to the send. */
    private const val RECEIPT_MS = 6_000L
    private const val SENT_WAIT_MS = 2_000L

    private fun nonce(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Sends one data SMS to [sim]'s own number, or records why not. The check is in the ledger
     * before the SMS goes out, so Telephony's answer and the SMS coming back always find it.
     */
    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, phone: OwnerPhone, sim: OwnerSim, on: Boolean): SelfSmsCheck {
        val store = FileStateStore(context)
        val now = System.currentTimeMillis()
        val nonce = nonce()
        val ims = runCatching { OwnerSession(phone, store, sim).observe() }.getOrNull()
        val smsOverIms = ims?.let { it.ims.registered == true && ImsService.SMSIP in it.ims.services }
        val own = ownNumber(context, sim.subId)
        // Round 31 (SM9): in a ROM build only with the person's opt-in, and only where the allowlist marks the SMS free.
        val refusal = OwnerTester.policy(store.read()).smsRefusal(CallBuild.entry(context, sim))
            ?: SelfSms.refusal(on, own, smsOverIms, QuietReader.roaming(context))
            ?: if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) "send_sms_not_granted" else null
        if (refusal != null || own == null) {
            return SelfSmsCheck(sim.key, now, nonce, "refused_${refusal ?: "own_number_unknown"}", imsAtSend = smsOverIms).also { record(store, it) }
        }
        val manager = smsManager(context, sim.subId)
        // Mutable, so Telephony's fill-in reaches the receiver: its "ims" extra is the way out. The intent names the receiver, so nothing else can take it.
        val sent = PendingIntent.getBroadcast(
            context, nonce.hashCode(), Intent(context, SelfSmsReceiver::class.java).setAction(ACTION_SENT).putExtra(EXTRA_NONCE, nonce),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Round 29: the build and the IMS bearer, so SMS both ways can settle per build.
        val build = OwnerBasis.current(context, store.read()).rom
        val check = SelfSmsCheck(sim.key, now, nonce, "sent", imsAtSend = smsOverIms, build = build, wifiAtSend = ims?.ims?.transport?.isWifi)
        record(store, check)
        // Always a port: a data SMS to the app's own port reaches only this app, never an inbox; never a Type 0 SMS.
        val ok = runCatching { manager.sendDataMessage(own, null, SelfSms.PORT, SelfSms.payload(nonce), sent, null) }.isSuccess
        if (!ok) store.updateLocked { s -> s.copy(selfSms = SelfSms.sent(s.selfSms, nonce, ok = false, viaIms = null)) }
        return if (ok) check else check.copy(result = "send_failed")
    }

    private fun record(store: FileStateStore, check: SelfSmsCheck) = store.updateLocked { it.copy(selfSms = SelfSms.add(it.selfSms, check)) }

    /** One change of the ledger under the state lock; [line] is logged with it when given. */
    fun change(context: Context, store: FileStateStore, nonce: String, atMs: Long, line: ((SelfSmsCheck?) -> String?)?, change: (List<SelfSmsCheck>) -> List<SelfSmsCheck>) {
        store.updateLocked { s ->
            val next = change(s.selfSms)
            val text = line?.invoke(next.lastOrNull { it.nonce == nonce })
            s.copy(selfSms = next).let { if (text != null) it.logged(atMs, "self_sms", text) else it }
        }
    }

    /** Telephony's answer to the send can come a moment after the SMS itself: up to [SENT_WAIT_MS] for it. */
    suspend fun awaitSent(store: FileStateStore, nonce: String) {
        withTimeoutOrNull(SENT_WAIT_MS) {
            while (store.read().selfSms.lastOrNull { it.nonce == nonce }?.let { it.sentVia == null && it.result != "send_failed" } == true) delay(200)
        }
    }

    /**
     * What the phone says at the receipt of the SMS sent at [sentAtMs]: IMS registered with SMS
     * now (ImsMmTelManager), and Telephony's record of how it came in ([InboundSms], read first,
     * as later SMS push it out). Each is null when it cannot be read in time.
     */
    suspend fun atReceipt(context: Context, subId: Int, sentAtMs: Long, receivedAtMs: Long): Pair<Boolean?, String?> {
        var via: String? = null
        var imsNow: Boolean? = null
        withTimeoutOrNull(RECEIPT_MS) {
            val handle = runCatching { OwnerAccess.open(context) }.getOrNull()
            try {
                via = InboundSms.via(InboundSms.parse(handle?.phone?.smsText(), subId, ZoneId.systemDefault(), receivedAtMs), sentAtMs, receivedAtMs)
                imsNow = handle?.phone?.imsApi(0, subId)?.state()?.let { s -> s.registered?.let { it && ImsService.SMSIP in s.services } }
            } finally {
                handle?.close()
            }
        }
        return imsNow to via
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }

    /** The SIM's own number (READ_PRIVILEGED_PHONE_STATE); null when the SIM does not know it. */
    @SuppressLint("MissingPermission")
    private fun ownNumber(context: Context, subId: Int): String? = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager.getPhoneNumber(subId) else null
        number?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    const val EXTRA_NONCE = "nonce"
}

/**
 * The data SMS on the app's port came back (DATA_SMS_RECEIVED for
 * sms://localhost:16123, sent by the system with BROADCAST_SMS), or Telephony
 * answered the send. Only the nonce is compared; the sender's number is never
 * read. At receipt it records IMS registered with SMS and Telephony's record
 * of how the SMS came in, so SMS both ways can settle (round 29).
 */
class SelfSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = FileStateStore(context)
        val now = System.currentTimeMillis()
        when (intent.action) {
            OwnerSelfSms.ACTION_SENT -> {
                val nonce = intent.getStringExtra(OwnerSelfSms.EXTRA_NONCE) ?: return
                val ok = resultCode == android.app.Activity.RESULT_OK
                val viaIms = if (intent.hasExtra(OwnerSelfSms.EXTRA_IMS)) intent.getBooleanExtra(OwnerSelfSms.EXTRA_IMS, false) else null
                // Logged only when this answer is the one that settles SMS both ways (it came after the SMS was back).
                OwnerSelfSms.change(context, store, nonce, now, { c -> c?.takeIf(SelfSms::bothWays)?.let { "Telephony's answer to the send came after the SMS was back: ${SelfSms.receivedLine(it)}" } }) {
                    SelfSms.sent(it, nonce, ok, viaIms)
                }
            }
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION -> {
                val payload = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent)?.map(SmsMessage::getUserData) }.getOrNull()
                val nonce = payload?.firstNotNullOfOrNull { SelfSms.nonceOf(it) } ?: return
                val check = store.read().selfSms.lastOrNull { it.nonce == nonce }
                if (check == null) {
                    store.updateLocked { it.logged(now, "self_sms", "a test SMS came back that is not in the ledger") }
                    return
                }
                val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID).takeIf { it >= 0 } ?: check.sim.subId
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val (imsNow, via) = OwnerSelfSms.atReceipt(context, subId, check.sentAtMs, now)
                        OwnerSelfSms.awaitSent(store, nonce)
                        OwnerSelfSms.change(context, store, nonce, now, SelfSms::receivedLine) { SelfSms.received(it, nonce, now, imsNow, via) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
