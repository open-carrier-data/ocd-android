package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.OperatorFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/*
 * The plan next to a report (OCD rule round 47, item 4): "unknown", or a closed offer ID with booked
 * options from the carrier's published list, as the person chose it for this one report. It only rides
 * in the report's `plan` field, and a report without one reads as unknown.
 *
 * What it never is: free text, or something read from an account, USSD, a carrier SMS or TS.43. It is not
 * kept in the app's state, is no matching key and feeds no for_you line or value, so nothing but the
 * report and the picker reads it. The automatic week report never holds one (rule round 51, item 4).
 */

/** One option a person can book with an offer, from the carrier's published list. */
data class OfferOption(val id: String, val name: String)

/** One offer of the carrier's published list. */
data class CarrierOffer(val id: String, val name: String, val options: List<OfferOption> = emptyList(), val brand: String = "") {
    /** The offer as the picker shows it, with its brand beside it: a shared SIM group holds several brands. */
    val label: String get() = if (brand.isEmpty()) name else "$name ($brand)"
}

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
    private const val SCHEMA = "1"
    private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The published offer list of the carrier profile [profileId], from the picker files the last pull left in [dir]:
     * the offers live on [today] whose brand is in the profile's sim-groups row, with the brand's name beside each. No
     * network. A missing or unreadable file, a format this build does not read or a profile with no row gives an empty
     * list, and an offer of another shape is left out, so a report never waits for a list. `options` stays empty: a pack
     * or option is an offer of its own.
     */
    fun offers(profileId: String?, dir: File?, today: String): List<CarrierOffer> = runCatching {
        if (profileId == null || dir == null) return emptyList()
        val row = list(File(dir, OperatorFiles.SIM_GROUPS), "profiles").firstOrNull { text(it, "profile_id") == profileId } ?: return emptyList()
        val brands = (row["brands"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val names = runCatching { OperatorFiles.identity(File(dir, OperatorFiles.IDENTITY).readText()).entries.associate { it.key to it.name } }.getOrDefault(emptyMap())
        list(File(dir, OperatorFiles.OFFERS), "offers").mapNotNull { item ->
            val id = text(item, "id")?.takeIf(ID::matches)
            val name = text(item, "name")?.takeIf(String::isNotBlank)
            val brand = text(item, "brand")?.takeIf { it in brands }
            if (id != null && name != null && brand != null && live(item, today)) CarrierOffer(id, name, brand = names[brand] ?: brand) else null
        }
    }.getOrDefault(emptyList())

    /** Whether an offer is live on [today]: not lapsed, and its valid_to not past. valid_from is often only an upper bound, so it decides nothing. */
    private fun live(offer: JsonObject, today: String): Boolean {
        if ((offer["lapsed"] ?: JsonNull) != JsonNull) return false
        val validTo = text(offer, "valid_to")?.removePrefix("≤") ?: return true
        return DAY.matches(validTo) && today <= validTo
    }

    /** The array [key] of one picker file, whose schema_version must be the one this build reads. */
    private fun list(file: File, key: String): List<JsonObject> {
        val document = json.parseToJsonElement(file.readText()) as JsonObject
        require(text(document, "schema_version") == SCHEMA) { "format" }
        return (document[key] as JsonArray).filterIsInstance<JsonObject>()
    }

    private fun text(item: JsonObject, key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The plan for what the person picked: the offer [offerId] of [offers] with those of [optionIds] that
     * offer really has. Anything else is unknown, so nothing outside the closed list reaches a report.
     */
    fun choose(offers: List<CarrierOffer>, offerId: String?, optionIds: Set<String> = emptySet()): ReportPlan {
        val offer = offers.firstOrNull { it.id == offerId && ID.matches(it.id) } ?: return ReportPlan.Unknown
        return ReportPlan.Offer(offer.id, offer.options.map { it.id }.distinct().filter { it in optionIds && ID.matches(it) }.take(MAX_OPTIONS))
    }
}
