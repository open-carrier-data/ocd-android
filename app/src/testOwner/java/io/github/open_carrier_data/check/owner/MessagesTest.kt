package io.github.open_carrier_data.check.owner

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
    private val ended = MmsProbe.parse(File("src/owner/assets/mms-ended.json").readText())
    private val sim = OwnerFixtures.SIM.key

    @Test
    fun `MMS is not checked where the network ended it, which covers every German network`() {
        for (code in listOf("26201", "26202", "26203", "26207", "26223")) assertTrue(code, MmsProbe.ended(ended, code) != null)
        assertEquals(
            "MMS: not checked, the network no longer offers MMS (Vodafone Germany ended it 2023-01-17)",
            MmsProbe.line(null, MmsProbe.ended(ended, "26202"), ZoneOffset.UTC),
        )
        // Only rows with high confidence: medium ones (KPN) and Vodafone's other codes stay test targets.
        assertNull(MmsProbe.ended(ended, "20408"))
        assertNull(MmsProbe.ended(ended, "26204"))
        assertNull(MmsProbe.ended(ended, "23410"))
    }

    @Test
    fun `the probe is one GET with no body, through the MMS proxy when there is one`() {
        val viaProxy = MmsProbe.request("http://mms.example.net:8002/servlets/mms", "10.0.0.1", 8080)!!
        assertEquals("10.0.0.1", viaProxy.connectHost)
        assertEquals(8080, viaProxy.connectPort)
        assertEquals("GET http://mms.example.net:8002/servlets/mms HTTP/1.1", viaProxy.requestLine)
        val text = viaProxy.bytes().decodeToString()
        assertTrue(text.startsWith("GET "))
        assertTrue(text.endsWith("\r\n\r\n"))
        assertFalse("POST" in text)
        assertFalse("application/vnd.wap.mms-message" in text)
        val direct = MmsProbe.request("http://mms.example.net/mms/wapenc", null, null)!!
        assertEquals("mms.example.net" to 80, direct.connectHost to direct.connectPort)
        assertEquals("GET /mms/wapenc HTTP/1.1", direct.requestLine)
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
        val back = SelfSms.received(listOf(sent), nonce, 2_000, true).single()
        assertEquals("received", back.result)
        val line = SelfSms.line(back, ZoneOffset.UTC)
        assertTrue(line, "partial check" in line && "nothing about calls" in line)
        assertFalse(Regex("(?i)\\bworks\\b").containsMatchIn(line))
        assertFalse("the number is never kept", SelfSmsCheck.serializer().descriptor.elementNames.any { "number" in it.lowercase() })
    }
}
