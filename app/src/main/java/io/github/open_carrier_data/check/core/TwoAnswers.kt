package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD's rule round 45, item 4: two answers per feature. A profile's `capabilities` stay the
 * configure answer, what the phone is set up to try. `for_you` says, per feature, what an admitted
 * operator or brand statement states about availability for this customer, and `depends_on` says
 * why a conditional value is not single. The app shows them apart and never as "works", "booked"
 * or "you have it" (round 42's rule that OCD Check never claims a booking): a for_you line always
 * says what the brand states and that the person's plan is not checked.
 */

/** One feature's for_you entry from lookup.json. */
@Serializable
data class ForYou(
    val state: String,
    val statements: List<String> = emptyList(),
    val reason: String? = null,
    val brands: List<String> = emptyList(),
)

object TwoAnswers {
    /** The heading over the capability values of the most specific profile. */
    fun configureHeading(profileName: String): String = "What the phone is set up to try ($profileName)"

    /** The fixed sentence under that heading. */
    const val CONFIGURE_NOTE =
        "\"Set up to try\" is what carrier tables tell the phone to use. It is not a test, and it does not say what your plan includes."

    /** The label in front of a for_you line. */
    const val FOR_YOU_LABEL = "What the brand states"

    /** One for_you entry in plain words, or null for a state this build does not know (shown as nothing). */
    fun forYouLine(item: ForYou): String? = when (item.state) {
        "depends_on_plan" ->
            if (item.reason == "brand_not_tellable" && item.brands.isNotEmpty()) {
                "depends on the brand and the plan: this phone can't tell ${item.brands.joinToString()} SIMs apart; your plan is not checked"
            } else {
                "offered; your plan is not checked"
            }
        "not_offered" -> "not offered"
        "ended" -> "ended"
        "included" -> "included in every current plan; your plan is not checked"
        "free_option" -> "a free option to switch on; your plan is not checked"
        "paid_option" -> "a paid option; your plan is not checked"
        else -> null
    }

    /** depends_on in plain words: why a conditional value is not single. */
    fun dependsOnLine(reasons: List<String>): String? {
        val words = reasons.mapNotNull {
            when (it) {
                "device" -> "it differs by phone model"
                "firmware" -> "it differs by phone software"
                "ims_stack" -> "it differs by IMS stack"
                "sources_disagree" -> "the sources disagree"
                else -> null
            }
        }
        return if (words.isEmpty()) null else "Not one value: ${words.joinToString("; ")}"
    }

    /**
     * The lines under [profile]'s value for [key], as the check screen and the owner screen show them: why it
     * is not one value, then what the brand states; empty when the profile has neither (or only unknown ones).
     */
    fun lines(profile: OcdProfile, key: String): List<String> = listOfNotNull(
        profile.dependsOn[key]?.let { dependsOnLine(it) },
        profile.forYou[key]?.let { forYouLine(it) }?.let { "$FOR_YOU_LABEL: $it" },
    )
}
