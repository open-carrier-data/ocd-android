package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetJobTiming
import io.github.open_carrier_data.check.owner.core.DatasetJobs
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.EmergencySetJobs
import io.github.open_carrier_data.check.owner.core.EmergencySetTiming
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.JobClock
import io.github.open_carrier_data.check.owner.core.ProviderTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.4.4: the two fixed waits 0.4.3 still had, both followed until CarrierSettings reports the job's end within a bound from
 * CarrierSettings's own source. The default emergency APN test's temporary set (0.4.3: 180 s, and a set that went in place
 * later stayed without the SIM's emergency rows until CarrierSettings's 30-minute check restored it as a stop event; its
 * restore followed for 300 s) and the dataset's activation (0.4.3: 300 s, for a job of up to 420 s).
 */
class ProviderJobsTest {
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
        JsonObject(members.filter { it.second != null }.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    private fun job(state: String, error: String? = null, reason: String? = null, waitingFor: String? = null) =
        answer("ok" to true, "install_id" to "j", "state" to state, "error" to error, "reason" to reason, "waiting_for" to waitingFor, "rows" to 19750)

    /**
     * CarrierSettings with the emergency default set's job `e1` ([set] at the clock's time) and its restore jobs ([restored] at
     * the clock's time). A restore asked while the set's job is not in place yet is refused `busy`, as Engine's is.
     */
    private class Emergency(
        val clock: Clock,
        val restored: (Long) -> DataAnswer = { DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(true), "state" to JsonPrimitive(InstallState.RESTORED)))) },
        val set: (Long) -> DataAnswer,
    ) : DatasetClient by DatasetClient.Absent {
        val restoresAt = mutableListOf<Long>()
        val accepted = mutableListOf<Long>()

        override fun emergencyDefaultSet(subId: Int) = DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(true), "job_id" to JsonPrimitive("e1"))))

        override fun installStatus(id: String): DataAnswer = if (id == "e1") set(clock.now) else restored(clock.now)

        override fun emergencyDefaultRestore(keep: Boolean): DataAnswer {
            restoresAt += clock.now
            val s = set(clock.now).str("state")
            if (s != InstallState.IN_PLACE && s in setOf("composing", "apn_installing", "apn_confirming")) {
                return DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive("busy"), "reason" to JsonPrimitive("the test set is still being installed"))))
            }
            accepted += clock.now
            return DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(true), "job_id" to JsonPrimitive("r${accepted.size}"))))
        }
    }

    private fun emergency(p: Emergency): EmergencySetJobs.SetEnd = runBlocking {
        var placed = 0
        EmergencySetJobs.set(p, 1, p.clock, placed = { placed++ }, restore = { until -> EmergencySetJobs.restore(p, p.clock, false, {}, retryBusyUntil = until) }).also {
            assertEquals("the set counts toward the caps once", 1, placed)
        }
    }

    // --- the default emergency APN test's set ------------------------------------------------------

    @Test
    fun `an emergency default set that goes in place after 180 s is followed until in_place, not given up`() {
        for (at in listOf(181_000L, 400_000L, EmergencySetTiming.SET_WAIT_MS - 2_000)) {
            val p = Emergency(Clock()) { t -> job(if (t < at) "apn_confirming" else InstallState.IN_PLACE) }
            val end = emergency(p)
            assertTrue("in place at ${at / 1000} s: ${end.notRun}", end.inPlace)
            assertNull(end.notRun)
            assertTrue("no restore while the set is followed (${at / 1000} s)", p.restoresAt.isEmpty())
        }
    }

    @Test
    fun `a set still installing at the bound - the restore is asked at once and again while CarrierSettings installs, before its deadline`() {
        // It goes in place at 25 minutes: the app gave up at 24, its restore is refused busy until then, and goes through.
        val late = Emergency(Clock()) { t -> job(if (t < 25 * 60_000L) "apn_confirming" else InstallState.IN_PLACE) }
        val end = emergency(late)
        assertFalse(end.inPlace)
        assertEquals("the first ask comes when the app gives up", EmergencySetTiming.SET_WAIT_MS, late.restoresAt.first())
        assertEquals(listOf(25 * 60_000L), late.accepted)
        assertTrue(end.notRun!!, "had not ended after 1440 s (apn_confirming)" in end.notRun!! && "asked for the restore at once: restored" in end.notRun!!)

        // It never goes in place: asked until CarrierSettings's deadline less the margin, then left to its check, and said so.
        val never = Emergency(Clock()) { job("apn_installing") }
        val line = emergency(never).notRun!!
        assertTrue(never.accepted.isEmpty())
        assertTrue("asked again (${never.restoresAt.size} times)", never.restoresAt.size > 1)
        assertTrue("the last ask comes before CarrierSettings's deadline less the margin", never.restoresAt.last() <= EmergencySetTiming.PROVIDER_TIMEOUT_MS - ProviderTiming.MARGIN_MS)
        assertTrue(line, "restore refused (busy: the test set is still being installed)" in line)
        assertTrue(line, "left to CarrierSettings: its check restores the set once it is in place over 30 minutes, as a stop event" in line)
    }

    @Test
    fun `the work stopped while the emergency default set is followed - the restore is asked for at once`() {
        val clock = Clock(stopAt = 200_000L)
        // In place by the time the stop comes, but not seen yet: the restore goes through.
        val p = Emergency(clock) { t -> job(if (t < 199_000L) "apn_confirming" else InstallState.IN_PLACE) }
        try {
            runBlocking { EmergencySetJobs.set(p, 1, clock, placed = {}, restore = { until -> EmergencySetJobs.restore(p, clock, false, {}, retryBusyUntil = until) }) }
            fail("the stop goes on to the caller")
        } catch (e: CancellationException) {
            assertEquals("the work was stopped", e.message)
        }
        assertEquals(listOf(200_000L), p.accepted)
    }

    @Test
    fun `a lost emergency default job is restored at once, a refused or rolled back one is not`() {
        val lost = Emergency(Clock()) { answer("ok" to false, "error" to "not_found", "reason" to "no install job e1") }
        val line = emergency(lost).notRun!!
        assertEquals(1, lost.restoresAt.size)
        assertTrue(line, "lost the set's job (not_found: no install job e1)" in line)
        for (state in listOf(InstallState.REFUSED, InstallState.APN_NOT_CONFIRMED)) {
            val p = Emergency(Clock()) { t -> job(if (t < 20_000) "composing" else state, error = if (state == InstallState.REFUSED) "user_edited_row" else state, reason = "not run: user-edited row") }
            val end = emergency(p)
            assertFalse(end.inPlace)
            assertTrue("$state: nothing to restore", p.restoresAt.isEmpty())
            assertTrue(end.notRun!!, "did not go in place ($state" in end.notRun!!)
        }
    }

    @Test
    fun `the emergency default restore is followed past 0_4_3's 300 s, and a restore waiting for a call is left after its bound`() = runBlocking {
        val clock = Clock()
        val p = Emergency(clock, restored = { t -> job(if (t < 420_000) "apn_confirming" else InstallState.RESTORED) }) { job(InstallState.IN_PLACE) }
        var counted = 0
        val r = EmergencySetJobs.restore(p, clock, keep = false, accepted = { counted++ })
        assertEquals(InstallState.RESTORED, r.state)
        assertFalse(r.timedOut)
        assertEquals(1, counted)
        val c2 = Clock()
        val waiting = Emergency(c2, restored = { job(InstallState.WAITING, waitingFor = "call") }) { job(InstallState.IN_PLACE) }
        val w = EmergencySetJobs.restore(waiting, c2, keep = false, accepted = {})
        assertTrue(w.timedOut)
        assertEquals(EmergencySetTiming.RESTORE_WAIT_MS, c2.now)
        assertEquals("restore not ended after 540 s (waiting, waiting for a call is up); CarrierSettings finishes it", w.line)
    }

    // --- the dataset's activation and verification -------------------------------------------------

    private class Dataset(val clock: Clock, val state: (Long) -> DataAnswer) : DatasetClient by DatasetClient.Absent {
        val cancels = mutableListOf<String>()
        override fun installStatus(id: String) = state(clock.now)
        override fun cancel(id: String): DataAnswer {
            cancels += id
            return DataAnswer.absent("not called")
        }
    }

    @Test
    fun `an activation that ends after 300 s is followed until installed`() = runBlocking {
        // Engine.runActivation: the APN install (up to 360 s), the switch, the proof (up to 60 s).
        for (at in listOf(301_000L, 420_000L, DatasetJobTiming.ACTIVATION_WAIT_MS - 2_000)) {
            val clock = Clock()
            val p = Dataset(clock) { t -> job(if (t < at - 60_000) "apn_confirming" else if (t < at) "proving" else InstallState.INSTALLED) }
            val f = DatasetJobs.activation(p, "a1", clock)
            assertEquals("ended at ${at / 1000} s", InstallState.INSTALLED, f.state)
            assertNull(DatasetJobs.left(f, activation = true))
        }
        // reload_not_proven and a rolled back install end it too.
        for (end in listOf(InstallState.RELOAD_NOT_PROVEN, InstallState.APN_NOT_CONFIRMED, "apn_rollback_failed")) {
            val clock = Clock()
            assertEquals(end, DatasetJobs.activation(Dataset(clock) { t -> job(if (t < 100_000) "apn_confirming" else end) }, "a1", clock).state)
        }
    }

    @Test
    fun `an activation that does not end is left to CarrierSettings after its longest time and the margin, and the line says why`() = runBlocking {
        val clock = Clock()
        val p = Dataset(clock) { job("apn_confirming") }
        val f = DatasetJobs.activation(p, "a1", clock)
        assertTrue(f.timedOut)
        assertEquals(DatasetJobTiming.ACTIVATION_WAIT_MS, clock.now)
        assertTrue("a running activation is never cancelled", p.cancels.isEmpty())
        assertEquals(
            "; still apn_confirming after 480 s; left to CarrierSettings: a running activation cannot be cancelled (cancel takes a verifying or verified job only), so it finishes by itself and the next pass reads its end",
            DatasetJobs.left(f, activation = true),
        )
        // Verification: verified ends the wait; one still verifying is followed by the next pass.
        val c2 = Clock()
        assertEquals(InstallState.VERIFIED, DatasetJobs.verification(Dataset(c2) { t -> job(if (t < 100_000) "verifying" else InstallState.VERIFIED) }, "s1", c2).state)
        val c3 = Clock()
        val v = DatasetJobs.verification(Dataset(c3) { job("verifying") }, "s1", c3)
        assertTrue(DatasetJobs.left(v, activation = false)!!.contains("nothing is in place while it verifies, and the next pass follows it"))
    }

    // --- the bounds are CarrierSettings's own --------------------------------------------------------

    private val engine = File("../carriersettings/ocd/src/com/android/carriersettings/ocd/Engine.kt").readText()

    private fun constant(name: String, names: MutableMap<String, Long> = mutableMapOf()): Long {
        val m = Regex("val $name(?:: Long)? = (.+)").find(engine) ?: throw AssertionError("Engine.kt has no $name")
        val e = m.groupValues[1].trim()
        Regex("^([0-9_]+)L?$").find(e)?.let { return it.groupValues[1].replace("_", "").toLong() }
        Regex("^TimeUnit\\.MINUTES\\.toMillis\\((\\d+)\\)$").find(e)?.let { return it.groupValues[1].toLong() * 60_000 }
        Regex("^(\\d+) \\* ([A-Z_]+)$").find(e)?.let { return it.groupValues[1].toLong() * constant(it.groupValues[2]) }
        throw AssertionError("an expression this test does not read: $e")
    }

    /** The body of an Engine function, from its signature to the next function at the same depth. */
    private fun body(signature: String): String {
        val start = engine.indexOf(signature).also { assertTrue("Engine.kt has $signature", it >= 0) }
        val open = engine.indexOf('{', start)
        var depth = 0
        for (i in open until engine.length) {
            if (engine[i] == '{') depth++ else if (engine[i] == '}') depth--
            if (depth == 0) return engine.substring(start, i + 1)
        }
        throw AssertionError("unbalanced $signature")
    }

    @Test
    fun `the bounds are read from OcdCarrierSettings's Engine`() {
        assertEquals(ProviderTiming.CONFIRM_MS, constant("CONFIRM_MS"))
        assertEquals(ProviderTiming.PROOF_MS, constant("PROOF_MS"))
        assertEquals(ProviderTiming.AFTER_MS, constant("AFTER_MS"))
        assertEquals(ProviderTiming.CANDIDATE_MAX_MS, constant("CANDIDATE_MAX_MS"))
        assertEquals(ProviderTiming.APN_TEST_MAX_MS, constant("APN_TEST_MAX_MS"))
        assertEquals(ProviderTiming.INSTALL_MS, constant("CANDIDATE_INSTALL_MS"))

        // installApn: two sends with their confirmations, and the rollback's: three CONFIRM_MS waits at most.
        val install = body("fun installApn(")
        assertEquals(3, Regex("\\bconfirm\\(").findAll(install).count())
        assertTrue(Regex("val deadline = p\\.nowMs\\(\\) \\+ CONFIRM_MS").containsMatchIn(body("private fun confirm(")))
        assertEquals(3 * ProviderTiming.CONFIRM_MS, ProviderTiming.INSTALL_MS)

        // The emergency default set: stored (sinceMs) before it is sent; in place over APN_TEST_MAX_MS, the check restores it as a stop event.
        val testSet = body("private fun runTestSet(")
        assertTrue(Regex("apnTest = ApnTestSet\\(sim\\.subId, p\\.nowMs\\(\\), \"installing\"").containsMatchIn(testSet))
        assertTrue(testSet.indexOf("ApnTestSet(sim.subId") < testSet.indexOf("installApn("))
        val check = body("private fun bootCheckInner(")
        assertTrue(Regex("val stale = p\\.nowMs\\(\\) - t\\.sinceMs > APN_TEST_MAX_MS").containsMatchIn(check))
        assertTrue(Regex("stale && t\\.state == \"in_place\" -> .*restoreTestSet\\(null, keep = false, stopReason = ").containsMatchIn(check))
        // Its restore is refused busy while the set installs, and follows R31.9's restore (install and AFTER_MS).
        assertTrue(Regex("if \\(t\\.state == \"installing\"\\) throw Refusal\\(\"busy\"").containsMatchIn(body("fun emergencyDefaultRestore(")))
        assertTrue(Regex("val deadline = p\\.nowMs\\(\\) \\+ AFTER_MS").containsMatchIn(engine))

        // The activation: the APN install, then the proof (PROOF_MS); no cancel once it runs.
        val activation = body("private fun runActivation(")
        assertTrue(activation.indexOf("installApn(") in 0 until activation.indexOf("notifyAndProve("))
        assertTrue(Regex("val deadline = p\\.nowMs\\(\\) \\+ PROOF_MS").containsMatchIn(body("fun notifyAndProve(")))
        assertTrue(Regex("if \\(job\\.state !in setOf\\(\"verifying\", \"verified\"\\)\\) throw Refusal\\(\"not_verified\"").containsMatchIn(body("fun cancel(")))

        // The app's bounds from those.
        assertEquals(ProviderTiming.INSTALL_MS + ProviderTiming.PROOF_MS + ProviderTiming.MARGIN_MS, DatasetJobTiming.ACTIVATION_WAIT_MS)
        assertEquals(ProviderTiming.APN_TEST_MAX_MS - ProviderTiming.MARGIN_MS - EmergencySetTiming.SESSION_MS, EmergencySetTiming.SET_WAIT_MS)
        assertTrue("the set's wait covers its longest install", EmergencySetTiming.SET_WAIT_MS > ProviderTiming.INSTALL_MS)
        // The session after the set is in place: the sos check (60 s at the first use), the EIMS request and its release, 3 s, the readings and the record (two provider locks).
        val lock = constant("LOCK_MS")
        assertTrue(EmergencySetTiming.SESSION_MS >= 60_000 + EmergencyData.WAIT_MS + 1_000 + EmergencyData.RELEASE_MS + 3_000 + 2 * lock + 60_000)
        assertEquals(ProviderTiming.INSTALL_MS + ProviderTiming.AFTER_MS + ProviderTiming.MARGIN_MS, EmergencySetTiming.RESTORE_WAIT_MS)
    }
}
