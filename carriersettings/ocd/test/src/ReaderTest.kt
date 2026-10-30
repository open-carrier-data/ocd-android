/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The reader's checks of spec 2.12, one refusal at a time, and the strict JSON rules of 2.2. */
class ReaderTest {
    private var n = 0

    private fun dir(files: Map<String, ByteArray>): File = Fixture.write(Fixture.tempDir("reader-${n++}"), files)

    private fun refused(code: String, files: Map<String, ByteArray>, verifier: Verifier = Fixture.verifier(), role: String = "app", dirOverride: File? = null) {
        val d = dirOverride ?: dir(files)
        try {
            verifier.verify(d, role)
            fail("expected $code")
        } catch (e: Refused) {
            assertEquals("${e.detail}", code, e.code)
            assertTrue("no SIM identifier in a reason", !e.detail.contains("262075000000001"))
        }
    }

    /** Recomputes the pin's blobs over the current bytes, leaving the manifest as it is. */
    private fun repin(files: MutableMap<String, ByteArray>): MutableMap<String, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val pin = Json.parse(String(files.getValue("pin.json"))) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val entries = pin["files"] as MutableMap<String, Any?>
        for (path in entries.keys.toList()) {
            val local = DatasetFile.byPath(path)!!.local
            val bytes = files.getValue(local)
            entries[path] = linkedMapOf("blob" to Files.gitBlob(bytes), "bytes" to bytes.size)
        }
        files["pin.json"] = Json.write(pin).toByteArray()
        return files
    }

    private fun edit(files: MutableMap<String, ByteArray>, name: String, change: (MutableMap<String, Any?>) -> Unit): MutableMap<String, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        val tree = Json.parse(String(files.getValue(name))) as MutableMap<String, Any?>
        change(tree)
        files[name] = Json.write(tree).toByteArray()
        return files
    }

    @Suppress("UNCHECKED_CAST")
    private fun profileOf(tree: Map<String, Any?>, net: String, id: String): MutableMap<String, Any?> =
        ((tree["networks"] as Map<String, Any?>)[net] as Map<String, Any?>).let { (it["profiles"] as List<Any?>).map { p -> p as MutableMap<String, Any?> }.single { p -> p["id"] == id } }

    // --- a good dataset -------------------------------------------------------------------

    @Test
    fun goodDatasetPassesChecks1To13() {
        val v = Fixture.verifier(sims = listOf(SimId("262", "07", imsi = "262075000000001")))
        val ds = v.verify(dir(Fixture.files()))
        assertEquals(Fixture.COMMIT, ds.commit)
        assertEquals("dataset", ds.laneSource)
        assertEquals((1..13).toSet(), v.checks.map { it.n }.toSet())
        assertTrue(v.checks.all { it.ok })
        assertEquals(14, ds.doc.rows.size)
    }

    @Test
    fun aDatasetRecordOfALocalTesterIsRefused() {
        val local = Fixture.record(kind = "measured", key = "maxImageHeight", value = 1, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4")).toMutableMap()
        local["tester"] = "local"
        refused("invalid_value", Fixture.files(records = Fixture.recordsJson(Fixture.s20Records() + local)))
    }

    @Test
    fun theGroupAReadsFileIsReadStrictlyAndTyped() {
        val ds = Fixture.verifier().verify(dir(Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read()), listOf(Fixture.finding("always_show_emergency_alert_onoff_bool"))))))
        assertEquals(1, ds.reads!!.reads.size)
        assertEquals(listOf(9, 9, 9), ds.reads!!.reads[0].value)
        assertEquals(setOf("always_show_emergency_alert_onoff_bool"), ds.reads!!.left)
        refused("wrong_type", Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read(value = "x")))))
        refused("invalid_value", Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read(verdict = "maybe")))))
        refused("invalid_value", Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read(id = "r:1")))))
        refused("invalid_value", Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read(), Fixture.read()))))
        refused("schema_unknown", Fixture.files(reads = Fixture.readsJson().apply { put("extra", 1) }))
        refused("unknown_key", Fixture.files(reads = Fixture.readsJson(listOf(Fixture.read(key = "carrier_typo_bool", value = true)))))
        refused("data_digest_disagree", Fixture.files(reads = Fixture.readsJson(digest = "sha256:" + "1".repeat(64))))
        // A dataset without the file has no reads (it is optional).
        assertNull(Fixture.verifier().verify(dir(Fixture.files())).reads)
    }

    @Test
    fun theS20RecordKeysAreTheSpecs() {
        val keys = Fixture.s20Records().map { it["record_key"] }
        assertEquals(listOf("r:40ee9931e862c76c", "r:63d82c4e3fa3a0ae", "r:5e72fea0c8d2f885", "r:e41c807e9f154e6a", "r:937a114148bf73b8"), keys)
    }

    @Test
    fun bakedDatasetWithThePrivateLaneAsExtra() {
        val files = Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"), role = "build")
        val sealed = Fixture.seal(files, role = "build", laneAsExtra = true)
        val ds = Fixture.verifier().verify(dir(sealed), "build")
        assertEquals("dataset", ds.laneSource)
        assertTrue(!ds.manifest.googleLaneIncluded)
    }

    // --- check 1 ----------------------------------------------------------------------------

    @Test
    fun pinCommitNot40Hex() = refused("pin_invalid", edit(Fixture.files(), "pin.json") { it["commit"] = "5358c2598be8" })

    @Test
    fun pinUnknownMember() = refused("pin_invalid", edit(Fixture.files(), "pin.json") { it["token"] = "x" })

    @Test
    fun appPinMayNotCarryExtra() {
        val files = Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"))
        refused("pin_invalid", Fixture.seal(files, role = "app", laneAsExtra = true))
    }

    @Test
    fun pinNamesFewerFilesThanTheManifest() {
        val files = edit(Fixture.files(), "pin.json") {
            @Suppress("UNCHECKED_CAST")
            (it["files"] as MutableMap<String, Any?>).remove(DatasetFile.DEVICE_RECORDS.path)
        }
        val d = dir(files)
        File(d, "device-records.json").delete()
        refused("pin_invalid", emptyMap(), dirOverride = d)
    }

    // --- check 2 ----------------------------------------------------------------------------

    @Test
    fun aFileIsMissing() {
        val d = dir(Fixture.files())
        File(d, "apn-index.json").delete()
        refused("bad_request", emptyMap(), dirOverride = d)
    }

    @Test
    fun aFileOverItsCap() {
        val files = Fixture.files()
        files["manifest.json"] = ByteArray(300 * 1024) { ' '.code.toByte() }
        refused("too_large", files)
    }

    // --- check 3 ----------------------------------------------------------------------------

    @Test
    fun aChangedByteFailsTheBlob() {
        val files = Fixture.files()
        val p = files.getValue("profiles.json").copyOf()
        p[p.size - 2] = ' '.code.toByte()
        files["profiles.json"] = p
        refused("blob_mismatch", files)
    }

    // --- checks 4 and 5 -------------------------------------------------------------------

    @Test
    fun manifestSchemaVersion2() = refused("schema_unknown", Fixture.seal(Fixture.files()) { it["schema_version"] = 2 })

    @Test
    fun manifestNotJson() {
        val files = Fixture.files()
        files["manifest.json"] = "{\"schema_version\": 1,".toByteArray()
        refused("parse_error", repin(files))
    }

    @Test
    fun sha256DiffersFromTheManifest() {
        val files = Fixture.files()
        files["device-records.json"] = Json.write(Fixture.recordsJson(Fixture.s20Records().take(4))).toByteArray()
        refused("sha256_mismatch", repin(files))
    }

    @Test
    fun datasetDigestRecomputed() = refused("dataset_digest_mismatch", Fixture.seal(Fixture.files()) { it["dataset_digest"] = "sha256:" + "0".repeat(64) })

    // --- check 6 ----------------------------------------------------------------------------

    @Test
    fun aFileNamesAnotherDataDigest() {
        val other = "sha256:" + "1".repeat(64)
        refused("data_digest_disagree", Fixture.files(records = Fixture.recordsJson(digest = other)))
    }

    @Test
    fun theXmlHeaderNamesAnotherDataDigest() {
        val files = Fixture.files()
        val other = "sha256:" + "2".repeat(64)
        val x = Fixture.xml(digest = other)
        files["apns-conf.xml"] = x.toByteArray()
        files["apn-index.json"] = Fixture.apnIndex(x).toByteArray()
        refused("data_digest_disagree", Fixture.seal(files))
    }

    // --- check 7 ----------------------------------------------------------------------------

    @Test
    fun duplicateMemberInProfiles() {
        val files = Fixture.files()
        files["profiles.json"] = String(files.getValue("profiles.json")).replaceFirst("{\"schema_version\":1,", "{\"schema_version\":1,\"schema_version\":1,").toByteArray()
        refused("parse_error", Fixture.seal(files))
    }

    @Test
    fun unknownMemberInAProfile() {
        val tree = Fixture.profilesJson()
        profileOf(tree, "262-07", "open.26207.c07dcc040b58")["comment"] = "x"
        refused("schema_unknown", Fixture.files(profiles = tree))
    }

    @Test
    fun unknownRecordsSchemaVersion() {
        val r = Fixture.recordsJson()
        r["schema_version"] = 2
        refused("schema_unknown", Fixture.files(records = r))
    }

    // --- check 8 ----------------------------------------------------------------------------

    @Test
    fun aValueKeyNotInTheRegistry() = refused("unknown_key", Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.c07dcc040b58" to mapOf("carrier_typo_bool" to true)))))

    @Test
    fun anIntWrittenWithAFraction() = refused("wrong_type", Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.78040a3afcc4" to mapOf("maxImageHeight" to Json.Num("1944.0"))))))

    @Test
    fun aBoolWrittenAsAString() = refused("wrong_type", Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.78040a3afcc4" to mapOf("hide_ims_apn_bool" to "true")))))

    @Test
    fun wfcModeOutsideItsDomain() = refused("invalid_value", Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.c07dcc040b58" to mapOf("carrier_default_wfc_ims_mode_int" to 3)))))

    @Test
    fun noValueForTheVersionString() {
        val keys = Fixture.KEYS + ("carrier_config_version_string" to ("string" to null))
        refused("invalid_value", Fixture.files(profiles = Fixture.profilesJson(keys = keys, values = mapOf("open.26207.c07dcc040b58" to mapOf("carrier_config_version_string" to "x")))))
    }

    @Test
    fun specificityRecomputed() {
        val tree = Fixture.profilesJson()
        profileOf(tree, "262-07", "open.26207.78040a3afcc4")["specificity"] = 0
        refused("invalid_value", Fixture.files(profiles = tree))
    }

    @Test
    fun multiNetworkCopiesMustBeEqual() {
        val tree = Fixture.profilesJson()
        profileOf(tree, "262-11", "open.26207.394d5585811d")["name"] = "another name"
        refused("invalid_value", Fixture.files(profiles = tree))
    }

    @Test
    fun aFillEntryMissingFromTheIncludedLane() {
        val entries = Fixture.laneEntries().filterKeys { it != "tchibo_de" }
        refused("invalid_value", Fixture.files(lane = Fixture.laneJson(entries)))
    }

    @Test
    fun aBundleValueDeeperThanThree() {
        fun nest(d: Int): Map<String, Any?> = if (d == 0) mapOf("x" to linkedMapOf("type" to "int", "value" to 1)) else mapOf("b" to linkedMapOf("type" to "bundle", "value" to nest(d - 1)))
        refused("invalid_value", Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.c07dcc040b58" to mapOf("imsvoice.amrnb_payload_description_bundle" to nest(3))))))
    }

    @Test
    fun aBundleValueIsTyped() {
        val bundle = mapOf(
            "103" to linkedMapOf("type" to "bundle", "value" to mapOf("imsvoice.amr_codec_attribute_modeset_int_array" to linkedMapOf("type" to "int_array", "value" to listOf(0, 2, 4, 7)))),
            "98" to linkedMapOf("type" to "bundle", "value" to mapOf("imsvoice.amr_codec_attribute_payload_format_int" to linkedMapOf("type" to "int", "value" to 1))),
        )
        val ds = Fixture.dataset(dir(Fixture.files(profiles = Fixture.profilesJson(values = mapOf("open.26207.c07dcc040b58" to mapOf("imsvoice.amrnb_payload_description_bundle" to bundle))))))
        val value = ds.profiles.networks.getValue("262-07").first { it.id == "open.26207.c07dcc040b58" }.values.getValue("imsvoice.amrnb_payload_description_bundle")
        @Suppress("UNCHECKED_CAST")
        assertEquals(KeyType.BUNDLE, (value as Map<String, Inner>).getValue("103").type)
    }

    private fun baked(types: Map<String, KeyType>) = Baked(Manifest.parse(Fixture.files(generatedAt = "2026-10-01T00:00:00Z").getValue("manifest.json")), types, null)

    private val bakedTypes = Fixture.KEYS.mapValues { KeyType.of(it.value.first)!! }

    @Test
    fun aNewKeyNeitherTheBuildNorAndroidKnows() {
        val keys = Fixture.KEYS + ("carrier_brand_new_bool" to ("bool" to null))
        refused("unknown_key", Fixture.files(profiles = Fixture.profilesJson(keys = keys)), Fixture.verifier(baked(bakedTypes)))
    }

    @Test
    fun aNewKeyAndroidKnowsWithThatType() {
        val keys = Fixture.KEYS + ("carrier_new_android_key_int" to ("int" to null))
        Fixture.verifier(baked(bakedTypes)).verify(dir(Fixture.files(profiles = Fixture.profilesJson(keys = keys))))
    }

    @Test
    fun aNullAndroidDefaultIsNoPrimitive() {
        val keys = Fixture.KEYS + ("carrier_null_default_string" to ("int" to null))
        refused("wrong_type", Fixture.files(profiles = Fixture.profilesJson(keys = keys)), Fixture.verifier(baked(bakedTypes)))
    }

    @Test
    fun aTypeChangeAgainstTheBuild() {
        val keys = Fixture.KEYS + ("maxImageHeight" to ("long" to null))
        refused("wrong_type", Fixture.files(profiles = Fixture.profilesJson(keys = keys, values = emptyMap())), Fixture.verifier(baked(bakedTypes)))
    }

    // --- checks 9 and 10 --------------------------------------------------------------------

    @Test
    fun apnVersionAgainstTheManifest() = refused("apn_version", Fixture.seal(Fixture.files()) { it["apn_database_version"] = 7 })

    @Test
    fun apnVersionAgainstThePlatform() = refused("apn_version", Fixture.files(), Fixture.verifier(platformApn = 9))

    @Test
    fun anUnreadablePlatformVersionRefuses() = refused("apn_version", Fixture.files(), Fixture.verifier(platformApn = null))

    @Test
    fun aRowKeyDiffersFromTheIndex() {
        val files = edit(Fixture.files(), "apn-index.json") {
            @Suppress("UNCHECKED_CAST")
            ((it["rows"] as List<Any?>)[3] as MutableMap<String, Any?>)["row_key"] = "a:" + "f".repeat(16)
        }
        refused("apn_index_mismatch", Fixture.seal(files))
    }

    @Test
    fun aRowOnAnotherLine() {
        val files = Fixture.files()
        val x = Fixture.xml()
        files["apn-index.json"] = Fixture.apnIndex(x).replaceFirst("\"line\":7,", "\"line\":8,").toByteArray()
        refused("apn_index_mismatch", Fixture.seal(files))
    }

    @Test
    fun twoElementsOnOneLine() {
        val files = Fixture.files()
        val rows = Fixture.ROWS.toMutableList()
        rows[0] = rows[0].first to (rows[0].second + rows[1].second.trim())
        files["apns-conf.xml"] = Fixture.xml(rows).toByteArray()
        refused("apn_index_mismatch", Fixture.seal(files))
    }

    // --- checks 11 to 13 --------------------------------------------------------------------

    @Test
    fun olderThanTheBuild() {
        val bakedManifest = Manifest.parse(Fixture.files(generatedAt = "2026-10-26T00:00:00Z", commit = "a".repeat(40), records = Fixture.recordsJson(Fixture.s20Records(withRead = true))).getValue("manifest.json"))
        refused("older_than_build", Fixture.files(), Fixture.verifier(Baked(bakedManifest, bakedTypes, null)))
    }

    @Test
    fun equalTimesFavourTheBuild() {
        val bakedManifest = Manifest.parse(Fixture.files(records = Fixture.recordsJson(Fixture.s20Records(withRead = true))).getValue("manifest.json"))
        refused("older_than_build", Fixture.files(), Fixture.verifier(Baked(bakedManifest, bakedTypes, null)))
    }

    @Test
    fun noGoogleLaneAnywhere() = refused("google_lane_missing", Fixture.files(lane = null), Fixture.verifier(baked(bakedTypes)))

    @Test
    fun theBakedLaneServesADatasetWithout() {
        val lane = GoogleLane.parse(Json.write(Fixture.laneJson()).toByteArray()) { k, v -> Types.typed(k, bakedTypes.getValue(k), v) }
        val bakedManifest = Manifest.parse(Fixture.files(generatedAt = "2026-10-01T00:00:00Z").getValue("manifest.json"))
        val ds = Fixture.verifier(Baked(bakedManifest, bakedTypes, lane)).verify(dir(Fixture.files(lane = null)))
        assertEquals("baked", ds.laneSource)
    }

    @Test
    fun aKeyOnlyTheBuildsLaneCarriesIsTypedAndIndexed() {
        val withheld = "international_roaming_dial_string_replace_string_array"
        val types = bakedTypes + (withheld to KeyType.of("string_array")!!)
        val entries = Fixture.laneEntries().toMutableMap()
        entries["o2_de"] = LinkedHashMap(entries.getValue("o2_de")).apply { put(withheld, listOf("+49:0")) }
        val lane = GoogleLane.parse(Json.write(Fixture.laneJson(entries, state = "private_lane_at_build_pin")).toByteArray()) { k, v -> Types.typed(k, types.getValue(k), v) }
        val bakedManifest = Manifest.parse(Fixture.files(generatedAt = "2026-10-01T00:00:00Z").getValue("manifest.json"))
        val ds = Fixture.verifier(Baked(bakedManifest, types, lane)).verify(dir(Fixture.files()))
        assertEquals("dataset", ds.laneSource)
        assertEquals(listOf("+49:0"), ds.lane.entries.getValue("o2_de")[withheld])
        ServingIndex.write(ds, Fixture.tempDir("withheld-index"), "installed", Fixture.CELL, "cell")
    }

    @Test
    fun aSimsFillEntryMissingFromTheBakedLane() {
        val lane = GoogleLane.parse(Json.write(Fixture.laneJson(Fixture.laneEntries().filterKeys { it != "tchibo_de" })).toByteArray()) { k, v -> Types.typed(k, bakedTypes.getValue(k), v) }
        val bakedManifest = Manifest.parse(Fixture.files(generatedAt = "2026-10-01T00:00:00Z").getValue("manifest.json"))
        val tchibo = SimId("262", "07", imsi = "262075000000001")
        refused("fill_missing_for_sim", Fixture.files(lane = null), Fixture.verifier(Baked(bakedManifest, bakedTypes, lane), sims = listOf(tchibo)))
    }

    @Test
    fun theCrashGuardsDataset() {
        val files = Fixture.files()
        val digest = Manifest.parse(files.getValue("manifest.json")).datasetDigest
        refused("crash_guard_dataset", files, Fixture.verifier(crashGuard = digest))
    }

    // --- 2.2: strict JSON -------------------------------------------------------------------

    private fun bad(text: String, limits: Json.Limits = Json.DEFAULT) {
        try {
            Json.parseBytes(text.toByteArray(), limits)
            fail("expected a parse error for $text")
        } catch (e: Json.ParseException) {
            // refused
        }
    }

    @Test fun jsonByteOrderMark() = bad("﻿{}")

    @Test fun jsonNaN() = bad("{\"a\": NaN}")

    @Test fun jsonInfinity() = bad("{\"a\": -Infinity}")

    @Test fun jsonEscapedNul() = bad("{\"a\": \"x\\u0000\"}")

    @Test fun jsonDuplicate() = bad("{\"a\": 1, \"a\": 1}")

    @Test fun jsonTrailingText() = bad("{} {}")

    @Test fun jsonLeadingZero() = bad("{\"a\": 01}")

    @Test fun jsonLongString() = bad("{\"a\": \"${"x".repeat(4097)}\"}")

    @Test fun jsonLongArray() = bad("{\"a\": [${(1..1025).joinToString(",")}]}")

    @Test
    fun jsonTopLevelListsAreNotCapped() {
        Json.parseBytes("{\"rows\": [${(1..1500).joinToString(",")}]}".toByteArray(), Json.DATASET)
        bad("{\"other\": [${(1..1500).joinToString(",")}]}", Json.DATASET)
    }

    @Test
    fun jsonStringsKeptVerbatim() {
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse("{\"spn\": \"C Spire\\r\"}") as Map<String, Any?>
        assertEquals("C Spire\r", m["spn"])
    }

    @Test
    fun jsonNotUtf8() {
        try {
            Json.parseBytes(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte()))
            fail("expected a parse error")
        } catch (e: Json.ParseException) {
            // refused
        }
    }
}
