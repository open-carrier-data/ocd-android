package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.ForYou
import io.github.open_carrier_data.check.core.OcdProfile
import io.github.open_carrier_data.check.core.TwoAnswers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCD rule round 45, item 4: for_you reads as what the brand states, always with the plan
 * unchecked where it is not an end or a refusal, and never as "works", "booked" or "you have it";
 * capabilities read as what the phone is set up to try.
 */
class TwoAnswersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun lookupEntryWithBothMapsDecodes() {
        val profile = json.decodeFromString(
            OcdProfile.serializer(),
            """{"profile_id":"open.26202.8cebd7550c67","display_name":"Vodafone","match":{"mccmnc":["26202"]},
               "capabilities":{"vowifi":"supported","volte":"conditional"},
               "depends_on":{"volte":["device","sources_disagree"]},
               "for_you":{"vowifi":{"state":"depends_on_plan","statements":["de.lidl-connect.wifi_calling","de.vodafone.wifi_calling"],
                 "reason":"brand_not_tellable","brands":["BILDmobil","LIDL Connect","Vodafone Deutschland"]}},
               "weight":1}""",
        )
        assertEquals(listOf("device", "sources_disagree"), profile.dependsOn["volte"])
        assertEquals("brand_not_tellable", profile.forYou["vowifi"]?.reason)
        // An entry without them (every lookup.json before round 45) still decodes.
        val old = json.decodeFromString(OcdProfile.serializer(), """{"profile_id":"open.26201.13528695fee7","display_name":"Telekom","match":{"mccmnc":["26201"]}}""")
        assertTrue(old.forYou.isEmpty() && old.dependsOn.isEmpty())
    }

    @Test
    fun lidlOnThePlainVodafoneProfileReadsDependsOnBrandAndPlan() {
        val line = TwoAnswers.forYouLine(
            ForYou("depends_on_plan", listOf("de.lidl-connect.wifi_calling"), "brand_not_tellable", listOf("LIDL Connect", "Vodafone Deutschland")),
        )!!
        assertEquals("depends on the brand and the plan: this phone can't tell LIDL Connect, Vodafone Deutschland SIMs apart; your plan is not checked", line)
    }

    @Test
    fun everyLineSaysWhatTheBrandStatesNeverThatItWorks() {
        val states = listOf("depends_on_plan", "not_offered", "ended", "included", "free_option", "paid_option")
        for (state in states) {
            val line = TwoAnswers.forYouLine(ForYou(state, listOf("x")))!!
            for (word in listOf("works", "booked", "you have it")) assertFalse("$state: $line", line.contains(word))
            if (state !in setOf("not_offered", "ended")) assertTrue("$state: $line", line.endsWith("your plan is not checked"))
        }
        assertNull(TwoAnswers.forYouLine(ForYou("unknown", listOf("x"))))
        assertEquals("What the phone is set up to try (Vodafone)", TwoAnswers.configureHeading("Vodafone"))
        assertFalse(TwoAnswers.CONFIGURE_NOTE.contains("works"))
    }

    @Test
    fun dependsOnNamesOnlyKnownReasons() {
        assertEquals("Not one value: it differs by phone model; the sources disagree", TwoAnswers.dependsOnLine(listOf("device", "sources_disagree")))
        assertNull(TwoAnswers.dependsOnLine(listOf("guess")))
    }
}
