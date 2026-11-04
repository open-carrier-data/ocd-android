package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.CarrierOffer
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.ObservedCell
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OfferOption
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.ReportPlan
import io.github.open_carrier_data.check.owner.core.ReportPlans
import io.github.open_carrier_data.check.owner.core.ReportWords
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SchemaSubset
import io.github.open_carrier_data.check.owner.core.SimKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 47, item 4: the plan next to a report. It comes only from the carrier's closed list and the person's
 * own choice, default unknown, is never a matching key, and feeds nothing but the report. No carrier has a published
 * offer list yet, so the list here is a stand-in for what migration step 8 will bring.
 */
class PlanPickerTest {
    private val schema = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/public/ocd-check-report.schema.json")).use { it.readBytes().decodeToString() }).jsonObject
    private val ownerRoot = listOf(File("src/owner"), File("app/src/owner")).first { it.isDirectory }
    private val sources = File(ownerRoot, "java").walkTopDown().filter { it.extension == "kt" }.toList()
    private fun source(name: String) = sources.single { it.name == name }.readText()

    private val profile = "open.26202.8cebd7550c67"
    private val build = "CP2A.260605.016 1792814506"
    private val cell = ReportCell(
        model = "SM-G981B", codename = "x1s", androidMajor = 16, romFamily = "lineageos", buildType = "userdebug", imsStack = "aosp_imsstack",
        spn = "LIDL Connect", appVersion = "0.3.0-owner", dataDigest = "sha256:" + "36d5f7eb".repeat(8), dataSource = "pulled",
        dataCommit = "e32fd75687d01e7271b640c3adb41c52d42af6a8", romBuild = build,
    )
    private val run = RunResult(
        id = "r47", startedMs = 1_792_456_340_000L, scheduled = false, sim = SimKey(3, "26202", 2397), simName = "LIDL Connect", profileId = profile,
        features = listOf(FeatureResult(OwnerFeature.VOLTE, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offReason = "off_tests_not_chosen", best = true)),
        cell = cell,
    )
    private val calls = emptyList<CallsTested>()

    private val data = OfferOption("de.lidl-connect.data-plus", "Data plus")
    private val offers = listOf(CarrierOffer("de.lidl-connect.smart", "Smart", listOf(data)), CarrierOffer("de.lidl-connect.classic", "Classic"))

    private fun report(plan: ReportPlan = ReportPlan.Unknown) = requireNotNull(IssueReport.report(run, cell, calls, plan = plan))

    @Test
    fun `no carrier has an offer list yet, so unknown is the only choice`() {
        assertEquals(emptyList<CarrierOffer>(), ReportPlans.offers(profile))
        assertEquals(emptyList<CarrierOffer>(), ReportPlans.offers(null))
        assertSame(ReportPlan.Unknown, ReportPlans.choose(ReportPlans.offers(profile), "de.lidl-connect.smart"))
        assertTrue(ReportWords.PLAN_NO_LIST, "unknown is the only choice" in ReportWords.PLAN_NO_LIST)
    }

