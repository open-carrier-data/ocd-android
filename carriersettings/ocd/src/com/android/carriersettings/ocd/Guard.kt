/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.util.concurrent.TimeUnit

/** The call and emergency readings a reload or install needs (8.4). Null: unreadable, which refuses. */
data class PhoneState(
    val inCall: Boolean?,
    val inEmergencyCall: Boolean?,
    val ecbm: Boolean?,
    val emergencySms: Boolean?,
)

/** What a CARRIER_CONFIG_CHANGED asks the glue to do next. */
enum class GuardAction { NONE, RELOAD, LATER, TRIP }

/**
 * Kept from round 29 (LayerGuard, OcdLayerPhone): the timing refusals (8.4) and the crash
 * guard with the served-versus-effective check (8.3), with round 30's actions.
 */
object Guard {
    val WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(10)
    /** No install or reload within 60 minutes after an emergency call (round 30, kept by round 31). */
    val EMERGENCY_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(60)
    const val STUB_LIMIT = 2
    const val MISMATCH_LIMIT = 3
    val MISMATCH_WINDOW_MS: Long = TimeUnit.HOURS.toMillis(1)
    val NOTIFY_SPACING_MS: Long = TimeUnit.MINUTES.toMillis(2)
    const val MARKER = "+ocd"

