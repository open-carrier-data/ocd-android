package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OcdProfile
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.phone.CallTestStore
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ObservedCell
import io.github.open_carrier_data.check.owner.core.ObservedPull
import io.github.open_carrier_data.check.owner.core.ObservedReports
import io.github.open_carrier_data.check.owner.core.ObservedSource
import io.github.open_carrier_data.check.owner.core.ObservedStore
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ReleaseCheck
import io.github.open_carrier_data.check.owner.core.ReleaseException
import io.github.open_carrier_data.check.owner.core.ReleaseInfo
import io.github.open_carrier_data.check.owner.core.ReleaseSource
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.phone.PhoneReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
 * a pulled release. Each pull also fetches OCD's observed/summary.json (others'
 * reports, which only order the night's tests; [ObservedReports]).
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
     * Pulls the latest release when its tag differs from the current one, and
     * fetches observed/summary.json either way ([pullObserved]). Returns the
     * release's line for the log. Throws [ReleaseException] or IOException
     * when the release could not be checked; the summary has its own log line.
     * [verbose]: the owner asked ("Check for update"); the background logs
     * only what is new.
     */
    suspend fun pull(force: Boolean = false, verbose: Boolean = true): String = withContext(Dispatchers.IO) {
        val release = runCatching { pullRelease(force) }
        pullObserved(verbose)
        release.getOrThrow()
    }

    /**
     * Fetches OCD's observed/summary.json from the public repository's main
     * branch and keeps it only when it passes: the size cap, strict JSON and
     * the public schema (a copy in the owner build's assets). A fetch that
     * fails, offline included, keeps the previous copy. The owner's own
     * reports (the hashes of the reports the app opened) are taken out.
     * Returns the log line; never throws.
     */
    fun pullObserved(verbose: Boolean = true): String {
        val now = System.currentTimeMillis()
        val device = OwnerWorker.device(context)
        val pull = runCatching {
            val schema = context.assets.open(ObservedSource.SCHEMA_ASSET).use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
            ObservedReports.pull({ url, max -> get(url, maxBytes = max) }, schema, device.model, device.codename, ObservedReports.ownHashes(store.read()), now)
        }.getOrElse { ObservedPull.Kept("not checked (${it.javaClass.simpleName})") }
        val kept = ObservedReports.keep(pull, ObservedStore(File(context.filesDir, "observed")))
        store.update { ObservedReports.applied(it, kept, device.model, now, verbose) }
        return ObservedReports.line(kept, device.model)
    }

    private fun pullRelease(force: Boolean): String {
        val latest = ReleaseCheck.latest(get(ReleaseSource.LATEST_API, accept = "application/vnd.github+json").decodeToString())
        if (latest.draft || latest.prerelease) throw ReleaseException("the latest release is a draft or prerelease")
        val tag = latest.tagName
        if (!ReleaseSource.TAG.matches(tag)) throw ReleaseException("the latest release $tag is not a data release")
        val current = store.read().release
        if (!force && current?.tag == tag && currentDir() != null) return "up to date: $tag"
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
        return "pulled $tag"
    }

    private fun get(url: String, accept: String? = null, maxBytes: Long = ReleaseSource.MAX_ASSET_BYTES): ByteArray {
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
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong > maxBytes) throw ReleaseException("download larger than the cap")
                        return connection.inputStream.use { capped(it, maxBytes) }
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

    /** Reads at most [maxBytes]; one byte more is a refusal, so an oversized answer is never read whole. */
    private fun capped(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw ReleaseException("download larger than the cap")
        }
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

        /**
         * The read-only check's call tests (T2 passes) on this phone model, by
         * feature and network: the ladder's last rung. The screen and the
         * Settings entry read the same list, so they say the same.
         */
        /**
         * The facts the test report names for a run on [sim] (README
         * "Uploads"): the phone and its software, the IMS stack, the SIM's SPN
         * and the GID1 prefix of the OCD [profile] it matched (never its whole
         * GID1), the app version and the data release. [pulledTag]: the
         * release whose files the run used; null when it used the bundled
         * snapshot.
         */
        fun reportCell(context: Context, device: DeviceInfo, sim: OwnerSim?, profile: OcdProfile?, pulledTag: String?, bundle: OcdBundle): ReportCell {
            val gid1 = sim?.identity?.gid1
            return ReportCell(
                model = device.model,
                codename = device.codename,
                androidMajor = device.androidMajor,
                romFamily = device.romFamily.wire,
                buildType = Build.TYPE.orEmpty(),
                imsStack = ImsStack.of(VideoStatic.imsPackages(context)),
                spn = sim?.identity?.spn?.trim()?.takeIf { it.isNotEmpty() },
                gid1Prefix = profile?.match?.gid1Prefixes
                    ?.firstOrNull { prefix -> gid1 != null && gid1.startsWith(prefix, ignoreCase = true) }
                    ?.uppercase()?.takeIf { HEX.matches(it) },
                appVersion = BuildConfig.VERSION_NAME,
                dataRelease = pulledTag ?: bundle.source.releaseTag,
                dataDigest = bundle.source.dataDigest.orEmpty(),
                dataSource = if (pulledTag != null) "pulled" else "bundled",
            )
        }

        private val HEX = Regex("[0-9A-F]{1,20}")

        /**
         * Each SIM's own cell in observed/summary.json, named as its test
         * report would name it: this phone, the OCD profile the SIM matches
         * in [bundle], its network code, the software and the IMS stack.
         */
        fun observedCells(context: Context, device: DeviceInfo, sims: List<OwnerSim>, bundle: OcdBundle): List<Pair<OwnerSim, ObservedCell>> {
            val ims = ImsStack.of(VideoStatic.imsPackages(context))
            return sims.map { sim ->
                sim to ObservedCell(
                    model = device.model,
                    codename = device.codename,
                    carrierProfile = Matcher.resolve(bundle, sim.identity).mostSpecific?.profileId,
                    mccmnc = sim.identity.mccmnc,
                    romFamily = device.romFamily.wire,
                    imsStack = ims,
                    androidMajor = device.androidMajor,
                )
            }
        }

        fun callsTested(context: Context): List<CallsTested> =
            runCatching { CallTestStore(context).load().t2 }.getOrDefault(emptyList())
                .filter { it.model == Build.MODEL }
                .mapNotNull { pass -> OwnerFeature.ofWire(pass.feature.wire)?.let { CallsTested(it, pass.homeMccMnc) } }

        /**
         * The data release the app ships ("2026.10"), for when none is pulled.
         * Read from the bundled data: its release tag when it names one, else
         * the month of its commit (the release tags are named after it).
         */
        fun bundledTag(context: Context): String? = runCatching {
            val source = context.assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip).source
            source.releaseTag?.removePrefix("data-") ?: source.commitDate.take(7).replace('-', '.')
        }.getOrNull()

        /**
         * The active SIMs as Settings names them ("LIDL Connect"), from the
         * subscription list only: no carrier config is read, so the Settings
         * entry's summary can ask for it. Null: not readable.
         */
        @SuppressLint("MissingPermission")
        fun simViews(context: Context): List<SimView>? = runCatching {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
            manager.activeSubscriptionInfoList.orEmpty().filter { it.simSlotIndex >= 0 }.sortedBy { it.simSlotIndex }.map { info ->
                val name = listOf(info.displayName, info.carrierName).firstOrNull { !it.isNullOrBlank() }?.toString() ?: "SIM ${info.simSlotIndex + 1}"
                SimView(info.simSlotIndex, info.subscriptionId, info.mccString.orEmpty() + info.mncString.orEmpty(), name)
            }
        }.getOrNull()
    }
}

