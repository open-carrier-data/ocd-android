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
    JUST_BOOTED("just_booted", "the phone started less than 10 minutes ago and the call watch cannot rule out an emergency call just before the restart"),
    SYSTEM_RESTARTED("system_restarted", "the phone's system restarted less than 10 minutes ago without a reboot and the call watch cannot rule out an emergency call just before it"),
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
    /**
     * How long the running system_server has run ([ServerClock]); a soft
     * restart clears the registry's emergency field as a reboot does, while
     * the elapsed clock runs on. Null: not known (a test without it).
     */
    val serverUpMs: Long? = null,
    /** The call watch's record, kept across restarts ([CallsSeen]); null: none, so a restart's first 10 minutes refuse as before. */
    val callsSeen: CallsSeen? = null,
)

/**
 * The call watch's record of calls, kept across restarts in device-protected storage
 * (PHONE_STATE, which the system sends for every call, an emergency call included, also
 * before the first unlock). It holds times only, never a number.
 */
@Serializable
data class CallsSeen(
    /** Since when the watch has run without a gap: its first record, or the app's last force stop or update. */
    val sinceMs: Long,
    /** The newest calls, oldest first. */
    val calls: List<CallSeen> = emptyList(),
) {
    companion object {
        const val MAX_CALLS = 12
    }
}

/** One call the watch saw, wall clock. [endMs] null: still up, or cut by a restart. */
@Serializable
data class CallSeen(
    val startMs: Long,
    val endMs: Long? = null,
    /** The app's own mailbox call. */
    val own: Boolean = false,
    /** Telecom called it an emergency call while the watch looked. */
    val emergency: Boolean = false,
    /** The system server it was seen under (boot ID and zygote), to tell a call cut by a restart. */
    val server: String = "",
)

/**
 * What a restart hides and the call watch shows (2026-10-25, "what can be done now should be
 * done now"): the registry's emergency-call field is cleared by a reboot or a soft restart, so
 * the builds before refused every change for a fixed 10 minutes after each. The reason stays,
 * as an event check: a restart refuses only when the watch saw a call end less than the window
 * before now and before the restart (it may have been an emergency call), or when the watch did
 * not cover that time. Calls since the restart are the registry's.
 */
object RestartWindow {
    /** True when the watch rules out a call that ended within [windowMs] before now and before the restart at [restartAtMs]. */
    fun clear(seen: CallsSeen?, restartAtMs: Long, nowMs: Long, windowMs: Long): Boolean {
        if (seen == null) return false
        // The watch must have run through the whole window; a clock that went back proves nothing.
        if (seen.sinceMs > nowMs - windowMs || seen.sinceMs > nowMs) return false
        for ((i, c) in seen.calls.withIndex()) {
            if (c.startMs > nowMs || (c.endMs ?: 0) > nowMs) return false
            if (c.own || c.startMs >= restartAtMs) continue
            // A call still open at the restart ended with it at the latest; a newer sighting is only added once it ended
            // (or its system server did), so an open one ended before the next began.
            val end = c.endMs ?: minOf(restartAtMs, seen.calls.getOrNull(i + 1)?.startMs ?: restartAtMs)
            if (nowMs - end < windowMs) return false
        }
        return true
    }

    /** When the newest emergency call the watch saw ended (or was last seen), any boot; null: none. */
    fun emergencyAtMs(seen: CallsSeen?): Long? = seen?.calls?.filter { it.emergency }?.maxOfOrNull { it.endMs ?: it.startMs }

    /** When the newest call the watch saw ended (or started, while open); [own] counts the app's mailbox calls too. */
    fun lastCallAtMs(seen: CallsSeen?, own: Boolean): Long? = seen?.calls?.filter { own || !it.own }?.maxOfOrNull { it.endMs ?: it.startMs }
}

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
 * clears the field, and so does a restart of the system server alone (a soft
 * restart: the elapsed clock runs on, [SafetyInputs.serverUpMs]); the first 10
 * minutes after either refuse only when the call watch cannot rule out a call
 * just before it ([RestartWindow]). A sighting the registry has since lost,
 * and an emergency call the watch saw, still count for their 10 minutes.
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
        // When the registry's field was last cleared: the boot, or the system server's own start.
        val serverAt = input.nowMs - minOf(input.uptimeMs, input.serverUpMs ?: input.uptimeMs)
        val mark = emergency.outgoingMark
        var seen = input.seen
        // A field set now is a call after the restart; a sighting from before it does not date that call.
        if (seen != null && mark != null && seen.firstSeenAtMs < serverAt) seen = null
        // The field gone (a restart cleared it): the sighting counts until its 10 minutes are over.
        if (seen != null && mark == null && input.nowMs - seen.firstSeenAtMs >= EMERGENCY_WINDOW_MS) seen = null
        if (mark != null && seen?.mark != mark) seen = EmergencySeen(mark, input.nowMs)
        val roaming = RoamingParser.parse(RegistryText.block(registry, input.slot))
        // The time before the restart, as the call watch saw it (any call then may have been an emergency call).
        val beforeRestartClear = RestartWindow.clear(input.callsSeen, serverAt, input.nowMs, EMERGENCY_WINDOW_MS)
        val watchedEmergency = RestartWindow.emergencyAtMs(input.callsSeen)
        val refusal = when {
            emergency.inEmergencyCall == null || emergency.ecbm == null || emergency.outgoingEmergencyCallSinceBoot == null ->
                Refusal.EMERGENCY_STATE_UNREADABLE
            // The API always answers the SMS mode; an older dump may lack the line, so only the API route needs it.
            api != null && emergency.emergencySmsMode == null -> Refusal.EMERGENCY_STATE_UNREADABLE
            emergency.inEmergencyCall -> Refusal.EMERGENCY_CALL_ACTIVE
            emergency.ecbm -> Refusal.EMERGENCY_CALLBACK_MODE
            emergency.emergencySmsMode == true -> Refusal.EMERGENCY_SMS_MODE
            seen != null && input.nowMs - seen.firstSeenAtMs < EMERGENCY_WINDOW_MS -> Refusal.EMERGENCY_CALL_RECENT
            watchedEmergency != null && input.nowMs - watchedEmergency < EMERGENCY_WINDOW_MS -> Refusal.EMERGENCY_CALL_RECENT
            input.uptimeMs < EMERGENCY_WINDOW_MS && !beforeRestartClear -> Refusal.JUST_BOOTED
            input.serverUpMs != null && input.serverUpMs < EMERGENCY_WINDOW_MS && !beforeRestartClear -> Refusal.SYSTEM_RESTARTED
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
