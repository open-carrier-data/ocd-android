package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.EimsAfter
import io.github.open_carrier_data.check.owner.core.EimsAttempt
import io.github.open_carrier_data.check.owner.core.EimsInputs
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.EmergencyDump
import io.github.open_carrier_data.check.owner.core.EmergencySession
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 30's emergency data test: the never-run conditions, the preconditions, the due rules, the verdicts and the stop list. */
class EmergencyDataTest {
    private val now = 1_792_900_000_000
    private val day = 86_400_000L
    private val sim = SimKey(3, "26207", 1911)

    private val ok = EimsInputs(
        inCall = false, lastCallSeenAtMs = now - 3_600_000, emergencySeenAtMs = null, upMs = 3 * 3_600_000, ecbm = false, emergencySmsMode = false,
        roaming = false, eimsExists = false, dataRat = "lte", emcSupported = true, dataWorks = true, honorRetryTimer = false, emergencyTransport = "WWAN",
        stopped = null, nowMs = now,
    )

    @Test
    fun `it never runs in a call, after a call or an emergency call, in an emergency mode, roaming, with an EIMS network, or over Wi-Fi`() {
        assertNull(EmergencyData.refusal(ok))
        val refused = listOf(
            ok.copy(inCall = true), ok.copy(inCall = null), ok.copy(lastCallSeenAtMs = now - 9 * 60_000), ok.copy(emergencySeenAtMs = now - 59 * 60_000),
            ok.copy(upMs = 59 * 60_000), ok.copy(ecbm = true), ok.copy(ecbm = null), ok.copy(emergencySmsMode = true), ok.copy(roaming = true), ok.copy(roaming = null),
            ok.copy(eimsExists = true), ok.copy(eimsExists = null), ok.copy(emergencyTransport = "WLAN"), ok.copy(stopped = "x"),
        )
        for (i in refused) assertNotNull(i.toString(), EmergencyData.refusal(i))
    }

    @Test
    fun `it needs the home network on LTE or NR, the EMC bit, working data and the retry flag false`() {
        assertNotNull(EmergencyData.refusal(ok.copy(dataRat = "umts")))
        assertNull(EmergencyData.refusal(ok.copy(dataRat = "nr")))
        assertTrue(EmergencyData.refusal(ok.copy(emcSupported = false))!!.startsWith("not available on this network"))
        assertNotNull(EmergencyData.refusal(ok.copy(dataWorks = false)))
        assertNotNull(EmergencyData.refusal(ok.copy(honorRetryTimer = true)))
        assertNotNull(EmergencyData.refusal(ok.copy(honorRetryTimer = null)))
    }

    private fun s(outcome: String, at: Long, row: String? = "a:2", basis: String = "b1") = EmergencySession(sim.subId, sim.mccmnc, row, basis, EmergencyData.day(at), outcome, at)

    @Test
    fun `due on a new SIM, a changed row, a new build, and before the 90-day record expires, once a day, paused after three failed days`() {
        assertEquals("a SIM first seen", EmergencyData.due(emptyList(), sim, "a:2", "b1", now))
        val passed = listOf(s("passed", now - 10 * day))
        assertNull(EmergencyData.due(passed, sim, "a:2", "b1", now))
        assertEquals("the SIM's emergency row changed", EmergencyData.due(passed, sim, "a:3", "b1", now))
        assertEquals("a new build, IMS stack or modem firmware", EmergencyData.due(passed, sim, "a:2", "b2", now))
        assertEquals("the 90-day record is about to expire", EmergencyData.due(listOf(s("passed", now - 77 * day)), sim, "a:2", "b1", now))
        // One session per SIM per day, no retry on the same day.
        assertNull(EmergencyData.due(listOf(s("not_counted", now - 3_600_000)), sim, "a:2", "b1", now))
        // A refusal (a condition did not hold) is no session.
        assertEquals("no decided session yet", EmergencyData.due(listOf(s("refused", now - 3_600_000)), sim, "a:2", "b1", now))
        // Three days on which both failed: paused until the row or the build changes.
        val failed = (1..3).map { s("both_failed", now - it * day) }
        assertNull(EmergencyData.due(failed, sim, "a:2", "b1", now))
        assertNotNull(EmergencyData.due(failed, sim, "a:2", "b2", now))
        // A stop event: never again for that cell until a judged round.
        assertNull(EmergencyData.due(listOf(s("stopped", now - 5 * day)), sim, "a:2", "b1", now))
    }

