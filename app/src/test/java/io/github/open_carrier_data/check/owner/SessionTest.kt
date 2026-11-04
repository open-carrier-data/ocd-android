package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.CcLayer
import io.github.open_carrier_data.check.owner.core.CcResult
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.RevertProof
import io.github.open_carrier_data.check.owner.core.SafetyException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ports of the lab's dump, command and session tests (test_carrier_config_dump, test_cc_command_outputs, test_apply_and_revert_proof and the guards). */
class SessionTest {
    private val cc = OwnerFixtures.CC

    @Test
    fun `the carrier config dump parses with its layers`() {
        val dump = CcDump.parse(cc)
        assertEquals("org.lineageos.samsungcarrierbridge", dump.platformPackage)
        assertEquals(CcLayer.ALL.associateWith { null }, dump.phones[0])
        assertEquals(mapOf("ims.ims_user_agent_string" to ""), dump.phones[1]!![CcLayer.DEFAULT_APP])
        assertEquals(emptyMap<String, String>(), dump.noSim)
        assertEquals(10, dump.defaults.size)
        assertEquals("false", dump.effective(0)["carrier_volte_available_bool"])
        assertEquals(emptyMap<String, Map<String, String>>(), dump.overrides(0))

        val applied = CcDump.parse(OwnerFixtures.withOverride(cc, CcLayer.OVERRIDE, mapOf("carrier_volte_available_bool" to "true")))
        assertEquals("true", applied.effective(0)["carrier_volte_available_bool"])
        assertEquals(listOf("carrier_volte_available_bool"), applied.overrides(0)[CcLayer.OVERRIDE]!!.keys.toList())
        assertEquals(1, CcDump.diff(dump, applied).size)
        assertTrue(dump.fingerprint(true) != applied.fingerprint(true))

        // clear-values leaves empty bundles where the loader had null ones.
        val cleared = CcDump.parse(OwnerFixtures.withOverride(OwnerFixtures.withOverride(cc, CcLayer.OVERRIDE, emptyMap()), CcLayer.PERSISTENT_OVERRIDE, emptyMap()))
        assertEquals(dump.fingerprint(true), cleared.fingerprint(true))
        assertTrue(dump.fingerprint(false) != cleared.fingerprint(false))
        assertEquals(setOf("empty"), CcDump.diff(dump, cleared).map { it.after }.toSet())
        assertTrue(RevertProof.of(dump, cleared, 0, emptyMap()).ok)
    }

    @Test
    fun `a difference outside the override layers, or a changed value, fails the proof`() {
        val dump = CcDump.parse(cc)
        val defaultsChanged = CcDump.parse(cc.replace("carrier_vt_available_bool = false", "carrier_vt_available_bool = true"))
        val proof = RevertProof.of(dump, defaultsChanged, 0, emptyMap())
        assertFalse(proof.ok)
        assertEquals("defaults", proof.unexpectedDifferences.single().where)
        val leftover = CcDump.parse(OwnerFixtures.withOverride(cc, CcLayer.OVERRIDE, mapOf("carrier_volte_available_bool" to "true")))
        assertFalse(RevertProof.of(dump, leftover, 0, emptyMap()).ok)
        assertTrue(RevertProof.of(dump, leftover, 0, mapOf("carrier_volte_available_bool" to true)).ok)
    }

    @Test
    fun `cc command outputs as the S20 printed them`() {
        assertEquals("permission_denied", CcResult.parse("cc: Permission denied.\n", 255).reason)
        assertEquals("no_valid_subscription", CcResult.parse("cc set-value: No valid subscription found.\n", 255).reason)
        assertEquals("invalid_phone_id", CcResult.parse("Exception occurred while executing 'cc':\njava.lang.IllegalArgumentException: Invalid phoneId -1 for subId -1\n", 255).reason)
        val done = CcResult.parse("Previous value: \ncarrier_volte_available_bool      BOOLEAN         false\nNew value: \ncarrier_volte_available_bool      BOOLEAN         true\n", 0)
        assertEquals(CcResult(true, null, "false", "true"), done)
        assertTrue(CcResult.parse("All previously set carrier config override values has been cleared\n", 0).ok)
    }

