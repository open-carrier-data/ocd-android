package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.owner.core.ApnCandidateGate
import io.github.open_carrier_data.check.owner.core.ApnCandidateProtocol
import io.github.open_carrier_data.check.owner.core.ApnCandidates
import io.github.open_carrier_data.check.owner.core.ApnConf
import io.github.open_carrier_data.check.owner.core.ApnFailures
import io.github.open_carrier_data.check.owner.core.ApnOp
import io.github.open_carrier_data.check.owner.core.ApnRowKeys
import io.github.open_carrier_data.check.owner.core.ApnSelection
import io.github.open_carrier_data.check.owner.core.ApnSetCandidate
import io.github.open_carrier_data.check.owner.core.ApnTrial
import io.github.open_carrier_data.check.owner.core.ApnTrialReview
import io.github.open_carrier_data.check.owner.core.ApnTrialVerdict
import io.github.open_carrier_data.check.owner.core.BuildCell
import io.github.open_carrier_data.check.owner.core.CandidateInput
import io.github.open_carrier_data.check.owner.core.ChangesHalted
import io.github.open_carrier_data.check.owner.core.D7Age
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetRef
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.LeadFailure
import io.github.open_carrier_data.check.owner.core.LeadRole
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.ProfileApnRows
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TrialOutcome
import io.github.open_carrier_data.check.owner.core.TrialVerdict
import io.github.open_carrier_data.check.owner.core.VoiceLossState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD's rule rounds 32 to 36: the APN candidates a tester phone tries after a measured failure (D8
 * and D7 rule 4, round 33 step 6, round 34 step 7, round 36's app-side step), computed from OCD's
 * public data. The fixtures are public main 7247b602's rows of five networks with their profiles'
 * JSON; expected.json holds the generator's own row keys for them (apn-index.json and apn_records).
 */
class ApnCandidatesTest {
    private val dir = File("src/testOwner/resources/apn-candidates")
    private val expected = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject

    private fun rows(code: String): List<OcdApnRow> = ApnConf.parse(File(dir, "$code.xml").readLines().asSequence()).toList()
    private fun profile(id: String): JsonObject = Json.parseToJsonElement(File(dir, id.removePrefix("open.") + ".json").readText()).jsonObject
    private fun keys(id: String, member: String) = expected.getValue(id).jsonObject.getValue(member).jsonArray.map { it.jsonPrimitive.content }
    private fun sim(code: String) = SimIdentity(code, spn = null, gid1 = null, carrierIds = null)

    private fun input(id: String, role: LeadRole): CandidateInput {
        val code = id.split('.')[1]
        val network = rows(code)
        val written = keys(id, "written").toSet()
        return CandidateInput(role, network, ApnSelection.of(network, sim(code)), network.filter { ApnRowKeys.rowKey(it.attributes) in written }, ProfileApnRows.records(profile(id)))
    }

    private val ids = listOf("open.26220.40aaf702d9ce", "open.40458.f0a31b354f9c", "open.25020.d93e3f41cc80", "open.25901.cec477271b1a", "open.26203.dbb2be7109a6")

    @Test
    fun `the row key is apn-index's, for every written row`() {
        for (id in ids) {
            val mine = rows(id.split('.')[1]).map { ApnRowKeys.rowKey(it.attributes) }.toSet()
            for (k in keys(id, "written")) assertTrue("$id $k", k in mine)
        }
    }

    @Test
    fun `a profile's rows are the generator's apn_records, row for row`() {
        for (id in ids) assertEquals(id, keys(id, "records"), ProfileApnRows.records(profile(id)).map { ApnRowKeys.rowKey(it) })
    }

    @Test
    fun `D8 rule 4 - the row rule 1 left bare is restored under the lead it fills in (262-20)`() {
        val c = ApnCandidates.restoreLeftOut(input("open.26220.40aaf702d9ce", LeadRole.INTERNET)).single()
        assertEquals("round 32 D8 rule 4", c.rule)
        assertEquals(ApnSetCandidate.RESTORE_LEFT_OUT, c.kind)
        val op = c.ops.single()
        assertEquals(ApnOp.INSERT, op.op)
        assertEquals("internet.mvno.mobi", op.attributes!!["apn"])
        assertEquals("default,supl", op.attributes["type"])
        assertEquals("mvno", op.attributes["user"])
        // Before the written row TelephonyProvider stores it with, so that row's values still win (change 17's best-last order).
        val enreach = rows("26220").first { it.name == "Enreach DE" }
        assertEquals(ApnRowKeys.rowKey(enreach.attributes), op.before)
        assertEquals(ApnRowKeys.uniqueKey(enreach.attributes), ApnRowKeys.uniqueKey(op.attributes))
        assertTrue(c.plain, "user/password" in c.plain && "mvno" !in c.plain.substringAfter("with its"))
        // Not a candidate for the other leads.
        assertTrue(ApnCandidates.restoreLeftOut(input("open.26220.40aaf702d9ce", LeadRole.IMS)).isEmpty())
    }

