package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.BundleSource
import io.github.open_carrier_data.check.core.DeviceRecords
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OcdProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/*
 * A tagged OCD data release from GitHub (the owner direction, 2026-10-19:
 * "load the data" and "update pulls"). Every asset is checked against the
 * release's SHA256SUMS and release.json. The data digest cannot be
 * recomputed on the phone, so it must be the same in release.json,
 * metadata.json, carrier-variants.json (based_on_data_digest) and the
 * apns-conf.xml header. Anything else refuses the release.
 */

object ReleaseSource {
    const val REPOSITORY = "open-carrier-data/open-carrier-data"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    val TAG = Regex("data-\\d{4}\\.\\d{2}(?:\\.\\d+)?")

    /** The assets the owner build downloads; release-notes.md is not needed. */
    val ASSETS = listOf("SHA256SUMS", "release.json", "metadata.json", "lookup.json", "carrier-variants.json", "apns-conf.xml")

    /** The size cap per asset, well above data-2026.10's largest (10.3 MB). */
    const val MAX_ASSET_BYTES = 96L * 1024 * 1024

    fun downloadUrl(tag: String, asset: String): String {
        require(TAG.matches(tag)) { "tag shape" }
        require(asset in ASSETS) { "asset" }
        return "$REPOSITORY_URL/releases/download/$tag/$asset"
    }
}

/** GitHub's latest-release answer, the fields the owner build reads. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(val name: String, val size: Long = 0, @SerialName("browser_download_url") val url: String = "")

@Serializable
data class ReleaseFile(val asset: String, val sha256: String, val bytes: Long? = null)

@Serializable
data class ReleaseManifest(
    val tag: String,
    val commit: String,
    @SerialName("commit_date") val commitDate: String,
    @SerialName("data_digest") val dataDigest: String,
    @SerialName("checks_through") val checksThrough: String,
    @SerialName("stale_after") val staleAfter: String,
    val rehearsal: Boolean = false,
    @SerialName("failed_gates") val failedGates: List<String> = emptyList(),
    val files: List<ReleaseFile> = emptyList(),
)

@Serializable
private data class MetadataHead(@SerialName("data_digest") val dataDigest: String, @SerialName("checks_through") val checksThrough: String? = null)

@Serializable
private data class VariantsHead(@SerialName("based_on_data_digest") val basedOnDataDigest: String)

@Serializable
private data class LookupFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("resolution_order") val resolutionOrder: String,
    val profiles: List<OcdProfile>,
)

/** What a checked release holds, for the owner screen and the log. */
@Serializable
data class ReleaseInfo(
    val tag: String,
    val commit: String,
    val dataDigest: String,
    val checksThrough: String,
    val staleAfter: String,
    val sha256: Map<String, String>,
    val profiles: Int,
    val pulledAtMs: Long,
)

class ReleaseException(message: String) : Exception(message)

object ReleaseCheck {
    private val json = Json { ignoreUnknownKeys = true }
    private val SUM_LINE = Regex("^([0-9a-f]{64}) [ *]?(\\S+)$")
    private val HEX40 = Regex("[0-9a-f]{40}")
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")

