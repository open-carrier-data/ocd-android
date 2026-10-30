/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle order of round 31's item "keys": Android's defaults, OCD's default profile, the
 * Google-lane fill, the matched stack, the cell's measured records; then groups A (with reads)
 * and B, X6, and SM7 or the X5 hold. The device override applies after all of it, in the service.
 */
class BundleTest {
    private val sha7 = Fixture.COMMIT.take(7)

    private fun data(
        records: List<Map<String, Any?>> = Fixture.s20Records(),
        cell: BuildCell = Fixture.CELL,
        reads: Map<String, Any?>? = null,
        values: Map<String, Map<String, Any?>> = Fixture.VALUES,
        alternatives: Map<String, Map<String, List<Map<String, Any?>>>> = emptyMap(),
    ): ServingData = ServingIndex.direct(
        Fixture.dataset(Fixture.write(Fixture.tempDir("bundle"), Fixture.files(profiles = Fixture.profilesJson(values = values, alternatives = alternatives), records = Fixture.recordsJson(records), reads = reads))),
        "installed", cell,
    )

    private val serving by lazy { data() }

    private fun build(
        sim: SimId,
        d: ServingData = serving,
        local: List<Record> = emptyList(),
        tested: Set<String> = emptySet(),
        reference: (String) -> Any? = { null },
        cell: BuildCell? = Fixture.CELL,
        today: String = "2026-10-25",
        crashGuardBuild: Boolean = false,
        sm7: Boolean = false,
    ) = BundleBuilder.build(BuildInput(sim, d, local, tested, reference, today, cell, crashGuardBuild, sm7 = sm7))

    private fun record(json: Map<String, Any?>): Record = Record.parse(Json.parse(Json.write(json)), "test") { k, v -> Types.typed(k, serving.types.getValue(k), v) }

    private val tchibo = SimId("262", "07", imsi = "262075000000001")
    private val o2 = SimId("262", "07", spn = "Example", imsi = "262070000000001")

    @Test
    fun workedExampleTchibo() {
        val b = build(tchibo)
        assertEquals("open.26207.78040a3afcc4+ocd$sha7", b.version)
        assertEquals(true, b.values["carrier_volte_available_bool"])
        assertEquals("ocd:open.26207.78040a3afcc4", b.sources["carrier_volte_available_bool"])
        assertEquals(true, b.values["carrier_vt_available_bool"])
        assertEquals("ocd:open.26207.c07dcc040b58", b.sources["carrier_vt_available_bool"])
        assertEquals(listOf(2, 1), b.values["imsemergency.emergency_domain_preference_int_array"])
        assertEquals("group_a:google", b.sources["imsemergency.emergency_domain_preference_int_array"])
        assertEquals(listOf("default", "dun", "supl"), b.values["carrier_metered_apn_types_strings"])
        assertEquals("group_b:ocd", b.sources["carrier_metered_apn_types_strings"])
        // Round 31: the S20's values come from the device override XML after the bundle, never
        // from OCD's carrier-less records, which main still carries until step 8.
        assertEquals("https://agnss.goog/pad/mtk/prod/1/LTEPO.DAT", b.values["gps.longterm_psds_server_1"])
        assertEquals("default", b.sources["gps.longterm_psds_server_1"])
        assertFalse(b.values.containsKey("imsvoice.voice_on_default_bearer_supported_bool"))
        assertFalse(b.values.containsKey("imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool"))
        assertTrue(b.recordsApplied.isEmpty())
        assertTrue(b.sources.values.none { it.startsWith("device_record:") })
        assertEquals(b.version, b.values[Types.VERSION_KEY])
    }

