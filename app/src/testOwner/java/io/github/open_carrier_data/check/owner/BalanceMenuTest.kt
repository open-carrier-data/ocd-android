package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountRead
import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.BalanceUssd
import io.github.open_carrier_data.check.owner.core.BalanceHalt
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OCD round 42, follow-up fix of 2026-10-30 (the S20 test of 0.4.7, bug 2): a USSD reply that asks for
 * input reaches the app as onReceiveUssdResponseFailed, without its text. 0.4.7 counted the send,
 * returned null and logged nothing. Now that is recorded, and one send that read nothing stops the
 * SIM's sends until the next day.
 */
class BalanceMenuTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val sim = SimKey(2, "26202", 1)
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a failed or missing reply is recorded, never dropped without a record`() {
        val failed = OwnerBalance.outcome(OwnerBalance.Reply.Failed)
        assertEquals(AccountState.UNKNOWN, failed.state)
        assertEquals("balance not read: the carrier's reply needs a menu step or failed", OwnerBalance.balanceLine(failed))
        assertEquals("balance not read: no reply from the carrier", OwnerBalance.balanceLine(OwnerBalance.outcome(OwnerBalance.Reply.NoReply)))
        // A menu text that does arrive keeps the ruling's record.
        val menu = OwnerBalance.outcome(OwnerBalance.Reply.Text(BalanceUssd.classify("Wählen Sie bitte aus: 1 Aufladen, 2 Guthaben & Verbrauch")))
        assertEquals("menu: the balance is one step down; pick it by hand", OwnerBalance.balanceLine(menu))
        // A balance is read as before.
        val read = OwnerBalance.outcome(OwnerBalance.Reply.Text(BalanceUssd.classify("Aktuelles Guthaben: 0,10 EUR")))
        assertEquals("balance blocked (0,10 EUR)", OwnerBalance.balanceLine(read))
    }

    @Test
    fun `a send that read nothing halts the SIM until the next day, a balance does not`() {
        assertTrue(OwnerBalance.halts(OwnerBalance.outcome(OwnerBalance.Reply.Failed)))
        assertTrue(OwnerBalance.halts(OwnerBalance.outcome(OwnerBalance.Reply.NoReply)))
        assertTrue(OwnerBalance.halts(BalanceUssd.classify("Wählen Sie bitte aus: 1 Aufladen, 2 Guthaben & Verbrauch")))
        assertFalse(OwnerBalance.halts(BalanceUssd.classify("Aktuelles Guthaben: 12,50 EUR")))
        assertFalse(OwnerBalance.halts(AccountRead(AccountState.BLOCKED, "0,10 EUR")))

        val halts = BalanceHalt.add(emptyList(), BalanceHalt(sim, at(30, 18), BalanceUssd.REPLY_FAILED))
        assertTrue("the same day", BalanceHalt.halted(halts, sim, at(30, 23), zone))
        assertFalse("the next day", BalanceHalt.halted(halts, sim, at(31, 0) + 1, zone))
        assertTrue("a clock that went back", BalanceHalt.halted(halts, sim, at(30, 17), zone))
        assertFalse("another SIM", BalanceHalt.halted(halts, SimKey(3, "26201", 2), at(30, 23), zone))
        // One halt per SIM, the latest kept.
        val again = BalanceHalt.add(halts, BalanceHalt(sim, at(31, 9), BalanceUssd.NO_REPLY))
        assertEquals(1, again.size)
        assertEquals(BalanceUssd.NO_REPLY, again.single().why)
    }

    @Test
    fun `a state file of 0_4_7 reads with no halts, and a halt round-trips without the reply`() {
        assertTrue(OwnerState.decode("{}").balanceHalts.isEmpty())
        val state = OwnerState(balanceHalts = listOf(BalanceHalt(sim, at(30, 18), BalanceUssd.REPLY_FAILED)))
        assertEquals(state.balanceHalts, OwnerState.decode(state.encode()).balanceHalts)
    }

    @Test
    fun `the runner checks the halt before a send, maps the failure callback, and records every outcome`() {
        val balance = File("src/owner/java/io/github/open_carrier_data/check/owner/OwnerBalance.kt").readText()
        assertTrue(balance.contains("if (BalanceHalt.halted(state.balanceHalts, sim.key, nowMs, zone)) return null"))
        // The halt is read before the send is counted and made.
        assertTrue(balance.indexOf("BalanceHalt.halted(") < balance.indexOf("balanceAskedMs = (it.balanceAskedMs + nowMs)"))
        assertTrue(balance.contains("if (!done.isCompleted) done.complete(Reply.Failed)"))
        assertTrue(balance.contains("?: Reply.NoReply"))
        assertTrue(balance.contains("it.logged(nowMs, \"balance\", \"slot \${sim.slot + 1}: \${balanceLine(read)}\")"))
        // Still exactly one send per read, and never a menu digit.
        assertEquals(1, Regex("sendUssdRequest\\(").findAll(balance).count())
        assertFalse(balance.contains("sendUssdRequest(\"1") || balance.contains("sendUssdRequest(\"2"))
    }
}
