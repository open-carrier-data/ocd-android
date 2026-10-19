package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * The guided switch step (OCD round 23, AP3). Where the phone has VoLTE or
 * Wi-Fi calling off and OCD's value for this phone is on, the app shows
 * OCD's value next to the phone's, with its source and the stack note, and
 * offers to open the right Settings screen. The person flips their own
 * switch; the app never does, and it never suggests turning a voice feature
 * off. Back in the app it reads the phone again, passively, and offers the
 * call test. The step is kept on the phone as `user_toggle`.
 */

/**
 * OCD's on or off for one feature on this phone, picked as OCD Lab's
 * `ocd_value` picks it: this model's device record on the most specific
 * profile (one for the phone's own software first), else the carrier
 * summary, where supported is on and unsupported off.
 */
data class OcdFeatureValue(
    val feature: T2Feature,
    /** null when OCD gives no single value (conditional or unknown, or a record whose files disagree). */
    val on: Boolean?,
    val summary: CapabilityValue,
    val profile: OcdProfile?,
    /** The device record the value comes from; null when it comes from the carrier summary. */
    val record: DeviceMatch?,
) {
    val basis: String
        get() = when {
            record != null -> "device_record"
            profile != null -> "carrier_summary"
            else -> "no_profile"
        }

    /** The record holds for the software this phone runs; null without a record (lab `same_stack`). */
    val sameStack: Boolean? get() = record?.sameStack

    /** OCD's value with its source, in plain words. */
    val sourceLine: String
        get() {
            val value = when (on) {
                true -> "on"
                false -> "off"
                null -> "no single value"
            }
            val profileName = profile?.let { "${it.displayName} (${it.profileId})" } ?: "no OCD profile"
            return if (record != null) {
                "$value: ${record.whose.replaceFirstChar(Char::lowercase)} (${record.sourceLine}), on OCD's profile " +
                    "$profileName. OCD's carrier summary there is ${summary.wire}."
            } else {
                "$value: OCD's carrier summary is ${summary.wire} on $profileName" +
                    (profile?.newestEntry?.let { ", newest source entry $it" } ?: "") + "."
            }
        }

    /**
     * Round 18: a stock record on a LineageOS build is this model's stock
     * firmware, never this phone's; a carrier summary is no file of this model.
     */
    fun stackNote(device: DeviceInfo): String {
        val match = record
        return when {
            match == null -> "OCD publishes no file of this phone model for this SIM, so this is the carrier summary over " +
                "many phone models' carrier tables, not this phone's own value."
            match.sameStack -> "This phone model's own file, for the software this phone runs (${device.romFamily.plain}). " +
                "Configuration, not a test."
            else -> "This value is from this model's ${DeviceMatch.stackPlain(match.record.stack)} file. This phone runs " +
                "${device.romFamily.plain}: that file says what the maker's own IMS is set to, not what this phone's " +
                "IMS can do, emergency calls included. Configuration, not a test."
        }
    }

    companion object {
        fun of(feature: T2Feature, bundle: OcdBundle, resolution: Resolution?, device: DeviceInfo): OcdFeatureValue {
            val profile = resolution?.mostSpecific
            val summary = resolution?.answer(feature.capability) ?: CapabilityValue.UNKNOWN
            if (profile == null) return OcdFeatureValue(feature, null, summary, null, null)
            val record = bundle.records?.recordsFor(profile.profileId, feature.capability, device)?.firstOrNull()
            if (record != null) {
                val on = when (record.record.state) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
                return OcdFeatureValue(feature, on, summary, profile, record)
            }
            val on = when (summary) {
                CapabilityValue.SUPPORTED -> true
                CapabilityValue.UNSUPPORTED -> false
                else -> null
            }
            return OcdFeatureValue(feature, on, summary, profile, null)
        }
    }
}

/** A user switch as the deep check read it. */
@Serializable
enum class UserSwitch(val wire: String, val plain: String) {
    ON("on", "on"),
    OFF("off", "off"),
    NOT_READ("not_read", "not read"),
}

/**
 * The phone's side of one feature: the carrier setting a normal app reads,
 * and the person's own switch where the deep check reads it.
 */
