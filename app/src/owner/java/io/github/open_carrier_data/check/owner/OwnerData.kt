package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OcdProfile
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.sameCard
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ObservedCell
import io.github.open_carrier_data.check.owner.core.ObservedPull
import io.github.open_carrier_data.check.owner.core.ObservedReports
import io.github.open_carrier_data.check.owner.core.ObservedSource
import io.github.open_carrier_data.check.owner.core.ObservedStore
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OperatorsData
import io.github.open_carrier_data.check.owner.core.OperatorsPull
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.DataException
import io.github.open_carrier_data.check.owner.core.DataSource
import io.github.open_carrier_data.check.owner.core.MainPin
import io.github.open_carrier_data.check.owner.core.MainPull
import io.github.open_carrier_data.check.owner.core.NotFound
import io.github.open_carrier_data.check.owner.core.SchemaRefused
import io.github.open_carrier_data.check.owner.core.Validation
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

    /**
     * The state, decoded once per version of the file: the screen asks every few seconds and the file is
     * over 100 KB, so it is read again only when its time or size changed (2026-10-25: re-reading it twice
     * every 3 s with the screen off freed about 10 MB every 30 s). [OwnerState] is immutable, so the copy is shared.
     */
    override fun read(): OwnerState = synchronized(LOCK) {
        if (!file.isFile) return OwnerState()
        val stamp = file.lastModified() to file.length()
        cached?.takeIf { it.path == file.path && it.stamp == stamp }?.let { return it.state }
        runCatching { OwnerState.decode(file.readText()) }
            .onSuccess { cached = Cached(file.path, stamp, it) }
            .getOrElse {
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
        cached = Cached(file.path, file.lastModified() to file.length(), state)
        trace(state)
    }

    private class Cached(val path: String, val stamp: Pair<Long, Long>, val state: OwnerState)

    /**
     * One read, change and write under the process's state lock, for updates that may race each
     * other (Telephony's answer to the test SMS and the SMS itself arrive within milliseconds).
     */
    fun updateLocked(change: (OwnerState) -> OwnerState): OwnerState = synchronized(LOCK) { change(read()).also(::write) }

    /**
     * Round 26's call and message lines also go to the device log (tag
     * [TRACE_TAG]), so the owner can follow a dry run or a night over adb
     * without root. They hold no number: the mailbox short code at most.
     */
    private fun trace(state: OwnerState) {
        val last = lastTraced
        // In a new process only what happens from now on.
        val since = last?.first ?: (PROCESS_START - 1)
        val fresh = state.log.filter { (it.atMs > since || (last != null && it.atMs == last.first && it.text != last.second)) && traced(it.kind) }
        for (event in fresh) runCatching { android.util.Log.i(TRACE_TAG, "${event.kind}: ${event.text}") }
        state.log.lastOrNull()?.let { lastTraced = it.atMs to it.text }
    }

    companion object {
        private val LOCK = Any()

        /** The last state read or written, with the file's time and size then. */
        @Volatile
        private var cached: Cached? = null
        const val TRACE_TAG = "OcdCheckCalls"
        private val TRACED = setOf("calls_out", "call_watch", "provisional", "self_sms", "mms", "balance")

        /** Round 42's per-slot lines (the verdict, the probe ladder, W1): classes and plain words only, no identifier. */
        private val TRACED_PREFIXES = listOf("diagnosis:", "probe:", "epdg:")

        fun traced(kind: String): Boolean = kind in TRACED || TRACED_PREFIXES.any { kind.startsWith(it) }

        @Volatile
        private var lastTraced: Pair<Long, String>? = null
        private val PROCESS_START = System.currentTimeMillis()
    }
}

/**
 * OCD's data for the owner build: the newest commit on OCD's public main
 * whose validate check passed, pinned by its SHA, every file read at that
 * SHA and checked ([MainPin], round 29 F1 to F3). Until the first pull the
 * bundled asset is used for the IMS values; APN rows need a pin. Each pull
 * also fetches OCD's observed/summary.json (others' reports, which only order
 * the night's tests; [ObservedReports]).
 */
class OwnerData(private val context: Context, private val store: StateStore) {
    private val root = File(context.filesDir, "data")

    /** The pinned commit's folder, when its files are in place. */
    fun currentDir(): File? = store.read().pin?.let { File(root, it.commit) }?.takeIf { File(it, "bundle.json.gz").isFile }

