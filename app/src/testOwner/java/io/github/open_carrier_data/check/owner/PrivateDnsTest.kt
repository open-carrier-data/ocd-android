package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import io.github.open_carrier_data.check.owner.core.Diagnoses
import io.github.open_carrier_data.check.owner.core.DiagnosisInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD round 42, follow-up fix of 2026-10-30 (the S20 test of 0.4.7, finding 3), on the owner build:
 * strict Private DNS that cannot reach its server over the SIM's cellular network is a phone-side
 * factor beside the verdict, never the carrier's fault; the owner path reads the Private DNS mode,
 * the broken bit and validation on the cellular network, not the Wi-Fi.
 */
class PrivateDnsTest {
    private val factor = "Phone-side: Private DNS (strict) cannot reach its server over this network"

    /** The S20 on 2026-10-30: LIDL's silent block, strict Private DNS (Quad9), the ladder's signature and its D2c failing. */
    private val lidl = DiagnosisInputs(
        cellularUp = true, dataValidated = false, imsRegistered = true, mmsEnded = true,
        privateDnsStrict = true, privateDnsBroken = false, privateDnsFails = true,
        userPlane = UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE,
    )

    @Test
    fun `the LIDL verdict names the account block and strict Private DNS as a phone-side factor`() {
        val d = Diagnoses.of(lidl)
        assertEquals("Mobile data probably blocked by the account", d.line)
        assertEquals(CauseClass.BARRED, d.verdicts.first { it.feature == VerdictFeature.MOBILE_DATA }.cause)
        assertEquals(listOf(factor), d.factors)
        assertEquals("Mobile data probably blocked by the account; $factor", d.summary)
        // Not a wrong APN, and the factor never sets a test aside.
        assertFalse(d.summary!!.contains("access point name"))
        assertFalse(d.notCounted())
        // With a zero balance read the same day, the two-signal form; the factor stays beside it.
        val two = Diagnoses.of(lidl.copy(account = AccountState.BLOCKED, balance = "0,10 EUR"))
        assertEquals("Mobile data blocked by the account: balance 0,10 EUR; $factor", two.summary)
    }

    @Test
    fun `the factor needs strict mode and a failure on the cellular network, and is not repeated as the verdict`() {
        assertTrue(lidl.privateDnsUnreachable)
        assertTrue(lidl.copy(privateDnsFails = null, privateDnsBroken = true).privateDnsUnreachable)
        assertFalse(lidl.copy(privateDnsStrict = false).privateDnsUnreachable)
        assertFalse(lidl.copy(privateDnsFails = false).privateDnsUnreachable)
        assertFalse(lidl.copy(privateDnsFails = null).privateDnsUnreachable)
        assertTrue(Diagnoses.of(lidl.copy(privateDnsStrict = false)).factors.isEmpty())
        // When strict Private DNS is the verdict itself (the path otherwise works), it is said once, in the ruling's words.
        val trap = Diagnoses.of(lidl.copy(userPlane = UserPlaneClass.PRIVATE_DNS_BROKEN, cellularUp = true, dataValidated = false))
        assertEquals("Phone-side: the Private DNS server can't be reached on mobile data", trap.summary)
        // The broken bit alone (no ladder run): the verdict is the phone-side line, no factor.
        val bit = Diagnoses.of(DiagnosisInputs(privateDnsStrict = true, privateDnsBroken = true))
        assertEquals("Phone-side: the Private DNS server can't be reached on mobile data", bit.summary)
    }

    @Test
    fun `a factor with no verdict still shows, and a clean read shows nothing`() {
        // A refusal code beside it: both.
        val coded = Diagnoses.of(DiagnosisInputs(defaultDataCause = 33, privateDnsStrict = true, privateDnsFails = true))
        assertEquals("Refused for mobile data (code 33): not in the plan, or an access point name the network does not know for this SIM; $factor", coded.summary)
        assertNull(Diagnoses.of(DiagnosisInputs()).summary)
    }

    @Test
    fun `W1 through the carrier's own resolver no longer blames the mobile network under strict Private DNS`() {
        // On the S20, 0.4.7's W1 read "mobile network's resolver fails" (DoT over the blocked path). Asked directly,
        // the carrier's resolver gives the address; with the Wi-Fi's answer there is no cause to name.
        val d = Diagnoses.of(lidl.copy(imsRegistered = false, epdgWifi = EpdgLookup.RESOLVES, epdgCellular = EpdgLookup.RESOLVES))
        assertNull(d.verdicts.firstOrNull { it.feature == VerdictFeature.VOWIFI })
    }

    private fun source(path: String) = File("src/owner/java/io/github/open_carrier_data/check/owner/$path").readText()

    @Test
    fun `the owner path reads Private DNS and validation on the cellular network and asks the carrier's resolvers directly`() {
        val passive = source("OwnerPassiveRead.kt")
        assertTrue(passive.contains("privateDnsBroken = cellular?.second?.let { privateDnsBroken(it) } ?: false"))
        assertTrue(passive.contains("privateDnsStrict = cellular?.let { ProbeNet.privateDnsStrict(context, it.first) } ?: false"))
        // Nothing about Private DNS or validation comes from the default network any more.
        assertFalse(passive.contains("activeCaps(context) ?: return false\n        NetworkCapabilities::class.java.getMethod(\"isPrivateDnsBroken\")"))
        assertFalse(Regex("activeCaps\\(context\\)\\?\\.hasCapability\\(NetworkCapabilities\\.NET_CAPABILITY_VALIDATED\\)").containsMatchIn(passive))
        val probe = source("OwnerProbe.kt")
        assertTrue(probe.contains("ProbeNet.run(network, link, ProbeNet.privateDnsStrict(context, network), sim.identity.mccmnc, includeD5 = true, privateDnsBrokenHint = broken)"))
        assertTrue(probe.contains("ProbeNet.epdgLookupDirect(network, ProbeNet.resolvers(link), home)"))
        // The global setting is not the cellular network's mode.
        assertFalse(source("OwnerWork.kt").contains("private_dns_mode"))
        // The logged line carries the phone-side factors (round 43 adds the wait clause after the verdict, before them).
        assertTrue(source("OwnerWork.kt").contains("val line = diagnosis.shown(now) ?: continue"))
    }
}