    @Test
    fun groupANeverServesOcdsOtherVendorValues() {
        val b = build(o2)
        // The O2 profile publishes [9, 9, 9] for the emergency domain preference; the Google value serves.
        assertEquals(listOf(2, 1, 3), b.values["imsemergency.emergency_domain_preference_int_array"])
        // Tchibo's RTT trues are group A (pattern rtt); no Google value, so Android's default.
        val t = build(tchibo)
        assertFalse(t.values.containsKey("rtt_supported_bool"))
        assertEquals("group_a:android_default", t.sources["rtt_supported_bool"])
        assertTrue("rtt_supported_while_roaming_bool" in t.groupA)
        assertTrue("the owner's eight extra keys are group A", "dial_string_replace_string_array" in t.groupA && "gps.es_extension_sec" in t.groupA)
    }

    @Test
    fun aGroupARecordIsNeverServedEvenWithItsRead() {
        // Round 31: only a group A read on a profile value admits a non-Google value; a record's
        // second_agent_read no longer does, and the carrier-less S20 record is not served at all.
        val key = "imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool"
        assertFalse(build(o2).values.containsKey(key))
        val withRead = build(o2, data(Fixture.s20Records(withRead = true)))
        assertFalse(withRead.values.containsKey(key))
        assertEquals("group_a:android_default", withRead.sources[key])
    }

    private val domain = "imsemergency.emergency_domain_preference_int_array"

    @Test
    fun aProceedReadAdmitsTheStacksOwnValue() {
        val b = build(o2, data(reads = Fixture.readsJson()))
        assertEquals(listOf(9, 9, 9), b.values[domain])
        assertEquals("group_a:read:gar:0000000000000001", b.sources[domain])
    }

    @Test
    fun aReadMustMatchKeyValueFamilyProfileAndStack() {
        fun served(read: Map<String, Any?>) = build(o2, data(reads = Fixture.readsJson(listOf(read)))).values[domain]
        val google = listOf(2, 1, 3)
        assertEquals("a hold keeps the Google lane", google, served(Fixture.read(verdict = "hold")))
        assertEquals("another value", google, served(Fixture.read(value = listOf(9, 9))))
        assertEquals("another family", google, served(Fixture.read(family = "apple_carrier_bundles")))
        assertEquals("another profile", google, served(Fixture.read(profile = "open.26207.78040a3afcc4")))
        assertEquals("another stack", google, served(Fixture.read(stack = "phh_ims")))
        assertEquals("another fork", google, served(Fixture.read(fork = "other")))
        assertEquals("this stack's fork", listOf(9, 9, 9), served(Fixture.read(fork = "ocd")))
        // A Tchibo SIM's stack resolves the key from the O2 profile too: the read admits it there.
        assertEquals(listOf(9, 9, 9), build(tchibo, data(reads = Fixture.readsJson())).values[domain])
    }

    @Test
    fun theMostSpecificProfileDecidesAndAnUnreadValueThereKeepsGoogle() {
        // Tchibo sets its own value without a read: the O2 read does not apply to Tchibo SIMs.
        val values = Fixture.VALUES + ("open.26207.78040a3afcc4" to (Fixture.VALUES.getValue("open.26207.78040a3afcc4") + (domain to listOf(7))))
        val b = build(tchibo, data(reads = Fixture.readsJson(), values = values))
        assertEquals(listOf(2, 1), b.values[domain])
        assertEquals("group_a:google", b.sources[domain])
    }

    @Test
    fun anAlternativeWithAProceedReadIsServed() {
        val alts = mapOf("open.26207.c07dcc040b58" to mapOf(domain to listOf(linkedMapOf<String, Any?>("value" to listOf(8), "families" to listOf("apple_carrier_bundles")))))
        val b = build(o2, data(reads = Fixture.readsJson(listOf(Fixture.read(value = listOf(8), family = "apple_carrier_bundles"))), alternatives = alts))
        assertEquals(listOf(8), b.values[domain])
    }

