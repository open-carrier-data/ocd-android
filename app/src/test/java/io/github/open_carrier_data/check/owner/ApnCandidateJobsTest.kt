package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnCandidateJobs
import io.github.open_carrier_data.check.owner.core.ApnCandidateProtocol
import io.github.open_carrier_data.check.owner.core.ApnCandidateTiming
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.JobClock
import io.github.open_carrier_data.check.owner.core.ProviderReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.4.3: the APN candidate set followed until CarrierSettings reports its end, within its own timeout less a margin,
 * and restored at once when the app gives up (0.4.2 gave up after a fixed 180 s and left a set that went in place
 * later to CarrierSettings's 10-minute timeout); a refused job's reason logged with its error, cleaned of
 * credentials, phone numbers, IMSIs and ICCIDs (0.4.2 logged the error only).
 */
class ApnCandidateJobsTest {
    /** The phone's elapsed clock; [stopAt]: the work is stopped (once) in the first wait at or after it. */
    private class Clock(var now: Long = 0L, var stopAt: Long? = null) : JobClock {
        override fun uptimeMs(): Long = now
        override suspend fun sleep(ms: Long) {
            now += ms
            val stop = stopAt
            if (stop != null && now >= stop) {
                stopAt = null
                throw CancellationException("the work was stopped")
            }
        }
    }

    private fun answer(vararg members: Pair<String, Any?>) = DataAnswer(
        JsonObject(
            members.filter { it.second != null }.associate { (k, v) ->
                k to when (v) {
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        ),
    )

    /**
     * CarrierSettings with one candidate set job `set1`, whose `install_status` is [set] at the clock's time, and
     * restore jobs that end `restored` ([restoreRefusal]: `apn_candidate_restore` refused with it).
     */
    private inner class Provider(
        val clock: Clock,
        val setAnswer: DataAnswer = answer("ok" to true, "job_id" to "set1"),
        val restoreRefusal: Pair<String, String>? = null,
        val set: (Long) -> DataAnswer,
    ) : DatasetClient by DatasetClient.Absent {
        val restoresAt = mutableListOf<Long>()
        val statusCalls = mutableListOf<String>()

        override fun apnCandidateSet(subId: Int, candidate: String): DataAnswer = setAnswer

        override fun installStatus(id: String): DataAnswer {
            statusCalls += id
            return if (id == "set1") set(clock.now) else answer("ok" to true, "install_id" to id, "kind" to "apn_candidate_restore", "state" to InstallState.RESTORED)
        }

        override fun apnCandidateRestore(): DataAnswer {
            restoresAt += clock.now
            return restoreRefusal?.let { (error, reason) -> answer("ok" to false, "error" to error, "reason" to reason) }
                ?: answer("ok" to true, "job_id" to "restore${restoresAt.size}")
        }
    }

    private fun job(state: String, error: String? = null, reason: String? = null) =
        answer("ok" to true, "install_id" to "set1", "kind" to "apn_candidate_set", "state" to state, "error" to error, "reason" to reason)

    private fun run(p: Provider): ApnCandidateJobs.SetEnd = runBlocking {
        var placed = 0
        ApnCandidateJobs.set(p, 1, "{}", p.clock, placed = { placed++ }, restore = { ApnCandidateJobs.restore(p, p.clock) {} }).also {
            assertEquals("a set CarrierSettings took counts toward the caps once", if (p.setAnswer.ok) 1 else 0, placed)
        }
    }

    // --- 1: the wait ---------------------------------------------------------------------------

    @Test
    fun `a set that goes in place after 180 s is followed until in_place, not given up`() {
        // CarrierSettings's install takes up to Engine.CANDIDATE_INSTALL_MS (360 s), plus the compose before it.
        for (inPlaceAt in listOf(181_000L, 250_000L, 400_000L, ApnCandidateTiming.SET_WAIT_MS - 2_000)) {
            val p = Provider(Clock()) { t -> job(if (t < inPlaceAt) "apn_confirming" else InstallState.IN_PLACE) }
            val end = run(p)
            assertEquals("in place at ${inPlaceAt / 1000} s", ApnCandidateJobs.SetEnd.InPlace("set1"), end)
            assertTrue("no restore while the set is followed (in place at ${inPlaceAt / 1000} s)", p.restoresAt.isEmpty())
        }
    }

    @Test
    fun `a set that does not end - the app gives up before CarrierSettings's own timeout and asks for the restore at once`() {
        val p = Provider(Clock()) { job("apn_confirming") }
        val end = run(p)
        assertTrue(end is ApnCandidateJobs.SetEnd.NotRun)
        assertEquals("the app asks for the restore itself, once", 1, p.restoresAt.size)
        val at = p.restoresAt.single()
        assertTrue("not before CarrierSettings's longest install (${at / 1000} s)", at >= ApnCandidateTiming.INSTALL_MS)
        assertTrue(
            "before CarrierSettings's own timeout less the margin (${at / 1000} s)",
            at <= ApnCandidateTiming.PROVIDER_TIMEOUT_MS - ApnCandidateTiming.MARGIN_MS,
        )
        val line = (end as ApnCandidateJobs.SetEnd.NotRun).line
        assertTrue(line, "had not ended after 900 s (apn_confirming)" in line && "asked for the restore at once: restored" in line)
        // The restore was followed to its end.
        assertTrue("restore1" in p.statusCalls)
    }

    @Test
    fun `a restore CarrierSettings refuses while it still installs leaves the set to its own time, and says so`() {
        val p = Provider(Clock(), restoreRefusal = "busy" to "the candidate set is still being installed") { job("apn_installing") }
        val line = (run(p) as ApnCandidateJobs.SetEnd.NotRun).line
        assertEquals(1, p.restoresAt.size)
        assertTrue(line, "refused (busy: the candidate set is still being installed); CarrierSettings restores it at its own time" in line)
    }

    @Test
    fun `the work stopped while the set is followed - the restore is asked for at once`() {
        val clock = Clock(stopAt = 300_000L)
        val p = Provider(clock) { job("apn_confirming") }
        try {
            run(p)
            fail("the stop goes on to the caller")
        } catch (e: CancellationException) {
            assertEquals("the work was stopped", e.message)
        }
        assertEquals(listOf(300_000L), p.restoresAt)
    }

    @Test
    fun `a job CarrierSettings lost is restored at once, a refused or rolled back one is not`() {
        val lost = Provider(Clock()) { answer("ok" to false, "error" to "not_found", "reason" to "no install job set1") }
        val line = (run(lost) as ApnCandidateJobs.SetEnd.NotRun).line
        assertEquals(1, lost.restoresAt.size)
        assertTrue(line, "lost the candidate set's job (not_found: no install job set1)" in line)
        val interrupted = Provider(Clock()) { job("interrupted") }
        run(interrupted)
        assertEquals(1, interrupted.restoresAt.size)

        for (state in listOf(InstallState.REFUSED, InstallState.APN_NOT_CONFIRMED)) {
            val p = Provider(Clock()) { t -> job(if (t < 30_000) "composing" else state, error = state, reason = "x") }
            assertTrue(run(p) is ApnCandidateJobs.SetEnd.NotRun)
            assertTrue("$state: CarrierSettings never installed it or put the installed set back itself", p.restoresAt.isEmpty())
        }
    }

    @Test
    fun `a restore is followed past 0_4_2's 300 s - CarrierSettings's install and its checks' wait`() = runBlocking {
        val clock = Clock()
        val p = object : DatasetClient by DatasetClient.Absent {
            override fun apnCandidateRestore() = answer("ok" to true, "job_id" to "r")
            override fun installStatus(id: String) = answer("ok" to true, "state" to if (clock.now < 420_000) "apn_confirming" else InstallState.RESTORED)
        }
        var counted = 0
        val r = ApnCandidateJobs.restore(p, clock) { counted++ }
        assertEquals(InstallState.RESTORED, r.line)
        assertEquals(1, counted)
        // A restore that waits for a call is left to CarrierSettings after the bound, and says so.
        val c2 = Clock()
        val waiting = object : DatasetClient by DatasetClient.Absent {
            override fun apnCandidateRestore() = answer("ok" to true, "job_id" to "r")
            override fun installStatus(id: String) = answer("ok" to true, "state" to InstallState.WAITING, "waiting_for" to "call")
        }
        val w = ApnCandidateJobs.restore(waiting, c2) {}
        assertEquals(ApnCandidateTiming.RESTORE_WAIT_MS, c2.now)
        assertEquals("not ended after 540 s (waiting, waiting for a call is up); CarrierSettings finishes it", w.line)
        assertFalse(w.failed)
    }

    /** The app's timing is CarrierSettings's own: read from the provider's source in this repository. */
    @Test
    fun `the timing is OcdCarrierSettings's Engine's`() {
        val engine = File("../carriersettings/ocd/src/com/android/carriersettings/ocd/Engine.kt").readText()
        val names = mutableMapOf<String, Long>()
        fun value(expr: String): Long {
            val e = expr.trim()
            Regex("^([0-9_]+)L?$").find(e)?.let { return it.groupValues[1].replace("_", "").toLong() }
            Regex("^TimeUnit\\.MINUTES\\.toMillis\\((\\d+)\\)$").find(e)?.let { return it.groupValues[1].toLong() * 60_000 }
            Regex("^(\\d+) \\* ([A-Z_]+)$").find(e)?.let { return it.groupValues[1].toLong() * names.getValue(it.groupValues[2]) }
            throw AssertionError("an expression this test does not read: $e")
        }
        fun constant(name: String): Long {
            val m = Regex("val $name(?:: Long)? = (.+)").find(engine) ?: throw AssertionError("Engine.kt has no $name")
            return value(m.groupValues[1]).also { names[name] = it }
        }
        assertEquals(ApnCandidateTiming.CONFIRM_MS, constant("CONFIRM_MS"))
        assertEquals(ApnCandidateTiming.INSTALL_MS, constant("CANDIDATE_INSTALL_MS"))
        assertEquals(ApnCandidateTiming.MAX_MS, constant("CANDIDATE_MAX_MS"))
        assertEquals(ApnCandidateTiming.AFTER_MS, constant("AFTER_MS"))
        // The stored trial's own timeout, and the one after the set went in place.
        assertTrue(Regex("now \\+ CANDIDATE_INSTALL_MS \\+ CANDIDATE_MAX_MS").containsMatchIn(engine))
        assertTrue(Regex("val until = p\\.nowMs\\(\\) \\+ CANDIDATE_MAX_MS").containsMatchIn(engine))
        assertEquals(ApnCandidateTiming.INSTALL_MS + ApnCandidateTiming.MAX_MS, ApnCandidateTiming.PROVIDER_TIMEOUT_MS)
        assertTrue(ApnCandidateTiming.SET_WAIT_MS in (ApnCandidateTiming.INSTALL_MS + 1) until ApnCandidateTiming.PROVIDER_TIMEOUT_MS)
        // The end states the app waits for are ones CarrierSettings's install_status reports as ends.
        val ends = Regex("val final = setOf\\(([^)]*)\\)").find(engine)!!.groupValues[1].split(',').map { it.trim().trim('"') }.toSet()
        assertTrue(ends.containsAll(ApnCandidateProtocol.SET_FINAL))
        assertTrue(ends.containsAll(ApnCandidateProtocol.RESTORE_FINAL))
        // The set's own states, as runCandidateSet writes them.
        for (s in listOf("composing", "in_place")) assertTrue(s, Regex("job\\.state = \"$s\"").containsMatchIn(engine))
    }

    // --- 2: the reason ---------------------------------------------------------------------------

    @Test
    fun `a refused set says its reason with its error`() {
        val reason = "the inserted row a:0123456789abcdef would not be one of this SIM's rows"
        val p = Provider(Clock()) { t -> job(if (t < 10_000) "composing" else InstallState.REFUSED, error = "candidate_invalid", reason = reason) }
        val line = (run(p) as ApnCandidateJobs.SetEnd.NotRun).line
        assertEquals("not run: the candidate set did not go in place (refused, candidate_invalid: $reason)", line)
        // apn_not_confirmed: the state and the error are one word; the reason once.
        val rolled = Provider(Clock()) { job(InstallState.APN_NOT_CONFIRMED, error = "apn_not_confirmed", reason = "the file did not show (3 rows lack types of the file); the previous file was installed again") }
        assertEquals(
            "not run: the candidate set did not go in place (apn_not_confirmed, the file did not show (3 rows lack types of the file); the previous file was installed again)",
            (run(rolled) as ApnCandidateJobs.SetEnd.NotRun).line,
        )
    }

    @Test
    fun `nothing logged carries a credential, a phone number, an IMSI or an ICCID`() {
        val bad = "row a:9876543210fedcba user=\"eplus\" password=\"gprs\" \"user\":\"web\",\"password\":\"internet\" password: hunter2 " +
            "for +49 176 12345678, 0176 1234 5678, IMSI 262031234567890, ICCID 8949226000001234567F, sip:+4917612345678@ims.example.org, tel:+4917612345678, owner@example.org"
        val secrets = listOf("eplus", "gprs", "\"web\"", "internet", "hunter2", "12345678", "1234 5678", "262031234567890", "8949226000001234567", "4917612345678", "owner@example.org")
        fun clean(text: String) = secrets.filter { it in text }

        val cleaned = ProviderReason.clean(bad, 1_000)!!
        assertEquals("credentials and numbers left: ${clean(cleaned)} in $cleaned", emptyList<String>(), clean(cleaned))
        assertTrue("the row key stays: $cleaned", "a:9876543210fedcba" in cleaned)
        assertTrue(cleaned, "user=<removed>" in cleaned && "\"password\":<removed>" in cleaned)
        // What CarrierSettings writes stays readable: row keys, counts, codes.
        assertEquals("3 rows lack types of the file (1 of 2), sim_loses_ims", ProviderReason.clean("3 rows lack types of the file (1 of 2), sim_loses_ims"))
        assertEquals("candidate_invalid: row a:00000000000000aa is on 2 lines", ProviderReason.of("candidate_invalid", "row a:00000000000000aa is on 2 lines"))

        // Every path that logs a provider's words: the job's reason, the refused call's, the refused restore's.
        val job = Provider(Clock()) { job(InstallState.REFUSED, error = "storage", reason = bad) }
        val refusedCall = Provider(Clock(), setAnswer = answer("ok" to false, "error" to "candidate_invalid", "reason" to bad)) { job(InstallState.REFUSED) }
        val refusedRestore = Provider(Clock(), restoreRefusal = "busy" to bad) { job("apn_confirming") }
        for (p in listOf(job, refusedCall, refusedRestore)) {
            val line = (run(p) as ApnCandidateJobs.SetEnd.NotRun).line
            assertEquals("left in \"$line\"", emptyList<String>(), clean(line))
            assertTrue(line, "a:9876543210fedcba" in line)
        }
    }
}
