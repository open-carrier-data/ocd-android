package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.EmergencyApi
import io.github.open_carrier_data.check.owner.core.EmergencyParser
import io.github.open_carrier_data.check.owner.core.ImsApi
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.Refusals
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.SafetyInputs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privileged route reads the emergency state and IMS through the system
 * APIs: the phone service's dump is closed to an app (ActivityManager wants
 * PACKAGE_USAGE_STATS), and every "Test now" on the S20 (2026-10-19) was
 * refused with emergency_state_unreadable.
 */
class PrivilegedReadTest {
    private val registry = OwnerFixtures.REGISTRY
    private val now = 1_792_408_702_000L
    private val clear = EmergencyApi(ecbm = false, inEmergencyCall = false, emergencySmsMode = false)

    private fun input(api: EmergencyApi?, registry: String? = this.registry, phone: String? = null, uptime: Long = 3_600_000) =
        SafetyInputs(registry, phone, null, slot = 0, homeMccMnc = "26202", nowMs = now, uptimeMs = uptime, seen = null, emergencyApi = api)

    @Test
    fun `the system APIs stand in for the phone dump`() {
        assertNull(Refusals.check(input(clear)).refusal)
        val reading = EmergencyParser.parse(registry, null, null, clear)
        assertEquals(false, reading.ecbm)
        assertEquals(false, reading.inEmergencyCall)
        assertEquals(false, reading.emergencySmsMode)
        assertEquals("the registry still gives the emergency number", false, reading.outgoingEmergencyCallSinceBoot)
    }

    @Test
    fun `each API answer refuses, and a failed call fails closed`() {
        val placed = registry.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=[EmergencyNumber: 112, countryIso=de]")
        val cases = listOf(
            input(clear.copy(inEmergencyCall = true)) to Refusal.EMERGENCY_CALL_ACTIVE,
            input(clear.copy(ecbm = true)) to Refusal.EMERGENCY_CALLBACK_MODE,
            input(clear.copy(emergencySmsMode = true)) to Refusal.EMERGENCY_SMS_MODE,
            input(clear.copy(inEmergencyCall = null)) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(clear.copy(ecbm = null)) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(clear.copy(emergencySmsMode = null)) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(null) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(clear, registry = null) to Refusal.EMERGENCY_STATE_UNREADABLE,
            // The registry's callback-mode timers and emergency number count with the API too.
            input(clear, registry = registry.replaceFirst("mECBMDuration=0", "mECBMDuration=300000")) to Refusal.EMERGENCY_CALLBACK_MODE,
            input(clear, registry = registry.replaceFirst("mSCBMDuration=0", "mSCBMDuration=300000")) to Refusal.EMERGENCY_SMS_MODE,
            input(clear, registry = placed) to Refusal.EMERGENCY_CALL_RECENT,
            input(clear, uptime = 9 * 60_000) to Refusal.JUST_BOOTED,
            input(clear, registry = registry.replaceFirst("mCallState=0", "mCallState=1")) to Refusal.CALL_ACTIVE,
            input(clear, registry = registry.replace("isRoaming=false", "isRoaming=true")) to Refusal.ROAMING,
        )
        for ((given, expected) in cases) assertEquals("$given", expected, Refusals.check(given).refusal)
    }

    @Test
    fun `an API saying false does not hide a dump saying true`() {
        val dumped = OwnerFixtures.PHONE.replaceFirst("isInEcm()=false", "isInEcm()=true")
        assertEquals(Refusal.EMERGENCY_CALLBACK_MODE, Refusals.check(input(clear, phone = dumped)).refusal)
    }

    @Test
    fun `the log names what was unreadable`() {
        val unreadable = input(clear.copy(ecbm = null, emergencySmsMode = null))
        assertEquals(listOf("emergency callback mode", "emergency SMS mode"), Refusals.unreadable(unreadable, Refusals.check(unreadable)))
        val nothing = input(null)
        assertEquals(listOf("phone dump and system API"), Refusals.unreadable(nothing, Refusals.check(nothing)))
    }

    @Test
    fun `ImsMmTelManager's answers become the dump parser's state`() {
        val wifi = ImsApi(ImsApi.REGISTERED, ImsApi.TRANSPORT_WLAN, voice = true, video = false, sms = true, volteByUser = true, vtByUser = false, wfcByUser = true).state()
        assertEquals(true, wifi.registered)
        assertEquals("registered", wifi.state)
        assertEquals(ImsTransport.IWLAN, wifi.logTransport)
        assertEquals(listOf(ImsService.MMTEL, ImsService.SMSIP), wifi.services)
        val lte = ImsApi(ImsApi.REGISTERED, ImsApi.TRANSPORT_WWAN, voice = true, video = true, sms = false).state()
        assertEquals(ImsTransport.WWAN, lte.logTransport)
        assertEquals(listOf(ImsService.MMTEL, ImsService.MMTEL_VIDEO), lte.services)
        val off = ImsApi(ImsApi.NOT_REGISTERED, -1, voice = false, video = false, sms = false).state()
        assertEquals(false, off.registered)
        assertNull(off.logTransport)
        assertTrue(off.services.isEmpty())
        assertEquals(false, ImsApi(ImsApi.REGISTERING, -1, false, false, false).state().registered)
        assertNull("no state answer", ImsApi(null, null, null, null, null).state().registered)
        assertNull("registered, but no capabilities: never service gone", ImsApi(ImsApi.REGISTERED, ImsApi.TRANSPORT_WWAN, null, null, null).state().registered)
        val flags = ImsApi(ImsApi.REGISTERED, 1, true, false, true, volteByUser = false, vtByUser = true, wfcByUser = null).flags()
        assertEquals(false, flags.volteByUser)
        assertEquals(true, flags.vtByUser)
        assertNull(flags.wfcByUser)
    }