    @Test
    fun aDisplayOnlyFindingTakesAKeyOutOfGroupAButNeverTheDialStringKeys() {
        val reads = Fixture.readsJson(emptyList(), listOf(Fixture.finding("always_show_emergency_alert_onoff_bool"), Fixture.finding("dial_string_replace_string_array"), Fixture.finding("rtt_supported_bool", verdict = "hold")))
        val d = data(reads = reads)
        assertFalse("always_show_emergency_alert_onoff_bool" in d.groupA)
        assertTrue("dial_string_replace_string_array" in d.groupA)
        assertTrue("a held finding keeps the key", "rtt_supported_bool" in d.groupA)
    }

    @Test
    fun theGoogleLaneValueServesWithoutARead() {
        val b = build(o2)
        assertEquals(listOf(2, 1, 3), b.values[domain])
        assertEquals("group_a:google", b.sources[domain])
    }

    @Test
    fun groupBServesTheFirstWithDefault() {
        // Vodafone's own list lacks default, and so does vodafone_de's: Android's default.
        val lidl = SimId("262", "02", spn = "Lidl Connect", imsi = "262020000000002", iccid = "89490200000000000002")
        val v = build(lidl)
        assertFalse(v.values.containsKey("carrier_metered_apn_types_strings"))
        assertEquals("group_b:android_default", v.sources["carrier_metered_apn_types_strings"])
        // Fido: OCD publishes none, fido_ca has none, the lane's default has default.
        val fido = build(SimId("302", "370", imsi = "302370000000001"))
        assertEquals(listOf("default", "mms", "dun"), fido.values["carrier_metered_apn_types_strings"])
        assertEquals("group_b:google", fido.sources["carrier_metered_apn_types_strings"])
    }

    @Test
    fun x6OnTheMmsEndedNetworks() {
        val b = build(o2)
        assertEquals(-1, b.values["smsToMmsTextThreshold"])
        assertEquals("x6", b.sources["smsToMmsTextThreshold"])
        assertEquals(false, b.values["enableGroupMms"])
        assertEquals(true, b.values["enableMultipartSMS"])
        val fido = build(SimId("302", "370", imsi = "302370000000001"))
        assertFalse(fido.values.containsKey("enableGroupMms"))
    }

    private val gid11 = SimId("262", "02", imsi = "262020000000004", gid1 = "11AB")

    @Test
    fun x5HoldsAnOffUntilTestedHere() {
        val b = build(gid11, reference = { k -> if (k == "carrier_volte_available_bool") true else null })
        assertEquals(true, b.values["carrier_volte_available_bool"])
        assertEquals("x5_hold", b.sources["carrier_volte_available_bool"])
        assertEquals(false to true, b.x5Held["carrier_volte_available_bool"])
        assertEquals(false, b.x5Data["carrier_volte_available_bool"])
        // Wi-Fi calling's reference is not the on value: the off is served.
        assertEquals(false, b.values["carrier_wfc_ims_available_bool"])
        assertNull(b.x5Held["carrier_wfc_ims_available_bool"])
    }

    @Test
    fun x5ReleasedByALocalRecordOrATestedPublishedOne() {
        val json = Fixture.record(kind = "measured", key = "carrier_volte_available_bool", value = false, carrier = mapOf("scope" to "profile", "profile_id" to "open.26202.5e6cc8d0e19b"))
        val local = record(json)
        val ref: (String) -> Any? = { true }
        val released = build(gid11, local = listOf(local), reference = ref)
        assertEquals(false, released.values["carrier_volte_available_bool"])
        assertEquals("measured_record:${local.key}", released.sources["carrier_volte_available_bool"])
        val published = data(Fixture.s20Records() + json)
        assertEquals(true, build(gid11, published, reference = ref).values["carrier_volte_available_bool"])
        assertEquals(false, build(gid11, published, tested = setOf(local.key), reference = ref).values["carrier_volte_available_bool"])
    }

    @Test
    fun noSimServesNoSimThenGroups() {
        val b = build(SimId.NO_SIM)
        assertEquals("no_sim+ocd$sha7", b.version)
        assertEquals(false, b.values["carrier_volte_available_bool"])
        assertEquals(2, b.values["iwlan.ike_rekey_hard_timer_in_sec"])
        assertFalse("no step 5: no carrier-less record", b.values.containsKey("gps.longterm_psds_server_1"))
        assertTrue(b.stack.isEmpty())
    }

