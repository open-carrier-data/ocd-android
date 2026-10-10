package io.github.open_carrier_data.check.core

/** OCD's four capability values (schemas/carrier-profile.schema.json, capabilityStatus). */
enum class CapabilityValue(val wire: String) {
    SUPPORTED("supported"),
    CONDITIONAL("conditional"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown");

    /** OCD's tables turn the feature on for this SIM (supported or conditional). */
    val tablesTurnOn: Boolean get() = this == SUPPORTED || this == CONDITIONAL

    companion object {
        fun fromWire(value: String?): CapabilityValue = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Plain names for OCD's capability keys, in the order the app shows them. */
object Capabilities {
    val labels: Map<String, String> = linkedMapOf(
        "volte" to "VoLTE (calls over 4G)",
        "vowifi" to "Wi-Fi calling",
        "vonr" to "VoNR (calls over 5G standalone)",
        "video_calling" to "Video calling",
        "sms_over_ims" to "SMS over IMS",
        "mms" to "MMS",
        "rcs" to "RCS chat",
        "wifi_calling_roaming" to "Wi-Fi calling while roaming",
        "ims_conference" to "Conference calls over IMS",
        "esim" to "eSIM",
    )

    fun label(key: String): String = labels[key] ?: key

    /** Shown order: the known keys first, then any new key the data adds. */
    fun ordered(keys: Collection<String>): List<String> =
        labels.keys.filter { it in keys } + keys.filter { it !in labels }.sorted()
}
