package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.owner.core.CallEvidence
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.Campaign
import io.github.open_carrier_data.check.owner.core.CampaignEnd
import io.github.open_carrier_data.check.owner.core.CampaignPhase
import io.github.open_carrier_data.check.owner.core.CampaignPlanner
import io.github.open_carrier_data.check.owner.core.Candidate
import io.github.open_carrier_data.check.owner.core.Candidates
import io.github.open_carrier_data.check.owner.core.CapEvent
import io.github.open_carrier_data.check.owner.core.Changed
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DatasetRef
import io.github.open_carrier_data.check.owner.core.ExplainKey
import io.github.open_carrier_data.check.owner.core.IdleBlock
import io.github.open_carrier_data.check.owner.core.IdleInputs
import io.github.open_carrier_data.check.owner.core.IdleMoment
import io.github.open_carrier_data.check.owner.core.KeyClasses
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.SafeMode
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.Settlement
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TestCaps
import io.github.open_carrier_data.check.owner.core.TestClass
import io.github.open_carrier_data.check.owner.core.TestsDue
import io.github.open_carrier_data.check.owner.core.TrialGuard
import io.github.open_carrier_data.check.owner.core.TrialJudge
import io.github.open_carrier_data.check.owner.core.TrialObs
import io.github.open_carrier_data.check.owner.core.TrialOutcome
import io.github.open_carrier_data.check.owner.core.TrialPlan
import io.github.open_carrier_data.check.owner.core.TrialVerdict
import io.github.open_carrier_data.check.owner.core.TypedValues
import io.github.open_carrier_data.check.owner.core.VoiceLoss
import io.github.open_carrier_data.check.owner.core.X5Test
import io.github.open_carrier_data.check.core.CallDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Round 30's "testsDecide": all changed values together, the split, the candidates, the outage rule, X5, voice loss, the caps, the idle moment. */
class TestsDecideTest {
    private val sim = OwnerSim(0, 3, SimIdentity("26207", "Example", null, null), "o2", 1911)
    private val now = 1_792_900_000_000

    private fun profile(json: String) = Json.parseToJsonElement(json).jsonObject

    // The stack, generic to specific: O2's network profile, then Tchibo's IMSI profile (M2 of the spec).
    private val o2 = profile("""{"id":"open.26207.c07dcc040b58","values":{"carrier_volte_available_bool":true,"imsvoice.x_int":5},"alternatives":{"imsvoice.x_int":[{"value":7,"families":["samsung_omc"]}]}}""")
    private val tchibo = profile("""{"id":"open.26207.78040a3afcc4","values":{"imsvoice.x_int":9},"alternatives":{"imsvoice.x_int":[{"value":8,"families":["apple_carrier_bundles"]},{"value":9,"families":["google"]}]}}""")

    @Test
    fun `candidates - the most specific profile's other values first, then the Google value, the switch's other value, Android's default`() {
        val list = Candidates.of("imsvoice.x_int", JsonPrimitive(9), "int", listOf(o2, tchibo), JsonPrimitive(6), androidDefault = JsonPrimitive(0))
        assertEquals(listOf(JsonPrimitive(8), JsonPrimitive(5), JsonPrimitive(7), JsonPrimitive(6), null), list.map { it.value })
        assertTrue(list[0].origin.startsWith("alternative:open.26207.78040a3afcc4"))
        assertEquals(Candidate.GOOGLE, list[3].origin)
        assertTrue(list.last().androidDefault)
        // A bool: its other value, never the failing one, and no value twice.
        val b = Candidates.of("carrier_volte_available_bool", JsonPrimitive(true), "bool", listOf(o2), JsonPrimitive(true))
        assertEquals(listOf(JsonPrimitive(false), null), b.map { it.value })
        // A value of another type is never a candidate; Android's default is left out when the failing value is it.
        assertEquals(listOf(null), Candidates.of("imsvoice.x_int", JsonPrimitive(1), "int", emptyList(), JsonPrimitive("1")).map { it.value })
        assertTrue(Candidates.of("imsvoice.x_int", JsonPrimitive(0), "int", emptyList(), null, androidDefault = JsonPrimitive(0)).isEmpty())
        // The previous dataset's value is not a candidate as such: an old value no source names never appears.
        assertTrue(Candidates.of("imsvoice.x_int", JsonPrimitive(9), "int", listOf(tchibo), null).none { it.value == JsonPrimitive(42) })
    }

