package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.SimIdentity

/**
 * How the owner build reaches the phone. Two routes behind this one
 * interface, with the same safety floor (the owner, 2026-10-19 evening):
 *
 * - [Route.PRIVILEGED]: the app is a privileged system app (priv-app with the
 *   shipped privapp-permissions allowlist). CarrierConfigManager.overrideConfig
 *   with persistent false, and the telephony provider directly. No root.
 * - [Route.SHIZUKU_ROOT]: Shizuku's server runs as root and starts the owner
 *   service, which runs `cmd phone cc` (never `-p`) and `content`.
 *
 * Every write goes through [OwnerSession], which checks the key guard, the
 * refusals and the stop flag first, records the change before it runs, and
 * proves every revert.
 */
interface OwnerPhone {
    val route: Route

    /** `dumpsys carrier_config`: kept in memory for the revert proof, never stored. */
    suspend fun carrierConfigText(): String?
    suspend fun registryText(): String?
    suspend fun phoneText(): String?

    /** `getprop ril.cdma.inecmmode` (emergency callback mode). */
    suspend fun ecmProperty(): String?

    /** The IWLAN tags of one slot, dumped from logcat, never cleared. */
    suspend fun iwlanLogText(slot: Int): String?

    /** A non-persistent override of one key. Implementations check [KeyGuard] again. */
    suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult

    /** Clears the slot's overrides. */
    suspend fun ccClear(slot: Int, subId: Int): CcResult

    suspend fun apnQuery(numeric: String): List<PhoneApnRow>?

    /** Rows whose name carries [ApnColumns.MARKER], for every network code. */
    suspend fun apnQueryMarked(): List<PhoneApnRow>?
    suspend fun apnInsert(values: Map<String, String>): Boolean
    suspend fun apnDelete(id: Long): Boolean
    suspend fun preferredApn(subId: Int): PreferredApn

    /** null removes the preference, so Android picks again. */
    suspend fun setPreferredApn(subId: Int, id: Long?): Boolean

    suspend fun mobileData(subId: Int): MobileData
    suspend fun wifiConnected(): Boolean?

    fun nowMs(): Long
    fun uptimeMs(): Long
    suspend fun sleep(ms: Long)
}

enum class Route(val wire: String, val plain: String) {
    PRIVILEGED("privileged", "privileged system app"),
    SHIZUKU_ROOT("shizuku_root", "Shizuku as root"),
}

/** Mobile data for one subscription: switched on by the user, and validated (Android reached the internet over it). */
data class MobileData(val enabled: Boolean?, val validated: Boolean?)

/** One active SIM. */
data class OwnerSim(val slot: Int, val subId: Int, val identity: SimIdentity, val name: String, val carrierId: Long?) {
    val key: SimKey get() = SimKey(subId, identity.mccmnc, carrierId)
}
