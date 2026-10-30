package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbeNet
import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.ProbeRunMark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OCD rule round 42, item 2, on the owner (and rom) build: the live mobile-data probe ladder.
 * It binds a cellular [Network] for the SIM (the owner manifest holds CHANGE_NETWORK_STATE, which
 * requestNetwork needs) and runs [ProbeNet] (D1-D5) on it, so every socket and DnsResolver query
 * goes over the cellular link, never the default network. [ProbeLadder.classify] turns the outcome
 * into a [UserPlaneClass], which [Diagnoses] pairs with the account-state read.
 *
 * The bounds ([ProbePolicy]): automatic only after a measured cellular failure, at most once per SIM
 * per 6 hours, never while roaming or with mobile data off, and never under the emergency and call
 * refusals (the caller checks those through [io.github.open_carrier_data.check.owner.core.Refusals]
 * before this runs). D5 (the carrier self-care page) is in; the D6 throughput sample never is.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object OwnerProbe {
    private const val NETWORK_MS = 20_000L

    /**
     * Runs the ladder for [sim] on a freshly requested cellular network and returns the user-plane
     * class, or null when the network did not come up. [privateDnsBrokenHint] is the passive read's
     * NetworkCapabilities bit. D5 runs (owner and rom only path).
     */
    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, sim: OwnerSim, privateDnsOn: Boolean, privateDnsBrokenHint: Boolean?): UserPlaneClass? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(sim.subId).build())
            .build()
        val available = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!available.isCompleted) available.complete(network)
            }

            override fun onUnavailable() {
                if (!available.isCompleted) available.complete(null)
            }
        }
        return try {
            cm.requestNetwork(request, callback, NETWORK_MS.toInt())
            val network = withTimeoutOrNull(NETWORK_MS + 2_000) { available.await() } ?: return null
            val results = ProbeNet.run(network, sim.identity.mccmnc, includeD5 = true, privateDnsOn = privateDnsOn, privateDnsBrokenHint = privateDnsBrokenHint)
            ProbeLadder.classify(results)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * The automatic run for the light check: only after a measured cellular failure, and only when
     * the last run of this SIM was at least 6 hours ago. Records the run's time and class and returns
     * the class, or null when it did not run or did not come up.
     */
    @SuppressLint("MissingPermission")
    suspend fun runIfDue(
        context: Context,
        store: FileStateStore,
        sim: OwnerSim,
        measuredFailure: Boolean,
        roaming: Boolean?,
        dataOff: Boolean,
        privateDnsOn: Boolean,
        privateDnsBrokenHint: Boolean?,
        nowMs: Long,
    ): UserPlaneClass? {
        if (ProbePolicy.refusal(roaming, dataOff) != null) return null
        val last = ProbeRunMark.last(store.read().probeRuns, sim.key)
        if (!ProbePolicy.autoDue(measuredFailure, last?.atMs, nowMs)) return null
        val cls = run(context, sim, privateDnsOn, privateDnsBrokenHint) ?: return null
        store.updateLocked { it.copy(probeRuns = ProbeRunMark.add(it.probeRuns, ProbeRunMark(sim.key, nowMs, cls))) }
        return cls
    }
}