    @Test
    fun `the trial guard never lets group A or B into an override`() {
        for (k in listOf("carrier_use_ims_first_for_emergency_bool", "imsemergency.x", "carrier_metered_apn_types_strings", "carrier_config_version_string", "gps.es_extension_sec", "bad key!")) {
            assertNotNull(k, TrialGuard.problem(k))
        }
        try {
            TrialGuard.check(listOf("carrier_volte_available_bool", "ecbm_timer_int"))
            fail("group A passed")
        } catch (e: SafetyException) {
            assertEquals("trial_key_refused", e.reason)
        }
        // Non-emergency iwlan.* keys are tested over Wi-Fi now (round 30).
        assertNull(TrialGuard.problem("iwlan.epdg_static_address_string"))
        assertEquals(TestClass.WIFI_CALLING, KeyClasses.of("iwlan.ike_rekey_hard_timer_in_sec"))
        assertEquals(TestClass.VOICE, KeyClasses.of("imsvoice.amrwb_payload_type_int_array"))
        assertEquals(TestClass.SMS, KeyClasses.of("imssms.sms_over_ims_supported_bool"))
        assertEquals(TestClass.VIDEO, KeyClasses.of("carrier_vt_available_bool"))
        assertEquals(TestClass.OTHER, KeyClasses.of("mmsc_url_string"))
    }

    private val good = TrialObs(true, false, setOf("mmtel", "smsip"), true, true, false)

    @Test
    fun `the judge - not counted is never a failure, a measured failure names its classes, a pass names its level`() {
        val all = TestClass.entries.toSet()
        assertEquals(TrialOutcome.NOT_COUNTED, TrialJudge.judge(all, good, good.copy(inService = false), emptyList(), null).outcome)
        assertEquals(TrialOutcome.NOT_COUNTED, TrialJudge.judge(all, good.copy(imsRegistered = false), good, emptyList(), null).outcome)
        val lost = TrialJudge.judge(all, good, good.copy(imsRegistered = false), emptyList(), null)
        assertEquals(TrialOutcome.MEASURED_FAIL, lost.outcome)
        assertEquals(all, lost.failed)
        val noVoice = TrialJudge.judge(all, good, good.copy(services = setOf("smsip")), emptyList(), null)
        assertTrue(TestClass.VOICE in noVoice.failed && TestClass.SMS !in noVoice.failed)
        assertEquals(setOf(TestClass.OTHER), TrialJudge.judge(all, good, good.copy(dataValidated = false), emptyList(), null).failed)
        assertEquals(setOf(TestClass.WIFI_CALLING), TrialJudge.judge(all, good, good, listOf(CallEvidence("iwlan", CallEvidence.FAILED)), null).failed)
        assertEquals(TrialOutcome.NOT_COUNTED, TrialJudge.judge(all, good, good, listOf(CallEvidence("lte", CallEvidence.NOT_COUNTED)), null).outcome)
        val l3 = TrialJudge.judge(all, good, good, listOf(CallEvidence("lte", CallEvidence.PASSED, "L2"), CallEvidence("lte", CallEvidence.PASSED, "L3")), null)
        assertEquals("L3", l3.level)
        assertEquals("sms_both_ways", TrialJudge.judge(setOf(TestClass.SMS), good, good, emptyList(), true).level)
        assertEquals("no_regression", TrialJudge.judge(setOf(TestClass.OTHER), good, good, emptyList(), null).level)
    }

    private fun changes(n: Int, cls: (Int) -> String = { "imsvoice.k${it}_int" }) =
        (0 until n).map { Changed(cls(it), JsonPrimitive(it), JsonPrimitive(it + 100), "ocd:p") }

    private fun pass(reason: String = "ok") = TrialVerdict(TrialOutcome.PASS, reason = reason, level = "L2", bearer = "lte")
    private fun fail(vararg classes: TestClass) = TrialVerdict(TrialOutcome.MEASURED_FAIL, classes.toSet().ifEmpty { setOf(TestClass.VOICE) }, "calls out failed")