    @Test
    fun noProfileServesDefaultWithoutDeviceRecords() {
        val b = build(SimId("999", "99", imsi = "999990000000001"))
        assertEquals("default", b.serving)
        assertEquals("default+ocd$sha7", b.version)
        assertEquals("default", b.sources["iwlan.ike_rekey_hard_timer_in_sec"])
        assertFalse(b.values.containsKey("imsvoice.voice_on_default_bearer_supported_bool"))
    }

    @Test
    fun step6ProfileAndSimKeyRecords() {
        val prof = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4")))
        val b = build(tchibo, local = listOf(prof))
        assertEquals(1000, b.values["maxImageHeight"])
        assertEquals("measured_record:${prof.key}", b.sources["maxImageHeight"])
        assertEquals(1944, build(o2, local = listOf(prof)).values["maxImageHeight"] ?: 1944)
        val simKey = record(Fixture.record(kind = "measured", key = "carrier_vt_available_bool", value = true, carrier = mapOf("scope" to "sim_key", "mcc" to "999", "mnc" to "99", "gid1" to "ab01")))
        val unknown = SimId("999", "99", imsi = "999990000000001", gid1 = "AB01")
        assertEquals(true, build(unknown, local = listOf(simKey)).values["carrier_vt_available_bool"])
        // A sim_key record never applies to a SIM with an OCD profile.
        assertFalse(build(o2, local = listOf(record(Fixture.record(kind = "measured", key = "maxMessageSize", value = 1, carrier = mapOf("scope" to "sim_key", "mcc" to "262", "mnc" to "07", "spn" to "Example"))))).values["maxMessageSize"] == 1)
    }

    @Test
    fun expiredOtherCellAndForkRecordsAreNotServed() {
        val expired = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4"), validUntil = "2026-10-01", lastMeasured = "2026-07-01"))
        assertEquals(1944, build(tchibo, local = listOf(expired)).values["maxImageHeight"])
        val other = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4"), model = "SM-G980F"))
        assertEquals(1944, build(tchibo, local = listOf(other)).values["maxImageHeight"])
        // The exact cell: a record of the stock stack (no fork) is not served on the fork's phone, nor the reverse.
        val stock = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4"), fork = null))
        assertEquals(1944, build(tchibo, local = listOf(stock)).values["maxImageHeight"])
        val noFork = Fixture.CELL.copy(stackFork = null)
        assertEquals(1000, build(tchibo, data(cell = noFork), local = listOf(stock), cell = noFork).values["maxImageHeight"])
        val forked = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4")))
        assertEquals(1944, build(tchibo, data(cell = noFork), local = listOf(forked), cell = noFork).values["maxImageHeight"])
        assertEquals(1944, build(tchibo, local = listOf(forked), cell = null).values["maxImageHeight"])
    }

    @Test
    fun noCarrierLessRecordIsServed() {
        // A measured record with carrier scope all (round 30's step 5) and the configured_device kind end.
        val all = Fixture.record(kind = "measured", key = "maxImageHeight", value = 1000)
        val d = data(Fixture.s20Records() + all)
        val b = build(tchibo, d)
        assertEquals(1944, b.values["maxImageHeight"])
        assertTrue(b.recordsApplied.isEmpty())
    }

