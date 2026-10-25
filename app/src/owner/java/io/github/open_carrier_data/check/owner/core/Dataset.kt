package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.Reader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * OCD round 30, "wholeDataset": the owner app keeps the phone's complete OCD dataset at the
 * newest validated main commit and hands it to OcdCarrierSettings, the only installer. This file
 * is the pull and the app's own checks before the hand-off (the spec "OCD round 30 dataset spec",
 * sections 2 and 2.12):
 *
 * - F1: one commit. The dataset is read at the commit the main pin took ([MainPin]: validate
 *   passed, read without a token), so the CarrierConfig part, the APN part and the app's own
 *   bundle come from one commit. data_digest must agree across metadata.json,
 *   carrier-variants.json and the apns-conf.xml header (checked by the main pin) and every
 *   dataset file (checked here).
 * - F2: the main pin's validate run, recorded in pin.json.
 * - F3: schema_version 1 and format "ocd-dataset", closed member sets in the manifest; an unknown
 *   one refuses the whole pull and the last good set stays whole.
 * - Each file's git blob SHA-1 equals its entry in the commit's tree, read from GitHub's public
 *   contents API without a token (one call per directory).
 * - Each file's SHA-256 and size equal the manifest, and dataset_digest is recomputed.
 *
 * The app writes pin.json (role "app"), which CarrierSettings checks again. Files are stored and
 * passed as plain bytes; nothing here is decompressed or rewritten.
 */

/** The dataset's files, paths and caps (spec 2.1). */
object DatasetFiles {
    const val MANIFEST = "manifest.json"
    const val PIN = "pin.json"
    const val MANIFEST_PATH = "generated/ocd-dataset/manifest.json"

    /** The three directories whose listing at the commit gives each file's blob SHA-1 (one API call each). */
    val DIRS = listOf("generated/ocd-dataset", "generated/android", "generated/devices")

    /** Each role's local name and path on main. */
    val ROLES: Map<String, Pair<String, String>> = linkedMapOf(
        "profiles" to ("profiles.json" to "generated/ocd-dataset/profiles.json"),
        "google_lane" to ("google-lane.json" to "generated/ocd-dataset/google-lane.json"),
        "device_records" to ("device-records.json" to "generated/devices/device-records.json"),
        "apn_xml" to ("apns-conf.xml" to "generated/android/apns-conf.xml"),
        "apn_index" to ("apn-index.json" to "generated/ocd-dataset/apn-index.json"),
    )

    /** The roles every dataset lists; google_lane only while the manifest says "included". */
    val REQUIRED_ROLES = setOf("profiles", "device_records", "apn_xml", "apn_index")

    private const val KIB = 1024L
    private const val MIB = 1024L * 1024

    /** Per-file caps by local name. */
    val CAPS: Map<String, Long> = mapOf(
        MANIFEST to 256 * KIB,
        "profiles.json" to 32 * MIB,
        "google-lane.json" to 8 * MIB,
        "device-records.json" to 4 * MIB,
        "apns-conf.xml" to 16 * MIB,
        "apn-index.json" to 16 * MIB,
        PIN to 64 * KIB,
    )

    /** The whole set, pin included (67,108,864 bytes). */
    const val TOTAL_CAP = 64 * MIB

    /** The JSON files whose schema_version and based_on_data_digest the app reads (every JSON file but the manifest). */
    val JSON_FILES = listOf("profiles.json", "google-lane.json", "device-records.json", "apn-index.json")

    fun pathOf(name: String): String? = if (name == MANIFEST) MANIFEST_PATH else ROLES.values.firstOrNull { it.first == name }?.second
}

/** A strict read refused the file: RFC 8259 plus the spec's own rules (2.2). */
class StrictJsonException(message: String) : DataException(message)

/**
 * A strict JSON reader for the dataset files (spec 2.2), streaming, so a 32 MiB file is read
 * with little memory. It refuses: a BOM, anything but one top-level object, a duplicate member
 * name at any level, NaN and Infinity (not JSON), a number of another shape, a U+0000 or a string
 * over 4,096 UTF-16 units, control characters in a string, and trailing data. It keeps only what
 * the caller asks for: top-level members by name, and one subtree by its path of member names.
 */
