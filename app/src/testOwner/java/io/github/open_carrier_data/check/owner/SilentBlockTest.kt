package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.ProbeResults
import io.github.open_carrier_data.check.core.RedirectHost
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ProbeRunMark
import io.github.open_carrier_data.check.owner.core.SimKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OCD round 42, follow-up fix of 2026-10-30 (the S20 test of 0.4.7): the silent block opens the gate.
 * LIDL's block leaves every APN's last cause 0 and the reject cause 0, so 0.4.7 never ran the probe
 * ladder or the balance read for the case the round was built for. A cellular network that is up but
 * not validated (NetworkMonitor, read on the SIM's cellular network) now counts as a measured
 * mobile-data failure; the owner's "check now" runs the ladder on demand within the same refusals and
 * a short cooldown.
 */
class SilentBlockTest {
    private val sim = SimKey(2, "26202", 1)
    private val now = 1_761_800_000_000L

    /** The LIDL case as the S20 read it on 2026-10-30: causes 0, no reject, the cellular network up, not validated. */
    private val lidl = DiagnosisInputs(defaultDataCause = 0, imsDataCause = 0, rejectCause = null, cellularUp = true, dataValidated = false, imsRegistered = true)

    @Test
    fun `the cellular network up but not validated is a measured failure, with every cause 0`() {
        assertTrue(lidl.cellularUnvalidated)
        assertTrue(lidl.measuredDataFailure)
        // Validated, not up, or unreadable: no failure (the gate fails closed; nothing runs on a guess).
        assertFalse(lidl.copy(dataValidated = true).measuredDataFailure)
        assertFalse(lidl.copy(cellularUp = false, dataValidated = null).measuredDataFailure)
        assertFalse(lidl.copy(cellularUp = null, dataValidated = null).measuredDataFailure)
        assertFalse(lidl.copy(dataValidated = null).measuredDataFailure)
        // The code paths still count on their own.
        assertTrue(DiagnosisInputs(defaultDataCause = 33, cellularUp = true, dataValidated = true).measuredDataFailure)
        assertTrue(DiagnosisInputs(rejectCause = 7).measuredDataFailure)
    }

    @Test
    fun `unvalidated alone names no verdict, the ladder's signature then names the account block`() {
        // No code and no user-plane reading: no mobile-data line is made up from the gate alone.
        assertNull(Diagnoses.of(lidl).verdicts.firstOrNull { it.feature == VerdictFeature.MOBILE_DATA })
        // With the ladder's signature (one signal, no balance read): the ruling's hedged words, not a wrong APN.
        val d = Diagnoses.of(lidl.copy(userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE))
        assertEquals("Mobile data probably blocked by the account", d.line)
        assertFalse("one signal never sets a test aside", d.notCounted())
    }

    @Test
    fun `check now runs the ladder on demand after a short cooldown, the automatic rule keeps its 6 hours`() {
        val cooldown = ProbePolicy.CHECK_NOW_COOLDOWN_MS
        assertEquals(2 * 60 * 1000L, cooldown)
        // On demand: no measured failure needed.
        assertTrue(ProbePolicy.due(measuredFailure = false, checkNow = true, lastRunAtMs = null, nowMs = now))
        assertFalse(ProbePolicy.due(false, true, now - 30_000, now))
        assertTrue(ProbePolicy.due(false, true, now - cooldown, now))
        assertTrue("a clock that went back", ProbePolicy.due(false, true, now + 60_000, now))
        // Check now right after an automatic run still waits for the cooldown only.
        assertTrue(ProbePolicy.due(false, true, now - TimeUnit.MINUTES.toMillis(5), now))
        // The automatic rule is unchanged: a measured failure and 6 hours.
        assertFalse(ProbePolicy.due(measuredFailure = false, checkNow = false, lastRunAtMs = null, nowMs = now))
        assertTrue(ProbePolicy.due(true, false, null, now))
        assertFalse(ProbePolicy.due(true, false, now - TimeUnit.HOURS.toMillis(5), now))
        assertTrue(ProbePolicy.due(true, false, now - TimeUnit.HOURS.toMillis(6), now))
        // The hard stops apply to both: roaming, unreadable roaming, mobile data off.
        assertTrue(ProbePolicy.refusal(roaming = true, dataOff = false) != null)
        assertTrue(ProbePolicy.refusal(roaming = null, dataOff = false) != null)
        assertTrue(ProbePolicy.refusal(roaming = false, dataOff = true) != null)
    }

    private val blocked = ProbeResults(
        loopbackOk = true, carrierDnsOk = true, publicDnsOk = true,
        ipv4LiteralOk = false, ipv6LiteralOk = false, hostOk = false, http204Clean = false, redirect = RedirectHost.NONE,
    )

    @Test
    fun `a run keeps each step, says who started it, and feeds the verdict for 6 hours`() {
        val mark = ProbeRunMark(sim, now, UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, blocked, checkNow = true)
        assertTrue(mark.line, mark.line.startsWith("probe ladder (check now): mobile data is up but silently blocked (DNS answers, nothing else passes); D1 loopback ok; D2a "))
        assertTrue(mark.line, mark.line.contains("D3 IPv4 literal fails, IPv6 literal fails, host fails; D4 no 204, no redirect; D5 not run"))
        assertTrue(ProbeRunMark(sim, now, UserPlaneClass.PATH_CLEAN).line.startsWith("probe ladder (automatic): the data path looks clean"))
        val marks = ProbeRunMark.add(emptyList(), mark)
        assertEquals(mark, ProbeRunMark.fresh(marks, sim, now + TimeUnit.HOURS.toMillis(5)))
        assertNull(ProbeRunMark.fresh(marks, sim, now + TimeUnit.HOURS.toMillis(6)))
        assertNull("a clock that went back", ProbeRunMark.fresh(marks, sim, now - 1000))
        assertNull(ProbeRunMark.fresh(marks, SimKey(3, "26201", 2), now))
    }

    @Test
    fun `a state file of 0_4_7 with a mark without steps still reads, and a new mark round-trips`() {
        val old = """{"probeRuns":[{"sim":{"subId":2,"mccmnc":"26202","carrierId":1},"atMs":$now,"userPlane":"PATH_CLEAN"}]}"""
        val read = OwnerState.decode(old).probeRuns.single()
        assertNull(read.results)
        assertFalse(read.checkNow)
        val state = OwnerState(probeRuns = listOf(ProbeRunMark(sim, now, UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, blocked, checkNow = true)))
        assertEquals(state.probeRuns, OwnerState.decode(state.encode()).probeRuns)
        // Booleans and classes only: no address ever goes into the mark.
        assertFalse(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+|[0-9a-f]+::").containsMatchIn(state.encode().substringAfter("probeRuns")))
    }

    private fun source(path: String) = File("src/owner/java/io/github/open_carrier_data/check/owner/$path").readText()

    @Test
    fun `the gate reads validation on the SIM's cellular network, and check now reaches the ladder`() {
        val passive = source("OwnerPassiveRead.kt")
        // The SIM's own cellular network (its TelephonyNetworkSpecifier), never the default network for validation.
        assertTrue(passive.contains("(it.second.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId == subId"))
        assertTrue(passive.contains("dataValidated = cellular?.second?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        assertFalse(passive.contains("activeCaps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        // It finds the network among those Android has; it requests nothing.
        assertFalse(passive.contains("requestNetwork"))
        val work = source("OwnerWork.kt")
        assertTrue(work.contains("if ((measuredFailure || checkNow) && safe())"))
        // The balance read stays behind a measured failure; "check now" alone sends no USSD.
        assertTrue(work.contains("if (measuredFailure) runCatching { OwnerBalance.readIfDue("))
        assertTrue(work.contains("checkNow = checkNow,"))
        val probe = source("OwnerProbe.kt")
        assertTrue(probe.contains("if (ProbePolicy.refusal(roaming, dataOff) != null) return null"))
        assertTrue(probe.contains("if (!ProbePolicy.due(measuredFailure, checkNow, last?.atMs, nowMs)) return null"))
    }
}
