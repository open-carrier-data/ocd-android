package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.CallWatchArm
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RealCallWatch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** OCD rule round 47, fix 5: two real calls that fail after a voice-class change bring the hold, with no candidates. */
class RealCallWatchTest {
    private val sim = OwnerFixtures.SIM.key
    private val other = sim.copy(subId = sim.subId + 1)

    private fun call(
        code: Int? = 352, active: Boolean = false, direction: CallDirection = CallDirection.OUTGOING, method: CallMethod = CallMethod.REAL_CALL,
        fresh: Boolean? = true, several: Boolean = false, on: io.github.open_carrier_data.check.owner.core.SimKey = sim,
    ) = CallRecord(
        1_761_800_000_000L, 0, on, method, direction, rang = false, answered = active, active = active,
        disconnect = code?.let { DisconnectReading(imsCode = it) }, disconnectFresh = fresh, severalCalls = several, build = "rom-1", stack = "aosp_imsstack",
    )

    private val armed = OwnerState(callWatches = listOf(CallWatchArm(sim, "c1", 0)))

    @Test
    fun `a failure is an outgoing real call that never went ACTIVE, one call, fresh, on the closed list`() {
        for (code in RealCallWatch.SETUP_FAILURES) assertTrue("$code", RealCallWatch.failed(call(code)))
        assertFalse(RealCallWatch.failed(call(active = true)))
        assertFalse(RealCallWatch.failed(call(direction = CallDirection.INCOMING)))
        assertFalse(RealCallWatch.failed(call(method = CallMethod.MAILBOX_CALL)))
        assertFalse(RealCallWatch.failed(call(fresh = false)))
        assertFalse(RealCallWatch.failed(call(fresh = null)))
        assertFalse(RealCallWatch.failed(call(several = true)))
        assertFalse(RealCallWatch.failed(call(code = null)))
    }

    @Test
    fun `busy, not found, bad address, rejected, forbidden, cancelled, no answer and the person's own hang-up never count`() {
        // 332 forbidden, 333 not found, 336 temporarily unavailable, 337 bad address, 338 busy, 339 cancelled, 341 not reachable,
        // 361 rejected, 501 and 510 the user's own end, 202 and 203 no answer.
        for (code in listOf(332, 333, 336, 337, 338, 339, 341, 361, 501, 510, 202, 203, 0)) assertFalse("$code", RealCallWatch.failed(call(code)))
    }

    @Test
    fun `two failures make the hold due, and one does not`() {
        val one = RealCallWatch.after(armed, call())
        assertFalse(one.realCallHoldDue)
        assertEquals(1, one.callWatches.single().failures)
        val two = RealCallWatch.after(one, call())
        assertTrue(two.realCallHoldDue)
        assertTrue(two.callWatches.isEmpty())
    }

    @Test
    fun `a call that went ACTIVE disarms, and a wrong number changes nothing`() {
        val one = RealCallWatch.after(armed, call())
        assertTrue(RealCallWatch.after(one, call(active = true)).callWatches.isEmpty())
        assertTrue(RealCallWatch.after(armed, call(method = CallMethod.MAILBOX_CALL, active = true)).callWatches.isEmpty())
        assertEquals(one, RealCallWatch.after(one, call(code = 338)))
    }

    @Test
    fun `nothing is armed, nothing counts, and another SIM's call leaves this watch alone`() {
        assertEquals(OwnerState(), RealCallWatch.after(OwnerState(), call()))
        assertEquals(armed, RealCallWatch.after(armed, call(on = other)))
    }

    @Test
    fun `arming is per SIM, keeps the count on the same commit, and starts over on a new one`() {
        val counted = listOf(CallWatchArm(sim, "c1", 0, failures = 1))
        assertEquals(counted, RealCallWatch.arm(counted, sim, "c1", 5))
        assertEquals(listOf(CallWatchArm(sim, "c2", 5)), RealCallWatch.arm(counted, sim, "c2", 5))
        assertEquals(2, RealCallWatch.arm(counted, other, "c1", 5).size)
    }

    @Test
    fun `the ledger feeds the watch, the hold runs with no candidates, and no split runs on it`() {
        val root = "src/owner/java/io/github/open_carrier_data/check/owner"
        assertTrue(File("$root/core/Calls.kt").readText().contains("RealCallWatch.after(state.copy(calls = (state.calls + record).takeLast(CallRecord.MAX)), record)"))
        val dataset = File("$root/OwnerDataset.kt").readText()
        val hold = dataset.substringAfter("private suspend fun realCallHold(").substringBefore("Round 31 (R31.8)")
        assertTrue(hold.contains("ownerBlock(store, phone, sims)?.let { why ->"))
        assertTrue(hold.indexOf("ownerBlock(") < hold.indexOf("client.voiceLossHold("))
        assertFalse(hold.contains("candidates(") || hold.contains("putRecord") || hold.contains("TrialRunner"))
        assertTrue(dataset.contains("if (store.read().heldByCalls) return listOf(\"the build's dataset stays held until you tap Resume\")"))
        assertTrue(dataset.substringAfter("fun resume(context: Context, store: StateStore): String {").contains("client.voiceLossRelease(\"no_failing_value\", \"the person tapped Resume\")"))
        assertTrue(dataset.contains("if (armed.changes.any { it.cls in RealCallWatch.CLASSES })"))
        assertTrue(File("$root/OwnerCalls.kt").readText().contains("if (store.read().realCallHoldDue) OwnerScheduler.dataset(context)"))
    }

    @Test
    fun `a manual run ends a served local emergency_default_won record and nothing else`() {
        fun entry(result: String, origin: String, state: String, key: String) =
            """{"record":{"record_key":"$key","kind":"measured","result":"$result"},"origin":"$origin","state":"$state"}"""
        val records = Json.parseToJsonElement(
            "[" + listOf(
                entry("emergency_default_won", "local", "served", "r:1"),
                entry("emergency_default_won", "local", "other_cell", "r:2"),
                entry("emergency_default_won", "dataset", "served", "r:3"),
                entry("winner_after_failure", "local", "served", "r:4"),
            ).joinToString(",") + "]",
        )
        assertEquals(listOf("r:1"), EmergencyData.defaultWonKeys(records))
        assertTrue(EmergencyData.defaultWonKeys(null).isEmpty())
    }
}
