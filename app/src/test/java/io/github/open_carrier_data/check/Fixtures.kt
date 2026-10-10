package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CarrierSettingsReading
import io.github.open_carrier_data.check.core.DataConnection
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.NetworkReading
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimReading
import java.time.LocalDate

/**
 * Test data. fixture-bundle.json holds 17 real profiles from OCD's
 * lookup.json (public commit 69c56e1f), built by tools/build_bundle.py's
 * build_bundle(): 26202 base, SPN and GID1 MVNO profiles, a 26201 stack with
 * carrier-ID profiles, AT&T with Tracfone's specific carrier ID, and profiles
 * that match on ICCID, IMSI and GID2.
 */
object Fixtures {
    val bundle: OcdBundle by lazy {
        OcdBundle.parse(requireNotNull(javaClass.getResource("/fixture-bundle.json")).readText())
    }

    val today: LocalDate = LocalDate.of(2026, 10, 10)

    fun sim(
        mccmnc: String? = "26202",
        spn: String? = "LIDL Connect",
        carrierId: Long? = 2397,
        specificCarrierId: Long? = 2397,
        gid1: String? = null,
        gid1Status: Gid1Status = if (gid1 == null) Gid1Status.EMPTY else Gid1Status.READ,
    ) = SimReading(
        slotIndex = 1,
        homeMccMnc = mccmnc,
        spn = spn,
        carrierId = carrierId,
        carrierName = carrierId?.let { "Vodafone" },
        specificCarrierId = specificCarrierId,
        specificCarrierName = specificCarrierId?.let { "Vodafone" },
        gid1 = gid1,
        gid1Status = gid1Status,
    )

    fun network(
        data: Int? = Radio.LTE,
        icon: Int? = 0,
        nrSignal: Boolean? = false,
        roaming: Boolean? = false,
        serving: String? = "26202",
    ) = NetworkReading(
        servingMccMnc = serving,
        roaming = roaming,
        dataNetworkType = data,
        voiceNetworkType = data,
        psWwanType = data,
        iconOverride = icon,
        nrSignalReported = nrSignal,
        inService = true,
    )

    fun settings(
        volte: Boolean? = true,
        wfc: Boolean? = true,
        vt: Boolean? = false,
        vonr: Boolean? = true,
        mms: Boolean? = true,
        identified: Boolean? = true,
    ) = CarrierSettingsReading(
        readable = true,
        identifiedCarrier = identified,
        booleans = buildMap {
            volte?.let { put("carrier_volte_available_bool", it) }
            wfc?.let { put("carrier_wfc_ims_available_bool", it) }
            vt?.let { put("carrier_vt_available_bool", it) }
            vonr?.let { put("vonr_enabled_bool", it) }
            mms?.let { put("enabledMMS", it) }
        },
        nrAvailabilities = listOf(1, 2),
    )

    fun readings(
        sim: SimReading? = sim(),
        network: NetworkReading? = network(),
        settings: CarrierSettingsReading? = settings(),
        connections: List<DataConnection>? = listOf(DataConnection("web.vodafone.de", listOf("internet", "supl", "xcap"), Radio.LTE)),
        device: DeviceInfo = DeviceInfo("Fairphone", "FP5", 15, RomFamily.STOCK),
        permission: Boolean = true,
        checkedOn: LocalDate = today,
    ) = PhoneReadings(
        checkedOn = checkedOn,
        device = device,
        hasTelephony = true,
        permissionGranted = permission,
        sim = sim,
        network = network,
        carrierSettings = settings,
        dataConnections = connections,
    )
}
