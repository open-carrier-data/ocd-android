/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec appendix A, normative: the 27 matcher vectors, run twice, once on the matcher alone
 * with the appendix's profiles and once through the whole path (the fixture dataset verified,
 * its serving index written and read, and the bundle built), plus the APN coupling vectors of
 * 4.6. The appendix JSON below is copied from the spec verbatim.
 */
class VectorsTest {
    companion object {
        val APPENDIX = """
{
  "schema": "ocd-matcher-vectors/1",
  "google_rules": [
    {"canonical_name": "test001_amarisoft", "mcc": "001", "mnc": "01", "mvno": {"kind": "gid1", "value": "ABCD2025"}},
    {"canonical_name": "test001_zz", "mcc": "001", "mnc": "01", "mvno": null},
    {"canonical_name": "21670IMSI=21670XX1XXX", "mcc": "216", "mnc": "70", "mvno": {"kind": "imsi", "value": "21670xx1"}},
    {"canonical_name": "vodafone_hu", "mcc": "216", "mnc": "70", "mvno": null},
    {"canonical_name": "vodafone_de", "mcc": "262", "mnc": "02", "mvno": null},
    {"canonical_name": "enreach_de", "mcc": "262", "mnc": "03", "mvno": {"kind": "imsi", "value": "26203482"}},
    {"canonical_name": "enreach_de", "mcc": "262", "mnc": "03", "mvno": {"kind": "imsi", "value": "26203483"}},
    {"canonical_name": "eplus_de", "mcc": "262", "mnc": "03", "mvno": null},
    {"canonical_name": "tchibo_de", "mcc": "262", "mnc": "07", "mvno": {"kind": "imsi", "value": "26207500"}},
    {"canonical_name": "o2prepaid_de", "mcc": "262", "mnc": "07", "mvno": {"kind": "imsi", "value": "2620749"}},
    {"canonical_name": "26207SPN=ALICE", "mcc": "262", "mnc": "07", "mvno": {"kind": "spn", "value": "ALICE"}},
    {"canonical_name": "o2_de", "mcc": "262", "mnc": "07", "mvno": null},
    {"canonical_name": "mts_ca", "mcc": "302", "mnc": "370", "mvno": {"kind": "gid1", "value": "2C"}},
    {"canonical_name": "fido_ca", "mcc": "302", "mnc": "370", "mvno": {"kind": "gid1", "value": "DD"}},
    {"canonical_name": "fido_ca", "mcc": "302", "mnc": "370", "mvno": null},
    {"canonical_name": "synthetic_0103", "mcc": "999", "mnc": "02", "mvno": {"kind": "gid1", "value": "0103"}},
    {"canonical_name": "synthetic_net", "mcc": "999", "mnc": "02", "mvno": null}
  ],
  "profiles": [
    {"id": "open.26207.c07dcc040b58", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}]}},
    {"id": "open.26207.394d5585811d", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}, {"mcc": "262", "mnc": "11"}], "android_carrier_ids": [1454]}},
    {"id": "open.26207.69841ad3698a", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}], "spn": ["ALICE"]}},
    {"id": "open.26207.1c95803fdfb1", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}], "imsi_prefix_patterns": ["2620749"]}},
    {"id": "open.26207.78040a3afcc4", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}], "imsi_prefix_patterns": ["26207500"]}},
    {"id": "open.26207.2aa389aa6407", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}], "imsi_prefix_patterns": ["26207520"]}},
    {"id": "open.26207.6aa33dfeb206", "match": {"mccmnc": [{"mcc": "262", "mnc": "07"}], "gid1_prefixes": ["000002"]}},
    {"id": "open.26203.dbb2be7109a6", "match": {"mccmnc": [{"mcc": "262", "mnc": "03"}]}},
    {"id": "open.26203.ec645827a22d", "match": {"mccmnc": [{"mcc": "262", "mnc": "03"}], "gid1_prefixes": ["010301"]}},
    {"id": "open.26203.c20311365064", "match": {"mccmnc": [{"mcc": "262", "mnc": "03"}], "imsi_prefix_patterns": ["26203482"]}},
    {"id": "open.26202.8cebd7550c67", "match": {"mccmnc": [{"mcc": "262", "mnc": "02"}]}},
    {"id": "open.26202.5e6cc8d0e19b", "match": {"mccmnc": [{"mcc": "262", "mnc": "02"}], "gid1_prefixes": ["11"]}},
    {"id": "open.26202.db9b35c48658", "match": {"mccmnc": [{"mcc": "262", "mnc": "02"}], "iccid_prefixes": ["893144"]}},
    {"id": "open.302370.9dc7c0c7b085", "match": {"mccmnc": [{"mcc": "302", "mnc": "370"}]}},
    {"id": "open.302370.f6d6383ed91a", "match": {"mccmnc": [{"mcc": "302", "mnc": "370"}], "gid1_prefixes": ["DD"]}},
    {"id": "open.302370.19c2b9f93626", "match": {"mccmnc": [{"mcc": "302", "mnc": "370"}], "android_carrier_ids": [1962], "gid1_prefixes": ["DD"]}},
    {"id": "open.302370.632d3386012f", "match": {"mccmnc": [{"mcc": "302", "mnc": "370"}], "gid1_prefixes": ["2C"]}},
    {"id": "open.302370.4944bb902cb1", "match": {"mccmnc": [{"mcc": "302", "mnc": "370"}, {"mcc": "302", "mnc": "660"}], "android_carrier_ids": [578], "gid1_prefixes": ["2C", "4D"]}},
    {"id": "open.30237.b8efad512dbf", "match": {"mccmnc": [{"mcc": "302", "mnc": "37"}]}},
    {"id": "open.21670.1b3b9f7d0a6f", "match": {"mccmnc": [{"mcc": "216", "mnc": "70"}]}},
    {"id": "open.21670.3d9b6e4451e0", "match": {"mccmnc": [{"mcc": "216", "mnc": "70"}], "imsi_prefix_patterns": ["21670xx1"]}},
    {"id": "open.21670.a90e85c2329e", "match": {"mccmnc": [{"mcc": "216", "mnc": "70"}], "android_carrier_ids": [1535], "imsi_prefix_patterns": ["21670xx2"]}},
    {"id": "fill.00101.<computed:test001_zz>", "kind": "fill_only", "match": {"mccmnc": [{"mcc": "001", "mnc": "01"}]}},
    {"id": "fill.00101.<computed:test001_amarisoft>", "kind": "fill_only", "match": {"mccmnc": [{"mcc": "001", "mnc": "01"}], "gid1_prefixes": ["ABCD2025"]}},
    {"id": "open.99901.000000000001", "match": {"mccmnc": [{"mcc": "999", "mnc": "01"}], "spn": ["STRASSE"]}},
    {"id": "open.99902.000000000001", "match": {"mccmnc": [{"mcc": "999", "mnc": "02"}]}},
    {"id": "open.99902.000000000002", "match": {"mccmnc": [{"mcc": "999", "mnc": "02"}], "gid1_prefixes": ["01"]}}
  ],
  "vectors": [
    {"id": "M1", "sim": {"mcc": "262", "mnc": "07", "imsi": "262070000000001", "spn": "Example", "iccid": "89490700000000000001"}, "stack": ["open.26207.c07dcc040b58"], "serving": "open.26207.c07dcc040b58", "fill_pick": "o2_de"},
    {"id": "M2", "sim": {"mcc": "262", "mnc": "07", "imsi": "262075000000001"}, "stack": ["open.26207.c07dcc040b58", "open.26207.78040a3afcc4"], "serving": "open.26207.78040a3afcc4", "fill_pick": "tchibo_de"},
    {"id": "M3", "sim": {"mcc": "262", "mnc": "07", "imsi": "262074900000001"}, "stack": ["open.26207.c07dcc040b58", "open.26207.1c95803fdfb1"], "serving": "open.26207.1c95803fdfb1", "fill_pick": "o2prepaid_de"},
    {"id": "M4", "sim": {"mcc": "262", "mnc": "07", "imsi": "262075200000001"}, "stack": ["open.26207.c07dcc040b58", "open.26207.2aa389aa6407"], "serving": "open.26207.2aa389aa6407", "fill_pick": "o2_de"},
    {"id": "M5", "sim": {"mcc": "262", "mnc": "07", "imsi": "262070000000002", "carrier_id": 1454, "specific_carrier_id": 1454}, "stack": ["open.26207.c07dcc040b58", "open.26207.394d5585811d"], "serving": "open.26207.394d5585811d", "fill_pick": "o2_de"},
    {"id": "M6", "sim": {"mcc": "262", "mnc": "07", "imsi": "262070000000003", "spn": "alice"}, "stack": ["open.26207.c07dcc040b58", "open.26207.69841ad3698a"], "serving": "open.26207.69841ad3698a", "fill_pick": "26207SPN=ALICE"},
    {"id": "M7", "sim": {"mcc": "262", "mnc": "03", "imsi": "262030000000001", "gid1": "0103010000FFFFFF"}, "stack": ["open.26203.dbb2be7109a6", "open.26203.ec645827a22d"], "serving": "open.26203.ec645827a22d", "fill_pick": "eplus_de"},
    {"id": "M8", "sim": {"mcc": "262", "mnc": "03", "imsi": "262034820000001"}, "stack": ["open.26203.dbb2be7109a6", "open.26203.c20311365064"], "serving": "open.26203.c20311365064", "fill_pick": "enreach_de"},
    {"id": "M9", "sim": {"mcc": "262", "mnc": "02", "imsi": "262020000000002", "spn": "Lidl Connect", "iccid": "89490200000000000002"}, "stack": ["open.26202.8cebd7550c67"], "serving": "open.26202.8cebd7550c67", "fill_pick": "vodafone_de"},
    {"id": "M10", "sim": {"mcc": "262", "mnc": "02", "imsi": "262020000000003", "iccid": "89314400000000000009"}, "stack": ["open.26202.8cebd7550c67", "open.26202.db9b35c48658"], "serving": "open.26202.db9b35c48658", "fill_pick": "vodafone_de"},
    {"id": "M11", "sim": {"mcc": "262", "mnc": "02", "imsi": "262020000000004", "gid1": "11AB"}, "stack": ["open.26202.8cebd7550c67", "open.26202.5e6cc8d0e19b"], "serving": "open.26202.5e6cc8d0e19b", "fill_pick": "vodafone_de"},
    {"id": "M12", "sim": {"mcc": "302", "mnc": "370", "imsi": "302370000000001"}, "stack": ["open.302370.9dc7c0c7b085"], "serving": "open.302370.9dc7c0c7b085", "fill_pick": "fido_ca", "must_not_contain": ["open.30237.b8efad512dbf"]},
    {"id": "M13", "sim": {"mcc": "302", "mnc": "370", "imsi": "302370000000002", "gid1": "DDFF", "carrier_id": 1962, "specific_carrier_id": 1962}, "stack": ["open.302370.9dc7c0c7b085", "open.302370.f6d6383ed91a", "open.302370.19c2b9f93626"], "serving": "open.302370.19c2b9f93626", "fill_pick": "fido_ca"},
    {"id": "M14", "sim": {"mcc": "302", "mnc": "370", "imsi": "302370000000003", "gid1": "2c00", "carrier_id": 578, "specific_carrier_id": 578}, "stack": ["open.302370.9dc7c0c7b085", "open.302370.632d3386012f", "open.302370.4944bb902cb1"], "serving": "open.302370.4944bb902cb1", "fill": [{"entry": "mts_ca", "when": {"kind": "gid1", "value": "2C"}}, {"entry": "fido_ca", "when": null}], "fill_pick": "mts_ca"},
    {"id": "M15", "sim": {"mcc": "302", "mnc": "37", "imsi": "30237000000001"}, "stack": ["open.30237.b8efad512dbf"], "serving": "open.30237.b8efad512dbf", "fill": [], "fill_pick": null},
    {"id": "M16", "sim": {"mcc": "216", "mnc": "70", "imsi": "216709910000000"}, "stack": ["open.21670.1b3b9f7d0a6f", "open.21670.3d9b6e4451e0"], "serving": "open.21670.3d9b6e4451e0", "fill_pick": "21670IMSI=21670XX1XXX"},
    {"id": "M17", "sim": {"mcc": "216", "mnc": "70", "imsi": "216709920000000"}, "stack": ["open.21670.1b3b9f7d0a6f"], "serving": "open.21670.1b3b9f7d0a6f", "fill_pick": "vodafone_hu"},
    {"id": "M18", "sim": {"mcc": "216", "mnc": "70", "imsi": "216709920000001", "carrier_id": 1535, "specific_carrier_id": 1535}, "stack": ["open.21670.1b3b9f7d0a6f", "open.21670.a90e85c2329e"], "serving": "open.21670.a90e85c2329e", "fill_pick": "vodafone_hu"},
    {"id": "M19", "sim": {"mcc": "001", "mnc": "01", "imsi": "001010000000001", "gid1": "ABCD2025FF"}, "stack": ["fill.00101.<computed:test001_zz>", "fill.00101.<computed:test001_amarisoft>"], "serving": "fill.00101.<computed:test001_amarisoft>", "fill_pick": "test001_amarisoft", "note": "stack order is by computed id; both have different specificity, so the order holds whatever the hashes"},
    {"id": "M20", "sim": {"mcc": "001", "mnc": "01", "imsi": "001010000000002"}, "stack": ["fill.00101.<computed:test001_zz>"], "serving": "fill.00101.<computed:test001_zz>", "fill_pick": "test001_zz"},
    {"id": "M21", "sim": {"mcc": "999", "mnc": "99", "imsi": "999990000000001"}, "stack": [], "serving": "default", "fill_pick": null},
    {"id": "M22", "sim": {"mcc": "262", "mnc": "007", "imsi": "262007000000001"}, "stack": [], "serving": "default", "fill_pick": null},
    {"id": "M23", "sim": {"mcc": "999", "mnc": "01", "imsi": "999010000000001", "spn": "Straße"}, "stack": [], "serving": "default", "fill_pick": null},
    {"id": "M24", "sim": {"mcc": "262", "mnc": "07", "imsi": "2620750"}, "stack": ["open.26207.c07dcc040b58"], "serving": "open.26207.c07dcc040b58", "fill_pick": "o2_de"},
    {"id": "M25", "sim": {"mcc": "999", "mnc": "02", "imsi": "999020000000001", "gid1": "0103AA"}, "stack": ["open.99902.000000000001", "open.99902.000000000002"], "serving": "open.99902.000000000002", "fill": [{"entry": "synthetic_0103", "when": {"kind": "gid1", "value": "0103"}}, {"entry": "synthetic_net", "when": null}], "fill_pick": "synthetic_0103"},
    {"id": "M26", "sim": {"mcc": "999", "mnc": "02", "imsi": "999020000000002", "gid1": "0199"}, "stack": ["open.99902.000000000001", "open.99902.000000000002"], "serving": "open.99902.000000000002", "fill_pick": "synthetic_net"},
    {"id": "M27", "sim": {"mcc": "302", "mnc": "370", "imsi": "302370000000004", "gid1": "4D", "carrier_id": 578, "specific_carrier_id": 578}, "stack": ["open.302370.9dc7c0c7b085", "open.302370.4944bb902cb1"], "serving": "open.302370.4944bb902cb1", "fill_pick": "fido_ca"}
  ]
}
"""

        @Suppress("UNCHECKED_CAST")
        val doc: Map<String, Any?> by lazy { Json.parse(APPENDIX.trim()) as Map<String, Any?> }

        private val computed: Map<String, String> by lazy {
            mapOf(
                "fill.00101.<computed:test001_zz>" to ReferenceExporter.fillOnlyId(Fixture.RULES[1]),
                "fill.00101.<computed:test001_amarisoft>" to ReferenceExporter.fillOnlyId(Fixture.RULES[0]),
            )
        }

        fun id(raw: String): String = computed[raw] ?: raw

        fun int(v: Any?): Int = (v as Json.Num).longOrNull()!!.toInt()

        fun simOf(v: Map<*, *>): SimId {
            val s = v["sim"] as Map<*, *>
            return SimId(
                s["mcc"] as String, s["mnc"] as String, s["spn"] as String?, s["imsi"] as String?, s["gid1"] as String?, null,
                s["iccid"] as String?, s["carrier_id"]?.let(::int) ?: -1, s["specific_carrier_id"]?.let(::int) ?: -1,
            )
        }

        /** The appendix's own profiles and rules (not the fixture's), as the matcher sees them. */
        val appendixProfiles: List<Pair<String, Match>> by lazy {
            (doc["profiles"] as List<*>).map { p ->
                val m = p as Map<*, *>
                id(m["id"] as String) to Match.parse(m["match"], m["id"] as String)
            }
        }

        val appendixRules: List<Rule> by lazy {
            (doc["google_rules"] as List<*>).map { r ->
                val m = r as Map<*, *>
                val mv = m["mvno"] as Map<*, *>?
                Rule(m["canonical_name"] as String, m["mcc"] as String, m["mnc"] as String, mv?.let { When(it["kind"] as String, it["value"] as String) })
            }
        }

        val serving: ServingData by lazy {
            val ds = Fixture.dataset()
            val dir = Fixture.tempDir("vectors-index")
            ServingIndex.write(ds, dir, "build", Fixture.CELL, "cell")
            ServingIndex.read(dir)
        }
    }

