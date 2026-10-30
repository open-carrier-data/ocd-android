/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Round 32's APN candidate trials (D8 and D7 rule 4, the restore candidates of rounds 33, 34 and
 * 36): the candidate's strict reading and composition ([ApnCandidateSet]), and `apn_candidate_set`
 * and `apn_candidate_restore` in the engine against the stand-in platform: the trial and its
 * restore with the five checks, the emergency rows untouched, the restore at boot, at its time and
 * on a SIM change, the call window, the tester, the caps' backstop and the protocol staying 3.
 * The candidates are built as the OCD app 0.4.2 sends them (ApnCandidateProtocol.encode).
 */
class CandidateTest {
    companion object {
        val O2 = EngineTest.O2
        val ROWS = EngineTest.ROWS
        const val BAKED = EngineTest.BAKED
        const val APP = EngineTest.APP

        /** One network's rows with a D8 fill-in (the credential row TelephonyProvider stores with the bare one), for the pure tests. */
        val PURE: List<Pair<String, String>> = listOf(
            "p" to """  <apn apn="web.vodafone.de" carrier="Vodafone" mcc="262" mnc="02" type="default,supl" />""",
            "p" to """  <apn apn="internet" carrier="o2 old" mcc="262" mnc="07" password="secret" protocol="IPV4V6" type="default,supl" user="o2" />""",
            "p" to """  <apn apn="internet" carrier="o2 Internet" mcc="262" mnc="07" protocol="IPV4V6" type="default,supl" />""",
            "p" to """  <apn apn="mms" carrier="o2 MMS" mcc="262" mmsc="http://mms.o2.example" mnc="07" type="mms" />""",
            "p" to """  <apn apn="ims" carrier="o2 IMS" mcc="262" mnc="07" protocol="IPV4V6" roaming_protocol="IPV4V6" type="ims" />""",
            "p" to """  <apn apn="sos" carrier="o2 SOS" mcc="262" mnc="07" protocol="IPV4V6" roaming_protocol="IPV4V6" type="emergency" />""",
            "p" to """  <apn apn="tchibo.example" carrier="Tchibo" mcc="262" mnc="07" mvno_match_data="26207500" mvno_type="imsi" type="default,supl" />""",
            "p" to """  <apn apn="filler" carrier="F" mcc="999" mnc="50" type="default" />""",
        )
        val TCHIBO = SimId("262", "07", spn = "Tchibo", imsi = "262075000000003")

        fun full(rows: List<Pair<String, String>> = PURE): ApnLogic.Composed {
            val bytes = Fixture.xml(rows).toByteArray()
            return ApnLogic.compose(bytes, ApnXml.parse(bytes), emptySet())
        }

        fun row(apn: String, carrier: String? = null, rows: List<Pair<String, String>> = PURE, mnc: String = "07"): ApnXml.Row =
            ApnXml.parse(Fixture.xml(rows)).rows.first { it.attrs["apn"] == apn && it.attrs["mnc"] == mnc && (carrier == null || it.attrs["carrier"] == carrier) }

        fun cand(vararg ops: Map<String, Any?>, id: String = "c:0123456789abcdef", role: String = "INTERNET"): String =
            Json.write(linkedMapOf("id" to id, "rule" to "round 32 D8 rule 4", "kind" to "without_fill", "role" to role, "plain" to "a test candidate", "ops" to ops.toList()))

        fun omit(key: String) = linkedMapOf<String, Any?>("op" to "omit", "row_key" to key)
        fun setTypes(key: String, types: String) = linkedMapOf<String, Any?>("op" to "set_types", "row_key" to key, "types" to types)
        fun unset(key: String, vararg fields: String) = linkedMapOf<String, Any?>("op" to "unset", "row_key" to key, "fields" to fields.toList())
        fun insert(attrs: Map<String, String>, before: String? = null) = linkedMapOf<String, Any?>("op" to "insert", "attributes" to attrs.toSortedMap()).apply { if (before != null) put("before", before) }
        fun order(vararg keys: String) = linkedMapOf<String, Any?>("op" to "order", "row_keys" to keys.toList())

        fun compose(json: String, sim: SimId = O2, others: List<SimId> = emptyList(), rows: List<Pair<String, String>> = PURE) =
            ApnCandidateSet.compose(full(rows), sim, others, ApnCandidateSet.parse(json))

        fun refused(code: String, block: () -> Unit): Refused {
            try {
                block()
                fail("expected $code")
            } catch (r: Refused) {
                assertEquals(r.detail, code, r.code)
                return r
            }
            throw AssertionError()
        }
    }

    // --- the candidate and its set (pure) -------------------------------------------------------

