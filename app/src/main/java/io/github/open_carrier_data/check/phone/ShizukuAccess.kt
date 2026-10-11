package io.github.open_carrier_data.check.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.LookupNetwork
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
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DeepCheckService::class.java.name))
            .daemon(false)
            .processNameSuffix("deepcheck")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
        val connected = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service?.takeIf { it.pingBinder() })
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        try {
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() } ?: return null
            val text = withContext(Dispatchers.IO) { DeepCheckProtocol.call(binder, target) } ?: return null
            return DeepCheckReading.decode(text).copy(lookupNetwork = network.first, privateDnsActive = network.second)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }

    /** The default network's kind and whether Private DNS is active on it, with ACCESS_NETWORK_STATE only. */
    private fun defaultNetwork(): Pair<LookupNetwork, Boolean?> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return LookupNetwork.NOT_KNOWN to null
        val network = runCatching { cm.activeNetwork }.getOrNull() ?: return LookupNetwork.NONE to null
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return LookupNetwork.NOT_KNOWN to null
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> LookupNetwork.OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LookupNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LookupNetwork.MOBILE
            else -> LookupNetwork.OTHER
        }
        return kind to runCatching { cm.getLinkProperties(network)?.isPrivateDnsActive }.getOrNull()
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST = 4711
        private const val BIND_TIMEOUT_MS = 20_000L
    }
}
