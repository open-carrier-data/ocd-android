package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.PreciseDataConnectionState
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * OCD round 42, item 1 (D0/W0) on the owner (and rom) build: the passive read, with the privileged
 * permissions. It keeps only codes, classes and booleans: the last DataFailCause of each APN
 * (default, IMS, MMS and the IWLAN transport), the attach reject cause, whether the default network
 * validated, whether a VPN or Data Saver is on, whether the Private DNS is broken, and the reason
 * mobile data is off. Never an address, an APN credential or an identifier. It sends no traffic.
 */
@RequiresApi(Build.VERSION_CODES.S)
object OwnerPassiveRead {
    private const val TIMEOUT_MS = 3_000L
    private val direct = Executor { it.run() }

    /** Reads the fail causes and the user-plane state for [subId]. Guarded: a missing read is a null, never a throw. */
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, subId: Int, roaming: Boolean, account: AccountState = AccountState.UNKNOWN, balance: String? = null, mmsEnded: Boolean = false): DiagnosisInputs {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId) }.getOrNull()
        val causes = tm?.let { preciseCauses(it) } ?: Causes()
        return DiagnosisInputs(
            roaming = roaming,
            defaultDataCause = causes.default,
            imsDataCause = causes.ims,
            mmsDataCause = causes.mms,
            iwlanDataCause = causes.iwlan,
            rejectCause = tm?.let { rejectCause(it) },
            dataValidated = validated(context),
            privateDnsBroken = privateDnsBroken(context),
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

    private fun validated(context: Context): Boolean? =
        activeCaps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun vpnActive(context: Context): Boolean =
        activeCaps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false

    private fun dataSaver(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    /** NetworkCapabilities.isPrivateDnsBroken() is @SystemApi; the app is on the hidden-API allowlist, so read it by reflection. */
    private fun privateDnsBroken(context: Context): Boolean = runCatching {
        val caps = activeCaps(context) ?: return false
        NetworkCapabilities::class.java.getMethod("isPrivateDnsBroken").invoke(caps) as? Boolean ?: false
    }.getOrDefault(false)

    /** The first data-off reason (user/policy/carrier/thermal), or null when mobile data is on for all reasons. */
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
