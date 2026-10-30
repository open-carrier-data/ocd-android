/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File

/**
 * A complete dataset for the tests, built the way the exporter builds one (spec section 2):
 * appendix A's profiles and Google rules, fill lists computed by [ReferenceExporter], the
 * S20's five round 30 records (main still carries them; round 31 never serves them), optional
 * group A reads (R31.7), and APN rows for the coupling vectors of 4.6 (the three CNT rows are
 * the public file's lines 7779 to 7781, the others shortened). All IMSIs and ICCIDs are
 * synthetic. [seal] recomputes the manifest and the pin over whatever files a test changed.
 */
object Fixture {
    const val DATA_DIGEST = "sha256:50c890109bcf5af69a84d57eb2216b1bf3d6c27fc6f29cc7c578454d9cfb9857"
    const val COMMIT = "5358c2598be8a1b2c3d4e5f60718293a4b5c6d7e"
    val CELL = BuildCell("x1s", "SM-G981B", "lineageos", "24.0", "aosp_imsstack", "ocd", firstUsePassedOn = "2026-10-26")

    val KEYS: Map<String, Pair<String, String?>> = linkedMapOf(
        "carrier_volte_available_bool" to ("bool" to null),
        "carrier_vt_available_bool" to ("bool" to null),
        "carrier_wfc_ims_available_bool" to ("bool" to null),
        "imssms.sms_over_ims_supported_bool" to ("bool" to null),
        "carrier_volte_provisioning_required_bool" to ("bool" to null),
        "carrier_volte_provisioned_bool" to ("bool" to null),
        "imsemergency.emergency_domain_preference_int_array" to ("int_array" to "A"),
        "imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool" to ("bool" to "A"),
        "carrier_metered_apn_types_strings" to ("string_array" to "B"),
        "carrier_metered_roaming_apn_types_strings" to ("string_array" to "B"),
        "gps.longterm_psds_server_1" to ("string" to null),
        "imsvoice.voice_on_default_bearer_supported_bool" to ("bool" to null),
        "imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool" to ("bool" to null),
        "imsvoice.audio_evs_support_bool" to ("bool" to null),
        "smsToMmsTextThreshold" to ("int" to "X6"),
        "smsToMmsTextLengthThreshold" to ("int" to "X6"),
        "enableGroupMms" to ("bool" to "X6"),
        "enableMultipartSMS" to ("bool" to "X6"),
        "hide_ims_apn_bool" to ("bool" to null),
        "maxImageHeight" to ("int" to null),
        "maxImageWidth" to ("int" to null),
        "maxMessageSize" to ("int" to null),
        "rtt_supported_bool" to ("bool" to "A"),
        "rtt_supported_while_roaming_bool" to ("bool" to "A"),
        "carrier_default_wfc_ims_mode_int" to ("int" to null),
        "iwlan.ike_rekey_hard_timer_in_sec" to ("int" to null),
        "imsvoice.amrnb_payload_description_bundle" to ("bundle" to null),
        "ims.sip_timer_t1_millis_long" to ("long" to null),
        "carrier_signal_ratio_double" to ("double" to null),
        "always_show_emergency_alert_onoff_bool" to ("bool" to "A"),
        "dial_string_replace_string_array" to ("string_array" to "A"),
        "hide_enhanced_4g_lte_bool" to ("bool" to null),
        "editable_wfc_mode_bool" to ("bool" to null),
        "carrier_default_wfc_ims_roaming_mode_int" to ("int" to null),
        "carrier_ecc_list_string_array" to ("string_array" to null),
    )
    val FORKS = mapOf("imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool" to "ocd")

