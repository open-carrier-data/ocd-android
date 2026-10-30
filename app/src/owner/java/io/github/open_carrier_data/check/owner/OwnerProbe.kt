package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.ims.ImsManager
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.EpdgPolicy
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbeNet
import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.ProbeResults
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.owner.core.EpdgRunMark
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
 * W1, the ePDG name lookup ([epdgIfDue]), asks the SIM's standard ePDG name of the connected Wi-Fi
 * network's resolver (found with a listen callback, never requested or brought up) and of the
 * cellular network's own DNS servers, directly over UDP 53, and keeps each answer as a class only.
 * W2 (IKE) is not built.
 *
 * The bounds ([ProbePolicy], [EpdgPolicy]): automatic only after a measured cellular (D1-D5) or
 * Wi-Fi-calling (W1) failure, at most once per SIM per 6 hours, plus the owner's "check now" on demand
 * after a short cooldown (D1-D5), never while roaming or with mobile
 * data off, and never under the emergency and call refusals (the caller checks those through
 * [io.github.open_carrier_data.check.owner.core.Refusals] before this runs). An automatic W1 asks the
 * cellular resolver only when cellular data itself fails or is the default network; the owner's
 * "check now" compares both. D5 (the carrier self-care page) is in; the D6 throughput sample never is.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object OwnerProbe {
    private const val NETWORK_MS = 20_000L
    private const val WIFI_MS = 2_000L

    /**
     * Runs the ladder for [sim] on a freshly requested cellular network and returns each step's
     * outcome, or null when the network did not come up. The network's own LinkProperties give its
     * DNS servers, [ProbeNet.privateDnsStrict] its Private DNS mode and its own capabilities the
     * Private DNS broken bit; the default network (the Wi-Fi while Wi-Fi is on) is never read for
     * them. D5 runs (owner and rom only path).
     */
    suspend fun run(context: Context, sim: OwnerSim): ProbeResults? =
        onCellular(context, sim) { network ->
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val link = runCatching { cm?.getLinkProperties(network) }.getOrNull()
            val broken = runCatching { cm?.getNetworkCapabilities(network) }.getOrNull()?.let { OwnerPassiveRead.privateDnsBroken(it) }
            ProbeNet.run(network, link, ProbeNet.privateDnsStrict(context, network), sim.identity.mccmnc, includeD5 = true, privateDnsBrokenHint = broken)
        }

    /**
     * Requests [sim]'s cellular network, runs [block] on it and lets it go again; null when the
     * network did not come up in time.
     */
    @SuppressLint("MissingPermission")
    private suspend fun <T> onCellular(context: Context, sim: OwnerSim, block: suspend (Network) -> T): T? {
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
            block(network)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * The connected Wi-Fi network with internet, or null. A listen callback (ACCESS_NETWORK_STATE
     * only): it neither requests nor brings up a network, and a VPN never matches (NOT_VPN is in
     * the request's default capabilities).
     */
    private suspend fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val found = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!found.isCompleted) found.complete(network)
            }
        }
        if (runCatching { cm.registerNetworkCallback(request, callback) }.isFailure) return null
        return try {
            withTimeoutOrNull(WIFI_MS) { found.await() }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /** Whether mobile data is the default network now (then a cellular lookup uses the link the person is on). */
    private fun cellularIsDefault(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }.getOrDefault(false)

    /** The person's Wi-Fi calling switch for [subId] (ImsMmTelManager, read with the privileged phone-state permission); null when unreadable. */
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private fun wifiCallingOn(context: Context, subId: Int): Boolean? = runCatching {
        context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId)?.isVoWiFiSettingEnabled
    }.getOrNull()

    /**
     * The ladder for the light check: automatic only after a measured cellular failure and only when
     * the last run of this SIM was at least 6 hours ago; on the owner's "check now" ([checkNow]) on
     * demand, once the short cooldown since the SIM's last run has passed ([ProbePolicy.due]). Never
     * roaming or with mobile data off; the caller has checked the emergency and call refusals. Records
     * the run (its time, class and each step's outcome) and returns it, or null when it did not run
     * or the network did not come up.
     */
    @SuppressLint("MissingPermission")
    suspend fun runIfDue(
        context: Context,
        store: FileStateStore,
        sim: OwnerSim,
        measuredFailure: Boolean,
        checkNow: Boolean,
        roaming: Boolean?,
        dataOff: Boolean,
        nowMs: Long,
    ): ProbeRunMark? {
        if (ProbePolicy.refusal(roaming, dataOff) != null) return null
        val last = ProbeRunMark.last(store.read().probeRuns, sim.key)
        if (!ProbePolicy.due(measuredFailure, checkNow, last?.atMs, nowMs)) return null
        val results = run(context, sim) ?: return null
        val mark = ProbeRunMark(sim.key, nowMs, ProbeLadder.classify(results), results, checkNow)
        store.updateLocked { it.copy(probeRuns = ProbeRunMark.add(it.probeRuns, mark)) }
        return mark
    }

    /**
     * W1 for the light check: the standard ePDG name of [sim]'s home network through the Wi-Fi
     * resolver and, where [EpdgPolicy.plan] allows, the cellular resolver. [checkNow] is the owner's
     * "check now"; otherwise only after a measured Wi-Fi-calling failure ([imsRegistered],
     * [iwlanCause]), at most once per SIM per 6 hours. [safe] reads the emergency and call refusals
     * once a run is due. Records and returns the run, or null when it did not run. A resolver whose
     * network does not come up counts as "fails".
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun epdgIfDue(
        context: Context,
        store: FileStateStore,
        sim: OwnerSim,
        checkNow: Boolean,
        roaming: Boolean?,
        dataOff: Boolean,
        imsRegistered: Boolean?,
        iwlanCause: Int?,
        cellularDataFailing: Boolean,
        nowMs: Long,
        safe: suspend () -> Boolean,
    ): EpdgRunMark? {
        if (ProbePolicy.refusal(roaming, dataOff) != null) return null
        val home = HomeNetwork.of(sim.identity.mccmnc) ?: return null
        val wifi = wifiNetwork(context)
        val wfcOn = wifiCallingOn(context, sim.subId)
        val failure = EpdgPolicy.wifiCallingFailure(wfcOn, wifi != null, imsRegistered, iwlanCause)
        val last = EpdgRunMark.last(store.read().epdgRuns, sim.key)
        val plan = EpdgPolicy.plan(
            checkNow = checkNow,
            wfcOn = wfcOn,
            wifiCallingFailure = failure,
            wifiConnected = wifi != null,
            cellularDataFailing = cellularDataFailing,
            cellularInUse = cellularIsDefault(context),
            lastRunAtMs = last?.atMs,
            nowMs = nowMs,
        ) ?: return null
        // The emergency and call refusals, read only once a run is due; one that cannot be read refuses.
        if (!safe()) return null
        val wifiAnswer = if (plan.wifi && wifi != null) ProbeNet.epdgLookup(wifi, home) else null
        // The cellular side asks the network's own DNS servers directly (UDP 53), so a strict Private DNS never reads as the carrier failing.
        val cellularAnswer = if (plan.cellular) {
            onCellular(context, sim) { network ->
                val link = runCatching { context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network) }.getOrNull()
                ProbeNet.epdgLookupDirect(network, ProbeNet.resolvers(link), home)
            } ?: EpdgLookup.FAILS
        } else {
            null
        }
        val mark = EpdgRunMark(sim.key, nowMs, wifiAnswer, cellularAnswer)
        store.updateLocked { it.copy(epdgRuns = EpdgRunMark.add(it.epdgRuns, mark)) }
        return mark
    }
}