    @Test
    fun d8TheSetWithoutTheFillingRow() {
        val filled = row("internet", "o2 old")
        val r = compose(cand(omit(filled.rowKey)))
        assertEquals(listOf(filled.rowKey), r.omitted)
        assertEquals(PURE.size - 1, r.composed.rows)
        assertFalse(r.selection.contains(filled.rowKey))
        // Every other line stays byte for byte.
        val before = Fixture.xml(PURE).split('\n').filter { !it.contains("o2 old") }
        assertEquals(before, String(r.composed.bytes).split('\n'))
        // Or the fill-in's fields taken off the row that also adds a type.
        val stripped = compose(cand(unset(filled.rowKey, "user", "password")))
        val back = stripped.composed.doc.rows.first { it.attrs["carrier"] == "o2 old" }
        assertNull(back.attrs["user"])
        assertNull(back.attrs["password"])
        assertEquals(listOf(back.rowKey), stripped.changed)
        assertEquals("the row stays in its place", filled.ordinal, back.ordinal)
    }

    @Test
    fun theRestoreCandidates() {
        // Round 33/34/36: a type put back on the retyped row.
        val internet = row("internet", "o2 Internet")
        val typed = compose(cand(setTypes(internet.rowKey, "default,supl,mms"), role = "MMS"))
        assertEquals("default,supl,mms", typed.composed.doc.rows.first { it.attrs["carrier"] == "o2 Internet" }.attrs["type"])
        // A left-out row restored right before the row it shares the stored row with, so that row's values still win.
        val leftOut = mapOf("apn" to "internet", "carrier" to "o2 restored", "mcc" to "262", "mnc" to "07", "password" to "x", "protocol" to "IPV4V6", "type" to "default,supl", "user" to "y")
        val restored = compose(cand(insert(leftOut, before = internet.rowKey)))
        val carriers = restored.composed.doc.rows.map { it.attrs["carrier"] }
        assertEquals(carriers.indexOf("o2 Internet") - 1, carriers.indexOf("o2 restored"))
        assertEquals(listOf(ApnXml.rowKey(leftOut)), restored.added)
        // Without an anchor: after the SIM's last row.
        val xcap = mapOf("apn" to "xcap.o2", "carrier" to "o2 XCAP", "mcc" to "262", "mnc" to "07", "type" to "xcap")
        val appended = compose(cand(insert(xcap), role = "XCAP")).composed.doc.rows.map { it.attrs["carrier"] }
        assertEquals(appended.indexOf("Tchibo") - 1, appended.indexOf("o2 XCAP"))
        // Round 34's earlier order.
        val mms = row("mms")
        val ims = row("ims")
        val ordered = compose(cand(order(ims.rowKey, mms.rowKey)))
        assertEquals(listOf("o2 old", "o2 Internet", "o2 IMS", "o2 MMS", "o2 SOS"), ordered.composed.doc.rows.filter { it.attrs["mnc"] == "07" && it.attrs["mvno_type"] == null }.map { it.attrs["carrier"] })
    }

    @Test
    fun d7TheLeftOutScopeKeepsTheSimsEmergencyRows() {
        val sos = row("sos")
        val sel = mapOf("mvno_type" to "spn", "mvno_match_data" to "Example")
        val rows = listOf(
            mapOf("apn" to "join", "carrier" to "Example", "mcc" to "262", "mnc" to "07", "type" to "default,supl") + sel,
            mapOf("apn" to "ims", "carrier" to "Example IMS", "mcc" to "262", "mnc" to "07", "type" to "ims") + sel,
        )
        val copy = sos.attrs + sel
        val r = compose(cand(*(rows + copy).map { insert(it) }.toTypedArray()))
        assertEquals("the SIM selects the restored scope", (rows + copy).map { ApnXml.rowKey(it) }.toSet(), r.selection.toSet())
        // Without the copy the SIM would lose its emergency row: refused.
        val e = refused("candidate_invalid") { compose(cand(*rows.map { insert(it) }.toTypedArray())) }
        assertTrue(e.detail, e.detail.contains("emergency rows would change"))
        // A different emergency row under the selector is no copy.
        refused("candidate_invalid") { compose(cand(*(rows + (copy + ("apn" to "sos2"))).map { insert(it) }.toTypedArray())) }
    }

    @Test
    fun d7ThePlainScopeUnderTheSimsSelector() {
        val tchibo = row("tchibo.example")
        val sel = mapOf("mvno_type" to "imsi", "mvno_match_data" to "26207500")
        val plain = listOf(row("internet", "o2 Internet"), row("ims")).map { it.attrs + sel }
        val r = compose(cand(omit(tchibo.rowKey), *plain.map { insert(it) }.toTypedArray()), sim = TCHIBO)
        assertEquals(plain.map { ApnXml.rowKey(it) }.toSet(), r.selection.toSet())
        assertEquals(listOf(tchibo.rowKey), r.omitted)
    }

