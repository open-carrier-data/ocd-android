package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.DataException
import io.github.open_carrier_data.check.owner.core.DataPin
import io.github.open_carrier_data.check.owner.core.DataSource
import io.github.open_carrier_data.check.owner.core.DatasetCheck
import io.github.open_carrier_data.check.owner.core.DatasetFiles
import io.github.open_carrier_data.check.owner.core.DatasetManifest
import io.github.open_carrier_data.check.owner.core.DatasetPin
import io.github.open_carrier_data.check.owner.core.DatasetPull
import io.github.open_carrier_data.check.owner.core.DatasetSource
import io.github.open_carrier_data.check.owner.core.Fetch
import io.github.open_carrier_data.check.owner.core.GitBlob
import io.github.open_carrier_data.check.owner.core.ManifestFile
import io.github.open_carrier_data.check.owner.core.NotFound
import io.github.open_carrier_data.check.owner.core.SchemaRefused
import io.github.open_carrier_data.check.owner.core.StrictJson
import io.github.open_carrier_data.check.owner.core.StrictJsonException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Round 30's whole-dataset pull: F1 to F3, the git blob check, the manifest and pin.json, the strict JSON reader. */
class DatasetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "sha256:" + "50c89010".repeat(8)
    private val commit = "5cb3c43fc536be51b9ee663ad04d05f07d546006"
    private val pin = DataPin(commit, "2026-10-25T09:58:23Z", digest, "2026-07-14", "2027-01-10", 120900000000, emptyMap(), 2, 0)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The dataset files of one commit, by path on main, in the spec's shapes (2.4 to 2.10). */
    private fun dataset(
        lane: Boolean = true,
        profilesVersion: Int = 1,
        profilesDigest: String = digest,
        manifestVersion: Int = 1,
        extraManifest: String = "",
        tamperSha: String? = null,
        tamperDatasetDigest: Boolean = false,
        manifestDigest: String = digest,
    ): Map<String, ByteArray> {
        val profiles = """{"schema_version":$profilesVersion,"based_on_data_digest":"$profilesDigest","keys":{"carrier_volte_available_bool":{"type":"bool","group":null,"origin":["ocd_schema","google"],"fork":null}},""" +
            """"groups":{"A":{"pattern":"emergency","names":[],"keys":["ecbm_timer_int"]},"B":{"keys":["carrier_metered_apn_types_strings"],"must_contain":"default"},"X6":{"keys":[],"networks":[]}},""" +
            """"reserved":{"default":{"fill":[{"entry":"default","when":null}]},"no_sim":{"fill":[{"entry":"no_sim","when":null}]}},""" +
            """"networks":{"262-07":{"mcc":"262","mnc":"07","profiles":[{"id":"open.26207.c07dcc040b58","kind":"ocd","name":"O2","match":{"mccmnc":[{"mcc":"262","mnc":"07"}]},"specificity":0,"fill":[{"entry":"o2_de","when":null}],"values":{"carrier_volte_available_bool":true},"sources":{},"alternatives":{"carrier_volte_available_bool":[{"value":false,"families":["samsung_omc"]}]},"apn_rows":1}]},""" +
            """"302-370":{"mcc":"302","mnc":"370","profiles":[]}}}"""
        val google = """{"schema_version":1,"based_on_data_digest":"$digest","family":"google","provenance":{"lane":"google_carriersettings","device":"kodiak","build":"B","carrier_list_version":1,"others_version":1,"snapshot":"s","state":"public_main"},"rules":[],"entries":{"default":{"values":{}},"o2_de":{"values":{"carrier_volte_available_bool":true}}}}"""
        val records = """{"schema_version":1,"based_on_data_digest":"$digest","expire_days":90,"records":[]}"""
        val apns = """<?xml version="1.0" encoding="utf-8"?>
<!--
    Android APN list from Open Carrier Data
    data_digest: $digest
-->
<apns version="8">
  <apn apn="internet" carrier="o2" mcc="262" mnc="07" type="default" />
</apns>
"""
        val index = """{"schema_version":1,"based_on_data_digest":"$digest","apn_xml_sha256":"${sha256(apns.toByteArray())}","rows":[{"ordinal":0,"line":7,"row_key":"a:0123456789abcdef","profiles":["open.26207.c07dcc040b58"]}]}"""
        val files = linkedMapOf(
            "generated/ocd-dataset/profiles.json" to profiles.toByteArray(),
            "generated/devices/device-records.json" to records.toByteArray(),
            "generated/android/apns-conf.xml" to apns.toByteArray(),
            "generated/ocd-dataset/apn-index.json" to index.toByteArray(),
        )
        if (lane) files["generated/ocd-dataset/google-lane.json"] = google.toByteArray()
        val roles = mapOf(
            "generated/ocd-dataset/profiles.json" to "profiles", "generated/devices/device-records.json" to "device_records",
            "generated/android/apns-conf.xml" to "apn_xml", "generated/ocd-dataset/apn-index.json" to "apn_index", "generated/ocd-dataset/google-lane.json" to "google_lane",
        )
        val entries = files.map { (path, bytes) ->
            ManifestFile(roles.getValue(path), path.substringAfterLast('/'), path, bytes.size.toLong(), if (tamperSha == path.substringAfterLast('/')) "0".repeat(64) else sha256(bytes))
        }
        val setDigest = if (tamperDatasetDigest) "sha256:" + "1".repeat(64) else DatasetManifest.datasetDigest(entries)
        val manifest = """{"schema_version":$manifestVersion,"format":"ocd-dataset","data_digest":"$manifestDigest","generated_at":"2026-10-25T09:57:41Z","generator":"live-private tools/export_ocd_dataset.py 1",""" +
            """"apn_database_version":8,"google_lane":"${if (lane) "included" else "absent"}","files":[""" +
            entries.joinToString(",") { """{"role":"${it.role}","name":"${it.name}","path":"${it.path}","bytes":${it.bytes},"sha256":"${it.sha256}"}""" } +
            """],"dataset_digest":"$setDigest","counts":{"networks":2,"profiles":1,"fill_only_profiles":0,"google_entries":2,"apn_rows":1,"records":0}$extraManifest}"""
        return files + (DatasetFiles.MANIFEST_PATH to manifest.toByteArray()) + ("generated/android/metadata.json" to "{}".toByteArray())
    }

    /** GitHub's contents API and raw files of one commit; [blobs] overrides a listed blob; [published] false: no generated/ocd-dataset at all. */
    private inner class FakeGithub(val files: Map<String, ByteArray>, val blobs: Map<String, String> = emptyMap(), val published: Boolean = true) : Fetch {
        val asked = mutableListOf<String>()

        override fun get(url: String, maxBytes: Long): ByteArray {
            asked += url
            for (dir in DatasetFiles.DIRS) {
                if (url == DatasetSource.contentsUrl(dir, commit)) {
                    if (!published && dir == "generated/ocd-dataset") throw NotFound("HTTP 404")
                    return files.filterKeys { it.substringBeforeLast('/') == dir }.map { (path, bytes) ->
                        """{"name":"${path.substringAfterLast('/')}","path":"$path","sha":"${blobs[path] ?: GitBlob.sha1(bytes)}","size":${bytes.size},"type":"file","download_url":"x"}"""
                    }.joinToString(",", "[", "]").toByteArray()
                }
            }
            val prefix = "${DataSource.RAW}/$commit/"
            if (url.startsWith(prefix)) return files[url.removePrefix(prefix)]?.also { if (it.size > maxBytes) throw DataException("cap") } ?: throw NotFound("HTTP 404")
            throw NotFound("HTTP 404 for $url")
        }
    }

    private fun pull(files: Map<String, ByteArray>, blobs: Map<String, String> = emptyMap(), previous: File? = null, published: Boolean = true, dir: File = tmp.newFolder()) =
        DatasetCheck.pull(FakeGithub(files, blobs, published), pin, dir, previous, "io.github.open_carrier_data.check.owner 0.4.0-owner", 1_792_900_000_000)

    @Test
    fun `a whole dataset at the pin, checked, with pin json naming exactly the manifest's files and their blobs`() {
        val files = dataset()
        val dir = tmp.newFolder()
        val result = pull(files, dir = dir) as DatasetPull.Pulled
        assertEquals(commit, result.dataset.commit)
        assertEquals(listOf("manifest.json", "profiles.json", "device-records.json", "apns-conf.xml", "apn-index.json", "google-lane.json"), result.dataset.names)
        val pinJson = Json.parseToJsonElement(File(dir, "pin.json").readText()).jsonObject
        assertEquals("ocd-dataset-pin/1", pinJson["schema"]!!.jsonPrimitive.content)
        assertEquals("app", pinJson["role"]!!.jsonPrimitive.content)
        assertEquals("open-carrier-data/open-carrier-data", pinJson["repository"]!!.jsonPrimitive.content)
        assertEquals(commit, pinJson["commit"]!!.jsonPrimitive.content)
        assertEquals("120900000000", pinJson["validate"]!!.jsonObject["check_run_id"]!!.jsonPrimitive.content)
        assertEquals("success", pinJson["validate"]!!.jsonObject["conclusion"]!!.jsonPrimitive.content)
        val pinned = pinJson["files"]!!.jsonObject
        // Reader check 1: exactly the manifest's files plus the manifest.
        assertEquals((files.keys - "generated/android/metadata.json"), pinned.keys)
        for ((path, entry) in pinned) {
            assertEquals(GitBlob.sha1(files.getValue(path)), entry.jsonObject["blob"]!!.jsonPrimitive.content)
            assertEquals(files.getValue(path).size.toLong(), entry.jsonObject["bytes"]!!.jsonPrimitive.content.toLong())
        }
        assertEquals(setOf("schema", "role", "repository", "commit", "commit_date", "validate", "files", "fetched_at", "fetched_by"), pinJson.keys)
        assertEquals("2026-10-25T09:58:23Z", pinJson["commit_date"]!!.jsonPrimitive.content)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(pinJson["fetched_at"]!!.jsonPrimitive.content))
        // Files are stored as plain bytes, byte for byte.
        for (name in result.dataset.names) assertTrue(name, File(dir, name).readBytes().contentEquals(files.getValue(DatasetFiles.pathOf(name)!!)))
        // The pin round-trips through the strict decoder.
        assertEquals(commit, DatasetPin.decode(File(dir, "pin.json").readText()).commit)
    }

    @Test
    fun `git's blob SHA-1 is computed as git does`() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitBlob.sha1("hello\n".toByteArray()))
        val f = tmp.newFile().apply { writeText("hello\n") }
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitBlob.sha1(f))
    }

    @Test
    fun `dataset_digest is SHA-256 over name, sha256 and bytes lines in byte order of the name`() {
        val files = listOf(
            ManifestFile("profiles", "profiles.json", "p", 10, "a".repeat(64)),
            ManifestFile("apn_xml", "apns-conf.xml", "x", 7, "b".repeat(64)),
        )
        val text = "apns-conf.xml\n${"b".repeat(64)}\n7\nprofiles.json\n${"a".repeat(64)}\n10\n"
        assertEquals("sha256:" + sha256(text.toByteArray()), DatasetManifest.datasetDigest(files))
    }

    @Test
    fun `a blob that differs from the commit's tree refuses the pull`() {
        val files = dataset()
        try {
            pull(files, blobs = mapOf("generated/ocd-dataset/profiles.json" to "1".repeat(40)))
            fail("blob mismatch passed")
        } catch (e: DataException) {
            assertTrue(e.message, "git blob" in e.message!!)
        }
    }

    @Test
    fun `a SHA-256 or dataset digest that differs from the manifest refuses the pull`() {
        for (files in listOf(dataset(tamperSha = "apn-index.json"), dataset(tamperDatasetDigest = true))) {
            try {
                pull(files)
                fail("integrity passed")
            } catch (e: SchemaRefused) {
                fail("not a schema refusal: ${e.message}")
            } catch (e: DataException) {
                assertTrue(e.message, "SHA-256" in e.message!! || "dataset_digest" in e.message!!)
            }
        }
    }

    @Test
    fun `F1 - every file names the main pin's data digest`() {
        try {
            pull(dataset(profilesDigest = "sha256:" + "0".repeat(64)))
            fail("a second data digest passed")
        } catch (e: DataException) {
            assertTrue(e.message, "based on another data digest" in e.message!!)
        }
        try {
            pull(dataset(manifestDigest = "sha256:" + "2".repeat(64)))
            fail("a manifest of another digest passed")
        } catch (e: DataException) {
            assertTrue(e.message, "metadata.json names" in e.message!!)
        }
    }

    @Test
    fun `F3 - an unknown schema version, format or member refuses the whole pull`() {
        for (files in listOf(dataset(manifestVersion = 2), dataset(profilesVersion = 2), dataset(extraManifest = ""","signature":"x""""))) {
            try {
                pull(files)
                fail("unknown schema passed")
            } catch (e: SchemaRefused) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun `main without a dataset yet is not an error, and the manifest lists the Google lane only while included`() {
        assertTrue(pull(dataset(), published = false) is DatasetPull.NotPublished)
        val absent = pull(dataset(lane = false)) as DatasetPull.Pulled
        assertEquals("absent", absent.dataset.googleLane)
        assertTrue("google-lane.json" !in absent.dataset.names)
    }

    @Test
    fun `unchanged files are copied from the last set by their blob, not downloaded again`() {
        val files = dataset()
        val first = tmp.newFolder()
        pull(files, dir = first)
        val fake = FakeGithub(files)
        val second = DatasetCheck.pull(fake, pin, tmp.newFolder(), first, "x", 0) as DatasetPull.Pulled
        assertEquals(6, second.reused)
        assertEquals(0, second.downloaded)
        assertTrue(fake.asked.none { it.startsWith(DataSource.RAW) })
        // Three API calls: one listing per directory, without a token.
        assertEquals(3, fake.asked.count { it.startsWith(DataSource.API) })
    }

    @Test
    fun `the strict reader refuses duplicates at any level, NaN, a BOM, leading zeros, U+0000, long strings and trailing data`() {
        val bad = listOf(
            """{"a":1,"a":2}""", """{"a":{"b":1,"b":2}}""", """{"a":[{"x":1,"x":1}]}""", """{"a":NaN}""", """{"a":Infinity}""",
            "\uFEFF{\"a\":1}", """{"a":01}""", """{"a":"x\u0000y"}""", "{\"a\":\"x\u0000y\"}", """{"a":1} x""", """[1]""", """{"a":1.}""",
            """{"a":"${"x".repeat(4097)}"}""", "{\"a\":\"tab\tin\"}",
        )
        for (text in bad) {
            try {
                StrictJson.scan(text.reader())
                fail("passed: ${text.take(40)}")
            } catch (_: StrictJsonException) {
            }
        }
        // Verbatim strings: Google's SPN rule "C Spire\r" keeps its CR.
        val ok = StrictJson.scan("""{"schema_version":1,"spn":"C Spire\r","n":-1.5e3,"big":{"x":[1,2]}}""".reader(), keep = setOf("schema_version", "spn"))
        assertEquals("C Spire\r", ok.top["spn"]!!.jsonPrimitive.content)
        assertEquals(1, ok.top["schema_version"]!!.jsonPrimitive.content.toInt())
        assertNull(ok.top["big"])
    }

    @Test
    fun `the strict reader captures one network's slice of profiles json`() {
        val dir = tmp.newFolder()
        pull(dataset(), dir = dir)
        val net = DatasetCheck.network(File(dir, "profiles.json"), "262", "07")!!
        val profile = net["profiles"]!!.jsonArray.single().jsonObject
        assertEquals("open.26207.c07dcc040b58", profile["id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), profile["alternatives"]!!.jsonObject["carrier_volte_available_bool"]!!.jsonArray.single().jsonObject["value"])
        // 302-37 and 302-370 are different networks; a two-digit MNC never matches a three-digit one.
        assertNull(DatasetCheck.network(File(dir, "profiles.json"), "302", "37"))
        assertNotNull(DatasetCheck.network(File(dir, "profiles.json"), "302", "370"))
        val (keys, groups) = DatasetCheck.registry(File(dir, "profiles.json"))
        assertEquals("bool", (keys!!["carrier_volte_available_bool"] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertNotNull(groups!!["A"])
    }

    @Test
    fun `the manifest is read with closed member sets and fixed paths`() {
        val text = String(dataset()[DatasetFiles.MANIFEST_PATH]!!)
        val m = DatasetManifest.parse(text)
        assertEquals(digest, m.dataDigest)
        assertEquals("2026-10-25T09:57:41Z", m.generatedAt)
        for (bad in listOf(
            text.replace("\"generated/ocd-dataset/profiles.json\"", "\"generated/other/profiles.json\""),
            text.replace("\"apn_database_version\":8", "\"apn_database_version\":9"),
            text.replace("\"format\":\"ocd-dataset\"", "\"format\":\"other\""),
            text.replace("\"google_lane\":\"included\"", "\"google_lane\":\"absent\""),
        )) {
            try {
                DatasetManifest.parse(bad)
                fail("passed")
            } catch (_: DataException) {
            }
        }
    }

    @Test
    fun `the source URLs are fixed - three directories and the dataset paths at the commit`() {
        assertEquals("https://api.github.com/repos/open-carrier-data/open-carrier-data/contents/generated/ocd-dataset?ref=$commit", DatasetSource.contentsUrl("generated/ocd-dataset", commit))
        assertEquals("https://raw.githubusercontent.com/open-carrier-data/open-carrier-data/$commit/generated/devices/device-records.json", DatasetSource.rawUrl(commit, "generated/devices/device-records.json"))
        for (bad in listOf({ DatasetSource.rawUrl("main", DatasetFiles.MANIFEST_PATH) }, { DatasetSource.rawUrl(commit, "generated/android/lookup.json") }, { DatasetSource.contentsUrl("observed", commit) })) {
            try {
                bad()
                fail("passed")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(64L * 1024 * 1024, DatasetFiles.TOTAL_CAP)
    }
}
