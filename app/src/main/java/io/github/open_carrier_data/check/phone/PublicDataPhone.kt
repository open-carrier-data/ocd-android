package io.github.open_carrier_data.check.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.open_carrier_data.check.core.IdentityFile
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.core.PublicData
import io.github.open_carrier_data.check.core.PublicPin
import io.github.open_carrier_data.check.core.PublicPull
import io.github.open_carrier_data.check.core.PullException
import io.github.open_carrier_data.check.core.PullNotFound
import io.github.open_carrier_data.check.core.PullPolicy
import io.github.open_carrier_data.check.core.StatementsFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection

/** What the read-only builds keep about their pulls, in their own files folder. */
@Serializable
data class PulledState(
    val pin: PublicPin? = null,
    /** The last pull that downloaded on a metered network (U1: at most one a day there). */
    val lastMeteredPullMs: Long? = null,
    val lastTriedMs: Long? = null,
    val lastLine: String? = null,
)

/** The pulled data in use: the bundle, and the operator statements with the identity table when the commit has them. */
data class PulledData(val pin: PublicPin, val bundle: OcdBundle, val statements: StatementsFile?, val identity: IdentityFile?)

/**
 * The read-only builds' data pull on the phone (round 32, U1): [PublicPull] over HTTPS to GitHub's
 * API and raw hosts only, for OCD's public repository only, with no identifier. Nothing else in
 * these builds opens a network connection (AllowlistTest).
 */
class PublicDataPhone(private val context: Context) {
    private val root = File(context.filesDir, "pulled")
    private val stateFile = File(root, "pulled.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun state(): PulledState = runCatching { json.decodeFromString(PulledState.serializer(), stateFile.readText()) }.getOrDefault(PulledState())

    private fun write(state: PulledState) {
        root.mkdirs()
        val tmp = File(root, "pulled.json.tmp")
        tmp.writeText(json.encodeToString(PulledState.serializer(), state))
        if (!tmp.renameTo(stateFile)) throw IOException("pull state not replaced")
    }

    /** The pulled data, when a good pull is in place; null means the bundled snapshot is used. */
    fun current(): PulledData? = runCatching {
        val pin = state().pin ?: return null
        val dir = File(root, pin.commit)
        val bundle = GZIPInputStream(File(dir, BUNDLE).inputStream()).use { OcdBundle.parse(it.readBytes().decodeToString()) }
        if (bundle.source.commit != pin.commit) return null
        val statements = if (pin.operators) OperatorFiles.statements(File(dir, OperatorFiles.STATEMENTS).readText()) else null
        val identity = if (pin.operators) OperatorFiles.identity(File(dir, OperatorFiles.IDENTITY).readText()) else null
        PulledData(pin, bundle, statements, identity)
    }.getOrNull()

    /** Whether the network now is metered; null without a network. */
    fun metered(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrNull()

    /**
     * One pull, started by the person's tap or the ROM builder's daily check. Returns the plain line
     * for the screen; never throws. A failed or refused pull keeps the data in use whole.
     */
    fun pull(bundled: OcdBundle, nowMs: Long = System.currentTimeMillis()): String {
        val before = state()
        val metered = metered()
        PullPolicy.refusal(metered, before.lastMeteredPullMs, nowMs)?.let { why ->
            return "Not downloaded: $why".also { write(before.copy(lastTriedMs = nowMs, lastLine = it)) }
        }
        root.mkdirs()
        val partial = File(root, "partial").apply { deleteRecursively(); mkdirs() }
        val line = try {
            when (val r = PublicPull.pull({ u, m -> get(u, m) }, before.pin?.commit?.takeIf { current() != null }, partial, bundled, nowMs)) {
                is PublicPull.Result.Current -> {
                    partial.deleteRecursively()
                    "Up to date: ${before.pin?.label ?: r.commit.take(7)}"
                }
                is PublicPull.Result.Kept -> {
                    partial.deleteRecursively()
                    "Kept ${before.pin?.label ?: "the data this app ships with"}: ${r.reason}"
                }
                is PublicPull.Result.Pinned -> {
                    GZIPOutputStream(File(partial, BUNDLE).outputStream()).use { it.write(Json.encodeToString(OcdBundle.serializer(), r.bundle).toByteArray()) }
                    // The big files are folded into the bundle; the operator files stay as read.
                    for (name in listOf("lookup.json", "carrier-variants.json", "metadata.json")) File(partial, name).delete()
                    val final = File(root, r.pin.commit)
                    final.deleteRecursively()
                    if (!partial.renameTo(final)) throw IOException("could not move the pulled data into place")
                    write(before.copy(pin = r.pin, lastMeteredPullMs = if (metered == true) nowMs else before.lastMeteredPullMs))
                    root.listFiles()?.filter { it.isDirectory && it.name != r.pin.commit }?.forEach { it.deleteRecursively() }
                    "Updated to ${r.pin.label}: validate passed, every file's git blob matched the commit" +
                        if (r.statements != null) ", with ${r.statements.statements.size} operator statements" else ""
                }
            }
        } catch (e: Exception) {
            partial.deleteRecursively()
            "Not updated, the data stays at ${before.pin?.label ?: "the data this app ships with"}: ${e.message?.take(160) ?: e.javaClass.simpleName}"
        }
        write(state().copy(lastTriedMs = nowMs, lastLine = line))
        return line
    }

    /** One GET: https only, every URL and redirect checked against [PublicData.allowed] first, a fixed User-Agent and no other identifying header. */
    private fun get(url: String, maxBytes: Long): ByteArray {
        var target = url
        repeat(4) {
            if (!PublicData.allowed(target)) throw PullException("not a request for OCD's public data")
            val connection = URL(target).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", PublicData.USER_AGENT)
            if (URL(target).host == PublicData.API_HOST) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong > maxBytes) throw PullException("download larger than the cap")
                        return connection.inputStream.use { capped(it, maxBytes) }
                    }
                    301, 302, 303, 307, 308 -> target = URL(URL(target), connection.getHeaderField("Location") ?: throw IOException("redirect without a location")).toString()
                    404 -> throw PullNotFound("HTTP 404")
                    403, 429 -> throw IOException(if (connection.getHeaderField("x-ratelimit-remaining") == "0") "GitHub's hourly limit for requests without an account is used up; try again later" else "HTTP $code")
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun capped(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw PullException("download larger than the cap")
        }
    }

    companion object {
        const val BUNDLE = "bundle.json.gz"
    }
}
