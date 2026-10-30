/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The APN part: row keys (2.9), the strict line reader, composition (7.3), confirmation (7.5), selection (4.6) and the gate (8.5). */
class ApnTest {
    private fun refusedXml(code: String, text: String) {
        try {
            ApnXml.parse(text)
            fail("expected $code")
        } catch (e: Refused) {
            assertEquals(e.detail, code, e.code)
        }
    }

    private fun xml(vararg rows: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!--\n    data_digest: ${Fixture.DATA_DIGEST}\n-->\n<apns version=\"8\">\n" + rows.joinToString("") { "  $it\n" } + "</apns>\n"

    @Test
    fun theCntRowKeysAreTheSpecs() {
        val doc = ApnXml.parse(Fixture.xml())
        val cnt = doc.rows.filter { it.attrs["mnc"] == "37" }.map { it.rowKey }
        assertEquals(listOf("a:f3b816097ac0ee1b", "a:a1e84b19ef1834d7", "a:a913d9286c805fdb"), cnt)
        assertEquals(Fixture.DATA_DIGEST, doc.dataDigest)
        assertEquals(8, doc.version)
    }

    @Test
    fun theWholePublicFileReadsStrictly() {
        val path = System.getenv("OCD_APNS_CONF") ?: return
        val bytes = File(path).readBytes()
        val doc = ApnXml.parse(bytes)
        assertTrue(doc.rows.size > 20000)
        val cnt = doc.rows.filter { it.attrs["mcc"] == "302" && it.attrs["mnc"] == "37" }
        assertEquals(listOf(7779, 7780, 7781), cnt.map { it.line })
        assertEquals(listOf("a:f3b816097ac0ee1b", "a:a1e84b19ef1834d7", "a:a913d9286c805fdb"), cnt.map { it.rowKey })
        assertEquals(7766, cnt.first().ordinal)
        println("public apns-conf.xml: ${doc.rows.size} rows read strictly")
    }

    @Test fun aDoctypeIsRefused() = refusedXml("apn_index_mismatch", xml().replace("<apns version", "<!DOCTYPE apns>\n<apns version"))

    @Test fun anUnknownEntityIsRefused() = refusedXml("apn_index_mismatch", xml("<apn apn=\"a&foo;\" mcc=\"001\" mnc=\"01\" />"))

    @Test fun aDuplicateAttributeIsRefused() = refusedXml("apn_index_mismatch", xml("<apn apn=\"a\" apn=\"b\" mcc=\"001\" mnc=\"01\" />"))

    @Test fun anElementSplitOverLinesIsRefused() = refusedXml("apn_index_mismatch", xml("<apn apn=\"a\"\n    mcc=\"001\" mnc=\"01\" />"))

    @Test fun anotherElementIsRefused() = refusedXml("apn_index_mismatch", xml("<carrier name=\"x\" />"))

    @Test fun aMissingCloseIsRefused() = refusedXml("apn_index_mismatch", xml().replace("</apns>\n", ""))

    @Test fun aRootWithoutVersionIsAnApnVersionRefusal() = refusedXml("apn_version", xml().replace("<apns version=\"8\">", "<apns>"))

    @Test
    fun entitiesAreUnescapedBeforeTheRowKey() {
        val doc = ApnXml.parse(xml("<apn apn=\"a&amp;b\" carrier=\"&quot;x&quot;\" mcc=\"001\" mnc=\"01\" />"))
        assertEquals("a&b", doc.rows[0].attrs["apn"])
        assertEquals(ApnXml.rowKey(mapOf("apn" to "a&b", "carrier" to "\"x\"", "mcc" to "001", "mnc" to "01")), doc.rows[0].rowKey)
    }

    // --- 7.3 composition -------------------------------------------------------------------

    @Test
    fun withoutOmitsTheFileIsThePublishedBytes() {
        val bytes = Fixture.xml().toByteArray()
        val c = ApnLogic.compose(bytes, ApnXml.parse(bytes), emptySet())
        assertTrue(c.bytes.contentEquals(bytes))
        assertEquals(Files.sha512(bytes), c.sha512)
    }

    @Test
    fun anOmitRemovesExactlyItsLine() {
        val bytes = Fixture.xml().toByteArray()
        val doc = ApnXml.parse(bytes)
        val tchibo = doc.rows.first { it.attrs["apn"] == "tchibo.example" }.rowKey
        val c = ApnLogic.compose(bytes, doc, setOf(tchibo))
        assertEquals(doc.rows.size - 1, c.rows)
        assertEquals(listOf(tchibo), c.omitted)
        assertFalse(String(c.bytes).contains("tchibo.example"))
        assertEquals(doc.rows.map { it.rowKey } - tchibo, c.doc.rows.map { it.rowKey })
    }

    @Test
    fun omitsComeFromValidRecordsOfThisCell() {
        val doc = ApnXml.parse(Fixture.xml())
        val sos = doc.rows.first { it.attrs["apn"] == "sos" }.rowKey
        val mtb = doc.rows.first { it.attrs["apn"] == "mtb.example" }.rowKey
        fun rec(json: Map<String, Any?>) = Record.parse(Json.parse(Json.write(json)), "t") { _, _ -> error("no value") }
        val won = rec(Fixture.record(kind = "measured", key = "", value = null, valueKind = "none", target = linkedMapOf("type" to "emergency_data", "row_key" to sos), result = "emergency_default_won", carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.c07dcc040b58")))
        val omit = rec(Fixture.record(kind = "measured", key = "", value = null, valueKind = "none", target = linkedMapOf("type" to "apn", "row_key" to mtb, "op" to "omit"), carrier = mapOf("scope" to "profile", "profile_id" to "open.302370.632d3386012f")))
        val refused = rec(Fixture.record(kind = "measured", key = "", value = null, valueKind = "none", target = linkedMapOf("type" to "emergency_data", "row_key" to sos), result = "emergency_data_refused", carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.c07dcc040b58")))
        val expired = rec(Fixture.record(kind = "measured", key = "", value = null, valueKind = "none", target = linkedMapOf("type" to "apn", "row_key" to sos, "op" to "omit"), carrier = mapOf("scope" to "profile", "profile_id" to "open.26207.c07dcc040b58"), validUntil = "2026-01-01", lastMeasured = "2025-10-01"))
        val keys = doc.rows.map { it.rowKey }.toSet()
        assertEquals(setOf(sos, mtb), ApnLogic.omits(listOf(won, omit, refused, expired), Fixture.CELL, "2026-10-25", keys))
        assertEquals(emptySet<String>(), ApnLogic.omits(listOf(won, omit), null, "2026-10-25", keys))
    }

    // --- 7.5 confirmation --------------------------------------------------------------------

    private fun db(row: ApnXml.Row, type: String? = row.attrs["type"], edited: Int = 0) = ApnLogic.DbRow(
        row.attrs["mcc"], row.attrs["mnc"], row.attrs["apn"], type, row.attrs["mvno_type"] ?: "", row.attrs["mvno_match_data"] ?: "",
        row.attrs["protocol"], row.attrs["roaming_protocol"], row.attrs["carrier_id"]?.toInt() ?: -1, edited,
    )

    @Test
    fun theFileShowsWhenEveryRowIsThere() {
        val rows = ApnXml.parse(Fixture.xml()).rows
        assertTrue(ApnLogic.shows(rows, rows.map { db(it) }).ok)
        assertFalse("a missing row", ApnLogic.shows(rows, rows.drop(1).map { db(it) }).ok)
        val extra = ApnLogic.DbRow("001", "01", "old", "default", "", "", "IP", "IP", -1, 0)
        assertFalse("an unedited row not in the file", ApnLogic.shows(rows, rows.map { db(it) } + extra).ok)
        assertTrue("a user-added row stays", ApnLogic.shows(rows, rows.map { db(it) } + extra.copy(edited = 1)).ok)
    }

    @Test
    fun telephonyProviderMergesTypesOfRowsEqualOnItsUniqueFields() {
        // The public file's first two Cosmote rows differ only in fields outside CARRIERS_UNIQUE_FIELDS
        // and in their type; TelephonyProvider keeps one row with the union of the types.
        val text = xml(
            "<apn apn=\"internet\" carrier=\"Cosmote Internet\" mcc=\"202\" mnc=\"01\" protocol=\"IPV4V6\" type=\"default,supl,xcap\" />",
            "<apn apn=\"internet\" carrier=\"internet\" mcc=\"202\" mnc=\"01\" protocol=\"IPV4V6\" type=\"default,dun,hipri,supl,xcap\" />",
        )
        val rows = ApnXml.parse(text).rows
        val merged = listOf(db(rows[0], type = "default,supl,xcap,dun,hipri"))
        assertTrue(ApnLogic.shows(rows, merged).ok)
        assertFalse("a type the file asks for is missing", ApnLogic.shows(rows, listOf(db(rows[0], type = "default,supl"))).ok)
        assertTrue("an empty type is every type", ApnLogic.shows(rows, listOf(db(rows[0], type = ""))).ok)
    }

    @Test
    fun protocolDefaultsToIp() {
        val rows = ApnXml.parse(xml("<apn apn=\"a\" mcc=\"001\" mnc=\"01\" type=\"default\" />")).rows
        assertTrue(ApnLogic.shows(rows, listOf(ApnLogic.DbRow("001", "01", "a", "default", "", "", "IP", "IP", -1, 0))).ok)
    }

    // --- 4.6 selection semantics --------------------------------------------------------------

    @Test
    fun mvnoBeforeMnoBeforeCarrierId() {
        val rows = ApnXml.parse(
            xml(
                "<apn apn=\"mno\" mcc=\"262\" mnc=\"02\" type=\"default\" />",
                "<apn apn=\"iccid\" mcc=\"262\" mnc=\"02\" mvno_match_data=\"8931,8944\" mvno_type=\"iccid\" type=\"default\" />",
                "<apn apn=\"cid-any\" carrier_id=\"1911\" type=\"default\" />",
                "<apn apn=\"cid-other\" carrier_id=\"1911\" mcc=\"999\" mnc=\"99\" type=\"default\" />",
            ),
        ).rows
        val mvno = SimId("262", "02", iccid = "89440000000000000001", specificCarrierId = 1911)
        assertEquals(listOf("iccid", "cid-any"), ApnSelect.select(rows, mvno).map { it.attrs["apn"] })
        val mno = SimId("262", "02", iccid = "89490000000000000001", specificCarrierId = 1911)
        assertEquals(listOf("mno", "cid-any"), ApnSelect.select(rows, mno).map { it.attrs["apn"] })
        val other = SimId("262", "03", specificCarrierId = 1911)
        assertEquals(listOf("cid-other", "cid-any"), ApnSelect.select(rows, other).map { it.attrs["apn"] })
        assertEquals(emptyList<String>(), ApnSelect.select(rows, SimId("262", "03")).map { it.attrs["apn"] })
    }

    @Test
    fun javaSplitSemantics() {
        assertEquals(listOf("a"), ApnSelect.javaSplit("a,,"))
        assertEquals(emptyList<String>(), ApnSelect.javaSplit(","))
        assertEquals(listOf(""), ApnSelect.javaSplit(""))
        assertEquals(listOf("a", "", "b"), ApnSelect.javaSplit("a,,b"))
    }

    @Test
    fun anEmptyTypeServesEveryType() {
        val row = ApnXml.parse(xml("<apn apn=\"a\" mcc=\"001\" mnc=\"01\" />")).rows[0]
        assertTrue(row.serves("default") && row.serves("ims"))
    }

    // --- 8.5 the emergency gate --------------------------------------------------------------

    @Test
    fun theEmergencyDigestFollowsEmergencyRowsOnly() {
        val rows = ApnXml.parse(Fixture.xml()).rows
        val changedOther = ApnXml.parse(Fixture.xml(Fixture.ROWS.filter { !it.second.contains("mtb.example") })).rows
        assertEquals(ApnLogic.emergencyDigest(rows), ApnLogic.emergencyDigest(changedOther))
        val changedSos = ApnXml.parse(Fixture.xml(Fixture.ROWS.map { it.first to it.second.replace("apn=\"sos\"", "apn=\"sos2\"") })).rows
        assertNotEquals(ApnLogic.emergencyDigest(rows), ApnLogic.emergencyDigest(changedSos))
    }

    @Test
    fun theF5ReadRecord() {
        val base = "b".repeat(40)
        val head = "c".repeat(40)
        fun read(reader: String = "second agent", verdict: String = "proceed", b: String = base, h: String = head) =
            Json.write(linkedMapOf("schema" to "x1s-ocd-f5-read.v1", "base_commit" to b, "head_commit" to h, "lines_digest" to "0123", "reader" to reader, "read_at" to "2026-10-25T12:00:00Z", "verdict" to verdict, "notes" to "read"))
        assertNull(ApnLogic.f5ReadProblem(read(), base, head))
        assertTrue(ApnLogic.f5ReadProblem(null, base, head) != null)
        assertTrue(ApnLogic.f5ReadProblem(read(verdict = "hold"), base, head) != null)
        assertTrue(ApnLogic.f5ReadProblem(read(b = head), base, head) != null)
        assertTrue(ApnLogic.f5ReadProblem(read(h = base), base, head) != null)
        assertTrue(ApnLogic.f5ReadProblem(read(reader = "io.github.open_carrier_data.check.owner"), base, head) != null)
        assertTrue(ApnLogic.f5ReadProblem(read(reader = " "), base, head) != null)
    }
}
