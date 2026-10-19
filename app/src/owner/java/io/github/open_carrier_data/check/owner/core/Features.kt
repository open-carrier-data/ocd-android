package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable

/*
 * The owner build's write list, ported from OCD Lab (tools/ocd_lab/lab_ocd.py
 * FEATURES, FORBIDDEN_KEY and check_key in OCD's private repository). Each
 * feature has exactly one CarrierConfig switch, and no other key can be
 * written. The owner direction of 2026-10-19 adds video calling and VoNR to
 * round 25's OB4 list; the safety floor stays.
 */

/** One IMS feature and the one CarrierConfig switch the owner build may change for it. */
@Serializable
enum class OwnerFeature(
    val wire: String,
    val key: String,
    /** OCD's capability key. */
    val capability: String,
    val label: String,
    /** Turning it off can take voice away, so an off is kept only with voice service measured. */
    val voice: Boolean,
    /** OCD's carrier-variants.json carries device records for it. */
    val deviceRecords: Boolean,
) {
    // VoLTE first: the others ride on IMS.
    VOLTE("volte", "carrier_volte_available_bool", "volte", "VoLTE", voice = true, deviceRecords = true),
    SMS_OVER_IMS("sms_over_ims", "imssms.sms_over_ims_supported_bool", "sms_over_ims", "SMS over IMS", voice = false, deviceRecords = false),
    VIDEO_CALLING("video_calling", "carrier_vt_available_bool", "video_calling", "Video calling", voice = false, deviceRecords = true),
    VOWIFI("vowifi", "carrier_wfc_ims_available_bool", "vowifi", "Wi-Fi calling", voice = true, deviceRecords = true),
    VONR("vonr", "vonr_enabled_bool", "vonr", "VoNR", voice = true, deviceRecords = true);

    companion object {
        fun ofKey(key: String): OwnerFeature? = entries.firstOrNull { it.key == key }
        fun ofWire(wire: String): OwnerFeature? = entries.firstOrNull { it.wire == wire }
    }
}

/** A guard refused. Whatever ran so far is reverted, and nothing else changes. */
class SafetyException(val reason: String, message: String = reason) : Exception(message)

/**
 * The key guard, checked before every write, in the app and again in the
 * Shizuku service. The owner's floor (2026-10-19): never a key matching
 * emergency, e911, ecbm, wps, tty, rtt or `iwlan.`; then only the five
 * feature switches.
 */
object KeyGuard {
    val FORBIDDEN = Regex("emergency|e911|ecbm|wps|tty|rtt|iwlan\\.", RegexOption.IGNORE_CASE)
    val ALLOWED: Set<String> = OwnerFeature.entries.map { it.key }.toSet()

    fun check(key: String) {
        if (FORBIDDEN.containsMatchIn(key)) throw SafetyException("forbidden_key", "$key is a safety-class key; the owner build never changes it")
        if (key !in ALLOWED) throw SafetyException("key_not_allowed", "$key is not one of the five feature switches")
    }

    fun allowed(key: String): Boolean = runCatching { check(key) }.isSuccess

    /**
     * An APN row the owner build may insert: never an emergency type, and no
     * type, APN name or entry name that matches the forbidden pattern.
     */
    fun checkApn(row: OcdApnRow) {
        if ("emergency" in row.types) throw SafetyException("emergency_apn", "emergency APN rows are never written")
        for (value in listOf(row.apn, row.name.orEmpty()) + row.types) {
            if (FORBIDDEN.containsMatchIn(value)) throw SafetyException("forbidden_apn", "the APN row names a safety-class value")
        }
    }
}
