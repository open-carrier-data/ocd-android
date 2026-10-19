package io.github.open_carrier_data.check.core

import java.time.LocalDate

/**
 * Everything the app reads from the phone, already reduced to allowlisted
 * fields. There is no field for IMSI, ICCID, IMEI, TAC, a phone number, cell
 * identities, location, the build fingerprint, build user or host, or the
 * baseband string: the app never reads them.
 */
data class PhoneReadings(
    val checkedOn: LocalDate,
    val device: DeviceInfo,
    val hasTelephony: Boolean,
    val permissionGranted: Boolean,
    val sim: SimReading?,
    val network: NetworkReading?,
    val carrierSettings: CarrierSettingsReading?,
    val dataConnections: List<DataConnection>?,
    /** The optional Shizuku deep check; null when it has not run. */
    val deep: DeepCheckReading? = null,
) {
    val accessTier: AccessTier get() = if (deep != null) AccessTier.SHIZUKU else AccessTier.NORMAL
}

/** Which access produced a report: a normal app, or the read-only deep check through Shizuku. */
enum class AccessTier(val wire: String) {
    NORMAL("normal"),
    SHIZUKU("shizuku"),
}

data class DeviceInfo(
    /** Build.MANUFACTURER; on screen and in findings only, never in the report. */
    val manufacturer: String,
    /** Build.MODEL, for example SM-A505F. */
    val model: String,
    /** Build.DEVICE, for example a50 or x1s: the device tree name LineageOS keys its carrier files by. */
    val codename: String,
    val androidMajor: Int,
    val romFamily: RomFamily,
)

enum class RomFamily(val wire: String, val plain: String) {
    STOCK("stock", "maker's own software (as far as the app can tell)"),
    LINEAGEOS("lineageos", "LineageOS"),
    OTHER("other", "another custom build");

    companion object {
        /**
         * Best effort from public Build fields. Only the family leaves this
         * function; the strings themselves (which can hold a builder's user
         * name) are never stored or shown.
         */
        fun detect(
            display: String?,
            fingerprint: String?,
            product: String?,
            buildType: String?,
            tags: String?,
            lineageFeature: Boolean = false,
        ): RomFamily {
            val names = listOfNotNull(display, fingerprint, product).joinToString(" ").lowercase()
            return when {
                // LineageOS declares the system feature org.lineageos.android
                // (vendor/lineage/config/permissions/org.lineageos.android.xml).
                // Builds that keep the stock fingerprint, like the owner's S20,
                // carry no "lineage" in any public Build field.
                lineageFeature || "lineage" in names -> LINEAGEOS
                buildType != null && buildType != "user" -> OTHER
                tags != null && "test-keys" in tags -> OTHER
                else -> STOCK
            }
        }
    }
}

enum class Gid1Status(val wire: String) {
    READ("read"),
    EMPTY("empty"),
    NOT_PERMITTED("not_permitted"),
    UNAVAILABLE("unavailable"),
}

data class SimReading(
    /** Slot number for the screen; never in the report. */
    val slotIndex: Int?,
    val homeMccMnc: String?,
    val spn: String?,
    val carrierId: Long?,
    val carrierName: String?,
    val specificCarrierId: Long?,
    val specificCarrierName: String?,
    val gid1: String?,
    val gid1Status: Gid1Status,
) {
    fun identity(): SimIdentity? {
        val code = homeMccMnc?.takeIf { it.length in 5..6 && it.all(Char::isDigit) } ?: return null
        return SimIdentity(
            mccmnc = code,
            spn = spn ?: "",
            gid1 = when (gid1Status) {
                Gid1Status.READ -> gid1
                Gid1Status.EMPTY -> ""
                Gid1Status.NOT_PERMITTED, Gid1Status.UNAVAILABLE -> null
            },
            carrierIds = setOfNotNull(carrierId, specificCarrierId).filter { it >= 0 }.toSet(),
        )
    }
}

data class NetworkReading(
    val servingMccMnc: String?,
    val roaming: Boolean?,
    /** TelephonyManager.NETWORK_TYPE_* values. */
    val dataNetworkType: Int?,
    val voiceNetworkType: Int?,
    /** Access technology of the packet-switched mobile (WWAN) registration, from ServiceState. */
    val psWwanType: Int?,
    /** TelephonyDisplayInfo override (the 5G icon); null before Android 11 or when not delivered. */
    val iconOverride: Int?,
    /** SignalStrength holds a 5G (NR) cell signal. */
    val nrSignalReported: Boolean?,
    val inService: Boolean?,
)

/** The six CarrierConfig keys the app reads, nothing else. */
data class CarrierSettingsReading(
    val readable: Boolean,
    val identifiedCarrier: Boolean?,
    val booleans: Map<String, Boolean>,
    val nrAvailabilities: List<Int>?,
)

data class DataConnection(
    /** NetworkInfo.getExtraInfo(): the APN name. Never a username or password. */
    val apn: String?,
    /** APN types the network serves, from NetworkCapabilities. */
    val serves: List<String>,
    /** NetworkInfo subtype: the network type the connection was set up over. */
    val setUpOver: Int?,
)
