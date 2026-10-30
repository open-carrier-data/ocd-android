/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A strict, bounded JSON reader and writer for the dataset files (spec 2.2). No library and no
 * reflection, so the reader depends on nothing that changes with the platform.
 *
 * - UTF-8 without a BOM, RFC 8259, trailing text refused.
 * - Objects become [LinkedHashMap] in file order, arrays [List], strings [String], true, false
 *   and null as themselves, and numbers [Num], which keeps the literal so an int written with
 *   a fraction or an exponent can be refused by type.
 * - A duplicate member name, a U+0000 (also escaped), a string over [Limits.maxString] UTF-16
 *   units, an array over [Limits.maxArray] items (except where [Limits.arrayExempt] allows it)
 *   or nesting deeper than [Limits.maxDepth] is a [ParseException].
 *
 * [parse] with a `keep` filter skips object members whose path it rejects without building
 * them, so one network of a 4 MB `profiles.json` can be read without the others (spec 9.4).
 */
object Json {
    class ParseException(message: String) : Exception(message)

    /** A JSON number as written. [integral] when it has neither a fraction nor an exponent. */
    class Num(val text: String) {
        val integral: Boolean = text.none { it == '.' || it == 'e' || it == 'E' }

        fun longOrNull(): Long? = if (integral) text.toLongOrNull() else null

        fun toDouble(): Double = text.toDouble()

        override fun equals(other: Any?): Boolean = other is Num && other.text == text

        override fun hashCode(): Int = text.hashCode()

        override fun toString(): String = text
    }

    /**
     * [arrayExempt] gets the array's depth (the root object is depth 0, its members' values
     * depth 1) and the member name that holds it (null inside an array).
     */
    class Limits(
        val maxString: Int = 4096,
        val maxArray: Int = 1024,
        val maxDepth: Int = 32,
        val arrayExempt: (depth: Int, name: String?) -> Boolean = { _, _ -> false },
    )

    val DEFAULT = Limits()

    /** The dataset files' rule: the top-level lists of profiles, rules, records and rows are not capped. */
    val DATASET = Limits(arrayExempt = { depth, name ->
        (depth == 1 && (name == "rules" || name == "records" || name == "rows")) || (depth == 3 && name == "profiles")
    })

