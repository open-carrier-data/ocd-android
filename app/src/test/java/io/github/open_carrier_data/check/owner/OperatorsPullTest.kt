package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.core.OperatorsFileRefused
import io.github.open_carrier_data.check.core.PublicPull
import io.github.open_carrier_data.check.core.PublicValidation
import io.github.open_carrier_data.check.owner.core.DataException
import io.github.open_carrier_data.check.owner.core.Fetch
import io.github.open_carrier_data.check.owner.core.GitBlob
import io.github.open_carrier_data.check.owner.core.MainPin
import io.github.open_carrier_data.check.owner.core.NotFound
import io.github.open_carrier_data.check.owner.core.OperatorsPull
import io.github.open_carrier_data.check.owner.core.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 32: the owner build reads the operator statements and the identity table at its pinned
 * commit, each blob checked, refused whole in a format it does not read; and the read-only builds'
 * pull (U1) judges validate exactly as the owner build's pin does.
 */
class OperatorsPullTest {
    private val sha = "a".repeat(40)
    private val statements = File("src/test/resources/operators/statements.json").readBytes()
    private val identity = File("src/test/resources/operators/carrier-identity.json").readBytes()

    private fun dir(): File = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "operators").toFile()

    private fun github(files: Map<String, ByteArray>, badBlob: String? = null, listed: Boolean = true): Fetch = Fetch { url, _ ->
        when (url) {
            OperatorsPull.contentsUrl(sha) -> if (!listed) throw NotFound("HTTP 404") else ("[" + files.entries.joinToString(",") { (name, bytes) ->
                """{"name":"$name","path":"${OperatorFiles.path(name)}","sha":"${if (name == badBlob) "0".repeat(40) else GitBlob.sha1(bytes)}","size":${bytes.size},"type":"file"}"""
            } + "]").toByteArray()
            else -> files.entries.firstOrNull { OperatorsPull.rawUrl(sha, it.key) == url }?.value ?: throw NotFound("HTTP 404")
        }
    }

    @Test
    fun `both files of the pinned commit, blob-checked and parsed`() {
        val d = dir()
        val data = OperatorsPull.pull(github(mapOf(OperatorFiles.STATEMENTS to statements, OperatorFiles.IDENTITY to identity)), sha, d)!!
        assertEquals(40, data.statements.statements.size)
        assertEquals(sha, data.commit)
        assertNotNull(OperatorsPull.read(d, sha))
        assertTrue(OperatorsPull.line(data).contains("8 networks with MMS ended (22802 22803 26202 26203 26207 26223 27001 50212)"))
    }

    @Test
    fun `a commit before round 32 has none, a blob or format that differs refuses them`() {
        assertNull(OperatorsPull.pull(github(emptyMap(), listed = false), sha, dir()))
        assertThrows(DataException::class.java) { OperatorsPull.pull(github(mapOf(OperatorFiles.STATEMENTS to statements, OperatorFiles.IDENTITY to identity), badBlob = OperatorFiles.IDENTITY), sha, dir()) }
        assertThrows(DataException::class.java) { OperatorsPull.pull(github(mapOf(OperatorFiles.STATEMENTS to statements)), sha, dir()) }
        val v2 = statements.decodeToString().replaceFirst("\"schema_version\": 1", "\"schema_version\": 2").toByteArray()
        assertThrows(OperatorsFileRefused::class.java) { OperatorsPull.pull(github(mapOf(OperatorFiles.STATEMENTS to v2, OperatorFiles.IDENTITY to identity)), sha, dir()) }
        assertNull(OperatorsPull.read(dir(), sha))
    }

    @Test
    fun `U1 - the read-only pull judges validate as the owner build's pin does`() {
        val cases = listOf(
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"$sha","status":"completed","conclusion":"success","app":{"slug":"github-actions"}}]}""",
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"$sha","status":"completed","conclusion":"failure","app":{"slug":"github-actions"}}]}""",
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"$sha","status":"in_progress","conclusion":null,"app":{"slug":"github-actions"}}]}""",
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"$sha","status":"completed","conclusion":"success","app":{"slug":"other"}}]}""",
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"${"b".repeat(40)}","status":"completed","conclusion":"success","app":{"slug":"github-actions"}}]}""",
            """{"check_runs":[{"id":1,"name":"validate","head_sha":"$sha","status":"completed","conclusion":"skipped","app":{"slug":"github-actions"}},{"id":2,"name":"validate","head_sha":"$sha","status":"completed","conclusion":"success","app":{"slug":"github-actions"}}]}""",
            """{"check_runs":[]}""",
        )
        for (text in cases) {
            val (owner, ownerRun) = MainPin.validation(MainPin.checkRuns(text), sha)
            val (readOnly, readOnlyRun) = PublicPull.validation(PublicPull.checkRuns(text), sha)
            assertEquals(text, owner.name, readOnly.name)
            assertEquals(text, ownerRun, readOnlyRun)
        }
        assertEquals(Validation.entries.map { it.name to it.plain }, PublicValidation.entries.map { it.name to it.plain })
    }
}