    @Test
    fun d7ThePlainScopeMayPutBackARowItLeavesOut() {
        // The scope's row is the plain row under the selector already: left out and put back (the app's plain-scope candidate).
        val sel = mapOf("mvno_type" to "imsi", "mvno_match_data" to "26207500")
        val copy = row("internet", "o2 Internet").attrs + sel
        val rows = PURE + ("p" to ApnCandidateSet.line("  ", copy, false)) + ("p" to """  <apn apn="ims" carrier="Tchibo IMS" mcc="262" mnc="07" mvno_match_data="26207500" mvno_type="imsi" type="ims" />""")
        val scope = ApnXml.parse(Fixture.xml(rows)).rows.filter { it.attrs["mvno_type"] != null }
        val plain = listOf(row("internet", "o2 Internet"), row("ims")).map { it.attrs + sel }
        val r = compose(cand(*(scope.map { omit(it.rowKey) } + plain.map { insert(it) }).toTypedArray()), sim = TCHIBO, rows = rows)
        assertEquals(plain.map { ApnXml.rowKey(it) }.toSet(), r.selection.toSet())
        // Inserted without leaving it out: a duplicate.
        refused("candidate_invalid") { compose(cand(insert(copy)), sim = TCHIBO, rows = rows) }
    }

    @Test
    fun aSimWithoutAnInternetRowMayStillTryAnMmsRow() {
        val rows = listOf("p" to """  <apn apn="mms" carrier="NCC" mcc="250" mmsc="http://10.0.3.50" mnc="03" type="mms" />""")
        val left = mapOf("apn" to "mms2", "carrier" to "NCC 2", "mcc" to "250", "mnc" to "03", "type" to "mms")
        val r = compose(cand(insert(left), role = "MMS"), sim = SimId("250", "03", imsi = "250030000000007"), rows = rows)
        assertEquals(2, r.selection.size)
    }

    @Test
    fun aCandidateNeverTouchesAnEmergencyRow() {
        val sos = row("sos")
        assertTrue(refused("candidate_invalid") { compose(cand(omit(sos.rowKey))) }.detail.contains("serves emergency"))
        refused("candidate_invalid") { compose(cand(unset(sos.rowKey, "user"))) }
        refused("candidate_invalid") { compose(cand(order(sos.rowKey, row("ims").rowKey))) }
        // set_types may never write emergency, on any row.
        refused("candidate_invalid") { ApnCandidateSet.parse(cand(setTypes(row("ims").rowKey, "ims,emergency"))) }
        // A row TelephonyProvider would store as one row with the emergency row (equal unique fields).
        val joins = sos.attrs + ("carrier" to "not sos") + ("type" to "default")
        assertTrue(refused("candidate_invalid") { compose(cand(insert(joins))) }.detail.contains("stored as one row with an emergency row"))
        // No row without a type (it serves every type, emergency included), no wildcard.
        val bare = mapOf("apn" to "x", "carrier" to "x", "mcc" to "262", "mnc" to "07", "type" to "")
        refused("candidate_invalid") { compose(cand(insert(bare))) }
        refused("candidate_invalid") { compose(cand(insert(bare + ("type" to "*")))) }
        // An emergency row of its own is no copy.
        refused("candidate_invalid") { compose(cand(insert(bare + ("type" to "emergency") + ("mvno_type" to "spn") + ("mvno_match_data" to "Example")))) }
    }

    @Test
    fun onlyTheSimsOwnRowsChange() {
        assertTrue(refused("candidate_invalid") { compose(cand(omit(row("web.vodafone.de", mnc = "02").rowKey))) }.detail.contains("not one of this SIM's rows"))
        refused("candidate_invalid") { compose(cand(omit(row("tchibo.example").rowKey))) }
        val vf = mapOf("apn" to "web", "carrier" to "x", "mcc" to "262", "mnc" to "02", "type" to "default")
        assertTrue(refused("candidate_invalid") { compose(cand(insert(vf))) }.detail.contains("not for this SIM's network"))
        // An MVNO row this SIM does not match.
        val other = mapOf("apn" to "web", "carrier" to "x", "mcc" to "262", "mnc" to "07", "type" to "default", "mvno_type" to "gid", "mvno_match_data" to "AB")
        refused("candidate_invalid") { compose(cand(insert(other))) }
        // A second SIM in the phone on the same network: its rows would change.
        val second = SimId("262", "07", spn = "Other", imsi = "262070000000002")
        val internet = row("internet", "o2 Internet")
        assertTrue(refused("candidate_invalid") { compose(cand(setTypes(internet.rowKey, "default,supl,mms")), others = listOf(second)) }.detail.contains("another SIM"))
        // A second SIM elsewhere is untouched: fine.
        compose(cand(setTypes(internet.rowKey, "default,supl,mms")), others = listOf(SimId("262", "02", imsi = "262020000000004")))
    }

