package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.ApnEdited
import io.github.open_carrier_data.check.core.ApnList
import io.github.open_carrier_data.check.core.ApnRow
import io.github.open_carrier_data.check.core.CarrierSettingsReading
import io.github.open_carrier_data.check.core.DataConnection
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.EpdgPhoneLookup
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.ImsDisconnect
import io.github.open_carrier_data.check.core.ImsReading
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.IwlanHold
import io.github.open_carrier_data.check.core.IwlanReading
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.NetworkOffer
import io.github.open_carrier_data.check.core.NetworkReading
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimReading
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import io.github.open_carrier_data.check.core.WifiResolver
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
        device: DeviceInfo = DeviceInfo("Fairphone", "FP5", "FP5", 15, RomFamily.STOCK),
        permission: Boolean = true,
        checkedOn: LocalDate = today,
        deep: DeepCheckReading? = null,
    ) = PhoneReadings(
        checkedOn = checkedOn,
        device = device,
        hasTelephony = true,
        permissionGranted = permission,
        sim = sim,
        network = network,
        carrierSettings = settings,
        dataConnections = connections,
        deep = deep,
    )

    val a50Apns = ApnList(
        readable = true,
        rows = listOf(
            ApnRow("web.vodafone.de", listOf("default", "supl", "xcap"), "IPV4V6", "IPV4V6", true, null, ApnEdited.NO),
            ApnRow("IMS", listOf("ims"), "IPV4V6", "IPV4V6", true, null, ApnEdited.NO),
        ),
    )

    /** A deep check result; by default the A50 before its DNS fix: VoLTE signed in, Wi-Fi calling off. */
    fun deep(
        registered: Boolean? = true,
        transport: ImsTransport = ImsTransport.LTE,
        services: List<ImsService> = listOf(ImsService.MMTEL, ImsService.SMSIP),
        source: ImsSource = ImsSource.SAMSUNG,
        wifiCalling: WifiCallingSwitch = WifiCallingSwitch.OFF,
        epdg: EpdgName = EpdgName.RESOLVES,
        lookup: LookupNetwork = LookupNetwork.WIFI,
        privateDns: Boolean? = false,
        apns: ApnList? = a50Apns,
    ) = DeepCheckReading(
        ims = if (source != ImsSource.SAMSUNG) {
            ImsReading(source)
        } else {
            ImsReading(
                source = source,
                registered = registered,
                state = registered?.let { if (it) "registered" else "not_registered" },
                transport = transport.takeIf { registered == true },
                services = if (registered == true) services else emptyList(),
                mno = "Vodafone_DE",
            )
        },
        wifiCallingSwitch = wifiCalling,
        epdgName = epdg,
        apnList = apns,
        lookupNetwork = lookup,
        privateDnsActive = privateDns,
    )

    val s20Lineage = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)

    /**
     * The S20 on LineageOS with the LIDL SIM, as the live dumps read on
     * 2026-10-19 at 13:39: Android's IMS signed in, the IMS data connection on
     * LTE while the registration log still says Wi-Fi, Wi-Fi calling switched
     * on, the phone's own ePDG lookup empty 39 minutes before, and Android's
     * one-hour retry wait with 21 minutes left. The home router is the resolver.
     */
    fun s20Deep(
        registered: Boolean = true,
        transport: ImsTransport? = ImsTransport.LTE,
        logTransport: ImsTransport? = ImsTransport.IWLAN,
        vops: LteVops? = LteVops.SUPPORTED,
        lookupResolved: Boolean = false,
        heldBack: IwlanHold? = IwlanHold.DATA_SERVICE_RETRY,
        ping: EpdgName = EpdgName.DOES_NOT_RESOLVE,
    ) = DeepCheckReading(
        ims = ImsReading(
            source = ImsSource.AOSP,
            registered = registered,
            state = if (registered) "registered" else "not_registered",
            transport = transport.takeIf { registered },
            services = if (registered) listOf(ImsService.MMTEL, ImsService.SMSIP) else emptyList(),
            imsPdn = transport,
            registrationLogTransport = logTransport.takeIf { registered },
            lastDisconnect = ImsDisconnect(0, "CODE_UNSPECIFIED"),
        ),
        wifiCallingSwitch = WifiCallingSwitch.ON,
        epdgName = ping,
        apnList = a50Apns,
        lookupNetwork = LookupNetwork.WIFI,
        privateDnsActive = false,
        networkOffer = NetworkOffer(vops, null),
        iwlan = IwlanReading(
            phoneLookup = EpdgPhoneLookup(resolved = lookupResolved, standardName = true, ageMinutes = 39),
            tunnelUp = false,
            lastRefusal = "IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED",
            selectionFailed = true,
            heldBackBy = heldBack,
            heldBackMinutes = heldBack?.let { 21L },
        ),
        wifiResolver = WifiResolver.LOCAL,
    )
}
