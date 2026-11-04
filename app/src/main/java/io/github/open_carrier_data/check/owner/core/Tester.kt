package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * OCD rule round 47: what runs by itself, the same on every phone.
 *
 * Automatic: passive reads, IMS registration state, watching real calls and SMS, the failure-only data
 * and DNS probes, dataset installs, and repair after a measured failure. Nothing automatic rings, shows,
 * leaves a trace or can cost money. On the person's tap only: a test call, a test SMS, the balance USSD,
 * Test now's trials, the voice-loss hold's split and the MMS APN candidates. The emergency data test
 * never runs by itself and has no tap: only a ROM builder's manual first use over adb (SM10).
 *
 * No phone declares a tester, so SM7 applies on every phone. A healthy phone gets no trials: a trial can
 * lose a real incoming call and gains only "still registered". An automatic result stops at signed_in;
 * only a real or tapped call can say works.
 *
 * Active verdicts count only on an IMS stack that one calls-out pass has calibrated (SM8); today that is
 * AOSP ImsStack. On any other stack results read "not counted": no candidates, no records, only passive
 * evidence.
 */

/** A tester declaration sent to CarrierSettings (R31.5): on or off, and when. */
@Serializable
data class TesterDeclaration(val on: Boolean, val atMs: Long)

/** What may start by itself on this phone (round 47, SM8). Pure. */
data class TesterPolicy(
    /** OcdCarrierSettings is on this phone and speaks round 31 ([DatasetStatus.round31]). */
    val provider: Boolean = false,
    /** The phone's IMS stack, as [ImsStack] names it. */
    val stackId: String? = null,
) {
    /** Why a setting trial may not start here, or null: the provider, then the calibrated stack (SM8). */
    val trialRefusal: TrialRefusal?
        get() = when {
            !provider -> TrialRefusal(NO_PROVIDER, "this phone has no OcdCarrierSettings that speaks round 31; only the passive checks run")
            else -> StackCalibration.notCounted(stackId)?.let { TrialRefusal(NOT_CALIBRATED, it) }
        }

    companion object {
        const val NOT_CALIBRATED = "stack_not_calibrated"
        const val NO_PROVIDER = "no_round31_provider"
    }
}

/** A refusal to start a setting trial: its [code] and [plain] words for the log and the screen. */
data class TrialRefusal(val code: String, val plain: String)

/** SM8: the IMS stacks whose counters one calls-out pass has confirmed against the stack's own logs. */
object StackCalibration {
    /** Today AOSP ImsStack (the owner's S20, 2026-10-22 onwards). */
    val CALIBRATED = setOf("aosp_imsstack")

    fun counted(stackId: String?): Boolean = stackId in CALIBRATED

    /** The plain reason a result is not counted on [stackId], or null when it counts. */
    fun notCounted(stackId: String?): String? =
        if (counted(stackId)) null else "not counted: the tests are not calibrated on the IMS stack ${stackId?.ifEmpty { null } ?: "this phone runs"}; only passive evidence counts"
}

/**
 * Round 47: the static line on the first screen, what the app does without being asked. Round 48: it says what the app
 * does on this install, the send included wherever it can happen. A hand install sends nothing by itself, and neither
 * does a ROM whose builder turned the question off, so those keep [LINE].
 */
object OnItsOwn {
    const val LINE = "On its own, this app only reads the phone's state, installs new carrier data and repairs a failure it measured. " +
        "It makes no calls and sends no SMS unless you tap for it."
    const val ROM_LINE = "On its own, this app only reads the phone's state, installs new carrier data, repairs a failure it measured and, if you said yes, " +
        "sends OCD one weekly summary. It makes no calls and sends no SMS unless you tap for it."

    fun line(privileged: Boolean, switch: WeekSwitch): String = if (privileged && switch == WeekSwitch.ASK) ROM_LINE else LINE
}

/**
 * Round 47: the kinds SM7 holds back, which an automatic trial never applies: VoLTE, Wi-Fi calling or SMS over IMS off,
 * provisioning that turns voice off, a Wi-Fi-only mode, and values that hide or lock a switch. With no dialled test to
 * catch it, such a value can remove calls, including the path to 112, on a network without 2G or 3G.
 */
object Sm7Kinds {
    private val WFC_MODE = setOf("carrier_default_wfc_ims_mode_int", "carrier_default_wfc_ims_roaming_mode_int")
    private const val WIFI_ONLY = 0

    /** CarrierSettings's own `sm7Kind`, read from a JSON value. */
    fun held(key: String, value: JsonElement?): Boolean {
        val primitive = value as? JsonPrimitive
        return when {
            key in Groups.X5_OFF -> primitive?.booleanOrNull == Groups.X5_OFF.getValue(key)
            key in WFC_MODE -> primitive?.intOrNull == WIFI_ONLY
            key == "carrier_allow_turnoff_ims_bool" || key == "allow_adding_apns_bool" -> primitive?.booleanOrNull == false
            key.startsWith("editable_") -> primitive?.booleanOrNull == false
            key.startsWith("hide_") -> primitive?.booleanOrNull == true
            key == "read_only_apn_types_string_array" || key == "read_only_apn_fields_string_array" -> (value as? JsonArray)?.isNotEmpty() == true
            else -> false
        }
    }
}