    private fun vectorOf(name: String): Map<*, *> = (doc["vectors"] as List<*>).map { it as Map<*, *> }.single { it["id"] == name }

    private fun vector(name: String) {
        val v = vectorOf(name)
        val sim = simOf(v)
        val wantStack = (v["stack"] as List<*>).map { id(it as String) }
        val wantServing = id(v["serving"] as String)
        val wantFill = v["fill_pick"] as String?

        // 1. The matcher alone, on the appendix's profiles and rules.
        val stack = Matcher.stack(appendixProfiles, sim, { it.second }, { it.first })
        assertEquals("$name stack", wantStack, stack.map { it.first })
        assertEquals("$name serving", wantServing, stack.lastOrNull()?.first ?: "default")
        val top = stack.lastOrNull()
        val fill = top?.let { ReferenceExporter.fillList(appendixRules, it.second, Network(sim.mcc, sim.mnc)) }.orEmpty()
        (v["fill"] as List<*>?)?.let { want ->
            val expected = want.map { f ->
                val m = f as Map<*, *>
                val w = m["when"] as Map<*, *>?
                FillItem(m["entry"] as String, w?.let { When(it["kind"] as String, it["value"] as String) })
            }
            assertEquals("$name fill list", expected, fill)
        }
        val pick = Matcher.pickFill(fill, sim) { true }
        assertEquals("$name fill pick", wantFill, pick.item?.entry)
        (v["must_not_contain"] as List<*>?)?.forEach { bad -> assertFalse("$name must not contain $bad", stack.any { it.first == bad }) }

        // 2. The whole path: verified dataset, serving index, bundle.
        val built = BundleBuilder.build(BuildInput(sim, serving, emptyList(), emptySet(), { null }, "2026-10-25", Fixture.CELL))
        assertEquals("$name stack (dataset)", wantStack, built.stack.map { it.id })
        assertEquals("$name serving (dataset)", wantServing, built.serving)
        assertEquals("$name fill (dataset)", wantFill, built.fill?.entry)
        assertEquals("$name version", "$wantServing+ocd${Fixture.COMMIT.take(7)}", built.version)
        // Each Google entry carries its own ike timer: the bundle shows which fill served (no OCD profile sets the key).
        val timer = built.values["iwlan.ike_rekey_hard_timer_in_sec"]
        if (wantFill != null) {
            assertEquals("$name fill value", Fixture.laneEntries().getValue(wantFill)["iwlan.ike_rekey_hard_timer_in_sec"], timer)
            assertEquals("$name fill source", "google_fill:$wantFill", built.sources["iwlan.ike_rekey_hard_timer_in_sec"])
        } else {
            assertEquals("$name: steps 1, 2 and 5 only", 1, timer)
            assertEquals("$name: steps 1, 2 and 5 only", "default", built.sources["iwlan.ike_rekey_hard_timer_in_sec"])
        }
    }

