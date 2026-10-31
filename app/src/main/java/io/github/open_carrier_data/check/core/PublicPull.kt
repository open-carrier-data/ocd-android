package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI
import java.security.MessageDigest

/*
 * OCD's rule round 32, U1: the read-only builds may pull OCD's newest validated main commit
 * through the owner build's path, so they stop showing stale data, and they stay read-only.
 *
 * - F1: main is resolved once to a commit through GitHub's public REST API, without a token, and
 *   every file is read at that commit (raw.githubusercontent.com/<repository>/<sha>/<path>).
 * - F2: only a commit whose `validate` check run (GitHub Actions) concluded success is taken;
 *   main's newest few commits are walked back for one.
 * - F3: a file in a schema_version this build does not read refuses the whole pull.
 * - The git blob check: each file's git blob SHA-1 equals its entry in the commit's tree, read
 *   from GitHub's contents API (one call per directory).
 * - metadata.json, carrier-variants.json and lookup.json agree on the data digest.
 *
 * The requests go only to api.github.com and raw.githubusercontent.com, only for OCD's public
 * repository ([PublicData.allowed]), and carry no device or SIM identifier: the URLs name the
 * repository, a commit and a path, and the one header the app sets is a fixed User-Agent. A
 * failed pull falls back closed: the previous good pull, or the bundled snapshot, stays whole.
 * The person starts a pull with a tap; on metered mobile data at most once a day ([PullPolicy]).
 * The pull writes nothing on the phone but the app's own files.
 */

/** OCD's public repository on GitHub, and the only hosts and paths the read-only builds' requests may reach. */
object PublicData {
    const val REPOSITORY = "open-carrier-data/open-carrier-data"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val BRANCH = "main"
    const val API_HOST = "api.github.com"
    const val RAW_HOST = "raw.githubusercontent.com"
    const val API = "https://$API_HOST/repos/$REPOSITORY"
    const val RAW = "https://$RAW_HOST/$REPOSITORY"

    /** The one header the read-only builds set: a fixed name, never the phone, its model or its Android build. */
    const val USER_AGENT = "ocd-check"

    /** The job of OCD's .github/workflows/validate.yml whose check run must have passed (F2). */
    const val CHECK = "validate"
    const val CHECK_APP = "github-actions"

    /** How many of main's newest commits a pull looks at, newest first, for one whose validate passed. */
    const val WALK_BACK = 4

    const val MAX_API_BYTES = 2L * 1024 * 1024

    val SHA = Regex("[0-9a-f]{40}")
    val BLOB = Regex("[0-9a-f]{40}")

    /** Every host a request (or a redirect) of the read-only builds may reach. */
    val HOSTS = setOf(API_HOST, RAW_HOST)

    /**
     * Whether [url] is a request for OCD's public data: https, on GitHub's API host under
     * /repos/<repository>/ or on its raw host under /<repository>/, with no user part, port or
     * fragment. Every request and every redirect is checked before it is sent.
     */
    fun allowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if (".." in path || "//" in path) return false
        return when (uri.host) {
            API_HOST -> path.startsWith("/repos/$REPOSITORY/")
            RAW_HOST -> path.startsWith("/$REPOSITORY/")
            else -> false
        }
    }

    fun commitsUrl(): String = "$API/commits?sha=$BRANCH&per_page=$WALK_BACK"

    fun checkRunsUrl(sha: String): String {
        require(SHA.matches(sha)) { "commit shape" }
        return "$API/commits/$sha/check-runs?check_name=$CHECK&per_page=100"
    }

    fun contentsUrl(dir: String, sha: String): String {
        require(SHA.matches(sha)) { "commit shape" }
        require(dir in PublicPull.DIRS) { "directory" }
        return "$API/contents/$dir?ref=$sha"
    }

    fun rawUrl(sha: String, path: String): String {
        require(SHA.matches(sha)) { "commit shape" }
        require(path in PublicPull.FILES) { "path" }
        return "$RAW/$sha/$path"
    }
}

/** One GET of at most [maxBytes] of OCD's public data; throws [PullException] or an IOException when it fails. */
fun interface PublicFetch {
    fun get(url: String, maxBytes: Long): ByteArray
}

open class PullException(message: String) : Exception(message)

/** F3: a file in a format this build does not read. Nothing of the pull is used. */
class PullSchemaRefused(message: String) : PullException(message)

/** A 404: the path does not exist at that commit. */
class PullNotFound(message: String) : PullException(message)