    /** Appendix A's Google rules, in file order. */
    val RULES: List<Rule> = listOf(
        Rule("test001_amarisoft", "001", "01", When("gid1", "ABCD2025")),
        Rule("test001_zz", "001", "01", null),
        Rule("21670IMSI=21670XX1XXX", "216", "70", When("imsi", "21670xx1")),
        Rule("vodafone_hu", "216", "70", null),
        Rule("vodafone_de", "262", "02", null),
        Rule("enreach_de", "262", "03", When("imsi", "26203482")),
        Rule("enreach_de", "262", "03", When("imsi", "26203483")),
        Rule("eplus_de", "262", "03", null),
        Rule("tchibo_de", "262", "07", When("imsi", "26207500")),
        Rule("o2prepaid_de", "262", "07", When("imsi", "2620749")),
        Rule("26207SPN=ALICE", "262", "07", When("spn", "ALICE")),
        Rule("o2_de", "262", "07", null),
        Rule("mts_ca", "302", "370", When("gid1", "2C")),
        Rule("fido_ca", "302", "370", When("gid1", "DD")),
        Rule("fido_ca", "302", "370", null),
        Rule("synthetic_0103", "999", "02", When("gid1", "0103")),
        Rule("synthetic_net", "999", "02", null),
    )

    fun n(mcc: String, mnc: String) = Network(mcc, mnc)

    /** Appendix A's profiles; the two fill-only IDs are computed by 2.6. */
    val PROFILES: List<Pair<String, Match>> by lazy {
        listOf(
            "open.26207.c07dcc040b58" to Match(listOf(n("262", "07"))),
            "open.26207.394d5585811d" to Match(listOf(n("262", "07"), n("262", "11")), carrierIds = listOf(1454)),
            "open.26207.69841ad3698a" to Match(listOf(n("262", "07")), spn = listOf("ALICE")),
            "open.26207.1c95803fdfb1" to Match(listOf(n("262", "07")), imsi = listOf("2620749")),
            "open.26207.78040a3afcc4" to Match(listOf(n("262", "07")), imsi = listOf("26207500")),
            "open.26207.2aa389aa6407" to Match(listOf(n("262", "07")), imsi = listOf("26207520")),
            "open.26207.6aa33dfeb206" to Match(listOf(n("262", "07")), gid1 = listOf("000002")),
            "open.26203.dbb2be7109a6" to Match(listOf(n("262", "03"))),
            "open.26203.ec645827a22d" to Match(listOf(n("262", "03")), gid1 = listOf("010301")),
            "open.26203.c20311365064" to Match(listOf(n("262", "03")), imsi = listOf("26203482")),
            "open.26202.8cebd7550c67" to Match(listOf(n("262", "02"))),
            "open.26202.5e6cc8d0e19b" to Match(listOf(n("262", "02")), gid1 = listOf("11")),
            "open.26202.db9b35c48658" to Match(listOf(n("262", "02")), iccid = listOf("893144")),
            "open.302370.9dc7c0c7b085" to Match(listOf(n("302", "370"))),
            "open.302370.f6d6383ed91a" to Match(listOf(n("302", "370")), gid1 = listOf("DD")),
            "open.302370.19c2b9f93626" to Match(listOf(n("302", "370")), gid1 = listOf("DD"), carrierIds = listOf(1962)),
            "open.302370.632d3386012f" to Match(listOf(n("302", "370")), gid1 = listOf("2C")),
            "open.302370.4944bb902cb1" to Match(listOf(n("302", "370"), n("302", "660")), gid1 = listOf("2C", "4D"), carrierIds = listOf(578)),
            "open.30237.b8efad512dbf" to Match(listOf(n("302", "37"))),
            "open.21670.1b3b9f7d0a6f" to Match(listOf(n("216", "70"))),
            "open.21670.3d9b6e4451e0" to Match(listOf(n("216", "70")), imsi = listOf("21670xx1")),
            "open.21670.a90e85c2329e" to Match(listOf(n("216", "70")), imsi = listOf("21670xx2"), carrierIds = listOf(1535)),
            ReferenceExporter.fillOnlyId(RULES[1]) to Match(listOf(n("001", "01"))),
            ReferenceExporter.fillOnlyId(RULES[0]) to Match(listOf(n("001", "01")), gid1 = listOf("ABCD2025")),
            "open.99901.000000000001" to Match(listOf(n("999", "01")), spn = listOf("STRASSE")),
            "open.99902.000000000001" to Match(listOf(n("999", "02"))),
            "open.99902.000000000002" to Match(listOf(n("999", "02")), gid1 = listOf("01")),
        )
    }

