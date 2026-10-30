package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.PreciseDataConnectionState
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.ProbeNet
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * OCD round 42, item 1 (D0/W0) on the owner (and rom) build: the passive read, with the privileged
 * permissions. It keeps only codes, classes and booleans: the last DataFailCause of each APN
 * (default, IMS, MMS and the IWLAN transport), the attach reject cause, whether the SIM's own
 * cellular network is up and validated, whether its Private DNS is strict and broken (each read on
 * that network, not the default one), whether a VPN or Data Saver is on, and the reason mobile data
 * is off. Never an address, an APN credential, a Private DNS hostname or an identifier. It sends
 * no traffic.
 *
 * The read itself needs Android 12 (API 31: TelephonyCallback, isDataEnabledForReason) and is
 * marked so; [privateDnsBroken] needs no particular version, so the probe ladder reads the bit on
 * any Android it runs on.
 */
object OwnerPassiveRead {
    private const val TIMEOUT_MS = 3_000L
    private val direct = Executor { it.run() }

    /** Reads the fail causes and the user-plane state for [subId]. Guarded: a missing read is a null, never a throw. */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, subId: Int, roaming: Boolean, account: AccountState = AccountState.UNKNOWN, balance: String? = null, mmsEnded: Boolean = false): DiagnosisInputs {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId) }.getOrNull()
        val causes = tm?.let { preciseCauses(it) } ?: Causes()
        // The SIM's own cellular network, not the default one (the Wi-Fi while Wi-Fi is on).
        val cellular = cellularNetwork(context, subId)
        return DiagnosisInputs(
            roaming = roaming,
            defaultDataCause = causes.default,
            imsDataCause = causes.ims,
            mmsDataCause = causes.mms,
            iwlanDataCause = causes.iwlan,
            rejectCause = tm?.let { rejectCause(it) },
            cellularUp = cellular != null,
            dataValidated = cellular?.second?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            // Private DNS as the SIM's cellular network has it: strict mode and the broken bit, read on that network.
            privateDnsBroken = cellular?.second?.let { privateDnsBroken(it) } ?: false,
            privateDnsStrict = cellular?.let { ProbeNet.privateDnsStrict(context, it.first) } ?: false,
            vpnActive = vpnActive(context),
            dataSaver = dataSaver(context),
            dataOffReason = dataOffReason(tm),
            account = account,
            balance = balance,
            mmsEnded = mmsEnded,
        )
    }

    private data class Causes(val default: Int? = null, val ims: Int? = null, val mms: Int? = null, val iwlan: Int? = null)

    /**
     * The last cause of each current data connection, from a PreciseDataConnectionStateListener.
     * Android delivers the current precise state of each connection on registration; a short window
     * collects them.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private suspend fun preciseCauses(tm: TelephonyManager): Causes {
        val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val first = CompletableDeferred<Unit>()
        val callback = object : TelephonyCallback(), TelephonyCallback.PreciseDataConnectionStateListener {
            override fun onPreciseDataConnectionStateChanged(state: PreciseDataConnectionState) {
                val cause = runCatching { state.lastCauseCode }.getOrNull() ?: return
                val wlan = runCatching { state.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN }.getOrDefault(false)
                val bitmask = runCatching { state.apnSetting?.apnTypeBitmask ?: 0 }.getOrDefault(0)
                if (wlan) seen["iwlan"] = cause
                if (bitmask and ApnSetting.TYPE_DEFAULT != 0 && !wlan) seen["default"] = cause
                if (bitmask and ApnSetting.TYPE_IMS != 0 && !wlan) seen["ims"] = cause
                if (bitmask and ApnSetting.TYPE_MMS != 0) seen["mms"] = cause
                if (!first.isCompleted) first.complete(Unit)
            }
        }
        if (runCatching { tm.registerTelephonyCallback(direct, callback) }.isFailure) return Causes()
        try {
            withTimeoutOrNull(TIMEOUT_MS) { first.await() }
        } finally {
            runCatching { tm.unregisterTelephonyCallback(callback) }
        }
        return Causes(seen["default"], seen["ims"], seen["mms"], seen["iwlan"])
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private fun rejectCause(tm: TelephonyManager): Int? = runCatching {
        val ss: ServiceState = tm.serviceState ?: return null
        ss.networkRegistrationInfoList
            .firstOrNull { it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.domain and android.telephony.NetworkRegistrationInfo.DOMAIN_PS != 0 }
            ?.rejectCause?.takeIf { it != 0 }
    }.getOrNull()

    private fun activeCaps(context: Context): NetworkCapabilities? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.getNetworkCapabilities(cm.activeNetwork)
    }.getOrNull()

    /**
     * The SIM's connected cellular internet network and its capabilities, found without requesting or
     * bringing up anything: among the networks Android already has, the one with TRANSPORT_CELLULAR and
     * INTERNET whose TelephonyNetworkSpecifier names [subId]; when no network carries a specifier, the
     * single cellular internet network. Null when none is up (mobile data off, no service) or when it
     * cannot be told which SIM a network belongs to.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun cellularNetwork(context: Context, subId: Int): Pair<Network, NetworkCapabilities>? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val cellular = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) network to caps else null
        }
        val specified = cellular.filter { (it.second.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId == subId }
        when {
            specified.isNotEmpty() -> specified.first()
            cellular.size == 1 && cellular.single().second.networkSpecifier !is TelephonyNetworkSpecifier -> cellular.single()
            else -> null
        }
    }.getOrNull()

    private fun vpnActive(context: Context): Boolean =
        activeCaps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false

    private fun dataSaver(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    /**
     * NetworkCapabilities.isPrivateDnsBroken() of the given (cellular) network's capabilities. It is
     * @SystemApi; the app is on the hidden-API allowlist, so read it by reflection. NetworkMonitor
     * sets it only once the HTTP probes passed, so on a silently blocked network it stays false; the
     * ladder's D2c asks the Private DNS server itself. Reflection needs no API level: where the
     * method is missing it reads false, and D2c's own query still decides.
     */
    fun privateDnsBroken(caps: NetworkCapabilities): Boolean = runCatching {
        NetworkCapabilities::class.java.getMethod("isPrivateDnsBroken").invoke(caps) as? Boolean ?: false
    }.getOrDefault(false)

    /** The first data-off reason (user/policy/carrier/thermal), or null when mobile data is on for all reasons. */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun dataOffReason(tm: TelephonyManager?): String? {
        tm ?: return null
        val reasons = linkedMapOf(
            "user" to TelephonyManager.DATA_ENABLED_REASON_USER,
            "policy" to TelephonyManager.DATA_ENABLED_REASON_POLICY,
            "carrier" to TelephonyManager.DATA_ENABLED_REASON_CARRIER,
            "thermal" to TelephonyManager.DATA_ENABLED_REASON_THERMAL,
        )
        for ((name, reason) in reasons) {
            val on = runCatching { tm.isDataEnabledForReason(reason) }.getOrNull() ?: continue
            if (!on) return name
        }
        return null
    }
}
