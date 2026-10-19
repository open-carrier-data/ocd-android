package io.github.open_carrier_data.check.owner

import android.content.Context
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ReleaseCheck
import io.github.open_carrier_data.check.owner.core.ReleaseException
import io.github.open_carrier_data.check.owner.core.ReleaseInfo
import io.github.open_carrier_data.check.owner.core.ReleaseSource
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.phone.PhoneReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection

/** The state file in the app's private files folder, written atomically. One lock for the whole process. */
class FileStateStore(context: Context) : StateStore {
    private val file = File(context.filesDir, "owner-state.json")

    override fun read(): OwnerState = synchronized(LOCK) {
        if (!file.isFile) return OwnerState()
        runCatching { OwnerState.decode(file.readText()) }.getOrElse {
            // An unreadable state must not look like "nothing live": the stop flag says so.
            OwnerState(stop = "state_file_unreadable")
        }
    }

    override fun write(state: OwnerState) = synchronized(LOCK) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(state.encode().toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("state file not replaced")
    }

    companion object {
        private val LOCK = Any()
    }
}

/**
 * OCD's data for the owner build: the latest tagged release from GitHub,
 * every asset checked against SHA256SUMS and release.json, the data digest
 * checked across the files. Until a release is pulled, the bundled asset
 * (the same data-2026.10 snapshot) is used for the IMS values; APN rows need
 * a pulled release.
 */
class OwnerData(private val context: Context, private val store: StateStore) {
    private val root = File(context.filesDir, "releases")

    fun currentDir(): File? = store.read().release?.let { File(root, it.tag) }?.takeIf { File(it, "bundle.json.gz").isFile }

    fun bundled(): OcdBundle = context.assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip)

    /** The pulled release's bundle, else the bundled asset. */
    fun bundle(): OcdBundle = currentDir()?.let { dir ->
        runCatching { GZIPInputStream(File(dir, "bundle.json.gz").inputStream()).use { OcdBundle.parse(it.readBytes().decodeToString()) } }.getOrNull()
    } ?: bundled()

    /** OCD's APN rows for the SIM's network code, from the pulled release's apns-conf.xml. */
    fun apnRows(sim: OwnerSim): List<OcdApnRow> {
        val dir = currentDir() ?: return emptyList()
        return File(dir, "apns-conf.xml").bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), sim.identity.mccmnc) }
    }

    /**
     * Pulls the latest release when its tag differs from the current one.
     * Returns a line for the log. Throws [ReleaseException] or IOException.
     */
    suspend fun pull(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val latest = ReleaseCheck.latest(get(ReleaseSource.LATEST_API, accept = "application/vnd.github+json").decodeToString())
        if (latest.draft || latest.prerelease) throw ReleaseException("the latest release is a draft or prerelease")
        val tag = latest.tagName
        if (!ReleaseSource.TAG.matches(tag)) throw ReleaseException("the latest release $tag is not a data release")
        val current = store.read().release
        if (!force && current?.tag == tag && currentDir() != null) return@withContext "up to date: $tag"
        for (asset in ReleaseSource.ASSETS) {
            val listed = latest.assets.firstOrNull { it.name == asset } ?: throw ReleaseException("the release has no $asset")
            if (listed.size > ReleaseSource.MAX_ASSET_BYTES) throw ReleaseException("$asset is larger than the cap")
        }
        root.mkdirs()
        val partial = File(root, "$tag.partial").apply { deleteRecursively(); mkdirs() }
        for (asset in ReleaseSource.ASSETS) {
            File(partial, asset).writeBytes(get(ReleaseSource.downloadUrl(tag, asset)))
        }
        val info = ReleaseCheck.verify(partial, tag, System.currentTimeMillis())
        val bundle = ReleaseCheck.bundle(partial, info, ReleaseCheck.manifest(partial), bundled())
        GZIPOutputStream(File(partial, "bundle.json.gz").outputStream()).use {
            it.write(Json.encodeToString(OcdBundle.serializer(), bundle).toByteArray())
        }
        // The big JSON files are folded into the bundle; apns-conf.xml and the manifests stay.
        File(partial, "lookup.json").delete()
        File(partial, "carrier-variants.json").delete()
        val final = File(root, tag)
        final.deleteRecursively()
        if (!partial.renameTo(final)) throw IOException("could not move the release into place")
        val kept = info.copy(profiles = bundle.profiles.size)
        store.update { s ->
            s.copy(release = kept).logged(
                System.currentTimeMillis(), "pull",
                "pulled $tag: commit ${kept.commit.take(8)}, ${kept.dataDigest.take(19)}, ${kept.profiles} profiles, SHA-256 checked for ${kept.sha256.keys.joinToString()}",
            )
        }
        root.listFiles()?.filter { it.name != tag }?.forEach { it.deleteRecursively() }
        "pulled $tag"
    }

    private fun get(url: String, accept: String? = null): ByteArray {
        var target = URL(url)
        repeat(6) {
            require(target.protocol == "https") { "https only" }
            val connection = target.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "ocd-check-owner")
            accept?.let { connection.setRequestProperty("Accept", it) }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > ReleaseSource.MAX_ASSET_BYTES) throw ReleaseException("download larger than the cap")
                        bytes
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = URL(target, connection.getHeaderField("Location") ?: throw IOException("redirect without a location"))
                        // GitHub serves release assets from its own hosts only.
                        if (!(next.host == "github.com" || next.host.endsWith(".githubusercontent.com"))) {
                            throw ReleaseException("a redirect to ${next.host} is not GitHub")
                        }
                        target = next
                    }
                    else -> throw IOException("HTTP $code for ${target.path}")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        /** The active SIMs, each with what OCD matching reads. Empty without a SIM. */
        suspend fun sims(context: Context): List<OwnerSim> {
            val reader = PhoneReader(context)
            return reader.simChoices().filter { it.slotIndex >= 0 }.mapNotNull { choice ->
                val sim = reader.read(choice.subId).sim ?: return@mapNotNull null
                val identity = sim.identity() ?: return@mapNotNull null
                OwnerSim(choice.slotIndex, choice.subId, identity, sim.carrierName ?: sim.spn ?: identity.mccmnc, sim.carrierId)
            }
        }
    }
}

/** The release line for the screen. */
fun ReleaseInfo?.line(): String = this?.let { "$tag (commit ${commit.take(8)}, ${dataDigest.take(19)}…, checks through $checksThrough)" } ?: "none pulled; the bundled data-2026.10 snapshot is used"
