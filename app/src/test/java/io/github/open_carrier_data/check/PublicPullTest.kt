package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.core.PublicData
import io.github.open_carrier_data.check.core.PublicFetch
import io.github.open_carrier_data.check.core.PublicPull
import io.github.open_carrier_data.check.core.PublicValidation
import io.github.open_carrier_data.check.core.PullException
import io.github.open_carrier_data.check.core.PullNotFound
import io.github.open_carrier_data.check.core.PullPolicy
import io.github.open_carrier_data.check.core.PullSchemaRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * OCD's rule round 32, U1: the read-only builds' pull of OCD's newest validated main commit, through
 * the owner build's path (F1 to F3 and the git blob check), to GitHub's hosts for OCD's public
 * repository only, with no identifier, falling back closed.
 */
class PublicPullTest {
    private val bundled: OcdBundle = OcdBundle.parse(File("src/test/resources/fixture-bundle.json").readText())
    private val digest = "sha256:" + "ab".repeat(32)
    private val newest = "1".repeat(40)
    private val older = "2".repeat(40)
    private val oldest = "3".repeat(40)

    private val metadata = """{"schema_version":1,"data_digest":"$digest","checks_through":"2026-07-14","stale_after":"2027-01-10"}"""
    private val lookup = """{"schema_version":1,"resolution_order":"generic_to_specific","profiles":[{"profile_id":"open.26203.dbb2be7109a6","display_name":"O2","match":{"mccmnc":["26203"]},"capabilities":{"mms":"unsupported","volte":"supported"}}]}"""
    private val variants = """{"schema_version":1,"based_on_data_digest":"$digest","capabilities":["volte"],"profiles":{}}"""
    private val statements = File("src/test/resources/operators/statements.json").readText()
    private val identity = File("src/test/resources/operators/carrier-identity.json").readText()

    private fun commits(vararg shas: String) = "[" + shas.mapIndexed { i, s ->
        val parent = shas.getOrNull(i + 1)?.let { """[{"sha":"$it"}]""" } ?: "[]"
        """{"sha":"$s","commit":{"committer":{"date":"2026-10-29T0$i:00:00Z"}},"parents":$parent}"""
    }.joinToString(",") + "]"

    private fun runs(sha: String, status: String = "completed", conclusion: String? = "success", app: String = "github-actions") =
        """{"check_runs":[{"id":77,"name":"validate","head_sha":"$sha","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},"app":{"slug":"$app"}}]}"""

    /** A fake GitHub: every URL the pull asks for, and a record of them. */
    private inner class Github(
        val files: MutableMap<String, String> = mutableMapOf(
            "generated/android/metadata.json" to metadata,
            "generated/android/lookup.json" to lookup,
            "generated/devices/carrier-variants.json" to variants,
            OperatorFiles.path(OperatorFiles.STATEMENTS) to statements,
            OperatorFiles.path(OperatorFiles.IDENTITY) to identity,
        ),
        val checks: Map<String, String> = mapOf(newest to runs(newest), older to runs(older), oldest to runs(oldest)),
        val badBlob: String? = null,
        val operators: Boolean = true,
    ) : PublicFetch {
        val asked = mutableListOf<String>()

        override fun get(url: String, maxBytes: Long): ByteArray {
            asked += url
            assertTrue("not allowed: $url", PublicData.allowed(url))
            if (url == PublicData.commitsUrl()) return commits(newest, older, oldest).toByteArray()
            for ((sha, body) in checks) if (url == PublicData.checkRunsUrl(sha)) return body.toByteArray()
            for (dir in PublicPull.DIRS) for (sha in listOf(newest, older, oldest)) if (url == PublicData.contentsUrl(dir, sha)) {
                if (dir == OperatorFiles.DIR && !operators) throw PullNotFound("HTTP 404")
                val listed = files.filterKeys { it.substringBeforeLast('/') == dir }.map { (path, text) ->
                    val blob = if (path == badBlob) "f".repeat(40) else PublicPull.blob(text.toByteArray())
                    """{"name":"${path.substringAfterLast('/')}","path":"$path","sha":"$blob","size":${text.toByteArray().size},"type":"file"}"""
                }
                return "[${listed.joinToString(",")}]".toByteArray()
            }
            for ((path, text) in files) for (sha in listOf(newest, older, oldest)) if (url == PublicData.rawUrl(sha, path)) return text.toByteArray()
            throw PullNotFound("HTTP 404")
        }
    }