    @Test
    fun `round 34 - 404-58's bsnlnet IMS row and 250-20's XCAP row restored`() {
        val ims = ApnCandidates.restoreLeftOut(input("open.40458.f0a31b354f9c", LeadRole.IMS)).single()
        assertEquals("round 34 step 7", ims.rule)
        assertEquals("bsnlnet", ims.ops.single().attributes!!["apn"])
        assertEquals("ims", ims.ops.single().attributes!!["type"])
        val xcap = ApnCandidates.restoreLeftOut(input("open.25020.d93e3f41cc80", LeadRole.XCAP)).single()
        assertEquals("xcap", xcap.ops.single().attributes!!["type"])
        assertEquals("round 34 step 7", xcap.rule)
    }

    @Test
    fun `rounds 33 and 34 - MMS and IMS put back on 259-01's retyped wap orange md`() {
        val mms = ApnCandidates.typeBack(input("open.25901.cec477271b1a", LeadRole.MMS)).single()
        assertEquals("round 33 step 6", mms.rule)
        val op = mms.ops.single()
        assertEquals(ApnOp.SET_TYPES, op.op)
        val row = rows("25901").first { ApnRowKeys.rowKey(it.attributes) == op.rowKey }
        assertEquals("wap.orange.md", row.apn)
        assertTrue("mms" in op.types!!.split(',') && row.types.all { it in op.types.split(',') })
        val ims = ApnCandidates.typeBack(input("open.25901.cec477271b1a", LeadRole.IMS)).single()
        assertEquals("round 34 step 7", ims.rule)
        assertTrue("ims" in ims.ops.single().types!!.split(','))
    }

    @Test
    fun `no candidate touches an emergency row, adds a wildcard or names a row outside the SIM's selection`() {
        for (id in ids) for (role in LeadRole.entries) {
            val input = input(id, role)
            val selection = input.selection.map { ApnRowKeys.rowKey(it.attributes) }.toSet()
            for (c in ApnCandidates.forFailure(input)) for (op in c.ops) {
                op.rowKey?.let { assertTrue("$id ${c.kind}", it in selection) }
                op.before?.let { assertTrue("$id ${c.kind}", it in selection) }
                op.attributes?.let { a -> assertTrue("$id ${c.kind}", ApnCandidates.touchable(a)) }
                op.types?.let { t -> assertFalse(t, "emergency" in t || "*" in t) }
            }
        }
        // The o2 SIM (262-03): two of its stored internet rows carry an auth type only an earlier row of the same storage key sets.
        // The candidate drops just that field from those rows, which also add a type (xcap, supl), and leaves every row in place.
        val o2 = ApnCandidates.forFailure(input("open.26203.dbb2be7109a6", LeadRole.INTERNET)).single()
        assertEquals(ApnSetCandidate.WITHOUT_FILL, o2.kind)
        assertEquals(2, o2.ops.size)
        for (op in o2.ops) {
            assertEquals(ApnOp.UNSET, op.op)
            assertEquals(listOf("authtype"), op.fields)
        }
        // Its stored IMS row takes auth type 1 from the second of four rows with one storage key; that row adds nothing else, so it leaves.
        val ims = ApnCandidates.forFailure(input("open.26203.dbb2be7109a6", LeadRole.IMS)).single()
        assertEquals(ApnSetCandidate.WITHOUT_FILL, ims.kind)
        val omitted = rows("26203").single { ApnRowKeys.rowKey(it.attributes) == ims.ops.single { it.op == ApnOp.OMIT }.rowKey }
        assertEquals("1", omitted.attributes["authtype"])
        assertEquals(listOf("ims"), omitted.types)
    }

    private fun row(vararg pairs: Pair<String, String>) = OcdApnRow(linkedMapOf("mcc" to "262", "mnc" to "99", *pairs))