    private fun session(phone: FakePhone) = OwnerSession(phone, phone.store, OwnerFixtures.SIM, "SM-G981B")

    @Test
    fun `apply registers first, and the revert clears and proves`() = runBlocking {
        val phone = FakePhone()
        val s = session(phone)
        s.apply(OwnerFeature.VOLTE, true)
        assertEquals(mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        assertEquals(listOf(true), phone.recordedAtWrite)
        assertEquals(Hold.TRIAL, phone.store.state.overrides.single().hold)
        val proof = s.revertTrials("test")!!
        assertTrue(proof.ok && proof.fingerprintNormalizedEqual && proof.effectiveValuesEqual)
        assertEquals(emptyList<LiveOverride>(), phone.store.state.overrides)
        assertEquals(listOf("set carrier_volte_available_bool=true", "clear"), phone.writes)
    }

    @Test
    fun `a revert keeps the kept values and proves them`() = runBlocking {
        val phone = FakePhone()
        val s = session(phone)
        s.apply(OwnerFeature.VOLTE, true, Hold.KEPT, "test")
        s.apply(OwnerFeature.SMS_OVER_IMS, false)
        assertTrue(s.revertTrials("sms failed")!!.ok)
        assertEquals(mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        assertEquals(listOf("carrier_volte_available_bool"), phone.store.state.overrides.map { it.key })
    }

    @Test
    fun `a revert that cannot be proven sets the stop flag, and the stop flag blocks every write`() = runBlocking {
        val phone = FakePhone(honourClear = false)
        val s = session(phone)
        s.apply(OwnerFeature.VOLTE, true)
        assertFalse(s.revertTrials("test")!!.ok)
        assertNotNull(phone.store.state.stop)
        assertEquals(1, phone.store.state.overrides.size) // the record stays
        val writes = phone.writes.size
        try {
            session(phone).apply(OwnerFeature.VOLTE, true)
            fail("stop flag ignored")
        } catch (e: SafetyException) {
            assertEquals("stopped", e.reason)
        }
        assertEquals(writes, phone.writes.size)
    }

    @Test
    fun `guards stop before any change`() = runBlocking {
        for (setup in listOf("call", "foreign", "roaming", "ecbm", "booted")) {
            val phone = FakePhone()
            when (setup) {
                "call" -> phone.call = true
                "foreign" -> phone.persistent = mutableMapOf("carrier_volte_available_bool" to "true")
                "roaming" -> phone.roaming = true
                "ecbm" -> phone.ecbm = true
                "booted" -> phone.uptime = 60_000
            }
            try {
                session(phone).apply(OwnerFeature.VOLTE, true)
                fail("$setup was not refused")
            } catch (_: SafetyException) {
            }
            assertTrue("$setup: ${phone.writes}", phone.writes.none { it.startsWith("set") })
        }
    }

    @Test
    fun `a refused set-value (no SIM) changes nothing and reverts with proof`() = runBlocking {
        val phone = FakePhone(sim = false)
        val s = session(phone)
        try {
            s.apply(OwnerFeature.VOLTE, false)
            fail("no SIM: set-value should be refused")
        } catch (e: SafetyException) {
            assertEquals("set_value_did_not_take", e.reason)
        }
        assertTrue(s.revertTrials("probe")!!.ok)
        assertNull(phone.overrides)
    }

    @Test
    fun `the revert waits for a call to end`() = runBlocking {
        val phone = FakePhone()
        val s = session(phone)
        s.apply(OwnerFeature.VOLTE, true)
        phone.call = true
        var sleeps = 0
        phone.onSleep = { if (++sleeps == 3) it.call = false }
        assertTrue(s.revertTrials("test")!!.ok)
        assertEquals(3, sleeps)
    }

    @Test
    fun `a call that outlasts the wait leaves the trial recorded for the cleanup`() = runBlocking {
        val phone = FakePhone()
        val s = session(phone)
        s.apply(OwnerFeature.VOLTE, true)
        phone.call = true
        assertNull(s.revertTrials("test", callWaitMs = 10_000))
        assertEquals(Hold.TRIAL, phone.store.state.overrides.single().hold)
        assertFalse(phone.writes.contains("clear"))
    }
}