    fun bundled(): OcdBundle = context.assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip)

    /** The pinned commit's bundle, else the bundled asset. */
    fun bundle(): OcdBundle = currentDir()?.let { dir ->
        runCatching { GZIPInputStream(File(dir, "bundle.json.gz").inputStream()).use { OcdBundle.parse(it.readBytes().decodeToString()) } }.getOrNull()
    } ?: bundled()

    /** Round 32: the operator statements and the identity table of the pinned commit; null before the first pull with them. */
    fun operators(): OperatorsData? {
        val pin = store.read().pin ?: return null
        return OperatorsPull.read(currentDir(), pin.commit)
    }

    /** OCD's APN rows for the SIM's network code, from the pinned commit's apns-conf.xml. */
    fun apnRows(sim: OwnerSim): List<OcdApnRow> {
        val dir = currentDir() ?: return emptyList()
        return File(dir, "apns-conf.xml").bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), sim.identity.mccmnc) }
    }

    /**
     * Pins main's newest validated commit when it differs from the current
     * pin, and fetches observed/summary.json either way ([pullObserved]).
     * Returns the pin's line for the log. Throws [DataException] (a refused
     * commit; [SchemaRefused] for a format this build does not know) or
     * IOException; the current data then stays whole. The summary has its own
     * log line. [verbose]: the owner asked ("Check for update", "Test now");
     * the background logs only what is new.
     */
    suspend fun pull(force: Boolean = false, verbose: Boolean = true): String = withContext(Dispatchers.IO) {
        val main = runCatching { pullMain(force) }
        main.exceptionOrNull()?.let { e ->
            val line = "pull refused or failed, the data stays at ${store.read().pin?.label ?: "the bundled snapshot"}: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            runCatching { Log.w(DATA_TAG, line) }
        }
        pullObserved(verbose)
        val line = main.getOrThrow()
        // Round 30: the whole dataset of the same commit, for CarrierSettings's installer; only on a ROM that has one.
        val dataset = pullDataset(force)
        if (dataset == null) line else "$line; $dataset"
    }

    /**
     * The dataset of the pinned commit (F1 to F3 and the blob check, [DatasetCheck.pull]) when the
     * ROM has the installer; null without one. A refused dataset keeps the last good set whole and
     * says why; it never undoes the main pin, whose files passed their own checks.
     */
    private fun pullDataset(force: Boolean): String? {
        val status = OwnerDatasetStep.status(context, store)
        if (!status.available) return null
        val pin = store.read().pin ?: return "no dataset: nothing is pinned yet"
        return try {
            OwnerDatasetStep.pullDataset(context, store, { url, max -> get(url, maxBytes = max) }, pin, force || status.pullAgain)
        } catch (e: Exception) {
            val text = "dataset of ${pin.label} refused or failed, the last good set stays: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            store.update { it.logged(System.currentTimeMillis(), "dataset", text) }
            runCatching { Log.w(DATA_TAG, text) }
            text
        }
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

    private fun pullMain(force: Boolean): String {
        val current = store.read().pin
        root.mkdirs()
        val partial = File(root, "partial").apply { deleteRecursively(); mkdirs() }
        val now = System.currentTimeMillis()
        val result = try {
            MainPin.pull({ url, max -> get(url, maxBytes = max) }, current?.commit?.takeIf { currentDir() != null }, force, partial, bundled(), now)
        } catch (e: Exception) {
            partial.deleteRecursively()
            throw e
        }
        val line = when (result) {
            is MainPull.Current -> {
                partial.deleteRecursively()
                // A pin taken before the app read the operator files gets them now, at the same commit.
                val operators = currentDir()?.takeIf { operators() == null }?.let { dir -> pullOperators(current?.commit ?: result.commit, dir) }
                "up to date: ${current?.label ?: result.commit.take(12)}" + passedOver(result.passedOver) + (operators?.let { "; $it" } ?: "")
            }
            is MainPull.Kept -> {
                partial.deleteRecursively()
                "kept ${current?.label ?: "the bundled snapshot"}: ${result.reason}"
            }
            is MainPull.Pinned -> {
                val pin = result.pin
                GZIPOutputStream(File(partial, "bundle.json.gz").outputStream()).use {
                    it.write(Json.encodeToString(OcdBundle.serializer(), result.bundle).toByteArray())
                }
                // The big JSON files are folded into the bundle; apns-conf.xml and metadata.json stay.
                File(partial, "lookup.json").delete()
                File(partial, "carrier-variants.json").delete()
                // Round 32: the operator statements and the identity table of the same commit; their own refusal keeps the pin.
                val operators = pullOperators(pin.commit, partial)
                val final = File(root, pin.commit)
                final.deleteRecursively()
                if (!partial.renameTo(final)) throw IOException("could not move the pinned data into place")
                val text = "pinned ${pin.label}: ${Validation.PASSED.plain} (check run ${pin.checkRun}); " +
                    "${pin.sha256.size} files read at the commit, data digest ${pin.dataDigest.take(19)} the same in metadata.json, carrier-variants.json and apns-conf.xml, " +
                    "schema_version known; ${pin.profiles} profiles" + (pin.previous?.let { "; previous pin ${it.take(12)}" } ?: "") + passedOver(result.passedOver) +
                    (operators?.let { "; $it" } ?: "")
                store.update { s -> s.copy(pin = pin).logged(now, "pull", text) }
                root.listFiles()?.filter { it.name != pin.commit }?.forEach { it.deleteRecursively() }
                // The tagged release of the builds before round 29 is no longer read.
                File(context.filesDir, "releases").deleteRecursively()
                text
            }
        }
        runCatching { Log.i(DATA_TAG, line) }
        return line
    }

    /**
     * The operator files of [commit] into [dir] (round 32): the line for the log, or null when the commit has none.
     * A refusal keeps the main pin; the statements are missing then and the bundled MMS-ended list stands alone.
     */
    private fun pullOperators(commit: String, dir: File): String? = try {
        OperatorsPull.pull({ url, max -> get(url, maxBytes = max) }, commit, dir)?.let { OperatorsPull.line(it) }
    } catch (e: Exception) {
        for (name in OperatorFiles.FILES.keys) File(dir, name).delete()
        "operator statements refused or failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
    }

    private fun passedOver(commits: List<String>): String = if (commits.isEmpty()) "" else "; newer commits passed over: ${commits.joinToString()}"

    /** The pull's GET (GitHub's hosts only, no identifier), for the APN candidates' reads of the installed commit. */
    fun fetch(): io.github.open_carrier_data.check.owner.core.Fetch = io.github.open_carrier_data.check.owner.core.Fetch { url, max -> get(url, max) }

    private fun get(url: String, maxBytes: Long): ByteArray {
        var target = URL(url)
        repeat(6) {
            require(target.protocol == "https") { "https only" }
            val connection = target.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            // Round 31 (SM11): the pull carries no identifier; a ROM build does not even name itself as the owner's.
            connection.setRequestProperty("User-Agent", if (OwnerTester.build == io.github.open_carrier_data.check.owner.core.TesterBuild.OWNER) "ocd-check-owner" else "ocd-check")
            if (target.host == "api.github.com") {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong > maxBytes) throw DataException("download larger than the cap")
                        return connection.inputStream.use { capped(it, maxBytes) }
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = URL(target, connection.getHeaderField("Location") ?: throw IOException("redirect without a location"))
                        // GitHub serves the API and the files from its own hosts only.
                        if (!(next.host == "github.com" || next.host == "api.github.com" || next.host.endsWith(".githubusercontent.com"))) {
                            throw DataException("a redirect to ${next.host} is not GitHub")
                        }
                        target = next
                    }
                    404 -> throw NotFound("HTTP 404 for ${target.path}")
                    403, 429 -> {
                        // The unauthenticated API allows 60 calls an hour; a pull needs two or three.
                        val left = connection.getHeaderField("x-ratelimit-remaining")
                        throw IOException(if (left == "0") "GitHub's hourly API limit is used up (HTTP $code); tried again later" else "HTTP $code for ${target.path}")
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
            if (out.size() > maxBytes) throw DataException("download larger than the cap")
        }
    }

    companion object {
        /** The device log tag of the data pull: each pin, up to date, kept or refused, with no identifier. */
        const val DATA_TAG = "OcdCheckData"

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
         * GID1), the app version and the data: [bundle]'s commit and data
         * digest (round 29 S13), the pinned main commit when [pulled], else
         * the commit the bundled snapshot was built from.
         */
        fun reportCell(context: Context, device: DeviceInfo, sim: OwnerSim?, profile: OcdProfile?, pulled: Boolean, bundle: OcdBundle): ReportCell {
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
                dataRelease = bundle.source.releaseTag.takeIf { !pulled },
                dataDigest = bundle.source.dataDigest.orEmpty(),
                dataSource = if (pulled) "pulled" else "bundled",
                dataCommit = bundle.source.commit.takeIf { DataSource.SHA.matches(it) },
                romBuild = OwnerAutoReports.publicBuild(),
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

        /**
         * How far this phone's own measured calls went per SIM and feature
         * (round 26): calls out or works, on this ROM build, within 90 days,
         * and for a value Open Carrier Data keeps only since it was applied.
         * The read-only check's T2 passes rest on a person's yes and no longer
         * count here (round 26 rule change 1).
         */
        fun callsTested(context: Context, state: OwnerState = FileStateStore(context).read()): List<CallsTested> = runCatching {
            val build = OwnerBasis.current(context, state).rom
            val now = System.currentTimeMillis()
            simViews(context).orEmpty().flatMap { view ->
                val key = SimKey(view.subId, view.mccmnc, null)
                Ladders.CALL_TESTED.mapNotNull { feature ->
                    val since = state.overrides.lastOrNull { it.slot == view.slot && it.key == feature.key && it.hold == Hold.KEPT && it.sim.sameCard(key) }?.sinceMs ?: 0L
                    CallLevels.level(state.calls, key, feature, build, since, now)?.let { CallsTested(feature, view.mccmnc, it) }
                }
            }
        }.getOrDefault(emptyList())

        /**
         * The data the app ships ("2026.10"), for when none is pinned yet.
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

