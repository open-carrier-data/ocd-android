package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnColumns
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ApnMatch
import io.github.open_carrier_data.check.owner.core.DataBasis
import io.github.open_carrier_data.check.owner.core.DataCheck
import io.github.open_carrier_data.check.owner.core.DataException
import io.github.open_carrier_data.check.owner.core.DataSource
import io.github.open_carrier_data.check.owner.core.Fetch
import io.github.open_carrier_data.check.owner.core.MainPin
import io.github.open_carrier_data.check.owner.core.MainPull
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.SchemaRefused
import io.github.open_carrier_data.check.owner.core.Validation
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The main pin (round 29 F1 to F4), the APN rows and the state file. (The issue report: ReportSchemaTest.) */
class DataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "sha256:" + "36d5f7eb".repeat(8)

    // main's newest commits, newest first (the shapes of 2026-10-22: e32fd756, 61484256, a49101ea, 6f22eab5).
    private val a = "e32fd75687d01e7271b640c3adb41c52d42af6a8"
    private val b = "614842565d95195bfbadc7afaee11bce8cde13eb"
    private val c = "a49101eaddd723f62fca532e1b4acf6c10755516"
    private val d = "6f22eab550f5c4e376db2c25b54618b43932f2ed"

    private val bundled: OcdBundle by lazy { javaClass.getResourceAsStream("/fixture-bundle.json")!!.use { OcdBundle.parse(it.readBytes().decodeToString()) } }

    /**
     * The four files at one commit, by their path in the repository, in the
     * shape of OCD's generated/ (schema_version 1, the digest in three places).
     */
    private fun files(
        otherDigest: String? = null,
        schema: Map<String, String> = emptyMap(),
        apnVersion: String = "8",
        volte: String = "supported",
        o2Volte: String = "supported",
        extraApn: String = "",
    ): Map<String, ByteArray> {
        fun version(file: String) = schema[file] ?: "1"
        fun digestIn(file: String) = if (otherDigest == file) "sha256:" + "0".repeat(64) else digest
        val texts = mapOf(
            "generated/android/apns-conf.xml" to """<?xml version="1.0" encoding="utf-8"?>
<!--
    Android APN list from Open Carrier Data
    data_digest: ${digestIn("apns-conf.xml")}
-->
<apns version="$apnVersion">
  <apn apn="web.vodafone.de" carrier="Vodafone Internet" mcc="262" mnc="02" type="default,supl" protocol="IPV4V6" bearer_bitmask="1|2|13" />
  <apn apn="internet.lidl" carrier="LIDL" mcc="262" mnc="02" mvno_type="spn" mvno_match_data="LIDL Connect" type="default" />
  <apn apn="sos" carrier="SOS" mcc="262" mnc="02" type="emergency" />
  <apn apn="ims" carrier="IMS" mcc="262" mnc="02" type="ims" carrier_id="1911" />
  <apn apn="internet" carrier="o2" mcc="262" mnc="07" type="default" />$extraApn
</apns>
""",
            "generated/android/lookup.json" to """{"match_semantics":"x","profiles":[""" +
                """{"profile_id":"open.26202.aaaaaaaaaaaa","display_name":"Vodafone","match":{"mccmnc":["26202"]},"capabilities":{"volte":"$volte","vowifi":"unknown"},"newest_entry":"2026-09","path":"x"},""" +
                """{"profile_id":"open.26207.bbbbbbbbbbbb","display_name":"o2","match":{"mccmnc":["26207"]},"capabilities":{"volte":"$o2Volte","vowifi":"supported"},"newest_entry":"2026-10","path":"y"}""" +
                """],"resolution_order":"generic_to_specific","schema_version":${version("lookup.json")}}""",
            "generated/android/metadata.json" to """{"checks_through":"2026-07-14","data_digest":"${digestIn("metadata.json")}","schema_version":${version("metadata.json")},"stale_after":"2027-01-10","target":{"apn_database_version":$apnVersion}}""",
            "generated/devices/carrier-variants.json" to """{"based_on_data_digest":"${digestIn("carrier-variants.json")}","capabilities":["volte"],"profiles":{"open.26202.aaaaaaaaaaaa":{"volte":[{"devices":["samsung:SM-G981B"],"source":"samsung_omc","stack":"stock","state":"on"}]}},"schema_version":${version("carrier-variants.json")}}""",
        )
        return texts.mapValues { it.value.toByteArray() }
    }

    private fun checkRun(sha: String, conclusion: String?, status: String = "completed", id: Long = 7, name: String = "validate", app: String = "github-actions") =
        """{"id":$id,"name":"$name","head_sha":"$sha","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},"app":{"slug":"$app"}}"""

    /**
     * GitHub as a pull sees it: main's commit list, each commit's validate
     * check runs, and the files by commit on raw.githubusercontent.com. It
     * answers 404 for anything else, a branch-name URL included.
     */
    private inner class FakeGithub(
        val line: List<String>,
        val runs: Map<String, List<String>>,
        val data: Map<String, Map<String, ByteArray>>,
    ) : Fetch {
        val asked = mutableListOf<String>()
        val apiCalls: Int get() = asked.count { it.startsWith(DataSource.API) }

        override fun get(url: String, maxBytes: Long): ByteArray {
            asked += url
            if (url == DataSource.commitsUrl()) {
                return line.mapIndexed { i, sha ->
                    val parent = line.getOrNull(i + 1) ?: "0".repeat(40)
                    """{"sha":"$sha","commit":{"committer":{"date":"2026-10-2${4 - i}T08:35:39Z"},"message":"m"},"parents":[{"sha":"$parent"}]}"""
                }.joinToString(",", "[", "]").toByteArray()
            }
            for (sha in line) {
                if (url == DataSource.checkRunsUrl(sha)) return """{"total_count":1,"check_runs":[${runs[sha].orEmpty().joinToString(",")}]}""".toByteArray()
                val prefix = "${DataSource.RAW}/$sha/"
                if (url.startsWith(prefix)) return data[sha]?.get(url.removePrefix(prefix)) ?: throw IOException("HTTP 404 for $url")
            }
            throw IOException("HTTP 404 for $url")
        }
    }

    private fun pull(github: FakeGithub, current: String? = null, force: Boolean = false, dir: File = tmp.newFolder()): MainPull =
        MainPin.pull(github, current, force, dir, bundled, 1L)

    @Test
    fun `main's newest commit with a passed validate is pinned, every file read at its SHA, never at the branch name`() {
        val github = FakeGithub(listOf(a, b, c), mapOf(a to listOf(checkRun(a, "success", id = 120), checkRun(a, "skipped", id = 121))), mapOf(a to files()))
        val dir = tmp.newFolder("pin")
        val pinned = pull(github, dir = dir) as MainPull.Pinned
        val pin = pinned.pin
        assertEquals(a, pin.commit)
        assertEquals(120L, pin.checkRun)
        assertEquals(digest, pin.dataDigest)
        assertEquals("2026-10-24T08:35:39Z", pin.commitDate)
        assertEquals(2, pin.profiles)
        assertNull("the first pin has no previous one", pin.previous)
        assertEquals(setOf("metadata.json", "lookup.json", "carrier-variants.json", "apns-conf.xml"), pin.sha256.keys)
        assertEquals("main e32fd75687d0 (2026-10-24)", pin.label)
        // F1: one resolution of main, then every file by the commit's SHA.
        val raw = github.asked.filter { it.startsWith(DataSource.RAW) }
        assertEquals(DataSource.FILES.map { "${DataSource.RAW}/$a/$it" }, raw)
        assertTrue(github.asked.none { "/main/" in it })
        assertEquals("one call for main's commits, one for the newest commit's check runs", 2, github.apiCalls)
        // The bundle names the commit it was read at.
        assertEquals(a, pinned.bundle.source.commit)
        assertEquals("2026-10-24", pinned.bundle.source.commitDate)
        assertEquals(digest, pinned.bundle.records!!.basedOnDataDigest)
        assertEquals(bundled.limits, pinned.bundle.limits)
        assertEquals(setOf("metadata.json", "lookup.json", "carrier-variants.json", "apns-conf.xml"), dir.list()!!.toSet())
        // A branch name or another path never makes a URL.
        for ((sha, path) in listOf("main" to DataSource.FILES[0], a to "README.md", a.take(12) to DataSource.FILES[0])) {
            try {
                DataSource.rawUrl(sha, path)
                fail("$sha $path")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `the current pin is kept while it is the newest, and a pin never moves back to an older commit`() {
        val github = FakeGithub(listOf(a, b), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to files()))
        val current = pull(github, current = a) as MainPull.Current
        assertEquals(a, current.commit)
        assertEquals("up to date costs one API call", 1, github.apiCalls)
        assertTrue(github.asked.none { it.startsWith(DataSource.RAW) })
        // The newest commit still in validate, the pin one behind it: nothing moves.
        val running = FakeGithub(listOf(a, b, c), mapOf(a to listOf(checkRun(a, null, status = "in_progress")), c to listOf(checkRun(c, "success"))), emptyMap())
        val kept = pull(running, current = b) as MainPull.Current
        assertEquals(b, kept.commit)
        assertEquals(listOf("e32fd756 (validate still running)"), kept.passedOver)
        // "Check for update" with force reads the current pin again, still only when its validate passed.
        val again = pull(FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to files())), current = a, force = true)
        assertEquals(a, (again as MainPull.Pinned).pin.commit)
        assertNull("the same commit is no previous pin", again.pin.previous)
    }

    @Test
    fun `without a passed validate on the newest commit, a few commits are walked back, else the data stays`() {
        val runs = mapOf(
            a to listOf(checkRun(a, null, status = "queued")),
            b to listOf(checkRun(b, "failure"), checkRun(b, "success", id = 9)),
            c to listOf(checkRun(c, "success", id = 33)),
        )
        val github = FakeGithub(listOf(a, b, c, d), runs, mapOf(c to files()))
        val pinned = pull(github, current = d) as MainPull.Pinned
        assertEquals(c, pinned.pin.commit)
        assertEquals(d, pinned.pin.previous)
        assertEquals(listOf("e32fd756 (validate still running)", "61484256 (validate failed)"), pinned.passedOver)
        assertTrue(github.asked.filter { it.startsWith(DataSource.RAW) }.all { "/$c/" in it })
        assertEquals(4, github.apiCalls)
        // None of the newest WALK_BACK passed: nothing is read, the data stays.
        val none = FakeGithub(listOf(a, b, c, d), mapOf(b to listOf(checkRun(b, "failure"))), mapOf(a to files()))
        val kept = pull(none, current = "1".repeat(40)) as MainPull.Kept
        assertTrue(kept.reason, "none of main's newest 4 commits has a passed validate" in kept.reason)
        assertTrue(none.asked.none { it.startsWith(DataSource.RAW) })
        assertEquals(1 + DataSource.WALK_BACK, none.apiCalls)
    }

    @Test
    fun `validate passed only for GitHub Actions' run on exactly that commit, and a failure outweighs a pass`() {
        fun verdict(vararg runs: String) = MainPin.validation(MainPin.checkRuns("""{"check_runs":[${runs.joinToString(",")}]}"""), a).first
        assertEquals(Validation.PASSED, verdict(checkRun(a, "success"), checkRun(a, "skipped")))
        assertEquals(Validation.PASSED, verdict(checkRun(a, "success"), checkRun(a, "cancelled")))
        assertEquals(Validation.FAILED, verdict(checkRun(a, "success"), checkRun(a, "failure")))
        assertEquals(Validation.FAILED, verdict(checkRun(a, "timed_out")))
        assertEquals(Validation.PENDING, verdict(checkRun(a, "success"), checkRun(a, null, status = "in_progress")))
        assertEquals("the daily schedule skips the job", Validation.NONE, verdict(checkRun(a, "skipped")))
        assertEquals(Validation.NONE, verdict())
        assertEquals("another app's run of the same name", Validation.NONE, verdict(checkRun(a, "success", app = "someone-else")))
        assertEquals("a run on another commit", Validation.NONE, verdict(checkRun(b, "success")))
        assertEquals("another job", Validation.NONE, verdict(checkRun(a, "success", name = "freshness-alarm")))
    }

    @Test
    fun `main's line follows first parents, at most WALK_BACK commits`() {
        fun commit(sha: String, parent: String) = """{"sha":"$sha","parents":[{"sha":"$parent"}]}"""
        val listed = MainPin.commits("[${commit(a, b)},${commit(c, d)},${commit(b, c)},${commit(d, "f".repeat(40))}]")
        assertEquals(listOf(a, b, c, d), MainPin.line(listed).map { it.sha })
        assertEquals("a parent outside the list ends the line", listOf(a), MainPin.line(MainPin.commits("[${commit(a, "f".repeat(40))},${commit(b, c)}]")).map { it.sha })
        try {
            MainPin.line(MainPin.commits("""[{"sha":"main","parents":[]}]"""))
            fail("a name that is no SHA")
        } catch (_: DataException) {
        }
    }

    @Test
    fun `a schema_version major this build does not know is refused, and nothing of the pull is used`() {
        for ((name, setup) in listOf<Pair<String, Map<String, ByteArray>>>(
            "lookup schema 2" to files(schema = mapOf("lookup.json" to "2")),
            "variants schema 3.0" to files(schema = mapOf("carrier-variants.json" to "\"3.0\"")),
            "metadata schema missing" to files(schema = mapOf("metadata.json" to "null")),
            "lookup schema not a version" to files(schema = mapOf("lookup.json" to "\"one\"")),
            "APN version 9" to files(apnVersion = "9"),
        )) {
            val github = FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to setup))
            try {
                pull(github, current = b)
                fail("$name passed")
            } catch (_: SchemaRefused) {
            }
        }
        assertEquals(1, DataCheck.major(JsonPrimitive(1)))
        assertEquals(1, DataCheck.major(JsonPrimitive("1.4")))
        assertNull(DataCheck.major(JsonPrimitive(1.5)))
        assertNull(DataCheck.major(null))
        // A minor step of a known major is read.
        val minor = FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to files(schema = mapOf("lookup.json" to "\"1.1\""))))
        assertEquals(a, (pull(minor) as MainPull.Pinned).pin.commit)
    }

    @Test
    fun `a second data digest, a file cut short or not well-formed refuses the commit`() {
        val good = files()
        fun cut(path: String) = good + (path to good.getValue(path).let { it.copyOf(it.size / 2) })
        for ((name, setup) in listOf<Pair<String, Map<String, ByteArray>>>(
            "metadata digest" to files(otherDigest = "metadata.json"),
            "variants digest" to files(otherDigest = "carrier-variants.json"),
            "apns header digest" to files(otherDigest = "apns-conf.xml"),
            "lookup cut short" to cut("generated/android/lookup.json"),
            "variants cut short" to cut("generated/devices/carrier-variants.json"),
            "apns-conf cut short" to cut("generated/android/apns-conf.xml"),
            "apns-conf with a DOCTYPE" to (good + ("generated/android/apns-conf.xml" to good.getValue("generated/android/apns-conf.xml").decodeToString().replace("<apns ", "<!DOCTYPE apns []>\n<apns ").toByteArray())),
            "a row the line reader misses" to files(extraApn = "\n  <apn\n    apn=\"split\" mcc=\"262\" mnc=\"02\" type=\"default\" />"),
            "empty file" to (good + ("generated/android/metadata.json" to ByteArray(0))),
        )) {
            val github = FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to setup))
            try {
                pull(github)
                fail("$name passed")
            } catch (e: DataException) {
                assertFalse("$name is no schema refusal", e is SchemaRefused)
            }
        }
        // A file GitHub does not serve at that commit is a failed pull, not a pin.
        val missing = FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to good - "generated/android/apns-conf.xml"))
        try {
            pull(missing)
            fail("a missing file passed")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `a new pin changes a SIM's data key only when its own values or an APN row of its scope changed`() {
        val device = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
        val lidl = OwnerFixtures.SIM
        fun key(set: Map<String, ByteArray>): String {
            val dir = tmp.newFolder()
            val pinned = pull(FakeGithub(listOf(a), mapOf(a to listOf(checkRun(a, "success"))), mapOf(a to set)), dir = dir) as MainPull.Pinned
            val rows = File(dir, "apns-conf.xml").bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), lidl.identity.mccmnc) }
            return DataBasis.key(OwnerRunner.answers(pinned.bundle, lidl, device), rows, lidl.identity)
        }
        val base = key(files())
        assertTrue(base.matches(Regex("ocd:[0-9a-f]{16}")))
        assertEquals("the same data on another commit", base, key(files()))
        assertEquals("o2's change is not the LIDL SIM's", base, key(files(o2Volte = "unsupported")))
        assertEquals("an APN row of another network", base, key(files(extraApn = "\n  <apn apn=\"new\" mcc=\"262\" mnc=\"07\" type=\"default\" />")))
        assertEquals("an APN row of another SIM on the same network", base, key(files(extraApn = "\n  <apn apn=\"new\" mcc=\"262\" mnc=\"02\" carrier_id=\"1911\" type=\"default\" />")))
        assertNotEquals("the SIM's profile changed a value", base, key(files(volte = "unsupported")))
        assertNotEquals("an APN row of its scope", base, key(files(extraApn = "\n  <apn apn=\"new.lidl\" mcc=\"262\" mnc=\"02\" type=\"default\" />")))
        // The row order in the file is not a value.
        val rows = listOf(OcdApnRow(mapOf("apn" to "x", "mcc" to "262", "mnc" to "02")), OcdApnRow(mapOf("apn" to "y", "mcc" to "262", "mnc" to "02")))
        assertEquals(DataBasis.key(emptyMap(), rows, lidl.identity), DataBasis.key(emptyMap(), rows.reversed(), lidl.identity))
    }

    @Test
    fun `new values count only for a SIM the phone had before`() {
        val one = OwnerFixtures.SIM.key
        val two = one.copy(subId = 4, mccmnc = "26207")
        val seen = DataBasis.joined(mapOf(one to "ocd:1111111111111111", two to "ocd:2222222222222222"))
        assertEquals("3/26202=ocd:1111111111111111,4/26207=ocd:2222222222222222", seen)
        assertFalse(DataBasis.changed(seen, seen))
        assertTrue(DataBasis.changed(seen, DataBasis.joined(mapOf(one to "ocd:1111111111111111", two to "ocd:3333333333333333"))))
        assertFalse("a SIM that went", DataBasis.changed(seen, DataBasis.joined(mapOf(one to "ocd:1111111111111111"))))
        assertFalse("a new SIM", DataBasis.changed(DataBasis.joined(mapOf(one to "ocd:1111111111111111")), seen))
        assertFalse("a basis from before round 29", DataBasis.changed("data-2026.10@sha256:36d5f7ebb084", seen))
    }

    @Test
    fun `OCD's APN rows for the SIM, matched as Android matches them`() {
        val dir = tmp.newFolder("apn")
        val apn = File(dir, "apns-conf.xml").apply { writeBytes(files().getValue("generated/android/apns-conf.xml")) }
        val rows = apn.bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), "26202") }
        assertEquals(4, rows.size)
        assertEquals(digest, ApnConf.headerDigest(apn.readText()))
        assertEquals(5, DataCheck.apnFile(apn).rows)
        val lidl = OwnerFixtures.LIDL
        assertEquals(listOf(ApnMatch.MATCHES, ApnMatch.MATCHES, ApnMatch.MATCHES, ApnMatch.NOT_THIS_SIM), rows.map { ApnConf.match(it, lidl) })
        assertEquals(ApnMatch.NOT_THIS_SIM, ApnConf.match(rows[1], lidl.copy(spn = "Vodafone")))
        assertEquals(ApnMatch.NOT_CHECKABLE, ApnConf.match(rows[1], lidl.copy(spn = null)))
        val values = ApnColumns.testRow(rows[0], 2397)
        assertEquals("Vodafone Internet (OCD Check test)", values["name"])
        assertEquals("the test row has no numeric; the carrier ID keys it", "", values["numeric"])
        assertEquals("26202", ApnColumns.identity(rows[0])["numeric"])
        assertEquals(((1L shl 0) or (1L shl 1) or (1L shl 12)).toString(), values["bearer_bitmask"])
        val parsed = PhoneApnRow.parseQuery("Row: 0 _id=12, name=LIDL, apn=web.vodafone.de, type=default,supl, protocol=IPV4V6, mvno_type=\n")!!
        assertEquals(rows[0].key, parsed.single().key)
        assertEquals(emptyList<PhoneApnRow>(), PhoneApnRow.parseQuery("No result found.\n"))
        assertNull(PhoneApnRow.parseQuery("Error while accessing provider"))
    }

    @Test
    fun `the state file round-trips, an unknown field is ignored, and the release of builds before round 29 is not read`() {
        val state = OwnerState(stop = "x").logged(1, "k", "t")
        assertEquals(state, OwnerState.decode(state.encode()))
        assertNotNull(OwnerState.decode("""{"future":1}"""))
        val old = OwnerState.decode("""{"release":{"tag":"data-2026.10","commit":"8cb64d63d9a482a40f43a10b37744241f9526e0e","dataDigest":"$digest"}}""")
        assertNull(old.pin)
    }
}
