/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * OCD's `apns-conf.xml`, read strictly by line (spec 2.9). The generator keeps three
 * invariants, and this reader relies on them:
 * 1. the root is `<apns version="N">`, with no DOCTYPE and no entity declarations;
 * 2. the header comment carries `    data_digest: sha256:<64 hex>`;
 * 3. every `<apn …/>` element is alone on its own line, and no other element appears.
 * Anything else (a second element on a line, an unknown entity, a duplicate attribute, text
 * outside comments) is [Refused] `apn_index_mismatch`, or `apn_version` for the root.
 */
object ApnXml {
    data class Row(val line: Int, val ordinal: Int, val attrs: Map<String, String>, val text: String) {
        val rowKey: String get() = rowKey(attrs)
        val types: List<String> get() = attrs["type"].orEmpty().split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }
        val numeric: String get() = if (attrs["mcc"] != null && attrs["mnc"] != null) attrs.getValue("mcc") + attrs.getValue("mnc") else ""

        /** TelephonyProvider reads an empty type as every type (ApnSetting.getApnTypesBitmaskFromString). */
        fun serves(type: String): Boolean = types.isEmpty() || "*" in types || type in types
    }

    data class Doc(val version: Int, val dataDigest: String?, val rows: List<Row>, val lines: List<String>)

    /** `a:` + 16 hex of SHA-256 over the attributes, sorted by name, as `name=value` lines (2.9). */
    fun rowKey(attrs: Map<String, String>): String =
        "a:" + Files.sha256(attrs.entries.sortedWith { a, b -> compareBytes(a.key, b.key) }.joinToString("\n") { "${it.key}=${it.value}" }).take(16)

    private fun bad(line: Int, what: String): Nothing = refuse("apn_index_mismatch", "apns-conf.xml line $line: $what")

    fun parse(bytes: ByteArray): Doc = parse(try {
        Json.decodeUtf8(bytes)
    } catch (e: Json.ParseException) {
        refuse("apn_index_mismatch", "apns-conf.xml: ${e.message}")
    })

    fun parse(text: String): Doc {
        val lines = text.split('\n')
        var version: Int? = null
        var digest: String? = null
        var inComment = false
        var closed = false
        val rows = ArrayList<Row>()
        for ((i, raw) in lines.withIndex()) {
            val n = i + 1
            val line = raw.removeSuffix("\r")
            val t = line.trim()
            if (inComment) {
                if (version == null) Regex("^data_digest:\\s*(sha256:[0-9a-f]{64})\\s*$").find(t)?.let { digest = digest ?: it.groupValues[1] }
                if (t.contains("-->")) {
                    if (!t.endsWith("-->") || t.indexOf("-->") != t.length - 3) bad(n, "text after a comment")
                    inComment = false
                }
                continue
            }
            when {
                t.isEmpty() -> Unit
                t.startsWith("<?xml") -> if (n != 1 || !t.endsWith("?>")) bad(n, "an XML declaration not on line 1")
                t.startsWith("<!--") -> {
                    val rest = t.removePrefix("<!--")
                    if (rest.contains("--") && !rest.endsWith("-->")) bad(n, "text after a comment")
                    if (!rest.endsWith("-->")) inComment = true
                    if (version == null) Regex("data_digest:\\s*(sha256:[0-9a-f]{64})").find(rest)?.let { digest = digest ?: it.groupValues[1] }
                }
                t.startsWith("<!") -> bad(n, "a DOCTYPE or declaration")
                t.startsWith("<apns") -> {
                    if (version != null || closed) bad(n, "a second root")
                    val m = Regex("^<apns\\s+version=\"([0-9]{1,4})\"\\s*>$").find(t) ?: refuse("apn_version", "apns-conf.xml line $n: the root is not <apns version=\"N\">")
                    version = m.groupValues[1].toInt()
                }
                t == "</apns>" -> {
                    if (version == null || closed) bad(n, "a stray </apns>")
                    closed = true
                }
                t.startsWith("<apn ") || t == "<apn/>" -> {
                    if (version == null || closed) bad(n, "an <apn> outside <apns>")
                    if (!t.endsWith("/>")) bad(n, "an <apn> element not closed on its own line")
                    rows += Row(n, rows.size, attributes(t.substring(4, t.length - 2), n), line)
                }
                else -> bad(n, "unexpected text")
            }
        }
        if (inComment) bad(lines.size, "an unterminated comment")
        if (version == null) refuse("apn_version", "apns-conf.xml has no <apns version> root")
        if (!closed) bad(lines.size, "no </apns>")
        return Doc(version!!, digest, rows, lines)
    }

    private fun attributes(body: String, n: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (true) {
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) break
            val start = i
            while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_' || body[i] == '-' || body[i] == ':' || body[i] == '.')) i++
            if (i == start) bad(n, "a bad attribute name")
            val name = body.substring(start, i)
            if (i + 1 >= body.length || body[i] != '=' || body[i + 1] != '"') bad(n, "attribute $name is not name=\"value\"")
            i += 2
            val end = body.indexOf('"', i)
            if (end < 0) bad(n, "attribute $name is not closed")
            val value = unescape(body.substring(i, end), n)
            if (out.put(name, value) != null) bad(n, "attribute $name twice")
            i = end + 1
            if (i < body.length && !body[i].isWhitespace()) bad(n, "no space after attribute $name")
        }
        return out
    }

    private fun unescape(s: String, n: Int): String {
        if ('<' in s) bad(n, "'<' in an attribute value")
        if ('&' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c); i++; continue
            }
            val end = s.indexOf(';', i)
            if (end < 0) bad(n, "an unterminated entity")
            val name = s.substring(i + 1, end)
            out.append(
                when {
                    name == "amp" -> "&"
                    name == "lt" -> "<"
                    name == "gt" -> ">"
                    name == "quot" -> "\""
                    name == "apos" -> "'"
                    name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: bad(n, "a bad character reference")
                    name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: bad(n, "a bad character reference")
                    else -> bad(n, "an unknown entity &$name;")
                },
            )
            i = end + 1
        }
        return out.toString()
    }

    /**
     * Check 10: the index agrees with the XML: the same row count, each row on its line and
     * ordinal, each recomputed row_key equal to the index's.
     */
    fun checkIndex(doc: Doc, index: ApnIndex, xmlSha256: String) {
        if (index.xmlSha256 != xmlSha256) refuse("apn_index_mismatch", "apn-index.json names another apns-conf.xml")
        if (doc.rows.size != index.rows.size) refuse("apn_index_mismatch", "apns-conf.xml has ${doc.rows.size} rows, apn-index.json ${index.rows.size}")
        for ((i, row) in doc.rows.withIndex()) {
            val ix = index.rows[i]
            if (ix.ordinal != i || ix.line != row.line) refuse("apn_index_mismatch", "row $i is on line ${row.line}, the index says ${ix.line} (ordinal ${ix.ordinal})")
            if (ix.rowKey != row.rowKey) refuse("apn_index_mismatch", "row $i on line ${row.line}: row_key ${row.rowKey}, the index says ${ix.rowKey}")
        }
    }
}