    @Test
    fun `D8 rule 4 - the set without the filling row, or the filling row without its credentials`() {
        val filler = row("apn" to "web", "carrier" to "old copy", "type" to "default", "user" to "u", "password" to "p")
        val best = row("apn" to "web", "carrier" to "Vendor", "type" to "default,supl")
        val other = row("apn" to "ims", "carrier" to "IMS", "type" to "ims")
        val network = listOf(filler, best, other)
        val input = CandidateInput(LeadRole.INTERNET, network, network, network)
        val c = ApnCandidates.withoutFill(input)!!
        assertEquals("round 32 D8 rule 4", c.rule)
        assertEquals(listOf(ApnOp(ApnOp.OMIT, rowKey = ApnRowKeys.rowKey(filler.attributes))), c.ops)
        // A filling row that also adds a type keeps it: only the filled-in fields go.
        val typed = row("apn" to "web", "carrier" to "old copy", "type" to "default,mms", "user" to "u", "password" to "p", "authtype" to "1")
        val c2 = ApnCandidates.withoutFill(CandidateInput(LeadRole.INTERNET, listOf(typed, best), listOf(typed, best), listOf(typed, best)))!!
        assertEquals(ApnOp.UNSET, c2.ops.single().op)
        assertEquals(listOf("user", "password", "authtype"), c2.ops.single().fields)
        // The best row sets its own credentials: nothing is filled in.
        val own = row("apn" to "web", "carrier" to "Vendor", "type" to "default", "user" to "v", "password" to "w")
        assertNull(ApnCandidates.withoutFill(CandidateInput(LeadRole.INTERNET, listOf(filler, own), listOf(filler, own), listOf(filler, own))))
        // Another protocol is another stored row.
        val v6 = row("apn" to "web", "carrier" to "Vendor", "type" to "default", "protocol" to "IPV6")
        assertNull(ApnCandidates.withoutFill(CandidateInput(LeadRole.INTERNET, listOf(filler, v6), listOf(filler, v6), listOf(filler, v6))))
    }

    @Test
    fun `round 34 - the earlier order the ROM's own data gives`() {
        val a = row("apn" to "vzwinternet", "carrier" to "A", "type" to "default,ia")
        val b = row("apn" to "vzwadmin", "carrier" to "B", "type" to "ia")
        val keys = listOf(a, b).map { ApnRowKeys.rowKey(it.attributes) }
        val c = ApnCandidates.earlierOrder(CandidateInput(LeadRole.ATTACH, listOf(a, b), listOf(a, b), listOf(a, b), buildOrder = keys.reversed()))!!
        assertEquals("round 34 step 7", c.rule)
        assertEquals(keys.reversed(), c.ops.single().rowKeys)
        assertNull(ApnCandidates.earlierOrder(CandidateInput(LeadRole.ATTACH, listOf(a, b), listOf(a, b), listOf(a, b), buildOrder = keys)))
        assertNull(ApnCandidates.earlierOrder(CandidateInput(LeadRole.ATTACH, listOf(a, b), listOf(a, b), listOf(a, b), buildOrder = null)))
    }

    @Test
    fun `D7 rule 4 - the plain scope's rows for a kept old scope, the left-out scope's rows for a SIM it left out, emergency rows unchanged`() {
        val plainNet = row("apn" to "internet", "carrier" to "Host", "type" to "default,supl")
        val plainSos = row("apn" to "sos", "carrier" to "SOS", "type" to "emergency")
        val mvnoNet = row("apn" to "old.mvno", "carrier" to "Old", "type" to "default", "mvno_type" to "spn", "mvno_match_data" to "Old")
        val mvnoSos = row("apn" to "sos", "carrier" to "SOS", "type" to "emergency", "mvno_type" to "spn", "mvno_match_data" to "Old")
        val network = listOf(plainNet, plainSos, mvnoNet, mvnoSos)
        val sim = SimIdentity("26299", "Old", null, null)
        val selection = ApnSelection.of(network, sim)
        assertEquals(listOf(mvnoNet, mvnoSos), selection)
        val kept = ApnCandidates.plainScope(CandidateInput(LeadRole.INTERNET, network, selection, selection, oldMvnoScope = true))!!
        assertEquals("round 32 D7 rule 4", kept.rule)
        assertEquals(listOf(ApnOp(ApnOp.OMIT, rowKey = ApnRowKeys.rowKey(mvnoNet.attributes))), kept.ops.filter { it.op == ApnOp.OMIT })
        val inserted = kept.ops.filter { it.op == ApnOp.INSERT }.map { it.attributes!! }
        assertEquals(listOf("internet"), inserted.map { it["apn"] })
        assertEquals("Old", inserted.single()["mvno_match_data"])
        assertNull(ApnCandidates.plainScope(CandidateInput(LeadRole.INTERNET, network, selection, selection, oldMvnoScope = false)))
        // A SIM the data moved to the plain scope: the left-out scope's rows, with copies of its emergency row under that selector.
        val plainSelection = ApnSelection.of(listOf(plainNet, plainSos), sim)
        val left = ApnCandidates.leftOutScope(CandidateInput(LeadRole.INTERNET, listOf(plainNet, plainSos), plainSelection, plainSelection, leftOutScopeRows = listOf(mvnoNet.attributes)))!!
        val added = left.ops.map { it.attributes!! }
        assertEquals(listOf("old.mvno", "sos"), added.map { it["apn"] })
        assertEquals(plainSos.attributes + mapOf("mvno_type" to "spn", "mvno_match_data" to "Old"), added[1])
        assertEquals(ApnSetCandidate.LEFT_OUT_SCOPE, ApnCandidates.forFailure(CandidateInput(LeadRole.INTERNET, listOf(plainNet, plainSos), plainSelection, plainSelection, leftOutScopeRows = listOf(mvnoNet.attributes))).first().kind)
    }

