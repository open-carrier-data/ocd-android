package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.owner.core.ApnColumns
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ApnMatch
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.ReleaseCheck
import io.github.open_carrier_data.check.owner.core.ReleaseException
import io.github.open_carrier_data.check.owner.core.ReleaseSource
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/** The release check, the APN rows and the issue report. */
class DataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "sha256:" + "36d5f7eb".repeat(8)
    private val commit = "8cb64d63d9a482a40f43a10b37744241f9526e0e"

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A small release in the shape of data-2026.10 (release.json, SHA256SUMS, the digest in four places). */
    private fun release(dir: File, tag: String = "data-2026.10", tamper: String? = null, otherDigest: String? = null) {
        val files = mapOf(
            "apns-conf.xml" to """<?xml version="1.0" encoding="utf-8"?>
<!--
    data_digest: ${if (otherDigest == "apns-conf.xml") "sha256:" + "0".repeat(64) else digest}
-->
<apns version="8">
  <apn apn="web.vodafone.de" carrier="Vodafone Internet" mcc="262" mnc="02" type="default,supl" protocol="IPV4V6" bearer_bitmask="1|2|13" />
  <apn apn="internet.lidl" carrier="LIDL" mcc="262" mnc="02" mvno_type="spn" mvno_match_data="LIDL Connect" type="default" />
  <apn apn="sos" carrier="SOS" mcc="262" mnc="02" type="emergency" />
  <apn apn="ims" carrier="IMS" mcc="262" mnc="02" type="ims" carrier_id="1911" />
</apns>
""",
            "lookup.json" to """{"match_semantics":"x","profiles":[{"profile_id":"open.26202.aaaaaaaaaaaa","display_name":"Vodafone","match":{"mccmnc":["26202"]},"capabilities":{"volte":"supported","vowifi":"unknown"},"newest_entry":"2026-09","path":"x"}],"resolution_order":"generic_to_specific","schema_version":1}""",
            "metadata.json" to """{"checks_through":"2026-07-14","data_digest":"${if (otherDigest == "metadata.json") "sha256:" + "1".repeat(64) else digest}"}""",
            "carrier-variants.json" to """{"based_on_data_digest":"$digest","capabilities":["volte"],"profiles":{"open.26202.aaaaaaaaaaaa":{"volte":[{"devices":["samsung:SM-G981B"],"source":"samsung_omc","stack":"stock","state":"on"}]}}}""",
        )
        dir.mkdirs()
        for ((name, text) in files) File(dir, name).writeText(text)
        val sums = files.keys.associateWith { hex(File(dir, it).readBytes()) }
        val listed = sums.entries.joinToString(",") { (name, sum) -> """{"asset":"$name","sha256":"$sum","bytes":${File(dir, name).length()}}""" }
        File(dir, "release.json").writeText(
            """{"schema_version":1,"tag":"$tag","commit":"$commit","commit_date":"2026-10-19T12:00:23+00:00","rehearsal":false,"failed_gates":[],""" +
                """"data_digest":"$digest","checks_through":"2026-07-14","stale_after":"2027-01-10","files":[$listed]}""",
        )
        val all = sums + ("release.json" to hex(File(dir, "release.json").readBytes()))
        File(dir, "SHA256SUMS").writeText(all.entries.joinToString("") { "${it.value}  ${it.key}\n" })
        tamper?.let { File(dir, it).appendText(" ") }
    }

    @Test
    fun `a release whose files match SHA256SUMS and release json, with one data digest, passes and becomes a bundle`() {
        val dir = tmp.newFolder("ok")
        release(dir)
        val info = ReleaseCheck.verify(dir, "data-2026.10", 1L)
        assertEquals(commit, info.commit)
        assertEquals(digest, info.dataDigest)
        assertEquals(setOf("release.json", "metadata.json", "lookup.json", "carrier-variants.json", "apns-conf.xml"), info.sha256.keys)
        val bundled = javaClass.getResourceAsStream("/fixture-bundle.json")!!.use { OcdBundle.parse(it.readBytes().decodeToString()) }
        val bundle = ReleaseCheck.bundle(dir, info, ReleaseCheck.manifest(dir), bundled)
        assertEquals(1, bundle.profiles.size)
        assertEquals(digest, bundle.records!!.basedOnDataDigest)
        assertEquals("2026-10-19", bundle.source.commitDate)
    }

    @Test
    fun `a changed file, another tag or a second data digest refuses the release`() {
        for ((name, setup) in listOf<Pair<String, (File) -> Unit>>(
            "tampered lookup" to { d -> release(d, tamper = "lookup.json") },
            "tampered SHA256SUMS target" to { d -> release(d, tamper = "release.json") },
            "metadata digest" to { d -> release(d, otherDigest = "metadata.json") },
            "apns header digest" to { d -> release(d, otherDigest = "apns-conf.xml") },
            "missing file" to { d -> release(d); File(d, "carrier-variants.json").delete() },
        )) {
            val dir = tmp.newFolder(name.replace(' ', '-'))
            setup(dir)
            try {
                ReleaseCheck.verify(dir, "data-2026.10", 1L)
                fail("$name passed")
            } catch (_: ReleaseException) {
            }
        }
        val dir = tmp.newFolder("tag")
        release(dir, tag = "data-2026.11")
        try {
            ReleaseCheck.verify(dir, "data-2026.10", 1L)
            fail("another tag passed")
        } catch (_: ReleaseException) {
        }
        try {
            ReleaseSource.downloadUrl("v1; rm", "lookup.json")
            fail("tag shape")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("https://github.com/open-carrier-data/open-carrier-data/releases/download/data-2026.10/SHA256SUMS", ReleaseSource.downloadUrl("data-2026.10", "SHA256SUMS"))
    }

    @Test
    fun `OCD's APN rows for the SIM, matched as Android matches them`() {
        val dir = tmp.newFolder("apn")
        release(dir)
        val rows = File(dir, "apns-conf.xml").bufferedReader().use { ApnConf.rowsFor(it.lineSequence(), "26202") }
        assertEquals(4, rows.size)
        assertEquals(digest, ApnConf.headerDigest(File(dir, "apns-conf.xml").readText()))
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

    private fun run() = RunResult(
        id = "r1", startedMs = 1_792_408_702_000L, scheduled = false, sim = SimKey(3, "26202", 2397), simName = "LIDL Connect",
        profileId = "open.26202.aaaaaaaaaaaa", releaseTag = "data-2026.10", device = "SM-G981B (x1s)", stack = "LineageOS, Android 16, userdebug",
        features = listOf(
            FeatureResult(OwnerFeature.VOLTE, false, false, OcdAnswer(true, "device_record", recordSource = "samsung_omc", recordStack = "stock", sameStack = false), onWorks = true, onReason = "ims_registered_with_service", best = true, applied = true),
            FeatureResult(OwnerFeature.VOWIFI, true, false, OcdAnswer(true, "carrier_profile"), offWorks = true, best = null),
        ),
    )

    @Test
    fun `the issue URL is prefilled for OCD's carrier-data form and passes the privacy scan`() {
        val url = IssueReport.url(run())!!
        assertTrue(url.startsWith("https://github.com/open-carrier-data/open-carrier-data/issues/new?template=wrong-or-missing-carrier-data.yml&title="))
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded, "carrier=LIDL Connect" in decoded)
        assertTrue(decoded, "country=Germany" in decoded)
        assertTrue(decoded, "| VoLTE | off | on (device_record, samsung_omc/stock)" in decoded)
        assertTrue(url.length <= IssueReport.MAX_URL)
    }

    @Test
    fun `an identifier in the report stops the URL`() {
        assertNull(IssueReport.url(run().copy(simName = "LIDL 262021234567890")))
        assertNull(IssueReport.url(run().copy(simName = "sip:+491700000000@ims")))
    }

    @Test
    fun `the state file round-trips and an unknown field is ignored`() {
        val state = OwnerState(stop = "x").logged(1, "k", "t")
        assertEquals(state, OwnerState.decode(state.encode()))
        assertNotNull(OwnerState.decode("""{"future":1}"""))
    }
}