    private fun dir(): File = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "pull").toFile()

    @Test
    fun `only https requests for OCD's public repository on GitHub's API and raw hosts are allowed`() {
        assertTrue(PublicData.allowed(PublicData.commitsUrl()))
        assertTrue(PublicData.allowed(PublicData.rawUrl(newest, "generated/android/lookup.json")))
        for (bad in listOf(
            "http://api.github.com/repos/open-carrier-data/open-carrier-data/commits",
            "https://api.github.com/repos/someone/else/commits",
            "https://api.github.com/user",
            "https://github.com/open-carrier-data/open-carrier-data",
            "https://raw.githubusercontent.com/open-carrier-data/open-carrier-data-private/main/x",
            "https://raw.githubusercontent.com/open-carrier-data/open-carrier-data/../x/y",
            "https://user@api.github.com/repos/open-carrier-data/open-carrier-data/commits",
            "https://api.github.com:8443/repos/open-carrier-data/open-carrier-data/commits",
            "https://example.com/repos/open-carrier-data/open-carrier-data/commits",
            "https://api.github.com.evil.example/repos/open-carrier-data/open-carrier-data/commits",
            "not a url",
        )) assertFalse(bad, PublicData.allowed(bad))
        assertThrows(IllegalArgumentException::class.java) { PublicData.rawUrl(newest, "generated/android/apns-conf.xml") }
    }

    @Test
    fun `a pull pins the newest validated commit, checks every blob and carries no identifier`() {
        val github = Github()
        val r = PublicPull.pull(github, null, dir(), bundled, 1_000L) as PublicPull.Result.Pinned
        assertEquals(newest, r.pin.commit)
        assertEquals(77L, r.pin.checkRun)
        assertEquals(digest, r.pin.dataDigest)
        assertTrue(r.pin.operators)
        assertEquals(newest, r.bundle.source.commit)
        assertEquals(PublicData.REPOSITORY_URL, r.bundle.source.repository)
        assertEquals(bundled.limits, r.bundle.limits)
        assertEquals(40, r.statements!!.statements.size)
        assertNotNull(r.identity)
        // The requests: the commit list, one check run, three listings, five files. Nothing names the phone or the SIM.
        val expected = listOf(PublicData.commitsUrl(), PublicData.checkRunsUrl(newest)) +
            PublicPull.DIRS.map { PublicData.contentsUrl(it, newest) } + PublicPull.FILES.keys.map { PublicData.rawUrl(newest, it) }
        assertEquals(expected, github.asked)
        // The one header the app sets is a fixed name.
        assertEquals("ocd-check", PublicData.USER_AGENT)
    }

    @Test
    fun `validate decides - a failed or running newest commit is passed over, a current pin stays, none passed keeps the data`() {
        val walked = PublicPull.pull(Github(checks = mapOf(newest to runs(newest, conclusion = "failure"), older to runs(older, status = "in_progress", conclusion = null), oldest to runs(oldest))), null, dir(), bundled, 1L)
        assertEquals(oldest, (walked as PublicPull.Result.Pinned).pin.commit)
        assertEquals(2, walked.passedOver.size)
        // Another app's run of the same name does not count.
        val foreign = PublicPull.pull(Github(checks = mapOf(newest to runs(newest, app = "someone"), older to runs(older, app = "someone"), oldest to runs(oldest, app = "someone"))), null, dir(), bundled, 1L)
        assertTrue(foreign is PublicPull.Result.Kept)
        val current = PublicPull.pull(Github(checks = mapOf(newest to runs(newest, conclusion = "failure"))), older, dir(), bundled, 1L)
        assertEquals(older, (current as PublicPull.Result.Current).commit)
        assertEquals(PublicValidation.FAILED, PublicPull.validation(PublicPull.checkRuns(runs(newest, conclusion = "timed_out")), newest).first)
    }

    @Test
    fun `a blob that differs from the commit's tree, a second data digest or an unknown schema refuses the whole pull`() {
        assertThrows(PullException::class.java) { PublicPull.pull(Github(badBlob = "generated/android/lookup.json"), null, dir(), bundled, 1L) }
        val other = Github().apply { files["generated/devices/carrier-variants.json"] = variants.replace(digest, "sha256:" + "cd".repeat(32)) }
        assertThrows(PullException::class.java) { PublicPull.pull(other, null, dir(), bundled, 1L) }
        val schema = Github().apply { files["generated/android/lookup.json"] = lookup.replace("\"schema_version\":1", "\"schema_version\":2") }
        assertThrows(PullSchemaRefused::class.java) { PublicPull.pull(schema, null, dir(), bundled, 1L) }
        val statements2 = Github().apply { files[OperatorFiles.path(OperatorFiles.STATEMENTS)] = statements.replaceFirst("\"schema_version\": 1", "\"schema_version\": 2") }
        assertThrows(Exception::class.java) { PublicPull.pull(statements2, null, dir(), bundled, 1L) }
        val half = Github().apply { files.remove(OperatorFiles.path(OperatorFiles.IDENTITY)) }
        assertThrows(PullException::class.java) { PublicPull.pull(half, null, dir(), bundled, 1L) }
    }

    @Test
    fun `a commit before round 32 has no operator files and still pulls`() {
        val r = PublicPull.pull(Github(operators = false), null, dir(), bundled, 1L) as PublicPull.Result.Pinned
        assertFalse(r.pin.operators)
        assertNull(r.statements)
    }

    @Test
    fun `on metered data at most one download a day, any number on Wi-Fi`() {
        val day = PullPolicy.DAY_MS
        assertNull(PullPolicy.refusal(metered = false, lastMeteredPullMs = 10 * day + 5, nowMs = 10 * day + 6))
        assertNull(PullPolicy.refusal(metered = true, lastMeteredPullMs = null, nowMs = 10 * day))
        assertNotNull(PullPolicy.refusal(metered = true, lastMeteredPullMs = 10 * day + 5, nowMs = 10 * day + 60_000))
        assertNull(PullPolicy.refusal(metered = true, lastMeteredPullMs = 10 * day + 5, nowMs = 11 * day + 1))
        assertEquals("no network", PullPolicy.refusal(metered = null, lastMeteredPullMs = null, nowMs = 0))
    }
}
