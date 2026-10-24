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
 * proves every revert. Neither route reads the device log or switches Wi-Fi
 * (2026-10-20).
 */
interface OwnerPhone {
    val route: Route

    /** `dumpsys carrier_config`: kept in memory for the revert proof, never stored. */
    suspend fun carrierConfigText(): String?
    suspend fun registryText(): String?

    /** `dumpsys activity service com.android.phone`; null on the privileged route, which reads [emergencyApi] and [imsApi] instead. */
    suspend fun phoneText(): String?

    /** `getprop ril.cdma.inecmmode` (emergency callback mode). */
    suspend fun ecmProperty(): String?

    /**
     * The emergency state from the system APIs, where the route cannot read
     * the phone service's dump ([phoneText] null on the privileged route).
     * Null: this route reads it from the dump.
     */
    suspend fun emergencyApi(): EmergencyApi? = null

    /** One slot's IMS from ImsMmTelManager, the privileged route's stand-in for [phoneText]. Null: this route reads the dump. */
    suspend fun imsApi(slot: Int, subId: Int): ImsApi? = null

    /**
     * The IWLAN data service's dump and QNS's dump (`dumpsys activity
     * service`; the privileged route needs DUMP and PACKAGE_USAGE_STATS), in
     * place of the device log ([IwlanDumps]). Null: unreadable.
     */
    suspend fun iwlanServiceText(): String? = null
    suspend fun qnsText(): String? = null

    /** `dumpsys isms`, for how an SMS came in ([InboundSms]); null when the route cannot read it. */
    suspend fun smsText(): String? = null

    /** `dumpsys power`, for how long the screen has been off ([PowerDump]); null when the route cannot read it. */
    suspend fun powerText(): String? = null

    /** When the running system_server started ([ServerClock]); null when the route cannot tell. */
    suspend fun serverStart(): ServerStart? = null

    /**
     * Starts watching the IMS registrations of one subscription (the
     * registration callback): a fresh REGISTER after a change is the stronger
     * result. Null when the route cannot. Close it after the measurement.
     */
    suspend fun watchRegistrations(subId: Int): RegistrationWatch? = null

    /** A non-persistent override of one key. Implementations check [KeyGuard] again. */
    suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult

    /** Clears the slot's overrides. */
    suspend fun ccClear(slot: Int, subId: Int): CcResult

    suspend fun apnQuery(numeric: String): List<PhoneApnRow>?

    /** Rows whose name carries [ApnColumns.MARKER], for every network code. Read only: the owner build never deletes by marker. */
    suspend fun apnQueryMarked(): List<PhoneApnRow>?

    /** One row by its own ID; null when it is gone (or unreadable). */
    suspend fun apnRowById(id: Long): PhoneApnRow?

    /**
     * IDs of the rows an insert of [values] would be merged into instead of
     * added ([ApnColumns.conflictMatch]); empty when it would add a row, null
     * when the table could not be read.
     */
    suspend fun apnConflicts(values: Map<String, String>): List<Long>?

    /** Inserts one row; its new ID, or null when the provider added none (a stop for the runner). */
    suspend fun apnInsert(values: Map<String, String>): Long?
    suspend fun apnDelete(id: Long): Boolean

    /** A hash over every visible APN row (journal before a trial, proof after its revert); null when unreadable. */
    suspend fun apnTableHash(): String?
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
