package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.owner.core.AccountReadMark
import io.github.open_carrier_data.check.owner.core.AccountSource
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ProbeRunMark
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TesterBuild
import io.github.open_carrier_data.check.core.UserPlaneClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** OCD rule round 42, items 2/4/5: the account-state marks' freshness and the balance cap. Pure. */
class AccountStateTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val sim = SimKey(2, "26202", 1)
    private val other = SimKey(3, "26201", 2)
    private val now = 1_761_800_000_000L // a fixed wall clock

    @Test
    fun `a balance counts only on the day it was read`() {
        val today = AccountReadMark(sim, now, AccountState.BLOCKED, AccountSource.BALANCE, "0,10 EUR")
        assertTrue(AccountReadMark.isFresh(today, now, zone))
        val (state, balance) = AccountReadMark.fresh(listOf(today), sim, now, zone)
        assertEquals(AccountState.BLOCKED, state)
        assertEquals("0,10 EUR", balance)
        // A day later the same balance no longer counts.
        val nextDay = now + TimeUnit.DAYS.toMillis(1)
        assertFalse(AccountReadMark.isFresh(today, nextDay, zone))
        assertEquals(AccountState.UNKNOWN, AccountReadMark.fresh(listOf(today), sim, nextDay, zone).first)
    }

    @Test
    fun `a carrier-SMS class counts until replaced or 31 days`() {
        val sms = AccountReadMark(sim, now, AccountState.BLOCKED, AccountSource.SMS)
        assertTrue(AccountReadMark.isFresh(sms, now + TimeUnit.DAYS.toMillis(30), zone))
        assertFalse(AccountReadMark.isFresh(sms, now + TimeUnit.DAYS.toMillis(32), zone))
        // A later SMS class replaces the earlier one for the same SIM and source.
        val later = AccountReadMark(sim, now + 1000, AccountState.OK, AccountSource.SMS)
        val marks = AccountReadMark.add(AccountReadMark.add(emptyList(), sms), later)
        assertEquals(1, marks.count { it.source == AccountSource.SMS })
        assertEquals(AccountState.OK, AccountReadMark.fresh(marks, sim, now + 2000, zone).first)
    }

    @Test
    fun `a balance and a carrier-SMS class are kept apart, and the newer fresh read wins`() {
        val balance = AccountReadMark(sim, now, AccountState.BLOCKED, AccountSource.BALANCE, "0,10 EUR")
        val sms = AccountReadMark(sim, now + 1000, AccountState.OK, AccountSource.SMS)
        val marks = AccountReadMark.add(AccountReadMark.add(emptyList(), balance), sms)
        assertEquals(2, marks.size) // balance and SMS do not displace each other
        assertEquals(AccountState.OK, AccountReadMark.fresh(marks, sim, now + 2000, zone).first)
    }

    @Test
    fun `marks are per SIM`() {
        val mine = AccountReadMark(sim, now, AccountState.BLOCKED, AccountSource.SMS)
        assertEquals(AccountState.UNKNOWN, AccountReadMark.fresh(listOf(mine), other, now, zone).first)
    }

    @Test
    fun `the balance read runs only on the opt-in, and on rom also behind the active-tests opt-in`() {
        // The allowed() check reads only the toggles, so a MemoryStore-free OwnerState is enough.
        val off = OwnerState()
        assertFalse(OwnerBalance.allowed(off))
        val on = OwnerState(balanceUssdOn = true)
        // On the owner build the toggle alone enables it; the rom build needs the active-tests opt-in too.
        val enabled = OwnerBalance.allowed(on)
        assertEquals(OwnerTester.build == TesterBuild.OWNER, enabled)
    }

    @Test
    fun `the balance daily cap holds`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val asked = listOf(now - 1000, now - 2000, now - 3000)
        assertFalse("three in a day is the cap", OwnerBalance.underCap(asked, now))
        assertTrue("two in a day is under the cap", OwnerBalance.underCap(asked.drop(1), now))
        // Sends older than a day do not count.
        assertTrue(OwnerBalance.underCap(asked.map { it - day - 1000 }, now))
    }

    @Test
    fun `a probe run mark is per SIM and keeps the latest`() {
        val first = ProbeRunMark(sim, now, UserPlaneClass.PATH_CLEAN)
        val second = ProbeRunMark(sim, now + 1000, UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE)
        val marks = ProbeRunMark.add(ProbeRunMark.add(emptyList(), first), second)
        assertEquals(1, marks.size)
        assertEquals(UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, ProbeRunMark.last(marks, sim)!!.userPlane)
        assertNull(ProbeRunMark.last(marks, other))
    }
}