object StrictJson {
    const val MAX_STRING_UNITS = 4096
    const val MAX_DEPTH = 64

    /** What [scan] kept: the asked top-level members and the subtree at the asked path (null when absent). */
    data class Scan(val top: Map<String, JsonElement>, val captured: JsonElement?)

    fun scan(input: Reader, keep: Set<String> = emptySet(), capture: List<String>? = null): Scan =
        Parser(input, keep, capture).document()

    /** The whole document as a tree, with the same refusals; for small files (the manifest). */
    fun parse(text: String): JsonObject {
        val parser = Parser(text.reader(), emptySet(), null, buildAll = true)
        return parser.document().captured as? JsonObject ?: throw StrictJsonException("the root is not an object")
    }

    private class Parser(
        private val input: Reader,
        private val keep: Set<String>,
        private val capture: List<String>?,
        private val buildAll: Boolean = false,
    ) {
        private val buffer = CharArray(1 shl 16)
        private var length = 0
        private var position = 0
        private var offset = 0L
        private var captured: JsonElement? = null

        private fun fill(): Boolean {
            if (position < length) return true
            offset += length
            length = input.read(buffer)
            position = 0
            return length > 0
        }

        private fun peek(): Int = if (fill()) buffer[position].code else -1

        private fun next(): Int = if (fill()) buffer[position++].code else -1

        private fun fail(what: String): Nothing = throw StrictJsonException("$what at character ${offset + position}")

        private fun skipSpace() {
            while (true) {
                when (peek()) {
                    ' '.code, '\t'.code, '\n'.code, '\r'.code -> position++
                    else -> return
                }
            }
        }

        private fun expect(c: Char) {
            if (next() != c.code) fail("expected '$c'")
        }

        fun document(): Scan {
            if (peek() == 0xFEFF) fail("a byte order mark")
            skipSpace()
            if (peek() != '{'.code) fail("the root is not an object")
            val top = LinkedHashMap<String, JsonElement>()
            val root = obj(0, if (buildAll || capture != null) emptyList() else null, top)
            skipSpace()
            if (peek() != -1) fail("data after the root object")
            return Scan(top, if (buildAll) root else captured)
        }

        /** [path]: the member names from the root to here while they still follow [capture]; null once they left it. */
        private fun obj(depth: Int, path: List<String>?, top: MutableMap<String, JsonElement>?): JsonElement? {
            if (depth >= MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            expect('{')
            val names = HashSet<String>()
            val members = if (buildAll) LinkedHashMap<String, JsonElement>() else null
            skipSpace()
            if (peek() == '}'.code) {
                position++
                return members?.let(::JsonObject)
            }
            while (true) {
                skipSpace()
                if (peek() != '"'.code) fail("expected a member name")
                val name = string()
                if (!names.add(name)) fail("duplicate member name")
                skipSpace()
                expect(':')
                skipSpace()
                val childPath = path?.let { it + name }?.takeIf { buildAll || capture != null && it.size <= capture.size && capture.subList(0, it.size) == it }
                val atCapture = !buildAll && capture != null && childPath != null && childPath.size == capture.size
                val wanted = top != null && name in keep
                val value = value(depth + 1, if (atCapture) null else childPath, build = buildAll || atCapture || wanted)
                if (atCapture) captured = value
                if (wanted && value != null) top!![name] = value
                if (members != null && value != null) members[name] = value
                skipSpace()
                when (next()) {
                    ','.code -> continue
                    '}'.code -> return members?.let(::JsonObject)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int, path: List<String>?, build: Boolean): JsonElement? {
            if (depth >= MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            expect('[')
            val items = if (build) ArrayList<JsonElement>() else null
            skipSpace()
            if (peek() == ']'.code) {
                position++
                return items?.let(::JsonArray)
            }
            while (true) {
                skipSpace()
                val v = value(depth + 1, null, build)
                if (items != null && v != null) items += v
                skipSpace()
                when (next()) {
                    ','.code -> continue
                    ']'.code -> return items?.let(::JsonArray)
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun value(depth: Int, path: List<String>?, build: Boolean): JsonElement? = when (val c = peek()) {
            '{'.code -> if (build && !buildAll) Parser.sub(this, depth, path) else obj(depth, path?.takeIf { capture != null || buildAll }, null)
            '['.code -> array(depth, path, build)
            '"'.code -> string().let { if (build) JsonPrimitive(it) else null }
            't'.code -> literal("true").let { if (build) JsonPrimitive(true) else null }
            'f'.code -> literal("false").let { if (build) JsonPrimitive(false) else null }
            'n'.code -> literal("null").let { if (build) JsonNull else null }
            else -> if (c == '-'.code || c in '0'.code..'9'.code) number(build) else fail("unexpected character")
        }

        /** An object kept whole (a captured subtree or a kept top-level member): built with the same checks. */
        private fun objBuilt(depth: Int): JsonObject {
            if (depth >= MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            expect('{')
            val names = HashSet<String>()
            val members = LinkedHashMap<String, JsonElement>()
            skipSpace()
            if (peek() == '}'.code) {
                position++
                return JsonObject(members)
            }
            while (true) {
                skipSpace()
                if (peek() != '"'.code) fail("expected a member name")
                val name = string()
                if (!names.add(name)) fail("duplicate member name")
                skipSpace()
                expect(':')
                skipSpace()
                val v = if (peek() == '{'.code) objBuilt(depth + 1) else value(depth + 1, null, true)!!
                members[name] = v
                skipSpace()
                when (next()) {
                    ','.code -> continue
                    '}'.code -> return JsonObject(members)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun literal(word: String): Unit = word.forEach { if (next() != it.code) fail("not a JSON literal") }

        private fun number(build: Boolean): JsonElement? {
            val text = StringBuilder()
            fun digits(): Int {
                var n = 0
                while (peek() in '0'.code..'9'.code) {
                    text.append(next().toChar())
                    n++
                }
                return n
            }
            if (peek() == '-'.code) text.append(next().toChar())
            if (peek() == '0'.code) {
                text.append(next().toChar())
                if (peek() in '0'.code..'9'.code) fail("a number with a leading zero")
            } else if (digits() == 0) {
                fail("not a number")
            }
            var integer = true
            if (peek() == '.'.code) {
                integer = false
                text.append(next().toChar())
                if (digits() == 0) fail("a fraction without digits")
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                integer = false
                text.append(next().toChar())
                if (peek() == '+'.code || peek() == '-'.code) text.append(next().toChar())
                if (digits() == 0) fail("an exponent without digits")
            }
            if (!build) return null
            val s = text.toString()
            return if (integer) s.toLongOrNull()?.let(::JsonPrimitive) ?: fail("an integer outside the long range") else JsonPrimitive(s.toDouble().also { if (!it.isFinite()) fail("a number that is not finite") })
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == -1 -> fail("an unterminated string")
                    c == '"'.code -> break
                    c == '\\'.code -> when (val e = next()) {
                        '"'.code -> out.append('"')
                        '\\'.code -> out.append('\\')
                        '/'.code -> out.append('/')
                        'b'.code -> out.append('\b')
                        'f'.code -> out.append('\u000C')
                        'n'.code -> out.append('\n')
                        'r'.code -> out.append('\r')
                        't'.code -> out.append('\t')
                        'u'.code -> {
                            var v = 0
                            repeat(4) {
                                val h = next()
                                val d = Character.digit(h, 16)
                                if (h == -1 || d < 0) fail("a bad \\u escape")
                                v = v * 16 + d
                            }
                            out.append(v.toChar())
                        }
                        else -> fail("a bad escape ${if (e == -1) "" else e.toChar()}")
                    }
                    c < 0x20 -> fail("a control character in a string")
                    else -> out.append(c.toChar())
                }
                if (out.length > MAX_STRING_UNITS) fail("a string over $MAX_STRING_UNITS UTF-16 units")
            }
            if (out.indexOf('\u0000') >= 0) fail("a U+0000 in a string")
            return out.toString()
        }

        companion object {
            fun sub(p: Parser, depth: Int, @Suppress("UNUSED_PARAMETER") path: List<String>?): JsonObject = p.objBuilt(depth)
        }
    }
}

/** One entry of the manifest's `files` (spec 2.4). */
data class ManifestFile(val role: String, val name: String, val path: String, val bytes: Long, val sha256: String)

/** The dataset's header (spec 2.4), read strictly with closed member sets. */
data class DatasetManifest(
    val dataDigest: String,
    val generatedAt: String,
    val generator: String,
    val apnDatabaseVersion: Int,
    val googleLane: String,
    val files: List<ManifestFile>,
    val datasetDigest: String,
    val counts: Map<String, Long>,
) {
    fun file(name: String): ManifestFile? = files.firstOrNull { it.name == name }

    companion object {
        private val MEMBERS = setOf("schema_version", "format", "data_digest", "generated_at", "generator", "apn_database_version", "google_lane", "files", "dataset_digest", "counts")
        private val FILE_MEMBERS = setOf("role", "name", "path", "bytes", "sha256")
        private val COUNT_MEMBERS = setOf("networks", "profiles", "fill_only_profiles", "google_entries", "apn_rows", "records")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val HEX64 = Regex("[0-9a-f]{64}")
        val GENERATED_AT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
        const val FORMAT = "ocd-dataset"
        const val SCHEMA = 1

        /** Throws [SchemaRefused] for a version or format this build does not read, [DataException] for anything else. */
        fun parse(text: String): DatasetManifest {
            val o = StrictJson.parse(text)
            val version = (o["schema_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version != SCHEMA) throw SchemaRefused("manifest.json is schema_version ${o["schema_version"]}; this build reads $SCHEMA")
            if (str(o, "format") != FORMAT) throw SchemaRefused("manifest.json has format ${o["format"]}; this build reads $FORMAT")
            (o.keys - MEMBERS).takeIf { it.isNotEmpty() }?.let { throw SchemaRefused("manifest.json has members this build does not know: ${it.sorted()}") }
            (MEMBERS - o.keys).takeIf { it.isNotEmpty() }?.let { throw DataException("manifest.json lacks ${it.sorted()}") }
            val digest = str(o, "data_digest")?.takeIf(DIGEST::matches) ?: throw DataException("manifest.json has no data digest")
            val generatedAt = str(o, "generated_at")?.takeIf(GENERATED_AT::matches) ?: throw DataException("manifest.json has no generated_at")
            val generator = str(o, "generator") ?: throw DataException("manifest.json has no generator")
            val apn = (o["apn_database_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw DataException("manifest.json has no APN version")
            if (apn.toString() != DataCheck.APN_VERSION) throw SchemaRefused("manifest.json names APN version $apn; this build reads ${DataCheck.APN_VERSION}")
            val lane = str(o, "google_lane")?.takeIf { it == "included" || it == "absent" } ?: throw SchemaRefused("manifest.json google_lane ${o["google_lane"]} is not known")
            val files = (o["files"] as? JsonArray ?: throw DataException("manifest.json files is not a list")).map { e ->
                val f = e as? JsonObject ?: throw DataException("a manifest file entry is not an object")
                if (f.keys != FILE_MEMBERS) throw SchemaRefused("a manifest file entry has the members ${f.keys.sorted()}")
                val role = str(f, "role") ?: throw DataException("a manifest file has no role")
                val (name, path) = DatasetFiles.ROLES[role] ?: throw SchemaRefused("manifest.json names the role $role, which this build does not know")
                if (str(f, "name") != name || str(f, "path") != path) throw DataException("manifest.json names $role as ${f["name"]} at ${f["path"]}")
                val bytes = (f["bytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: throw DataException("manifest.json has no size for $name")
                val sha = str(f, "sha256")?.takeIf(HEX64::matches) ?: throw DataException("manifest.json has no SHA-256 for $name")
                ManifestFile(role, name, path, bytes, sha)
            }
            val roles = files.map { it.role }
            if (roles.size != roles.toSet().size) throw DataException("manifest.json lists a role twice")
            val expected = DatasetFiles.REQUIRED_ROLES + if (lane == "included") setOf("google_lane") else emptySet()
            if (roles.toSet() != expected) throw DataException("manifest.json lists ${roles.sorted()}, expected ${expected.sorted()} (google_lane $lane)")
            val datasetDigest = str(o, "dataset_digest")?.takeIf(DIGEST::matches) ?: throw DataException("manifest.json has no dataset digest")
            val counts = (o["counts"] as? JsonObject ?: throw DataException("manifest.json counts is not an object")).also {
                if (it.keys != COUNT_MEMBERS) throw SchemaRefused("manifest.json counts has the members ${it.keys.sorted()}")
            }.mapValues { (k, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw DataException("manifest.json count $k is not a number") }
            return DatasetManifest(digest, generatedAt, generator, apn, lane, files, datasetDigest, counts)
        }

        private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * dataset_digest (spec 2.4): SHA-256 over, for each file in byte order of its name, the
         * lines name, sha256 and bytes, each ending with "\n".
         */
        fun datasetDigest(files: List<ManifestFile>): String {
            val text = files.sortedWith { a, b -> compareBytes(a.name, b.name) }.joinToString("") { "${it.name}\n${it.sha256}\n${it.bytes}\n" }
            return "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex()
        }

        private fun compareBytes(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }
    }
}

/** One file's entry in pin.json: git's blob SHA-1 at the commit and its size. */
@Serializable
data class PinFile(val blob: String, val bytes: Long)

/** pin.json (spec 2.11), role "app": written by this app for each download, never by the data side. */
@Serializable
data class DatasetPin(
    val schema: String = SCHEMA,
    val role: String = "app",
    val repository: String = DataSource.REPOSITORY,
    val commit: String,
    @SerialName("commit_date") val commitDate: String,
    val validate: PinValidate,
    /** Keyed by the path on main: the manifest and exactly the manifest's files. */
    val files: Map<String, PinFile>,
    @SerialName("fetched_at") val fetchedAt: String,
    @SerialName("fetched_by") val fetchedBy: String,
) {
    fun json(): String = wire.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = "ocd-dataset-pin/1"
        private val wire = Json { encodeDefaults = true }
        private val strict = Json { ignoreUnknownKeys = false }
        fun decode(text: String): DatasetPin = strict.decodeFromString(serializer(), text)
    }
}

@Serializable
data class PinValidate(@SerialName("check_run_id") val checkRunId: Long, val conclusion: String = "success")

/** One entry of GitHub's directory listing (contents API), the fields the blob check reads. */
@Serializable
data class GithubContent(val name: String, val path: String, val sha: String, val size: Long = 0, val type: String = "file")

/** The dataset of one commit, checked and in its folder, ready for the hand-off. */
@Serializable
data class PulledDataset(
    val commit: String,
    val dataDigest: String,
    val datasetDigest: String,
    val generatedAt: String,
    val googleLane: String,
    /** The local names handed over, the manifest first. */
    val names: List<String>,
    val bytes: Long,
    val pulledAtMs: Long,
    /** The folder under the app's files, by commit. */
    val folder: String,
    /** The commit's date on main, kept for the record; it does not order datasets (spec 2.8). */
    val commitDate: String = "",
) {
    val label: String get() = "main ${commit.take(12)} (generated ${generatedAt.take(16).replace('T', ' ')})"
}

/** How the dataset pull of a commit ended. */
sealed interface DatasetPull {
    data class Pulled(val dataset: PulledDataset, val pin: DatasetPin, val reused: Int, val downloaded: Int) : DatasetPull

    /** Main has no dataset at this commit yet (no manifest.json): the data side has not published it. Not an error. */
    data class NotPublished(val reason: String) : DatasetPull
}

object GitBlob {
    /** git's blob SHA-1: sha1("blob " + length + "\0" + bytes), lowercase hex. */
    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }

    fun sha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.update(bytes)
        return digest.digest().hex()
    }
}

/** A 404 of a raw file: the path does not exist at that commit. */
class NotFound(message: String) : DataException(message)

object DatasetSource {
    val BLOB = Regex("[0-9a-f]{40}")

    /** GitHub's listing of one directory at a commit, without a token (spec 2.11). */
    fun contentsUrl(dir: String, sha: String): String {
        require(DataSource.SHA.matches(sha)) { "commit shape" }
        require(dir in DatasetFiles.DIRS) { "directory" }
        return "${DataSource.API}/contents/$dir?ref=$sha"
    }

    /** A dataset file at exactly that commit (F1). */
    fun rawUrl(sha: String, path: String): String {
        require(DataSource.SHA.matches(sha)) { "commit shape" }
        require(path == DatasetFiles.MANIFEST_PATH || DatasetFiles.ROLES.values.any { it.second == path }) { "path" }
        return "${DataSource.RAW}/$sha/$path"
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    fun listing(text: String): List<GithubContent> = try {
        lenient.decodeFromString(ListSerializer(GithubContent.serializer()), text)
    } catch (e: Exception) {
        throw DataException("GitHub's directory listing cannot be read")
    }
}

object DatasetCheck {
    private val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun utc(ms: Long): String = utc.format(Instant.ofEpochMilli(ms))

    /**
     * Reads the dataset of [commit] into [dir] (F1 to F3, the blob check) and writes pin.json.
     * [pin]: the main pin of the same commit (validate's run, the data digest metadata.json,
     * carrier-variants.json and apns-conf.xml agreed on). [previous]: the last pulled dataset's
     * folder, whose files are reused where the blob is the same, so a commit that changes one file
     * downloads one file. Throws [SchemaRefused] (F3), [DataException] or IOException; [dir] is
     * then not to be used and the last good set stays whole.
     */
    fun pull(fetch: Fetch, pin: DataPin, dir: File, previous: File?, fetchedBy: String, nowMs: Long): DatasetPull {
        val sha = pin.commit
        if (!DataSource.SHA.matches(sha)) throw DataException("the pin is not a commit")
        // The tree first: the blob of every file at the commit (three calls), so unchanged files need no download.
        val blobs = HashMap<String, GithubContent>()
        for (d in DatasetFiles.DIRS) {
            val listed = try {
                DatasetSource.listing(fetch.get(DatasetSource.contentsUrl(d, sha), DataSource.MAX_API_BYTES).decodeToString())
            } catch (e: NotFound) {
                if (d == "generated/ocd-dataset") return DatasetPull.NotPublished("main ${sha.take(12)} has no generated/ocd-dataset yet")
                throw e
            }
            for (c in listed) if (c.type == "file" && DatasetSource.BLOB.matches(c.sha)) blobs[c.path] = c
        }
        val manifestEntry = blobs[DatasetFiles.MANIFEST_PATH] ?: return DatasetPull.NotPublished("main ${sha.take(12)} has no generated/ocd-dataset/manifest.json yet")
        dir.mkdirs()
        var reused = 0
        var downloaded = 0
        fun place(name: String, path: String, cap: Long) {
            val entry = blobs[path] ?: throw DataException("$path is not in the commit's tree")
            if (entry.size > cap) throw DataException("$name is over its cap of $cap bytes")
            val target = File(dir, name)
            val old = previous?.let { File(it, name) }?.takeIf { it.isFile && it.length() == entry.size }
            if (old != null && GitBlob.sha1(old) == entry.sha) {
                old.copyTo(target, overwrite = true)
                reused++
            } else {
                target.writeBytes(fetch.get(DatasetSource.rawUrl(sha, path), cap))
                downloaded++
            }
            val blob = GitBlob.sha1(target)
            if (blob != entry.sha) throw DataException("$name: git blob $blob, the commit's tree says ${entry.sha}")
        }
        place(DatasetFiles.MANIFEST, DatasetFiles.MANIFEST_PATH, DatasetFiles.CAPS.getValue(DatasetFiles.MANIFEST))
        val manifest = DatasetManifest.parse(File(dir, DatasetFiles.MANIFEST).readText(Charsets.UTF_8))
        for (f in manifest.files) place(f.name, f.path, DatasetFiles.CAPS.getValue(f.name))
        verify(dir, manifest, pin.dataDigest)
        val files = linkedMapOf(DatasetFiles.MANIFEST_PATH to PinFile(manifestEntry.sha, File(dir, DatasetFiles.MANIFEST).length()))
        for (f in manifest.files) files[f.path] = PinFile(blobs.getValue(f.path).sha, f.bytes)
        val datasetPin = DatasetPin(
            commit = sha,
            commitDate = pin.commitDate,
            validate = PinValidate(pin.checkRun),
            files = files,
            fetchedAt = utc(nowMs),
            fetchedBy = fetchedBy,
        )
        val pinText = datasetPin.json()
        if (pinText.length > DatasetFiles.CAPS.getValue(DatasetFiles.PIN)) throw DataException("pin.json is over its cap")
        File(dir, DatasetFiles.PIN).writeText(pinText, Charsets.UTF_8)
        val total = manifest.files.sumOf { it.bytes } + File(dir, DatasetFiles.MANIFEST).length() + pinText.length
        if (total > DatasetFiles.TOTAL_CAP) throw DataException("the dataset is $total bytes, over ${DatasetFiles.TOTAL_CAP}")
        val names = listOf(DatasetFiles.MANIFEST) + manifest.files.map { it.name }
        return DatasetPull.Pulled(
            PulledDataset(sha, manifest.dataDigest, manifest.datasetDigest, manifest.generatedAt, manifest.googleLane, names, total, nowMs, dir.name, pin.commitDate),
            datasetPin, reused, downloaded,
        )
    }

    /**
     * The app's checks of a dataset in [dir] before the hand-off (spec 2.12): each file's size and
     * SHA-256 equal the manifest, dataset_digest recomputed, the manifest's data digest is the
     * pin's ([dataDigest], which metadata.json, carrier-variants.json and the apns-conf.xml header
     * named), every JSON file strict with schema_version 1 and that data digest, and the APN file's
     * header and version.
     */
    fun verify(dir: File, manifest: DatasetManifest, dataDigest: String) {
        if (manifest.dataDigest != dataDigest) throw DataException("manifest.json names data digest ${manifest.dataDigest.take(19)}; the commit's metadata.json names ${dataDigest.take(19)}")
        for (f in manifest.files) {
            val file = File(dir, f.name)
            if (!file.isFile) throw DataException("${f.name} is missing")
            if (file.length() != f.bytes) throw DataException("${f.name} is ${file.length()} bytes; the manifest says ${f.bytes}")
            val sha = DataCheck.sha256Hex(file.inputStream())
            if (sha != f.sha256) throw DataException("${f.name}: SHA-256 differs from the manifest")
        }
        if (DatasetManifest.datasetDigest(manifest.files) != manifest.datasetDigest) throw DataException("dataset_digest does not match the files")
        for (name in DatasetFiles.JSON_FILES) {
            val file = File(dir, name).takeIf { manifest.file(name) != null } ?: continue
            val top = file.bufferedReader(Charsets.UTF_8).use { StrictJson.scan(it, setOf("schema_version", "based_on_data_digest")).top }
            val version = (top["schema_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version != DatasetManifest.SCHEMA) throw SchemaRefused("$name is schema_version ${top["schema_version"]}; this build reads ${DatasetManifest.SCHEMA}")
            val based = (top["based_on_data_digest"] as? JsonPrimitive)?.contentOrNull
            if (based != manifest.dataDigest) throw DataException("$name is based on another data digest")
        }
        val apn = DataCheck.apnFile(File(dir, "apns-conf.xml"))
        if (apn.version != DataCheck.APN_VERSION) throw SchemaRefused("apns-conf.xml is APN version ${apn.version}; this build reads ${DataCheck.APN_VERSION}")
        if (apn.digest != manifest.dataDigest) throw DataException("apns-conf.xml names another data digest")
    }

    /**
     * The profiles of one network from profiles.json (spec 2.5), read strictly and streamed, with
     * the dataset's key registry and groups; null when the network is not in the file.
     */
    fun network(profiles: File, mcc: String, mnc: String): JsonObject? = profiles.bufferedReader(Charsets.UTF_8).use {
        StrictJson.scan(it, capture = listOf("networks", "$mcc-$mnc")).captured as? JsonObject
    }

    /** The registry and groups of profiles.json (its top-level `keys` and `groups`). */
    fun registry(profiles: File): Pair<JsonObject?, JsonObject?> = profiles.bufferedReader(Charsets.UTF_8).use {
        val top = StrictJson.scan(it, keep = setOf("keys", "groups")).top
        (top["keys"] as? JsonObject) to (top["groups"] as? JsonObject)
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
