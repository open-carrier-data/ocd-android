package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.StackCalibration
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 47: a setting trial needs OcdCarrierSettings speaking round 31 and a calibrated stack (SM8). A healthy
 * phone gets none by itself: only a measured failure on that phone, or the person's tap on Test now, starts one.
 */
class TrialGateTest {
    private val calibrated = "aosp_imsstack"
    private val uncalibrated = listOf("phh_ims", "samsung_ims", "other", "not_known", "", null)

    private fun policy(stack: String? = calibrated, provider: Boolean = true) = TesterPolicy(provider, stack)

    private val sources = File("src/main/java/io/github/open_carrier_data/check/owner").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val work = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWork.kt").readText()

    @Test
    fun `a trial needs the provider and a calibrated stack`() {
        assertNull(policy().trialRefusal)
        assertEquals(TesterPolicy.NO_PROVIDER, policy(provider = false).trialRefusal?.code)
        assertEquals(TesterPolicy.NO_PROVIDER, TesterPolicy().trialRefusal?.code)
    }

    @Test
    fun `SM8 - no setting trial starts on a stack the tests are not calibrated on`() {
        for (stack in uncalibrated) {
            val refusal = policy(stack).trialRefusal
            assertNotNull("$stack", refusal)
            assertEquals(TesterPolicy.NOT_CALIBRATED, refusal!!.code)
            assertEquals(StackCalibration.notCounted(stack), refusal.plain)
        }
        val phh = policy("phh_ims").trialRefusal!!
        assertTrue(phh.plain, "phh_ims" in phh.plain && "only passive evidence counts" in phh.plain)
    }

    @Test
    fun `the stack the gate reads is the installed ImsService's name the reports and the calls use, and only AOSP ImsStack counts`() {
        assertNull(policy(ImsStack.of(listOf("com.android.imsstack"))).trialRefusal)
        assertNotNull(policy(ImsStack.of(listOf("me.phh.ims"))).trialRefusal)
        assertNotNull(policy(ImsStack.of(listOf("com.sec.imsservice"))).trialRefusal)
        assertNotNull(policy(ImsStack.of(listOf("com.example.ims"))).trialRefusal)
        // An ImsService the phone does not show is no calibrated stack either.
        assertNotNull(policy(ImsStack.of(emptyList())).trialRefusal)
        val tester = File("src/main/java/io/github/open_carrier_data/check/owner/core/Tester.kt").readText()
        assertEquals(1, Regex("setOf\\(\"aosp_imsstack\"\\)").findAll(tester).count())
        assertTrue(File("src/main/java/io/github/open_carrier_data/check/owner/OwnerCalls.kt").readText().contains("TesterPolicy(state.datasetStatus?.round31 == true, CallBuild.stack(context))"))
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
    fun `round 47 - the quiet job plans nothing, and the passive reads and the dataset step still run`() {
        for (gone in listOf("QuietPlan.plan(", "callsOutDue(", "runPlanned(", "runCallsOut(", "OwnerEmergencyData.session(context, store, phone, OwnerDatasetStep.client(context), sim, manual = false)")) {
            assertFalse(gone, gone in work)
        }
        val quiet = work.substring(work.indexOf("private suspend fun quiet("), work.indexOf("     * The message check of the light check"))
        for (still in listOf("diagnoseCauses(store, phone, sims)", "OwnerDatasetStep.run(context, store, phone, sims)", "messageChecks(store, phone, sims, look, night)")) {
            assertTrue(still, still in quiet)
        }
    }

    @Test
    fun `a cleanup never asks the gate, it only takes a change back`() {
        val cleanup = work.substringAfter("private suspend fun cleanup(store: FileStateStore): Result {").substringBefore("private suspend fun quiet(")
        assertFalse(cleanup.contains("requireTrials") || cleanup.contains("trialRefusal"))
    }

    @Test
    fun `Test now without an installer tests nothing, and says why`() {
        val tests = work.substring(work.indexOf("suspend fun runTests("), work.indexOf("/** Whether the work of a unique name is still to run. */"))
        val gate = tests.indexOf("OwnerTester.trialRefusal(context, FileStateStore(context).read())?.let { why ->")
        assertTrue(gate > 0)
        assertTrue(tests.indexOf("if (status.available) {") in 0 until gate)
        assertFalse(tests.contains("beginUniqueWork(RUN"))
        assertTrue(StackCalibration.notCounted("phh_ims")!!.startsWith("not counted: "))
        val activity = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerActivity.kt").readText()
        val couldNot = activity.indexOf("line?.startsWith(\"queued\") != true -> say(\"Couldn't start the test\")")
        assertTrue(activity.indexOf("line?.startsWith(\"off: \")") in 0 until couldNot)
        assertTrue(activity.indexOf("line?.startsWith(\"not counted: \")") in 0 until couldNot)
    }

    @Test
    fun `the restart check is offered only where a trial may start`() {
        val screen = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerScreen.kt").readText()
        assertTrue(screen.contains("val trialsAllowed = remember(state.datasetStatus?.round31) { OwnerTester.trialRefusal(context, state) == null }\n    if (!state.rebootCleared && trialsAllowed) {\n        SectionTitle(\"Restart check\")"))
    }
}