    @Test
    fun theSimKeepsItsInternetAndImsRows() {
        refused("sim_loses_internet") { compose(cand(omit(row("internet", "o2 old").rowKey), omit(row("internet", "o2 Internet").rowKey))) }
        refused("sim_loses_ims") { compose(cand(omit(row("ims").rowKey))) }
    }

    @Test
    fun candidateRefusals() {
        val internet = row("internet", "o2 Internet")
        refused("candidate_invalid") { compose(cand(setTypes(internet.rowKey, "supl,default"))) }
        assertTrue(refused("candidate_invalid") { compose(cand(setTypes(internet.rowKey, "default"))) }.detail.contains("lose a type"))
        refused("candidate_invalid") { compose(cand(insert(internet.attrs))) }
        refused("candidate_invalid") { compose(cand(omit(internet.rowKey), omit(internet.rowKey))) }
        refused("candidate_invalid") { compose(cand(unset(internet.rowKey, "user"))) }
        refused("candidate_invalid") { compose(cand(omit(row("internet", "o2 old").rowKey), insert(internet.attrs + ("carrier" to "y"), before = row("internet", "o2 old").rowKey))) }
        refused("candidate_invalid") { compose(cand(order(row("mms").rowKey, row("ims").rowKey))) }
        // The reading: strict, bounded, never a SIM value in the reason.
        refused("bad_request") { ApnCandidateSet.parse(null) }
        refused("candidate_invalid") { ApnCandidateSet.parse("{") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"id":"c:1","ops":[]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"ops":[{"op":"omit","row_key":"a:0123456789abcdef"}]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"id":"c:1","ops":[{"op":"drop","row_key":"a:0123456789abcdef"}]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"id":"c:1","ops":[{"op":"omit","row_key":"a:0123456789abcdef","types":"ims"}]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"id":"c:1","ops":[{"op":"omit","row_key":"a:xyz"}]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse("""{"id":"c:1","role":"VOICE","ops":[{"op":"omit","row_key":"a:0123456789abcdef"}]}""") }
        refused("candidate_invalid") { ApnCandidateSet.parse(cand(insert(mapOf("apn" to "x", "type" to "default", "mcc" to "262", "mnc" to "07", "edited" to "1")))) }
        refused("candidate_invalid") { ApnCandidateSet.parse(cand(insert(mapOf("apn" to "x\ny", "type" to "default", "mcc" to "262", "mnc" to "07")))) }
        refused("candidate_invalid") { ApnCandidateSet.parse(cand(*Array(ApnCandidateSet.MAX_OPS + 1) { omit("a:0123456789abcdef") })) }
        refused("candidate_invalid") { ApnCandidateSet.parse("x".repeat(ApnCandidateSet.MAX_CHARS + 1)) }
        // Unknown top-level members are read past; the role maps to its APN type.
        val c = ApnCandidateSet.parse("""{"id":"c:1","role":"IMS","later":true,"ops":[{"op":"omit","row_key":"a:0123456789abcdef"}]}""")
        assertEquals("ims", c.role)
    }

    @Test
    fun anInsertedRowIsWrittenAsTheGeneratorWritesIt() {
        val attrs = mapOf("apn" to "a&b", "carrier" to "Quote \"x\" <y>", "mcc" to "262", "mnc" to "07", "type" to "default,supl")
        val r = compose(cand(insert(attrs)))
        val line = String(r.composed.bytes).split('\n').first { it.contains("a&amp;b") }
        assertEquals("""  <apn apn="a&amp;b" carrier="Quote &quot;x&quot; &lt;y&gt;" mcc="262" mnc="07" type="default,supl" />""", line)
        assertEquals(attrs, r.composed.doc.rows.first { it.rowKey == ApnXml.rowKey(attrs) }.attrs)
    }

    @Test
    fun imsNoWorseThanBefore() {
        assertTrue(ApnCandidateSet.imsNoWorse("registration=0;", "registration=0;"))
        assertTrue("IMS was failing before: anything readable", ApnCandidateSet.imsNoWorse("registration=0;", "registration=2;sms@lte,voice@lte"))
        assertTrue(ApnCandidateSet.imsNoWorse("registration=2;voice@lte", "registration=2;sms@lte,voice@lte"))
        assertFalse(ApnCandidateSet.imsNoWorse("registration=2;sms@lte,voice@lte", "registration=2;sms@lte"))
        assertFalse(ApnCandidateSet.imsNoWorse("registration=2;voice@lte", null))
        assertTrue(ApnCandidateSet.imsNoWorse(null, null))
    }

    // --- apn_candidate_set and apn_candidate_restore in the engine ----------------------------------

    private var n = 0

