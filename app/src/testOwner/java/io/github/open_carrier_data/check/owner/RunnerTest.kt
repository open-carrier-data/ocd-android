package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.SimKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's loop against the fake phone: test on and off, keep the best working value, smart revert, cleanup, re-apply. */
class RunnerTest {
    private val s20 = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val sim = OwnerFixtures.SIM
    private val ocdOn = OcdAnswer(true, "device_record", "open.26202.aaaaaaaaaaaa", recordSource = "samsung_omc", recordStack = "stock", sameStack = false)
    private val fast = RunOptions(settleMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000)

    private fun runner(phone: FakePhone) = OwnerRunner(phone, phone.store, s20)

    @Test
    fun `VoLTE on registers, is kept as the best working value, and survives the revert of later trials`() = runBlocking {
        val phone = FakePhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(false, result.baseline)
        assertEquals(true, result.onWorks)
        assertEquals(true, result.best)
        assertTrue(result.applied)
        assertEquals(mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        val kept = phone.store.state.overrides.single()
        assertEquals(Hold.KEPT, kept.hold)
        assertEquals(sim.key, kept.sim)
        // SMS over IMS next: its trial is reverted, the VoLTE keep stays.
        val sms = runner(phone).testFeature(sim, OwnerFeature.SMS_OVER_IMS, OcdAnswer(true, "carrier_profile"), fast)
        assertNull(sms.refused)
        assertEquals("true", phone.overrides!!["carrier_volte_available_bool"])
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `an on that does not register is reverted and nothing is kept`() = runBlocking {
        val phone = FakePhone().apply { imsWorks = false }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(false, result.onWorks)
        assertEquals("the phone's own off stays", false, result.best)
        assertFalse(result.applied)
        assertEquals(emptyList<LiveOverride>(), phone.store.state.overrides)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `a voice off is never kept without voice measured`() = runBlocking {
        // The phone's carrier file has VoLTE on, but IMS never registers.
        val onPhone = FakePhoneWithVolteOn()
        onPhone.imsWorks = false
        val result = OwnerRunner(onPhone, onPhone.store, s20).testFeature(sim, OwnerFeature.VOLTE, OcdAnswer(false, "carrier_profile"), fast)
        assertEquals(true, result.baseline)
        assertEquals(true, result.offWorks)
        assertEquals(null, result.offVoiceMeasured)
        assertNull("no keep: voice not measured", result.best)
        assertTrue(onPhone.overrides.isNullOrEmpty())
    }

    @Test
    fun `a voice off is kept when the owner's own call went active with it off`() = runBlocking {
        val phone = FakePhoneWithVolteOn().apply { imsWorks = false }
        var sleeps = 0
        phone.onSleep = { p ->
            sleeps++
            // The owner places a call during the voice check, then hangs up.
            if (p.overrides?.get("carrier_volte_available_bool") == "false") p.call = sleeps in 6..8
        }
        val result = OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, OcdAnswer(false, "carrier_profile"), fast.copy(voiceCheckMs = 60_000))
        assertEquals(true, result.offVoiceMeasured)
        assertEquals(false, result.best)
        assertTrue(result.applied)
        assertEquals("false", phone.overrides!!["carrier_volte_available_bool"])
    }

    @Test
    fun `VoNR on the S20 stops before any change, the modem cannot do it`() = runBlocking {
        val phone = FakePhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VONR, ocdOn, fast)
        assertEquals("device_not_capable", result.onReason)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `a refusal changes nothing and says why`() = runBlocking {
        val phone = FakePhone().apply { roaming = true }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals("roaming", result.refused)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `a kept value that no longer works is dropped (smart revert)`() = runBlocking {
        val phone = FakePhone()
        runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(1, phone.store.state.overrides.size)
        phone.imsWorks = false
        val again = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(true, again.baseline)
        assertEquals(false, again.baselineWorks)
        assertEquals(emptyList<LiveOverride>(), phone.store.state.overrides)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `the start-up pass reverts a leftover trial and re-applies a keep a reboot dropped`() = runBlocking {
        val phone = FakePhone()
        phone.overrides = mutableMapOf("carrier_vt_available_bool" to "true")
        phone.store.state = phone.store.state.copy(
            overrides = listOf(
                LiveOverride(0, sim.key, "carrier_vt_available_bool", true, Hold.TRIAL, phone.now - 1_000),
                LiveOverride(0, sim.key, "carrier_volte_available_bool", true, Hold.KEPT, phone.now - 1_000),
            ),
        )
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "leftover trial" in summary)
        assertEquals(mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        assertEquals(listOf(Hold.KEPT), phone.store.state.overrides.map { it.hold })
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `after a reboot the keeps of the SIM in its slot are applied again`() = runBlocking {
        val phone = FakePhone()
        phone.store.state = phone.store.state.copy(
            overrides = listOf(LiveOverride(0, sim.key, "carrier_volte_available_bool", true, Hold.KEPT, phone.now - 86_400_000)),
        )
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "re-applied carrier_volte_available_bool" in summary)
        assertEquals(mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        // Another SIM in the slot: nothing is applied for it.
        val other = FakePhone()
        other.store.state = phone.store.state
        OwnerRunner(other, other.store, s20).reconcile(listOf(sim.copy(subId = 9)))
        assertTrue(other.writes.none { it.startsWith("set") })
    }

    @Test
    fun `a keep of another SIM is cleared when that SIM left the slot`() = runBlocking {
        val phone = FakePhone()
        phone.overrides = mutableMapOf("carrier_volte_available_bool" to "true")
        val other = SimKey(9, "26223", 1234)
        phone.store.state = phone.store.state.copy(overrides = listOf(LiveOverride(0, other, "carrier_volte_available_bool", true, Hold.KEPT, phone.now - 1_000)))
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "keep(s) of another SIM reverted" in summary)
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.overrides.isEmpty())
    }

    @Test
    fun `a trial that survived a reboot is a stop event`() = runBlocking {
        val phone = FakePhone().apply { uptime = 20 * 60_000 }
        phone.overrides = mutableMapOf("carrier_volte_available_bool" to "true")
        phone.store.state = phone.store.state.copy(overrides = listOf(LiveOverride(0, sim.key, "carrier_volte_available_bool", true, Hold.TRIAL, phone.now - 3_600_000)))
        runner(phone).reconcile(listOf(sim))
        assertTrue(phone.store.state.stop!!.startsWith("value_after_reboot"))
    }

    @Test
    fun `before first-use check 3 nothing is kept, no off is tested and a scheduled run changes nothing`() = runBlocking {
        val phone = FakePhone(store = MemoryStore(OwnerState()))
        val on = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(true, on.onWorks)
        assertEquals(true, on.best)
        assertFalse(on.applied)
        assertFalse(phone.store.state.anyLive)
        val off = runner(FakePhoneWithVolteOn().also { it.store.state = OwnerState() }).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(OwnerRunner.FIRST_USE_PENDING, off.offReason)
        val scheduled = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(scheduled = true))
        assertEquals(OwnerRunner.FIRST_USE_PENDING, scheduled.refused)
        val writes = phone.writes.size
        runner(phone).testApns(sim, listOf(ocdRow), fast.copy(scheduled = true))
        assertEquals(writes, phone.writes.size)
    }

    @Test
    fun `check 3 passes when the probe's override is gone after a reboot`() = runBlocking {
        val phone = FakePhone(store = MemoryStore(OwnerState()))
        assertTrue(runner(phone).rebootProbe(sim).startsWith("VoLTE=false is live"))
        assertEquals(mapOf("carrier_volte_available_bool" to "false"), phone.overrides)
        // A start-up pass before the reboot leaves the check's override alone.
        runner(phone).reconcile(listOf(sim))
        assertEquals(mapOf("carrier_volte_available_bool" to "false"), phone.overrides)
        assertNotNull(phone.store.state.rebootProbeAtMs)
        // Reboot: the in-memory override is gone, the uptime starts again.
        phone.overrides = null
        phone.now += 15 * 60_000
        phone.uptime = 12 * 60_000
        runner(phone).reconcile(listOf(sim))
        assertTrue(phone.store.state.rebootCleared)
        assertNull(phone.store.state.stop)
        assertFalse(phone.store.state.anyLive)
        assertNull(phone.store.state.rebootProbeAtMs)
    }

    @Test
    fun `revert all clears every override and APN row, with proof`() = runBlocking {
        val phone = FakePhone()
        runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertTrue(phone.store.state.anyLive)
        val summary = runner(phone).revertAll(listOf(sim))
        assertTrue(summary, "proven" in summary)
        assertFalse(phone.store.state.anyLive)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    private val ocdRow = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "internet.lidl", "type" to "default,supl", "protocol" to "IPV4V6", "carrier" to "LIDL Internet"))

    @Test
    fun `an OCD APN row the phone lacks is inserted, preferred, measured and removed while data works anyway`() = runBlocking {
        val phone = FakePhone()
        val results = runner(phone).testApns(sim, listOf(ocdRow), fast)
        assertEquals("inserted", results.single().status)
        assertEquals(false, results.single().dataWorks)
        assertEquals(listOf(1L), phone.apns.map { it.id })
        assertEquals(1L, phone.preferred)
        assertTrue(phone.store.state.apns.isEmpty())
    }

    @Test
    fun `an OCD APN row is kept when mobile data works only with it, and revert all removes it`() = runBlocking {
        val phone = FakePhone()
        phone.workingApnKeys = setOf(ocdRow.key)
        val results = runner(phone).testApns(sim, listOf(ocdRow), fast)
        assertTrue(results.single().kept)
        val kept: LiveApn = phone.store.state.apns.single()
        assertEquals(Hold.KEPT, kept.hold)
        assertEquals(kept.rowId, phone.preferred)
        runner(phone).revertAll(listOf(sim))
        assertEquals(1L, phone.preferred)
        assertEquals(listOf(1L), phone.apns.map { it.id })
    }

    @Test
    fun `a marked APN row the state does not keep is removed on start`() = runBlocking {
        val phone = FakePhone()
        phone.apns += PhoneApnRow(55, "Old (OCD Check test)", "x", listOf("default"), null, null)
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "marked APN row" in summary)
        assertEquals(listOf(1L), phone.apns.map { it.id })
    }
}

/** A phone whose carrier file turns VoLTE on (the default-app layer), for the off tests. */
class FakePhoneWithVolteOn : FakePhone() {
    override suspend fun carrierConfigText(): String =
        OwnerFixtures.withOverride(super.carrierConfigText(), "mConfigFromDefaultApp", mapOf("carrier_volte_available_bool" to "true"))
}