    private val s20 = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val sim = OwnerFixtures.SIM
    private val ocdOn = OcdAnswer(true, "device_record", "open.26202.aaaaaaaaaaaa", recordSource = "samsung_omc", recordStack = "stock", sameStack = false)
    private val fast = RunOptions(settleMs = 30_000, restoreMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000)

    @Test
    fun `the observation reads IMS from the API when there is no dump`() = runBlocking {
        val phone = FakePhone().apply {
            privileged = true
            defaultApp = mapOf("carrier_volte_available_bool" to "true", "carrier_wfc_ims_available_bool" to "true")
            imsOverWifi = true
            wifi = true
        }
        assertNull(phone.phoneText())
        val o = OwnerSession(phone, phone.store, sim).observe()
        assertEquals(true, o.ims.registered)
        assertEquals("the IMS data connection decides the transport", ImsTransport.IWLAN, o.ims.transport)
        assertTrue(ImsService.MMTEL in o.ims.services)
        assertEquals(true, o.flags.volteByUser)
    }

    @Test
    fun `a privileged run measures VoLTE inside the Wi-Fi-calling-off override, Wi-Fi stays on`() = runBlocking {
        val phone = FakePhone().apply {
            privileged = true
            defaultApp = mapOf("carrier_volte_available_bool" to "true", "carrier_wfc_ims_available_bool" to "true")
            imsOverWifi = true
            wifi = true
        }
        val result = OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertNull(result.refused)
        assertEquals(true, result.baselineWorks)
        assertEquals(OwnerRunner.VOLTE_METHOD_WFC_OVERRIDE, result.method)
        assertEquals(true, result.offWorks)
        assertEquals(true, phone.wifi)
        assertNull(phone.store.state.stop)
        assertTrue(phone.overrides.isNullOrEmpty())
    }

    @Test
    fun `a privileged run turns VoLTE on and keeps it`() = runBlocking {
        val phone = FakePhone().apply { privileged = true }
        val result = OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertNull(result.refused)
        assertEquals(true, result.onWorks)
        assertTrue(result.applied)
    }

    @Test
    fun `a privileged run whose emergency calls fail changes nothing`() = runBlocking {
        val phone = FakePhone().apply {
            privileged = true
            emergencyApiAnswers = false
        }
        val result = OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        assertEquals(Refusal.EMERGENCY_STATE_UNREADABLE.wire, result.refused)
        assertTrue(phone.writes.isEmpty())
        assertTrue(phone.store.state.log.any { it.kind == "unreadable" && "emergency callback mode" in it.text })
        val apns = OwnerRunner(phone, phone.store, s20).testApns(sim, listOf(OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "internet.lidl", "type" to "default,supl", "protocol" to "IPV4V6", "carrier" to "LIDL Internet"))), fast)
        assertEquals("refused_emergency_state_unreadable", apns.single().status)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `a privileged run refuses in emergency callback mode and in emergency SMS mode`() = runBlocking {
        for (setup in listOf<(FakePhone) -> Unit>({ it.ecbm = true }, { it.emergencySms = true })) {
            val phone = FakePhone().apply { privileged = true }.also(setup)
            val result = OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.SMS_OVER_IMS, OcdAnswer(true, "carrier_profile"), fast)
            assertTrue(result.refused, result.refused in setOf(Refusal.EMERGENCY_CALLBACK_MODE.wire, Refusal.EMERGENCY_SMS_MODE.wire))
            assertFalse(phone.writes.any { it.startsWith("set") })
        }
    }
}

/**
 * The APN trial on the S20 (2026-10-19): TelephonyProvider merged OCD rows
 * that matched a stock row on its UNIQUE columns into that row, answered
 * with no row, and the revert then deleted the stock rows by their new
 * marker name. Since 2026-10-20 the test row is keyed by the SIM's carrier
 * ID with an empty numeric (AOSP Settings' way), so it cannot match a stock
 * row; an OCD row the phone already has is still not inserted; a null insert
 * answer stops; nothing is ever deleted by marker.
 */
class ApnMergeTest {
    private val s20 = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val sim = OwnerFixtures.SIM
    private val fast = RunOptions(settleMs = 30_000, restoreMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000)

    /** The fake's stock row is web.vodafone.de [default,supl] IPV4V6; this one differs only in its types. */
    private val sameKey = OcdApnRow(mapOf("mcc" to "262", "mnc" to "02", "apn" to "web.vodafone.de", "type" to "default", "protocol" to "IPV4V6", "carrier" to "Vodafone"))

