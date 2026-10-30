package io.github.open_carrier_data.check.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor

/**
 * OCD rule round 42, item 2: the socket work of the mobile-data ladder, every socket and every
 * DnsResolver query bound to the cellular [Network] the caller hands in, never the default
 * network. It fills a [ProbeResults] that [ProbeLadder.classify] turns into a [UserPlaneClass].
 *
 * The carrier-resolver steps (D2a, and W1's cellular lookup in [epdgLookupDirect]) ask the network's
 * own DNS servers, from its [LinkProperties], directly: one UDP port-53 query per server on a socket
 * bound to the network ([DnsWire]), so no cache answers and the phone's Private DNS is not in the
 * path (a follow-up fix after the S20 test of 0.4.7: with strict Private DNS, DnsResolver asks only
 * the Private DNS server over TLS, so those steps had measured DNS over TLS). Private DNS has its own
 * step, D2c, run only in strict mode: one uncached DnsResolver query, which in strict mode goes only
 * to the Private DNS server.
 *
 * It keeps no address: a resolver is kept only as whether it answered, a connect as a boolean, a
 * redirect as a [RedirectHost] class, a carrier-page fetch only "answered or not". Every target is
 * from [ProbeTargets], fixed in the app, never from a reply; D3c and D4 connect to the address the
 * carrier's resolver gave for that fixed name, used in memory only. D4 follows no redirect; D5 keeps
 * only the status class of the body it never reads past the first line. W1 (owner and rom only)
 * keeps only "resolves", "no address" or "fails". It needs an Android [Network], so it has no JVM
 * test; the bounds ([ProbePolicy], [EpdgPolicy]), the codec ([DnsWire]) and the classifiers do.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object ProbeNet {
    private const val DNS_MS = 4_000L
    private const val UDP_MS = 3_000
    private const val TCP_MS = 6_000
    private const val HTTP_MS = 8_000

    /** At most this many of the network's DNS servers are asked. */
    private const val MAX_RESOLVERS = 4
    private val direct = Executor { it.run() }
    private val ids = java.security.SecureRandom()

    /**
     * Whether Private DNS is in strict mode on [network] ([PrivateDnsMode.strict]): its LinkProperties name
     * a Private DNS server, or the phone's Private DNS setting is a hostname and [network] is one Android
     * applies Private DNS to. Read on that network, never the default one; the setting is the phone's
     * own, the same for every such network, and only its mode is read, never the hostname.
     */
    fun privateDnsStrict(context: Context, network: Network): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
            ?: Settings.Global.getString(context.contentResolver, "private_dns_default_mode")
        PrivateDnsMode.strict(
            serverName = runCatching { link?.privateDnsServerName }.getOrNull(),
            settingMode = mode,
            appliesToNetwork = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED),
        )
    }.getOrDefault(false)

    /** The network's own DNS servers, from its LinkProperties: used to send to, never stored or shown. */
    fun resolvers(link: LinkProperties?): List<InetAddress> = runCatching { link?.dnsServers.orEmpty().take(MAX_RESOLVERS) }.getOrDefault(emptyList())

    /**
     * Runs D1-D4 on [network] for the home network [mccmnc], and D5 only when [includeD5] (owner and
     * rom). [link] is the network's LinkProperties (its DNS servers); [strict] whether its Private DNS
     * is in strict mode ([privateDnsStrict]). [privateDnsBrokenHint] is the owner build's
     * NetworkCapabilities bit, read on the cellular network; null on a build that cannot read it.
     */
    suspend fun run(
        network: Network,
        link: LinkProperties?,
        strict: Boolean,
        mccmnc: String?,
        includeD5: Boolean,
        privateDnsBrokenHint: Boolean?,
    ): ProbeResults = withContext(Dispatchers.IO) {
        // D1: the phone's own stack is not firewalled off (a local loopback connect works).
        val loopbackOk = loopbackOk()
        // D2a: each of the network's own resolvers, asked directly over UDP 53 (no cache, no Private DNS).
        val carrier = resolvers(link).map { udp53(network, it, ProbeTargets.DNS_NAME, DnsWire.TYPE_A) }
        val carrierAnswers = carrier.map { (it?.addresses?.size ?: 0) > 0 }
        val carrierDnsOk = if (carrier.isEmpty()) null else carrierAnswers.any { it }
        // The address the carrier's resolver gave for the fixed name, for D3c and D4 (in memory only).
        val carrierAddress = carrier.firstNotNullOfOrNull { r -> r?.addresses?.firstOrNull()?.let { runCatching { InetAddress.getByAddress(it) }.getOrNull() } }
        // D2b: a public resolver over UDP 53.
        val publicDnsOk = publicResolver(network, ProbeTargets.DNS_NAME)
        // D2c: in strict mode only, the Private DNS server through Android's resolver, uncached.
        val privateDnsOk = if (strict) privateDnsBrokenHint != true && privateDnsAnswers(network, ProbeTargets.DNS_NAME) else null
        // D3: an IPv4 literal, an IPv6 literal (DNS bypassed), and a host name (the carrier's answer + TCP).
        val ipv4LiteralOk = tcpLiteral(network, ProbeTargets.IPV4_LITERAL)
        val ipv6LiteralOk = tcpLiteral(network, ProbeTargets.IPV6_LITERAL)
        val hostOk = tcpHost(network, ProbeTargets.DNS_NAME, carrierAddress)
        // D4: the plain-HTTP 204 check, redirects recorded but never followed.
        val d4 = http204(network, mccmnc, carrierAddress)
        // D5: the carrier self-care page, owner and rom only, status class kept, body never read.
        val carrierPageOk = if (includeD5) carrierPage(network, mccmnc) else null
        ProbeResults(
            loopbackOk = loopbackOk,
            carrierDnsOk = carrierDnsOk,
            carrierDnsAnswers = carrierAnswers,
            publicDnsOk = publicDnsOk,
            privateDnsOk = privateDnsOk,
            privateDnsOn = strict,
            ipv4LiteralOk = ipv4LiteralOk,
            ipv6LiteralOk = ipv6LiteralOk,
            hostOk = hostOk,
            http204Clean = d4.first,
            redirect = d4.second,
            carrierPageOk = carrierPageOk,
        )
    }

    /**
     * W1 through the Wi-Fi network (owner and rom only, from OwnerProbe): [home]'s standard ePDG name,
     * built from the SIM's MCC and MNC by the fixed 3GPP template ([HomeNetwork.epdgFqdn]), looked up
     * once through [network]'s resolver as Android has it (with the Private DNS the phone set), the
     * path the phone's own lookup takes over that Wi-Fi. Only the class is kept ([EpdgLookup]); the
     * addresses are counted and dropped, never stored or shown.
     */
    suspend fun epdgLookup(network: Network, home: HomeNetwork): EpdgLookup = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<EpdgLookup>()
        val cb = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                if (!done.isCompleted) done.complete(EpdgLookup.of(answer.size, rcode))
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (!done.isCompleted) done.complete(EpdgLookup.FAILS)
            }
        }
        runCatching {
            DnsResolver.getInstance().query(network, home.epdgFqdn, DnsResolver.FLAG_EMPTY, direct, null, cb)
            withTimeoutOrNull(DNS_MS) { done.await() } ?: EpdgLookup.FAILS
        }.getOrDefault(EpdgLookup.FAILS)
    }

    /**
     * W1 through the cellular network (owner and rom only): [home]'s standard ePDG name asked of the
     * network's own DNS servers ([resolvers]) directly, over UDP 53 on a socket bound to [network], an
     * A query and, without an address, an AAAA query. The phone's Private DNS is not in the path, so
     * a strict Private DNS that cannot reach its server never reads as the carrier's resolver failing.
     * Kept only as a class ([EpdgLookup.combine]); no address is kept.
     */
    suspend fun epdgLookupDirect(network: Network, servers: List<InetAddress>, home: HomeNetwork): EpdgLookup = withContext(Dispatchers.IO) {
        val answers = servers.take(MAX_RESOLVERS).map { server ->
            val a = udp53(network, server, home.epdgFqdn, DnsWire.TYPE_A)
            val one = EpdgLookup.of(a)
            if (one == EpdgLookup.NO_ADDRESS && a?.rcode == DnsWire.RCODE_NOERROR) {
                EpdgLookup.combine(listOf(one, EpdgLookup.of(udp53(network, server, home.epdgFqdn, DnsWire.TYPE_AAAA))))
            } else {
                one
            }
        }
        EpdgLookup.combine(answers)
    }

    private fun loopbackOk(): Boolean = runCatching {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            java.net.Socket().use { client ->
                client.connect(InetSocketAddress(server.inetAddress, server.localPort), 1_000)
                true
            }
        }
    }.getOrDefault(false)

    /**
     * D2c: name resolution through Android's resolver on the network, which in strict mode asks only the
     * Private DNS server; never answered from the cache (FLAG_NO_CACHE_LOOKUP).
     */
    private suspend fun privateDnsAnswers(network: Network, name: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val cb = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                if (!done.isCompleted) done.complete(answer.isNotEmpty())
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (!done.isCompleted) done.complete(false)
            }
        }
        return runCatching {
            DnsResolver.getInstance().query(network, name, DnsResolver.FLAG_NO_CACHE_LOOKUP, direct, null, cb)
            withTimeoutOrNull(DNS_MS) { done.await() } ?: false
        }.getOrDefault(false)
    }

    /** D2b: an A query to a fixed public resolver over UDP 53, bound to the network; an address back is a real answer. */
    private fun publicResolver(network: Network, name: String): Boolean = runCatching {
        val resolver = InetAddress.getByName(ProbeTargets.PUBLIC_RESOLVER) // a literal IP: no DNS lookup
        (udp53(network, resolver, name, DnsWire.TYPE_A)?.addresses?.size ?: 0) > 0
    }.getOrDefault(false)

    /**
     * One query for [name] of [type] to [resolver] over UDP port 53, on a socket bound to [network]: no
     * cache, no Private DNS. The reply to this query's random id, or null when none came in time.
     */
    private fun udp53(network: Network, resolver: InetAddress, name: String, type: Int): DnsWire.Reply? = runCatching {
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = UDP_MS
            val id = ids.nextInt(0x10000)
            val query = DnsWire.query(id, name, type)
            socket.send(DatagramPacket(query, query.size, resolver, 53))
            val buffer = ByteArray(1232)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            DnsWire.parse(buffer, reply.length, id, type)
        }
    }.getOrNull()

    /** D3a/D3b: a TCP connect to an IP literal on 443, DNS bypassed. Null when the literal is unusable. */
    private fun tcpLiteral(network: Network, literal: String): Boolean? = runCatching {
        val address = InetAddress.getByName(literal) // a literal: no DNS
        connect(network, address, ProbeTargets.TLS_PORT)
    }.getOrNull()

    /** D3c: the host's address from the carrier's resolver (else Android's resolver on the network), then TCP connect on 443. */
    private fun tcpHost(network: Network, host: String, carrierAddress: InetAddress?): Boolean = runCatching {
        val address = carrierAddress ?: network.getAllByName(host).firstOrNull() ?: return false
        connect(network, address, ProbeTargets.TLS_PORT)
    }.getOrDefault(false)

    private fun connect(network: Network, address: InetAddress, port: Int): Boolean = runCatching {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TCP_MS)
            true
        }
    }.getOrDefault(false)

    /** D4: one plain-HTTP GET of the fixed 204 path; the status and the redirect class, no redirect followed. */
    private fun http204(network: Network, mccmnc: String?, carrierAddress: InetAddress?): Pair<Boolean?, RedirectHost> = runCatching {
        // DNS_NAME and HTTP204_HOST are the same fixed host: the carrier resolver's answer first.
        val address = carrierAddress?.takeIf { ProbeTargets.HTTP204_HOST == ProbeTargets.DNS_NAME }
            ?: network.getAllByName(ProbeTargets.HTTP204_HOST).firstOrNull()
            ?: return null to RedirectHost.NONE
        network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = HTTP_MS
            socket.connect(InetSocketAddress(address, ProbeTargets.HTTP_PORT), HTTP_MS)
            socket.getOutputStream().apply {
                write(
                    ("GET ${ProbeTargets.HTTP204_PATH} HTTP/1.1\r\nHost: ${ProbeTargets.HTTP204_HOST}\r\n" +
                        "User-Agent: OCD-Check\r\nConnection: close\r\n\r\n").toByteArray()
                )
                flush()
            }
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val status = statusCode(reader.readLine())
            var locationHost: String? = null
            if (status != null && status in 300..399) {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Location:", ignoreCase = true)) {
                        locationHost = hostOfUrl(line.substringAfter(':').trim())
                        break
                    }
                }
            }
            val clean = status == 204
            clean to ProbeTargets.redirectHost(mccmnc, locationHost)
        }
    }.getOrDefault(null to RedirectHost.NONE)

    /** D5: whether the fixed carrier self-care page answered with any HTTP status line. The body is never read. */
    private fun carrierPage(network: Network, mccmnc: String?): Boolean? {
        val url = ProbeTargets.carrierPage(mccmnc) ?: return null
        val host = hostOfUrl(url) ?: return null
        return runCatching {
            val address = network.getAllByName(host).firstOrNull() ?: return false
            network.socketFactory.createSocket().use { socket ->
                socket.soTimeout = HTTP_MS
                socket.connect(InetSocketAddress(address, ProbeTargets.HTTP_PORT), HTTP_MS)
                socket.getOutputStream().apply {
                    write("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: OCD-Check\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                statusCode(socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()) != null
            }
        }.getOrDefault(false)
    }

    private fun statusCode(firstLine: String?): Int? =
        Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})\\b").find(firstLine.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** The host of a URL or authority, lower-cased, no scheme or port; null when none. */
    private fun hostOfUrl(raw: String): String? = runCatching {
        val s = raw.trim()
        val afterScheme = if ("://" in s) s.substringAfter("://") else s
        val authority = afterScheme.substringBefore('/')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
        else authority.substringBefore(':')
        host.lowercase().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