    fun parseSums(text: String): Map<String, String> {
        val sums = LinkedHashMap<String, String>()
        for (line in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val match = SUM_LINE.matchEntire(line) ?: throw ReleaseException("SHA256SUMS has a line of another shape")
            if (sums.put(match.groupValues[2], match.groupValues[1]) != null) throw ReleaseException("SHA256SUMS lists ${match.groupValues[2]} twice")
        }
        return sums
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
     * Checks a downloaded release in [dir] for [tag] and returns what it
     * holds. Throws [ReleaseException] on the first problem.
     */
    fun verify(dir: File, tag: String, nowMs: Long): ReleaseInfo {
        if (!ReleaseSource.TAG.matches(tag)) throw ReleaseException("the tag $tag is not a data release tag")
        val sums = parseSums(File(dir, "SHA256SUMS").readText())
        val actual = LinkedHashMap<String, String>()
        for (asset in ReleaseSource.ASSETS - "SHA256SUMS") {
            val file = File(dir, asset)
            if (!file.isFile) throw ReleaseException("$asset is missing")
            val expected = sums[asset] ?: throw ReleaseException("SHA256SUMS does not list $asset")
            val got = file.inputStream().let(::sha256Hex)
            if (got != expected) throw ReleaseException("$asset does not match SHA256SUMS")
            actual[asset] = got
        }
        val manifest = runCatching { json.decodeFromString(ReleaseManifest.serializer(), File(dir, "release.json").readText()) }
            .getOrElse { throw ReleaseException("release.json cannot be read: ${it.message}") }
        if (manifest.tag != tag) throw ReleaseException("release.json names ${manifest.tag}, not $tag")
        if (manifest.rehearsal) throw ReleaseException("release.json is a rehearsal")
        if (manifest.failedGates.isNotEmpty()) throw ReleaseException("release.json lists failed gates")
        if (!HEX40.matches(manifest.commit)) throw ReleaseException("release.json has no commit")
        if (!DIGEST.matches(manifest.dataDigest)) throw ReleaseException("release.json has no data digest")
        for (asset in listOf("apns-conf.xml", "lookup.json", "metadata.json", "carrier-variants.json")) {
            val listed = manifest.files.firstOrNull { it.asset == asset } ?: throw ReleaseException("release.json does not list $asset")
            if (listed.sha256 != actual[asset]) throw ReleaseException("release.json and SHA256SUMS disagree on $asset")
            listed.bytes?.let { if (File(dir, asset).length() != it) throw ReleaseException("$asset has another size than release.json says") }
        }
        val metadata = json.decodeFromString(MetadataHead.serializer(), File(dir, "metadata.json").readText())
        if (metadata.dataDigest != manifest.dataDigest) throw ReleaseException("metadata.json has another data digest")
        val variantsHead = File(dir, "carrier-variants.json").bufferedReader().use { reader ->
            val head = CharArray(4096)
            String(head, 0, reader.read(head).coerceAtLeast(0))
        }
        val variantsDigest = Regex("\"based_on_data_digest\"\\s*:\\s*\"(sha256:[0-9a-f]{64})\"").find(variantsHead)?.groupValues?.get(1)
            ?: json.decodeFromString(VariantsHead.serializer(), File(dir, "carrier-variants.json").readText()).basedOnDataDigest
        if (variantsDigest != manifest.dataDigest) throw ReleaseException("carrier-variants.json is based on another data digest")
        val apnHead = File(dir, "apns-conf.xml").bufferedReader().use { reader ->
            val head = CharArray(2048)
            String(head, 0, reader.read(head).coerceAtLeast(0))
        }
        if (ApnConf.headerDigest(apnHead) != manifest.dataDigest) throw ReleaseException("apns-conf.xml names another data digest")
        return ReleaseInfo(
            tag = tag,
            commit = manifest.commit,
            dataDigest = manifest.dataDigest,
            checksThrough = manifest.checksThrough,
            staleAfter = manifest.staleAfter,
            sha256 = actual,
            profiles = 0,
            pulledAtMs = nowMs,
        )
    }

    /**
     * The app's bundle format from a checked release: lookup.json's profiles
     * and carrier-variants.json's records. The README limits text and the
     * device alias map are not release assets, so they come from the bundled
     * asset ([bundled]); the aliases only name catalog devices, and the S20's
     * own key (samsung:SM-G981B) needs none.
     */
    fun bundle(dir: File, info: ReleaseInfo, manifest: ReleaseManifest, bundled: OcdBundle): OcdBundle {
        val lookup = json.decodeFromString(LookupFile.serializer(), File(dir, "lookup.json").readText())
        if (lookup.schemaVersion != 1) throw ReleaseException("lookup.json schema ${lookup.schemaVersion} is not known")
        if (lookup.resolutionOrder != "generic_to_specific") throw ReleaseException("lookup.json resolution order is not known")
        val variants = json.decodeFromString(DeviceRecords.serializer(), File(dir, "carrier-variants.json").readText())
        val keys = lookup.profiles.firstOrNull()?.capabilities?.keys?.sorted() ?: throw ReleaseException("lookup.json has no profiles")
        return OcdBundle(
            format = OcdBundle.FORMAT,
            source = BundleSource(
                repository = ReleaseSource.REPOSITORY_URL,
                commit = info.commit,
                commitDate = manifest.commitDate.take(10),
                checksThrough = info.checksThrough,
                staleAfter = info.staleAfter,
                dataDigest = info.dataDigest,
                lookupSchemaVersion = lookup.schemaVersion,
                resolutionOrder = lookup.resolutionOrder,
            ),
            capabilityKeys = keys,
            limits = bundled.limits,
            profiles = lookup.profiles,
            deviceRecords = variants.copy(devices = bundled.deviceRecords?.devices.orEmpty()),
        )
    }

    fun manifest(dir: File): ReleaseManifest = json.decodeFromString(ReleaseManifest.serializer(), File(dir, "release.json").readText())

    fun latest(text: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), text)
}
