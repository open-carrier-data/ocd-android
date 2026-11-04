package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The plan next to a report (OCD rule round 47, item 4): "unknown", or a closed offer ID with booked
 * options from the carrier's published list, as the person chose it for this one report. It only rides
 * in the report's `plan` field, and a report without one reads as unknown.
 *
 * What it never is: free text, or something read from an account, USSD, a carrier SMS or TS.43. It is not
 * kept in the app's state, is no matching key and feeds no for_you line or value, so nothing but the
 * report and the picker reads it.
 */

/** One option a person can book with an offer, from the carrier's published list. */
data class OfferOption(val id: String, val name: String)

/** One offer of the carrier's published list. */
data class CarrierOffer(val id: String, val name: String, val options: List<OfferOption> = emptyList())

/** What a report's `plan` says. */
sealed interface ReportPlan {
    val wire: JsonElement

    data object Unknown : ReportPlan {
        override val wire: JsonElement = JsonPrimitive("unknown")
    }

    /** Made only by [ReportPlans.choose], from the closed list. */
    class Offer internal constructor(val offer: String, val options: List<String>) : ReportPlan {
        init {
            require((options + offer).all(ReportPlans.ID::matches) && options.size <= ReportPlans.MAX_OPTIONS && options.toSet().size == options.size) { "not a closed offer ID" }
        }

        override val wire: JsonElement = JsonObject(
            buildMap {
                put("offer", JsonPrimitive(offer))
                if (options.isNotEmpty()) put("options", JsonArray(options.map(::JsonPrimitive)))
            },
        )
    }
}

object ReportPlans {
    /** The public schema's `offer_id`: country, brand and offer, for example de.lidl-connect.smart. */
    val ID = Regex("[a-z]{2}\\.[a-z0-9-]{1,40}\\.[a-z0-9-]{1,40}")
    const val MAX_OPTIONS = 8

    /**
     * The published offer list of the carrier profile [profileId]. No file the app bundles or pulls holds one
     * yet: the lists come with OCD's migration step 8, and the public bot takes no plan but unknown until then.
     * So every carrier's list is empty and unknown is the only choice. Step 8's reader goes here, and nothing
     * else changes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun offers(profileId: String?): List<CarrierOffer> = emptyList()

    /**
     * The plan for what the person picked: the offer [offerId] of [offers] with those of [optionIds] that
     * offer really has. Anything else is unknown, so nothing outside the closed list reaches a report.
     */
    fun choose(offers: List<CarrierOffer>, offerId: String?, optionIds: Set<String> = emptySet()): ReportPlan {
        val offer = offers.firstOrNull { it.id == offerId && ID.matches(it.id) } ?: return ReportPlan.Unknown
        return ReportPlan.Offer(offer.id, offer.options.map { it.id }.distinct().filter { it in optionIds && ID.matches(it) }.take(MAX_OPTIONS))
    }
}