    /** Values of the worked example (5.5) and a few more for the group tests. */
    val VALUES: Map<String, Map<String, Any?>> = mapOf(
        "open.26207.c07dcc040b58" to linkedMapOf(
            "carrier_volte_available_bool" to true, "carrier_vt_available_bool" to true,
            "carrier_metered_apn_types_strings" to listOf("default", "dun", "supl"), "imssms.sms_over_ims_supported_bool" to true,
            "smsToMmsTextThreshold" to 5, "enableMultipartSMS" to false,
            "imsemergency.emergency_domain_preference_int_array" to listOf(9, 9, 9),
        ),
        "open.26207.78040a3afcc4" to linkedMapOf(
            "carrier_volte_available_bool" to true, "hide_ims_apn_bool" to true, "maxImageHeight" to 1944, "maxImageWidth" to 2592,
            "maxMessageSize" to 614400, "rtt_supported_bool" to true, "rtt_supported_while_roaming_bool" to true,
        ),
        "open.26202.8cebd7550c67" to linkedMapOf("carrier_volte_available_bool" to true, "carrier_metered_apn_types_strings" to listOf("mms")),
        "open.26202.5e6cc8d0e19b" to linkedMapOf("carrier_volte_available_bool" to false, "carrier_wfc_ims_available_bool" to false),
    )

