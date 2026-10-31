package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.BundleSource
import io.github.open_carrier_data.check.core.DeviceRecord
import io.github.open_carrier_data.check.core.DeviceRecords
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OcdProfile
import io.github.open_carrier_data.check.core.SimIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.xml.parsers.SAXParserFactory

/*
 * OCD's data for the owner build: the newest commit on OCD's public main
 * whose `validate` check passed, instead of the monthly tagged release (rule
 * round 29, "freshness", decided 2026-10-24; the owner: "we want to ship
 * allways the newest data and in the fastest way"). Releases stay the channel
 * for consumers that do not verify on a phone. For this app the release's
 * guarantees are replaced by:
 *
 * F1 one commit pin per pull. main is resolved once to a SHA through GitHub's
 *    public REST API, without a token, and every file is read at that SHA
 *    (raw.githubusercontent.com/<repository>/<sha>/<path>), never at the
 *    branch name, so a pull near a publish cannot mix two commits. The SHA is
 *    recorded in the state ([DataPin]), the log and every pointer report.
 * F2 only a commit whose `validate` check run concluded success is taken
 *    (the job of OCD's .github/workflows/validate.yml). main's newest few
 *    commits are walked back for one; otherwise the current data stays.
 * F3 a file whose schema_version major this build does not know is refused,
 *    and the last good data set is kept whole ([SchemaRefused]).
 * F4 a new pin makes an entry due only when a value of the SIM's matched
 *    OCD profile, or an APN row of its scope, changed ([DataBasis]).
 *
 * The data digest cannot be recomputed on the phone, so it must be the same
 * in metadata.json, carrier-variants.json (based_on_data_digest) and the
 * apns-conf.xml header; with the commit pin it replaces SHA256SUMS. Each file
 * is parsed strictly: whole JSON, well-formed XML whose rows the line reader
 * counts the same.
 */

object DataSource {
    const val REPOSITORY = "open-carrier-data/open-carrier-data"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val BRANCH = "main"

    /** GitHub's public REST API, read without a token (60 calls an hour per address; a pull needs two or three). */
    const val API = "https://api.github.com/repos/$REPOSITORY"
    const val RAW = "https://raw.githubusercontent.com/$REPOSITORY"

    /** The job of OCD's .github/workflows/validate.yml whose check run must have passed on the commit (F2). */
    const val CHECK = "validate"

    /** Only GitHub Actions' check runs count; another app's run of the same name does not. */
    const val CHECK_APP = "github-actions"

    /** How many of main's newest commits a pull looks at, newest first, for one whose validate passed. */
    const val WALK_BACK = 4

    val SHA = Regex("[0-9a-f]{40}")

    /** OCD's release tags, which reports of runs before round 29 still name. */
    val RELEASE_TAG = Regex("data-\\d{4}\\.\\d{2}(?:\\.\\d+)?")

    /** The files a pull reads at the pin, by their path in the repository; each is saved under its file name. */
    val FILES = listOf(
        "generated/android/metadata.json",
        "generated/android/lookup.json",
        "generated/devices/carrier-variants.json",
        "generated/android/apns-conf.xml",
    )

    /** The size cap per file, well above the largest today (carrier-variants.json, 10.3 MB). */
    const val MAX_FILE_BYTES = 96L * 1024 * 1024

    /** The size cap for an API answer. */
    const val MAX_API_BYTES = 2L * 1024 * 1024

    /** main's newest commits (one API call). */
    fun commitsUrl(): String = "$API/commits?sha=$BRANCH&per_page=$WALK_BACK"

    /** The validate check runs of one commit (one API call). */
    fun checkRunsUrl(sha: String): String {
        require(SHA.matches(sha)) { "commit shape" }
        return "$API/commits/$sha/check-runs?check_name=$CHECK&per_page=100"
    }

    /** A file at exactly that commit, never at the branch name (F1). */
    fun rawUrl(sha: String, path: String): String {
        require(SHA.matches(sha)) { "commit shape" }
        require(path in FILES) { "path" }
        return "$RAW/$sha/$path"
    }

    fun fileName(path: String): String = path.substringAfterLast('/')
}

/** One entry of GitHub's commit list, the fields a pull reads. */
@Serializable
data class GithubCommit(
    val sha: String,
    val commit: GithubCommitBody = GithubCommitBody(),
    val parents: List<GithubRef> = emptyList(),
) {
    val date: String get() = commit.committer?.date.orEmpty()
}

@Serializable
data class GithubCommitBody(val committer: GithubPerson? = null)

@Serializable
data class GithubPerson(val date: String? = null)

