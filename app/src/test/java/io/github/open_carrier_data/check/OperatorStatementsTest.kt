package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.BundledMmsEnded
import io.github.open_carrier_data.check.core.MmsEndedList
import io.github.open_carrier_data.check.core.OperatorFiles
import io.github.open_carrier_data.check.core.OperatorsFileRefused
import io.github.open_carrier_data.check.core.StatementDisplay
import io.github.open_carrier_data.check.core.StatementView
import io.github.open_carrier_data.check.core.StatementWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD's rule round 32, part A and the MMS override: the published operator statements
 * (generated/operators/statements.json of public main 7247b602) and the carrier-identity table,
 * shown in part A's words, never "works", a host-only brand never as a statement about the SIM, and
 * the MMS-ended list that X6 reads.
 */
class OperatorStatementsTest {
    private val statementsText = File("src/test/resources/operators/statements.json").readText()
    private val statements = OperatorFiles.statements(statementsText)
    private val identity = OperatorFiles.identity(File("src/test/resources/operators/carrier-identity.json").readText())

    /** The o2 SIM of the owner's S20 (262-03), matched to Telefónica's plain profile on its network. */
    private val o2 = StatementView.forSim(statements, identity, "26203", listOf("open.26203.dbb2be7109a6"))

    @Test
    fun `the published files are read, with their MMS-ended networks`() {
        assertEquals(40, statements.statements.size)
        assertEquals(7, statements.counts.setsValue)
        assertEquals(listOf("22802", "22803", "26202", "26203", "26207", "26223", "27001", "50212"), statements.mmsEndedNetworks.map { it.mccmnc })
        assertTrue(identity.entries.size >= 26)
    }

    @Test
    fun `an o2 SIM shows o2's MMS end first, and every offer says the tariff is not checked`() {
        assertEquals("Telefónica Deutschland (O2)", o2.about)
        assertEquals("The operator states MMS ended on 2026-06-29", o2.lines.first())
        val offers = o2.lines.filter { "offers" in it }
        assertTrue(offers.size >= 5)
        for (line in offers) assertTrue(line, line.endsWith("(read on 2026-10-28); whether your tariff includes it is not checked"))
        assertTrue(o2.lines.contains("The operator states it offers VoLTE (calls over 4G) (read on 2026-10-28); whether your tariff includes it is not checked"))
        // Wi-Fi calling abroad is the operator's word, never OCD's wifi_calling_roaming.
        assertTrue(o2.lines.any { it.startsWith("The operator states it offers Wi-Fi calling abroad") })
        for (line in o2.lines) {
            assertFalse(line, Regex("\\bworks?\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
            assertFalse(line, "roaming" in line && "Wi-Fi" in line)
        }
    }

    @Test
    fun `host-only brands are only brands on the network, never statements about the SIM`() {
        assertEquals(listOf("ALDI TALK", "Blau", "simyo", "sipgate"), o2.brandsOnNetwork)
        // ALDI TALK's own statements (host_only) never reach a 262-03 SIM's lines.
        assertEquals(o2.lines.size, o2.lines.distinct().size)
        val aldi = statements.statements.filter { it.identityKey == "de.aldi-talk" }
        assertTrue(aldi.isNotEmpty() && aldi.all { it.hostOnly })
        val vodafone = StatementView.forSim(statements, identity, "26202", listOf("open.26202.8cebd7550c67"))
        assertTrue("LIDL Connect" in vodafone.brandsOnNetwork)
        assertEquals("The operator states MMS ended on 2023-01-16", vodafone.lines.first())
        assertTrue(vodafone.lines.contains("The operator states it does not offer Wi-Fi calling abroad (read on 2026-10-28)"))
    }

    @Test
    fun `a brand's statement stays with the brand, a network-wide end reaches every SIM on the network`() {
        // A plain Telekom SIM: Telekom's own statements; congstar's MMS end is the brand's (scope brand), not the network's.
        val telekom = StatementView.forSim(statements, identity, "26201", listOf("open.26201.13528695fee7"))
        assertEquals("Telekom Deutschland", telekom.about)
        assertFalse(telekom.lines.any { "MMS" in it })
        assertTrue(telekom.lines.none { "VoNR" in it && "does not offer" in it }) // congstar's, not Telekom's
        // An MVNO's SIM on Telefónica's network gets the network-wide MMS end, named by who states it.
        val mvno = StatementView.forSim(statements, identity, "26203", listOf("open.26203.dbb2be7109a6", "open.not.in.the.table"))
        assertTrue(mvno.lines.first().contains("MMS ended on 2026-06-29"))
        // No statements file: nothing to show.
        assertTrue(StatementView.forSim(null, identity, "26203", emptyList()).empty)
    }

    @Test
    fun `part A's wording for each status`() {
        val ended = statements.byId("ch.salt.mms")!!
        assertEquals("The operator states MMS ended; it is off since 2023-01-10", StatementWords.line(ended))
        val lapsed = statements.byId("de.vodafone.volte")!!.copy(status = "unknown", previousStatus = "offered", lapsed = io.github.open_carrier_data.check.core.StatementLapse("not_confirmed_180_days", "2027-04-26"))
        assertEquals("VoLTE (calls over 4G): no current statement; the operator's last one lapsed on 2027-04-26", StatementWords.line(lapsed))
        assertNull(StatementWords.line(lapsed.copy(lapsed = null)))
        assertTrue("never" in StatementDisplay.NOTE && "tariff" in StatementDisplay.NOTE)
    }

    @Test
    fun `X6's list - the published networks first, the bundled ones only where the published list has none`() {
        val bundled = listOf(BundledMmsEnded("Telekom Deutschland", listOf("26201"), "2026-06-30"), BundledMmsEnded("Telefonica Germany / O2", listOf("26203", "26207"), "2026-06-30"))
        val list = MmsEndedList.of(statements, bundled)
        assertEquals(9, list.size)
        assertEquals("Telefonica Deutschland (O2) states MMS ended on 2026-06-29", MmsEndedList.find(list, "26203")!!.plain)
        assertEquals("de.telefonica.mms", MmsEndedList.find(list, "26207")!!.statement)
        assertFalse(MmsEndedList.find(list, "26201")!!.published)
        assertEquals("POST Luxembourg states MMS ended; it is off since 2024-09-29", MmsEndedList.find(list, "27001")!!.plain)
        assertEquals(setOf("26203", "26207"), MmsEndedList.coveredByPublished(statements, bundled))
        assertNull(MmsEndedList.find(list, "26204"))
    }

    @Test
    fun `a file this build does not read is refused whole (F3)`() {
        assertThrows(OperatorsFileRefused::class.java) { OperatorFiles.statements(statementsText.replaceFirst("\"schema_version\": 1", "\"schema_version\": 2")) }
        assertThrows(OperatorsFileRefused::class.java) { OperatorFiles.statements(statementsText.replace("\"service\": \"esim\"", "\"service\": \"satellite\"")) }
        assertThrows(OperatorsFileRefused::class.java) { OperatorFiles.statements(statementsText.replace("\"statement\": \"ch.salt.mms\"", "\"statement\": \"de.vodafone.volte\"")) }
        assertThrows(OperatorsFileRefused::class.java) { OperatorFiles.statements(statementsText.dropLast(200)) }
        assertThrows(OperatorsFileRefused::class.java) { OperatorFiles.statements(statementsText.replace("\"CC0-1.0\"", "\"CC-BY-4.0\"")) }
    }
}