@Serializable
data class PublicCommit(val sha: String, val commit: PublicCommitBody = PublicCommitBody(), val parents: List<PublicRef> = emptyList()) {
    val date: String get() = commit.committer?.date.orEmpty()
}

@Serializable
data class PublicCommitBody(val committer: PublicPerson? = null)

@Serializable
data class PublicPerson(val date: String? = null)

@Serializable
data class PublicRef(val sha: String)

@Serializable
data class PublicCheckRuns(@SerialName("check_runs") val checkRuns: List<PublicCheckRun> = emptyList())

@Serializable
data class PublicCheckRun(
    val id: Long,
    val name: String,
    @SerialName("head_sha") val headSha: String,
    val status: String,
    val conclusion: String? = null,
    val app: PublicApp? = null,
)

@Serializable
data class PublicApp(val slug: String? = null)

@Serializable
data class PublicContent(val name: String, val path: String, val sha: String, val size: Long = 0, val type: String = "file")

/** validate's verdict on one commit (F2), as the owner build reads it. */
enum class PublicValidation(val plain: String) {
    PASSED("validate passed"),
    FAILED("validate failed"),
    PENDING("validate still running"),
    NONE("no validate run"),
}

/** The pulled commit, as the read-only builds keep and show it. */
@Serializable
data class PublicPin(
    val commit: String,
    @SerialName("commit_date") val commitDate: String,
    @SerialName("data_digest") val dataDigest: String,
    @SerialName("check_run") val checkRun: Long,
    /** Each file's git blob SHA-1 as the commit's tree named it. */
    val blobs: Map<String, String>,
    @SerialName("pulled_at_ms") val pulledAtMs: Long,
    /** Whether the commit has the operator statements and the identity table. */
    val operators: Boolean,
) {
    val label: String get() = "main ${commit.take(7)} (${commitDate.take(10)})"
}

@Serializable
private data class PublicMetadata(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("data_digest") val dataDigest: String,
    @SerialName("checks_through") val checksThrough: String,
    @SerialName("stale_after") val staleAfter: String,
)

@Serializable
private data class PublicLookup(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("resolution_order") val resolutionOrder: String,
    val profiles: List<OcdProfile>,
)

@Serializable
private data class PublicVariants(
    @SerialName("schema_version") val schemaVersion: JsonElement? = null,
    @SerialName("based_on_data_digest") val basedOnDataDigest: String,
    val capabilities: List<String>,
    val profiles: Map<String, Map<String, List<DeviceRecord>>> = emptyMap(),
)

object PublicPull {
    /** The directories whose listing at the commit gives each file's blob (one API call each). */
    val DIRS = listOf("generated/android", "generated/devices", OperatorFiles.DIR)

    /** The files a pull reads, with their caps; the operator files may be missing at an older commit. */
    val FILES: Map<String, Long> = linkedMapOf(
        "generated/android/metadata.json" to 1L * 1024 * 1024,
        "generated/android/lookup.json" to 32L * 1024 * 1024,
        "generated/devices/carrier-variants.json" to 64L * 1024 * 1024,
        OperatorFiles.path(OperatorFiles.STATEMENTS) to OperatorFiles.FILES.getValue(OperatorFiles.STATEMENTS),
        OperatorFiles.path(OperatorFiles.IDENTITY) to OperatorFiles.FILES.getValue(OperatorFiles.IDENTITY),
    )
    val OPTIONAL = setOf(OperatorFiles.path(OperatorFiles.STATEMENTS), OperatorFiles.path(OperatorFiles.IDENTITY))

    /** F3: the schema_version major this build reads, per file. */
    /**
     * The schema_version majors this build reads, per file. lookup.json 2 is 1 with OCD's resolution
     * order of rule round 45 as its documented order, the order this build already uses for 1.
     */
    val KNOWN_SCHEMAS = mapOf("metadata.json" to setOf(1), "lookup.json" to setOf(1, 2), "carrier-variants.json" to setOf(1))

    private val json = Json { ignoreUnknownKeys = true }
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    private val VERSION = Regex("[0-9]+(\\.[0-9]+)*")
    private val FAILED = setOf("failure", "timed_out", "startup_failure", "action_required")

    sealed interface Result {
        /** The current pull is still main's newest validated commit. */
        data class Current(val commit: String, val passedOver: List<String>) : Result

        /** A new commit, its files checked in the folder, and the bundle, statements and identity table of them. */
        data class Pinned(val pin: PublicPin, val bundle: OcdBundle, val statements: StatementsFile?, val identity: IdentityFile?, val passedOver: List<String>) : Result