@Serializable
data class GithubRef(val sha: String)

/** GitHub's check runs of one commit. */
@Serializable
data class GithubCheckRuns(@SerialName("check_runs") val checkRuns: List<GithubCheckRun> = emptyList())

@Serializable
data class GithubCheckRun(
    val id: Long,
    val name: String,
    @SerialName("head_sha") val headSha: String,
    val status: String,
    val conclusion: String? = null,
    val app: GithubApp? = null,
)

@Serializable
data class GithubApp(val slug: String? = null)

/** validate's verdict on one commit (F2). */
enum class Validation(val plain: String) {
    PASSED("validate passed"),
    FAILED("validate failed"),
    PENDING("validate still running"),
    NONE("no validate run"),
}

/** The pinned main commit the app's data comes from: what the state, the log and the pointer reports name (F1). */
@Serializable
data class DataPin(
    /** The commit every file was read at. */
    val commit: String,
    /** The commit's date on main (ISO 8601), so a newer build's data can be told from older data. */
    val commitDate: String,
    val dataDigest: String,
    val checksThrough: String,
    val staleAfter: String,
    /** The GitHub Actions check run of validate that passed on the commit (F2). */
    val checkRun: Long,
    /** SHA-256 of each file as read at the commit, for the log. */
    val sha256: Map<String, String>,
    val profiles: Int,
    val pulledAtMs: Long,
    /** The pin before this one: what a rollback goes back to, and what a bad value is traced from. */
    val previous: String? = null,
) {
    /** "main 1a2b3c4d5e6f (2026-10-24)". */
    val label: String get() = "main ${commit.take(12)} (${commitDate.take(10)})"
}

open class DataException(message: String) : Exception(message)

/** F3: a file in a format this build does not know. Nothing of the pull is used, and the last good data set stays whole. */
class SchemaRefused(message: String) : DataException(message)

/** One GET of at most [maxBytes]; throws IOException or [DataException] when it fails. */
fun interface Fetch {
    fun get(url: String, maxBytes: Long): ByteArray
}

/** How a pull ended. */
sealed interface MainPull {
    /** The pin is still main's newest validated commit; [passedOver]: newer commits without a passed validate. */
    data class Current(val commit: String, val passedOver: List<String>) : MainPull

    /** A new pin, its files checked in the folder they were read into, and the app's bundle of them. */
    data class Pinned(val pin: DataPin, val bundle: OcdBundle, val passedOver: List<String>) : MainPull

    /** No newer commit passed validate within [DataSource.WALK_BACK]: the current data stays. */
    data class Kept(val reason: String) : MainPull
}

object MainPin {
    private val json = Json { ignoreUnknownKeys = true }

    /** A check run that ended this way is a failure of the commit; skipped, cancelled and neutral decide nothing. */
    private val FAILED = setOf("failure", "timed_out", "startup_failure", "action_required")

    fun commits(text: String): List<GithubCommit> = parse("GitHub's commit list") { json.decodeFromString(ListSerializer(GithubCommit.serializer()), text) }

    fun checkRuns(text: String): GithubCheckRuns = parse("GitHub's check runs") { json.decodeFromString(GithubCheckRuns.serializer(), text) }

    /**
     * main's newest commits along first parents, as GitHub listed them, at
     * most [DataSource.WALK_BACK]; a commit whose first parent is not in the
     * list ends the line.
     */
    fun line(commits: List<GithubCommit>): List<GithubCommit> {
        val bySha = commits.associateBy { it.sha }
        val out = mutableListOf<GithubCommit>()
        val seen = HashSet<String>()
        var next = commits.firstOrNull()
        while (next != null && out.size < DataSource.WALK_BACK && seen.add(next.sha)) {
            if (!DataSource.SHA.matches(next.sha)) throw DataException("GitHub named a commit of another shape")
            out += next
            next = next.parents.firstOrNull()?.let { bySha[it.sha] }
        }
        return out
    }

    /**
     * F2, validate's verdict on [sha] from its check runs: passed when a
     * GitHub Actions run named validate on exactly this commit concluded
     * success and none failed; a run still going is pending. Skipped and
     * cancelled runs decide nothing (OCD's daily schedule skips the job).
     * The second value is the passing run's ID.
     */
    fun validation(runs: GithubCheckRuns, sha: String): Pair<Validation, Long?> {
        val mine = runs.checkRuns.filter { it.name == DataSource.CHECK && it.headSha == sha && it.app?.slug == DataSource.CHECK_APP }
        return when {
            mine.any { it.status == "completed" && it.conclusion in FAILED } -> Validation.FAILED to null
            mine.any { it.status != "completed" } -> Validation.PENDING to null
            else -> mine.firstOrNull { it.conclusion == "success" }?.let { Validation.PASSED to it.id } ?: (Validation.NONE to null)
        }
    }

