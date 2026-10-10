package io.github.open_carrier_data.check.core

/** Names for TelephonyManager.NETWORK_TYPE_* and TelephonyDisplayInfo override values. */
object Radio {
    const val LTE = 13
    const val IWLAN = 18
    const val LTE_CA = 19
    const val NR = 20

    private val types = mapOf(
        1 to ("gprs" to "2G (GPRS)"),
        2 to ("edge" to "2G (EDGE)"),
        3 to ("umts" to "3G (UMTS)"),
        4 to ("cdma" to "2G (CDMA)"),
        5 to ("evdo_0" to "3G (EV-DO)"),
        6 to ("evdo_a" to "3G (EV-DO)"),
        7 to ("1xrtt" to "2G (1xRTT)"),
        8 to ("hsdpa" to "3G (HSDPA)"),
        9 to ("hsupa" to "3G (HSUPA)"),
        10 to ("hspa" to "3G (HSPA)"),
        11 to ("iden" to "2G (iDEN)"),
        12 to ("evdo_b" to "3G (EV-DO)"),
        LTE to ("lte" to "4G (LTE)"),
        14 to ("ehrpd" to "3G (eHRPD)"),
        15 to ("hspap" to "3G (HSPA+)"),
        16 to ("gsm" to "2G (GSM)"),
        17 to ("td_scdma" to "3G (TD-SCDMA)"),
        IWLAN to ("iwlan" to "Wi-Fi (IWLAN)"),
        LTE_CA to ("lte_ca" to "4G (LTE-A)"),
        NR to ("nr" to "5G (NR)"),
    )

    fun wire(type: Int?): String = types[type]?.first ?: "unknown"

    fun plain(type: Int?): String = types[type]?.second ?: "none or unknown"

    fun isLte(type: Int?): Boolean = type == LTE || type == LTE_CA

    // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*
    private val overrides = mapOf(
        0 to ("none" to "no override"),
        1 to ("lte_ca" to "4G+ (LTE carrier aggregation)"),
        2 to ("lte_advanced_pro" to "4G advanced pro"),
        3 to ("nr_nsa" to "5G (non-standalone)"),
        4 to ("nr_nsa_mmwave" to "5G mmWave (non-standalone)"),
        5 to ("nr_advanced" to "5G+ (advanced)"),
    )

    fun iconWire(value: Int?): String = if (value == null) "not_reported" else overrides[value]?.first ?: "other"

    fun iconPlain(value: Int?): String =
        if (value == null) "not reported (needs Android 11)" else overrides[value]?.second ?: "other ($value)"

    fun isNrIcon(value: Int?): Boolean = value == 3 || value == 4 || value == 5
}

/** What the public APIs say about 5G right now. All of it is a hint, not the radio's own state. */
enum class FiveG(val wire: String, val plain: String) {
    SA("sa", "5G standalone (SA) is in use for mobile data."),
    NSA_WITH_NR_SIGNAL(
        "nsa_with_nr_signal",
        "5G non-standalone (NSA): a 4G connection with the 5G icon, and the phone reports a 5G signal.",
    ),
    NSA_ICON_ONLY(
        "nsa_icon_without_nr_signal",
        "The 5G icon is on, but the phone reports no 5G signal. The icon follows carrier rules and can " +
            "show near a 5G-capable cell without an active 5G link.",
    ),
    NR_SIGNAL_NO_ICON("nr_signal_without_icon", "The phone reports a 5G signal, but shows no 5G icon."),
    NONE("none", "No 5G now."),
    UNKNOWN("unknown", "Not known: the phone gave no network type.");

    companion object {
        /**
         * SA: the mobile data registration (or the data network type) is NR.
         * NSA: LTE plus the NR icon override (carrier branding, see
         * android-access.md 2.2) and, separately, an NR cell in SignalStrength.
         */
        fun classify(network: NetworkReading?): FiveG {
            if (network == null) return UNKNOWN
            val base = network.psWwanType?.takeIf { it != 0 } ?: network.dataNetworkType?.takeIf { it != 0 }
            if (base == Radio.NR || network.dataNetworkType == Radio.NR) return SA
            val icon = Radio.isNrIcon(network.iconOverride)
            val signal = network.nrSignalReported == true
            return when {
                base == null && !icon && !signal -> if (network.inService == false) NONE else UNKNOWN
                base != null && !Radio.isLte(base) -> NONE
                icon && signal -> NSA_WITH_NR_SIGNAL
                icon -> NSA_ICON_ONLY
                signal -> NR_SIGNAL_NO_ICON
                else -> NONE
            }
        }
    }
}