    /**
     * Runs a campaign against an oracle: a trial fails when [culprit] holds its new value and
     * the candidate that would fix it ([fix]) is not applied. Returns the settlements and the plans.
     */
    private fun drive(c0: Campaign, culprit: String?, fix: JsonElement?, candidates: (Changed) -> List<Candidate>): Pair<List<Settlement>, List<TrialPlan>> {
        var c = c0
        val out = mutableListOf<Settlement>()
        val plans = mutableListOf<TrialPlan>()
        while (!c.done) {
            val plan = CampaignPlanner.next(c) ?: break
            plans += plan
            val value = plan.overrides[culprit]
            val broken = culprit != null && (value == null || (value.origin != Candidate.SPLIT_OLD && !TypedValues.same(value.value, fix)))
            val verdict = if (broken) fail(KeyClasses.of(culprit!!)) else pass()
            val (next, settled) = CampaignPlanner.advance(c, verdict, candidates, now)
            out += settled
            c = next
        }
        return out to plans
    }

    @Test
    fun `all changed values pass together - one trial, no override, done`() {
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", changes(6), emptySet(), now)
        val (settled, plans) = drive(c, null, null) { emptyList() }
        assertEquals(1, plans.size)
        assertTrue(plans.single().overrides.isEmpty())
        assertTrue(settled.single() is Settlement.Passed)
    }

    @Test
    fun `a failing batch is split to the value at fault, and the first candidate that passes wins`() {
        val ch = changes(8)
        val culprit = ch[5].key
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", ch, emptySet(), now)
        val cands = listOf(Candidate(JsonPrimitive(1), "alternative:a"), Candidate(JsonPrimitive(2), "google"), Candidate(null, Candidate.ANDROID_DEFAULT))
        val (settled, plans) = drive(c, culprit, JsonPrimitive(2)) { if (it.key == culprit) cands else emptyList() }
        val winner = settled.filterIsInstance<Settlement.Winner>().single()
        assertEquals(culprit, winner.key)
        assertEquals(JsonPrimitive(2), winner.candidate.value)
        // Batch, three halvings of eight, two candidates, the batch again with the winner.
        assertEquals(1 + 3 + 2 + 1, plans.size)
        assertTrue(settled.last() is Settlement.Passed)
        // The split holds the other half at its old value, for the split only; never served or recorded.
        assertTrue(plans.drop(1).take(3).all { p -> p.overrides.values.all { it.origin == Candidate.SPLIT_OLD } })
    }

    @Test
    fun `a value counts as failed only when another value passes - otherwise an outage, the newest value stays and the record reads no value passed`() {
        val ch = changes(4)
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", ch, emptySet(), now)
        val cands = listOf(Candidate(JsonPrimitive(1), "a"), Candidate(null, Candidate.ANDROID_DEFAULT))
        val (settled, _) = drive(c, ch[1].key, JsonPrimitive(99)) { cands }
        assertTrue(settled.none { it is Settlement.Winner })
        val none = settled.filterIsInstance<Settlement.NoValuePassed>().single()
        assertEquals(ch[1].key, none.key)
        assertEquals(cands, none.tried)
    }

    @Test
    fun `not counted leaves the campaign where it was, for the next idle moment`() {
        val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", changes(4), emptySet(), now)
        val (next, settled) = CampaignPlanner.advance(c, TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "no service"), { emptyList() }, now)
        assertEquals(CampaignPhase.BATCH, next.phase)
        assertEquals(1, next.trials)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `600 random campaigns - the culprit is found, the first passing candidate wins, and no override ever names group A`() {
        val random = Random(30)
        repeat(600) {
            val n = random.nextInt(1, 40)
            val ch = (0 until n).map { i ->
                val key = when (random.nextInt(5)) {
                    0 -> "imssms.k${i}_bool"
                    1 -> "iwlan.k${i}_int"
                    2 -> "carrier_k${i}_int"
                    3 -> "imsemergency.k$i"
                    else -> "imsvoice.k${i}_int"
                }
                Changed(key, JsonPrimitive(i), JsonPrimitive(i + 100))
            }
            val c = CampaignPlanner.start("c", sim, "commit", "sha256:d", ch, emptySet(), now)
            assertTrue(c.changes.none { it.key.startsWith("imsemergency.") })
            val testable = c.changes
            if (testable.isEmpty()) return@repeat
            val culprit = testable[random.nextInt(testable.size)].key
            val cands = (0 until random.nextInt(1, 4)).map { Candidate(JsonPrimitive(1000 + it), "a$it") }
            val fixAt = random.nextInt(cands.size + 1)
            val fix = cands.getOrNull(fixAt)?.value ?: JsonPrimitive(-1)
            val (settled, plans) = drive(c, culprit, fix) { cands }
            assertTrue(plans.all { p -> p.overrides.keys.none { k -> TrialGuard.problem(k) != null } })
            if (fixAt < cands.size) {
                val w = settled.filterIsInstance<Settlement.Winner>().single()
                assertEquals(culprit, w.key)
                assertEquals(cands[fixAt], w.candidate)
            } else {
                assertTrue(settled.none { it is Settlement.Winner })
                assertEquals(culprit, settled.filterIsInstance<Settlement.NoValuePassed>().single().key)
            }
            assertTrue(plans.size <= CampaignPlanner.MAX_TRIALS)
        }
    }

