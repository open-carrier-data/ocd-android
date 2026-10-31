package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.MmsEndedList
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.owner.core.InboundSms
import io.github.open_carrier_data.check.owner.core.MmsCheck
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.SelfSmsCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.descriptors.elementNames
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * MMS (round 26 rule change 6, refined 2026-10-20): never tested where OCD's
 * record says the network ended it; elsewhere only a free GET to the MMSC,
 * never an MMS. The optional data SMS to the phone's own number: off by
 * default, always on the app's port, never "works".
 */
class MessagesTest {
    private val bundled = MmsProbe.bundled(MmsProbe.parse(File("src/owner/assets/mms-ended.json").readText()))
    private val published = OperatorFiles.statements(File("src/test/resources/operators/statements.json").readText())
    private val ended = MmsEndedList.of(published, bundled)
    private val sim = OwnerFixtures.SIM.key

    @Test
    fun `MMS is not checked where the network ended it, which covers every German network`() {
        for (code in listOf("26201", "26202", "26203", "26207", "26223")) assertTrue(code, MmsProbe.ended(ended, code) != null)
        assertEquals(
            "MMS: not checked, the network no longer offers MMS (Vodafone Deutschland states MMS ended on 2023-01-16)",
            MmsProbe.line(null, MmsProbe.ended(ended, "26202"), ZoneOffset.UTC),
        )
        // Only rows with high confidence: medium ones (KPN) and Vodafone's other codes stay test targets.
        assertNull(MmsProbe.ended(ended, "20408"))
        assertNull(MmsProbe.ended(ended, "26204"))
        assertNull(MmsProbe.ended(ended, "23410"))
    }

    @Test
    fun `round 32 - X6 reads the published list, and mms-ended json keeps only what it does not cover yet`() {
        // The published list covers 26202, 26203, 26207, 26223, 22802, 22803 and 27001 (private #230); they left the bundled file.
        assertEquals(setOf("26201", "22801"), bundled.flatMap { it.mccmnc }.toSet())
        assertEquals(emptySet<String>(), MmsEndedList.coveredByPublished(published, bundled))
        // Nothing dropped out of X6: every network of the file before round 32 is still on the list.
        for (code in listOf("26202", "26201", "26203", "26207", "26223", "22801", "22803", "22802", "27001")) assertTrue(code, MmsProbe.ended(ended, code) != null)
        // The published entries come first and name the statement; the bundled ones say they are OCD's earlier list.
        assertTrue(MmsProbe.ended(ended, "26203")!!.published)
        assertEquals("de.telefonica.mms", MmsProbe.ended(ended, "26203")!!.statement)
        assertFalse(MmsProbe.ended(ended, "26201")!!.published)
        assertEquals(
            "MMS: not checked, the network no longer offers MMS (OCD's earlier list records Telekom Deutschland ending it on 2026-06-30)",
            MmsProbe.line(null, MmsProbe.ended(ended, "26201"), ZoneOffset.UTC),
        )
        // Without the published list (no pin yet, or its files refused) only the bundled networks stand.
        assertEquals(setOf("26201", "22801"), MmsEndedList.of(null, bundled).map { it.mccmnc }.toSet())
    }

    @Test
    fun `the probe is one GET of a made-up path with no body, through the MMS proxy when there is one`() {
        val viaProxy = MmsProbe.request("http://mms.example.net:8002/servlets/mms", "10.0.0.1", 8080)!!
        assertEquals("10.0.0.1", viaProxy.connectHost)
        assertEquals(8080, viaProxy.connectPort)
        // Round 42, item 6: M4 is only a GET of a made-up message path, never the row's own MMSC path.
        assertEquals("GET http://mms.example.net:8002/ocd-check/no-such-message HTTP/1.1", viaProxy.requestLine)
        assertFalse("servlets" in viaProxy.bytes().decodeToString())
        val text = viaProxy.bytes().decodeToString()
        assertTrue(text.startsWith("GET "))
        assertTrue(text.endsWith("\r\n\r\n"))
        assertFalse("POST" in text)
        assertFalse("application/vnd.wap.mms-message" in text)
        val direct = MmsProbe.request("http://mms.example.net/mms/wapenc", null, null)!!
        assertEquals("mms.example.net" to 80, direct.connectHost to direct.connectPort)
        assertEquals("GET /ocd-check/no-such-message HTTP/1.1", direct.requestLine)
        assertEquals("Host: mms.example.net", direct.bytes().decodeToString().lines()[1])
        // Nothing is sent for anything that is not a plain http URL, host and port.
        for (bad in listOf(null, "", "https://mms.example.net/", "ftp://x/", "http://user@mms.example.net/", "http://mms.example.net/a\r\nX: y", "http://mms.example.net:0/", "http://mms.example.net:99999/", "http://bad host/")) {
            assertNull(bad, MmsProbe.request(bad, null, null))
        }
        assertNull(MmsProbe.request("http://mms.example.net/", "bad proxy", 80))
        assertNull(MmsProbe.request("http://mms.example.net/", "10.0.0.1", 70000))
    }