    private fun within(atMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean = atMs > 0 && nowMs - atMs < windowMs && atMs - nowMs < windowMs

    /**
     * 8.4, as round 29's S6, with round 30's 60 minutes after an emergency call: no install or
     * reload during a call (ringing, active or held), in emergency callback or emergency SMS
     * mode, within 60 minutes of an emergency call (a call that could not be classified counts
     * as one), within 10 minutes of any other call, or when any of these cannot be read. The
     * answer is the `waiting_for` value, or null when it may run.
     */
    fun callRefusal(p: PhoneState, emergencySeenAtMs: Long, lastCallEndedAtMs: Long, nowMs: Long): String? =
        restoreRefusal(p, emergencySeenAtMs, nowMs) ?: when {
            within(lastCallEndedAtMs, nowMs) -> "call_ended_10min"
            else -> null
        }

    /**
     * The restore of the temporary emergency APN set waits only for a ringing, active or held
     * call, emergency callback or emergency SMS mode, and 60 minutes after an emergency call; the
     * 10 minutes after an ordinary call do not apply (round 31, defaultEmergencyApnTest).
     */
    fun restoreRefusal(p: PhoneState, emergencySeenAtMs: Long, nowMs: Long): String? = when {
        p.inEmergencyCall == null || p.ecbm == null || p.emergencySms == null || p.inCall == null -> "state_unreadable"
        p.inEmergencyCall -> "call"
        p.ecbm -> "ecbm"
        p.emergencySms -> "emergency_sms_mode"
        p.inCall -> "call"
        within(emergencySeenAtMs, nowMs, EMERGENCY_WINDOW_MS) -> "emergency_call_60min"
        else -> null
    }

    fun hasMarker(version: String?): Boolean = version != null && version.contains(MARKER)

    /**
     * A CARRIER_CONFIG_CHANGED for a loaded SIM [sub] with the effective version string
     * [effective]. [expected] is what CarrierSettings serves now (null: unknown).
     * - No `+ocd` in it (the empty stub, or a stale bundle from before round 30) after a load of
     *   ours, or a load that died: the first time a reload at once; the second in a row TRIP.
     * - Another `+ocd` version than expected (Telephony's restore from its cache at boot, a
     *   stale bundle): a reload, spaced by 2 minutes; three in an hour stop asking for more.
     */
    fun onConfigChanged(state: State, sub: Int, effective: String?, expected: String?, nowMs: Long): Pair<State, GuardAction> {
        val diedLoading = sub in state.loading
        var next = state.copy(loading = state.loading - sub)
        if (!hasMarker(effective)) {
            if (state.served[sub] == null && !diedLoading) {
                // Nothing of ours was served to this SIM yet (first boot, or a cleared storage): load it once.
                return next to spaced(next, sub, nowMs)
            }
            val stubs = state.stubLoads + 1
            next = next.copy(stubLoads = stubs)
            return next to if (stubs >= STUB_LIMIT) GuardAction.TRIP else GuardAction.RELOAD
        }
        next = next.copy(stubLoads = 0)
        val want = expected ?: state.served[sub]?.version ?: return next to GuardAction.NONE
        if (effective == want) return next.copy(mismatches = 0, proving = next.proving - sub) to GuardAction.NONE
        val fresh = nowMs - next.mismatchSinceMs > MISMATCH_WINDOW_MS || next.mismatches == 0 || nowMs < next.mismatchSinceMs
        val count = if (fresh) 1 else next.mismatches + 1
        next = next.copy(mismatches = count, mismatchSinceMs = if (fresh) nowMs else next.mismatchSinceMs)
        if (count > MISMATCH_LIMIT) return next to GuardAction.NONE
        return next to spaced(next, sub, nowMs)
    }

    private fun spaced(state: State, sub: Int, nowMs: Long): GuardAction {
        val last = state.lastNotifyMs[sub] ?: return GuardAction.RELOAD
        return if (nowMs - last >= NOTIFY_SPACING_MS || nowMs < last) GuardAction.RELOAD else GuardAction.LATER
    }

    /** The crash guard's new state when it trips while [servedDigest] was served (8.3). */
    fun trip(state: State, servedDigest: String, bakedDigest: String?, nowMs: Long): State {
        val onBuild = state.crashGuard != null || servedDigest == bakedDigest || !state.servingInstalled
        return state.copy(
            crashGuard = CrashGuard(state.crashGuard?.datasetDigest ?: servedDigest, nowMs, onBuild),
            pullAgain = true,
            active = "build",
            servingReason = "crash_guard",
            stubLoads = 0,
        )
    }

    // --- 8.1 the boot and daily check ----------------------------------------------------

    data class Target(val target: String, val reason: String, val dropInstalled: Boolean = false, val waitsForHome: Boolean = false)

    /**
     * The target of 8.1's table, as round 31 changes it. [writerOff] when the person disabled the
     * OCD app (or it is not there): the build serves, both parts, and the installed dataset goes.
     * [installedOk] is false when the installed dataset is unreadable or fails checks 3 to 8;
     * [bakedNewer] when the baked dataset is newer than the installed one (2.8); [roaming] null
     * when unreadable (it then waits too). No dataset installed, for any cause (first boot, a
     * clear, `clear_dataset`), reads `no_dataset`.
     */
    fun bootTarget(state: State, installedOk: Boolean, bakedNewer: Boolean, roaming: Boolean?, writerOff: Boolean = false): Target = when {
        writerOff -> Target("build", "app_disabled", dropInstalled = state.installed != null)
        state.voiceStop != null -> Target("build", "voice_loss_stop", dropInstalled = state.installed != null)
        state.crashGuard != null -> Target("build", "crash_guard")
        state.hold != null -> Target("build", "voice_loss_hold")
        state.installed == null -> Target("build", "no_dataset")
        !installedOk -> Target("build", "corrupt")
        bakedNewer -> if (roaming == false) Target("build", "build_pin_newer", dropInstalled = true) else Target("installed", "installed", waitsForHome = true)
        else -> Target("installed", "installed")
    }

    /** 2.8: X newer than Y when the digests differ and X was generated later; equal times favour the baked dataset. */
    fun newer(xDigest: String, xGeneratedAt: String, yDigest: String, yGeneratedAt: String, xIsBaked: Boolean): Boolean =
        xDigest != yDigest && (xGeneratedAt > yGeneratedAt || (xGeneratedAt == yGeneratedAt && xIsBaked))
}
