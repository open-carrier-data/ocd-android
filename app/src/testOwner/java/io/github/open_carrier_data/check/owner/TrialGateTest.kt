package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 31 (SM9, which narrowed round 29's A4 first-run opt-in to the active tests): the owner
 * build runs the round 29 setting trials (a non-persistent override, a temporary access point row, the
 * restart check's override) as before; the rom build only after the person's opt-in. Until now a rom
 * install with no dataset installer (the switch off, an old CarrierSettings) fell through the quiet job
 * to `OwnerScheduler.runPlanned` and "Test now" queued the same chain, and nothing on the way read
 * `activeTests`, while every round 30 and 31 path (the campaigns, the candidates, the calls, the SMS,
 * the emergency data test, the probe ladder) did. The rom build's cases are in src/testRom (TrialGateTest there).
 */
class TrialGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    private val sources = File("src/owner/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val work = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerWork.kt").readText()

    @Test
    fun `the owner build runs the setting trials with no opt-in`() {
        assertEquals(TesterBuild.OWNER, OwnerTester.build)
        assertTrue(OwnerTester.trialsAllowed(OwnerState()))
        // The opt-in is the rom build's; the owner build does not use it.
        assertTrue(OwnerTester.trialsAllowed(optedIn))
    }

    @Test
    fun `the three builds' cases, by the policy`() {
        assertTrue(TesterPolicy(TesterBuild.OWNER).activeTests)
        assertFalse(TesterPolicy(TesterBuild.ROM, optIn = false).activeTests)
        assertTrue(TesterPolicy(TesterBuild.ROM, optIn = true).activeTests)
    }

    @Test
    fun `the gate is the active-tests rule the probe ladder, the campaigns and the policy follow, in whatever build this is`() {
        for (state in listOf(OwnerState(), optedIn)) {
            assertEquals(TesterPolicy(OwnerTester.build, state.activeTests.on).activeTests, OwnerTester.trialsAllowed(state))
            assertEquals(OwnerProbe.allowed(state), OwnerTester.trialsAllowed(state))
        }
    }

    @Test
    fun `each step that starts a trial checks the gate first`() {
        val feature = work.substringAfter("private suspend fun feature(store: FileStateStore): Result {")
        val apn = work.substringAfter("private suspend fun apn(store: FileStateStore): Result {")
        for ((name, body) in listOf("feature" to feature, "apn" to apn)) {
            assertTrue(name, body.trimStart().startsWith("requireTrials(store)"))
        }
        assertTrue(work.contains("STEP_PROBE -> {\n                    requireTrials(store)\n                    withPhone(store) { runner, sims, _ ->"))
        // The gate refuses with the policy's own reason, and before a phone is opened.
        val gate = work.substringAfter("private fun requireTrials(store: FileStateStore) {").substringBefore("\n    }")
        assertTrue(gate, gate.contains("if (!OwnerTester.trialsAllowed(store.read())) throw SafetyException(TesterPolicy.OPT_IN_OFF,"))
    }

    @Test
    fun `nothing else starts a trial, and a revert never asks`() {
        // The runner's three trial entry points are called from those three steps only.
        for (call in listOf("runner.testFeature(", "runner.testApns(", "runner.rebootProbe(")) {
            assertEquals(call, 1, sources.sumOf { Regex(Regex.escape(call)).findAll(it.readText()).count() })
            assertEquals(call, 1, Regex(Regex.escape(call)).findAll(work).count())
        }
        // The chain is queued from the quiet job and from "Test now" only, both behind the gate (below).
        assertEquals(1, Regex("OwnerScheduler\\.runPlanned\\(").findAll(work).count())
        assertEquals(1, sources.sumOf { Regex("OwnerScheduler\\.rebootProbe\\(").findAll(it.readText()).count() })
        // The cleanup, the reconcile and the provisional revert take a change back and have no gate.
        val cleanup = work.substringAfter("private suspend fun cleanup(store: FileStateStore): Result {").substringBefore("private suspend fun quiet(")
        assertFalse(cleanup.contains("requireTrials") || cleanup.contains("trialsAllowed"))
    }

    @Test
    fun `the quiet job plans no trial before the gate, and the light check and the dataset step come before it`() {
        val quiet = work.substring(work.indexOf("private suspend fun quiet("), work.indexOf("private suspend fun callsOutDue("))
        val gate = quiet.indexOf("if (!OwnerTester.trialsAllowed(store.read())) {")
        assertTrue(gate > 0)
        assertEquals("one gate", gate, quiet.lastIndexOf("if (!OwnerTester.trialsAllowed(store.read())) {"))
        // The passive reads and the round 30 dataset step (gated on activeTests inside) are not cut off by it.
        for (before in listOf("diagnoseCauses(store, phone, sims)", "OwnerDatasetStep.run(context, store, phone, sims)", "OwnerEmergencyData.session(", "if (round30) {")) {
            assertTrue(before, quiet.indexOf(before) in 0 until gate)
        }
        // Everything of the round 29 chain comes after: the plan, the mailbox calls, the chain.
        for (after in listOf("QuietPlan.plan(", "callsOutDue(store, sims, phone", "OwnerScheduler.runPlanned(", "OwnerScheduler.runCallsOut(")) {
            assertTrue(after, quiet.indexOf(after) > gate)
        }
        // The gate ends the run the way the older-app refusal does: told in the log and in the last result, nothing queued.
        val body = quiet.substring(gate, quiet.indexOf("return Result.success()", gate))
        assertTrue(body, body.contains("quietLog(store, \"check\", \"light check: no tests: the active tests are off on this phone"))
    }

    @Test
    fun `Test now queues no trial chain before the gate`() {
        val tests = work.substring(work.indexOf("suspend fun runTests("), work.indexOf("/** Whether the work of a unique name is still to run. */"))
        val gate = tests.indexOf("if (!OwnerTester.trialsAllowed(FileStateStore(context).read())) {")
        assertTrue(gate > 0)
        // The dataset installer's own path (gated on activeTests inside) comes first, the chain's steps after.
        assertTrue(tests.indexOf("if (status.available) {") in 0 until gate)
        assertTrue(tests.indexOf("beginUniqueWork(RUN") > gate)
        assertTrue(tests.substring(gate).contains("return \"off: the active tests are off on this phone\""))
        // The screen says so instead of "Couldn't start the test".
        val activity = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerActivity.kt").readText()
        assertTrue(activity.indexOf("line?.startsWith(\"off: \")") in 0 until activity.indexOf("line?.startsWith(\"queued\") != true -> say(\"Couldn't start the test\")"))
    }

    @Test
    fun `the restart check is offered in a rom build only with the opt-in`() {
        val screen = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerScreen.kt").readText()
        assertTrue(screen.contains("if (!state.rebootCleared && (owner || state.activeTests.on)) {\n        SectionTitle(\"Restart check\")"))
    }
}