    private fun bakedFiles(): Map<String, ByteArray> {
        val files = Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"), rows = ROWS, generatedAt = "2026-10-20T00:00:00Z", commit = BAKED, role = "build")
        return Fixture.seal(files, "2026-10-20T00:00:00Z", BAKED, "build", laneAsExtra = true)
    }

    private fun appFiles(rows: List<Pair<String, String>> = ROWS): MutableMap<String, ByteArray> = Fixture.files(rows = rows, generatedAt = "2026-10-25T09:57:41Z", commit = APP)

    private fun setup(): Pair<Engine, FakePlatform> {
        val root = Fixture.tempDir("candidate-${n++}")
        val p = FakePlatform(root, Fixture.write(File(root, "baked"), bakedFiles()))
        val e = Engine(p)
        p.engine = e
        return e to p
    }

    private fun installed(e: Engine, p: FakePlatform, files: Map<String, ByteArray> = appFiles()) {
        e.bootCheck("boot")
        p.drain()
        val names = files.keys.filter { it != "pin.json" }
        val id = e.stage(String(files.getValue("pin.json")), names, names.associateWith { ByteArrayInputStream(files.getValue(it)) }, null)[Protocol.INSTALL_ID] as String
        p.drain()
        e.activate(id)
        p.drain()
        assertEquals("installed", e.installStatus(id)[Protocol.STATE])
    }

    private fun refusal(code: String, block: () -> Unit): Refusal {
        try {
            block()
            fail("expected $code")
        } catch (r: Refusal) {
            assertEquals(r.reason, code, r.code)
            return r
        }
        throw AssertionError()
    }

    private fun appRow(apn: String, mnc: String = "07") = ApnXml.parse(Fixture.xml(ROWS)).rows.first { it.attrs["apn"] == apn && it.attrs["mnc"] == mnc }

    /** Round 33's MMS put back on o2's internet row: a candidate the fixture's O2 SIM can take. */
    private fun typeBack() = cand(setTypes(appRow("internet").rowKey, "default,supl,mms"), role = "MMS")

    private fun appSha() = Files.sha512(appFiles().getValue("apns-conf.xml"))

    private fun inPlace(e: Engine, p: FakePlatform, candidate: String = typeBack()): String {
        val id = e.apnCandidateSet(1, candidate)
        p.drain()
        assertEquals(e.installStatus(id).toString(), "in_place", e.installStatus(id)[Protocol.STATE])
        return id
    }

    private fun checks(e: Engine, id: String): List<Map<*, *>> = (Json.parse(e.installStatus(id)[Protocol.CHECKS] as String) as List<*>).map { it as Map<*, *> }

