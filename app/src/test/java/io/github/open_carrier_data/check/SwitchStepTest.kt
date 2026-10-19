package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.Fixtures.settings
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.ConfigOrigin
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.OcdFeatureValue
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.PhoneSwitches
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SettingsScreen
import io.github.open_carrier_data.check.core.SwitchSteps
import io.github.open_carrier_data.check.core.T2Feature
import io.github.open_carrier_data.check.core.ToggleAnswer
import io.github.open_carrier_data.check.core.UserSwitch
import io.github.open_carrier_data.check.core.UserToggleRecord
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD round 23 AP3, the guided switch step: offered only where OCD's value
 * for this phone is on and the phone has the feature off; OCD's value comes
 * with its source and round 18's stack note; never a suggestion to turn a
 * voice feature off; round 10's emergency note; public Settings actions only.
 * The S20 cases use the bundled asset, whose Vodafone DE profile holds the
 * S20's stock VoLTE record (samsung_omc, on).
 */
class SwitchStepTest {
    private val asset: OcdBundle by lazy { File("src/main/assets/${OcdBundle.ASSET_NAME}").inputStream().use(OcdBundle::readGzip) }
    private val a50Stock = DeviceInfo("samsung", "SM-A505F", "a50", 14, RomFamily.STOCK)

    private fun steps(readings: PhoneReadings, bundle: OcdBundle = asset) =
        SwitchSteps.offered(readings, Matcher.resolve(bundle, readings.sim!!.identity()!!), bundle)

    @Test
    fun `OCD's value is picked as OCD Lab's ocd_value - the model's record first, else the carrier summary`() {
        val s20 = readings(device = Fixtures.s20Lineage)
        val resolution = Matcher.resolve(asset, s20.sim!!.identity()!!)
        assertEquals("open.26202.8cebd7550c67", resolution.mostSpecific!!.profileId)
        val volte = OcdFeatureValue.of(T2Feature.VOLTE, asset, resolution, Fixtures.s20Lineage)
        assertEquals(true, volte.on)
        assertEquals("device_record", volte.basis)
        assertEquals(false, volte.sameStack)
        assertEquals(CapabilityValue.CONDITIONAL, volte.summary)
        assertEquals("samsung_omc", volte.record!!.record.source)
        val wifi = OcdFeatureValue.of(T2Feature.VOWIFI, asset, resolution, Fixtures.s20Lineage)
        assertEquals(true, wifi.on)
        assertEquals("carrier_summary", wifi.basis)
        assertNull(wifi.sameStack)
        // A conditional summary without a record is no single value.
        val noRecord = OcdFeatureValue.of(T2Feature.VOLTE, Fixtures.bundle, Matcher.resolve(Fixtures.bundle, s20.sim!!.identity()!!), Fixtures.s20Lineage)
        assertNull(noRecord.on)
    }

    @Test
    fun `the S20 on LineageOS with VoLTE off in its carrier settings gets the step, with the stock-firmware note`() {
        val offered = steps(readings(device = Fixtures.s20Lineage, settings = settings(volte = false)))
        val step = offered.single()
        assertEquals(T2Feature.VOLTE, step.feature)
        assertEquals(false, step.phone.carrierSetting)
        assertEquals(
            "on: this model's stock firmware file (one source: Samsung's carrier pack, stock firmware, newest entry 2025-02), " +
                "on OCD's profile Vodafone (open.26202.8cebd7550c67). OCD's carrier summary there is conditional.",
            step.ocd.sourceLine,
        )
        val note = step.ocd.stackNote(Fixtures.s20Lineage)
        assertTrue(note, note.startsWith("This value is from this model's stock firmware file. This phone runs LineageOS"))
        assertTrue(note, "emergency calls included" in note)
        assertEquals("The carrier settings have it off (carrier_volte_available_bool = false).", step.phoneLine(Fixtures.s20Lineage))
        assertTrue(step.todo, "Settings may show no VoLTE switch" in step.todo)
        assertEquals(listOf(SettingsScreen.SIM_NETWORK, SettingsScreen.NETWORK), SwitchSteps.screens(T2Feature.VOLTE, Fixtures.s20Lineage))
    }

    @Test
    fun `the person's own switch off, read by the deep check, also offers the step`() {
        val deep = Fixtures.s20Deep().copy(volteByUser = false, wifiCallingSwitch = WifiCallingSwitch.OFF)
        val offered = steps(readings(device = Fixtures.s20Lineage, deep = deep))
        assertEquals(listOf(T2Feature.VOLTE, T2Feature.VOWIFI), offered.map { it.feature })
        assertEquals(PhoneSwitches(carrierSetting = true, userSwitch = UserSwitch.OFF), offered.first().phone)
        assertEquals(
            "The carrier settings have it on (carrier_wfc_ims_available_bool = true); your Wi-Fi calling switch is off.",
            offered.last().phoneLine(Fixtures.s20Lineage),
        )
        assertEquals(
            "OCD publishes no file of this phone model for this SIM, so this is the carrier summary over many phone " +
                "models' carrier tables, not this phone's own value.",
            offered.last().ocd.stackNote(Fixtures.s20Lineage),
        )
        // Everything on: nothing to offer.
        assertEquals(emptyList<Any>(), steps(readings(device = Fixtures.s20Lineage, deep = Fixtures.s20Deep().copy(volteByUser = true))))
    }