    /** What [choose] picked. */
    sealed interface Choice {
        val passedOver: List<String>

        data class Current(val commit: String, override val passedOver: List<String>) : Choice
        data class Pin(val commit: GithubCommit, val checkRun: Long, override val passedOver: List<String>) : Choice
        data class Keep(val reason: String, override val passedOver: List<String>) : Choice
    }

    /**
     * Walks [line] newest first: the first commit whose validate passed is
     * the pin. Reaching the [current] pin first means nothing newer passed,
     * so a pin never moves back. [validation] costs one API call a commit; the
     * current pin costs none.
     */
    fun choose(line: List<GithubCommit>, current: String?, validation: (String) -> Pair<Validation, Long?>): Choice {
        val passedOver = mutableListOf<String>()
        for (commit in line) {
            if (commit.sha == current) return Choice.Current(commit.sha, passedOver)
            val (verdict, run) = validation(commit.sha)
            if (verdict == Validation.PASSED && run != null) return Choice.Pin(commit, run, passedOver)
            passedOver += "${commit.sha.take(8)} (${verdict.plain})"
        }
        val reason = if (line.isEmpty()) "GitHub listed no commit of main" else "none of main's newest ${line.size} commits has a passed validate: ${passedOver.joinToString()}"
        return Choice.Keep(reason, passedOver)
    }

    /**
     * One pull (F1, F2, F3): main's newest commits, validate's verdict on the
     * newest until one passed, then every file read at that commit into
     * [dir] and checked ([DataCheck.load]). [current]: the commit the app
     * holds; [force] reads it again when it is still the newest. Throws
     * [DataException] or IOException; [dir] is then not to be used.
     */
    fun pull(fetch: Fetch, current: String?, force: Boolean, dir: File, bundled: OcdBundle, nowMs: Long): MainPull {
        val line = line(commits(fetch.get(DataSource.commitsUrl(), DataSource.MAX_API_BYTES).decodeToString()))
        val check = { sha: String -> validation(checkRuns(fetch.get(DataSource.checkRunsUrl(sha), DataSource.MAX_API_BYTES).decodeToString()), sha) }
        val choice = when (val c = choose(line, current, check)) {
            is Choice.Current -> if (!force) {
                return MainPull.Current(c.commit, c.passedOver)
            } else {
                val (verdict, run) = check(c.commit)
                if (verdict != Validation.PASSED || run == null) return MainPull.Current(c.commit, c.passedOver)
                Choice.Pin(line.first { it.sha == c.commit }, run, c.passedOver)
            }
            is Choice.Keep -> return MainPull.Kept(c.reason)
            is Choice.Pin -> c
        }
        val sha = choice.commit.sha
        dir.mkdirs()
        for (path in DataSource.FILES) File(dir, DataSource.fileName(path)).writeBytes(fetch.get(DataSource.rawUrl(sha, path), DataSource.MAX_FILE_BYTES))
        val (pin, bundle) = DataCheck.load(dir, sha, choice.commit.date, choice.checkRun, nowMs, current?.takeIf { it != sha }, bundled)
        return MainPull.Pinned(pin, bundle, choice.passedOver)
    }

    private fun <T> parse(what: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw DataException("$what cannot be read")
    } catch (e: IllegalArgumentException) {
        throw DataException("$what cannot be read")
    }
}

@Serializable
private data class MetadataFile(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("data_digest") val dataDigest: String,
    @SerialName("checks_through") val checksThrough: String,
    @SerialName("stale_after") val staleAfter: String,
    val target: MetadataTarget? = null,
)

@Serializable
private data class MetadataTarget(@SerialName("apn_database_version") val apnDatabaseVersion: Int? = null)

@Serializable
private data class LookupFile(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("resolution_order") val resolutionOrder: String,
    val profiles: List<OcdProfile>,
)

/** carrier-variants.json whole: [DeviceRecords] with the file's schema_version. */
@Serializable
private data class VariantsFile(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("based_on_data_digest") val basedOnDataDigest: String,
    val capabilities: List<String>,
    val profiles: Map<String, Map<String, List<DeviceRecord>>> = emptyMap(),
)

/** What a strict read of apns-conf.xml found. */
data class ApnFileHead(val digest: String?, val version: String?, val rows: Int)

object DataCheck {
    private val json = Json { ignoreUnknownKeys = true }
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    private val VERSION = Regex("[0-9]+(\\.[0-9]+)*")