    @Test
    fun theCandidateSetAndItsRestore() {
        val (e, p) = setup()
        installed(e, p)
        p.setPreferredApn(1, p.idOf("internet"))
        val fullRows = p.tpRows.size
        val versionBefore = p.effective[1]
        val id = inPlace(e, p)
        val st = e.installStatus(id)
        assertEquals("c:0123456789abcdef", st["candidate"])
        assertEquals(1, (st["changed_rows"] as List<*>).size)
        assertEquals(fullRows, st["rows"])
        assertEquals("default,supl,mms", p.tpRows.first { it.apn == "internet" && it.mnc == "07" }.type)
        assertEquals("the preferred APN is set again after the set", p.idOf("internet"), p.preferred[1])
        val status = e.status()["apn_test_set"] as Map<*, *>
        assertEquals("APN candidate test set", status["status"])
        assertEquals("apn_candidate", status["kind"])
        assertEquals("c:0123456789abcdef", status["candidate"])
        assertEquals("APN candidate test set", (Json.parse(e.explain(1, null)) as Map<*, *>)["apn_test_set"])
        assertEquals("the CarrierConfig part is not reloaded", versionBefore, p.effective[1])
        // Nothing else installs, reloads or sets a second temporary set meanwhile.
        assertEquals("apn_test_set_in_place", e.reload(setOf(1), "test").second)
        assertEquals("apn_test_set_in_place", refusal("refused_now") { e.applyApnRecords() }.waitingFor)
        assertEquals("apn_test_set_in_place", refusal("refused_now") { e.emergencyDefaultSet(1) }.waitingFor)
        assertEquals("apn_test_set_in_place", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        refusal("apn_test_set_not_in_place") { e.emergencyDefaultRestore(false) }
        // The app's restore: the installed set and the preferred APN back, the five checks.
        val r = e.apnCandidateRestore()
        p.drain()
        val rs = e.installStatus(r)
        assertEquals(rs.toString(), "restored", rs[Protocol.STATE])
        assertEquals(5, checks(e, r).size)
        assertTrue(checks(e, r).toString(), checks(e, r).all { it["ok"] == true })
        assertEquals(appSha(), p.tpFileSha512)
        assertEquals(fullRows, p.tpRows.size)
        assertEquals(p.idOf("internet"), p.preferred[1])
        assertNull(e.state().apnTest)
        assertNull(e.state().halted)
        assertEquals(1, e.state().candidateTrials.size)
        refusal("apn_test_set_not_in_place") { e.apnCandidateRestore() }
    }

    @Test
    fun aCandidateThatTouchesTheEmergencyRowIsRefusedAndNothingIsSent() {
        val (e, p) = setup()
        installed(e, p)
        val broadcasts = p.broadcasts.size
        val id = e.apnCandidateSet(1, cand(omit(appRow("sos").rowKey)))
        p.drain()
        assertEquals("refused", e.installStatus(id)[Protocol.STATE])
        assertEquals("candidate_invalid", e.installStatus(id)[Protocol.ERROR])
        assertEquals(broadcasts, p.broadcasts.size)
        assertEquals(appSha(), p.tpFileSha512)
        assertNull(e.state().apnTest)
        assertTrue(p.tpRows.any { it.apn == "sos" })
        // Malformed: refused at the call.
        refusal("candidate_invalid") { e.apnCandidateSet(1, "{\"id\":\"c:1\"}") }
        refusal("bad_request") { e.apnCandidateSet(null, typeBack()) }
        refusal("bad_request") { e.apnCandidateSet(1, null) }
    }

    @Test
    fun aRebootWithTheCandidateSetInPlaceRestoresItAsAStopEvent() {
        val (e, p) = setup()
        installed(e, p)
        inPlace(e, p)
        val after = e.bootCheck("locked_boot_completed")
        p.drain()
        assertTrue(after, after.contains("restored"))
        assertEquals(appSha(), p.tpFileSha512)
        assertNull(e.state().apnTest)
        assertTrue(e.state().halted!!.reason, e.state().halted!!.reason.contains("rebooted with the APN candidate test set in place"))
        assertTrue(p.notifications.isNotEmpty())
    }

    @Test
    fun theSetGoesAtItsTimeWithoutTheApp() {
        val (e, p) = setup()
        installed(e, p)
        inPlace(e, p)
        p.now += 5 * 60_000
        e.processPending("retry")
        p.drain()
        assertNotNull("within its time it stays", e.state().apnTest)
        p.now += 6 * 60_000
        e.processPending("retry")
        p.drain()
        assertNull(e.state().apnTest)
        assertEquals(appSha(), p.tpFileSha512)
        assertNull("no stop event: only a failed restore is one", e.state().halted)
        assertTrue(p.logs.any { it.contains("APN candidate test set restored") && it.contains("its time is up") })
    }

    @Test
    fun aSimChangeRestoresTheSet() {
        val (e, p) = setup()
        installed(e, p)
        p.setPreferredApn(1, p.idOf("internet"))
        inPlace(e, p)
        // The SIM goes and another comes (a new subscription): CARRIER_CONFIG_CHANGED.
        p.sims = listOf(ActiveSim(2, 0, SimId("262", "02", spn = "Vodafone", imsi = "262020000000005")))
        e.onSimChange("carrier config changed")
        p.drain()
        assertNull(e.state().apnTest)
        assertEquals(appSha(), p.tpFileSha512)
        assertNull("the restore's checks pass with the SIM gone", e.state().halted)
        assertTrue(p.logs.any { it.contains("the SIMs in the phone changed") })
        // A load of the new SIM finds it too.
        p.sims = listOf(ActiveSim(1, 0, O2))
        inPlace(e, p)
        val second = ActiveSim(3, 1, SimId("262", "02", spn = "Vodafone", imsi = "262020000000006"))
        p.sims = listOf(ActiveSim(1, 0, O2), second)
        e.load(3, second.sim)
        p.drain()
        assertNull(e.state().apnTest)
        assertEquals(appSha(), p.tpFileSha512)
    }

    @Test
    fun disablingTheAppRestoresTheSetAndTheBuildServes() {
        val (e, p) = setup()
        installed(e, p)
        inPlace(e, p)
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "disabled")
        e.appCheck("test")
        p.drain()
        assertNull(e.state().apnTest)
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
    }

