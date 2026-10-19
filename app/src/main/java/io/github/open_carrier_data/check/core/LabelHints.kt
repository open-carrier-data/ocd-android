package io.github.open_carrier_data.check.core

/**
 * A `supported` summary next to `off` device records (OCD round 22).
 *
 * Since round 17's step 2, OCD's summary for VoLTE, Wi-Fi calling and video
 * calling can be `supported` while one maker's or maintainer's own files turn
 * the feature off on some phone models: OCD lists those models as `off` in
 * generated/devices/carrier-variants.json and at least one model as `on`. The
 * summary never overrides an `off` record for its model. Round 22 orders the
 * app to say so wherever such a summary is shown: under the summary, in the
 * phone-off finding, and next to this phone model's own record.
 */
data class OffRecordHint(
    val capability: String,
    /** Distinct device keys in `off` records on the profile and capability. */
    val offModels: Int,
    /** This phone model's own record among them, when it has one. */
    val thisModel: DeviceMatch?,
) {
    /** Round 22's sentence (correctness ruling, Q4 item 4), with the count. */
    val text: String
        get() = buildString {
            val label = Capabilities.label(capability)
            append(
                if (offModels == 1) {
                    "1 phone model's own file turns $label off for this SIM"
                } else {
                    "$offModels phone models' own files turn $label off for this SIM"
                },
            )
            append(" (one maker's or maintainer's files; OCD does not count that as proof).")
            thisModel?.let { append(" ${it.whose} is one of them; for this model, its file comes before OCD's summary.") }
        }
}

object LabelHints {
    /** The three switches whose summary step 2 can make `supported` next to `off` records. */
    val capabilities = listOf("volte", "vowifi", "video_calling")

    /**
     * The hint for one capability on the most specific profile, or null: the
     * summary must be `supported` and the bundle's device records must hold an
     * `off` record there.
     */
    fun offRecords(bundle: OcdBundle, resolution: Resolution?, device: DeviceInfo, capability: String): OffRecordHint? {
        if (capability !in capabilities) return null
        val records = bundle.records ?: return null
        val profile = resolution?.mostSpecific ?: return null
        if (resolution.answer(capability) != CapabilityValue.SUPPORTED) return null
        val off = records.profiles[profile.profileId]?.get(capability).orEmpty().filter { it.state == "off" }
        if (off.isEmpty()) return null
        val own = records.recordsFor(profile.profileId, capability, device).firstOrNull { it.record.state == "off" }
        return OffRecordHint(capability, off.flatMap { it.devices }.distinct().size, own)
    }
}
