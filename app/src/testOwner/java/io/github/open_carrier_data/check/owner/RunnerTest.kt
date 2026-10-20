package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LiveApn
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TestBasis
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
    private val fast = RunOptions(settleMs = 30_000, restoreMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000)
    private val basis = TestBasis("data-2026.10@sha256:36d5f7ebb084", "lineage/x1s@1", "0.3.0-owner(3)/1/1")

    private fun runner(phone: FakePhone) = OwnerRunner(phone, phone.store, s20)

    /** The S20 at home: VoLTE and Wi-Fi calling on, IMS registered over Wi-Fi, LTE with VoPS. */
    private fun imsOnWifiPhone() = FakePhone().apply {
        defaultApp = mapOf("carrier_volte_available_bool" to "true", "carrier_wfc_ims_available_bool" to "true")
        imsOverWifi = true
        wifi = true
    }

    @Test
    fun `VoLTE on registers, is kept as the best working value, and survives the revert of later trials`() = runBlocking {
        val phone = FakePhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(false, result.baseline)
        assertEquals(true, result.onWorks)
        assertEquals(true, result.best)
        assertTrue(result.applied)
        assertEquals(true, result.restored)
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
    fun `VoLTE while IMS runs over Wi-Fi is measured with the Wi-Fi-calling-off override, Wi-Fi untouched`() = runBlocking {
        val phone = imsOnWifiPhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(basis = basis))
        assertNull(result.refused)
        assertEquals(true, result.baseline)
        assertEquals("VoLTE registered over LTE inside the override", true, result.baselineWorks)
        assertEquals(OwnerRunner.VOLTE_METHOD_WFC_OVERRIDE, result.method)
        assertEquals("a fresh REGISTER over LTE", true, result.freshRegister)
        assertEquals("one change, two results: Wi-Fi calling gone with it off", true, result.sideVowifiOff)
        // Then VoLTE off, with Wi-Fi calling as the voice path.
        assertEquals(true, result.offWorks)
        assertEquals(true, result.best)
        assertEquals(true, result.restored)
        assertEquals(true, phone.wifi)
        val sets = phone.writes.filter { it.startsWith("set") }
        assertEquals(listOf("set carrier_wfc_ims_available_bool=false", "set carrier_volte_available_bool=false"), sets)
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.log.any { it.kind == "volte_method" && "wfc_override" in it.text && "fresh REGISTER over LTE seen" in it.text })
        assertTrue(phone.store.state.log.any { it.kind == "restored" && "baseline back" in it.text })
        // The entries it settled: VoLTE on (measured), VoLTE off (tried), Wi-Fi calling off (the same change).
        val entries = phone.store.state.entries.associateBy { it.entry }
        assertEquals(true, entries.getValue("volte:on").works)
        assertEquals(true, entries.getValue("volte:off").works)
        assertEquals(true, entries.getValue("vowifi:off").works)
    }

    @Test
    fun `an on-values run keeps what the VoLTE measurement showed about Wi-Fi calling off`() = runBlocking {
        val phone = imsOnWifiPhone()
        val onOnly = fast.copy(testOffs = false, basis = basis)
        runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, onOnly)
        runner(phone).testFeature(sim, OwnerFeature.VOWIFI, ocdOn, onOnly)
        val entries = phone.store.state.entries.associateBy { it.entry }
        assertEquals("the side result stays", true, entries.getValue("vowifi:off").works)
        assertEquals("VoLTE off is still to try", null, entries.getValue("volte:off").works)
    }

    @Test
    fun `without LTE voice the VoLTE measurement is not made, and nothing changes`() = runBlocking {
        val phone = imsOnWifiPhone().apply { lteVops = false }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(OwnerRunner.NO_VOICE_PATH, result.refused)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `a stack that keeps IMS on Wi-Fi leaves VoLTE not measured, not failed`() = runBlocking {
        val phone = imsOnWifiPhone().apply { imsStaysOnWifi = true }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(only = true))
        assertNull(result.baselineWorks)
        assertEquals(false, result.freshRegister)
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.log.any { "IMS stayed on Wi-Fi" in it.text })
    }

    @Test
    fun `a voice off is never kept without voice measured`() = runBlocking {
        // VoLTE is on but does not register over LTE; its off is tried with Wi-Fi calling as the voice path.
        val phone = imsOnWifiPhone().apply { volteRegisters = false }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, OcdAnswer(false, "carrier_profile"), fast)
        assertEquals(false, result.baselineWorks)
        assertEquals(true, result.offWorks)
        assertNull(result.offVoiceMeasured)
        assertNull("no keep: voice not measured", result.best)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `a voice off is kept when the owner's own call went active with it off`() = runBlocking {
        val phone = imsOnWifiPhone().apply { volteRegisters = false }
        var offSleeps = 0
        phone.onSleep = { p ->
            // The owner places a call during the voice check, then hangs up.
            if (p.overrides?.get("carrier_volte_available_bool") == "false") p.call = ++offSleeps in 2..4
        }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, OcdAnswer(false, "carrier_profile"), fast.copy(voiceCheckMs = 60_000))
        assertEquals(true, result.offVoiceMeasured)
        assertEquals(false, result.best)
        assertTrue(result.applied)
        assertEquals("false", phone.overrides!!["carrier_volte_available_bool"])
    }

    @Test
    fun `VoLTE off is not tried without Wi-Fi calling as the voice path`() = runBlocking {
        val phone = FakePhone().apply { defaultApp = mapOf("carrier_volte_available_bool" to "true") }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(true, result.baselineWorks)
        assertEquals(OwnerRunner.NO_VOICE_PATH, result.offReason)
        assertTrue(phone.writes.none { it.startsWith("set") })
    }

    @Test
    fun `Wi-Fi calling off is tried only with LTE voice, and it measures VoLTE over LTE too`() = runBlocking {
        val noLte = imsOnWifiPhone().apply { lteVops = false }
        assertEquals(OwnerRunner.NO_VOICE_PATH, runner(noLte).testFeature(sim, OwnerFeature.VOWIFI, ocdOn, fast).offReason)
        assertTrue(noLte.writes.isEmpty())
        val phone = imsOnWifiPhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VOWIFI, ocdOn, fast.copy(basis = basis))
        assertEquals(true, result.baselineWorks)
        assertEquals(true, result.offWorks)
        assertEquals(true, result.sideVolteOn)
        assertEquals(true, result.restored)
        assertEquals(true, phone.store.state.entries.single { it.entry == "volte:on" }.works)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `VoLTE on while IMS runs over Wi-Fi is tried together with Wi-Fi calling off, and kept`() = runBlocking {
        val phone = FakePhone().apply {
            defaultApp = mapOf("carrier_volte_available_bool" to "false", "carrier_wfc_ims_available_bool" to "true")
            imsOverWifi = true
            wifi = true
        }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(true, result.onWorks)
        assertTrue(result.applied)
        assertEquals("only the keep is left; the measurement override went with the revert", mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
    }

    @Test
    fun `a worker cancelled in the middle of a measurement reverts it`() = runBlocking {
        // VoLTE slow to register over LTE: the measurement waits, and the worker is stopped then.
        val phone = imsOnWifiPhone().apply { volteRegisters = false }
        phone.onSleep = { p -> if (p.overrides?.get("carrier_wfc_ims_available_bool") == "false") throw kotlinx.coroutines.CancellationException("worker stopped") }
        val outcome = runCatching { runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast) }
        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.overrides.none { it.hold == Hold.TRIAL })
        assertTrue(phone.store.state.log.any { it.kind == "interrupted" && "trial reverted" in it.text })
    }

    @Test
    fun `an interruption reverts the trial at once and is not recorded as a result`() = runBlocking {
        val phone = FakePhone()
        var polls = 0
        val screenOn = io.github.open_carrier_data.check.owner.core.Interrupt { if (phone.overrides?.isNotEmpty() == true && ++polls > 3) "screen_on" else null }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(interrupt = screenOn, basis = basis))
        assertEquals("interrupted_screen_on", result.refused)
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.overrides.isEmpty())
        assertTrue("an interruption is no failure and no result", phone.store.state.entries.none { it.entry == "volte:on" })
        assertTrue("stopped within a second or so of the screen", phone.now - 1_792_408_702_000L < 20_000)
    }

    @Test
    fun `VoNR on the S20 stops before any change, the modem cannot do it`() = runBlocking {
        val phone = FakePhone()
        val result = runner(phone).testFeature(sim, OwnerFeature.VONR, ocdOn, fast)
        assertEquals("device_not_capable", result.onReason)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `video calling on PHH IMS is not available on this build, without a trial`() = runBlocking {
        val phone = FakePhone().apply { defaultApp = mapOf("carrier_volte_available_bool" to "true") }
        val result = runner(phone).testFeature(
            sim, OwnerFeature.VIDEO_CALLING, OcdAnswer(null, "carrier_profile"),
            fast.copy(videoUnavailable = listOf("ims_stack_phh_no_video", "device_vt_flag_off"), basis = basis),
        )
        assertEquals(OwnerRunner.NOT_AVAILABLE, result.onReason)
        assertEquals(false, result.onWorks)
        assertEquals(false, result.best)
        assertTrue(result.bestWhy!!, "not available on this build" in result.bestWhy!!)
        assertTrue(phone.writes.isEmpty())
        assertEquals(OwnerRunner.NOT_AVAILABLE, phone.store.state.entries.single { it.entry == "video_calling:on" }.outcome)
    }

    @Test
    fun `video calling that IMS never adds is not supported by this IMS stack, after the trial window`() = runBlocking {
        val phone = FakePhone().apply { defaultApp = mapOf("carrier_volte_available_bool" to "true") }
        val result = runner(phone).testFeature(sim, OwnerFeature.VIDEO_CALLING, OcdAnswer(null, "carrier_profile"), fast)
        assertEquals(false, result.baseline)
        assertEquals(false, result.onWorks)
        assertEquals(OwnerRunner.IMS_STACK_NO_VIDEO, result.onReason)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `a refusal changes nothing and says why`() = runBlocking {
        val phone = FakePhone().apply { roaming = true }
        val result = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals("roaming", result.refused)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `a kept value measured not working is dropped (smart revert), one not measured stays`() = runBlocking {
        val phone = FakePhone()
        runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(1, phone.store.state.overrides.size)
        // IMS now over Wi-Fi, VoLTE not measured: the keep stays.
        phone.defaultApp = mapOf("carrier_wfc_ims_available_bool" to "true")
        phone.imsOverWifi = true
        phone.wifi = true
        runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast.copy(only = false))
        assertEquals(1, phone.store.state.overrides.size)
        assertEquals("the keep is back after the off trial's revert", mapOf("carrier_volte_available_bool" to "true"), phone.overrides)
        assertEquals(listOf(Hold.KEPT), phone.store.state.overrides.map { it.hold })
        // Measured not working: dropped.
        phone.defaultApp = null
        phone.imsOverWifi = false
        phone.imsWorks = false
        val again = runner(phone).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(true, again.baseline)
        assertEquals(false, again.baselineWorks)
        assertEquals(emptyList<LiveOverride>(), phone.store.state.overrides)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `with only one value the other is neither tried nor recorded`() = runBlocking {
        val phone = FakePhone()
        // SMS over IMS is on: "on only" observes it and changes nothing.
        runner(phone).testFeature(sim, OwnerFeature.SMS_OVER_IMS, OcdAnswer(true, "carrier_profile"), fast.copy(only = true, basis = basis))
        assertTrue(phone.writes.none { it.startsWith("set") })
        assertEquals(setOf("sms_over_ims:on"), phone.store.state.entries.map { it.entry }.toSet())
        assertEquals(true, phone.store.state.entries.single().works)
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
        // Undo is remembered: the night does not keep VoLTE on again for this SIM.
        assertEquals(listOf(sim.key to "volte:on"), phone.store.state.undone.map { it.sim to it.entry })
    }

    private val ocdRow = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "internet.lidl", "type" to "default,supl", "protocol" to "IPV4V6", "carrier" to "LIDL Internet"))

    @Test
    fun `an OCD APN row the phone lacks is tried as a carrier-ID test row and deleted by its own ID`() = runBlocking {
        val phone = FakePhone()
        val results = runner(phone).testApns(sim, listOf(ocdRow), fast.copy(basis = basis))
        assertEquals("inserted", results.single().status)
        assertEquals(false, results.single().dataWorks)
        // The report of 2026-10-20: empty numeric, the SIM's carrier ID, edited, never ia/ims/emergency.
        val values = phone.lastInsert!!
        assertEquals("", values["numeric"])
        assertEquals("", values["mcc"])
        assertEquals("", values["mnc"])
        assertEquals("2397", values["carrier_id"])
        assertEquals("1", values["infrastructure_bitmask"])
        assertEquals("0", values["apn_set_id"])
        assertEquals("1", values["edited"])
        assertEquals(listOf(1L), phone.apns.map { it.id })
        assertEquals(1L, phone.preferred)
        assertEquals(listOf("apn delete 100"), phone.writes.filter { it.startsWith("apn delete") })
        assertTrue(phone.store.state.apns.isEmpty())
        assertNull(phone.store.state.stop)
        assertEquals("inserted", phone.store.state.entries.single { it.entry.startsWith("apn:") }.outcome)
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
    fun `a null insert answer is a stop, and nothing is deleted`() = runBlocking {
        val phone = FakePhone().apply { insertAnswersNull = true }
        val results = runner(phone).testApns(sim, listOf(ocdRow), fast)
        assertEquals("insert_no_row", results.single().status)
        assertTrue(phone.store.state.stop!!.startsWith("apn_insert_no_row"))
        assertFalse(phone.writes.any { it.startsWith("apn delete") })
        assertTrue(phone.store.state.apns.isEmpty())
    }

    @Test
    fun `a change to another APN row during the trial fails the proof and stops`() = runBlocking {
        val phone = FakePhone()
        phone.onSleep = { p -> if (p.apns.size == 2 && p.apns.none { it.id == 7L }) p.apns += PhoneApnRow(7, "Other", "x", listOf("mms"), null, null, "26202") }
        runner(phone).testApns(sim, listOf(ocdRow), fast)
        assertTrue(phone.store.state.stop!!, "other rows CHANGED" in phone.store.state.stop!!)
    }

    @Test
    fun `a marked APN row the state does not hold is named for the owner, never deleted`() = runBlocking {
        val phone = FakePhone()
        phone.apns += PhoneApnRow(55, "Old (OCD Check test)", "x", listOf("default"), null, null, "26202")
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "left for the owner" in summary)
        assertEquals(listOf(1L, 55L), phone.apns.map { it.id })
        assertFalse(phone.writes.any { it.startsWith("apn delete") })
    }

    @Test
    fun `an old Wi-Fi-off record is dropped, and Wi-Fi is never switched`() = runBlocking {
        val phone = FakePhone().apply { wifi = false }
        phone.store.state = phone.store.state.copy(wifiOffSinceMs = phone.now - 60_000)
        val summary = runner(phone).reconcile(listOf(sim))
        assertTrue(summary, "Wi-Fi-off record was dropped" in summary)
        assertNull(phone.store.state.wifiOffSinceMs)
        assertEquals(false, phone.wifi)
    }
}

/** A phone whose carrier file turns VoLTE on (the default-app layer), for the off tests. */
class FakePhoneWithVolteOn : FakePhone() {
    override suspend fun carrierConfigText(): String =
        OwnerFixtures.withOverride(super.carrierConfigText(), "mConfigFromDefaultApp", mapOf("carrier_volte_available_bool" to "true"))
}