    @Test fun vectorM1() = vector("M1")
    @Test fun vectorM2() = vector("M2")
    @Test fun vectorM3() = vector("M3")
    @Test fun vectorM4() = vector("M4")
    @Test fun vectorM5() = vector("M5")
    @Test fun vectorM6() = vector("M6")
    @Test fun vectorM7() = vector("M7")
    @Test fun vectorM8() = vector("M8")
    @Test fun vectorM9() = vector("M9")
    @Test fun vectorM10() = vector("M10")
    @Test fun vectorM11() = vector("M11")
    @Test fun vectorM12() = vector("M12")
    @Test fun vectorM13() = vector("M13")
    @Test fun vectorM14() = vector("M14")
    @Test fun vectorM15() = vector("M15")
    @Test fun vectorM16() = vector("M16")
    @Test fun vectorM17() = vector("M17")
    @Test fun vectorM18() = vector("M18")
    @Test fun vectorM19() = vector("M19")
    @Test fun vectorM20() = vector("M20")
    @Test fun vectorM21() = vector("M21")
    @Test fun vectorM22() = vector("M22")
    @Test fun vectorM23() = vector("M23")
    @Test fun vectorM24() = vector("M24")
    @Test fun vectorM25() = vector("M25")
    @Test fun vectorM26() = vector("M26")
    @Test fun vectorM27() = vector("M27")

