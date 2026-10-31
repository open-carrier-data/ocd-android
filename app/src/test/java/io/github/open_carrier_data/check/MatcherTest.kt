package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.Resolution
import io.github.open_carrier_data.check.core.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected stacks were cross-checked with OCD's tools/resolve_carrier_profiles.py
 * on the full lookup.json (the fixture holds a subset, so its stacks are the
 * resolver's stacks restricted to the fixture's profiles, in the same order).
 * Since rule round 45 the order is `--order android`; the fixture predates
 * lookup.json's weight, so the app computes the weight from the match, and
 * every stack here comes out as before.
 */
class MatcherTest {
    private fun resolve(mccmnc: String, spn: String? = "", gid1: String? = "", ids: Set<Long>? = emptySet()): Resolution =
        Matcher.resolve(Fixtures.bundle, SimIdentity(mccmnc, spn, gid1, ids))

    private fun Resolution.ids() = stack.map { it.profileId }

    @Test
    fun `network code alone gives only the base profile`() {
        assertEquals(listOf("open.26202.8cebd7550c67"), resolve("26202").ids())
    }

    @Test
    fun `an MVNO without its own profile resolves to the host network (LIDL Connect)`() {
        val resolution = resolve("26202", spn = "LIDL Connect", ids = setOf(2397))
        assertEquals(listOf("open.26202.8cebd7550c67"), resolution.ids())
        assertEquals(CapabilityValue.CONDITIONAL, resolution.answer("volte"))
        assertEquals(CapabilityValue.SUPPORTED, resolution.answer("vowifi"))
    }

    @Test
    fun `SPN matches ignoring case, generic to specific`() {
        assertEquals(
            listOf("open.26202.8cebd7550c67", "open.26202.b148b725e925"),
            resolve("26202", spn = "vodafone.DE").ids(),
        )
    }

    @Test
    fun `GID1 matches as a case-insensitive prefix (1and1 MVNO)`() {
        assertEquals(
            listOf("open.26202.8cebd7550c67", "open.26202.5e6cc8d0e19b"),
            resolve("26202", gid1 = "11ffffffff").ids(),
        )
        assertEquals(listOf("open.26202.8cebd7550c67"), resolve("26202", gid1 = "12FF").ids())
    }

    @Test
    fun `without the permission a GID1 profile is not checkable instead of failing`() {
        val resolution = resolve("26202", gid1 = null)
        assertEquals(listOf("open.26202.8cebd7550c67"), resolution.ids())
        val aside = resolution.notCheckable.associate { it.profile.profileId to it.needs }
        assertEquals(listOf("gid1"), aside["open.26202.5e6cc8d0e19b"])
        assertEquals(listOf("iccid"), aside["open.26202.db9b35c48658"])
    }

    @Test
    fun `ICCID, IMSI and GID2 profiles never match and are listed as not checkable`() {
        assertEquals(listOf("iccid"), resolve("26202").notCheckable.single().needs)
        val dutch = resolve("20404")
        assertEquals(listOf("open.20404.e431cf6d3200"), dutch.ids())
        assertEquals(listOf("imsi"), dutch.notCheckable.single().needs)
        assertEquals(listOf("gid2"), resolve("310120").notCheckable.single().needs)
    }

    @Test
    fun `Telekom stack follows OCD's README example and answers from the most specific profile`() {
        val resolution = resolve("26201", spn = "Telekom.de", ids = setOf(3))
        assertEquals(
            listOf("open.26201.13528695fee7", "open.26201.1f7e1db680d5", "open.26201.420d035152c1"),
            resolution.ids(),
        )
        // README: the most specific profile gives no VoLTE value, so the answer is unknown.
        assertEquals(CapabilityValue.UNKNOWN, resolution.answer("volte"))
        assertEquals(CapabilityValue.SUPPORTED, resolution.answer("wifi_calling_roaming"))
        assertEquals(
            listOf("open.26201.13528695fee7", "open.26201.1f7e1db680d5"),
            resolution.broaderValues("volte").map { it.first.profileId },
        )
    }

    @Test
    fun `a profile listing several network codes matches on each of them`() {
        assertEquals(listOf("open.26201.420d035152c1"), resolve("26206", spn = "TELEKOM.DE", ids = setOf(3)).ids())
        assertTrue(resolve("2620").ids().isEmpty())
    }

    @Test
    fun `profiles go by Android's weight, SPN below GID1 below both (rule round 45)`() {
        assertEquals(
            listOf(
                "open.26201.13528695fee7",
                "open.26201.e7be740147c1",
                "open.26201.fdceda072a5d",
                "open.26201.3e9dccb1fd2a",
            ),
            resolve("26201", spn = "congstar", gid1 = "44FF").ids(),
        )
    }

    @Test
    fun `either the carrier ID or the specific carrier ID matches android_carrier_ids (Tracfone on AT&T)`() {
        val ids = resolve("310410", gid1 = "DEFF0000", ids = setOf(1187, 10000)).ids()
        assertEquals(listOf("open.310410.cdedc056c91a", "open.310280.45bacfa96750"), ids)
        assertEquals(listOf("open.310410.cdedc056c91a"), resolve("310410", gid1 = "DEFF0000", ids = setOf(1187)).ids())
    }

    @Test
    fun `an empty SPN or no carrier ID is a read value, not a hidden one`() {
        val resolution = resolve("26201", spn = "", ids = emptySet())
        assertEquals(listOf("open.26201.13528695fee7"), resolution.ids())
        assertTrue(resolution.notCheckable.none { it.profile.profileId == "open.26201.fb727a260df2" })
    }
}