        /** No newer commit passed validate: the current data stays. */
        data class Kept(val reason: String) : Result
    }

    fun commits(text: String): List<PublicCommit> = parse("GitHub's commit list") { json.decodeFromString(ListSerializer(PublicCommit.serializer()), text) }

    fun checkRuns(text: String): PublicCheckRuns = parse("GitHub's check runs") { json.decodeFromString(PublicCheckRuns.serializer(), text) }

    fun listing(text: String): List<PublicContent> = parse("GitHub's directory listing") { json.decodeFromString(ListSerializer(PublicContent.serializer()), text) }

    /** main's newest commits along first parents, at most [PublicData.WALK_BACK]. */
    fun line(commits: List<PublicCommit>): List<PublicCommit> {
        val bySha = commits.associateBy { it.sha }
        val out = mutableListOf<PublicCommit>()
        val seen = HashSet<String>()
        var next = commits.firstOrNull()
        while (next != null && out.size < PublicData.WALK_BACK && seen.add(next.sha)) {
            if (!PublicData.SHA.matches(next.sha)) throw PullException("GitHub named a commit of another shape")
            out += next
            next = next.parents.firstOrNull()?.let { bySha[it.sha] }
        }
        return out
    }

    /** F2 as the owner build reads it: a GitHub Actions run named validate on exactly [sha] concluded success, and none failed. */
    fun validation(runs: PublicCheckRuns, sha: String): Pair<PublicValidation, Long?> {
        val mine = runs.checkRuns.filter { it.name == PublicData.CHECK && it.headSha == sha && it.app?.slug == PublicData.CHECK_APP }
        return when {
            mine.any { it.status == "completed" && it.conclusion in FAILED } -> PublicValidation.FAILED to null
            mine.any { it.status != "completed" } -> PublicValidation.PENDING to null
            else -> mine.firstOrNull { it.conclusion == "success" }?.let { PublicValidation.PASSED to it.id } ?: (PublicValidation.NONE to null)
        }
    }