    /**
     * F3: the schema_version majors this build reads, per file. lookup.json 2 is 1 with OCD's
     * resolution order of rule round 45 as its documented order, the order this build already uses.
     */
    val KNOWN_SCHEMAS = mapOf("metadata.json" to setOf(1), "lookup.json" to setOf(1, 2), "carrier-variants.json" to setOf(1))

    /** The `<apns version>` this build reads: OCD generates version 8, against LineageOS's apns-conf.xsd. */
    const val APN_VERSION = "8"

    /** A schema_version's major: 1 for 1, "1" or "1.2"; null for anything else. */
    fun major(version: JsonElement?): Int? {
        val primitive = version as? JsonPrimitive ?: return null
        val text = primitive.content
        if (!VERSION.matches(text) || (!primitive.isString && '.' in text)) return null
        return text.substringBefore('.').toIntOrNull()
    }

    /** F3: refuses [file] unless its schema_version major is one this build reads. */
    fun schema(file: String, version: JsonElement?) {
        val known = KNOWN_SCHEMAS.getValue(file)
        val major = major(version) ?: throw SchemaRefused("$file has no schema_version this build can read")
        if (major !in known) throw SchemaRefused("$file is schema_version $major; this build reads ${known.joinToString(" or ")}")
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * apns-conf.xml read strictly: no DOCTYPE, well-formed XML with an
     * `<apns>` root, and as many `<apn>` elements as [ApnConf]'s line reader
     * finds, so the rows the app tests are the rows the file holds.
     */
    fun apnFile(file: File): ApnFileHead {
        val head = file.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            String(buffer, 0, reader.read(buffer).coerceAtLeast(0))
        }
        // OCD writes no DOCTYPE, and no parser here may resolve one.
        if (file.bufferedReader().useLines { lines -> lines.any { "<!DOCTYPE" in it || "<!ENTITY" in it } }) throw DataException("apns-conf.xml declares a DOCTYPE")
        var root: String? = null
        var version: String? = null
        var rows = 0
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = qName?.takeIf { it.isNotEmpty() } ?: localName
                if (root == null) {
                    root = name
                    version = attributes.getValue("version")
                } else if (name == "apn") {
                    rows++
                }
            }
        }
        try {
            val factory = SAXParserFactory.newInstance()
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            file.inputStream().buffered().use { factory.newSAXParser().parse(it, handler) }
        } catch (e: SAXException) {
            throw DataException("apns-conf.xml is not well-formed XML")
        }
        if (root != "apns") throw DataException("apns-conf.xml has no <apns> root")
        val lineRows = file.bufferedReader().use { ApnConf.parse(it.lineSequence()).count() }
        if (rows == 0 || lineRows != rows) throw DataException("apns-conf.xml holds $rows rows, the line reader finds $lineRows")
        return ApnFileHead(ApnConf.headerDigest(head), version, rows)
    }

    /**
     * Checks the four files read at [commit] into [dir] and returns the pin
     * and the app's bundle of them. Throws [SchemaRefused] for a format this
     * build does not know (F3) and [DataException] for anything else: a file
     * missing or cut short, a second data digest, an unknown APN version.
     */
    fun load(dir: File, commit: String, commitDate: String, checkRun: Long, nowMs: Long, previous: String?, bundled: OcdBundle): Pair<DataPin, OcdBundle> {
        if (!DataSource.SHA.matches(commit)) throw DataException("the pin is not a commit")
        val sha256 = LinkedHashMap<String, String>()
        for (path in DataSource.FILES) {
            val file = File(dir, DataSource.fileName(path))
            if (!file.isFile || file.length() == 0L) throw DataException("${file.name} is missing")
            sha256[file.name] = file.inputStream().let(::sha256Hex)
        }
        val metadata = strict("metadata.json") { json.decodeFromString(MetadataFile.serializer(), File(dir, "metadata.json").readText()) }
        schema("metadata.json", metadata.schemaVersion)
        if (!DIGEST.matches(metadata.dataDigest)) throw DataException("metadata.json has no data digest")
        metadata.target?.apnDatabaseVersion?.let { if (it.toString() != APN_VERSION) throw SchemaRefused("metadata.json names APN version $it; this build reads $APN_VERSION") }
        val lookup = strict("lookup.json") { json.decodeFromString(LookupFile.serializer(), File(dir, "lookup.json").readText()) }
        schema("lookup.json", lookup.schemaVersion)
        if (lookup.resolutionOrder != "generic_to_specific") throw SchemaRefused("lookup.json resolution order ${lookup.resolutionOrder} is not known")
        val keys = lookup.profiles.firstOrNull()?.capabilities?.keys?.sorted() ?: throw DataException("lookup.json has no profiles")
        val variants = strict("carrier-variants.json") { json.decodeFromString(VariantsFile.serializer(), File(dir, "carrier-variants.json").readText()) }
        schema("carrier-variants.json", variants.schemaVersion)
        if (variants.basedOnDataDigest != metadata.dataDigest) throw DataException("carrier-variants.json is based on another data digest")
        val apn = apnFile(File(dir, "apns-conf.xml"))
        if (apn.version != APN_VERSION) throw SchemaRefused("apns-conf.xml is APN version ${apn.version}; this build reads $APN_VERSION")
        if (apn.digest != metadata.dataDigest) throw DataException("apns-conf.xml names another data digest")
        val pin = DataPin(
            commit = commit,
            commitDate = commitDate,
            dataDigest = metadata.dataDigest,
            checksThrough = metadata.checksThrough,
            staleAfter = metadata.staleAfter,
            checkRun = checkRun,
            sha256 = sha256,
            profiles = lookup.profiles.size,
            pulledAtMs = nowMs,
            previous = previous,
        )
        // The README limits text and the device alias map are not among the files read; they come from the bundled
        // asset. The aliases only name catalog devices, and the S20's own key (samsung:SM-G981B) needs none.
        val bundle = OcdBundle(
            format = OcdBundle.FORMAT,
            source = BundleSource(
                repository = DataSource.REPOSITORY_URL,
                commit = commit,
                commitDate = commitDate.take(10),
                checksThrough = metadata.checksThrough,
                staleAfter = metadata.staleAfter,
                dataDigest = metadata.dataDigest,
                lookupSchemaVersion = major(lookup.schemaVersion),
                resolutionOrder = lookup.resolutionOrder,
            ),
            capabilityKeys = keys,
            limits = bundled.limits,
            profiles = lookup.profiles,
            deviceRecords = DeviceRecords(variants.basedOnDataDigest, variants.capabilities, variants.profiles, bundled.deviceRecords?.devices.orEmpty()),
        )
        return pin to bundle
    }

    private fun <T> strict(name: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw DataException("$name cannot be read (cut short or another shape)")
    } catch (e: IllegalArgumentException) {
        throw DataException("$name cannot be read (cut short or another shape)")
    }
}

