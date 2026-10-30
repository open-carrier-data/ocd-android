package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.MmsLadder
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
