package io.github.open_carrier_data.check.owner

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
    private val sources = File("src/owner/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun probe() = sources.single { it.name == "OwnerProbe.kt" }.readText()

    @Test
    fun `round 47 - the ladder and W1 run only through their two entry points, on a measured failure or check now`() {
        val code = probe()
        // The ladder run itself is private; the object's public entry points are the two bounded ones.
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
        // gate is reviewed (round 47: nothing that can cost money runs by itself) and it is added below.
        val api = Regex("\\.(requestNetwork|sendUssdRequest|sendDataMessage|sendTextMessage|sendMultipartTextMessage|placeCall|createSocket)\\(|HttpsURLConnection")
        val files = sources.filter { api.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "OwnerBalance.kt", // the balance USSD: the person's tap only (STEP_BALANCE)
                "OwnerCalls.kt", // the mailbox call: the person's tap only (STEP_CALLS_OUT)
                "OwnerData.kt", // OCD's public data over HTTPS: passive, the read-only builds' pull too
                "OwnerEmergencyData.kt", // the emergency data test: adb only (SM10)
                "OwnerMessages.kt", // the test SMS: the person's tap only; the MMS probe is passive
                "OwnerProbe.kt", // the probe ladder and W1: only after a measured failure, within their bounds
            ),
            files,
        )
    }
}