/**
 * F4: what a SIM's entries rest on in OCD's data. A new pin makes an entry
 * due only when this changed for the SIM: the OCD answer for each switch on
 * this phone (the matched profile, its capability, the device record) or an
 * APN row of the SIM's scope. main publishes several times a day; a trigger
 * per commit would rerun trials for nothing and keep the night in its
 * on-values-only nights for good.
 */
object DataBasis {
    private const val PREFIX = "ocd:"

    /** "ocd:" and 16 hex digits of the SIM's answers and APN rows; the same data gives the same key on any pin. */
    fun key(answers: Map<OwnerFeature, OcdAnswer>, rows: List<OcdApnRow>, sim: SimIdentity): String {
        val text = buildString {
            for ((feature, a) in answers.entries.sortedBy { it.key.wire }) {
                append(feature.wire).append('=').append(listOf(a.value, a.basis, a.profileId, a.capability, a.recordSource, a.recordStack, a.sameStack).joinToString("/")).append('\n')
            }
            rows.filter { ApnConf.match(it, sim) != ApnMatch.NOT_THIS_SIM }
                .map { row -> row.attributes.toSortedMap().entries.joinToString("\u0001") { "${it.key}=${it.value}" } }
                .sorted()
                .forEach { append("apn ").append(it).append('\n') }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return PREFIX + hash.take(16)
    }

    /** Every SIM's key in one line, for the basis the background job saw: "subId/mccmnc=ocd:...", sorted. */
    fun joined(keys: Map<SimKey, String>): String = keys.entries.map { "${it.key.subId}/${it.key.mccmnc}=${it.value}" }.sorted().joinToString(",")

    /** Whether a SIM in both [seen] and [now] ([joined]) has other values. A SIM that came or went is no data change. */
    fun changed(seen: String, now: String): Boolean {
        val before = parse(seen)
        return parse(now).any { (sim, key) -> before[sim]?.let { it != key } == true }
    }

    private fun parse(joined: String): Map<String, String> = joined.split(',').mapNotNull { part ->
        val sim = part.substringBefore('=', "")
        val key = part.substringAfter('=', "")
        if (sim.isEmpty() || !key.startsWith(PREFIX)) null else sim to key
    }.toMap()
}