    private val attempt = EimsAttempt(connected = true, cellular = true, profileMatches = true, families = setOf("ipv4"), failCause = null, dataWorks = true, releasedInTime = true)

    @Test
    fun `verdicts - pass, counted failure, operator barring, Wi-Fi and everything else not counted`() {
        assertEquals(EmergencyData.Verdict.PASS, EmergencyData.classify(attempt, "IP"))
        // The address family must be one the row allows.
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(attempt.copy(families = setOf("ipv6")), "IP"))
        assertEquals(EmergencyData.Verdict.PASS, EmergencyData.classify(attempt.copy(families = setOf("ipv6")), "IPV4V6"))
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(attempt.copy(profileMatches = false), "IP"))
        assertEquals(EmergencyData.Verdict.WIFI, EmergencyData.classify(attempt.copy(cellular = false), "IP"))
        val refused = attempt.copy(connected = false, families = emptySet())
        for (cause in listOf(0x1C, 0x32, 0x33, 0x1B, 0x20, 0x21)) assertEquals(EmergencyData.Verdict.COUNTED_FAIL, EmergencyData.classify(refused.copy(failCause = cause), "IP"))
        // A counted cause while ordinary data does not work is not counted.
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(refused.copy(failCause = 0x1B, dataWorks = false), "IP"))
        assertEquals(EmergencyData.Verdict.BARRED, EmergencyData.classify(refused.copy(failCause = 0x08), "IP"))
        // A timeout, congestion and the rest: not counted.
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(refused, "IP"))
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(refused.copy(failCause = 0x1A), "IP"))
        // A call-state change aborts: nothing counted.
        assertEquals(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.classify(attempt.copy(aborted = true), "IP"))
    }

    private val clean = EimsAfter(imsUnchanged = true, ecbm = false, emergencyCallState = false, callUp = false, emergencyThrottle = false, eimsGone = true, serviceOk = true)

    @Test
    fun `the stop list - emergency mode, a bearer not released, service lost, a throttle, operator barring or a message`() {
        assertNull(EmergencyData.stopEvent(attempt, clean, EmergencyData.Verdict.PASS))
        val stops = listOf(
            clean.copy(ecbm = true), clean.copy(ecbm = null), clean.copy(emergencyCallState = true), clean.copy(eimsGone = false), clean.copy(emergencyThrottle = true),
            clean.copy(emergencyThrottle = null), clean.copy(serviceOk = false), clean.copy(imsUnchanged = false), clean.copy(operatorMessage = true),
        )
        for (a in stops) assertNotNull(a.toString(), EmergencyData.stopEvent(attempt, a, EmergencyData.Verdict.PASS))
        assertNotNull(EmergencyData.stopEvent(attempt.copy(releasedInTime = false), clean, EmergencyData.Verdict.PASS))
        assertNotNull(EmergencyData.stopEvent(attempt, clean, EmergencyData.Verdict.BARRED))
    }

    @Test
    fun `results - accepted, Android's default won only after a counted failure, refused only when both failed - and never a word about calls working`() {
        assertEquals(RecordResult.EMERGENCY_DATA_ACCEPTED, EmergencyData.result(EmergencyData.Verdict.PASS, null))
        assertEquals(RecordResult.EMERGENCY_DEFAULT_WON, EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, EmergencyData.Verdict.PASS))
        assertEquals(RecordResult.EMERGENCY_DATA_REFUSED, EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, EmergencyData.Verdict.COUNTED_FAIL))
        assertNull(EmergencyData.result(EmergencyData.Verdict.COUNTED_FAIL, null))
        assertNull(EmergencyData.result(EmergencyData.Verdict.NOT_COUNTED, EmergencyData.Verdict.PASS))
        for (r in listOf(RecordResult.EMERGENCY_DATA_ACCEPTED, RecordResult.EMERGENCY_DEFAULT_WON, RecordResult.EMERGENCY_DATA_REFUSED)) {
            val text = EmergencyData.recordBasis(r, "emergency.volte.ims.cnt.net.ec", "IP", setOf("ipv4"), 0x1B)
            assertTrue(text, "says nothing about emergency calls" in text)
            assertFalse(text, Regex("(?i)emergency calls? (work|works|are working)").containsMatchIn(text))
            assertTrue(text.length <= 500)
        }
        assertEquals(15_000L, EmergencyData.WAIT_MS)
        assertEquals(5_000L, EmergencyData.RELEASE_MS)
    }

    @Test
    fun `the phone service's dump - the retry flag, the emergency transport and a throttle, per phone`() {
        val dump = """
TelephonyDebugService:
  DataConfigManager-0:
    isTempNotMeteredSupportedByCarrier=false
    shouldHonorRetryTimerForEmergencyNetworkRequest=false
  DataConfigManager-1:
    shouldHonorRetryTimerForEmergencyNetworkRequest=true
  AccessNetworksManager-0:
    preferred transports=
      default: WWAN
      emergency: WWAN
      ims: WLAN
    Local logs=
  AccessNetworksManager-1:
    preferred transports=
      emergency: WLAN
  DataRetryManager-0:
    Retry entries:
    Throttling entries:
    Local logs:
  DataRetryManager-1:
    Throttling entries:
      [DataThrottlingEntry: dataProfile=[DataProfile=[ApnSetting] SOS, 2, sos, , , , , , 0, emergency, IP, IP], request list=[], retry type=new setup data]
    Local logs:
"""
        assertEquals(false, EmergencyDump.honorRetryTimer(dump, 0))
        assertEquals(true, EmergencyDump.honorRetryTimer(dump, 1))
        assertEquals("WWAN", EmergencyDump.emergencyTransport(dump, 0))
        assertEquals("WLAN", EmergencyDump.emergencyTransport(dump, 1))
        assertEquals(false, EmergencyDump.emergencyThrottle(dump, 0))
        assertEquals(true, EmergencyDump.emergencyThrottle(dump, 1))
        assertNull(EmergencyDump.honorRetryTimer(null, 0))
        assertNull(EmergencyDump.emergencyThrottle("nothing", 0))
        val block = "mLteVopsSupportInfo=LteVopsSupportInfo :  mVopsSupport = 2 mEmcBearerSupport = 2"
        assertEquals(true, EmergencyDump.emcSupported(block, "lte"))
        assertEquals(false, EmergencyDump.emcSupported(block.replace("mEmcBearerSupport = 2", "mEmcBearerSupport = 3"), "lte"))
        assertEquals(true, EmergencyDump.emcSupported("NrVopsSupportInfo :  mVopsSupport = 2 mEmcSupport = 3 mEmfSupport = 3", "nr"))
        assertEquals(false, EmergencyDump.emcSupported("NrVopsSupportInfo :  mVopsSupport = 2 mEmcSupport = 0 mEmfSupport = 0", "nr"))
        assertNull(EmergencyDump.emcSupported(block, "umts"))
    }

    @Test
    fun `the request lives in one file, asks only for EIMS on cellular, never dials and never sends`() {
        val sources = File("src/owner/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val requesters = sources.filter { "NET_CAPABILITY_EIMS)" in it.readText() && ".addCapability(" in it.readText() }.map { it.name }
        assertEquals(listOf("OwnerEmergencyData.kt"), requesters)
        val text = sources.single { it.name == "OwnerEmergencyData.kt" }.readText()
        for (banned in listOf("placeCall", "sendDataMessage", "Socket(", "DatagramSocket", "openConnection", "tel:", "112", "911", "bindSocket")) {
            assertFalse(banned, banned in text)
        }
        // Released at once: the callback is unregistered in a finally block.
        assertTrue(Regex("finally \\{\\s*runCatching \\{ cm\\.unregisterNetworkCallback\\(network\\) \\}").containsMatchIn(text))
    }
}