    @Test
    fun `D7's age - an MVNO scope whose newest entry is over three years before the data`() {
        assertTrue(D7Age.old("2023-09", "2026-10-29T00:54:44Z"))
        assertFalse(D7Age.old("2023-11", "2026-10-29T00:54:44Z"))
        assertFalse(D7Age.old(null, "2026-10-29"))
        assertFalse(D7Age.old("2020-01", null))
    }

    private fun status(stack: String = "aosp_imsstack", installed: Boolean = true) = DatasetStatus(
        available = true, protocol = 3, build = BuildCell(device = "x1s", model = "SM-G981B", romFamily = "lineageos", romLine = "24.0", imsStackId = stack),
        installed = DatasetRef(commit = "c".repeat(40)), serving = if (installed) "installed" else "build",
    )

    private fun gate(
        status: DatasetStatus = status(),
        active: Boolean = true,
        roaming: Boolean? = false,
        left: Int = 6,
        stop: String? = null,
        failure: LeadFailure = LeadFailure.DATA_VALIDATION,
        mmsEnded: Boolean = false,
    ) = ApnCandidateGate.refusal(ApnCandidateGate.Inputs(status, active, roaming, left, stop, failure, mmsEnded))

    @Test
    fun `candidates run only on a tester phone - calibrated stack, active tests, at home, within the caps`() {
        assertNull(gate())
        assertNotNull(gate(status = status(stack = "phh_ims")))
        assertNotNull(gate(status = status().copy(protocol = 2)))
        assertNotNull(gate(active = false))
        assertNotNull(gate(roaming = true))
        assertNotNull(gate(roaming = null))
        assertNotNull(gate(left = 1))
        assertNotNull(gate(stop = "paused"))
        assertNotNull(gate(status = status().copy(changesHalted = ChangesHalted(true))))
        assertNotNull(gate(status = status().copy(voiceLoss = VoiceLossState(hold = true))))
        assertNotNull(gate(status = status().copy(apnTestSet = true)))
        assertNotNull(gate(status = status(installed = false)))
        assertEquals("the network ended MMS", gate(failure = LeadFailure.MMSC_PROBE, mmsEnded = true))
        assertNull(gate(failure = LeadFailure.MMSC_PROBE))
    }