    /** Each Google entry carries its own ike timer, so the fill a load picked is visible in the bundle. */
    fun laneEntries(): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        out["default"] = linkedMapOf("gps.longterm_psds_server_1" to "https://agnss.goog/pad/mtk/prod/1/LTEPO.DAT", "iwlan.ike_rekey_hard_timer_in_sec" to 1, "carrier_metered_apn_types_strings" to listOf("default", "mms", "dun"))
        out["no_sim"] = linkedMapOf("carrier_volte_available_bool" to false, "iwlan.ike_rekey_hard_timer_in_sec" to 2)
        var i = 100
        for (name in RULES.map { it.name }.distinct()) out[name] = linkedMapOf("iwlan.ike_rekey_hard_timer_in_sec" to i++)
        out["o2_de"] = linkedMapOf("carrier_volte_available_bool" to true, "imsemergency.emergency_domain_preference_int_array" to listOf(2, 1, 3), "iwlan.ike_rekey_hard_timer_in_sec" to 111)
        out["tchibo_de"] = linkedMapOf("carrier_volte_available_bool" to true, "imsemergency.emergency_domain_preference_int_array" to listOf(2, 1), "iwlan.ike_rekey_hard_timer_in_sec" to 108)
        out["vodafone_de"] = linkedMapOf("iwlan.ike_rekey_hard_timer_in_sec" to 104, "carrier_metered_apn_types_strings" to listOf("mms", "dun"))
        return out
    }

    // --- device records (2.10) ------------------------------------------------------------

    fun record(
        kind: String = "configured_device", key: String, value: Any?, fork: String? = if (kind == "measured") "ocd" else null, valueKind: String = "value",
        carrier: Map<String, Any?> = mapOf("scope" to "all"), label: String? = null, read: Map<String, Any?>? = null,
        target: Map<String, Any?> = linkedMapOf("type" to "config", "key" to key), validUntil: String? = null, lastMeasured: String = "2026-10-22",
        result: String? = null, model: String = "SM-G981B", line: String = "24.0",
    ): Map<String, Any?> {
        val c = when (carrier["scope"]) {
            "all" -> Carrier("all")
            "profile" -> Carrier("profile", profileId = carrier["profile_id"] as String)
            else -> Carrier("sim_key", mcc = carrier["mcc"] as String, mnc = carrier["mnc"] as String, gid1 = carrier["gid1"] as String?, spn = carrier["spn"] as String?)
        }
        val t = when (target["type"]) {
            "config" -> Target("config", key = target["key"] as String)
            "apn" -> Target("apn", rowKey = target["row_key"] as String)
            else -> Target("emergency_data", rowKey = target["row_key"] as String?)
        }
        val rk = Record.computeKey(kind, "x1s", model, "lineageos", line, "aosp_imsstack", fork, c, t)
        return linkedMapOf(
            "record_key" to rk, "kind" to kind,
            "device" to linkedMapOf("codename" to "x1s", "model" to model),
            "rom" to linkedMapOf("family" to "lineageos", "line" to line),
            "ims_stack" to linkedMapOf("id" to "aosp_imsstack", "fork" to fork, "version" to null),
            "carrier" to carrier, "target" to target, "value_kind" to valueKind, "value" to value,
            "claim" to if (kind == "measured") "measured" else "configured", "label" to label, "basis" to "test",
            "level" to null, "result" to result, "method" to null, "bearer" to null, "n" to null,
            "first_measured" to lastMeasured, "last_measured" to lastMeasured, "valid_until" to (validUntil ?: if (kind == "measured") "2027-01-20" else null),
            "data" to linkedMapOf("commit" to COMMIT, "data_digest" to DATA_DIGEST), "tester" to "owner",
            "differs_between_sims" to null, "second_agent_read" to read,
        )
    }

    /** The S20's five records (2.10); the group A one without its read unless [withRead]. */
    fun s20Records(withRead: Boolean = false): List<Map<String, Any?>> = listOf(
        record(key = "imsvoice.voice_on_default_bearer_supported_bool", value = true),
        record(key = "imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool", value = true, fork = "ocd"),
        record(key = "imsvoice.audio_evs_support_bool", value = false),
        record(key = "gps.longterm_psds_server_1", value = ""),
        record(
            key = "imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool", value = true, label = "derived from source, not tested",
            read = if (withRead) linkedMapOf("reader" to "second agent", "read_at" to "2026-10-25T12:00:00Z", "basis" to "the radio reports no QoS", "reference" to "observed/x") else null,
        ),
    )

    // --- APN rows ------------------------------------------------------------------------

    /** (profile, row line). The CNT rows are the public file's lines 7779 to 7781. */
    val ROWS: List<Pair<String, String>> = listOf(
        "open.26207.c07dcc040b58" to """  <apn apn="internet" carrier="o2 Internet" mcc="262" mnc="07" protocol="IPV4V6" type="default,supl" />""",
        "open.26207.c07dcc040b58" to """  <apn apn="ims" carrier="o2 IMS" mcc="262" mnc="07" protocol="IPV4V6" roaming_protocol="IPV4V6" type="ims" />""",
        "open.26207.c07dcc040b58" to """  <apn apn="sos" carrier="o2 SOS" mcc="262" mnc="07" protocol="IPV4V6" roaming_protocol="IPV4V6" type="emergency" />""",
        "open.26207.78040a3afcc4" to """  <apn apn="tchibo.example" carrier="Tchibo" mcc="262" mnc="07" mvno_match_data="26207500" mvno_type="imsi" type="default,supl" />""",
        "open.26207.2aa389aa6407" to """  <apn apn="pinternet.interkom.de" carrier="Lidl Mobile" mcc="262" mnc="07" mvno_match_data="26207520" mvno_type="imsi" type="default,mms,supl" />""",
        "open.26207.69841ad3698a" to """  <apn apn="alice.example" carrier="ALICE" mcc="262" mnc="07" mvno_match_data="ALICE" mvno_type="spn" type="default" />""",
        "open.26202.8cebd7550c67" to """  <apn apn="web.vodafone.de" carrier="Vodafone" mcc="262" mnc="02" type="default,supl" />""",
        "open.26202.8cebd7550c67" to """  <apn apn="ims" carrier="Vodafone IMS" mcc="262" mnc="02" type="ims" />""",
        "open.30237.b8efad512dbf" to """  <apn apn="internet.cnt.net.ec" carrier="CNT Internet" mcc="302" mnc="37" type="default,xcap" />""",
        "open.30237.b8efad512dbf" to """  <apn apn="ims" carrier="CNT VoLTE" mcc="302" mnc="37" protocol="IP" roaming_protocol="IP" type="ims" />""",
        "open.30237.b8efad512dbf" to """  <apn apn="emergency.volte.ims.cnt.net.ec" carrier="CNT Emergency" mcc="302" mnc="37" protocol="IP" roaming_protocol="IPV4V6" type="emergency" />""",
        "open.302370.9dc7c0c7b085" to """  <apn apn="ltemobile.apn" carrier="Fido" mcc="302" mnc="370" type="default,mms,supl" />""",
        "open.302370.f6d6383ed91a" to """  <apn apn="ltemobile.apn" carrier="Fido DD" mcc="302" mnc="370" mvno_match_data="DD" mvno_type="gid" type="default,mms,supl" />""",
        "open.302370.632d3386012f" to """  <apn apn="mtb.example" carrier="MTB" mcc="302" mnc="370" mvno_match_data="2C" mvno_type="gid" type="default" />""",
    )

    fun xml(rows: List<Pair<String, String>> = ROWS, digest: String = DATA_DIGEST, version: Int = 8): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!--\n    Android APN list from Open Carrier Data\n    data_digest: ").append(digest).append("\n-->\n")
        append("<apns version=\"").append(version).append("\">\n")
        for ((_, line) in rows) append(line).append('\n')
        append("</apns>\n")
    }

    fun apnIndex(xmlText: String, rows: List<Pair<String, String>> = ROWS, digest: String = DATA_DIGEST): String {
        val doc = ApnXml.parse(xmlText)
        val entries = doc.rows.mapIndexed { i, r -> linkedMapOf("ordinal" to i, "line" to r.line, "row_key" to r.rowKey, "profiles" to listOf(rows[i].first)) }
        return Json.write(linkedMapOf("schema_version" to 1, "based_on_data_digest" to digest, "apn_xml_sha256" to Files.sha256(xmlText.toByteArray()), "rows" to entries))
    }

    // --- the files -------------------------------------------------------------------------

    fun profilesJson(
        values: Map<String, Map<String, Any?>> = VALUES,
        profiles: List<Pair<String, Match>> = PROFILES,
        rules: List<Rule> = RULES,
        keys: Map<String, Pair<String, String?>> = KEYS,
        digest: String = DATA_DIGEST,
        rowsOf: (String) -> Int = { id -> ROWS.count { it.first == id } },
        sources: Map<String, Map<String, List<String>>> = SOURCES,
        alternatives: Map<String, Map<String, List<Map<String, Any?>>>> = emptyMap(),
    ): MutableMap<String, Any?> {
        val networks = LinkedHashMap<String, MutableMap<String, Any?>>()
        for ((id, match) in profiles.sortedWith { a, b -> compareBytes(a.first, b.first) }) {
            for (net in match.mccmnc) {
                val list = networks.getOrPut(net.key) { linkedMapOf("mcc" to net.mcc, "mnc" to net.mnc, "profiles" to ArrayList<Any?>()) }
                @Suppress("UNCHECKED_CAST")
                (list["profiles"] as MutableList<Any?>) += linkedMapOf(
                    "id" to id,
                    "kind" to if (id.startsWith("fill.")) "fill_only" else "ocd",
                    "name" to id,
                    "match" to match.toJson(),
                    "specificity" to match.specificity,
                    "fill" to ReferenceExporter.fillList(rules, match, net).map { linkedMapOf("entry" to it.entry, "when" to it.condition?.let { w -> linkedMapOf("kind" to w.kind, "value" to w.value) }) },
                    "values" to (values[id] ?: emptyMap<String, Any?>()),
                    "sources" to (sources[id]?.filterKeys { it in (values[id] ?: emptyMap<String, Any?>()) } ?: emptyMap<String, Any?>()),
                    "alternatives" to (alternatives[id] ?: emptyMap<String, Any?>()),
                    "apn_rows" to rowsOf(id),
                )
            }
        }
        return linkedMapOf(
            "schema_version" to 1,
            "based_on_data_digest" to digest,
            "keys" to keys.entries.associate { (k, v) ->
                k to linkedMapOf("type" to v.first, "group" to v.second.takeIf { it in setOf("A", "B", "X6") }, "origin" to listOf("ocd_schema"), "fork" to FORKS[k])
            },
            "groups" to linkedMapOf(
                "A" to linkedMapOf("pattern" to Rules.GROUP_A_PATTERN.pattern, "names" to listOf("call_forwarding_blocks_while_roaming_string_array", "ims_reasoninfo_mapping_string_array"), "keys" to keys.filter { it.value.second == "A" }.keys.toList()),
                "B" to linkedMapOf("keys" to listOf("carrier_metered_apn_types_strings", "carrier_metered_roaming_apn_types_strings"), "must_contain" to "default"),
                "X6" to linkedMapOf("keys" to listOf("smsToMmsTextThreshold", "smsToMmsTextLengthThreshold", "enableGroupMms", "enableMultipartSMS"), "networks" to Rules.X6_NETWORKS.toList()),
            ),
            "reserved" to linkedMapOf("default" to linkedMapOf("fill" to listOf(linkedMapOf("entry" to "default", "when" to null))), "no_sim" to linkedMapOf("fill" to listOf(linkedMapOf("entry" to "no_sim", "when" to null)))),
            "networks" to networks,
        )
    }

    fun laneJson(entries: Map<String, Map<String, Any?>> = laneEntries(), digest: String = DATA_DIGEST, state: String = "public_main"): MutableMap<String, Any?> = linkedMapOf(
        "schema_version" to 1,
        "based_on_data_digest" to digest,
        "family" to "google",
        "provenance" to linkedMapOf("lane" to "google_carriersettings", "device" to "kodiak", "build" to "CD1A.260714.001.A9", "carrier_list_version" to 80000000582L, "others_version" to 80000000032L, "snapshot" to "adevtool 92b73cfa", "state" to state),
        "rules" to RULES.map { r -> linkedMapOf("canonical_name" to r.name, "mcc" to r.mcc, "mnc" to r.mnc, "mvno" to r.mvno?.let { linkedMapOf("kind" to it.kind, "value" to it.value) }) },
        "entries" to entries.mapValues { linkedMapOf("values" to it.value) },
    )

    fun recordsJson(records: List<Map<String, Any?>> = s20Records(), digest: String = DATA_DIGEST): MutableMap<String, Any?> =
        linkedMapOf("schema_version" to 1, "based_on_data_digest" to digest, "expire_days" to 90, "records" to records)

    /** The source families behind the O2 profile's values (the group A read tests need them). */
    val SOURCES: Map<String, Map<String, List<String>>> = mapOf(
        "open.26207.c07dcc040b58" to mapOf(
            "imsemergency.emergency_domain_preference_int_array" to listOf("samsung_omc"),
            "carrier_volte_available_bool" to listOf("google", "samsung_omc"),
        ),
    )

    // --- group A reads (round 31, R31.7) -------------------------------------------------------

    fun read(
        key: String = "imsemergency.emergency_domain_preference_int_array", value: Any? = listOf(9, 9, 9), family: String = "samsung_omc",
        profile: String = "open.26207.c07dcc040b58", verdict: String = "proceed", fork: String? = null, stack: String = "aosp_imsstack", id: String = "gar:0000000000000001",
    ): Map<String, Any?> = linkedMapOf(
        "read_id" to id, "key" to key, "value" to value, "family" to family, "profile_id" to profile, "rom_family" to "lineageos",
        "ims_stack" to stack, "ims_stack_fork" to fork, "verdict" to verdict, "reader" to "a second agent", "read_at" to "2026-10-28T12:00:00Z",
        "reference" to "devices/records/reads/test.json",
    )

    fun finding(key: String, verdict: String = "proceed"): Map<String, Any?> = linkedMapOf(
        "key" to key, "finding" to "display_only", "verdict" to verdict, "reader" to "a second agent", "read_at" to "2026-10-28T12:00:00Z", "reference" to "devices/records/reads/f.json",
    )

    fun readsJson(reads: List<Map<String, Any?>> = listOf(read()), findings: List<Map<String, Any?>> = emptyList(), digest: String = DATA_DIGEST): MutableMap<String, Any?> =
        linkedMapOf("schema_version" to 1, "based_on_data_digest" to digest, "reads" to reads, "key_findings" to findings)

    /** Every file of a consistent dataset, by local name. */
    fun files(
        profiles: Map<String, Any?> = profilesJson(),
        lane: Map<String, Any?>? = laneJson(),
        records: Map<String, Any?> = recordsJson(),
        rows: List<Pair<String, String>> = ROWS,
        generatedAt: String = "2026-10-25T09:57:41Z",
        commit: String = COMMIT,
        role: String = "app",
        reads: Map<String, Any?>? = null,
    ): MutableMap<String, ByteArray> {
        val x = xml(rows)
        val out = linkedMapOf(
            "profiles.json" to Json.write(profiles).toByteArray(),
            "device-records.json" to Json.write(records).toByteArray(),
            "apns-conf.xml" to x.toByteArray(),
            "apn-index.json" to apnIndex(x, rows).toByteArray(),
        )
        if (lane != null) out["google-lane.json"] = Json.write(lane).toByteArray()
        if (reads != null) out["group-a-reads.json"] = Json.write(reads).toByteArray()
        return seal(out, generatedAt, commit, role)
    }

    /** Recomputes manifest.json and pin.json over the content files of [files]. */
    fun seal(
        files: MutableMap<String, ByteArray>,
        generatedAt: String = "2026-10-25T09:57:41Z",
        commit: String = COMMIT,
        role: String = "app",
        laneAsExtra: Boolean = false,
        manifestEdit: (MutableMap<String, Any?>) -> Unit = {},
    ): MutableMap<String, ByteArray> {
        val content = files.filterKeys { it != "manifest.json" && it != "pin.json" && !(laneAsExtra && it == "google-lane.json") }
        val entries = content.keys.mapNotNull { DatasetFile.byLocal(it) }.map { f ->
            val b = content.getValue(f.local)
            ManifestEntry(f.role, f.local, f.path, b.size.toLong(), Files.sha256(b))
        }.sortedWith { a, b -> compareBytes(a.name, b.name) }
        val manifest = linkedMapOf<String, Any?>(
            "schema_version" to 1, "format" to "ocd-dataset", "data_digest" to DATA_DIGEST, "generated_at" to generatedAt,
            "generator" to "test fixture", "apn_database_version" to 8,
            "google_lane" to if (entries.any { it.role == "google_lane" }) "included" else "absent",
            "files" to entries.map { linkedMapOf("role" to it.role, "name" to it.name, "path" to it.path, "bytes" to it.bytes, "sha256" to it.sha256) },
            "dataset_digest" to Manifest.digestOf(entries),
            "counts" to linkedMapOf("networks" to 0, "profiles" to 0, "fill_only_profiles" to 2, "google_entries" to 0, "apn_rows" to 0, "records" to 5),
        )
        manifestEdit(manifest)
        val manifestBytes = Json.write(manifest).toByteArray()
        val out = LinkedHashMap(files)
        out["manifest.json"] = manifestBytes
        val pinFiles = LinkedHashMap<String, Any?>()
        pinFiles[DatasetFile.MANIFEST.path] = linkedMapOf("blob" to Files.gitBlob(manifestBytes), "bytes" to manifestBytes.size)
        for (e in entries) pinFiles[e.path] = linkedMapOf("blob" to Files.gitBlob(content.getValue(e.name)), "bytes" to e.bytes)
        val pin = linkedMapOf<String, Any?>(
            "schema" to "ocd-dataset-pin/1", "role" to role, "repository" to "open-carrier-data/open-carrier-data", "commit" to commit,
            "commit_date" to "2026-10-25T09:58:23Z", "validate" to linkedMapOf("check_run_id" to 120900000000L, "conclusion" to "success"),
            "files" to pinFiles, "fetched_at" to "2026-10-25T10:20:02Z", "fetched_by" to "test",
        )
        if (laneAsExtra) {
            val lane = files.getValue("google-lane.json")
            pin["extra"] = linkedMapOf("google-lane.json" to linkedMapOf("sha256" to Files.sha256(lane), "bytes" to lane.size))
        }
        out["pin.json"] = Json.write(pin).toByteArray()
        return out
    }

    fun write(dir: File, files: Map<String, ByteArray>): File {
        Files.deleteTree(dir)
        dir.mkdirs()
        for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
        return dir
    }

    fun tempDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "ocd-test-${ProcessHandle.current().pid()}-$name").also { Files.deleteTree(it); it.mkdirs() }

    fun defaults(key: String): DefaultType = when (key) {
        "carrier_volte_available_bool", "carrier_vt_available_bool" -> DefaultType.Of(KeyType.BOOL)
        "carrier_new_android_key_int" -> DefaultType.Of(KeyType.INT)
        "carrier_null_default_string" -> DefaultType.Null
        else -> DefaultType.Absent
    }

    fun verifier(baked: Baked? = null, sims: List<SimId> = emptyList(), crashGuard: String? = null, platformApn: Int? = 8) =
        Verifier(baked, ::defaults, platformApn, crashGuard, sims)

    fun dataset(dir: File = write(tempDir("ds"), files()), role: String = "app"): Dataset = verifier().verify(dir, role)
}

