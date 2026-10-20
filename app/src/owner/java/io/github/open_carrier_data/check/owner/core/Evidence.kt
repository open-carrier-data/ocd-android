package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.ImsReading
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.IwlanReading
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.NrVops
import kotlinx.serialization.Serializable

/** One read of the phone for one slot, typed: what the evidence ladder takes. */
@Serializable
data class Observation(
    /** The five switches and carrier_nr_availabilities_int_array, as the effective config gives them. */
    val config: Map<String, String>,
    val ims: ImsReading,
    val flags: ImsFlags,
    val ps: PsRegistration?,
    val lteVops: LteVops?,
    val nrVops: NrVops?,
    val wifiConnected: Boolean?,
    val iwlan: IwlanReading?,
    val callActive: Boolean,
    /** TelephonyManager network type of the call, while one is up. */
    val callNetworkType: Int? = null,
    /**
     * The IMS registration itself is on the bearer its data connection is on
     * (the API's live registration transport; the phone dump's registration
     * log is history, so the dump route counts as agreeing). After a handover
     * PHH can stay registered over the old bearer for half a minute.
     */
    val registrationOnPdn: Boolean = true,
) {
    fun switch(feature: OwnerFeature): Boolean? = config[feature.key].asBool()

    val onLte: Boolean get() = ps != null && ps.rat in setOf("lte", "lte_ca") && ps.state in setOf("home", "roaming")
    val onNr: Boolean get() = ps != null && ps.rat == "nr" && ps.state in setOf("home", "roaming")

    fun registeredWith(service: ImsService): Boolean? = when (ims.registered) {
        null -> null
        false -> false
        true -> service in ims.services
    }
}

@Serializable
enum class Outcome { REGISTERED, SERVICE_OFF, REVERT, WAIT }

@Serializable
data class Verdict(val outcome: Outcome, val reason: String)

/**
 * The evidence ladder and the decision after a change (lab `ladder` and
 * `decide`), reduced to what the owner build reads. Turning a feature on
 * reaches REGISTERED when IMS registered with its service over its bearer
 * (T1: "registered with this value"). Turning it off reaches SERVICE_OFF when
 * that service is gone; whether voice stays is a separate, measured check.
 */
object Evidence {
    /** The feature's last rung: registered with its service on its bearer. */
    fun working(feature: OwnerFeature, o: Observation): Boolean? {
        val registered = o.ims.registered ?: return null
        if (!registered) return false
        val transport = o.ims.transport
        return when (feature) {
            OwnerFeature.VOLTE -> ImsService.MMTEL in o.ims.services && transport != null && !transport.isWifi
            OwnerFeature.SMS_OVER_IMS -> ImsService.SMSIP in o.ims.services
            OwnerFeature.VIDEO_CALLING -> ImsService.MMTEL_VIDEO in o.ims.services
            OwnerFeature.VOWIFI -> ImsService.MMTEL in o.ims.services && transport == ImsTransport.IWLAN
            OwnerFeature.VONR -> ImsService.MMTEL in o.ims.services && (transport == ImsTransport.NR || (transport?.isWifi == false && o.onNr))
        }
    }

    /** The first rung that blocks an on before IMS can register; a blocked on is reverted at once. */
    fun blocker(feature: OwnerFeature, o: Observation): String? = when (feature) {
        OwnerFeature.VOLTE -> when {
            o.onLte && o.lteVops == LteVops.NOT_SUPPORTED -> "network_offers"
            o.flags.volteByUser == false -> "user_switch"
            else -> null
        }
        OwnerFeature.VIDEO_CALLING -> if (o.flags.vtByUser == false) "user_switch" else null
        OwnerFeature.VOWIFI -> when {
            o.wifiConnected == false -> "wifi_connected"
            o.flags.wfcByUser == false -> "user_switch"
            o.iwlan?.heldBackBy != null -> "backoff_${o.iwlan.heldBackBy.wire}"
            o.iwlan?.selectionFailed == true -> "epdg_selector_failed"
            else -> null
        }
        OwnerFeature.VONR -> when {
            2 !in nrAvailabilities(o) -> "sa_in_config"
            o.onNr && o.nrVops == NrVops.NOT_SUPPORTED -> "network_offers"
            else -> null
        }
        OwnerFeature.SMS_OVER_IMS -> null
    }

    fun decide(feature: OwnerFeature, desired: Boolean, o: Observation): Verdict {
        val works = working(feature, o)
        if (desired) {
            if (works == true) return Verdict(Outcome.REGISTERED, "ims_registered_with_service")
            blocker(feature, o)?.let { return Verdict(Outcome.REVERT, "blocked_at_$it") }
            return Verdict(Outcome.WAIT, if (works == null) "ims_state_unreadable" else "not_yet_registered")
        }
        return when (works) {
            false -> Verdict(Outcome.SERVICE_OFF, "service_gone")
            true -> Verdict(Outcome.WAIT, "service_still_registered")
            null -> Verdict(Outcome.WAIT, "ims_state_unreadable")
        }
    }

    fun nrAvailabilities(o: Observation): List<Int> =
        Regex("-?\\d+").findAll(o.config["carrier_nr_availabilities_int_array"].orEmpty()).map { it.value.toInt() }.toList()
}