    @Test
    fun `X5 - an off is released only by a call ACTIVE on the remaining path, or an SMS delivered`() {
        assertEquals("iwlan", X5Test.remainingBearer("carrier_volte_available_bool"))
        assertEquals("lte", X5Test.remainingBearer("carrier_wfc_ims_available_bool"))
        assertTrue(X5Test.passed("carrier_volte_available_bool", listOf(CallEvidence("iwlan", CallEvidence.PASSED, "L2")), null))
        assertFalse(X5Test.passed("carrier_volte_available_bool", listOf(CallEvidence("lte", CallEvidence.PASSED, "L2")), null))
        // "Registered" never proves a voice off: no call, no release.
        assertFalse(X5Test.passed("carrier_volte_available_bool", emptyList(), true))
        assertTrue(X5Test.passed("imssms.sms_over_ims_supported_bool", emptyList(), true))
        assertFalse(X5Test.passed("imssms.sms_over_ims_supported_bool", emptyList(), null))
    }

    @Test
    fun `voice loss - only while an installed dataset serves, the build's bundle is the final candidate, without group A`() {
        val serving = DatasetStatus(available = true, protocol = 2, installed = DatasetRef("c"), serving = "installed")
        assertTrue(VoiceLoss.applies(true, serving))
        assertFalse(VoiceLoss.applies(false, serving))
        assertFalse(VoiceLoss.applies(null, serving))
        assertFalse(VoiceLoss.applies(true, serving.copy(serving = "build")))
        assertFalse(VoiceLoss.applies(true, serving.copy(safeMode = SafeMode(true))))
        assertFalse(VoiceLoss.applies(true, DatasetStatus.absent("x")))
        val served = mapOf(
            "carrier_volte_available_bool" to ExplainKey(JsonPrimitive(false), "ocd:p"),
            "imsvoice.x_int" to ExplainKey(JsonPrimitive(9), "ocd:p"),
            "only_served_int" to ExplainKey(JsonPrimitive(1), "google_fill:x"),
            "imsemergency.y_int" to ExplainKey(JsonPrimitive(1), "group_a:google"),
            "same_bool" to ExplainKey(JsonPrimitive(true), "ocd:p"),
        )
        val build = mapOf(
            "carrier_volte_available_bool" to ExplainKey(JsonPrimitive(true), "ocd:p"),
            "imsvoice.x_int" to ExplainKey(JsonPrimitive(5), "ocd:p"),
            "imsemergency.y_int" to ExplainKey(JsonPrimitive(2), "group_a:google"),
            "same_bool" to ExplainKey(JsonPrimitive(true), "ocd:p"),
        )
        val o = VoiceLoss.buildOverrides(served, build, emptySet())
        assertEquals(setOf("carrier_volte_available_bool", "imsvoice.x_int", "only_served_int"), o.keys)
        assertEquals(JsonPrimitive(true), o["carrier_volte_available_bool"]!!.value)
        assertTrue(o["only_served_int"]!!.androidDefault)
        val order = VoiceLoss.suspects(listOf(Changed("mmsc_url_string"), Changed("imsvoice.a_int"), Changed("iwlan.b_int"), Changed("imssms.c_bool")))
        assertEquals(listOf("imsvoice.a_int", "iwlan.b_int", "mmsc_url_string"), order.map { it.key })
    }

