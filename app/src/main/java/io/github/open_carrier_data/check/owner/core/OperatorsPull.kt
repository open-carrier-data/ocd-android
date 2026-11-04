package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.IdentityFile
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.core.StatementsFile
import java.io.File

/*
 * OCD's rule round 32 (part A, the MMS override rule 6): the operator statements and the
 * carrier-identity table of the pinned commit, generated/operators/statements.json and
 * carrier-identity.json. They are read at the same commit as the rest of the pin (F1), each file's
 * git blob checked against the commit's tree (one contents call), and refused whole in a format this
 * build does not read (F3). The app shows the statements and reads their MMS-ended networks (X6);
 * it never serves a statement and never turns one into a value of its own.
 */

/** Both operator files of one commit, checked. */
data class OperatorsData(val commit: String, val statements: StatementsFile, val identity: IdentityFile)

object OperatorsPull {
    fun contentsUrl(sha: String): String {
        require(DataSource.SHA.matches(sha)) { "commit shape" }
        return "${DataSource.API}/contents/${OperatorFiles.DIR}?ref=$sha"
    }

    fun rawUrl(sha: String, name: String): String {
        require(DataSource.SHA.matches(sha)) { "commit shape" }
        require(name in OperatorFiles.FILES) { "file" }
        return "${DataSource.RAW}/$sha/${OperatorFiles.path(name)}"
    }

    /**
     * Reads both files of [sha] into [dir]: the directory's listing at the commit (one API call),
     * each file at the commit, its blob checked, then both parsed. Null when the commit has no
     * generated/operators (before round 32). Throws [DataException] or IOException, and
     * [io.github.open_carrier_data.check.core.OperatorsFileRefused] for a format this build does not read.
     */
    fun pull(fetch: Fetch, sha: String, dir: File): OperatorsData? {
        val listed = try {
            DatasetSource.listing(fetch.get(contentsUrl(sha), DataSource.MAX_API_BYTES).decodeToString())
        } catch (e: NotFound) {
            return null
        }
        val blobs = listed.filter { it.type == "file" && DatasetSource.BLOB.matches(it.sha) }.associateBy { it.name }
        if (OperatorFiles.FILES.keys.none { it in blobs }) return null
        dir.mkdirs()
        for ((name, cap) in OperatorFiles.FILES) {
            val entry = blobs[name] ?: throw DataException("${OperatorFiles.path(name)} is not in the commit's tree")
            if (entry.size > cap) throw DataException("$name is over its cap of $cap bytes")
            val bytes = fetch.get(rawUrl(sha, name), cap)
            val blob = GitBlob.sha1(bytes)
            if (blob != entry.sha) throw DataException("$name: git blob $blob, the commit's tree says ${entry.sha}")
            File(dir, name).writeBytes(bytes)
        }
        return OperatorsData(
            sha,
            OperatorFiles.statements(File(dir, OperatorFiles.STATEMENTS).readText(Charsets.UTF_8)),
            OperatorFiles.identity(File(dir, OperatorFiles.IDENTITY).readText(Charsets.UTF_8)),
        )
    }

    /** Both files in [dir], parsed; null when either is missing or refused. */
    fun read(dir: File?, commit: String): OperatorsData? = runCatching {
        if (dir == null) return null
        val statements = File(dir, OperatorFiles.STATEMENTS).takeIf { it.isFile } ?: return null
        val identity = File(dir, OperatorFiles.IDENTITY).takeIf { it.isFile } ?: return null
        OperatorsData(commit, OperatorFiles.statements(statements.readText(Charsets.UTF_8)), OperatorFiles.identity(identity.readText(Charsets.UTF_8)))
    }.getOrNull()

    /** The log line for a pulled set. */
    fun line(data: OperatorsData): String =
        "operator statements of main ${data.commit.take(12)}: ${data.statements.statements.size} statements, " +
            "${data.statements.counts.lapsed} lapsed, ${data.statements.mmsEndedNetworks.size} networks with MMS ended " +
            "(${data.statements.mmsEndedNetworks.joinToString(" ") { it.mccmnc }}), every file's git blob matched"
}