    @Test
    fun `any HTTP answer is reachable, never MMS works, and the check repeats every 14 days`() {
        assertEquals(200, MmsProbe.status("HTTP/1.1 200 OK"))
        assertEquals(405, MmsProbe.status("HTTP/1.0 405 Method Not Allowed"))
        assertNull(MmsProbe.status("SSH-2.0"))
        assertNull(MmsProbe.status(null))
        val check = MmsCheck(sim, 1_792_400_000_000, "reachable", 405)
        val line = MmsProbe.line(check, null, ZoneOffset.UTC)
        assertTrue(line, line.startsWith("MMS access point reachable"))
        assertFalse(Regex("(?i)mms works|\\bworks\\b").containsMatchIn(line))
        assertTrue(MmsProbe.due(emptyList(), sim, 0))
        assertFalse(MmsProbe.due(listOf(check), sim, check.atMs + TimeUnit.DAYS.toMillis(13)))
        assertTrue(MmsProbe.due(listOf(check), sim, check.atMs + TimeUnit.DAYS.toMillis(14)))
    }

    @Test
    fun `the data SMS to the phone's own number is off by default, always on the app's port, and never works`() {
        assertFalse(OwnerState().selfSmsOn)
        assertEquals("off", SelfSms.refusal(on = false, ownNumber = "+491701234567", smsOverIms = true, roaming = false))
        assertEquals("no_port", SelfSms.refusal(on = true, ownNumber = "+491701234567", smsOverIms = true, roaming = false, port = 0))
        assertEquals("own_number_unknown", SelfSms.refusal(on = true, ownNumber = null, smsOverIms = true, roaming = false))
        assertEquals("own_number_unknown", SelfSms.refusal(on = true, ownNumber = "5500;", smsOverIms = true, roaming = false))
        assertEquals("sms_over_ims_not_registered", SelfSms.refusal(on = true, ownNumber = "+491701234567", smsOverIms = false, roaming = false))
        assertEquals("roaming", SelfSms.refusal(on = true, ownNumber = "+491701234567", smsOverIms = true, roaming = null))
        assertNull(SelfSms.refusal(on = true, ownNumber = "+491701234567", smsOverIms = true, roaming = false))
        assertTrue(SelfSms.PORT > 0)
        val nonce = "0123456789abcdef"
        val payload = SelfSms.payload(nonce)
        assertEquals(20, payload.size)
        assertEquals(nonce, SelfSms.nonceOf(payload))
        assertNull(SelfSms.nonceOf("hello".encodeToByteArray()))
        assertNull(SelfSms.nonceOf("OCD1+491701234567...".encodeToByteArray()))
        val sent = SelfSmsCheck(sim, 1_000, nonce, "sent")
        assertEquals("not_received", SelfSms.settle(listOf(sent), 1_000 + SelfSms.WAIT_MS).single().result)
        val back = SelfSms.received(listOf(sent.copy(imsAtSend = true, sentVia = SelfSms.IMS)), nonce, 2_000, true, SelfSms.IMS).single()
        assertEquals("received", back.result)
        val line = SelfSms.line(back, ZoneOffset.UTC)
        assertTrue(line, "went out and came back over IMS" in line && "partial check" in line && "nothing about calls" in line)
        assertFalse(Regex("(?i)\\bworks\\b").containsMatchIn(line))
        assertFalse("the number is never kept", SelfSmsCheck.serializer().descriptor.elementNames.any { "number" in it.lowercase() })
    }

