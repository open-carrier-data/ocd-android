package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable

/** Why the owner build changes nothing right now. Every refusal fails closed: a state it cannot read refuses too. */
@Serializable
enum class Refusal(val wire: String, val plain: String) {
    STOPPED("stopped", "the stop flag is set after a stop event; the write path stays off until you clear it"),
    NOT_OWNER_PHONE("not_owner_phone", "this is not the owner's S20 build"),
    NO_ACCESS("no_access", "neither the privileged system-app route nor Shizuku as root is available"),
    NO_SIM("no_sim", "no SIM, nothing to test"),
    EMERGENCY_STATE_UNREADABLE("emergency_state_unreadable", "the emergency state could not be read"),
    EMERGENCY_CALL_ACTIVE("emergency_call_active", "an emergency call is up"),
    EMERGENCY_CALLBACK_MODE("emergency_callback_mode", "the phone is in emergency callback mode"),
    EMERGENCY_SMS_MODE("emergency_sms_mode", "the phone is in emergency SMS callback mode"),
    EMERGENCY_CALL_RECENT("emergency_call_recent", "an emergency call was seen less than 10 minutes ago"),
    JUST_BOOTED("just_booted", "the phone started less than 10 minutes ago; an emergency call before the restart cannot be ruled out"),
    CALL_ACTIVE("call_active", "a call is up or the call state cannot be read"),
    ROAMING("roaming", "the phone is roaming"),
    ROAMING_UNREADABLE("roaming_unreadable", "whether the phone is roaming could not be read"),
    FOREIGN_OVERRIDES("foreign_overrides", "the slot holds overrides the owner build did not set"),
    CONFIG_UNREADABLE("config_unreadable", "the carrier config could not be read"),
}

/** When the owner build first saw the registry's emergency-call field set, wall clock. */
@Serializable
data class EmergencySeen(val mark: Int, val firstSeenAtMs: Long)

/** The readings the refusals take, all from one pass. */
data class SafetyInputs(
    val registry: String?,
    val phoneDump: String?,
    val ecmProperty: String?,
    val slot: Int,
    val homeMccMnc: String?,
    val nowMs: Long,
    val uptimeMs: Long,
    val seen: EmergencySeen?,
    /** The privileged route's emergency state from the system APIs, in place of the phone dump; null on the Shizuku route. */
    val emergencyApi: EmergencyApi? = null,
)

data class SafetyVerdict(
    val refusal: Refusal?,
    val emergency: EmergencyReading?,
    val roaming: RoamingReading?,
    /** The emergency sighting to store for the next check. */
    val seen: EmergencySeen?,
)

/**
 * The owner's refusals (2026-10-19): nothing changes in a call, in emergency
 * callback mode, within 10 minutes of an emergency call, while roaming, or
 * when one of these cannot be read (round 25 LB6, failing closed).
 *
 * The registry keeps no time for an emergency call: its
 * mOutgoingCallEmergencyNumber stays set until the system server restarts.
 * The first time the owner build sees it set is at or after the call, so 10
 * minutes from that sighting are at least 10 minutes from the call. A reboot
 * clears the field, so the first 10 minutes after boot refuse too.
 */
object Refusals {
    const val EMERGENCY_WINDOW_MS = 10 * 60 * 1000L

    fun check(input: SafetyInputs): SafetyVerdict {
        val registry = input.registry
        val phone = input.phoneDump
        val api = input.emergencyApi
        if (registry.isNullOrBlank() || (phone.isNullOrBlank() && api == null)) {
            return SafetyVerdict(Refusal.EMERGENCY_STATE_UNREADABLE, null, null, input.seen)
        }
        val emergency = EmergencyParser.parse(registry, phone, input.ecmProperty, api)
        val bootAt = input.nowMs - input.uptimeMs
        var seen = input.seen?.takeIf { it.firstSeenAtMs >= bootAt }
        val mark = emergency.outgoingMark
        if (mark != null && seen?.mark != mark) seen = EmergencySeen(mark, input.nowMs)
        if (mark == null && emergency.outgoingEmergencyCallSinceBoot == false) seen = null
        val roaming = RoamingParser.parse(RegistryText.block(registry, input.slot))
        val refusal = when {
            emergency.inEmergencyCall == null || emergency.ecbm == null || emergency.outgoingEmergencyCallSinceBoot == null ->
                Refusal.EMERGENCY_STATE_UNREADABLE
            // The API always answers the SMS mode; an older dump may lack the line, so only the API route needs it.
            api != null && emergency.emergencySmsMode == null -> Refusal.EMERGENCY_STATE_UNREADABLE
            emergency.inEmergencyCall -> Refusal.EMERGENCY_CALL_ACTIVE
            emergency.ecbm -> Refusal.EMERGENCY_CALLBACK_MODE
            emergency.emergencySmsMode == true -> Refusal.EMERGENCY_SMS_MODE
            seen != null && input.nowMs - seen.firstSeenAtMs < EMERGENCY_WINDOW_MS -> Refusal.EMERGENCY_CALL_RECENT
            input.uptimeMs < EMERGENCY_WINDOW_MS -> Refusal.JUST_BOOTED
            RegistryText.anyCallActive(registry) -> Refusal.CALL_ACTIVE
            input.homeMccMnc == null -> Refusal.NO_SIM
            roaming.roaming == null -> Refusal.ROAMING_UNREADABLE
            roaming.roaming -> Refusal.ROAMING
            roaming.servingPlmn != null && roaming.servingPlmn.take(3) != input.homeMccMnc.take(3) -> Refusal.ROAMING
            else -> null
        }
        return SafetyVerdict(refusal, emergency, roaming, seen)
    }

    /** Which inputs left the emergency state unreadable, for the log. Names only, no values. */
    fun unreadable(input: SafetyInputs, verdict: SafetyVerdict): List<String> = buildList {
        val api = input.emergencyApi
        if (input.registry.isNullOrBlank()) add("registry")
        if (input.phoneDump.isNullOrBlank() && api == null) add("phone dump and system API")
        val e = verdict.emergency ?: return@buildList
        if (e.ecbm == null) add("emergency callback mode")
        if (e.inEmergencyCall == null) add("emergency call")
        if (api != null && e.emergencySmsMode == null) add("emergency SMS mode")
        if (e.outgoingEmergencyCallSinceBoot == null) add("registry emergency number")
    }
}
