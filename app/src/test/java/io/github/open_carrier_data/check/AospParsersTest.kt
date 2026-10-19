package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.AospImsParser
import io.github.open_carrier_data.check.core.CallBearer
import io.github.open_carrier_data.check.core.CallState
import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsDisconnect
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.IwlanEvent
import io.github.open_carrier_data.check.core.IwlanHold
import io.github.open_carrier_data.check.core.IwlanLogParser
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.PreciseCallState
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import io.github.open_carrier_data.check.core.WifiResolver
import io.github.open_carrier_data.check.core.WifiResolverParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AOSP IMS reader's parsers on OCD Lab's fixtures (tools/ocd_lab/fixtures
 * in OCD's private repository, main 0f21d239), copied to
 * src/test/resources/lab/. The S20 printed them: LineageOS 23.2 with PHH IMS,
 * AOSP IWLAN and a LIDL Connect SIM, cell identities and addresses removed.
 * Each test mirrors one of the lab's tests in tools/test_ocd_lab.py.
 */
class AospParsersTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/lab/$name")) { name }.readText()

    private val registryNoSim = fixture("registry_s20_no_sim.txt")
    private val registryLidl = fixture("registry_s20_lidl_lte.txt")
    private val phone = fixture("phone_lineage_two_phones.txt")
    private val iwlan = fixture("iwlan_lidl_backoff_then_up.log")
    private val selectorFailed = fixture("iwlan_lidl_selector_failed.log")

    private val slot0 = SimSlot.of(0)!!
    private val slot1 = SimSlot.of(1)!!
    private val lidlName = HomeNetwork.of("26202")!!.epdgFqdn

    private fun lines(text: String) = text.lineSequence()

    /** The lab's in_call(): the LIDL registry with a call up on [code]. */
    private fun inCall(code: Int, foreground: Int = 1) = registryLidl
        .replaceFirst("mCallState=0", "mCallState=2")
        .replaceFirst("Foreground call state: 0", "Foreground call state: $foreground")
        .replaceFirst("mCallNetworkType=0", "mCallNetworkType=$code")

    // --- test_registry -------------------------------------------------------------------------

    @Test
    fun `registry without a SIM - VoPS not supported, call idle, another phone absent`() {
        val phone0 = RegistryParser.parse(lines(registryNoSim), slot0)!!
        assertEquals(LteVops.NOT_SUPPORTED, phone0.lteVops)
        assertNull(phone0.nrVops)
        assertEquals(CallState.IDLE, phone0.call.state)
        assertEquals(PreciseCallState.IDLE, phone0.call.foreground)
        assertEquals(0, phone0.call.networkType)
        assertEquals(emptyList<ImsTransport>(), phone0.imsPdn)
        assertNull(RegistryParser.parse(lines(registryNoSim), SimSlot.of(3)!!))
        // Phone 1's block ends at "local logs:", so phone 0's VoPS lines do not leak into it.
        assertNull(RegistryParser.parse(lines(registryNoSim), slot1)!!.lteVops)
    }

    // --- test_registry_lidl_fields --------------------------------------------------------------

    @Test
    fun `registry with the LIDL SIM - VoPS supported and the IMS data connection on LTE`() {
        val phone0 = RegistryParser.parse(lines(registryLidl), slot0)!!
        assertEquals(LteVops.SUPPORTED, phone0.lteVops)
        assertNull(phone0.nrVops)
        // A stale WLAN entry still says HANDOVERINPROGRESS; only the CONNECTED one on LTE counts (2026-10-19).
        assertEquals(listOf(ImsTransport.LTE), phone0.imsPdn)
        assertEquals(CallState.IDLE, phone0.call.state)
    }

    // --- test_call_type_reading -----------------------------------------------------------------

    @Test
    fun `mCallNetworkType - 18 Wi-Fi, 13 LTE, 20 NR, 16 a circuit-switched call`() {
        for ((code, bearer) in listOf(18 to CallBearer.VOWIFI, 13 to CallBearer.VOLTE, 20 to CallBearer.VONR, 16 to CallBearer.CS)) {
            val call = RegistryParser.parse(lines(inCall(code)), slot0)!!.call
            assertEquals(CallState.OFFHOOK, call.state)
            assertEquals(PreciseCallState.ACTIVE, call.foreground)
            assertEquals(code, call.networkType)
            assertEquals(bearer, CallBearer.of(call.networkType))
        }
        assertNull(CallBearer.of(RegistryParser.parse(lines(registryLidl), slot0)!!.call.networkType))
    }

    // --- test_aosp_ims and ims_with_pdn ---------------------------------------------------------

    @Test
    fun `Android's IMS - phone 0 not registered, phone 1 registered over Wi-Fi with calls and SMS`() {
        val idle = AospImsParser.parse(lines(phone), slot0)!!
        assertEquals(false, idle.registered)
        assertEquals("not_registered", idle.state)
        assertNull(idle.logTransport)
        assertEquals(emptyList<ImsService>(), idle.services)
        assertEquals(false, idle.wifiCallingByUser)

        val wifi = AospImsParser.parse(lines(phone), slot1)!!
        assertEquals(true, wifi.registered)
        assertEquals("registered", wifi.state)
        assertEquals(ImsTransport.IWLAN, wifi.logTransport)
        assertEquals(listOf(ImsService.MMTEL, ImsService.SMSIP), wifi.services)
        assertEquals(true, wifi.wifiCallingByUser)

        assertNull(AospImsParser.parse(lines(phone), SimSlot.of(2)!!))
    }

    @Test
    fun `the IMS data connection wins over a stale registration log (lab ims_with_pdn)`() {
        val wifi = AospImsParser.parse(lines(phone), slot1)
        val onLte = RegistryParser.parse(lines(registryLidl), slot0)
        val fixed = AospImsParser.reading(wifi, onLte, dumpRead = true)
        assertEquals(ImsSource.AOSP, fixed.source)
        assertEquals(ImsTransport.LTE, fixed.transport)
        assertEquals(ImsTransport.LTE, fixed.imsPdn)
        assertEquals(ImsTransport.IWLAN, fixed.registrationLogTransport)

        // Without a registry, or with the PDN on two transports, the log's transport stands.
        assertEquals(ImsTransport.IWLAN, AospImsParser.reading(wifi, null, dumpRead = true).transport)
        val both = onLte!!.copy(imsPdn = listOf(ImsTransport.IWLAN, ImsTransport.LTE))
        val logOnly = AospImsParser.reading(wifi, both, dumpRead = true)
        assertEquals(ImsTransport.IWLAN, logOnly.transport)
        assertNull(logOnly.registrationLogTransport)

        // The PDN on the same transport as the log: nothing stale to keep.
        val sameWifi = AospImsParser.reading(wifi, onLte.copy(imsPdn = listOf(ImsTransport.IWLAN)), dumpRead = true)
        assertEquals(ImsTransport.IWLAN, sameWifi.transport)
        assertNull(sameWifi.registrationLogTransport)

        // Not registered: no transport, whatever the PDN says.
        val idle = AospImsParser.reading(AospImsParser.parse(lines(phone), slot0), onLte, dumpRead = true)
        assertEquals(false, idle.registered)
        assertNull(idle.transport)

        assertEquals(ImsSource.NOT_FOUND, AospImsParser.reading(null, onLte, dumpRead = true).source)
        assertEquals(ImsSource.UNREADABLE, AospImsParser.reading(null, null, dumpRead = false).source)
    }

    @Test
    fun `the last sign-out reason keeps the code and Android's name, never the message`() {
        val text = phone.replace(
            "        IMS features registration state: MMTEL=registered",
            "        2026-10-19T06:12:31.625675 - handleImsUnregistered: onImsMmTelDisconnected imsRadioTech=ImsReasonInfo :: " +
                "{1000 : CODE_LOCAL_ILLEGAL_ARGUMENT, 0, sip:+491700000001@ims.example 10.20.30.40}\n" +
                "        IMS features registration state: MMTEL=registered",
        )
        val state = AospImsParser.parse(lines(text), slot1)!!
        assertEquals(ImsDisconnect(1000, "CODE_LOCAL_ILLEGAL_ARGUMENT"), state.lastDisconnect)
        // The lab's two-phone fixture has a sign-in after it, so the transport is still read.
        assertEquals(ImsTransport.IWLAN, state.logTransport)
    }

    // --- test_iwlan_log -------------------------------------------------------------------------

    @Test
    fun `IWLAN log - the network's back-off of 14400 s, then the tunnel up`() {
        val events = IwlanLogParser.events(lines(iwlan), lidlName)
        assertEquals(1, events.count { it is IwlanEvent.BackoffTimer })
        assertEquals(3, events.count { it is IwlanEvent.TunnelClosed })
        assertEquals(2, events.count { it is IwlanEvent.LocalRetryWait })
        assertTrue(events.last() is IwlanEvent.TunnelOpened)
        val first = events.first().at

        val during = IwlanLogParser.summary(events, first + 120)
        assertEquals(IwlanHold.NETWORK_BACKOFF_TIMER, during.heldBackBy)
        assertEquals(239L, during.heldBackMinutes) // the data service's retry ends 14400.07 s after the timer; 120 s gone
        assertEquals(false, during.tunnelUp)
        assertEquals("IWLAN_IKE_PROTOCOL_EXCEPTION", during.lastRefusal)
        assertEquals(10500, during.lastRefusalCode)

        val after = IwlanLogParser.summary(events, events.last().at + 1)
        assertEquals(true, after.tunnelUp)
        assertNull(after.heldBackBy)

        // A tunnel that comes up inside the back-off ends it.
        val early = (events.dropLast(1) + IwlanEvent.TunnelOpened(first + 600)).sortedBy { it.at }
        assertNull(IwlanLogParser.summary(early, first + 700).heldBackBy)

        // Emergency tunnels are ignored.
        assertEquals(
            emptyList<IwlanEvent>(),
            IwlanLogParser.events(lines("  1783054055.933 1 2 D IwlanDataService[0]: Tunnel opened! APN: emergency, x"), lidlName),
        )
    }

    @Test
    fun `IWLAN log - the phone's own ePDG lookup got no address and the selector failed (2026-10-19)`() {
        val events = IwlanLogParser.events(lines(selectorFailed), lidlName)
        val now = events.last().at + 1
        val summary = IwlanLogParser.summary(events, now)
        val lookup = summary.phoneLookup!!
        assertFalse(lookup.resolved)
        assertTrue(lookup.standardName)
        assertEquals(0L, lookup.ageMinutes)
        assertEquals(false, summary.tunnelUp)
        assertEquals("IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED", summary.lastRefusal)
        assertNull(summary.lastRefusalCode)
        assertTrue(summary.selectionFailed)
        // Another network's lookup does not count as the SIM's own name.
        assertFalse(IwlanLogParser.parse(lines(selectorFailed), HomeNetwork.of("26201")!!.epdgFqdn, now).phoneLookup!!.standardName)
        assertEquals("under_1h", lookup.ageBucket)
        assertEquals("older", IwlanLogParser.summary(events, now + 3 * 86_400).phoneLookup!!.ageBucket)
    }

    @Test
    fun `IWLAN log - the live S20 lines of 2026-10-19 with the data service's retry of an hour`() {
        // Shape of the S20's tag-filtered log at 13:39 local time: an empty lookup, the selector refusal,
        // and the data service's retry; addresses in the response cut.
        val log = """
            --------- beginning of main
                     1792407600.047  9848  9898 D IwlanDataService[0]: Setup data call with network: 5, reason: HANDOVER, pduSessionId: 0
                     1792407600.100  9848 14430 D EpdgSelector: Processing request with transactionId: 93, for slotID: 0, isEmergency: false
                     1792407600.110  9848 14430 D EpdgSelector: Final plmn list:[262-02]
                     1792407600.120  9848 14430 D EpdgSelector: epdg.epc.mnc002.mcc262.pub.3gppnetwork.org: []
                     1792407600.200  9848 10004 D EpdgTunnelManager[0]: On triggering underlying network validation. Cause: TYPE: IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED
                     1792407600.210  9848 10004 D IwlanDataService[0]: Tunnel closed! APN: ims, Error: TYPE: IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED
                     1792407600.220  9848  9899 D IwlanDataService[0]: Delivering callbackType:1 result:0 rsp:DataCallResponse: { cause=IWLAN_DNS_RESOLUTION_NAME_FAILURE(0x4004) retry=3599999 cid=104399 linkStatus=0 protocolType=2 ifname= addresses=<cut>
        """.trimIndent()
        val summary = IwlanLogParser.parse(lines(log), lidlName, 1_792_409_949.0)
        assertEquals(false, summary.phoneLookup!!.resolved)
        assertEquals(39L, summary.phoneLookup!!.ageMinutes)
        assertTrue(summary.selectionFailed)
        assertEquals(IwlanHold.DATA_SERVICE_RETRY, summary.heldBackBy)
        assertEquals(21L, summary.heldBackMinutes)
    }

    // --- wifi_dns_servers -----------------------------------------------------------------------

    /** The lab's CONNECTIVITY_WIFI test line, with a made-up link-local suffix. */
    private val connectivityWifi =
        "  NetworkAgentInfo{network{365}  handle{1571068760077}  ni{WIFI CONNECTED extra: } created=2026-10-19T07:47:18.102Z " +
            "lp{{InterfaceName: wlan0 LinkAddresses: [ fe80::1234:56ff:fe78:9abc/64,192.168.178.185/24 ] " +
            "DnsAddresses: [ /192.168.178.1,/fd00::1 ] Domains: fritz.box MTU: 0}}"

    @Test
    fun `the Wi-Fi's DNS servers are classed, never kept`() {
        assertEquals(WifiResolver.LOCAL, WifiResolverParser.parse(lines("header\n$connectivityWifi\n")))
        assertEquals(
            WifiResolver.NOT_ON_WIFI,
            WifiResolverParser.parse(lines("ni{MOBILE CONNECTED} lp{{InterfaceName: rmnet0 DnsAddresses: [ /139.7.30.125 ]}}")),
        )
        assertEquals(WifiResolver.UNREADABLE, WifiResolverParser.parse(emptySequence()))
        assertEquals(WifiResolver.PUBLIC, WifiResolverParser.classify(listOf("8.8.8.8", "2001:4860:4860::8888")))
        assertEquals(WifiResolver.MIXED, WifiResolverParser.classify(listOf("192.168.178.1", "1.1.1.1")))
        assertEquals(WifiResolver.NONE, WifiResolverParser.classify(emptyList()))
        for (local in listOf("10.0.0.1", "172.16.5.4", "192.168.1.1", "fe80::1", "fd00::1", "127.0.0.1")) {
            assertTrue(local, WifiResolverParser.isLocal(local))
        }
        for (other in listOf("8.8.8.8", "100.64.0.1", "2a01:8f0::1", "dns.google", "192.168.1.1; reboot")) {
            assertFalse(other, WifiResolverParser.isLocal(other))
        }
    }

    // --- the whole AOSP path with the lab's fixtures --------------------------------------------

    private val pii = listOf(
        "262021234567890", "491700000001", "8949000000000000001", "350000000000001", "10.20.30.40", "192.168", "sip:", "tel:",
        "fritz.box", "139.7.",
    )

    private val identifiers = """
        mCallIncomingNumber=+491700000001
        mOutgoingCallEmergencyNumber=null
        imsi=262021234567890 iccid=8949000000000000001 imei=350000000000001
        P-CSCF: [10.20.30.40] sip:+491700000001@ims.example
    """.trimIndent()

    @Test
    fun `the deep check on LineageOS with the lab's fixtures`() {
        val target = DeepCheckTarget(slot0, HomeNetwork.of("26202"))
        val ran = mutableListOf<ShellCommand>()
        // The S20 on 2026-10-19: IMS registered (log says Wi-Fi), the IMS data connection on LTE, the lookup failing.
        val phoneSlot0Registered = phone.replaceFirst("mImsMmTelRegistrationState = 0", "mImsMmTelRegistrationState = 2")
            .replaceFirst(
                "MmTel Capabilities - [Voice: false Video: false UT: false SMS: false",
                "MmTel Capabilities - [Voice: true Video: false UT: true SMS: true",
            )
            .replaceFirst("isWfcEnabledByUser = false", "isWfcEnabledByUser = true")
        val runner = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> "Can't find service: secims"
                    ShellCommand.AOSP_IMS -> identifiers + "\n" + phoneSlot0Registered
                    ShellCommand.TELEPHONY_REGISTRY -> registryLidl.replace("    mSrvccState=-1", identifiers.prependIndent("    ") + "\n    mSrvccState=-1")
                    ShellCommand.IWLAN_LOG -> selectorFailed
                    ShellCommand.EPDG_LOOKUP -> "ping: unknown host epdg.epc.mnc002.mcc262.pub.3gppnetwork.org"
                    ShellCommand.WIFI_DNS -> connectivityWifi
                    ShellCommand.APN_LIST -> "Row: 0 apn=ims, type=ims, protocol=IPV4V6, roaming_protocol=IPV4V6, carrier_enabled=1, mvno_type=, edited=0"
                    ShellCommand.WIFI_CALLING_SWITCH -> throw AssertionError("Samsung's switch is not read without Samsung's IMS")
                }
                return read(output.lineSequence())
            }
        }
        val reading = DeepCheck.run(target, runner) { 1_792_370_155.786 + 600 }
        assertEquals(
            listOf(
                ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.AOSP_IMS, ShellCommand.IWLAN_LOG,
                ShellCommand.EPDG_LOOKUP, ShellCommand.WIFI_DNS, ShellCommand.APN_LIST,
            ),
            ran,
        )
        val ims = reading.ims
        assertEquals(ImsSource.AOSP, ims.source)
        assertEquals(true, ims.registered)
        assertEquals(ImsTransport.LTE, ims.transport)
        assertEquals(ImsTransport.IWLAN, ims.registrationLogTransport)
        assertEquals(listOf(ImsService.MMTEL, ImsService.SMSIP), ims.services)
        assertEquals(WifiCallingSwitch.ON, reading.wifiCallingSwitch)
        assertEquals(LteVops.SUPPORTED, reading.networkOffer!!.lteVops)
        assertEquals(EpdgName.DOES_NOT_RESOLVE, reading.epdgName)
        assertEquals(WifiResolver.LOCAL, reading.wifiResolver)
        val iwlan = reading.iwlan!!
        assertEquals(false, iwlan.phoneLookup!!.resolved)
        assertEquals(10L, iwlan.phoneLookup!!.ageMinutes)
        assertTrue(iwlan.selectionFailed)

        val encoded = reading.encode()
        for (value in pii) assertFalse(value, value in encoded)
        assertEquals(reading, DeepCheckReading.decode(encoded))
    }

    @Test
    fun `on Samsung's IMS the AOSP reader and the IWLAN log are not read`() {
        val ran = mutableListOf<ShellCommand>()
        val runner = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> "  SIM slot: [0] state: [REGISTERED] IMS Profile: [Name : VODAFONE Germany IMS VoLTE, pdn : ims]"
                    ShellCommand.WIFI_CALLING_SWITCH -> "0"
                    ShellCommand.TELEPHONY_REGISTRY -> registryLidl
                    else -> ""
                }
                return read(output.lineSequence())
            }
        }
        val reading = DeepCheck.run(DeepCheckTarget(slot0, null), runner)
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.WIFI_CALLING_SWITCH), ran)
        assertEquals(ImsSource.SAMSUNG, reading.ims.source)
        assertNull(reading.iwlan)
        assertNull(reading.wifiResolver)
        assertEquals(LteVops.SUPPORTED, reading.networkOffer!!.lteVops)
    }
}