    @Test
    fun `never a step towards off - OCD off or no single value gives nothing`() {
        // Telekom 26201 with VoLTE made unsupported: phone on or off, the app suggests nothing.
        val telekom = "open.26201.13528695fee7"
        val unsupported = Fixtures.bundle.copy(
            profiles = Fixtures.bundle.profiles.map {
                if (it.profileId == telekom) it.copy(capabilities = it.capabilities + ("volte" to "unsupported")) else it
            },
        )
        val sim = Fixtures.sim(mccmnc = "26201", spn = "", carrierId = null, specificCarrierId = null)
        val resolution = Matcher.resolve(unsupported, sim.identity()!!)
        assertEquals(telekom, resolution.mostSpecific!!.profileId)
        assertEquals(CapabilityValue.UNSUPPORTED, resolution.answer("volte"))
        for (volte in listOf(true, false)) {
            assertEquals(emptyList<Any>(), steps(readings(sim = sim, settings = settings(volte = volte, wfc = true)), unsupported))
        }
        // Vodafone DE without records: VoLTE is conditional, so no step even with VoLTE off.
        assertEquals(emptyList<Any>(), steps(readings(settings = settings(volte = false)), Fixtures.bundle))
        // The texts the step shows never ask for anything to be turned off.
        val offOrDisable = Regex("(?i)turn(ing)? (it |\\w+ )?off|switch(ing)? (it |\\w+ )?off|disable|deactivate")
        val shown = steps(readings(device = Fixtures.s20Lineage, settings = settings(volte = false, wfc = false))).flatMap {
            listOf(it.todo, it.ocd.sourceLine, it.ocd.stackNote(Fixtures.s20Lineage), SwitchSteps.where(it.feature, Fixtures.s20Lineage))
        } + SwitchSteps.EMERGENCY_NOTE
        assertEquals(9, shown.size)
        for (line in shown) assertFalse(line, offOrDisable.containsMatchIn(line))
    }

    @Test
    fun `on Samsung's own software only Samsung's Wi-Fi calling switch counts (round 18 - no CarrierConfig comparison)`() {
        // The A50 before its DNS fix: CarrierConfig says Wi-Fi calling off, Samsung's switch on. No step.
        val a50 = readings(device = a50Stock, settings = settings(volte = false, wfc = false), deep = Fixtures.deep(wifiCalling = WifiCallingSwitch.ON))
        assertEquals(emptyList<Any>(), steps(a50))
        assertNull(PhoneSwitches.of(T2Feature.VOLTE, a50).carrierSetting)
        // Samsung's switch off: the Wi-Fi calling step, through the phone app's call settings.
        val off = steps(readings(device = a50Stock, deep = Fixtures.deep(wifiCalling = WifiCallingSwitch.OFF))).single()
        assertEquals(T2Feature.VOWIFI, off.feature)
        assertEquals("Samsung's Wi-Fi calling switch is off.", off.phoneLine(a50Stock))
        assertEquals(listOf(SettingsScreen.CALL_SETTINGS, SettingsScreen.NETWORK), SwitchSteps.screens(T2Feature.VOWIFI, a50Stock))
    }

    @Test
    fun `the emergency note is round 10's, and the Settings screens are public SDK actions`() {
        assertTrue(SwitchSteps.EMERGENCY_NOTE.startsWith("Emergency calls are not tested."))
        assertTrue("never places one" in SwitchSteps.EMERGENCY_NOTE)
        // The actions the phone layer sends: public SDK constants only (android.jar), none that writes.
        val source = File("src/main/java/io/github/open_carrier_data/check/phone/SettingsScreens.kt").readText()
        val actions = Regex("Intent\\(([A-Za-z_.]+)\\)").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("Settings.ACTION_NETWORK_OPERATOR_SETTINGS", "TelecomManager.ACTION_SHOW_CALL_SETTINGS", "Settings.ACTION_WIRELESS_SETTINGS"),
            actions,
        )
        assertEquals(SettingsScreen.entries.size, actions.size)
        assertFalse("WIFI_CALLING_SETTINGS" in source)
    }

    @Test
    fun `a kept step records user_toggle only when the person turned it on`() {
        val step = steps(readings(device = Fixtures.s20Lineage, settings = settings(volte = false))).single()
        fun kept(answer: ToggleAnswer, after: PhoneSwitches?) = UserToggleRecord(
            date = "2026-10-19", model = "SM-G981B", codename = "x1s", romFamily = "lineageos", androidMajor = 16,
            homeMccMnc = "26202", spn = "LIDL Connect", ocdProfile = step.ocd.profile!!.profileId, feature = step.feature,
            ocdValue = "on", ocdBasis = step.ocd.basis, ocdSource = step.ocd.record?.record?.source,
            ocdStack = step.ocd.record?.record?.stack, sameStack = step.ocd.sameStack, screen = SettingsScreen.SIM_NETWORK.wire,
            before = step.phone, after = after, answer = answer,
            configOrigin = if (answer == ToggleAnswer.TURNED_ON) ConfigOrigin.USER_TOGGLE else ConfigOrigin.AS_FOUND,
        )
        val on = kept(ToggleAnswer.TURNED_ON, PhoneSwitches(carrierSetting = false, userSwitch = UserSwitch.ON))
        assertEquals("VoLTE: you turned it on in this SIM's mobile network page; re-read: switch now on.", on.plain)
        assertTrue("\"configOrigin\":\"user_toggle\"" in CallTestLog().plus(on).encode())
        val none = kept(ToggleAnswer.NO_SWITCH, step.phone)
        assertEquals("VoLTE: Settings showed no such switch in this SIM's mobile network page; the app's re-read shows no change.", none.plain)
        assertTrue("\"configOrigin\":\"as_found\"" in CallTestLog().plus(none).encode())
    }
}
