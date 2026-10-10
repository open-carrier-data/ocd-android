package io.github.open_carrier_data.check.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fail-closed privacy scan of a report, ported from OCD's private tools
 * (read-only copies of the patterns; keep them in step):
 *
 * - `BLOCK_PATTERNS` from tools/sanitize_profiles.py, run on every value;
 * - `EXTRA_VALUE_PATTERNS` from tools/field_observations.py, run on every
 *   value except fixed-shape hex fields;
 * - `BLOCKED_RUNTIME_KEYS` from tools/import_android_runtime_snapshot.py and
 *   `KEY_SCAN_PATTERNS` from field_observations.py, run on every key;
 * - phone_check.py's `redact` shapes (SIP/tel URIs, IPv4, IPv6) as extra
 *   blocking rules: the app blocks instead of rewriting.
 *
 * A hit names the field and the rule, never the matched text. Any hit
 * disables copy and share.
 */
object PrivacyScan {
    data class Hit(val path: String, val rule: String)

    val blockPatterns: Map<String, Regex> = linkedMapOf(
        "email" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        "long_decimal_identity" to Regex("\\b\\d{14,20}\\b"),
        "international_phone" to Regex("\\+\\d{8,15}\\b"),
        "secret_word" to Regex("(?i)\\b(pswd|secret|token|authorization)\\b"),
        "raw_vendor_file" to Regex(
            "(?i)\\b(customer\\.xml|cscfeature\\.xml|imsupdate\\.json|omc\\.info|" +
                "customer_carrier_feature\\.json|carrier_canonical_id_list\\.json|" +
                "carrier\\.plist|\\.ipcc|carrier_settings\\.pb|mbn)\\b",
        ),
        "raw_vendor_key" to Regex("\\b(CarrierFeature_|GRASSE-AUTH|SemCarrierFeature|CarrierBundle|CSettingsDir)\\b"),
        "gras_payload" to Regex("(?i)\\b(updateURL|pkgHash|pkgSize|fileDownloadUrl|deviceId|imei)\\b"),
        "ims_patch" to Regex(
            "\\b(imsswitch_update|imsprofile_update|globalsettings_update|mnomap_update|" +
                "add_mnomap|remove_mnomap|remove_profile|epdgsettings|iwlansettings|" +
                "vowifisettings|enableIms|enableService[A-Za-z]+|mnoname)\\b",
        ),
        // phone_check.py redact() shapes, blocking here.
        "sip_or_tel_uri" to Regex("\\b(?:sip|sips|tel|urn):[^\\s,;>\"\\]]+"),
        "ipv4_address" to Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
        "ipv6_address" to Regex("\\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){3,7}\\b"),
    )

    val extraValuePatterns: Map<String, Regex> = linkedMapOf(
        "spaced_international_phone" to Regex("\\+\\d{1,3}[ ./-]?(?:\\(\\d{1,5}\\)[ ./-]?)?\\d{2,5}[ ./-]\\d{3,}"),
        "national_phone" to Regex("\\b0\\d{2,5}[ /-]?\\d{6,}\\b"),
    )

    val blockedKey = Regex("(?i)(imsi|iccid|imei|meid|msisdn|phone_number|subscriber|line1|device_id|android_id|serial)")
    private val keyScanPatterns = listOf("secret_word", "raw_vendor_key", "gras_payload")

    /** Fixed-shape hex fields skip only the two extra phone heuristics, as in field_observations.py. */
    val hexFields = setOf("report.ocd_data.source_commit", "report.sim.gid1")

    fun scan(element: JsonElement, where: String = "report"): List<Hit> {
        val hits = mutableListOf<Hit>()
        walk(element, where, hits)
        return hits
    }

    fun scanValue(path: String, text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (candidate in variants(text)) {
            for ((name, pattern) in blockPatterns) {
                if (pattern.containsMatchIn(candidate)) hits += Hit(path, name)
            }
            if (path in hexFields) continue
            for ((name, pattern) in extraValuePatterns) {
                if (pattern.containsMatchIn(candidate)) hits += Hit(path, name)
            }
        }
        return hits.distinct()
    }

    private fun scanKey(path: String, key: String): List<Hit> = buildList {
        if (blockedKey.containsMatchIn(key)) add(Hit(path, "blocked_key"))
        for (name in keyScanPatterns) {
            if (blockPatterns.getValue(name).containsMatchIn(key)) add(Hit(path, name))
        }
    }

    private fun walk(element: JsonElement, where: String, hits: MutableList<Hit>) {
        when (element) {
            is JsonObject -> for ((key, child) in element) {
                hits += scanKey("$where.$key", key)
                walk(child, "$where.$key", hits)
            }
            is JsonArray -> element.forEachIndexed { index, child -> walk(child, "$where[$index]", hits) }
            JsonNull -> Unit
            is JsonPrimitive -> if (element.isString) hits += scanValue(where, element.content)
        }
    }

    /**
     * The text itself and, when it holds non-ASCII digits, a copy with every
     * decimal digit mapped to ASCII: Python's \d matches any Unicode digit, the
     * JVM's only 0-9. Scanning both keeps the port at least as strict.
     */
    private fun variants(text: String): List<String> {
        if (text.none { it.isDigit() && it !in '0'..'9' }) return listOf(text)
        val ascii = buildString {
            for (char in text) append(if (char.isDigit()) ('0' + Character.digit(char, 10)) else char)
        }
        return listOf(text, ascii)
    }
}