/**
 * The exporter's side as the spec defines it, for the fixture only: fill lists (2.5.5) and
 * fill-only profile IDs (2.6). CarrierSettings never computes fills; it only picks one.
 */
object ReferenceExporter {
    fun fillOnlyId(rule: Rule): String {
        val text = "google-rule\n${rule.name}\n${rule.mcc}-${rule.mnc}\n${rule.mvno?.kind ?: "none"}\n${rule.mvno?.value ?: ""}"
        return "fill.${rule.mcc}${rule.mnc}.${Files.sha256(text).take(12)}"
    }

    private fun dim(match: Match, kind: String): List<String>? = when (kind) {
        "spn" -> match.spn
        "imsi" -> match.imsi
        "gid1" -> match.gid1
        "iccid" -> match.iccid
        else -> null
    }

    fun overlap(kind: String, w: String, v: String): Boolean = when (kind) {
        "spn" -> w.equals(v, ignoreCase = true)
        "gid1" -> Matcher.prefixIgnoreCase(v, w) || Matcher.prefixIgnoreCase(w, v)
        "iccid" -> w.startsWith(v) || v.startsWith(w)
        "imsi" -> w.zip(v).all { (a, b) -> a == 'x' || a == 'X' || b == 'x' || b == 'X' || a == b }
        else -> false
    }

    fun implied(kind: String, w: String, v: String): Boolean = when (kind) {
        "spn" -> w.equals(v, ignoreCase = true)
        "gid1" -> Matcher.prefixIgnoreCase(v, w)
        "iccid" -> w.startsWith(v)
        "imsi" -> w.length >= v.length && v.indices.all { i -> v[i] == 'x' || v[i] == 'X' || (w[i] != 'x' && w[i] != 'X' && w[i] == v[i]) }
        else -> false
    }

    fun fillList(rules: List<Rule>, match: Match, net: Network): List<FillItem> {
        val out = ArrayList<FillItem>()
        for (r in rules) {
            if (r.mcc != net.mcc || r.mnc != net.mnc) continue
            val mv = r.mvno
            if (mv == null) {
                out += FillItem(r.name, null)
                break
            }
            val values = dim(match, mv.kind).orEmpty()
            if (values.none { overlap(mv.kind, it, mv.value) }) continue
            if (values.all { implied(mv.kind, it, mv.value) }) {
                out += FillItem(r.name, null)
                break
            }
            out += FillItem(r.name, mv)
        }
        return out
    }
}
