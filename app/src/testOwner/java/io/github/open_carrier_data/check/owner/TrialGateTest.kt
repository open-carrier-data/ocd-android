package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.StackCalibration
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 *
 * SM8 (tests calibrated per IMS stack), the same gate: SM7 counts "a stack not yet calibrated" as no tester next to
 * "the person did not opt in", and the round 30 and 31 campaigns set no override on such a stack. The chain read the
 * opt-in only, so on PHH IMS or a vendor IMS it set overrides and recorded their registration verdicts in the entry
 * ledger and the pointer reports. It now asks the same [StackCalibration] and, with no dataset installer to say
 * which stack runs, reads it from the installed ImsService packages the reports and the calls already use.
 */
class TrialGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    private val calibrated = "aosp_imsstack"
    private val uncalibrated = listOf("phh_ims", "samsung_ims", "other", "not_known", "", null)

    private val sources = File("src/owner/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val work = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerWork.kt").readText()

    @Test
    fun `the owner build runs the setting trials with no opt-in, on the calibrated stack`() {
        assertEquals(TesterBuild.OWNER, OwnerTester.build)
        assertNull(OwnerTester.policy(OwnerState()).trialRefusal(calibrated))
        // The opt-in is the rom build's; the owner build does not use it.
        assertNull(OwnerTester.policy(optedIn).trialRefusal(calibrated))
    }

    @Test
    fun `the three builds' cases, by the policy`() {
        assertTrue(TesterPolicy(TesterBuild.OWNER).activeTests)
        assertFalse(TesterPolicy(TesterBuild.ROM, optIn = false).activeTests)
        assertTrue(TesterPolicy(TesterBuild.ROM, optIn = true).activeTests)
    }

    @Test
    fun `on the calibrated stack the gate is the active-tests rule the probe ladder, the campaigns and the policy follow, in whatever build this is`() {
        for (state in listOf(OwnerState(), optedIn)) {
            val allowed = OwnerTester.policy(state).trialRefusal(calibrated) == null
            assertEquals(TesterPolicy(OwnerTester.build, state.activeTests.on).activeTests, allowed)
            assertEquals(OwnerProbe.allowed(state), allowed)
        }
    }

    @Test
    fun `SM8 - no setting trial starts on a stack the tests are not calibrated on, in either build, with or without the opt-in`() {
        for (policy in listOf(TesterPolicy(TesterBuild.OWNER), TesterPolicy(TesterBuild.ROM, optIn = false), TesterPolicy(TesterBuild.ROM, optIn = true))) {
            for (stack in uncalibrated) {
                val refusal = policy.trialRefusal(stack)
                assertNotNull("$policy on $stack", refusal)
                // The stack is read first, so the opt-in is never named as the way to make the trials run, and the words are the round 30 and 31 paths'.
                assertEquals(TesterPolicy.NOT_CALIBRATED, refusal!!.code)
                assertEquals(StackCalibration.notCounted(stack), refusal.plain)
            }
        }
        val phh = TesterPolicy(TesterBuild.ROM, optIn = true).trialRefusal("phh_ims")!!
        assertTrue(phh.plain, "phh_ims" in phh.plain && "only passive evidence counts" in phh.plain)
    }

    @Test
    fun `SM8 and SM9 - on the calibrated stack only the opt-in refuses, and its words stay`() {
        assertNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(calibrated))
        assertNull(TesterPolicy(TesterBuild.ROM, optIn = true).trialRefusal(calibrated))
        val off = TesterPolicy(TesterBuild.ROM, optIn = false).trialRefusal(calibrated)!!
        assertEquals(TesterPolicy.OPT_IN_OFF, off.code)
        assertEquals("the active tests are off on this phone; only the passive checks run", off.plain)
    }

    @Test
    fun `the stack the gate reads is the installed ImsService's name the reports and the calls use, and only AOSP ImsStack counts`() {
        assertNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(ImsStack.of(listOf("com.android.imsstack"))))
        assertNotNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(ImsStack.of(listOf("me.phh.ims"))))
        assertNotNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(ImsStack.of(listOf("com.sec.imsservice"))))
        assertNotNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(ImsStack.of(listOf("com.example.ims"))))
        // An ImsService the phone does not show is no calibrated stack either.
        assertNotNull(TesterPolicy(TesterBuild.OWNER).trialRefusal(ImsStack.of(emptyList())))
        // One list: the gate adds none beside the round 30 and 31 paths' StackCalibration.
        val tester = File("src/owner/java/io/github/open_carrier_data/check/owner/core/Tester.kt").readText()
        assertEquals(1, Regex("setOf\\(\"aosp_imsstack\"\\)").findAll(tester).count())
        assertTrue(tester.contains("StackCalibration.notCounted(stackId)?.let { TrialRefusal(NOT_CALIBRATED, it) }"))
        assertTrue(File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerCalls.kt").readText().contains("policy(state).trialRefusal(CallBuild.stack(context))"))
    }

    @Test
    fun `each step that starts a trial checks the gate first`() {
        val feature = work.substringAfter("private suspend fun feature(store: FileStateStore): Result {")
        val apn = work.substringAfter("private suspend fun apn(store: FileStateStore): Result {")
        for ((name, body) in listOf("feature" to feature, "apn" to apn)) {
            assertTrue(name, body.trimStart().startsWith("requireTrials(store)"))
        }
        assertTrue(work.contains("STEP_PROBE -> {\n                    requireTrials(store)\n                    withPhone(store) { runner, sims, _ ->"))
        // The gate refuses with the policy's own code and words (the opt-in's or SM8's), and before a phone is opened.
        val gate = work.substringAfter("private fun requireTrials(store: FileStateStore) {").substringBefore("\n    }")
        assertTrue(gate, gate.contains("OwnerTester.trialRefusal(applicationContext, store.read())?.let { throw SafetyException(it.code, it.plain) }"))
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
        assertFalse(cleanup.contains("requireTrials") || cleanup.contains("trialRefusal"))
    }

    @Test
    fun `the quiet job plans no trial before the gate, and the light check and the dataset step come before it`() {
        val quiet = work.substring(work.indexOf("private suspend fun quiet("), work.indexOf("private suspend fun callsOutDue("))
        val gate = quiet.indexOf("OwnerTester.trialRefusal(context, store.read())?.let { why ->")
        assertTrue(gate > 0)
        assertEquals("one gate", gate, quiet.lastIndexOf("OwnerTester.trialRefusal(context, store.read())?.let { why ->"))
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
        assertTrue(body, body.contains("quietLog(store, \"check\", \"light check: no tests: ${'$'}{why.plain}\")"))
        assertTrue(body, body.contains("\"checked; nothing tested: ${'$'}{why.plain}\""))
    }

    @Test
    fun `Test now queues no trial chain before the gate`() {
        val tests = work.substring(work.indexOf("suspend fun runTests("), work.indexOf("/** Whether the work of a unique name is still to run. */"))
        val gate = tests.indexOf("OwnerTester.trialRefusal(context, FileStateStore(context).read())?.let { why ->")
        assertTrue(gate > 0)
        // The dataset installer's own path (gated on the stack and activeTests inside) comes first, the chain's steps after.
        assertTrue(tests.indexOf("if (status.available) {") in 0 until gate)
        assertTrue(tests.indexOf("beginUniqueWork(RUN") > gate)
        // The opt-in's refusal keeps its "off: " answer; SM8's is the round 30 and 31 paths' own "not counted: " words.
        assertTrue(tests.substring(gate).contains("return if (why.code == TesterPolicy.NOT_CALIBRATED) why.plain else \"off: ${'$'}{why.plain}\""))
        assertTrue(StackCalibration.notCounted("phh_ims")!!.startsWith("not counted: "))
        // The screen says so instead of "Couldn't start the test".
        val activity = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerActivity.kt").readText()
        val couldNot = activity.indexOf("line?.startsWith(\"queued\") != true -> say(\"Couldn't start the test\")")
        assertTrue(activity.indexOf("line?.startsWith(\"off: \")") in 0 until couldNot)
        assertTrue(activity.indexOf("line?.startsWith(\"not counted: \")") in 0 until couldNot)
    }

    @Test
    fun `the restart check is offered only where the trials may start - the calibrated stack, and in a rom build the opt-in`() {
        val screen = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerScreen.kt").readText()
        assertTrue(screen.contains("val trialsAllowed = remember(state.activeTests.on) { OwnerTester.trialRefusal(context, state) == null }\n    if (!state.rebootCleared && trialsAllowed) {\n        SectionTitle(\"Restart check\")"))
    }
}
