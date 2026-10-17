package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OCD's device records (generated/devices/carrier-variants.json, round 17):
 * what one phone model's own carrier file sets for VoLTE, Wi-Fi calling, video
 * calling and VoNR on one carrier profile. OCD lists every device value it
 * publishes, including those that agree with the profile's value (round 17
 * tie-break), so a record that agrees shows the model's own file was read.
 *
 * A record is configuration from one source, not a test. A missing record
 * means OCD reads no file of the model that sets a value there, the value is
 * older than five years, or the model does not bind to one catalog device;
 * it is never "off" and never agreement. The app then shows the carrier
 * summary, labelled as the carrier's.
 */
@Serializable
data class DeviceRecords(
    @SerialName("based_on_data_digest") val basedOnDataDigest: String,
    val capabilities: List<String>,
    /** profile ID to capability to records. */
    val profiles: Map<String, Map<String, List<DeviceRecord>>> = emptyMap(),
    /** Catalog device ID to the Build.DEVICE and Build.MODEL values no other catalog device shares. */
    val devices: Map<String, DeviceAliases> = emptyMap(),
) {
    /**
     * The device keys this phone has in the records: `samsung:<Build.MODEL>`
     * on a Samsung phone, and the one catalog device whose unique device names
     * list Build.DEVICE or whose unique models list Build.MODEL, case-folded.
     * Two catalog devices matching is ambiguous and gives none.
     */
    fun keysFor(device: DeviceInfo): List<String> = buildList {
        if (device.manufacturer.casefold() == "samsung" && device.model.isNotBlank()) {
            add("samsung:" + device.model.trim().uppercase())
        }
        val codename = device.codename.casefold()
        val model = device.model.casefold()
        val matches = devices.filterValues { aliases ->
            aliases.deviceNames.any { it.casefold() == codename } || aliases.models.any { it.casefold() == model }
        }.keys
        if (matches.size == 1) add(matches.single())
    }

    /**
     * The records for this phone on one profile and capability, the phone's
     * own software stack first. Usually one; a LineageOS build of a Samsung
     * model can have its tree's record and the model's stock record.
     */
    fun recordsFor(profileId: String, capability: String, device: DeviceInfo): List<DeviceMatch> {
        val records = profiles[profileId]?.get(capability).orEmpty()
        if (records.isEmpty()) return emptyList()
        val keys = keysFor(device)
        val stack = device.romFamily.recordStack
        return records.mapNotNull { record ->
            val key = keys.firstOrNull { it in record.devices } ?: return@mapNotNull null
            DeviceMatch(record, key, record.stack == stack)
        }.sortedBy { !it.sameStack }
    }
}

@Serializable
data class DeviceRecord(
    /** on, off, or conditional (two files of the same device disagree). */
    val state: String,
    val source: String,
    /** stock, lineageos or aosp: the software the value holds for. */
    val stack: String,
    @SerialName("newest_entry") val newestEntry: String? = null,
    val basis: String? = null,
    val devices: List<String> = emptyList(),
)

@Serializable
data class DeviceAliases(
    @SerialName("device_names") val deviceNames: List<String> = emptyList(),
    val models: List<String> = emptyList(),
)

/** A record that applies to this phone; sameStack when it holds for the software the phone runs. */
data class DeviceMatch(val record: DeviceRecord, val deviceKey: String, val sameStack: Boolean) {
    /**
     * "This phone model's own file" only for the phone's own stack. A stock
     * record on a LineageOS build is "this model's stock firmware", never
     * "this phone's".
     */
    val whose: String
        get() = if (sameStack) "This phone model's own file" else "This model's ${stackPlain(record.stack)} file"

    /** One source, its stack and its month, as OCD's docs print it. */
    val sourceLine: String
        get() = buildString {
            append("one source: ").append(sourcePlain(record.source))
            append(", ").append(stackPlain(record.stack))
            record.newestEntry?.let { append(", newest entry ").append(it) }
        }

    companion object {
        fun stackPlain(stack: String): String = when (stack) {
            "stock" -> "stock firmware"
            "lineageos" -> "LineageOS"
            "aosp" -> "AOSP"
            else -> stack
        }

        fun sourcePlain(source: String): String = when (source) {
            "samsung_omc" -> "Samsung's carrier pack"
            "google_carriersettings" -> "Google's Pixel carrier settings"
            "google_pixel_vendor_carriersettings" -> "a frozen copy of Google's Pixel carrier settings"
            "lineageos_device_overlays" -> "the LineageOS device tree"
            "sony_open_devices_aosp" -> "Sony's AOSP tree"
            "fairphone_official_source" -> "Fairphone's carrier files"
            else -> source
        }
    }
}

/** The stack name OCD's device records use for this software family, or null for another custom build. */
val RomFamily.recordStack: String?
    get() = when (this) {
        RomFamily.STOCK -> "stock"
        RomFamily.LINEAGEOS -> "lineageos"
        RomFamily.OTHER -> null
    }