    /**
     * `dumpsys isms` on the S20 (LineageOS 24.0, AOSP ImsStack, o2) after one test SMS on
     * 2026-10-24, shortened. The -1 section is cut down to one made-up CS arrival, to show that
     * the sections stay apart.
     */
    private val ismsDump = """
        SmsManager for subId = 4:
            Enabled GSM channels:
            SMS dispatcher controller log:
            GsmInboundSmsHandler extends StateMachine:
              GsmInboundSmsHandler:
               total records=10
                rec[0]: time=10-24 07:05:12.346 processed=StartupState org=StartupState dest=IdleState what=EVENT_START_ACCEPTING_SMS
                rec[1]: time=10-24 07:05:12.540 processed=IdleState org=IdleState dest=<null> what=EVENT_RELEASE_WAKELOCK
                rec[2]: time=10-24 08:15:49.363 processed=IdleState org=IdleState dest=DeliveringState what=EVENT_INJECT_SMS
                rec[3]: time=10-24 08:15:49.385 processed=DeliveringState org=DeliveringState dest=<null> what=EVENT_INJECT_SMS
                rec[4]: time=10-24 08:15:49.419 processed=DeliveringState org=DeliveringState dest=WaitingState what=EVENT_BROADCAST_SMS
                rec[5]: time=10-24 08:15:49.419 processed=WaitingState org=WaitingState dest=<null> what=EVENT_RETURN_TO_IDLE
                rec[6]: time=10-24 08:15:49.419 processed=WaitingState org=WaitingState dest=<null> what=EVENT_UPDATE_TRACKER
                rec[7]: time=10-24 08:15:49.709 processed=WaitingState org=WaitingState dest=DeliveringState what=EVENT_BROADCAST_COMPLETE
                rec[8]: time=10-24 08:15:49.710 processed=DeliveringState org=DeliveringState dest=IdleState what=EVENT_RETURN_TO_IDLE
                rec[9]: time=10-24 08:15:52.712 processed=IdleState org=IdleState dest=<null> what=EVENT_RELEASE_WAKELOCK
        GsmCellBroadcastHandler:
         total records=1
          rec[0]: time=10-24 07:05:14.721 processed=IdleState org=IdleState dest=<null> what=3(0x3)
            CdmaInboundSmsHandler extends StateMachine:
              CdmaInboundSmsHandler:
               total records=2
                rec[0]: time=10-24 07:05:12.346 processed=StartupState org=StartupState dest=IdleState what=EVENT_START_ACCEPTING_SMS
                rec[1]: time=10-24 07:05:12.541 processed=IdleState org=IdleState dest=<null> what=EVENT_RELEASE_WAKELOCK
        SmsManager for subId = -1:
            GsmInboundSmsHandler extends StateMachine:
              GsmInboundSmsHandler:
                rec[3]: time=10-24 08:20:01.000 processed=DeliveringState org=DeliveringState dest=<null> what=EVENT_NEW_SMS
    """.trimIndent()

    private val berlin = java.time.ZoneId.of("Europe/Berlin")
    private fun at(text: String, zone: java.time.ZoneId = berlin) = java.time.LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `Telephony's inbound record says how an SMS came in, read only in the SIM's own section`() {
        val sent = at("2026-10-24T08:15:48.188")
        val back = at("2026-10-24T08:15:49.437")
        val arrivals = InboundSms.parse(ismsDump, 4, berlin, back)!!
        // One arrival: the record in the delivering state, not the idle state's deferral; nothing from the cell broadcast handler.
        assertEquals(listOf(InboundSms.Arrival(at("2026-10-24T08:15:49.385"), SelfSms.IMS)), arrivals)
        assertEquals(SelfSms.IMS, InboundSms.via(arrivals, sent, back))
        // Another section's records never count for this SIM; a missing section or dump is unreadable, not "none".
        assertEquals(listOf(InboundSms.Arrival(at("2026-10-24T08:20:01"), SelfSms.CS)), InboundSms.parse(ismsDump, -1, berlin, back))
        assertNull(InboundSms.parse(ismsDump, 7, berlin, back))
        assertNull(InboundSms.parse(null, 4, berlin, back))
        assertEquals(emptyList<InboundSms.Arrival>(), InboundSms.parse("SmsManager for subId = 4:\n  mLocalLog:", 4, berlin, back))
    }

    @Test
    fun `the way back is told only when every arrival between the send and the receipt took one way`() {
        val sent = at("2026-10-24T08:15:48")
        val back = at("2026-10-24T08:15:52")
        val ims = InboundSms.Arrival(at("2026-10-24T08:15:50"), SelfSms.IMS)
        val cs = InboundSms.Arrival(at("2026-10-24T08:15:51"), SelfSms.CS)
        assertEquals(SelfSms.IMS, InboundSms.via(listOf(ims), sent, back))
        assertEquals(SelfSms.CS, InboundSms.via(listOf(cs), sent, back))
        assertNull("another SMS came the other way: not told which was ours", InboundSms.via(listOf(ims, cs), sent, back))
        assertNull("nothing recorded (pushed out by later SMS)", InboundSms.via(emptyList(), sent, back))
        assertNull(InboundSms.via(null, sent, back))
        // Outside the window (an older SMS, beyond the slack of the shared clock) it does not count.
        val older = InboundSms.Arrival(sent - InboundSms.SLACK_MS - 1, SelfSms.CS)
        assertEquals(SelfSms.IMS, InboundSms.via(listOf(older, ims), sent, back))
        assertEquals(SelfSms.IMS, InboundSms.via(listOf(InboundSms.Arrival(back + 500, SelfSms.IMS)), sent, back))
    }