    @Test
    fun fillOnlyIdsFollowTheSpecRule() {
        assertTrue(ReferenceExporter.fillOnlyId(Fixture.RULES[1]).matches(Regex("^fill\\.00101\\.[0-9a-f]{12}$")))
        assertEquals(id("fill.00101.<computed:test001_zz>"), Fixture.PROFILES.first { it.first.startsWith("fill.") && it.second.gid1 == null }.first)
    }

    @Test
    fun javaEqualsIgnoreCaseNotCasefold() {
        assertFalse(Matcher.eqIgnoreCase("Straße", "STRASSE"))
        assertTrue(Matcher.eqIgnoreCase("alice", "ALICE"))
    }

    // --- 4.6 APN selection and coupling ---------------------------------------------------

    private val apnDataset by lazy { Fixture.dataset() }

    private fun coupling(sim: SimId): Triple<List<String>, String?, String> {
        val built = BundleBuilder.build(BuildInput(sim, serving, emptyList(), emptySet(), { null }, "2026-10-25", Fixture.CELL))
        val rows = ApnSelect.select(apnDataset.doc.rows, sim)
        val apnProfile = ApnSelect.apnProfile(built.stack.map { it.id }) { id -> built.stack.first { it.id == id }.apnRows }
        val c = ApnSelect.coupling(rows, apnProfile) { apnDataset.rowProfiles[it.rowKey].orEmpty() }
        return Triple(rows.map { it.attrs.getValue("apn") }, apnProfile, c)
    }