    /** git's blob SHA-1 of [bytes]: sha1("blob " + length + "\0" + bytes). */
    fun blob(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun major(version: JsonElement?): Int? {
        val primitive = version as? JsonPrimitive ?: return null
        val text = primitive.content
        if (!VERSION.matches(text) || (!primitive.isString && '.' in text)) return null
        return text.substringBefore('.').toIntOrNull()
    }

    private fun schema(file: String, version: JsonElement?) {
        val known = KNOWN_SCHEMAS.getValue(file)
        val major = major(version) ?: throw PullSchemaRefused("$file has no schema_version this build can read")
        if (major !in known) throw PullSchemaRefused("$file is schema_version $major; this build reads ${known.joinToString(" or ")}")
    }

    /**
     * One pull: main's newest commits, validate's verdict newest first until one passed (the
     * [current] commit reached first means nothing newer passed, so a pull never moves back),
     * the commit's tree listings, every file read at that commit into [dir] with its blob checked,
     * then the files checked. [bundled] gives the README limits and the device alias map, which
     * the pull does not read. Throws [PullException] or an IOException; [dir] is then not to be used.
     */
    fun pull(fetch: PublicFetch, current: String?, dir: File, bundled: OcdBundle, nowMs: Long): Result {
        val line = line(commits(get(fetch, PublicData.commitsUrl(), PublicData.MAX_API_BYTES).decodeToString()))
        val passedOver = mutableListOf<String>()
        var chosen: Pair<PublicCommit, Long>? = null
        for (c in line) {
            if (c.sha == current) return Result.Current(c.sha, passedOver)
            val (verdict, run) = validation(checkRuns(get(fetch, PublicData.checkRunsUrl(c.sha), PublicData.MAX_API_BYTES).decodeToString()), c.sha)
            if (verdict == PublicValidation.PASSED && run != null) {
                chosen = c to run
                break
            }
            passedOver += "${c.sha.take(8)} (${verdict.plain})"
        }
        val (commit, run) = chosen ?: return Result.Kept(
            if (line.isEmpty()) "GitHub listed no commit of main" else "none of main's newest ${line.size} commits has a passed validate: ${passedOver.joinToString()}",
        )
        val sha = commit.sha
        val blobs = HashMap<String, PublicContent>()
        for (d in DIRS) {
            val listed = try {
                listing(get(fetch, PublicData.contentsUrl(d, sha), PublicData.MAX_API_BYTES).decodeToString())
            } catch (e: PullNotFound) {
                if (d == OperatorFiles.DIR) continue
                throw e
            }
            for (c in listed) if (c.type == "file" && PublicData.BLOB.matches(c.sha)) blobs[c.path] = c
        }
        dir.mkdirs()
        val read = LinkedHashMap<String, String>()
        for ((path, cap) in FILES) {
            val entry = blobs[path] ?: if (path in OPTIONAL) continue else throw PullException("$path is not in the commit's tree")
            if (entry.size > cap) throw PullException("${entry.name} is over its cap of $cap bytes")
            val bytes = get(fetch, PublicData.rawUrl(sha, path), cap)
            val got = blob(bytes)
            if (got != entry.sha) throw PullException("${entry.name}: git blob $got, the commit's tree says ${entry.sha}")
            File(dir, entry.name).writeBytes(bytes)
            read[path] = got
        }
        val operators = OPTIONAL.all { it in read }
        if (!operators && OPTIONAL.any { it in read }) throw PullException("the commit has only one of the operator files")
        val (bundle, digest) = load(dir, sha, commit.date, bundled)
        val statements = if (operators) OperatorFiles.statements(File(dir, OperatorFiles.STATEMENTS).readText(Charsets.UTF_8)) else null
        val identity = if (operators) OperatorFiles.identity(File(dir, OperatorFiles.IDENTITY).readText(Charsets.UTF_8)) else null
        val pin = PublicPin(sha, commit.date, digest, run, read, nowMs, operators)
        return Result.Pinned(pin, bundle, statements, identity, passedOver)
    }

    /** The bundle of the files in [dir] (F3 and the data digest agreement). */
    fun load(dir: File, commit: String, commitDate: String, bundled: OcdBundle): Pair<OcdBundle, String> {
        val metadata = strict("metadata.json") { json.decodeFromString(PublicMetadata.serializer(), File(dir, "metadata.json").readText()) }
        schema("metadata.json", metadata.schemaVersion)
        if (!DIGEST.matches(metadata.dataDigest)) throw PullException("metadata.json has no data digest")
        val lookup = strict("lookup.json") { json.decodeFromString(PublicLookup.serializer(), File(dir, "lookup.json").readText()) }
        schema("lookup.json", lookup.schemaVersion)
        if (lookup.resolutionOrder != "generic_to_specific") throw PullSchemaRefused("lookup.json resolution order ${lookup.resolutionOrder} is not known")
        val keys = lookup.profiles.firstOrNull()?.capabilities?.keys?.sorted() ?: throw PullException("lookup.json has no profiles")
        val variants = strict("carrier-variants.json") { json.decodeFromString(PublicVariants.serializer(), File(dir, "carrier-variants.json").readText()) }
        schema("carrier-variants.json", variants.schemaVersion)
        if (variants.basedOnDataDigest != metadata.dataDigest) throw PullException("carrier-variants.json is based on another data digest")
        val bundle = OcdBundle(
            format = OcdBundle.FORMAT,
            source = BundleSource(
                repository = PublicData.REPOSITORY_URL,
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
        return bundle to metadata.dataDigest
    }

    /** Every request goes through the host and path check first; a request for anything else is never sent. */
    private fun get(fetch: PublicFetch, url: String, maxBytes: Long): ByteArray {
        if (!PublicData.allowed(url)) throw PullException("not a request for OCD's public data")
        return fetch.get(url, maxBytes)
    }

    private fun <T> parse(what: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw PullException("$what cannot be read")
    } catch (e: IllegalArgumentException) {
        throw PullException("$what cannot be read")
    }

    private fun <T> strict(name: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw PullException("$name cannot be read (cut short or another shape)")
    } catch (e: IllegalArgumentException) {
        throw PullException("$name cannot be read (cut short or another shape)")
    }
}

/**
 * When a pull may run (U1): only on the person's tap, or on the ROM builder's daily check; on a
 * metered network at most once a calendar day (UTC), counted from the last pull that downloaded.
 */
object PullPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Null when a pull may start now; else why not, in plain words. */
    fun refusal(metered: Boolean?, lastMeteredPullMs: Long?, nowMs: Long): String? {
        if (metered == null) return "no network"
        if (!metered || lastMeteredPullMs == null) return null
        val sameDay = lastMeteredPullMs / DAY_MS == nowMs / DAY_MS && nowMs >= lastMeteredPullMs
        return if (sameDay) "on mobile data the app downloads at most once a day; it tries again tomorrow, or now on Wi-Fi" else null
    }
}
