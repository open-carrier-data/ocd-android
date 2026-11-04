package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.EmergencyParser
import io.github.open_carrier_data.check.owner.core.EmergencySeen
import io.github.open_carrier_data.check.owner.core.ImsFlags
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.Refusals
import io.github.open_carrier_data.check.owner.core.RegistrationParser
import io.github.open_carrier_data.check.owner.core.RegistryText
import io.github.open_carrier_data.check.owner.core.RoamingParser
import io.github.open_carrier_data.check.owner.core.SafetyInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the lab's LB6 parser and refusal tests, with the owner's 10-minute rule. */
class RefusalTest {
    private val registry = OwnerFixtures.REGISTRY
    private val phone = OwnerFixtures.PHONE
    private val now = 1_792_408_702_000L

    private fun input(registry: String? = this.registry, phone: String? = this.phone, ecm: String? = "", uptime: Long = 3_600_000, seen: EmergencySeen? = null, home: String? = "26202") =
        SafetyInputs(registry, phone, ecm, slot = 0, homeMccMnc = home, nowMs = now, uptimeMs = uptime, seen = seen)

    @Test
    fun `the lab's parsers on the LIDL fixtures`() {
        val home = RoamingParser.parse(RegistryText.block(registry, 0))
        assertEquals(false, home.roaming)
        assertEquals("26202", home.servingPlmn)
        val state = RoamingParser.parse(RegistryText.block(registry.replaceFirst("domain=PS transportType=WWAN registrationState=HOME", "domain=PS transportType=WWAN registrationState=ROAMING"), 0))
        assertEquals(listOf("wwan_ps_state"), state.why)
        val national = registry.replaceFirst(
            "domain=CS transportType=WWAN registrationState=HOME networkRegistrationState=HOME",
            "domain=CS transportType=WWAN registrationState=HOME networkRegistrationState=ROAMING",
        )
        assertEquals(true, RoamingParser.parse(RegistryText.block(national, 0)).roaming)
        assertEquals(listOf("display_is_roaming"), RoamingParser.parse(RegistryText.block(registry.replaceFirst("isRoaming=false", "isRoaming=true"), 0)).why)
        assertNull("not registered: unreadable", RoamingParser.parse(RegistryText.block(registry, 1)).roaming)

        val clear = EmergencyParser.parse(registry, phone, "")
        assertEquals(false, clear.ecbm)
        assertEquals(false, clear.inEmergencyCall)
        assertEquals(false, clear.emergencySmsMode)
        assertEquals(false, clear.outgoingEmergencyCallSinceBoot)
        assertEquals(true, EmergencyParser.parse(registry.replaceFirst("mECBMDuration=0", "mECBMDuration=300000"), phone, "").ecbm)
        assertEquals(true, EmergencyParser.parse(registry, phone.replaceFirst("isInEcm()=false", "isInEcm()=true"), "").ecbm)
        assertEquals(true, EmergencyParser.parse(registry, phone, "true\n").ecbm)
        assertNull("no isInEcm line: unreadable", EmergencyParser.parse(registry, "", "").ecbm)
        val placed = registry.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=[EmergencyNumber: 112, countryIso=de]")
        assertEquals(true, EmergencyParser.parse(placed, phone, "").outgoingEmergencyCallSinceBoot)

        assertFalse(RegistryText.anyCallActive(registry))
        assertTrue(RegistryText.anyCallActive(registry.replaceFirst("mCallState=0", "mCallState=2")))
        assertTrue("unreadable counts as busy", RegistryText.anyCallActive(""))
        assertEquals("lte", RegistrationParser.wwanPs(RegistryText.block(registry, 0))!!.rat)
        assertEquals(true, ImsFlags.parse(phone, 0).volteByUser)
        assertEquals(true, ImsFlags.parse(phone, 1).volteByPlatform)
    }

    @Test
    fun `home in Germany with a clear emergency state is allowed`() {
        assertNull(Refusals.check(input()).refusal)
    }

    @Test
    fun `every refusal fails closed`() {
        val placed = registry.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=[EmergencyNumber: 112, countryIso=de]")
        val cases = listOf(
            input(registry = null) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(phone = "") to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(phone = phone.replace("isInEcm()=", "x=")) to Refusal.EMERGENCY_STATE_UNREADABLE,
            input(phone = phone.replaceFirst("isInEmergencyCall()=false", "isInEmergencyCall()=true")) to Refusal.EMERGENCY_CALL_ACTIVE,
            input(ecm = "true") to Refusal.EMERGENCY_CALLBACK_MODE,
            input(phone = phone.replaceFirst("isInEmergencySmsMode=false", "isInEmergencySmsMode=true")) to Refusal.EMERGENCY_SMS_MODE,
            input(registry = placed) to Refusal.EMERGENCY_CALL_RECENT,
            input(uptime = 9 * 60_000) to Refusal.JUST_BOOTED,
            input(registry = registry.replaceFirst("mCallState=0", "mCallState=1")) to Refusal.CALL_ACTIVE,
            input(registry = registry.replace("isRoaming=false", "isRoaming=true")) to Refusal.ROAMING,
            input(home = "20801") to Refusal.ROAMING,
            input(registry = registry.replace("registrationState=HOME", "registrationState=NOT_REG_OR_SEARCHING")) to Refusal.ROAMING_UNREADABLE,
        )
        for ((given, expected) in cases) assertEquals(expected, Refusals.check(given).refusal)
    }

    @Test
    fun `an emergency call refuses for 10 minutes from the first sighting, and a reboot starts the count again`() {
        val placed = registry.replaceFirst("mOutgoingCallEmergencyNumber=null", "mOutgoingCallEmergencyNumber=[EmergencyNumber: 112, countryIso=de]")
        val first = Refusals.check(input(registry = placed))
        assertEquals(Refusal.EMERGENCY_CALL_RECENT, first.refusal)
        val seen = first.seen!!
        assertEquals(now, seen.firstSeenAtMs)
        val nine = Refusals.check(input(registry = placed, seen = seen.copy(firstSeenAtMs = now - 9 * 60_000)))
        assertEquals(Refusal.EMERGENCY_CALL_RECENT, nine.refusal)
        val eleven = Refusals.check(input(registry = placed, seen = seen.copy(firstSeenAtMs = now - 11 * 60_000)))
        assertNull(eleven.refusal)
        // A sighting from before the boot does not count; the new one starts at now.
        val stale = Refusals.check(input(registry = placed, uptime = 20 * 60_000, seen = seen.copy(firstSeenAtMs = now - 30 * 60_000)))
        assertEquals(Refusal.EMERGENCY_CALL_RECENT, stale.refusal)
    }
}
