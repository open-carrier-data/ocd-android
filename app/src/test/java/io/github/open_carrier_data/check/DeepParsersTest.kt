package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.ApnEdited
import io.github.open_carrier_data.check.core.ApnList
import io.github.open_carrier_data.check.core.ApnListParser
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.EpdgLookupParser
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsReading
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.SamsungImsParser
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.WifiCallingSwitch
import io.github.open_carrier_data.check.core.WifiCallingSwitchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deep check's parsers on output in the formats phones print. SECIMS is
 * test_phone_check.py's fixture (the owner's A50); the extra lines carry the
 * kinds of identifiers a real dump holds (made up here) to show none of them
 * leaves a parser. APN rows use the `content` tool's format
 * (frameworks/base/cmds/content Content.java) with the A50's two rows.
 */
class DeepParsersTest {
    private val slot0 = SimSlot.of(0)!!
    private val slot1 = SimSlot.of(1)!!

    private val secims = """
  SIM slot: [1] state: [REGISTERED] IMS Profile: [Name : VODAFONE Germany IMS VoLTE, enabled : 2, pdn : ims]
  Governor: RegiGvnBase [mMno=Vodafone_DE, mFailureCounter=0]
    10/09/*026 20:05:26.446   slot[1]: [VODAFONE Germany IMS VoLTE|REGISTERED] onRegistered: RAT = 13, profile=VODAFONE Germany IMS VoLTE, service=[smsip, mmtel]
    10/09/*026 21:24:10.185   slot[1]: [VODAFONE Germany IMS VoLTE|REGISTERED] onRegistered: RAT = 18, profile=VODAFONE Germany IMS VoLTE, service=[smsip, mmtel]
"""

    private val identifiers = """
  impi: 262021234567890@ims.mnc002.mcc262.3gppnetwork.org
  impu: [sip:+491700000001@ims.vodafone.de, tel:+491700000001]
  iccid=8949000000000000001 imei=350000000000001
  P-CSCF: [2a01:8f0:400:955::5, 10.20.30.40]
"""

    private val pii = listOf(
        "262021234567890", "491700000001", "8949000000000000001", "350000000000001",
        "2a01:8f0", "10.20.30.40", "sip:", "tel:", "ims.vodafone.de",
    )

    private fun lines(text: String) = text.lineSequence()

    @Test
    fun `secims - registered over Wi-Fi with calls and SMS, as phone_check parse_secims reads it`() {
        val ims = SamsungImsParser.parse(lines(secims), slot1)
        assertEquals(ImsSource.SAMSUNG, ims.source)
        assertEquals(true, ims.registered)
        assertEquals("registered", ims.state)
        assertEquals(ImsTransport.IWLAN, ims.transport)
        assertEquals(listOf(ImsService.MMTEL, ImsService.SMSIP), ims.services)
        assertEquals("Vodafone_DE", ims.mno)
        assertTrue(ims.hasCalls)
    }

    @Test
    fun `secims - another slot has no task, so registration is unknown`() {
        val ims = SamsungImsParser.parse(lines(secims), slot0)
        assertEquals(ImsSource.SAMSUNG, ims.source)
        assertNull(ims.registered)
        assertNull(ims.mno)
    }

    @Test
    fun `secims - not registered keeps no transport or services from older registrations`() {
        val ims = SamsungImsParser.parse(lines(secims.replace("state: [REGISTERED]", "state: [NOT_REGISTERED]")), slot1)
        assertEquals(false, ims.registered)
        assertEquals("not_registered", ims.state)
        assertNull(ims.transport)
        assertEquals(emptyList<ImsService>(), ims.services)
    }

    @Test
    fun `secims - registered over LTE, and an emergency task is skipped`() {
        val text = "  SIM slot: [1] state: [REGISTERED] IMS Profile: [Name : Emergency, enabled : 2, pdn : emergency]\n" +
            secims.lines().filterNot { "RAT = 18" in it }.joinToString("\n")
        val ims = SamsungImsParser.parse(lines(text), slot1)
        assertEquals(ImsTransport.LTE, ims.transport)
        assertEquals("Vodafone_DE", ims.mno)
    }

    @Test
    fun `secims - missing service, permission denial and empty output`() {
        assertEquals(ImsSource.NOT_FOUND, SamsungImsParser.parse(lines("Can't find service: secims"), slot0).source)
        val denied = "Permission Denial: can't dump secims from pid=1, uid=10000"
        assertEquals(ImsSource.UNREADABLE, SamsungImsParser.parse(lines(denied), slot0).source)
        assertEquals(ImsSource.UNREADABLE, SamsungImsParser.parse(emptySequence(), slot0).source)
    }

    @Test
    fun `secims - an unknown service name is dropped, not passed on`() {
        val ims = SamsungImsParser.parse(lines(secims.replace("service=[smsip, mmtel]", "service=[mmtel, sip:+491700000001]")), slot1)
        assertEquals(listOf(ImsService.MMTEL), ims.services)
    }

    @Test
    fun `identifiers in a dump never leave the parser`() {
        val withIdentifiers = secims.replace("  Governor:", identifiers + "  Governor:")
        val ims = SamsungImsParser.parse(lines(withIdentifiers + identifiers), slot1)
        val apns = ApnListParser.parse(lines(apnRows + "\n" + identifiers))
        val epdg = EpdgLookupParser.parse(lines("PING epdg.epc.mnc002.mcc262.pub.3gppnetwork.org (10.20.30.40) 56(84) bytes of data."))
        val encoded = DeepCheckReading(ims, WifiCallingSwitch.ON, epdg, apns).encode()
        for (value in pii) assertFalse(value, value in encoded)
        assertEquals(EpdgName.RESOLVES, epdg)
        assertEquals(true, ims.registered)
    }

