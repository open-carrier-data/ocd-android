package io.github.open_carrier_data.check.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.PrivateDns
import io.github.open_carrier_data.check.core.SimSlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Shizuku as this app sees it. */
data class ShizukuStatus(
    /** The Shizuku app is installed. Sui and other Shizuku servers have no app, so this can be false while [running]. */
    val installed: Boolean,
    val running: Boolean,
    /** Older than Shizuku 11, which this app does not support. */
    val tooOld: Boolean,
    val permitted: Boolean,
    /** The user refused with "don't ask again"; only the Shizuku app can change that. */
    val refused: Boolean,
    val asRoot: Boolean,
) {
    val ready: Boolean get() = running && !tooOld && permitted
}

/**
 * The optional Shizuku tier. Without Shizuku nothing here runs and the app
 * works as a normal app. Permission is asked only from [requestPermission],
 * which the UI calls when the user taps.
 */
class ShizukuAccess(private val context: Context) {
    fun status(): ShizukuStatus {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return ShizukuStatus(installed, running = false, tooOld = false, permitted = false, refused = false, asRoot = false)
        val tooOld = runCatching { Shizuku.isPreV11() || Shizuku.getVersion() < 11 }.getOrDefault(true)
        val permitted = !tooOld &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val refused = !tooOld && !permitted &&
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val asRoot = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)
        return ShizukuStatus(installed, running = true, tooOld, permitted, refused, asRoot)
    }

    fun requestPermission() {
        val status = status()
        if (status.running && !status.tooOld && !status.permitted && !status.refused) {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }
    }

    /**
     * Starts [DeepCheckService] through Shizuku, runs the allowlist once for
     * [target], and stops the service again. Null when Shizuku is not ready or
     * the service gave no answer.
     */
    suspend fun run(target: DeepCheckTarget): DeepCheckReading? {
        if (!status().ready) return null
        val network = defaultNetwork()
        val bound = bind(args("deepcheck")) ?: return null
        try {
            val text = withContext(Dispatchers.IO) { DeepCheckProtocol.call(bound.binder, target) } ?: return null
            return DeepCheckReading.decode(text).copy(
                lookupNetwork = network.kind,
                privateDnsActive = network.privateDnsActive,
                privateDns = network.privateDns,
            )
        } finally {
            bound.unbind()
        }
    }

    /**
     * Starts the guided call test's watch for [slot] in a service process of
     * its own, so a deep check cannot stop it. The watch runs until
     * [CallWatchSession.stop]; it only reads the registry and never touches a
     * call. [mailboxes] are the short codes an incoming number is compared
     * with inside the service; the number itself never comes back.
     */
    suspend fun startCallWatch(slot: SimSlot, mailboxes: List<String>): CallWatchSession? {
        if (!status().ready) return null
        val bound = bind(args("callwatch")) ?: return null
        val started = runCatching {
            withContext(Dispatchers.IO) { DeepCheckProtocol.watchStart(bound.binder, slot, mailboxes) }
        }.getOrDefault(false)
        if (!started) {
            bound.unbind()
            return null
        }
        return CallWatchSession(bound)
    }

    /** A running watch. [read] shows it so far; [stop] ends it, returns what it saw and stops the service. */
    class CallWatchSession internal constructor(private val bound: Bound) {
        suspend fun read(): CallWatchSummary? =
            runCatching { withContext(Dispatchers.IO) { DeepCheckProtocol.watchRead(bound.binder) } }.getOrNull()

        suspend fun stop(): CallWatchSummary? = try {
            runCatching { withContext(Dispatchers.IO) { DeepCheckProtocol.watchStop(bound.binder) } }.getOrNull()
        } finally {
            bound.unbind()
        }
    }

    private fun args(name: String) = Shizuku.UserServiceArgs(ComponentName(context.packageName, DeepCheckService::class.java.name))
        .tag(name)
        .daemon(false)
        .processNameSuffix(name)
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)

    internal class Bound(val binder: IBinder, private val release: suspend () -> Unit) {
        suspend fun unbind() = release()
    }

    private suspend fun bind(args: Shizuku.UserServiceArgs): Bound? {
        val connected = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service?.takeIf { it.pingBinder() })
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }
        }
        val release: suspend () -> Unit = {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
        if (binder == null) {
            release()
            return null
        }
        return Bound(binder, release)
    }

    private class DefaultNetwork(
        val kind: LookupNetwork,
        val privateDnsActive: Boolean? = null,
        val privateDns: PrivateDns? = null,
    )

    /**
     * The default network's kind and its Private DNS, with ACCESS_NETWORK_STATE
     * only (LinkProperties, public since Android 9). The strict-mode hostname is
     * classed here and dropped.
     */
    private fun defaultNetwork(): DefaultNetwork {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return DefaultNetwork(LookupNetwork.NOT_KNOWN)
        val network = runCatching { cm.activeNetwork }.getOrNull() ?: return DefaultNetwork(LookupNetwork.NONE)
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return DefaultNetwork(LookupNetwork.NOT_KNOWN)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> LookupNetwork.OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LookupNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LookupNetwork.MOBILE
            else -> LookupNetwork.OTHER
        }
        val link = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return DefaultNetwork(kind)
        val active = runCatching { link.isPrivateDnsActive }.getOrNull()
        val privateDns = PrivateDns.classify(active, runCatching { link.privateDnsServerName }.getOrNull())
        return DefaultNetwork(kind, active, privateDns)
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST = 4711
        private const val BIND_TIMEOUT_MS = 20_000L
    }
}