    @Test
    fun theCallWindow() {
        val (e, p) = setup()
        installed(e, p)
        p.phone = PhoneState(true, false, false, false)
        assertEquals("call", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.phone = PhoneState(false, false, false, false)
        e.onCallState(idle = true, emergency = null)
        p.drain()
        p.now += 5 * 60_000
        assertEquals("call_ended_10min", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.now += 6 * 60_000
        p.emergencyRecent = true
        assertEquals("emergency_call_60min", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.emergencyRecent = false
        inPlace(e, p)
        // A call during the trial: the restore waits for it, and runs as soon as it ends (not 10 minutes later).
        p.phone = PhoneState(true, false, false, false)
        val r = e.apnCandidateRestore()
        p.drain()
        assertEquals("waiting", e.installStatus(r)[Protocol.STATE])
        assertEquals("call", e.installStatus(r)[Protocol.WAITING_FOR])
        assertNotNull(e.state().apnTest)
        p.phone = PhoneState(false, false, false, false)
        e.onCallState(idle = true, emergency = null)
        p.drain()
        assertEquals("restored", e.installStatus(r)[Protocol.STATE])
        assertEquals(appSha(), p.tpFileSha512)
    }

    @Test
    fun aRestoreThatDoesNotComeBackIsRestoreFailedWithTheFiveChecks() {
        val (e, p) = setup()
        installed(e, p)
        inPlace(e, p)
        p.data = false
        val r = e.apnCandidateRestore()
        p.drain()
        assertEquals("restore_failed", e.installStatus(r)[Protocol.STATE])
        assertEquals("restore_failed", e.installStatus(r)[Protocol.ERROR])
        val c = checks(e, r)
        assertEquals(5, c.size)
        assertEquals(listOf(4L), c.filter { it["ok"] == false }.map { (it["check"] as Json.Num).longOrNull() })
        assertNotNull("a stop event", e.state().halted)
        assertNull(e.state().apnTest)
        assertEquals(appSha(), p.tpFileSha512)
    }

    @Test
    fun theRestoreIsCheckedAgainstHowTheSimWasBeforeTheSet() {
        val (e, p) = setup()
        installed(e, p)
        // Mobile data was not validated (the failure that opened the trial): not validated after the restore either passes.
        p.data = false
        p.ims = "registration=0;"
        inPlace(e, p)
        p.ims = "registration=2;sms@lte,voice@lte"
        val r = e.apnCandidateRestore()
        p.drain()
        assertEquals(checks(e, r).toString(), "restored", e.installStatus(r)[Protocol.STATE])
        assertNull(e.state().halted)
        // IMS had services before and lost one: restore_failed.
        p.data = true
        p.ims = "registration=2;sms@lte,voice@lte"
        inPlace(e, p)
        p.ims = "registration=2;sms@lte"
        val r2 = e.apnCandidateRestore()
        p.drain()
        assertEquals("restore_failed", e.installStatus(r2)[Protocol.STATE])
        assertEquals(listOf(5L), checks(e, r2).filter { it["ok"] == false }.map { (it["check"] as Json.Num).longOrNull() })
    }

    @Test
    fun aRestoreThatDoesNotShowEndsWithTheBuildsApnFileAndFiveChecks() {
        val (e, p) = setup()
        val app = appFiles(rows = ROWS.dropLast(1))
        installed(e, p, app)
        inPlace(e, p)
        p.refuseSha = setOf(Files.sha512(app.getValue("apns-conf.xml")))
        val r = e.apnCandidateRestore()
        p.drain()
        assertEquals("restore_failed", e.installStatus(r)[Protocol.STATE])
        assertEquals(5, checks(e, r).size)
        assertEquals(false, checks(e, r)[0]["ok"])
        assertNotNull(e.state().halted)
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
        assertNull("both parts from the build", e.state().installed)
    }

    @Test
    fun whenNothingShowsTheSetStillDoesNotStay() {
        val (e, p) = setup()
        installed(e, p)
        val id = inPlace(e, p)
        val candidateSha = p.tpFileSha512
        p.refuseAll = true
        val r = e.apnCandidateRestore()
        p.drain()
        assertEquals("restore_failed", e.installStatus(r)[Protocol.STATE])
        assertEquals(candidateSha, p.tpFileSha512)
        assertNotNull(e.state().halted)
        // The retry alarm: CarrierSettings's own install puts the installed set back once TelephonyProvider takes it.
        p.refuseAll = false
        e.processPending("retry")
        p.drain()
        assertEquals(appSha(), p.tpFileSha512)
        assertEquals("in_place", e.installStatus(id)[Protocol.STATE])
    }

    @Test
    fun aSetThatDoesNotShowIsRolledBack() {
        val (e, p) = setup()
        installed(e, p)
        val appXml = appFiles().getValue("apns-conf.xml")
        val temp = ApnCandidateSet.compose(ApnLogic.compose(appXml, ApnXml.parse(appXml), emptySet()), O2, emptyList(), ApnCandidateSet.parse(typeBack()))
        p.refuseSha = setOf(temp.composed.sha512)
        val id = e.apnCandidateSet(1, typeBack())
        p.drain()
        assertEquals("apn_not_confirmed", e.installStatus(id)[Protocol.STATE])
        assertEquals(appSha(), p.tpFileSha512)
        assertNull(e.state().apnTest)
        assertNull(e.state().halted)
    }

    @Test
    fun theSetsRefusals() {
        val (e, p) = setup()
        e.bootCheck("boot")
        p.drain()
        refusal("not_serving_installed") { e.apnCandidateSet(1, typeBack()) }
        installed(e, p)
        refusal("not_found") { e.apnCandidateSet(9, typeBack()) }
        p.roamingValue = true
        assertEquals("roaming", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.roamingValue = false
        p.trial = true
        assertEquals("apn_trial_in_place", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.trial = false
        e.setTester(false, "the person did not opt in")
        p.drain()
        refusal("not_tester") { e.apnCandidateSet(1, typeBack()) }
        e.setTester(true, "opted in")
        p.drain()
        p.cellValue = Fixture.CELL.copy(stackId = "phh_ims")
        refusal("not_tester") { e.apnCandidateSet(1, typeBack()) }
        p.cellValue = Fixture.CELL
        e.stop("a stop event")
        refusal("changes_halted") { e.apnCandidateSet(1, typeBack()) }
        e.resume()
        // A row the person edited is never touched.
        p.userRows = listOf(ApnLogic.DbRow("262", "07", "internet", "default,supl", "", "", "IPV4V6", "IP", -1, 1))
        val id = e.apnCandidateSet(1, typeBack())
        p.drain()
        assertEquals("user_edited_row", e.installStatus(id)[Protocol.ERROR])
        p.userRows = emptyList()
        // The emergency test's set in place: no candidate, and no candidate restore of it.
        e.emergencyDefaultSet(1)
        p.drain()
        assertEquals("apn_test_set_in_place", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        refusal("apn_test_set_not_in_place") { e.apnCandidateRestore() }
        e.emergencyDefaultRestore(false)
        p.drain()
        assertNull(e.state().apnTest)
    }

    @Test
    fun theCapsBackstop() {
        val (e, p) = setup()
        installed(e, p)
        repeat(Engine.CANDIDATES_PER_HOUR) {
            inPlace(e, p)
            e.apnCandidateRestore()
            p.drain()
            assertNull(e.state().apnTest)
        }
        assertEquals("candidate_cap", refusal("refused_now") { e.apnCandidateSet(1, typeBack()) }.waitingFor)
        p.now += 61 * 60_000
        inPlace(e, p)
    }

    @Test
    fun theProtocolStaysThreeAndTheMethodsAreTheOwnersOnly() {
        assertEquals(3, Protocol.VERSION)
        assertTrue(Protocol.APN_CANDIDATE_SET in Protocol.OWNER_METHODS)
        assertTrue(Protocol.APN_CANDIDATE_RESTORE in Protocol.OWNER_METHODS)
        assertFalse("adb never writes", Protocol.APN_CANDIDATE_SET in Protocol.ADB_METHODS)
        assertFalse(Protocol.APN_CANDIDATE_RESTORE in Protocol.ADB_METHODS)
        // The names the OCD app 0.4.2 sends and reads (core/OcdData.kt, ApnCandidateTrials.kt).
        assertEquals("apn_candidate_set", Protocol.APN_CANDIDATE_SET)
        assertEquals("apn_candidate_restore", Protocol.APN_CANDIDATE_RESTORE)
        assertEquals("candidate", Protocol.CANDIDATE)
        val (e, p) = setup()
        installed(e, p)
        assertEquals(3, e.status()[Protocol.PROTOCOL])
        val id = inPlace(e, p)
        for (k in listOf("install_id", "state", "candidate", "removed_rows", "changed_rows", "added_rows", "rows", "preferred_apn_recorded")) assertTrue(k, e.installStatus(id).containsKey(k))
        assertTrue("the app reads \"candidate\" in it", ((e.status()["apn_test_set"] as Map<*, *>)["status"] as String).contains("candidate"))
    }

    @Test
    fun theTrialRoundTripsInTheStateAndKeepsNoSimIdentifier() {
        val t = ApnTestSet(
            1, 10, "in_place", listOf("a:1"), "s1", "s2", "sha256:x", 40, listOf(PreferredApn(1, mapOf("apn" to "internet"), 5)), null,
            ApnTestSet.APN_CANDIDATE, CandidateTrial("c:1", "round 32 D8 rule 4", "without_fill", "default", listOf("a:2"), emptyList(), 0, "26207", listOf(1, 2), null, 99),
        )
        val s = State(apnTest = t, candidateTrials = listOf(5, 6))
        assertEquals(s, State.decode(State.encode(s)))
        val (e, p) = setup()
        installed(e, p)
        inPlace(e, p)
        e.apnCandidateRestore()
        p.drain()
        val everything = StringBuilder(e.status().toString()).append(p.logs.joinToString("\n"))
        p.store.root.walkTopDown().filter { it.isFile }.forEach { everything.append(String(it.readBytes(), Charsets.ISO_8859_1)) }
        assertFalse("no IMSI", everything.contains(EngineTest.IMSI))
        assertFalse("no ICCID", everything.contains(EngineTest.ICCID))
    }
}