    @Test
    fun `an MNO name or APN that looks like an identifier is dropped`() {
        val ims = SamsungImsParser.parse(lines(secims.replace("mMno=Vodafone_DE", "mMno=262021234567890")), slot1)
        assertNull(ims.mno)
        val apns = ApnListParser.parse(lines(apnRow(0, apn = "10.20.30.40") + "\n" + apnRow(1, apn = "a262021234567890")))
        assertEquals(emptyList<Any>(), apns.rows)
        assertEquals(2, apns.leftOut)
    }

    @Test
    fun `ePDG name - phone_check epdg_fqdn and the first line of ping`() {
        assertEquals("epdg.epc.mnc002.mcc262.pub.3gppnetwork.org", HomeNetwork.of("26202")!!.epdgFqdn)
        assertEquals("epdg.epc.mnc260.mcc310.pub.3gppnetwork.org", HomeNetwork.of("310260")!!.epdgFqdn)
        val unknown = "ping: unknown host epdg.epc.mnc002.mcc262.pub.3gppnetwork.org"
        assertEquals(EpdgName.DOES_NOT_RESOLVE, EpdgLookupParser.parse(lines(unknown)))
        assertEquals(EpdgName.UNKNOWN, EpdgLookupParser.parse(lines("connect: Network is unreachable")))
        assertEquals(EpdgName.UNKNOWN, EpdgLookupParser.parse(emptySequence()))
    }

    @Test
    fun `Samsung's Wi-Fi calling switch`() {
        assertEquals(WifiCallingSwitch.ON, WifiCallingSwitchParser.parse(lines("1\n")))
        assertEquals(WifiCallingSwitch.OFF, WifiCallingSwitchParser.parse(lines("0")))
        assertEquals(WifiCallingSwitch.NOT_SET, WifiCallingSwitchParser.parse(lines("null")))
        assertEquals(WifiCallingSwitch.UNREADABLE, WifiCallingSwitchParser.parse(lines("Exception occurred")))
    }

    private fun apnRow(
        index: Int,
        apn: String = "web.vodafone.de",
        type: String = "default,supl,xcap",
        mvno: String = "",
        edited: String = "0",
    ) = "Row: $index apn=$apn, type=$type, protocol=IPV4V6, roaming_protocol=IPV4V6, carrier_enabled=1, mvno_type=$mvno, edited=$edited"

    private val apnRows = listOf(
        apnRow(0),
        apnRow(1, apn = "IMS", type = "ims"),
    ).joinToString("\n")

    @Test
    fun `APN list - the A50's two rows`() {
        val list = ApnListParser.parse(lines(apnRows))
        assertTrue(list.readable)
        assertEquals(2, list.rows.size)
        val web = list.rows[0]
        assertEquals("web.vodafone.de", web.apn)
        assertEquals(listOf("default", "supl", "xcap"), web.types)
        assertEquals("IPV4V6", web.protocol)
        assertEquals(true, web.carrierEnabled)
        assertNull(web.mvnoType)
        assertEquals(ApnEdited.NO, web.edited)
        assertEquals(listOf("ims"), list.rows[1].types)
    }

    @Test
    fun `APN list - emergency and deleted rows are left out, MVNO and hand-made rows are marked`() {
        val text = listOf(
            apnRow(0, apn = "sos", type = "emergency"),
            apnRow(1, edited = "2"),
            apnRow(2, apn = "event.vodafone.de", type = "mms", mvno = "spn"),
            apnRow(3, apn = "my.apn", edited = "1"),
            "Row: 4 apn=broken",
        ).joinToString("\n")
        val list = ApnListParser.parse(lines(text))
        assertEquals(listOf("event.vodafone.de", "my.apn"), list.rows.map { it.apn })
        assertEquals("spn", list.rows[0].mvnoType)
        assertEquals(ApnEdited.BY_USER, list.rows[1].edited)
        assertEquals(3, list.leftOut)
    }

    @Test
    fun `APN list - no rows, an error, and the row limit`() {
        assertEquals(ApnList(readable = true), ApnListParser.parse(lines("No result found.")))
        val error = "Error while accessing provider:telephony\njava.lang.SecurityException: No permission to access APN settings"
        assertEquals(ApnList.UNREADABLE, ApnListParser.parse(lines(error)))
        val many = (0 until 45).joinToString("\n") { apnRow(it) }
        val list = ApnListParser.parse(lines(many))
        assertEquals(ApnListParser.MAX_ROWS, list.rows.size)
        assertEquals(5, list.leftOut)
    }

    @Test
    fun `the typed result crosses the process boundary unchanged, and a wrong shape fails`() {
        val reading = DeepCheckReading(
            ims = SamsungImsParser.parse(lines(secims), slot1),
            wifiCallingSwitch = WifiCallingSwitch.ON,
            epdgName = EpdgName.DOES_NOT_RESOLVE,
            apnList = ApnListParser.parse(lines(apnRows)),
        )
        assertEquals(reading, DeepCheckReading.decode(reading.encode()))
        val forged = reading.encode().replace("Vodafone_DE", "sip:+491700000001@x")
        assertTrue(runCatching { DeepCheckReading.decode(forged) }.isFailure)
        val extra = reading.encode().replaceFirst("{", "{\"raw\":\"text\",")
        assertTrue(runCatching { DeepCheckReading.decode(extra) }.isFailure)
        assertTrue(runCatching { ImsReading(ImsSource.SAMSUNG, mno = "262021234567890") }.isFailure)
    }
}