    @Test
    fun `the default report says unknown, and fits the public schema like a report from before the field`() {
        assertEquals(JsonPrimitive("unknown"), report()["plan"])
        assertEquals(emptyList<String>(), SchemaSubset.problems(report(), schema))
        val old = JsonObject(report() - "plan")
        assertEquals(emptyList<String>(), SchemaSubset.problems(old, schema))
        assertFalse("plan" in schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a choice comes only from the closed list`() {
        val chosen = ReportPlans.choose(offers, "de.lidl-connect.smart", setOf(data.id))
        assertEquals("""{"offer":"de.lidl-connect.smart","options":["de.lidl-connect.data-plus"]}""", chosen.wire.toString())
        assertEquals(emptyList<String>(), SchemaSubset.problems(report(chosen), schema))
        assertEquals("""{"offer":"de.lidl-connect.classic"}""", ReportPlans.choose(offers, "de.lidl-connect.classic").wire.toString())
        // An offer outside the list, typed text or no pick at all is unknown; an option the offer does not have is dropped.
        for (pick in listOf(null, "", "de.other.offer", "my tariff", "+4917612345678")) assertSame(pick, ReportPlan.Unknown, ReportPlans.choose(offers, pick))
        assertEquals("""{"offer":"de.lidl-connect.classic"}""", ReportPlans.choose(offers, "de.lidl-connect.classic", setOf(data.id, "typed")).wire.toString())
        // A list entry of another shape is no choice, and nothing else can make a plan.
        assertSame(ReportPlan.Unknown, ReportPlans.choose(listOf(CarrierOffer("Free text", "Free text")), "Free text"))
        assertThrows(IllegalArgumentException::class.java) { ReportPlan.Offer("my tariff", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ReportPlan.Offer("de.lidl-connect.smart", listOf("+4917612345678")) }
        // The public schema refuses free text as well.
        for (bad in listOf(JsonPrimitive("my tariff"), JsonObject(mapOf("offer" to JsonPrimitive("my tariff"))))) {
            assertTrue(SchemaSubset.problems(JsonObject(report() + ("plan" to bad)), schema).isNotEmpty())
        }
    }

    @Test
    fun `the picker has no text field and reads the plan from nowhere but the person`() {
        val screen = source("OwnerScreen.kt")
        assertFalse(Regex("\\bTextField\\b|OutlinedTextField").containsMatchIn(screen))
        val picker = screen.substringAfter("private fun PlanPicker(").substringBefore("/** The newest 20 entries inline")
        assertTrue("ReportWords.PLAN_UNKNOWN" in picker)
        val plan = source("ReportPlan.kt")
        assertEquals(listOf("kotlinx.serialization.json"), Regex("(?m)^import (.*)\\.[A-Za-z]+$").findAll(plan).map { it.groupValues[1] }.distinct().toList())
    }

    @Test
    fun `plan is no matching key and feeds no value`() {
        // Everything but the plan and the report's hash stays the same.
        val chosen = ReportPlans.choose(offers, "de.lidl-connect.smart")
        assertEquals(JsonObject(report() - "plan"), JsonObject(report(chosen) - "plan"))
        assertNotEquals(IssueReport.hash(IssueReport.text(report())), IssueReport.hash(IssueReport.text(report(chosen))))
        // Only the report and its screen know it: no cell, no state, no pointer, no for_you, no provider call.
        val readers = sources.filter { Regex("\\b(ReportPlans?|CarrierOffer)\\b").containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("IssueReport.kt", "OwnerActivity.kt", "OwnerScreen.kt", "ReportPlan.kt"), readers)
        for (type in listOf(ObservedCell::class.java, ReportCell::class.java, OwnerState::class.java, RunResult::class.java)) {
            assertEquals(type.name, emptyList<String>(), type.declaredFields.filter { ReportPlan::class.java.isAssignableFrom(it.type) || it.name.contains("offer", ignoreCase = true) }.map { it.name })
        }
        assertFalse(OwnerState().encode().contains("offer"))
        val shared = File(ownerRoot.parentFile, "main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(shared.size > 8)
        for (file in shared) assertNull(file.name, Regex("\\bReportPlans?\\b").find(file.readText()))
    }

    @Test
    fun `a report keeps a date and never a time`() {
        assertTrue(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(report().getValue("tested_on").jsonPrimitive.content))
        // The release names a digest, whose "sha256:36" is no clock.
        val text = IssueReport.text(JsonObject(report() - "release"))
        assertNull(text, Regex("[0-9]{2}:[0-9]{2}|[0-9]T[0-9]{2}|\"[a-z_]*(time|_ms|_at)\"").find(text))
    }

    @Test
    fun `firmware in a report is Build ID and build time, never the incremental`() {
        assertEquals(build, report().getValue("device").jsonObject.getValue("build").jsonPrimitive.content)
        // CallBuild.short's shape carries the incremental and the stack: it never fits the field and never reaches a report.
        val short = "CP2A.260605.016 eng.bela.20261024.123456, aosp_imsstack"
        assertNull(IssueReport.report(run, cell.copy(romBuild = short), calls)!!.getValue("device").jsonObject["build"])
        assertTrue("romBuild = OwnerAutoReports.publicBuild()" in source("OwnerData.kt"))
        assertTrue("Settled.publicBuild(Build.ID.orEmpty(), Build.TIME)" in source("OwnerReports.kt"))
        for (name in listOf("OwnerReports.kt", "IssueReport.kt", "ReportPlan.kt", "Settled.kt")) {
            assertNull(name, Regex("INCREMENTAL|CallBuild|FINGERPRINT").find(source(name)))
        }
        assertNotNull(IssueReport.prefill(run, cell, calls))
    }
}