@Serializable
data class PhoneSwitches(
    /**
     * The CarrierConfig key (carrier_volte_available_bool,
     * carrier_wfc_ims_available_bool); null when not read, and on Samsung's
     * own software, whose IMS uses Samsung's tables (round 18: not compared).
     */
    val carrierSetting: Boolean? = null,
    /** The person's own switch: Samsung's Wi-Fi calling switch, or Android's VoLTE and Wi-Fi calling switches on Android's IMS. */
    val userSwitch: UserSwitch = UserSwitch.NOT_READ,
) {
    val anyOff: Boolean get() = carrierSetting == false || userSwitch == UserSwitch.OFF

    companion object {
        fun of(feature: T2Feature, readings: PhoneReadings): PhoneSwitches {
            val key = if (feature == T2Feature.VOLTE) "carrier_volte_available_bool" else "carrier_wfc_ims_available_bool"
            val setting = if (SwitchSteps.samsungStock(readings.device)) null else readings.carrierSettings?.takeIf { it.readable }?.booleans?.get(key)
            val deep = readings.deep
            val user = when (feature) {
                T2Feature.VOLTE -> when (deep?.volteByUser) {
                    true -> UserSwitch.ON
                    false -> UserSwitch.OFF
                    null -> UserSwitch.NOT_READ
                }
                T2Feature.VOWIFI -> when (deep?.wifiCallingSwitch) {
                    WifiCallingSwitch.ON -> UserSwitch.ON
                    WifiCallingSwitch.OFF -> UserSwitch.OFF
                    else -> UserSwitch.NOT_READ
                }
            }
            return PhoneSwitches(setting, user)
        }
    }
}

/** Where the app sends the person, by public Android intent actions only; the phone layer turns these into intents. */
enum class SettingsScreen(val wire: String, val plain: String) {
    /** Settings.ACTION_NETWORK_OPERATOR_SETTINGS with the SIM's subscription (AOSP 9 and later: the SIM's mobile network page). */
    SIM_NETWORK("sim_network", "this SIM's mobile network page"),

    /** TelecomManager.ACTION_SHOW_CALL_SETTINGS: the phone app's call settings, where Samsung keeps its Wi-Fi calling switch. */
    CALL_SETTINGS("call_settings", "the phone app's call settings"),

    /** Settings.ACTION_WIRELESS_SETTINGS: the general network settings. */
    NETWORK("network", "the network settings"),
}

object SwitchSteps {
    /**
     * The screens to try for [feature], in order; the last is the general
     * network settings. On AOSP 16 the SIM's mobile network page holds the
     * VoLTE switch ("VoLTE", "4G calling" or "Advanced calling") and, under
     * Calling, Wi-Fi calling (checked on the S20, LineageOS 23.2). One UI
     * keeps Wi-Fi calling in the phone app's call settings; not yet checked
     * on a Samsung phone. On Samsung's own software only the Wi-Fi calling
     * step is offered (see [PhoneSwitches]).
     */
    fun screens(feature: T2Feature, device: DeviceInfo): List<SettingsScreen> =
        if (samsungStock(device) && feature == T2Feature.VOWIFI) {
            listOf(SettingsScreen.CALL_SETTINGS, SettingsScreen.NETWORK)
        } else {
            listOf(SettingsScreen.SIM_NETWORK, SettingsScreen.NETWORK)
        }

    /** What to look for on the screen that opens. */
    fun where(feature: T2Feature, device: DeviceInfo): String = when {
        samsungStock(device) && feature == T2Feature.VOWIFI -> "In the phone app's settings, look for \"Wi-Fi calling\"."
        feature == T2Feature.VOLTE -> "On this SIM's page, look for \"VoLTE\", \"4G calling\" or \"Advanced calling\"."
        else -> "On this SIM's page, look under Calling for \"Wi-Fi calling\"."
    }

    /** Samsung's own software: Samsung's IMS uses its own tables, not Android's carrier settings. */
    fun samsungStock(device: DeviceInfo): Boolean =
        device.manufacturer.equals("samsung", ignoreCase = true) && device.romFamily == RomFamily.STOCK