    @Test
    fun `caps - 6 re-registrations an hour, 30 a day, 10 dialled calls a day, per SIM`() {
        val key = sim.key
        var events = emptyList<CapEvent>()
        assertEquals(6, TestCaps.reregistrationsLeft(events, key, now))
        assertEquals(5, TestCaps.trialsLeft(events, key, now))
        events = TestCaps.add(events, key, CapEvent.REREGISTRATION, now, 6)
        assertEquals(0, TestCaps.reregistrationsLeft(events, key, now))
        // An hour later the hourly cap is free again, the daily one counts on.
        assertEquals(6, TestCaps.reregistrationsLeft(events, key, now + TestCaps.HOUR))
        repeat(4) { h -> events = TestCaps.add(events, key, CapEvent.REREGISTRATION, now + (h + 1) * TestCaps.HOUR, 6) }
        assertEquals(0, TestCaps.reregistrationsLeft(events, key, now + 5 * TestCaps.HOUR + 1))
        // Another SIM has its own caps.
        assertEquals(6, TestCaps.reregistrationsLeft(events, SimKey(4, "26202", null), now))
        val call = CallRecord(now, 0, key, CallMethod.MAILBOX_CALL, CallDirection.OUTGOING, rang = false, answered = true, active = true, build = "b", stack = "s")
        assertEquals(0, TestCaps.callsLeft(List(10) { call }, key, now + 1))
        assertEquals(10, TestCaps.callsLeft(List(10) { call.copy(method = CallMethod.REAL_CALL) }, key, now + 1))
    }

    private val idle = IdleInputs(false, 5 * 60_000L, false, null, false, false, false, false, null, 2 * 3_600_000L, 80, false, false, now)

    @Test
    fun `the idle moment - each of testsDecide's conditions, and no night or Wi-Fi requirement`() {
        assertNull(IdleMoment.blocker(idle))
        assertEquals(IdleBlock.SCREEN_ON, IdleMoment.blocker(idle.copy(interactive = true)))
        assertEquals(IdleBlock.SCREEN_ON_RECENTLY, IdleMoment.blocker(idle.copy(screenOffForMs = 60_000)))
        assertEquals(IdleBlock.IN_CALL, IdleMoment.blocker(idle.copy(inCall = null)))
        assertEquals(IdleBlock.CALL_RECENT, IdleMoment.blocker(idle.copy(lastCallSeenAtMs = now - 9 * 60_000)))
        assertNull(IdleMoment.blocker(idle.copy(lastCallSeenAtMs = now - 11 * 60_000)))
        assertEquals(IdleBlock.MEDIA, IdleMoment.blocker(idle.copy(recording = true)))
        assertEquals(IdleBlock.EMERGENCY_MODE, IdleMoment.blocker(idle.copy(emergencySmsMode = null)))
        assertEquals(IdleBlock.EMERGENCY_RECENT, IdleMoment.blocker(idle.copy(emergencySeenAtMs = now - 59 * 60_000)))
        assertEquals(IdleBlock.EMERGENCY_RECENT, IdleMoment.blocker(idle.copy(upMs = 30 * 60_000)))
        assertEquals(IdleBlock.BATTERY_LOW, IdleMoment.blocker(idle.copy(batteryPercent = 19)))
        assertNull(IdleMoment.blocker(idle.copy(batteryPercent = 19, charging = true)))
        assertEquals(IdleBlock.ROAMING, IdleMoment.blocker(idle.copy(roaming = true)))
    }

    @Test
    fun `tests are due for a new SIM, a new build or stack, and every 14 days`() {
        val key = sim.key
        assertEquals("a new SIM", TestsDue.due(key, "b1", emptyList(), now))
        val ended = listOf(CampaignEnd(key.subId, key.mccmnc, now, "b1", "c", "ok"))
        assertNull(TestsDue.due(key, "b1", ended, now + 86_400_000))
        assertEquals("a new build or IMS stack", TestsDue.due(key, "b2", ended, now + 86_400_000))
        assertEquals("14 days since the last test", TestsDue.due(key, "b1", ended, now + TestsDue.RETEST_MS))
    }

    @Test
    fun `an install's campaign tests what its changes touch, a re-test runs every test`() {
        val install = CampaignPlanner.start("c", sim, "commit", "d", listOf(Changed("mmsc_url_string")), emptySet(), now)
        assertEquals(setOf(TestClass.OTHER), CampaignPlanner.next(install)!!.classes)
        val retest = CampaignPlanner.start("c", sim, "commit", "d", emptyList(), emptySet(), now, why = "14 days since the last test")
        assertTrue(CampaignPlanner.next(retest)!!.classes.containsAll(setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS)))
        // The batch's JSON survives the state file.
        val text = Json.encodeToString(Campaign.serializer(), install)
        assertEquals(install, Json.decodeFromString(Campaign.serializer(), text))
        assertTrue(Json.parseToJsonElement(text) is JsonObject)
    }
}
