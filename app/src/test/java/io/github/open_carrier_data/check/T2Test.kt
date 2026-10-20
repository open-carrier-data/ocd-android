package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.Fixtures.readings
import io.github.open_carrier_data.check.core.AudioAnswer
import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallBearer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallState
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CallTestRecord
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.ConfigOrigin
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.T2
import io.github.open_carrier_data.check.core.T2Feature
import io.github.open_carrier_data.check.core.T2Record
import io.github.open_carrier_data.check.core.T2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 23 LB3 (round 19's T2) as a pure rule, ported from OCD Lab's
 * `t2_assess` and its test `test_t2_assess_rules` (private
 * tools/test_ocd_lab.py, main 0d12145b), plus the app's narrower rules:
 * the outgoing call measured as outgoing, and one call per watch; round 26's
 * bearer proof in place of airplane mode. LB4's wording: "registered" from
 * the deep check, "works on this phone" only with T2.
 */
class T2Test {
    /** The lab's `call()` helper: one call of [direction] ("mo" or "mt") with [code] as its only call network type. */
    private fun call(
        direction: String,
        code: Int?,
        active: Boolean = true,
        audio: AudioAnswer = AudioAnswer.YES,
        ringing: Boolean = direction == "mt",
        mailbox: Boolean? = if (direction == "mt") false else null,
        secondLine: Boolean? = if (direction == "mt") true else null,
        airplane: Boolean? = true,
        calls: Int = 1,
        /** The ims data connection and data RAT as they read during the call; by default they agree with [code]. */
        pdn: List<ImsTransport> = if (code == 18) listOf(ImsTransport.IWLAN) else listOf(ImsTransport.LTE),
        rat: String? = "lte",
    ): CallTestRecord {
        val incoming = direction == "mt"
        val reading = BearerReading(code, pdn, rat)
        val watch = CallWatchSummary(
            samples = 10,
            statesSeen = listOfNotNull(CallState.IDLE, CallState.RINGING.takeIf { ringing }, CallState.OFFHOOK),
            startedIdle = true,
            callsSeen = calls,
            direction = if (incoming) (if (ringing) CallDirection.INCOMING else CallDirection.OUTGOING) else CallDirection.OUTGOING,
            ringingSeen = ringing,
            incomingIsMailbox = mailbox,
            connected = true,
            wentActive = active,
            callNetworkTypes = listOfNotNull(code),
            callNetworkType = code,
            bearer = CallBearer.of(code) ?: CallBearer.UNKNOWN,
            ended = true,
            bearerAtActive = reading.takeIf { code != null },
            bearerAtHangUp = reading,
            bearerProof = BearerProof.of(active, reading.takeIf { code != null }, reading),
        )
        return CallTestRecord(
            date = "2026-10-19", model = "SM-G981B", codename = "x1s", romFamily = "lineageos", androidMajor = 16,
            imsStack = "aosp_telephony", homeMccMnc = "26202", spn = "LIDL Connect", ocdProfile = "open.26202.8cebd7550c67",
            brand = "LIDL Connect (Vodafone network)", mailbox = "5500", watch = watch, airplaneModeAtStart = airplane,
            wifiAtStart = true, heard = audio, intended = if (incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
            feature = T2Feature.VOLTE, session = 1, secondLine = secondLine,
        )
    }

    @Test
    fun `T2 passes with an outgoing and an incoming call on the feature's bearer and audio yes`() {
        val volte = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), call("mt", 13)))
        assertTrue(volte.passed)
        assertTrue(volte.concluded)
        assertNull(volte.failed)
        assertTrue(T2.assess(T2Feature.VOWIFI, listOf(call("mo", 18), call("mt", 18))).passed)
        assertEquals(13, T2Feature.VOLTE.bearerCode)
        assertEquals(18, T2Feature.VOWIFI.bearerCode)
    }

    @Test
    fun `a failed call or a no on audio fails the feature (lab failed)`() {
        for ((calls, failed) in listOf(
            listOf(call("mo", 13), call("mt", 13, active = false)) to "mt_call_did_not_connect",
            listOf(call("mo", 13, audio = AudioAnswer.NO), call("mt", 13)) to "mo_audio_no",
        )) {
            val result = T2.assess(T2Feature.VOLTE, calls)
            assertFalse(failed, result.passed)
            assertEquals(failed, result.failed)
            assertTrue(result.concluded)
        }
    }

    @Test
    fun `round 26 - a bearer that is not proven or another bearer does not count and never fails`() {
        for ((calls, why) in listOf(
            listOf(call("mo", 18), call("mt", 13)) to "mo_other_bearer_18",
            listOf(call("mo", null), call("mt", 13)) to "mo_bearer_not_proven",
            // LTE_CA (19) is not 13: the lab's T2 code is exact.
            listOf(call("mo", 19), call("mt", 13)) to "mo_other_bearer_19",
            listOf(call("mo", 13), call("mt", 16)) to "mt_other_bearer_16",
            // PHH's default 13 while the IMS data connection is on Wi-Fi: the call type alone is no proof.
            listOf(call("mo", 13, pdn = listOf(ImsTransport.IWLAN)), call("mt", 13)) to "mo_bearer_not_proven",
            // LTE with the data network on 5G, or not read.
            listOf(call("mo", 13, rat = "nr"), call("mt", 13)) to "mo_bearer_not_proven",
            listOf(call("mo", 13, rat = null), call("mt", 13)) to "mo_bearer_not_proven",
            // Both transports connected (a handover in the middle).
            listOf(call("mo", 13), call("mt", 13, pdn = listOf(ImsTransport.LTE, ImsTransport.IWLAN))) to "mt_bearer_not_proven",
            listOf(call("mo", 13, pdn = emptyList()), call("mt", 13)) to "mo_bearer_not_proven",
        )) {
            val result = T2.assess(T2Feature.VOLTE, calls)
            assertFalse(why, result.passed)
            assertNull(why, result.failed)
            assertFalse(why, result.concluded)
            assertTrue("$why in ${result.notCounted}", why in result.notCounted)
        }
        // A watch from before round 26 has no proof: it does not count.
        val old = call("mo", 13).let { it.copy(watch = it.watch.copy(bearerProof = null)) }
        assertEquals(listOf("mo_bearer_not_proven"), T2.assess(T2Feature.VOLTE, listOf(old, call("mt", 13))).notCounted)
        assertTrue("try again" in T2.assess(T2Feature.VOLTE, listOf(old, call("mt", 13))).plain)
    }

    @Test
    fun `what does not count waits, without failing (lab not_counted)`() {
        for ((mt, why) in listOf(
            call("mt", 13, mailbox = true) to "mt_from_mailbox",
            call("mt", 13, ringing = false) to "mt_not_incoming",
            call("mt", 13, secondLine = null) to "mt_not_confirmed_second_line",
            call("mt", 13, secondLine = false) to "mt_not_confirmed_second_line",
            call("mt", 13, audio = AudioAnswer.NOT_SURE) to "mt_no_audio_answer",
            call("mt", 13, calls = 2) to "mt_several_calls",
        )) {
            val result = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), mt))
            assertFalse(why, result.passed)
            assertNull(why, result.failed)
            assertFalse(why, result.concluded)
            assertTrue("$why in ${result.notCounted}", why in result.notCounted)
            assertTrue(result.outgoing != null && result.incoming == null)
        }
        // The mailbox (outgoing) call alone never gives the incoming half.
        assertFalse(T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), call("mo", 13))).passed)
        // A later counted incoming call completes the test.
        val later = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), call("mt", 13, mailbox = true), call("mt", 13)))
        assertTrue(later.passed)
    }

    @Test
    fun `Wi-Fi calling needs no airplane mode with the bearer proven (round 26), and an outgoing call must be measured as outgoing`() {
        assertTrue(T2.assess(T2Feature.VOWIFI, listOf(call("mo", 18, airplane = false), call("mt", 18, airplane = false))).passed)
        // Call type 18 with the IMS data connection on LTE: not proven.
        val mixed = T2.assess(T2Feature.VOWIFI, listOf(call("mo", 18, pdn = listOf(ImsTransport.LTE)), call("mt", 18)))
        assertFalse(mixed.passed)
        assertNull(mixed.failed)
        assertEquals(listOf("mo_bearer_not_proven"), mixed.notCounted)
        assertTrue(T2.assess(T2Feature.VOLTE, listOf(call("mo", 13, airplane = false), call("mt", 13, airplane = false))).passed)
        val rang = call("mo", 13).let { it.copy(watch = it.watch.copy(direction = CallDirection.INCOMING, ringingSeen = true)) }
        assertEquals(listOf("mo_not_outgoing"), T2.assess(T2Feature.VOLTE, listOf(rang, call("mt", 13))).notCounted)
    }

    @Test
    fun `the plain result names what happened and never the number`() {
        val failed = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13, audio = AudioAnswer.NO), call("mt", 13)))
        assertEquals("T2 not passed for VoLTE: you said the audio of the outgoing call did not work. A new test can start again.", failed.plain)
        val other = T2.assess(T2Feature.VOLTE, listOf(call("mo", 18), call("mt", 13)))
        assertTrue(other.plain, "the outgoing call ran with call network type 18, not 13 (4G (LTE)); try again on 4G (LTE)" in other.plain)
        val waiting = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), call("mt", 13, mailbox = true)))
        assertTrue(waiting.plain, "mailbox call never counts as the incoming call" in waiting.plain)
        val passed = T2.assess(T2Feature.VOLTE, listOf(call("mo", 13), call("mt", 13)))
        assertEquals(
            "T2 passed for VoLTE: an outgoing and an incoming call over 4G (LTE), call network type 13 with the bearer proven, audio confirmed.",
            passed.plain,
        )
    }

    private fun pass(feature: T2Feature = T2Feature.VOLTE, origin: ConfigOrigin = ConfigOrigin.AS_FOUND, spn: String? = "LIDL Connect") = T2Record(
        date = "2026-10-19", model = "SM-G981B", codename = "x1s", romFamily = "lineageos", androidMajor = 16,
        imsStack = "aosp_telephony", homeMccMnc = "26202", spn = spn, ocdProfile = "open.26202.8cebd7550c67",
        feature = feature, bearerCode = feature.bearerCode, session = 1, configOrigin = origin,
    )

    @Test
    fun `round 23 LB4 - registered without T2, works on this phone only with T2 on the same phone and SIM`() {
        val s20 = readings(device = Fixtures.s20Lineage, deep = Fixtures.s20Deep())
        val empty = CallTestLog()
        val registered = T2Status.line(T2Feature.VOLTE, empty, s20)
        assertEquals(
            "registered over 4G (LTE) with these settings; no T2 on this phone and SIM yet, so this is not a call test",
            registered,
        )
        assertEquals("not registered over Wi-Fi (IWLAN) now; no T2 on this phone and SIM yet", T2Status.line(T2Feature.VOWIFI, empty, s20))
        assertEquals(
            "registration not read (needs the deep check); no T2 on this phone and SIM yet",
            T2Status.line(T2Feature.VOLTE, empty, readings(device = Fixtures.s20Lineage)),
        )
        val worksWord = Regex("(?i)\\bworks\\b|verified")
        for (line in listOf(registered, T2Status.line(T2Feature.VOWIFI, empty, s20))) assertFalse(line, worksWord.containsMatchIn(line))

        val log = empty.plus(pass())
        val works = T2Status.line(T2Feature.VOLTE, log, s20)
        assertEquals(
            "works on this phone: T2 passed (an outgoing and an incoming call over 4G (LTE), call network type 13, " +
                "audio confirmed, with the settings as found, 2026-10-19)",
            works,
        )
        assertTrue(works.startsWith("works on this phone: T2 passed"))
        // A pass for another SIM or feature says nothing here.
        assertFalse(worksWord.containsMatchIn(T2Status.line(T2Feature.VOLTE, empty.plus(pass(spn = "other")), s20)))
        assertFalse(worksWord.containsMatchIn(T2Status.line(T2Feature.VOWIFI, log, s20)))
        assertTrue("after you turned it on yourself" in pass(origin = ConfigOrigin.USER_TOGGLE).plain)
        assertEquals(log, CallTestLog.decode(log.encode()))
        // Stored as OCD's ledger names it.
        assertTrue("\"configOrigin\":\"as_found\"" in log.encode())
        assertTrue("\"configOrigin\":\"user_toggle\"" in empty.plus(pass(origin = ConfigOrigin.USER_TOGGLE)).encode())
    }
}