    /**
     * The steps to offer: VoLTE and Wi-Fi calling where OCD's value for this
     * phone is on and the phone has the feature off, in its carrier setting
     * or in the person's switch. Never the other way round: the app never
     * suggests turning a voice feature off.
     */
    fun offered(readings: PhoneReadings, resolution: Resolution?, bundle: OcdBundle): List<SwitchStep> =
        T2Feature.entries.mapNotNull { feature ->
            val ocd = OcdFeatureValue.of(feature, bundle, resolution, readings.device)
            val phone = PhoneSwitches.of(feature, readings)
            SwitchStep(feature, ocd, phone).takeIf { ocd.on == true && phone.anyOff }
        }

    /**
     * OCD's docs/consume.md, "Emergency calls are not tested" (round 10), as
     * it applies to a person turning a voice switch on.
     */
    const val EMERGENCY_NOTE =
        "Emergency calls are not tested. No OCD value has been tested for emergency calls, and this app never places " +
            "one. Switching VoLTE or Wi-Fi calling on also changes how the phone places emergency calls."
}

/** One offered step: OCD's value and the phone's, for one feature. */
data class SwitchStep(val feature: T2Feature, val ocd: OcdFeatureValue, val phone: PhoneSwitches) {
    /** The phone's side in plain words. */
    fun phoneLine(device: DeviceInfo): String = buildList {
        val key = if (feature == T2Feature.VOLTE) "carrier_volte_available_bool" else "carrier_wfc_ims_available_bool"
        when (phone.carrierSetting) {
            true -> add("the carrier settings have it on ($key = true)")
            false -> add("the carrier settings have it off ($key = false)")
            null -> {}
        }
        val whose = if (SwitchSteps.samsungStock(device)) "Samsung's" else "your"
        if (phone.userSwitch != UserSwitch.NOT_READ) add("$whose ${feature.label} switch is ${phone.userSwitch.plain}")
    }.joinToString("; ").replaceFirstChar(Char::uppercase) + "."

    /** What the person can do; never a suggestion to turn anything off. */
    val todo: String
        get() = "If you want ${feature.label}, open Settings and turn it on yourself. This app changes nothing. " +
            if (phone.carrierSetting == false) {
                "With the carrier setting off, Settings may show no ${feature.label} switch for this SIM; this app cannot change that."
            } else {
                "If Settings shows no such switch, tell the app below."
            }
}

/** What the person said after Settings. */
@Serializable
enum class ToggleAnswer(val wire: String, val plain: String) {
    TURNED_ON("turned_on", "you turned it on"),
    NOT_CHANGED("not_changed", "you did not change it"),
    NO_SWITCH("no_switch", "Settings showed no such switch"),
}

/**
 * One guided switch step, kept in the app's private storage (round 23 AP3:
 * the ledger records `user_toggle`). It holds OCD's value and where it came
 * from, the phone's readings before Settings and after, and the person's
 * answer; no number, time of day or location.
 */
@Serializable
data class UserToggleRecord(
    val date: String,
    val model: String,
    val codename: String,
    val romFamily: String,
    val androidMajor: Int,
    val homeMccMnc: String?,
    val spn: String?,
    val ocdProfile: String?,
    val feature: T2Feature,
    /** on: the step is offered only where OCD's value is on. */
    val ocdValue: String,
    /** device_record or carrier_summary (lab `basis`). */
    val ocdBasis: String,
    val ocdSource: String?,
    val ocdStack: String?,
    val sameStack: Boolean?,
    val screen: String,
    val before: PhoneSwitches,
    val after: PhoneSwitches?,
    val answer: ToggleAnswer,
    /** user_toggle when the person turned the feature on; as_found otherwise. */
    val configOrigin: ConfigOrigin,
) {
    val plain: String
        get() = buildString {
            append("${feature.label}: ").append(answer.plain).append(" in ").append(screenPlain(screen))
            after?.let { now ->
                val changes = buildList {
                    if (before.carrierSetting != now.carrierSetting && now.carrierSetting != null) {
                        add("carrier setting now ${if (now.carrierSetting) "on" else "off"}")
                    }
                    if (before.userSwitch != now.userSwitch && now.userSwitch != UserSwitch.NOT_READ) {
                        add("switch now ${now.userSwitch.plain}")
                    }
                }
                append(if (changes.isEmpty()) "; the app's re-read shows no change" else "; re-read: ${changes.joinToString(", ")}")
            }
            append('.')
        }

    companion object {
        fun screenPlain(wire: String): String = SettingsScreen.entries.firstOrNull { it.wire == wire }?.plain ?: wire
    }
}
