package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.SimIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/*
 * The data the APN candidates are computed from (round 32 D8 and D7 rule 4, rounds 33 to 36), all
 * read at the installed commit (F1) from OCD's public repository without a token, each file's git
 * blob checked: the SIM profile's JSON (carriers/open/<profile>.json, one contents call), and for D7
 * the network's profiles the data left out (the commit's tree, three calls, then one call per
 * candidate profile). The apns-conf.xml and apn-index.json rows come from the pulled dataset.
 */

@Serializable
private data class GithubFile(val name: String = "", val path: String = "", val sha: String, val size: Long = 0, val encoding: String? = null, val content: String? = null, val type: String = "file")

@Serializable
private data class GithubTree(val sha: String = "", val tree: List<GithubTreeEntry> = emptyList(), val truncated: Boolean = false)

@Serializable
private data class GithubTreeEntry(val path: String, val type: String, val sha: String, val size: Long = 0)

object ApnCandidateData {
    val PROFILE_ID = Regex("open\\.([0-9]{5,6})\\.([0-9a-f]{12})")
    const val MAX_PROFILE_BYTES = 1L * 1024 * 1024
    const val MAX_TREE_BYTES = 6L * 1024 * 1024

    /** At most this many left-out profiles of one network are read for D7. */
    const val MAX_LEFT_OUT_READS = 6

    private val json = Json { ignoreUnknownKeys = true }

    fun profilePath(profileId: String): String? = PROFILE_ID.matchEntire(profileId)?.let { "carriers/open/${it.groupValues[1]}.${it.groupValues[2]}.json" }

    fun contentsUrl(commit: String, path: String): String {
        require(DataSource.SHA.matches(commit)) { "commit shape" }
        require(Regex("carriers/open/[0-9]{5,6}\\.[0-9a-f]{12}\\.json").matches(path)) { "path" }
        return "${DataSource.API}/contents/$path?ref=$commit"
    }

    fun treeUrl(sha: String): String {
        require(DataSource.SHA.matches(sha)) { "tree shape" }
        return "${DataSource.API}/git/trees/$sha"
    }

    /** A contents answer for one file: its bytes, with the git blob checked against the answer's SHA. */
    fun file(text: String): ByteArray {
        val f = try {
            json.decodeFromString(GithubFile.serializer(), text)
        } catch (e: Exception) {
            throw DataException("GitHub's file answer cannot be read")
        }
        if (f.type != "file" || f.encoding != "base64" || f.content == null || !DatasetSource.BLOB.matches(f.sha)) throw DataException("GitHub's file answer has no content")
        val bytes = Base64.getMimeDecoder().decode(f.content)
        val blob = GitBlob.sha1(bytes)
        if (blob != f.sha) throw DataException("${f.name}: git blob $blob, the commit's tree says ${f.sha}")
        return bytes
    }

    /** The profile's JSON at [commit], blob-checked; null when the commit has no such file. */
    fun profile(fetch: Fetch, commit: String, profileId: String): JsonObject? {
        val path = profilePath(profileId) ?: return null
        val bytes = try {
            file(fetch.get(contentsUrl(commit, path), MAX_PROFILE_BYTES * 2).decodeToString())
        } catch (e: NotFound) {
            return null
        }
        val o = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: throw DataException("$path is not a JSON object")
        if ((o["profile_id"] as? JsonPrimitive)?.contentOrNull != profileId) throw DataException("$path names another profile")
        return o
    }

    /** The entries of one tree answer, refused when GitHub cut it short. */
    fun tree(text: String): List<Triple<String, String, String>> {
        val t = try {
            json.decodeFromString(GithubTree.serializer(), text)
        } catch (e: Exception) {
            throw DataException("GitHub's tree answer cannot be read")
        }
        if (t.truncated) throw DataException("GitHub cut the tree short")
        return t.tree.map { Triple(it.path, it.type, it.sha) }
    }

    /**
     * D7 rule 4's left-out scope: the profiles of [mccmnc] at [commit] that the published lookup leaves
     * out ([known]: its profile IDs) and whose rows' selector this SIM matches. Their rows as the
     * generator would write them ([ProfileApnRows]). Empty when there are none.
     */
    fun leftOutScopes(fetch: Fetch, commit: String, mccmnc: String, known: Set<String>, sim: SimIdentity): List<Map<String, String>> {
        val root = tree(fetch.get(treeUrl(commit), DataSource.MAX_API_BYTES).decodeToString())
        val carriers = root.firstOrNull { it.first == "carriers" && it.second == "tree" }?.third ?: return emptyList()
        val open = tree(fetch.get(treeUrl(carriers), DataSource.MAX_API_BYTES).decodeToString()).firstOrNull { it.first == "open" && it.second == "tree" }?.third ?: return emptyList()
        val names = tree(fetch.get(treeUrl(open), MAX_TREE_BYTES).decodeToString())
            .filter { it.second == "blob" && it.first.startsWith("$mccmnc.") && it.first.endsWith(".json") }
            .map { "open." + it.first.removeSuffix(".json") }
            .filter { PROFILE_ID.matches(it) && it !in known }
        val out = mutableListOf<Map<String, String>>()
        for (id in names.take(MAX_LEFT_OUT_READS)) {
            val profile = profile(fetch, commit, id) ?: continue
            val rows = ProfileApnRows.records(profile).filter { ApnConf.match(OcdApnRow(it), sim) == ApnMatch.MATCHES && it["mvno_type"] != null }
            out += rows
        }
        return out
    }

    /** The row keys apn-index.json gives [profileId], in file order; [text] is the whole file. */
    fun writtenKeys(text: String, profileId: String): List<String> {
        val rows = runCatching { (Json.parseToJsonElement(text) as JsonObject)["rows"] as JsonArray }.getOrNull() ?: throw DataException("apn-index.json cannot be read")
        return rows.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val profiles = (o["profiles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (profileId in profiles) (o["row_key"] as? JsonPrimitive)?.contentOrNull else null
        }
    }

    /** The SIM's rows serving [type] in the order [rows] (explain's rows of the build's dataset) give them. */
    fun buildOrder(rows: List<ExplainRow>, type: String): List<String>? =
        rows.filter { type in it.types && !it.emergency }.map { it.rowKey }.takeIf { it.isNotEmpty() }
}
