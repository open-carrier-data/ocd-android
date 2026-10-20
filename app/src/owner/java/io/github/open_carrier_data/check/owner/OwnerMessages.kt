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
import io.github.open_carrier_data.check.owner.core.MmsCheck
import io.github.open_carrier_data.check.owner.core.MmsEndedFile
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.MmsRequest
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.SelfSmsCheck
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.security.SecureRandom

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

    private fun nonce(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, phone: OwnerPhone, sim: OwnerSim, on: Boolean): SelfSmsCheck {
        val now = System.currentTimeMillis()
        val nonce = nonce()
        val ims = runCatching { OwnerSession(phone, FileStateStore(context), sim).observe() }.getOrNull()
        val smsOverIms = ims?.let { it.ims.registered == true && ImsService.SMSIP in it.ims.services }
        val own = ownNumber(context, sim.subId)
        val refusal = SelfSms.refusal(on, own, smsOverIms, QuietReader.roaming(context))
            ?: if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) "send_sms_not_granted" else null
        if (refusal != null || own == null) return SelfSmsCheck(sim.key, now, nonce, "refused_${refusal ?: "own_number_unknown"}", imsAtSend = smsOverIms)
        val manager = smsManager(context, sim.subId)
        val sent = PendingIntent.getBroadcast(
            context, 11, Intent(context, SelfSmsReceiver::class.java).setAction(ACTION_SENT).putExtra(EXTRA_NONCE, nonce),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Always a port: a data SMS to the app's own port reaches only this app, never an inbox; never a Type 0 SMS.
        val ok = runCatching { manager.sendDataMessage(own, null, SelfSms.PORT, SelfSms.payload(nonce), sent, null) }.isSuccess
        return SelfSmsCheck(sim.key, now, nonce, if (ok) "sent" else "send_failed", imsAtSend = smsOverIms)
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
 * sms://localhost:16123, sent by the system with BROADCAST_SMS), or the send
 * failed. Only the nonce is compared; the sender's number is never read.
 */
class SelfSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = FileStateStore(context)
        val now = System.currentTimeMillis()
        when (intent.action) {
            OwnerSelfSms.ACTION_SENT -> {
                if (resultCode != android.app.Activity.RESULT_OK) {
                    val nonce = intent.getStringExtra(OwnerSelfSms.EXTRA_NONCE) ?: return
                    store.update { s -> s.copy(selfSms = s.selfSms.map { if (it.nonce == nonce && it.result == "sent") it.copy(result = "send_failed") else it }) }
                }
            }
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION -> {
                val payload = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent)?.map(SmsMessage::getUserData) }.getOrNull()
                val nonce = payload?.firstNotNullOfOrNull { SelfSms.nonceOf(it) } ?: return
                store.update { s -> s.copy(selfSms = SelfSms.received(s.selfSms, nonce, now, null)).logged(now, "self_sms", "the test SMS came back to this phone") }
            }
        }
    }
}
