package io.github.open_carrier_data.check.core

/** A CarrierConfig key the app reads, and the OCD capability it is compared with. */
data class CarrierKey(
    val key: String,
    /** OCD capability key, or null when OCD has no capability to compare it with. */
    val capability: String?,
    /** Android's default when no layer sets the key (docs/consume.md table). */
    val androidDefault: Boolean?,
)

/** The allowlist from phone_check.py CARRIER_CONFIG_KEYS. The app reads no other key. */
object CarrierKeys {
    const val NR_AVAILABILITIES = "carrier_nr_availabilities_int_array"

    val booleans = listOf(
        CarrierKey("carrier_volte_available_bool", "volte", false),
        CarrierKey("carrier_wfc_ims_available_bool", "vowifi", false),
        CarrierKey("carrier_vt_available_bool", "video_calling", false),
        CarrierKey("vonr_enabled_bool", "vonr", false),
        CarrierKey("enabledMMS", "mms", true),
    )

    val all: List<String> = booleans.map { it.key } + NR_AVAILABILITIES

    /**
     * Whether the phone's value agrees with OCD's answer: supported expects
     * on, unsupported expects off. conditional (sources disagree) and unknown
     * give nothing to compare, so the result is null, as it is when the phone
     * gave no value.
     */
    fun matchesOcd(phone: Boolean?, ocd: CapabilityValue): Boolean? = when {
        phone == null -> null
        ocd == CapabilityValue.SUPPORTED -> phone
        ocd == CapabilityValue.UNSUPPORTED -> !phone
        else -> null
    }

    /** carrier_nr_availabilities_int_array: 1 = NSA, 2 = SA (CarrierConfigManager). */
    fun nrPlain(values: List<Int>?): String = when {
        values == null -> "not set"
        values.isEmpty() -> "none"
        else -> values.joinToString(", ") { if (it == 1) "NSA" else if (it == 2) "SA" else "other ($it)" }
    }
}
