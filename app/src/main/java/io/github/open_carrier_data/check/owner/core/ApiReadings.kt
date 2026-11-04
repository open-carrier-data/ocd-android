package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.AospImsState
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsTransport

/*
 * What the privileged route reads through Android's APIs instead of a dump.
 * A privileged system app holds DUMP, but `dumpsys activity service
 * com.android.phone` goes through ActivityManager, which also wants
 * PACKAGE_USAGE_STATS ("Permission Denial: can't dump ActivityManager", the
 * S20 on 2026-10-19), and SELinux keeps priv_app from reading
 * ril.cdma.inecmmode (radio_cdma_ecm_prop). So the emergency state and the
 * IMS state come from the system APIs that READ_PRIVILEGED_PHONE_STATE and
 * MODIFY_PHONE_STATE open. Every field is null when its call failed; the
 * refusals and the evidence treat null as unreadable.
 */

/**
 * The emergency state from the system APIs (privileged route): TelephonyManager
 * getEmergencyCallbackMode for every active subscription, isInEmergencySmsMode,
 * and TelecomManager.isInEmergencyCall. The calls go to the services' binders
 * directly, so a failed call is null here and not the false the managers'
 * wrappers return on a RemoteException.
 */
data class EmergencyApi(
    val ecbm: Boolean?,
    val inEmergencyCall: Boolean?,
    val emergencySmsMode: Boolean?,
)

/**
 * One slot's IMS from ImsMmTelManager (privileged route): the registration
 * state and transport (the same ImsPhone fields the phone dump prints as
 * mImsMmTelRegistrationState and the registration log), the MmTel
 * capabilities (mMmTelCapabilities) and the user's three switches.
 */
data class ImsApi(
    /** ImsRegistrationImplBase / RegistrationManager: 0 not registered, 1 registering, 2 registered. */
    val registrationState: Int?,
    /** AccessNetworkConstants: 1 WWAN, 2 WLAN, -1 invalid. */
    val transportType: Int?,
    /** The MmTel capabilities now available; null when the capability callback did not answer. */
    val voice: Boolean?,
    val video: Boolean?,
    val sms: Boolean?,
    val volteByUser: Boolean? = null,
    val vtByUser: Boolean? = null,
    val wfcByUser: Boolean? = null,
) {
    /**
     * The same typed state the dump parser gives ([io.github.open_carrier_data.check.core.AospImsParser.parse]).
     * Registered without readable capabilities counts as unreadable: an off
     * trial must never read "service gone" from a capability it did not see.
     */
    fun state(): AospImsState {
        val capsRead = voice != null && video != null && sms != null
        val registered = when (registrationState) {
            REGISTERED -> if (capsRead) true else null
            NOT_REGISTERED, REGISTERING -> false
            else -> null
        }
        val transport = if (registered == true) {
            when (transportType) {
                TRANSPORT_WLAN -> ImsTransport.IWLAN
                TRANSPORT_WWAN -> ImsTransport.WWAN
                else -> null
            }
        } else {
            null
        }
        val services = if (registered == true) {
            listOfNotNull(
                ImsService.MMTEL.takeIf { voice == true },
                ImsService.MMTEL_VIDEO.takeIf { video == true },
                ImsService.SMSIP.takeIf { sms == true },
            ).sortedBy { it.wire }
        } else {
            emptyList()
        }
        return AospImsState(
            registered = registered,
            state = STATES[registrationState],
            logTransport = transport,
            services = services,
            lastDisconnect = null,
            wifiCallingByUser = wfcByUser,
            volteByUser = volteByUser,
        )
    }

    /** The user's switches; the platform flags have no public reader and stay null. */
    fun flags(): ImsFlags = ImsFlags(volteByUser = volteByUser, vtByUser = vtByUser, wfcByUser = wfcByUser)

    companion object {
        const val NOT_REGISTERED = 0
        const val REGISTERING = 1
        const val REGISTERED = 2
        const val TRANSPORT_WWAN = 1
        const val TRANSPORT_WLAN = 2
        private val STATES = mapOf(NOT_REGISTERED to "not_registered", REGISTERING to "registering", REGISTERED to "registered")
    }
}