    private fun sim(name: String) = simOf(vectorOf(name))

    @Test fun apnM1() = assertEquals(Triple(listOf("internet", "ims", "sos"), "open.26207.c07dcc040b58", "same"), coupling(sim("M1")))

    @Test fun apnM2() = assertEquals(Triple(listOf("tchibo.example"), "open.26207.78040a3afcc4", "same"), coupling(sim("M2")))

    @Test fun apnM4() = assertEquals(Triple(listOf("pinternet.interkom.de"), "open.26207.2aa389aa6407", "same"), coupling(sim("M4")))

    @Test fun apnM5() = assertEquals(Triple(listOf("internet", "ims", "sos"), "open.26207.c07dcc040b58", "same"), coupling(sim("M5")))

    @Test fun apnM12() {
        val (rows, profile, c) = coupling(sim("M12"))
        assertEquals(listOf("ltemobile.apn"), rows)
        assertEquals("open.302370.9dc7c0c7b085", profile)
        assertEquals("same", c)
        assertFalse("no 302-37 CNT row for a 302-370 SIM", rows.any { it.contains("cnt") })
    }

    @Test fun apnM14() = assertEquals(Triple(listOf("mtb.example"), "open.302370.632d3386012f", "same"), coupling(sim("M14")))

    @Test fun apnM15() = assertEquals(Triple(listOf("internet.cnt.net.ec", "ims", "emergency.volte.ims.cnt.net.ec"), "open.30237.b8efad512dbf", "same"), coupling(sim("M15")))

    @Test fun apnA1TelephonyProviderUnitesTchiboAndAlice() {
        val a1 = SimId("262", "07", spn = "ALICE", imsi = "262075000000009")
        val (rows, profile, c) = coupling(a1)
        assertEquals(listOf("tchibo.example", "alice.example"), rows)
        assertEquals("open.26207.78040a3afcc4", profile)
        assertEquals("mismatch", c)
    }
}