    @Test
    fun groupBRecordsAreNeverServed() {
        val r = record(Fixture.record(kind = "measured", key = "carrier_metered_apn_types_strings", value = listOf("mms"), carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.c07dcc040b58")))
        assertEquals(listOf("default", "dun", "supl"), build(o2, local = listOf(r)).values["carrier_metered_apn_types_strings"])
    }

    @Test
    fun theNewerOfLocalAndPublishedCounts() {
        val old = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 1, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4"), lastMeasured = "2026-10-01"))
        val new = record(Fixture.record(kind = "measured", key = "maxImageHeight", value = 2, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.78040a3afcc4"), lastMeasured = "2026-10-20"))
        assertEquals(old.key, new.key)
        assertEquals(listOf(new), BundleBuilder.effectiveRecords(listOf(old), listOf(new)))
        assertEquals(listOf(old), BundleBuilder.effectiveRecords(listOf(old), listOf(old.copy(value = 3))))
    }

    @Test
    fun crashGuardOnTheBuildServesGroupAOnly() {
        val b = build(tchibo, data(Fixture.s20Records(withRead = true)), crashGuardBuild = true)
        assertEquals("crash_guard_build+ocd$sha7", b.version)
        assertFalse(b.values.containsKey("imsvoice.voice_on_default_bearer_supported_bool"))
        assertFalse(b.values.containsKey("imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool"))
        assertFalse(b.values.containsKey("carrier_volte_available_bool"))
        assertFalse(b.values.containsKey("iwlan.ike_rekey_hard_timer_in_sec"))
    }

    @Test
    fun theLastFallbackServesTheVersionStringOnly() {
        val b = BundleBuilder.minimal(sha7)
        assertEquals("fallback+ocd$sha7", b.version)
        assertEquals(setOf(Types.VERSION_KEY), b.values.keys)
    }

    // --- SM7 (round 31): without a tester ---------------------------------------------------------

    private val sm7Values = Fixture.VALUES + ("open.26202.5e6cc8d0e19b" to linkedMapOf<String, Any?>(
        "carrier_volte_available_bool" to false, "carrier_wfc_ims_available_bool" to false, "hide_enhanced_4g_lte_bool" to true,
        "editable_wfc_mode_bool" to false, "carrier_default_wfc_ims_roaming_mode_int" to 0, "maxImageHeight" to 77,
    ))

    @Test
    fun sm7ServesX3ToX5OnlyWhereTheyEqualTheGoogleLane() {
        val d = data(values = sm7Values)
        val b = build(gid11, d, sm7 = true)
        assertTrue(b.sm7)
        // No Google value for these on vodafone_de: Android's default.
        for (k in listOf("carrier_volte_available_bool", "carrier_wfc_ims_available_bool", "hide_enhanced_4g_lte_bool", "editable_wfc_mode_bool", "carrier_default_wfc_ims_roaming_mode_int")) {
            assertFalse(k, b.values.containsKey(k))
            assertEquals(k, "sm7:android_default", b.sources[k])
            assertTrue(k, k in b.sm7Held)
        }
        assertEquals("other keys are untouched", 77, b.values["maxImageHeight"])
        assertTrue("no X5 hold under SM7", b.x5Held.isEmpty())
        // With a tester: the data's values (the X5 hold only where the reference is on).
        val t = build(gid11, d)
        assertEquals(false, t.values["carrier_wfc_ims_available_bool"])
        assertEquals(true, t.values["hide_enhanced_4g_lte_bool"])
        assertEquals(0, t.values["carrier_default_wfc_ims_roaming_mode_int"])
    }

    @Test
    fun sm7ServesTheGoogleValueWhenOneExists() {
        // O2's own profile says VoLTE false; o2_de says true: under SM7 the Google value stands.
        val values = Fixture.VALUES + ("open.26207.c07dcc040b58" to (Fixture.VALUES.getValue("open.26207.c07dcc040b58") + ("carrier_volte_available_bool" to false)))
        val b = build(o2, data(values = values), sm7 = true)
        assertEquals(true, b.values["carrier_volte_available_bool"])
        assertEquals("sm7:google", b.sources["carrier_volte_available_bool"])
        assertEquals(false to true, b.sm7Held["carrier_volte_available_bool"])
        // An off that equals the Google value is served as is.
        val lane = Fixture.laneEntries().toMutableMap()
        lane["o2_de"] = lane.getValue("o2_de") + ("carrier_volte_available_bool" to false)
        val d = ServingIndex.direct(Fixture.dataset(Fixture.write(Fixture.tempDir("bundle-sm7"), Fixture.files(profiles = Fixture.profilesJson(values = values), lane = Fixture.laneJson(lane)))), "installed", Fixture.CELL)
        assertEquals(false, build(o2, d, sm7 = true).values["carrier_volte_available_bool"])
    }

    @Test
    fun sm7LeavesOutAMeasuredRecordOfAnSm7Kind() {
        val off = record(Fixture.record(kind = "measured", key = "carrier_volte_available_bool", value = false, carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.c07dcc040b58")))
        val b = build(o2, local = listOf(off), sm7 = true)
        assertEquals(true, b.values["carrier_volte_available_bool"])
        assertEquals("sm7:google", b.sources["carrier_volte_available_bool"])
    }

    @Test
    fun noEmergencyNumberListIsServed() {
        val values = Fixture.VALUES + ("open.26207.c07dcc040b58" to (Fixture.VALUES.getValue("open.26207.c07dcc040b58") + ("carrier_ecc_list_string_array" to listOf("112", "911"))))
        val b = build(o2, data(values = values))
        assertFalse(b.values.containsKey("carrier_ecc_list_string_array"))
        assertEquals("no_emergency_number", b.sources["carrier_ecc_list_string_array"])
    }

    @Test
    fun theServingIndexServesWhatTheDatasetServes() {
        val ds = Fixture.dataset()
        val dir = Fixture.tempDir("bundle-index")
        ServingIndex.write(ds, dir, "installed", Fixture.CELL, "cell")
        val fromIndex = ServingIndex.read(dir)
        val direct = ServingIndex.direct(ds, "installed", Fixture.CELL)
        for (sim in listOf(tchibo, o2, gid11, SimId.NO_SIM, SimId("302", "370", imsi = "302370000000003", gid1 = "2c00", carrierId = 578, specificCarrierId = 578))) {
            val a = build(sim, fromIndex)
            val b = build(sim, direct)
            assertEquals(b.values, a.values)
            assertEquals(b.sources, a.sources)
        }
    }

    /**
     * Build E's finding (S20, LIDL, 500525ec): the first load after boot streams the baked files
     * and served `carrier_use_ims_first_for_emergency_bool` true, admitted by a `proceed` read of
     * the profile's own value; every load from the serving index left it out, and explain said
     * `group_a:android_default`. A read must name a family of the value it admits, and the index
     * lost each profile's sources. The index, the streamed baked files and the dataset itself
     * serve the same bundle: values and sources, for a read over Android's default (LIDL) and one
     * over a Google value (O2), and for keys no read admits.
     */
    @Test
    fun theIndexAndTheStreamedBakedFilesServeAReadAsTheDatasetDoes() {
        val key = "carrier_use_ims_first_for_emergency_bool"
        val lidlProfile = "open.26202.8cebd7550c67"
        val values = Fixture.VALUES + (lidlProfile to (Fixture.VALUES.getValue(lidlProfile) + (key to true)))
        val sources = Fixture.SOURCES + (lidlProfile to mapOf(key to listOf("lineageos_device_overlays")))
        val reads = Fixture.readsJson(listOf(Fixture.read(), Fixture.read(key = key, value = true, family = "lineageos_device_overlays", profile = lidlProfile, id = "gar:0000000000000002")))
        val profiles = Fixture.profilesJson(values = values, sources = sources, keys = Fixture.KEYS + (key to ("bool" to "A")))
        val files = Fixture.files(profiles = profiles, lane = Fixture.laneJson(state = "private_lane_at_build_pin"), role = "build", reads = reads)
        val baked = Fixture.write(Fixture.tempDir("bundle-read-baked"), Fixture.seal(files, role = "build", laneAsExtra = true))
        val ds = Fixture.dataset(baked, role = "build")
        val dir = Fixture.tempDir("bundle-read-index")
        ServingIndex.write(ds, dir, "build", Fixture.CELL, "cell")
        val fromIndex = ServingIndex.read(dir)
        val direct = ServingIndex.direct(ds, "build", Fixture.CELL)
        val lidl = SimId("262", "02", imsi = "262020000000001")
        for (sim in listOf(lidl, o2, tchibo, gid11)) {
            val want = build(sim, direct)
            val streamed = build(sim, ServingIndex.streamBaked(baked, sim.network, Fixture.CELL))
            val indexed = build(sim, fromIndex)
            assertEquals("streamed values, ${sim.network}", want.values, streamed.values)
            assertEquals("streamed sources, ${sim.network}", want.sources, streamed.sources)
            assertEquals("index values, ${sim.network}", want.values, indexed.values)
            assertEquals("index sources, ${sim.network}", want.sources, indexed.sources)
        }
        val l = build(lidl, fromIndex)
        assertEquals(true, l.values[key])
        assertEquals("group_a:read:gar:0000000000000002", l.sources[key])
        val o = build(o2, fromIndex)
        assertEquals(listOf(9, 9, 9), o.values[domain])
        assertEquals("group_a:read:gar:0000000000000001", o.sources[domain])
        // Without its read the key is Android's default on every path.
        val t = build(tchibo, fromIndex)
        assertFalse(t.values.containsKey("rtt_supported_bool"))
        assertEquals("group_a:android_default", t.sources["rtt_supported_bool"])
    }

    @Test
    fun anIndexWithoutTheSourcesIsNeverCompleteNorRead() {
        val ds = Fixture.dataset()
        val dir = Fixture.tempDir("bundle-index-schema")
        ServingIndex.write(ds, dir, "installed", Fixture.CELL, "cell")
        assertTrue(ServingIndex.complete(dir, ds.datasetDigest, "cell", ds.commit))
        // An ocd-index/1 index (no sources): its marker names no schema, so it is built again.
        java.io.File(dir, "complete").writeText("${ds.datasetDigest} ${ds.commit}")
        assertFalse(ServingIndex.complete(dir, ds.datasetDigest, "cell", ds.commit))
        assertFalse(ServingIndex.complete(dir, ds.datasetDigest, null, ds.commit))
        // A load that finds one anyway (the build's index is read without the marker check) fails over.
        val meta = java.io.File(dir, "meta.json")
        meta.writeText(meta.readText().replace("\"ocd-index/2\"", "\"ocd-index/1\""))
        val refused = runCatching { ServingIndex.read(dir) }.exceptionOrNull()
        assertEquals("storage", (refused as? Refused)?.code)
    }

    @Test
    fun streamingTheBakedFilesServesTheSame() {
        val files = Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"), role = "build")
        val dir = Fixture.write(Fixture.tempDir("bundle-stream"), Fixture.seal(files, role = "build", laneAsExtra = true))
        val streamed = ServingIndex.streamBaked(dir, "262-07", Fixture.CELL)
        assertEquals(build(tchibo).values, build(tchibo, streamed).values)
    }

    @Test
    fun mainLaneKeepsTheBuildsWithheldValues() {
        val main = GoogleLane("sha256:m", emptyMap(), "public_main", emptyList(),
            mapOf("o2_de" to mapOf("carrier_volte_available_bool" to true), "default" to emptyMap(), "no_sim" to emptyMap()))
        val build = GoogleLane("sha256:b", emptyMap(), "private_lane_at_build_pin", emptyList(),
            mapOf("o2_de" to mapOf("carrier_volte_available_bool" to false, "vvm_destination_number_string" to "333"),
                "default" to emptyMap(), "no_sim" to emptyMap()))
        val lane = main.filledFrom(build)
        assertEquals(true, lane.entries["o2_de"]!!["carrier_volte_available_bool"])
        assertEquals("333", lane.entries["o2_de"]!!["vvm_destination_number_string"])
        assertEquals("public_main", lane.state)
    }
}
