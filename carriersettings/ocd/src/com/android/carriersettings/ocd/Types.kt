/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/** A refusal with one of the spec's error codes (6.5). [detail] never holds a SIM identifier. */
class Refused(val code: String, detail: String) : Exception(detail.take(240)) {
    val detail: String get() = message.orEmpty()
}

fun refuse(code: String, detail: String): Nothing = throw Refused(code, detail)

/** The key registry's types (spec 2.3). */
enum class KeyType(val wire: String) {
    BOOL("bool"),
    INT("int"),
    LONG("long"),
    DOUBLE("double"),
    STRING("string"),
    STRING_ARRAY("string_array"),
    INT_ARRAY("int_array"),
    BUNDLE("bundle");

    companion object {
        fun of(wire: Any?): KeyType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One member of a bundle value: its inner type and value. Bundle values are
 * `Map<String, Inner>`; the other types are Boolean, Int, Long, Double, String, List<String>
 * and List<Int>, so `==` compares values.
 */
data class Inner(val type: KeyType, val value: Any)

/**
 * Typed values: JSON to a key's registry type and back. Every failure is [Refused] with
 * `wrong_type` or `invalid_value`.
 */
object Types {
    const val MAX_STRING = 4096
    const val MAX_ARRAY = 1024
    const val MAX_BUNDLE_DEPTH = 3
    const val MAX_INNER_KEY = 128
    const val VERSION_KEY = "carrier_config_version_string"
    private val WFC_MODE_KEYS = setOf("carrier_default_wfc_ims_mode_int", "carrier_default_wfc_ims_roaming_mode_int")

    /** [json] (as [Json] reads it) as a value of [type] for [key]; the domain checks of 2.3 included. */
    fun typed(key: String, type: KeyType, json: Any?): Any {
        if (key == VERSION_KEY) refuse("invalid_value", "no value may be set for $VERSION_KEY")
        val value = convert(key, type, json, 1)
        if (key in WFC_MODE_KEYS && value !in setOf(0, 1, 2)) refuse("invalid_value", "$key must be 0, 1 or 2")
        return value
    }

    private fun wrong(key: String, type: KeyType): Nothing = refuse("wrong_type", "$key is not a ${type.wire}")

    private fun convert(key: String, type: KeyType, json: Any?, depth: Int): Any = when (type) {
        KeyType.BOOL -> json as? Boolean ?: wrong(key, type)
        KeyType.INT -> int(key, json) ?: wrong(key, type)
        KeyType.LONG -> ((json as? Json.Num)?.longOrNull()) ?: wrong(key, type)
        KeyType.DOUBLE -> {
            val n = json as? Json.Num ?: wrong(key, type)
            val d = n.toDouble()
            if (!d.isFinite()) refuse("invalid_value", "$key is not finite")
            d
        }
        KeyType.STRING -> string(key, json) ?: wrong(key, type)
        KeyType.STRING_ARRAY -> {
            val list = json as? List<*> ?: wrong(key, type)
            if (list.size > MAX_ARRAY) refuse("invalid_value", "$key has more than $MAX_ARRAY items")
            list.map { string(key, it) ?: wrong(key, type) }
        }
        KeyType.INT_ARRAY -> {
            val list = json as? List<*> ?: wrong(key, type)
            if (list.size > MAX_ARRAY) refuse("invalid_value", "$key has more than $MAX_ARRAY items")
            list.map { int(key, it) ?: wrong(key, type) }
        }
        KeyType.BUNDLE -> {
            if (depth > MAX_BUNDLE_DEPTH) refuse("invalid_value", "$key nests bundles deeper than $MAX_BUNDLE_DEPTH")
            val obj = json as? Map<*, *> ?: wrong(key, type)
            val out = LinkedHashMap<String, Inner>()
            for ((k, v) in obj) {
                val inner = k as String
                if (inner.length > MAX_INNER_KEY) refuse("invalid_value", "$key has an inner key over $MAX_INNER_KEY units")
                val m = v as? Map<*, *> ?: wrong(key, type)
                if (m.keys != setOf("type", "value")) refuse("wrong_type", "$key: inner $inner is not {type, value}")
                val t = KeyType.of(m["type"]) ?: refuse("wrong_type", "$key: inner $inner has an unknown type")
                out[inner] = Inner(t, convert(key, t, m["value"], depth + 1))
            }
            out
        }
    }

    private fun int(key: String, json: Any?): Int? {
        val n = (json as? Json.Num)?.longOrNull() ?: return null
        if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) refuse("invalid_value", "$key is out of the int range")
        return n.toInt()
    }

    private fun string(key: String, json: Any?): String? {
        val s = json as? String ?: return null
        if (s.length > MAX_STRING) refuse("invalid_value", "$key is longer than $MAX_STRING")
        if ('\u0000' in s) refuse("invalid_value", "$key holds U+0000")
        return s
    }

    /** A typed value back to JSON (for the serving index, `explain` and records). */
    fun toJson(type: KeyType, value: Any): Any? = when (type) {
        KeyType.BOOL, KeyType.STRING -> value
        KeyType.INT -> value as Int
        KeyType.LONG -> value as Long
        KeyType.DOUBLE -> value as Double
        KeyType.STRING_ARRAY, KeyType.INT_ARRAY -> value as List<*>
        KeyType.BUNDLE -> {
            @Suppress("UNCHECKED_CAST")
            val b = value as Map<String, Inner>
            b.entries.associateTo(LinkedHashMap()) { (k, inner) -> k to linkedMapOf("type" to inner.type.wire, "value" to toJson(inner.type, inner.value)) }
        }
    }

    /** JSON written by [toJson] back to a typed value; numbers may arrive as Int, Long or [Json.Num]. */
    fun fromStored(key: String, type: KeyType, json: Any?): Any = convert(key, type, normalize(json), 1)

    private fun normalize(json: Any?): Any? = when (json) {
        is Int, is Long -> Json.Num(json.toString())
        is Double -> Json.Num(json.toString())
        is List<*> -> json.map { normalize(it) }
        is Map<*, *> -> json.entries.associate { (k, v) -> k to normalize(v) }
        else -> json
    }

    /** The registry type of a value Android's getDefaultConfig holds, or null for a value no registry type carries. */
    fun typeOfAndroid(value: Any?): KeyType? = when (value) {
        is Boolean -> KeyType.BOOL
        is Int -> KeyType.INT
        is Long -> KeyType.LONG
        is Double -> KeyType.DOUBLE
        is String -> KeyType.STRING
        is Array<*> -> if (value.isArrayOf<String>()) KeyType.STRING_ARRAY else null
        is IntArray -> KeyType.INT_ARRAY
        else -> null
    }

    /** 2.5.1: a null default is compatible with these types only, never with a primitive. */
    val NULLABLE = setOf(KeyType.STRING, KeyType.STRING_ARRAY, KeyType.INT_ARRAY, KeyType.BUNDLE)

    /** A short, single-line rendering for logs and `explain` reasons (never a SIM identifier: values come from the dataset). */
    fun show(type: KeyType, value: Any?): String = if (value == null) "absent" else Json.write(toJson(type, value)).take(120)
}
