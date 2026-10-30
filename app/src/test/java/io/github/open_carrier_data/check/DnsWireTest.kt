package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.DnsWire
import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.PrivateDnsMode
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbeResults
import io.github.open_carrier_data.check.core.RedirectHost
import io.github.open_carrier_data.check.core.UserPlaneClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OCD round 42, follow-up fix of 2026-10-30 (the S20 test of 0.4.7, finding 3): with strict Private
 * DNS, Android's DnsResolver asks only the Private DNS server over TLS, so the ladder's D2a and W1's
 * cellular lookup measured DNS over TLS, not the carrier's resolver they name. They now ask the
 * network's own DNS servers directly over UDP 53; strict Private DNS is its own step (D2c) and a
 * phone-side factor, never the carrier's fault.
 */
class DnsWireTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A reply to [query]: the header with [rcode] and [answers] answer records, the question echoed, then [records]. */
    private fun reply(query: ByteArray, rcode: Int, answers: Int, records: ByteArray, flags: Int = 0x8180): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(bytes(flags shr 8 and 0xff, (flags and 0xf0) or rcode, 0, 1, 0, answers, 0, 0, 0, 0))
        out.write(query, 12, query.size - 12)
        out.write(records)
        return out.toByteArray()
    }

    /** One answer record whose name is a pointer to the question (offset 12). */
    private fun record(type: Int, data: ByteArray) = bytes(0xc0, 12, 0, type, 0, 1, 0, 0, 0, 60, 0, data.size) + data

    @Test
    fun `a query is a standard recursive question for the name, with the id it was given`() {
        val q = DnsWire.query(0x4f43, "connectivitycheck.gstatic.com", DnsWire.TYPE_A)
        assertArrayEquals(bytes(0x4f, 0x43, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0), q.copyOfRange(0, 12))
        assertEquals(17, q[12].toInt())
        assertEquals("connectivitycheck", String(q, 13, 17))
        assertArrayEquals(bytes(0, 0, 1, 0, 1), q.copyOfRange(q.size - 5, q.size))
        assertArrayEquals(bytes(0, 28, 0, 1), DnsWire.query(1, "epdg.epc.mnc002.mcc262.pub.3gppnetwork.org", DnsWire.TYPE_AAAA).let { it.copyOfRange(it.size - 4, it.size) })
    }

    @Test
    fun `a reply gives its code and only the addresses of the asked type`() {
        val q = DnsWire.query(0x1234, "epdg.epc.mnc002.mcc262.pub.3gppnetwork.org", DnsWire.TYPE_A)
        // A CNAME first (skipped), then two A records.
        val cname = bytes(0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0, 12)
        val r = reply(q, 0, 3, cname + record(1, bytes(10, 0, 0, 1)) + record(1, bytes(10, 0, 0, 2)))
        val parsed = DnsWire.parse(r, r.size, 0x1234, DnsWire.TYPE_A)!!
        assertEquals(DnsWire.RCODE_NOERROR, parsed.rcode)
        assertEquals(2, parsed.addresses.size)
        assertTrue(parsed.answered)
        assertEquals(EpdgLookup.RESOLVES, EpdgLookup.of(parsed))
        // An AAAA record is not an A answer.
        val v6 = reply(q, 0, 1, record(28, ByteArray(16)))
        assertEquals(0, DnsWire.parse(v6, v6.size, 0x1234, DnsWire.TYPE_A)!!.addresses.size)
        assertEquals(1, DnsWire.parse(v6, v6.size, 0x1234, DnsWire.TYPE_AAAA)!!.addresses.size)
        // NXDOMAIN and an empty NOERROR: answered, no address.
        val nx = reply(q, 3, 0, ByteArray(0))
        assertEquals(EpdgLookup.NO_ADDRESS, EpdgLookup.of(DnsWire.parse(nx, nx.size, 0x1234, DnsWire.TYPE_A)))
        // SERVFAIL and no reply at all: fails.
        val servfail = reply(q, 2, 0, ByteArray(0))
        assertEquals(EpdgLookup.FAILS, EpdgLookup.of(DnsWire.parse(servfail, servfail.size, 0x1234, DnsWire.TYPE_A)))
        assertEquals(EpdgLookup.FAILS, EpdgLookup.of(null as DnsWire.Reply?))
    }

    @Test
    fun `a reply to another query, a question, or a cut message is not taken as an answer`() {
        val q = DnsWire.query(0x1234, "connectivitycheck.gstatic.com", DnsWire.TYPE_A)
        val r = reply(q, 0, 1, record(1, bytes(10, 0, 0, 1)))
        assertNull("another id", DnsWire.parse(r, r.size, 0x1235, DnsWire.TYPE_A))
        assertNull("not a response", DnsWire.parse(q, q.size, 0x1234, DnsWire.TYPE_A))
        assertNull("too short", DnsWire.parse(r, 11, 0x1234, DnsWire.TYPE_A))
        // Cut inside the answer: the code stays, no address is made up.
        assertEquals(0, DnsWire.parse(r, r.size - 2, 0x1234, DnsWire.TYPE_A)!!.addresses.size)
    }

    @Test
    fun `strict mode is the network's server name, or the phone's hostname setting on a network Private DNS applies to`() {
        assertTrue(PrivateDnsMode.strict(serverName = "dns.example", settingMode = null, appliesToNetwork = false))
        // The S20 on 2026-10-30: the background cellular network's LinkProperties named no server, the setting said hostname.
        assertTrue(PrivateDnsMode.strict(serverName = null, settingMode = "hostname", appliesToNetwork = true))
        assertFalse("a restricted network (IMS) gets no Private DNS", PrivateDnsMode.strict(null, "hostname", appliesToNetwork = false))
        assertFalse("opportunistic is not strict", PrivateDnsMode.strict(null, "opportunistic", true))
        assertFalse(PrivateDnsMode.strict(null, "off", true))
        assertFalse(PrivateDnsMode.strict(null, null, true))
        assertFalse(PrivateDnsMode.strict("", "off", true))
    }

    @Test
    fun `several answers combine - any address resolves, an answer without one is no address, nothing fails`() {
        assertEquals(EpdgLookup.RESOLVES, EpdgLookup.combine(listOf(EpdgLookup.FAILS, EpdgLookup.RESOLVES)))
        assertEquals(EpdgLookup.NO_ADDRESS, EpdgLookup.combine(listOf(EpdgLookup.FAILS, EpdgLookup.NO_ADDRESS)))
        assertEquals(EpdgLookup.FAILS, EpdgLookup.combine(listOf(EpdgLookup.FAILS)))
        assertEquals("no resolver listed", EpdgLookup.FAILS, EpdgLookup.combine(emptyList()))
    }

    /** The LIDL block with strict Private DNS: the carrier's resolvers answer directly, DoT and every TCP connection fail. */
    private val lidlStrict = ProbeResults(
        loopbackOk = true, carrierDnsOk = true, carrierDnsAnswers = listOf(true, true), publicDnsOk = true,
        privateDnsOn = true, privateDnsOk = false,
        ipv4LiteralOk = false, ipv6LiteralOk = false, hostOk = false, http204Clean = false, redirect = RedirectHost.NONE,
    )

    @Test
    fun `strict Private DNS failing beside the silent block is a phone-side factor, the block stays the class`() {
        assertEquals(UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE, ProbeLadder.classify(lidlStrict))
        assertTrue(ProbeLadder.privateDnsFactor(lidlStrict))
        assertEquals("Private DNS (strict) cannot reach its server over this network", ProbeLadder.PRIVATE_DNS_FACTOR)
        assertTrue(ProbeLadder.steps(lidlStrict), ProbeLadder.steps(lidlStrict).contains("D2a carrier resolvers (UDP 53, direct): 2 of 2 answered"))
        assertTrue(ProbeLadder.steps(lidlStrict).contains("D2c Private DNS (strict) fails"))
        // With the path otherwise working, strict Private DNS is the class itself (phone-side), and no extra factor.
        val trap = lidlStrict.copy(ipv4LiteralOk = true, ipv6LiteralOk = true, hostOk = true, http204Clean = true)
        assertEquals(UserPlaneClass.PRIVATE_DNS_BROKEN, ProbeLadder.classify(trap))
        assertFalse(ProbeLadder.privateDnsFactor(trap))
        // Not strict, or strict and answering: nothing.
        assertFalse(ProbeLadder.privateDnsFactor(lidlStrict.copy(privateDnsOn = false, privateDnsOk = null)))
        assertFalse(ProbeLadder.privateDnsFactor(lidlStrict.copy(privateDnsOk = true)))
        // A carrier portal redirect stays an account signal even when strict Private DNS fails behind it.
        assertEquals(UserPlaneClass.PORTAL_REDIRECT, ProbeLadder.classify(lidlStrict.copy(redirect = RedirectHost.CARRIER_PORTAL)))
        // Some resolvers silent, one answering: still answered; the step names the count, never an address.
        val one = lidlStrict.copy(carrierDnsAnswers = listOf(false, true))
        assertTrue(ProbeLadder.steps(one).contains("1 of 2 answered"))
        assertTrue(ProbeLadder.steps(ProbeResults(carrierDnsOk = null)).contains("D2a carrier resolver not run (the network lists none)"))
    }

    private val main = File("src/main/java/io/github/open_carrier_data/check")

    @Test
    fun `D2a and W1's cellular side ask the network's own servers directly, Private DNS has its own uncached step`() {
        val net = File(main, "core/ProbeNet.kt").readText()
        // D2a: each of the network's own DNS servers over UDP 53 on a socket bound to the network.
        assertTrue(net.contains("val carrier = resolvers(link).map { udp53(network, it, ProbeTargets.DNS_NAME, DnsWire.TYPE_A) }"))
        assertTrue(net.contains("link?.dnsServers"))
        assertTrue(net.contains("network.bindSocket(socket)"))
        assertTrue(net.contains("socket.send(DatagramPacket(query, query.size, resolver, 53))"))
        // D2c: strict only, uncached, and the broken bit counts.
        assertTrue(net.contains("val privateDnsOk = if (strict) privateDnsBrokenHint != true && privateDnsAnswers(network, ProbeTargets.DNS_NAME) else null"))
        assertTrue(net.contains("DnsResolver.getInstance().query(network, name, DnsResolver.FLAG_NO_CACHE_LOOKUP, direct, null, cb)"))
        // Strict mode on the network itself: its LinkProperties or the phone's setting on a network Android applies it to.
        assertTrue(net.contains("serverName = runCatching { link?.privateDnsServerName }.getOrNull()"))
        assertTrue(net.contains("val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()"))
        // W1's cellular lookup: the same direct path, an A and, without an address, an AAAA query.
        assertTrue(net.contains("suspend fun epdgLookupDirect(network: Network, servers: List<InetAddress>, home: HomeNetwork): EpdgLookup"))
        // The read-only tap reads the network's own LinkProperties, not the global setting.
        val vm = File(main, "ui/CheckViewModel.kt").readText()
        assertTrue(vm.contains("val link = runCatching { cm.getLinkProperties(network) }.getOrNull()"))
        assertFalse(vm.contains("private_dns_mode"))
        assertTrue(vm.contains("includeD5 = false"))
        // The codec opens nothing.
        assertFalse(Regex("DatagramSocket|Socket\\(|openConnection|DnsResolver\\.").containsMatchIn(File(main, "core/DnsWire.kt").readText()))
    }
}
