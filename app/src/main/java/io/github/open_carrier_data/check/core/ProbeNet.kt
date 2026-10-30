package io.github.open_carrier_data.check.core

import android.net.DnsResolver
import android.net.Network
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
 * It keeps no address: a resolve or a connect is a boolean, a redirect a [RedirectHost] class, a
 * carrier-page fetch only "answered or not". Every target is from [ProbeTargets], fixed in the
 * app, never from a reply. D4 follows no redirect; D5 keeps only the status class of the body it
 * never reads past the first line. W1 ([epdgLookup], owner and rom only) asks one resolver for the
 * SIM's standard ePDG name and keeps only "resolves", "no address" or "fails". It needs an Android
 * [Network], so it has no JVM test; the bounds ([ProbePolicy], [EpdgPolicy]) and the classifiers do.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object ProbeNet {
    private const val DNS_MS = 4_000L
    private const val TCP_MS = 6_000
    private const val HTTP_MS = 8_000
    private val direct = Executor { it.run() }

    /**
     * Runs D1-D4 on [network] for the home network [mccmnc], and D5 only when [includeD5] (owner and
     * rom). [privateDnsBrokenHint] is the owner build's authoritative NetworkCapabilities bit; null on
     * a build that cannot read it, where D2's private-DNS reading rests on the resolver alone.
     */
    suspend fun run(
        network: Network,
        mccmnc: String?,
        includeD5: Boolean,
        privateDnsOn: Boolean,
        privateDnsBrokenHint: Boolean?,
    ): ProbeResults = withContext(Dispatchers.IO) {
        // D1: the phone's own stack is not firewalled off (a local loopback connect works).
        val loopbackOk = loopbackOk()
        // D2: the network's own resolver, a public resolver over UDP 53, and the private-DNS reading.
        val carrierDnsOk = resolveOnNetwork(network, ProbeTargets.DNS_NAME)
        val publicDnsOk = publicResolver(network, ProbeTargets.DNS_NAME)
        val privateDnsOk = when {
            !privateDnsOn -> null
            privateDnsBrokenHint != null -> !privateDnsBrokenHint
            else -> carrierDnsOk
        }
        // D3: an IPv4 literal, an IPv6 literal (DNS bypassed), and a host name (DNS + TCP).
        val ipv4LiteralOk = tcpLiteral(network, ProbeTargets.IPV4_LITERAL)
        val ipv6LiteralOk = tcpLiteral(network, ProbeTargets.IPV6_LITERAL)
        val hostOk = tcpHost(network, ProbeTargets.DNS_NAME)
        // D4: the plain-HTTP 204 check, redirects recorded but never followed.
        val d4 = http204(network, mccmnc)
        // D5: the carrier self-care page, owner and rom only, status class kept, body never read.
        val carrierPageOk = if (includeD5) carrierPage(network, mccmnc) else null
        ProbeResults(
            loopbackOk = loopbackOk,
            carrierDnsOk = carrierDnsOk,
            publicDnsOk = publicDnsOk,
            privateDnsOk = privateDnsOk,
            privateDnsOn = privateDnsOn,
            ipv4LiteralOk = ipv4LiteralOk,
            ipv6LiteralOk = ipv6LiteralOk,
            hostOk = hostOk,
            http204Clean = d4.first,
            redirect = d4.second,
            carrierPageOk = carrierPageOk,
        )
    }

    /**
     * W1 (owner and rom only, from OwnerProbe): [home]'s standard ePDG name, built from the SIM's
     * MCC and MNC by the fixed 3GPP template ([HomeNetwork.epdgFqdn]), looked up once through
     * [network]'s own resolvers: Android's per-network resolver, with the Private DNS the phone set,
     * the path the phone's own IWLAN lookup takes. Only the class is kept ([EpdgLookup]); the
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

    private fun loopbackOk(): Boolean = runCatching {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            java.net.Socket().use { client ->
                client.connect(InetSocketAddress(server.inetAddress, server.localPort), 1_000)
                true
            }
        }
    }.getOrDefault(false)

    /** D2a/D3c name resolution through the network's own resolvers (honours a configured Private DNS). */
    private suspend fun resolveOnNetwork(network: Network, name: String): Boolean {
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
            DnsResolver.getInstance().query(network, name, DnsResolver.FLAG_EMPTY, direct, null, cb)
            withTimeoutOrNull(DNS_MS) { done.await() } ?: false
        }.getOrDefault(false)
    }

    /** D2b: a minimal A query to a fixed public resolver over UDP 53, bound to the network. */
    private fun publicResolver(network: Network, name: String): Boolean = runCatching {
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = 3_000
            val query = dnsQuery(name)
            val resolver = InetAddress.getByName(ProbeTargets.PUBLIC_RESOLVER) // a literal IP: no DNS lookup
            socket.send(DatagramPacket(query, query.size, resolver, 53))
            val buffer = ByteArray(512)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            // The first two bytes are the query id we set; an answer count above zero is a real answer.
            reply.length >= 8 && buffer[0] == query[0] && buffer[1] == query[1] &&
                ((buffer[6].toInt() and 0xff) shl 8 or (buffer[7].toInt() and 0xff)) > 0
        }
    }.getOrDefault(false)

    /** A standard-recursion A query for [name], id 0x4f43 ("OC"). */
    private fun dnsQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x4f, 0x43, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // QTYPE A, QCLASS IN
        return out.toByteArray()
    }

    /** D3a/D3b: a TCP connect to an IP literal on 443, DNS bypassed. Null when the literal is unusable. */
    private fun tcpLiteral(network: Network, literal: String): Boolean? = runCatching {
        val address = InetAddress.getByName(literal) // a literal: no DNS
        connect(network, address, ProbeTargets.TLS_PORT)
    }.getOrNull()

    /** D3c: resolve the host on the network, then TCP connect on 443. */
    private fun tcpHost(network: Network, host: String): Boolean = runCatching {
        val address = network.getAllByName(host).firstOrNull() ?: return false
        connect(network, address, ProbeTargets.TLS_PORT)
    }.getOrDefault(false)

    private fun connect(network: Network, address: InetAddress, port: Int): Boolean = runCatching {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TCP_MS)
            true
        }
    }.getOrDefault(false)

    /** D4: one plain-HTTP GET of the fixed 204 path; the status and the redirect class, no redirect followed. */
    private fun http204(network: Network, mccmnc: String?): Pair<Boolean?, RedirectHost> = runCatching {
        val address = network.getAllByName(ProbeTargets.HTTP204_HOST).firstOrNull()
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
