package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 42, item 2: the owner build runs the probe ladder (D1-D5) and W1; the rom build runs
 * them only after SM9's opt-in. 0.4.12 ran both on a rom install with no opt-in: nothing on the way from
 * OwnerWork.diagnoseCauses to OwnerProbe.runIfDue and epdgIfDue read `activeTests`, while the balance read
 * and the carrier-SMS receiver did. The rom build's two cases are in src/testRom (ProbeGateTest there).
 */
class ProbeGateTest {
    private val optedIn = OwnerState(activeTests = ActiveTestsOptIn(on = true, atMs = 1_761_800_000_000L))

    private val sources = File("src/owner/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun probe() = sources.single { it.name == "OwnerProbe.kt" }.readText()

    @Test
    fun `the owner build runs the ladder and W1 with no opt-in`() {
        assertEquals(TesterBuild.OWNER, OwnerTester.build)
        assertTrue(OwnerProbe.allowed(OwnerState()))
        // The opt-in is the rom build's; the owner build does not use it.
        assertTrue(OwnerProbe.allowed(optedIn))
    }

    @Test
    fun `the gate is the active-tests rule the balance read and the policy follow, in whatever build this is`() {
        for (state in listOf(OwnerState(), optedIn)) {
            assertEquals(TesterPolicy(OwnerTester.build, state.activeTests.on).activeTests, OwnerProbe.allowed(state))
            assertEquals(OwnerBalance.allowed(state.copy(balanceUssdOn = true)), OwnerProbe.allowed(state))
        }
    }

    @Test
    fun `the ladder and W1 each check the gate before anything else, and nothing else can start the ladder`() {
        val code = probe()
        val ladder = code.substringAfter("suspend fun runIfDue(").substringAfter("): ProbeRunMark? {")
        val epdg = code.substringAfter("suspend fun epdgIfDue(").substringAfter("): EpdgRunMark? {")
        for ((name, body) in listOf("runIfDue" to ladder, "epdgIfDue" to epdg)) {
            assertTrue(name, body.trimStart().startsWith("if (!allowed(store.read())) return null"))
        }
        // The ungated ladder run is private; the object's public entry points are the two gated ones and the gate itself.
        assertTrue(code.contains("private suspend fun run(context: Context, sim: OwnerSim): ProbeResults?"))
        val used = sources.flatMap { Regex("OwnerProbe\\.(\\w+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("runIfDue", "epdgIfDue"), used)
        val work = sources.single { it.name == "OwnerWork.kt" }.readText()
        assertEquals(1, Regex("OwnerProbe\\.runIfDue\\(").findAll(work).count())
        assertEquals(1, Regex("OwnerProbe\\.epdgIfDue\\(").findAll(work).count())
    }

    @Test
    fun `the owner sources send traffic from these files only`() {
        // A new file that requests a network, sends USSD, SMS or a call, or opens an HTTPS connection fails here until its
        // gate is reviewed (round 42's rule: the rom build's active tests wait for SM9's opt-in) and it is added below.
        val api = Regex("\\.(requestNetwork|sendUssdRequest|sendDataMessage|sendTextMessage|sendMultipartTextMessage|placeCall|createSocket)\\(|HttpsURLConnection")
        val files = sources.filter { api.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "OwnerBalance.kt", // the balance USSD: its toggle and the opt-in (OwnerBalance.allowed)
                "OwnerCalls.kt", // the mailbox call: TesterPolicy.callRefusal in MailboxCaller
                "OwnerData.kt", // OCD's public data over HTTPS: passive, the read-only builds' pull too
                "OwnerEmergencyData.kt", // the emergency data test: TesterPolicy.emergencyRefusal
                "OwnerMessages.kt", // the test SMS: TesterPolicy.smsRefusal; the MMS ladder (item 6) is owner and rom, no opt-in
                "OwnerProbe.kt", // the probe ladder and W1: OwnerProbe.allowed
                "OwnerReports.kt", // report issues with the owner's token: TesterPolicy.sendsWithToken
            ),
            files,
        )
    }
}