    @Test
    fun `a measured failure no value explained opens the candidates for its lead`() {
        assertEquals(listOf(LeadFailure.DATA_VALIDATION), ApnFailures.of(TrialVerdict(TrialOutcome.MEASURED_FAIL, reason = "mobile data no longer validated")))
        assertEquals(listOf(LeadFailure.DATA_VALIDATION, LeadFailure.IMS), ApnFailures.of(TrialVerdict(TrialOutcome.MEASURED_FAIL, reason = "IMS lost its registration; mobile data no longer validated")))
        assertEquals(emptyList<LeadFailure>(), ApnFailures.of(TrialVerdict(TrialOutcome.PASS, reason = "IMS with the same services")))
        assertEquals(emptyList<LeadFailure>(), ApnFailures.of(TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "no mobile service")))
        assertEquals(emptyList<LeadFailure>(), ApnFailures.of(null))
        // MMS only by the free probe: its check never sends an MMS.
        assertTrue("no MMS is sent" in LeadFailure.MMSC_PROBE.check)
    }

    @Test
    fun `a failure counts only when the candidate passes, and each candidate runs once per commit and SIM`() {
        val c = ApnCandidates.restoreLeftOut(input("open.40458.f0a31b354f9c", LeadRole.IMS)).single()
        assertEquals(ApnTrial.PASSED, ApnTrialVerdict.outcome(true))
        assertTrue(ApnTrialVerdict.line(c, LeadFailure.IMS, true).startsWith("the failure counts"))
        assertTrue(ApnTrialVerdict.line(c, LeadFailure.IMS, false).contains("does not count"))
        assertEquals(ApnTrial.NOT_RUN, ApnTrialVerdict.outcome(null))
        val sim = SimKey(1, "40458", null)
        val tried = ApnTrial(1L, 1, "40458", "c1", LeadFailure.IMS, c.id, c.kind, c.rule, c.plain, ApnTrial.FAILED, "x")
        assertFalse(ApnTrial.due(listOf(tried), sim, "c1", c.id))
        assertTrue(ApnTrial.due(listOf(tried), sim, "c2", c.id))
        assertTrue(ApnTrial.due(listOf(tried.copy(outcome = ApnTrial.NOT_RUN)), sim, "c1", c.id))
        // A failure's candidates are looked at once a day per SIM, commit and lead.
        assertTrue(ApnTrial.lookedAtRecently(listOf(tried), sim, "c1", LeadFailure.IMS, 1L + ApnTrial.DAY_MS - 1))
        assertFalse(ApnTrial.lookedAtRecently(listOf(tried), sim, "c1", LeadFailure.IMS, 1L + ApnTrial.DAY_MS))
        assertFalse(ApnTrial.lookedAtRecently(listOf(tried), sim, "c1", LeadFailure.DATA_VALIDATION, 2L))
        assertFalse(ApnTrial.lookedAtRecently(listOf(tried), sim, "c2", LeadFailure.IMS, 2L))
        // The same data gives the same candidate ID.
        assertEquals(c.id, ApnCandidates.restoreLeftOut(input("open.40458.f0a31b354f9c", LeadRole.IMS)).single().id)
        // A winner's source review names the scope and the operations, never a credential.
        val d8 = ApnCandidates.restoreLeftOut(input("open.26220.40aaf702d9ce", LeadRole.INTERNET)).single()
        val review = ApnTrialReview.of("open.26220.40aaf702d9ce", d8, LeadFailure.DATA_VALIDATION, "c".repeat(40), 1L)
        assertTrue(review.title.startsWith("[source review] APN candidate passed"))
        assertFalse(review.body, "mvno\"" in review.body || "password=" in review.body || "=mvno" in review.body)
    }

    @Test
    fun `the protocol - CarrierSettings composes the set, and a ROM without the method runs no candidate`() {
        val c = ApnCandidates.restoreLeftOut(input("open.25020.d93e3f41cc80", LeadRole.XCAP)).single()
        val wire = Json.parseToJsonElement(ApnCandidateProtocol.encode(c)).jsonObject
        assertEquals(setOf("id", "rule", "kind", "role", "plain", "ops"), wire.keys)
        assertEquals("insert", ((wire["ops"] as JsonArray)[0] as JsonObject)["op"]?.jsonPrimitive?.content)
        // The client's default and a protocol 3 provider: unknown_method, said in plain words.
        val client = object : DatasetClient by DatasetClient.Absent {
            override fun apnCandidateSet(subId: Int, candidate: String): DataAnswer = ApnCandidateProtocol.unknown(OcdDataProtocol.APN_CANDIDATE_SET)
        }
        val a = client.apnCandidateSet(1, ApnCandidateProtocol.encode(c))
        assertFalse(a.ok)
        assertEquals(OcdDataProtocol.UNKNOWN_METHOD, a.error)
        assertTrue(ApnCandidateProtocol.setRefusal(a.error, a.reason).contains("no temporary APN candidate set"))
        assertTrue(ApnCandidateProtocol.setRefusal("refused_now", "call").contains("a call is up"))
        assertEquals("absent", DatasetClient.Absent.apnCandidateRestore().error)
    }

    @Test
    fun `status names which temporary set is in place`() {
        val s = DatasetStatus.parse(DataAnswer(Json.parseToJsonElement("""{"ok":true,"protocol":3,"apn_test_set":"APN candidate test set"}""").jsonObject), 1)
        assertTrue(s.apnTestSet)
        assertEquals("APN candidate test set", s.apnTestSetKind)
        val e = DatasetStatus.parse(DataAnswer(Json.parseToJsonElement("""{"ok":true,"protocol":3,"apn_test_set":{"status":"emergency default test set"}}""").jsonObject), 1)
        assertEquals("emergency default test set", e.apnTestSetKind)
        assertNull(DatasetStatus.parse(DataAnswer(Json.parseToJsonElement("""{"ok":true,"protocol":3}""").jsonObject), 1).apnTestSetKind)
    }
}