    /** Strict UTF-8 without a BOM. */
    fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            throw ParseException("a byte order mark")
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw ParseException("not UTF-8")
        }
    }

    fun parseBytes(bytes: ByteArray, limits: Limits = DEFAULT, keep: ((List<String>) -> Boolean)? = null): Any? =
        parse(decodeUtf8(bytes), limits, keep)

    fun parse(text: String, limits: Limits = DEFAULT, keep: ((List<String>) -> Boolean)? = null): Any? {
        val reader = Reader(text, limits, keep)
        reader.space()
        val value = reader.value(0, null, ArrayList())
        reader.space()
        if (reader.pos != text.length) throw ParseException("trailing text at ${reader.pos}")
        return value
    }

    // --- writing ---------------------------------------------------------------------

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "not a finite number" }
                out.append(if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() + ".0" else value.toString())
            }
            is Num -> out.append(value.text)
            is String -> string(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k as String)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value: ${value.javaClass.simpleName}")
        }
    }

    fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c.code in 0x7f..0x9f || c == ' ' || c == ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    // --- reading ---------------------------------------------------------------------

    private class Reader(val text: String, val limits: Limits, val keep: ((List<String>) -> Boolean)?) {
        var pos = 0

        fun space() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) pos++
        }

        fun value(depth: Int, name: String?, path: ArrayList<String>): Any? {
            if (depth > limits.maxDepth) throw ParseException("nested deeper than ${limits.maxDepth}")
            if (pos >= text.length) throw ParseException("unexpected end")
            return when (val c = text[pos]) {
                '{' -> obj(depth, path)
                '[' -> array(depth, name, path)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun number(): Num {
            val start = pos
            if (text[pos] == '-') pos++
            val digits = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digits) throw ParseException("bad number at $start")
            if (pos - digits > 1 && text[digits] == '0') throw ParseException("leading zero at $start")
            if (pos < text.length && text[pos] == '.') {
                pos++
                val f = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == f) throw ParseException("bad fraction at $start")
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                val e = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == e) throw ParseException("bad exponent at $start")
            }
            if (pos - start > 40) throw ParseException("number too long at $start")
            return Num(text.substring(start, pos))
        }

        fun string(): String {
            pos++ // opening quote
            val begin = pos
            // Fast path: no escape.
            while (pos < text.length) {
                val c = text[pos]
                if (c == '"') {
                    val s = text.substring(begin, pos)
                    pos++
                    if (s.length > limits.maxString) throw ParseException("string longer than ${limits.maxString}")
                    return s
                }
                if (c == '\\') break
                if (c < ' ') throw ParseException(if (c == '\u0000') "U+0000 in a string" else "control character in a string")
                pos++
            }
            val out = StringBuilder().append(text, begin, pos)
            while (true) {
                if (pos >= text.length) throw ParseException("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> {
                        if (out.length > limits.maxString) throw ParseException("string longer than ${limits.maxString}")
                        return out.toString()
                    }
                    c == '\\' -> {
                        if (pos >= text.length) throw ParseException("unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw ParseException("short \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw ParseException("bad \\u escape")
                                val ch = hex.toInt(16).toChar()
                                if (ch == '\u0000') throw ParseException("U+0000 in a string")
                                out.append(ch)
                                pos += 4
                            }
                            else -> throw ParseException("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw ParseException(if (c == '\u0000') "U+0000 in a string" else "control character in a string")
                    else -> out.append(c)
                }
                if (out.length > limits.maxString + 1) throw ParseException("string longer than ${limits.maxString}")
            }
        }

        private fun obj(depth: Int, path: ArrayList<String>): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            val seen = HashSet<String>()
            space()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                space()
                if (pos >= text.length || text[pos] != '"') throw ParseException("expected a member name at $pos")
                val key = string()
                if (!seen.add(key)) throw ParseException("duplicate member \"$key\"")
                space()
                if (pos >= text.length || text[pos] != ':') throw ParseException("expected ':' at $pos")
                pos++
                space()
                path.add(key)
                if (keep == null || keep.invoke(path)) {
                    out[key] = value(depth + 1, key, path)
                } else {
                    skip(depth + 1)
                }
                path.removeAt(path.size - 1)
                space()
                if (pos >= text.length) throw ParseException("unterminated object")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw ParseException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun array(depth: Int, name: String?, path: ArrayList<String>): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            val cap = if (limits.arrayExempt(depth, name)) Int.MAX_VALUE else limits.maxArray
            space()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                space()
                out += value(depth + 1, null, path)
                if (out.size > cap) throw ParseException("array${name?.let { " \"$it\"" } ?: ""} longer than $cap")
                space()
                if (pos >= text.length) throw ParseException("unterminated array")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw ParseException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        /** Skips one value, checking its syntax and duplicate names but building nothing. */
        private fun skip(depth: Int) {
            if (depth > limits.maxDepth) throw ParseException("nested deeper than ${limits.maxDepth}")
            if (pos >= text.length) throw ParseException("unexpected end")
            when (text[pos]) {
                '{' -> {
                    pos++
                    space()
                    if (pos < text.length && text[pos] == '}') { pos++; return }
                    val seen = HashSet<String>()
                    while (true) {
                        space()
                        if (pos >= text.length || text[pos] != '"') throw ParseException("expected a member name at $pos")
                        if (!seen.add(string())) throw ParseException("duplicate member")
                        space()
                        if (pos >= text.length || text[pos] != ':') throw ParseException("expected ':' at $pos")
                        pos++
                        space()
                        skip(depth + 1)
                        space()
                        if (pos >= text.length) throw ParseException("unterminated object")
                        when (text[pos++]) {
                            ',' -> continue
                            '}' -> return
                            else -> throw ParseException("expected ',' or '}' at ${pos - 1}")
                        }
                    }
                }
                '[' -> {
                    pos++
                    space()
                    if (pos < text.length && text[pos] == ']') { pos++; return }
                    while (true) {
                        space()
                        skip(depth + 1)
                        space()
                        if (pos >= text.length) throw ParseException("unterminated array")
                        when (text[pos++]) {
                            ',' -> continue
                            ']' -> return
                            else -> throw ParseException("expected ',' or ']' at ${pos - 1}")
                        }
                    }
                }
                else -> value(depth, null, ArrayList())
            }
        }
    }
}
