package io.github.open_carrier_data.check.core

/*
 * OCD rule round 42, item 2 (the follow-up fix after the S20 test of 0.4.7): a minimal DNS message
 * codec, pure, for the probe ladder's D2 steps and W1's cellular lookup. Android's DnsResolver
 * follows the phone's Private DNS: in strict mode it asks only the Private DNS server, over TLS, so a
 * step that named "the carrier's resolver" measured DNS over TLS instead. The runner ([ProbeNet])
 * sends these messages itself, over UDP port 53 on a socket bound to the cellular network, to the
 * network's own DNS servers (its LinkProperties), so nothing comes from a cache and Private DNS is
 * not in the path.
 *
 * It builds one standard recursive query and reads one reply: the response code and the addresses
 * of the asked type, counted by the caller and used only in memory (D3 and D4 connect to one), never
 * stored or shown.
 */
object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3
    private const val CLASS_IN = 1

    /** A standard recursive query (RD set) for [name] of [type], with the message id [id] (16 bits). */
    fun query(id: Int, name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8 and 0xff).toByte(), (id and 0xff).toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, type.toByte(), 0x00, CLASS_IN.toByte()))
        return out.toByteArray()
    }

    /** One reply: its response code, and the raw addresses (4 or 16 bytes) of the asked type, kept only in memory. */
    class Reply(val rcode: Int, val addresses: List<ByteArray>) {
        /** NOERROR or NXDOMAIN: the resolver answered, with or without an address. */
        val answered: Boolean get() = rcode == RCODE_NOERROR || rcode == RCODE_NXDOMAIN
    }

    /**
     * Reads the first [length] bytes of [message] as the reply to the query [id] of [type]. Null when it
     * is not a reply to that query (too short, another id, not a response); a reply whose answer
     * section is cut short keeps the addresses read before the cut.
     */
    fun parse(message: ByteArray, length: Int, id: Int, type: Int): Reply? {
        val n = minOf(length, message.size)
        if (n < 12) return null
        fun u8(i: Int) = message[i].toInt() and 0xff
        fun u16(i: Int) = u8(i) shl 8 or u8(i + 1)
        if (u16(0) != id) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null // QR: a response
        val rcode = flags and 0x0f
        val questions = u16(4)
        val answers = u16(6)
        var pos = 12
        repeat(questions) {
            pos = skipName(message, n, pos) ?: return Reply(rcode, emptyList())
            pos += 4
        }
        val out = mutableListOf<ByteArray>()
        repeat(answers) {
            pos = skipName(message, n, pos) ?: return Reply(rcode, out)
            if (pos + 10 > n) return Reply(rcode, out)
            val rrType = u16(pos)
            val rrClass = u16(pos + 2)
            val size = u16(pos + 8)
            val data = pos + 10
            if (data + size > n) return Reply(rcode, out)
            val want = when (type) {
                TYPE_A -> 4
                TYPE_AAAA -> 16
                else -> -1
            }
            if (rrType == type && rrClass == CLASS_IN && size == want) out += message.copyOfRange(data, data + size)
            pos = data + size
        }
        return Reply(rcode, out)
    }

    /** The position after the name at [start] (labels, ending in a zero byte or a compression pointer); null when cut short. */
    private fun skipName(message: ByteArray, n: Int, start: Int): Int? {
        var pos = start
        var labels = 0
        while (pos < n && labels++ < 128) {
            val len = message[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return if (pos + 2 <= n) pos + 2 else null
                len and 0xc0 != 0 -> return null
                else -> pos += 1 + len
            }
        }
        return null
    }
}
