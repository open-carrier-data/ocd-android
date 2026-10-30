package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.ProbeTargets
import io.github.open_carrier_data.check.core.RedirectHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCD rule round 42, item 2: the probe ladder's fixed targets and the bounds on when a run may happen. */
class ProbeRunTest {
    @Test
    fun `a redirect host is kept only as the carrier portal class or another host, never the URL`() {
        assertEquals(RedirectHost.CARRIER_PORTAL, ProbeTargets.redirectHost("26201", "pass.telekom.de"))
        assertEquals(RedirectHost.CARRIER_PORTAL, ProbeTargets.redirectHost("26202", "center.vodafone.de"))
        // A host not on this network's portal allowlist is "another host", never kept.
        assertEquals(RedirectHost.OTHER, ProbeTargets.redirectHost("26201", "tracker.example.com"))
        // The Telekom portal is not a portal for a Vodafone SIM.
        assertEquals(RedirectHost.OTHER, ProbeTargets.redirectHost("26202", "pass.telekom.de"))
        assertEquals(RedirectHost.NONE, ProbeTargets.redirectHost("26201", null))
        assertEquals(RedirectHost.NONE, ProbeTargets.redirectHost("26201", ""))
    }

    @Test
    fun `D5 ships only pass_telekom_de, and only for the Telekom networks`() {
        assertEquals("http://pass.telekom.de/", ProbeTargets.carrierPage("26201"))
        assertEquals("http://pass.telekom.de/", ProbeTargets.carrierPage("26206"))
        // center.vodafone.de joins only once a measured fetch confirms it answers without a login (ruling).
        assertNull(ProbeTargets.carrierPage("26202"))
        assertNull(ProbeTargets.carrierPage("26203"))
        assertNull(ProbeTargets.carrierPage(null))
    }

    @Test
    fun `a run is refused while roaming, with data off, or when roaming cannot be read`() {
        assertNull(ProbePolicy.refusal(roaming = false, dataOff = false))
        assertEquals("the phone is roaming", ProbePolicy.refusal(roaming = true, dataOff = false))
        assertEquals("mobile data is switched off", ProbePolicy.refusal(roaming = false, dataOff = true))
        assertTrue(ProbePolicy.refusal(roaming = null, dataOff = false)!!.contains("could not be read"))
    }

    @Test
    fun `an automatic run needs a measured failure and at most one per SIM per 6 hours`() {
        val now = 1_000_000_000_000L
        assertFalse("no run without a measured failure", ProbePolicy.autoDue(measuredFailure = false, lastRunAtMs = null, nowMs = now))
        assertTrue("first run on a measured failure", ProbePolicy.autoDue(measuredFailure = true, lastRunAtMs = null, nowMs = now))
        val fiveHours = now - 5 * 60 * 60 * 1000L
        assertFalse("too soon within 6 hours", ProbePolicy.autoDue(true, fiveHours, now))
        val sevenHours = now - 7 * 60 * 60 * 1000L
        assertTrue("due after 6 hours", ProbePolicy.autoDue(true, sevenHours, now))
    }

    @Test
    fun `the read-only tap keeps a short cooldown`() {
        val now = 1_000_000_000_000L
        assertTrue(ProbePolicy.tapAllowed(lastTapAtMs = null, nowMs = now))
        assertFalse(ProbePolicy.tapAllowed(lastTapAtMs = now - 30_000, nowMs = now))
        assertTrue(ProbePolicy.tapAllowed(lastTapAtMs = now - 3 * 60 * 1000, nowMs = now))
    }
}