    @Test
    fun `a record without a year takes the year nearest the receipt, across New Year`() {
        val dump = "SmsManager for subId = 4:\n rec[3]: time=12-31 23:59:59.900 processed=DeliveringState org=DeliveringState dest=<null> what=EVENT_INJECT_SMS"
        val newYear = at("2027-01-01T00:00:01")
        assertEquals(listOf(InboundSms.Arrival(at("2026-12-31T23:59:59.900"), SelfSms.IMS)), InboundSms.parse(dump, 4, berlin, newYear))
        // Another event name that merely starts the same never counts.
        assertEquals(emptyList<InboundSms.Arrival>(), InboundSms.parse(dump.replace("EVENT_INJECT_SMS", "EVENT_INJECT_SMS_LATER"), 4, berlin, newYear))
    }

    @Test
    fun `Telephony's answer to the send records the way out, and a failure is send_failed`() {
        val nonce = "0123456789abcdef"
        val sent = SelfSmsCheck(sim, 1_000, nonce, "sent", imsAtSend = true)
        assertEquals(SelfSms.IMS, SelfSms.sent(listOf(sent), nonce, ok = true, viaIms = true).single().sentVia)
        assertEquals(SelfSms.CS, SelfSms.sent(listOf(sent), nonce, ok = true, viaIms = false).single().sentVia)
        assertNull("no extra: not told", SelfSms.sent(listOf(sent), nonce, ok = true, viaIms = null).single().sentVia)
        assertEquals("send_failed", SelfSms.sent(listOf(sent), nonce, ok = false, viaIms = true).single().result)
        assertEquals(listOf(sent), SelfSms.sent(listOf(sent), "fedcba9876543210", ok = true, viaIms = true))
        // The answer may come after the SMS is back (in the measurement it came 95 ms before): the way out is still recorded, the result stays.
        val back = SelfSms.received(listOf(sent), nonce, 2_000, true, SelfSms.IMS)
        val late = SelfSms.sent(back, nonce, ok = true, viaIms = true)
        assertEquals("received", late.single().result)
        assertTrue(SelfSms.settles(back, late, nonce))
        assertFalse("settled once, not again", SelfSms.settles(late, late, nonce))
    }

    @Test
    fun `SMS both ways needs IMS registered at the send and Telephony's IMS word on each leg`() {
        val both = SelfSmsCheck(sim, 1_000, "0123456789abcdef", "received", 2_000, imsAtSend = true, imsAtReceive = true, sentVia = SelfSms.IMS, receivedVia = SelfSms.IMS)
        assertTrue(SelfSms.bothWays(both))
        assertTrue("the record is the proof at receipt; registration unread does not undo it", SelfSms.bothWays(both.copy(imsAtReceive = null)))
        for (not in listOf(both.copy(result = "sent"), both.copy(imsAtSend = false), both.copy(sentVia = null), both.copy(sentVia = SelfSms.CS), both.copy(receivedVia = null), both.copy(receivedVia = SelfSms.CS))) {
            assertFalse(not.toString(), SelfSms.bothWays(not))
        }
        assertFalse("registered at both ends is the indirect reading only", SelfSms.bothWays(both.copy(sentVia = null, receivedVia = null)))
        val line = SelfSms.receivedLine(both)
        assertEquals("the test SMS went out through the IMS service and came back through the IMS service (Telephony: injected by IMS); IMS registered with SMS at receipt; SMS both ways over IMS: settled", line)
        assertTrue(SelfSms.receivedLine(both.copy(receivedVia = SelfSms.CS)).endsWith("not settled"))
        assertEquals("A test SMS came back over CS, not over IMS, 1970-01-01", SelfSms.line(both.copy(receivedVia = SelfSms.CS), ZoneOffset.UTC))
        assertTrue(SelfSms.line(both.copy(receivedVia = null), ZoneOffset.UTC).contains("whether over IMS is not known"))
        assertTrue(SelfSms.line(both.copy(sentVia = SelfSms.CS), ZoneOffset.UTC).contains("over IMS, 1970-01-01; it went out over CS"))
        assertTrue(SelfSms.line(both.copy(sentVia = null), ZoneOffset.UTC).contains("the way out is not proven"))
        for (c in listOf(both, both.copy(receivedVia = SelfSms.CS), both.copy(sentVia = null))) {
            assertFalse(Regex("(?i)\\bworks\\b").containsMatchIn(SelfSms.line(c, ZoneOffset.UTC) + SelfSms.receivedLine(c)))
        }
    }
}