    @Test
    fun `a row the phone has already (the same provider key) is not inserted`() = runBlocking {
        val phone = FakePhone().apply { privileged = true }
        val results = OwnerRunner(phone, phone.store, s20).testApns(sim, listOf(sameKey), fast)
        assertEquals(OwnerRunner.SAME_AS_PHONE_ROW, results.single().status)
        assertTrue(phone.writes.isEmpty())
        assertEquals("LIDL Internet", phone.apns.single().name)
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `an unreadable conflict check refuses the row`() = runBlocking {
        val phone = FakePhone().apply { conflictsUnreadable = true }
        val results = OwnerRunner(phone, phone.store, s20).testApns(sim, listOf(sameKey), fast)
        assertEquals("refused_apn_table_unreadable", results.single().status)
        assertTrue(phone.writes.isEmpty())
    }

    @Test
    fun `the carrier-ID test row cannot merge into the stock row even when the lookup misses it`() = runBlocking {
        val phone = FakePhone().apply { conflictsBlind = true }
        val results = OwnerRunner(phone, phone.store, s20).testApns(sim, listOf(sameKey), fast)
        assertEquals("inserted", results.single().status)
        assertEquals("the stock row is untouched", "LIDL Internet", phone.apns.single { it.id == 1L }.name)
        assertEquals(listOf(1L), phone.apns.map { it.id })
        assertEquals(listOf("apn delete 100"), phone.writes.filter { it.startsWith("apn delete") })
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `a crash leftover is found only as the test row itself, added after the record`() = runBlocking {
        val phone = FakePhone()
        // A phone row that carries the marker (an old merge) and the same key as the trial's record: never deleted.
        phone.apns[0] = phone.apns[0].copy(name = "LIDL Internet (OCD Check test)")
        val record = io.github.open_carrier_data.check.owner.core.LiveApn(
            sim.key, null, "web.vodafone.de", phone.apns[0].key, io.github.open_carrier_data.check.owner.core.Hold.TRIAL,
            phone.now, 1L, true, madePreferred = false, maxIdBefore = 1L, testName = "LIDL Internet (OCD Check test)", carrierId = 2397,
        )
        phone.store.state = phone.store.state.copy(apns = listOf(record))
        OwnerRunner(phone, phone.store, s20).revertApn(sim, record)
        assertEquals(listOf(1L), phone.apns.map { it.id })
        assertFalse(phone.writes.any { it.startsWith("apn delete") })
        // The test row itself (after the record, its own name, carrier ID and empty numeric) is.
        phone.apns += io.github.open_carrier_data.check.owner.core.PhoneApnRow(120, "LIDL Internet (OCD Check test)", "internet.lidl", listOf("default"), "IPV4V6", null, "", 2397)
        phone.store.state = phone.store.state.copy(apns = listOf(record))
        OwnerRunner(phone, phone.store, s20).revertApn(sim, record)
        assertEquals(listOf(1L), phone.apns.map { it.id })
        assertEquals(listOf("apn delete 120"), phone.writes.filter { it.startsWith("apn delete") })
    }

    @Test
    fun `the lookups mirror the provider's UNIQUE columns and defaults, and the test row's key is its own`() {
        val identity = io.github.open_carrier_data.check.owner.core.ApnColumns.identity(sameKey)
        val match = io.github.open_carrier_data.check.owner.core.ApnColumns.conflictMatch(identity).toMap()
        assertEquals(22, match.size)
        assertEquals("web.vodafone.de", match["apn"])
        assertEquals("IPV4V6", match["protocol"])
        assertEquals("roaming protocol not given: the provider's default", "IP", match["roaming_protocol"])
        assertEquals("1", match["owned_by"])
        assertEquals("-1", match["carrier_id"])
        assertEquals("3", match["infrastructure_bitmask"])
        assertFalse("name and type are not UNIQUE columns", "name" in match || "type" in match)
        val test = io.github.open_carrier_data.check.owner.core.ApnColumns.conflictMatch(io.github.open_carrier_data.check.owner.core.ApnColumns.testRow(sameKey, 2397)).toMap()
        assertEquals("", test["numeric"])
        assertEquals("2397", test["carrier_id"])
        assertEquals("1", test["infrastructure_bitmask"])
        val quoted = OcdApnRow(sameKey.attributes + ("apn" to "o'brien"))
        val argv = io.github.open_carrier_data.check.owner.core.OwnerCommand.APN_CONFLICTS.argv(
            io.github.open_carrier_data.check.owner.core.OwnerArgs(binds = io.github.open_carrier_data.check.owner.core.ApnColumns.identity(quoted)),
        )!!
        assertEquals(listOf("content", "query", "--uri", "content://telephony/carriers", "--projection", "_id", "--where"), argv.dropLast(1))
        assertTrue(argv.last(), "apn='o''brien'" in argv.last())
        assertTrue(argv.last().startsWith("numeric='26202' AND mcc='262' AND mnc='02' AND apn="))
    }
}
