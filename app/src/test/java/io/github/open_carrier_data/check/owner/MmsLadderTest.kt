package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.MmsEndedEntry
import io.github.open_carrier_data.check.owner.core.MmsCheck
import io.github.open_carrier_data.check.owner.core.MmsLadder
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.SimKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** OCD round 42, item 6: the MMS ladder's M4 reading and the corroboration on an MMS-ended network. */
class MmsLadderTest {
    @Test
    fun `the GET status reads the MMSC`() {
        assertEquals("alive", MmsLadder.classifyStatus(404))
        assertEquals("refuses_subscriber", MmsLadder.classifyStatus(403))
        assertEquals("degraded", MmsLadder.classifyStatus(503))
        assertEquals("reachable", MmsLadder.classifyStatus(200))
        assertEquals("no_answer", MmsLadder.classifyStatus(null))
    }

    @Test
    fun `corroboration on an ended network supports or flags, never flips`() {
        // No answer supports the "ended" statement; an answer only flags it for a fresh hand read.
        assertEquals("no_answer_supports_ended", MmsLadder.corroboration(null))
        assertEquals("answer_flags_for_reread", MmsLadder.corroboration(404))
        assertEquals("answer_flags_for_reread", MmsLadder.corroboration(200))
    }

    private val sim = SimKey(3, "26202", 2397)
    private val vodafone = MmsEndedEntry(
        mccmnc = "26202", operator = "Vodafone Deutschland", lastDay = "2023-01-16", firstDayWithout = "2023-01-17", statement = "de.vodafone.mms",
    )
    private val head = "MMS: not checked, the network no longer offers MMS (Vodafone Deutschland states MMS ended on 2023-01-16)"

    /** The S20 with LIDL Connect on 2026-10-31: event.vodafone.de refused 11 times with 55, so the MMS network never came up. */
    private val m1Failed = MmsCheck(
        sim, 1_793_442_622_000, "ended", operator = "Vodafone Deutschland", ended = "2023-01-16",
        stage = "m1_apn", apnCause = 55, corroboration = MmsLadder.corroboration(null),
    )

    @Test
    fun `an MMS network that does not come up supports the ended statement, with its cause`() {
        assertEquals("no_answer_supports_ended", m1Failed.corroboration)
        assertEquals(
            "$head; the free probe on 2026-10-31 could not open the MMS connection (code 55), which agrees (no MMS was sent)",
            MmsProbe.line(m1Failed, vodafone, ZoneOffset.UTC),
        )
        // The result stays "ended": nothing flips X6 or enabledMMS, and no candidate trial reads it as an MMSC failure.
        assertEquals("ended", m1Failed.result)
    }

    @Test
    fun `no cause, an Android-internal cause and an unanswered GET read plainly`() {
        assertEquals(
            "$head; the free probe on 2026-10-31 could not open the MMS connection, which agrees (no MMS was sent)",
            MmsProbe.line(m1Failed.copy(apnCause = null), vodafone, ZoneOffset.UTC),
        )
        assertEquals(
            "$head; the free probe on 2026-10-31 could not open the MMS connection, which agrees (no MMS was sent)",
            MmsProbe.line(m1Failed.copy(apnCause = 65536), vodafone, ZoneOffset.UTC),
        )
        assertEquals(
            "$head; the free probe on 2026-10-31 got no answer from the MMS access point, which agrees (no MMS was sent)",
            MmsProbe.line(m1Failed.copy(stage = "m3_tcp", apnCause = null), vodafone, ZoneOffset.UTC),
        )
    }

    @Test
    fun `an answer on an ended network flags the statement for a fresh read and never says MMS works`() {
        val answered = m1Failed.copy(stage = "m4_get", apnCause = null, httpStatus = 404, corroboration = MmsLadder.corroboration(404))
        val line = MmsProbe.line(answered, vodafone, ZoneOffset.UTC)
        assertEquals("$head; but the MMS access point answered the free probe on 2026-10-31 (no MMS was sent), so the statement is flagged for a fresh read", line)
        assertFalse(Regex("(?i)\\bworks\\b").containsMatchIn(line))
    }

    @Test
    fun `a record from before the corroboration, or none, keeps the plain ended line`() {
        assertEquals(head, MmsProbe.line(null, vodafone, ZoneOffset.UTC))
        assertEquals(head, MmsProbe.line(m1Failed.copy(stage = null, apnCause = null, corroboration = null), vodafone, ZoneOffset.UTC))
        // The state files of 0.4.9 decode unchanged (the fields existed already).
        val old = Json.decodeFromString(MmsCheck.serializer(), """{"sim":{"subId":3,"mccmnc":"26202","carrierId":2397},"atMs":1793442622000,"result":"ended","operator":"Vodafone Deutschland","ended":"2023-01-16","stage":"m0_ended"}""")
        assertEquals(head, MmsProbe.line(old, vodafone, ZoneOffset.UTC))
        assertTrue(MmsProbe.line(m1Failed, vodafone, ZoneOffset.UTC).startsWith(head))
    }
}
