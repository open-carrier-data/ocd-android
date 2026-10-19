package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.SimIdentity
import kotlinx.serialization.Serializable

/**
 * OCD's on or off for one feature on this phone and SIM, and what it rests
 * on (lab `ocd_value` and `stack_honesty`, LB7): the most specific profile's
 * device record for this model, the phone's own stack first; without one,
 * the profile's capability (supported is on, unsupported is off;
 * conditional and unknown give no value).
 */
@Serializable
data class OcdAnswer(
    val value: Boolean?,
    /** device_record, carrier_profile or no_profile. */
    val basis: String,
    val profileId: String? = null,
    val profileName: String? = null,
    val capability: String? = null,
    val recordSource: String? = null,
    val recordStack: String? = null,
    /** null when the value is not a device record. */
    val sameStack: Boolean? = null,
) {
    /** How a result on this phone must read (LB7). */
    val stackNote: String
        get() = when {
            basis == "device_record" && sameStack == true -> "this stack's own value tested on this stack"
            basis == "device_record" -> "a ${recordStack ?: "other-stack"} value tested on this stack"
            basis == "carrier_profile" -> "an OCD carrier capability, with no device record, tested on this stack"
            else -> "no OCD value"
        }
}

object OcdValues {
    fun answer(bundle: OcdBundle, sim: SimIdentity, feature: OwnerFeature, device: DeviceInfo): OcdAnswer {
        val most = Matcher.resolve(bundle, sim).mostSpecific ?: return OcdAnswer(null, "no_profile")
        val capability = most.capability(feature.capability)
        if (feature.deviceRecords) {
            val match = bundle.records?.recordsFor(most.profileId, feature.capability, device)?.firstOrNull()
            if (match != null) {
                return OcdAnswer(
                    value = when (match.record.state) {
                        "on" -> true
                        "off" -> false
                        else -> null
                    },
                    basis = "device_record",
                    profileId = most.profileId,
                    profileName = most.displayName,
                    capability = capability.wire,
                    recordSource = match.record.source,
                    recordStack = match.record.stack,
                    sameStack = match.sameStack,
                )
            }
        }
        return OcdAnswer(
            value = when (capability) {
                CapabilityValue.SUPPORTED -> true
                CapabilityValue.UNSUPPORTED -> false
                else -> null
            },
            basis = "carrier_profile",
            profileId = most.profileId,
            profileName = most.displayName,
            capability = capability.wire,
        )
    }
}

/**
 * The lab's device_capability.json rows the owner build needs: models whose
 * modem build has standalone 5G switched off, so VoNR cannot come up and its
 * on-test stops before any change. Source: the CP image G981BXXSNHYB2 prints
 * "PROJECT_OPT_NRSA : OFF" (research/config-gap-proof, 2026-09-30) and the
 * European CP rows of modem-evidence.csv (2026-10-02).
 */
object DeviceCapability {
    private val NO_VONR = setOf("SM-G981B", "SM-G986B", "SM-G988B", "SM-N981B")

    fun vonrNotCapable(model: String): Boolean = model.trim().uppercase() in NO_VONR
}
