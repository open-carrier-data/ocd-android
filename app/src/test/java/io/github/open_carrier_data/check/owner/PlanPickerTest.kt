package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.OperatorFiles
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
import io.github.open_carrier_data.check.owner.core.WeekReports
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
import java.nio.file.Files

/**
 * OCD rule round 47, item 4: the plan next to a report. It comes only from the carrier's closed list and the person's
 * own choice, default unknown, is never a matching key, and feeds nothing but the report. Rule round 51, item 4 opens it:
 * the list comes from the picker files of the last pull, and the automatic week report never holds a plan.
 */
class PlanPickerTest {
    private val schema = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/public/ocd-check-report.schema.json")).use { it.readBytes().decodeToString() }).jsonObject
    private val ownerRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
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

    private val today = "2026-11-05"
    private val realOffers = File("src/test/resources/operators/offers.json").readText()
    private val realGroups = File("src/test/resources/operators/sim-groups.json").readText()

    /** A folder as a pull leaves it; a null text leaves that file out. */
    private fun pulled(offers: String? = realOffers, groups: String? = realGroups, identity: String? = File("src/test/resources/operators/carrier-identity.json").readText()): File {
        val dir = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "picker").toFile()
        offers?.let { File(dir, OperatorFiles.OFFERS).writeText(it) }
        groups?.let { File(dir, OperatorFiles.SIM_GROUPS).writeText(it) }
        identity?.let { File(dir, OperatorFiles.IDENTITY).writeText(it) }
        return dir
    }

    private fun offer(id: String, validTo: String? = null, validFrom: String = "≤2026-11-05", lapsed: String = "", brand: String = "de.lidl-connect", name: String = "Name") =
        """{"id":"$id","brand":"$brand","name":"$name","valid_from":"$validFrom","valid_to":${validTo?.let { "\"$it\"" } ?: "null"}$lapsed}"""

    private fun offersFile(vararg offers: String) = """{"schema_version":1,"offers":[${offers.joinToString(",")}]}"""

    private val oneGroup = """{"schema_version":1,"profiles":[{"profile_id":"$profile","brands":["de.lidl-connect","de.vodafone"],"tellable":"no"}]}"""

    @Test
    fun `the list is the live offers of the brands in the profile's row, each with its brand`() {
        val list = ReportPlans.offers(profile, pulled(), today)
        assertEquals(
            listOf("de.lidl-connect.classic", "de.lidl-connect.smart-l", "de.lidl-connect.smart-m", "de.lidl-connect.smart-s", "de.lidl-connect.smart-xs", "de.vodafone.5g-plus"),
            list.map { it.id },
        )
        assertEquals("Smart S (LIDL Connect)", list.single { it.id == "de.lidl-connect.smart-s" }.label)
        assertTrue("a pack or option is an offer of its own", list.all { it.options.isEmpty() })
        val chosen = ReportPlans.choose(list, "de.lidl-connect.smart-s")
        assertEquals("""{"offer":"de.lidl-connect.smart-s"}""", chosen.wire.toString())
        assertEquals(emptyList<String>(), SchemaSubset.problems(report(chosen), schema))
        // Another brand's offer is on no other profile's list.
        assertSame(ReportPlan.Unknown, ReportPlans.choose(list, "de.telekom.5g-plus-ultra"))
    }

    @Test
    fun `only valid_to and lapsed decide what is live`() {
        val lapsed = ""","lapsed":{"reason":"not_confirmed_180_days","since":"2026-11-01"}"""
        val dir = pulled(
            offers = offersFile(
                offer("de.lidl-connect.plain"),
                offer("de.lidl-connect.starts-later", validFrom = "2027-01-01"),
                offer("de.lidl-connect.last-day", "2026-11-05"),
                offer("de.lidl-connect.bound", "≤2026-11-05"),
                offer("de.lidl-connect.ended", "2026-11-04"),
                offer("de.lidl-connect.bound-ended", "≤2026-11-04"),
                offer("de.lidl-connect.odd-end", "soon"),
                offer("de.lidl-connect.lapsed", lapsed = lapsed),
                offer("de.other.brand", brand = "de.other"),
                offer("Free text"),
                offer("de.lidl-connect.no-name", name = " "),
            ),
            groups = oneGroup,
        )
        assertEquals(
            listOf("de.lidl-connect.plain", "de.lidl-connect.starts-later", "de.lidl-connect.last-day", "de.lidl-connect.bound"),
            ReportPlans.offers(profile, dir, today).map { it.id },
        )
    }

    @Test
    fun `no list, or a list that cannot be read, leaves unknown as the only choice and never throws`() {
        val none = emptyList<CarrierOffer>()
        assertEquals(none, ReportPlans.offers(profile, null, today))
        assertEquals(none, ReportPlans.offers(null, pulled(), today))
        assertEquals(none, ReportPlans.offers("open.99999.000000000000", pulled(), today))
        assertEquals(none, ReportPlans.offers(profile, pulled(offers = null), today))
        assertEquals(none, ReportPlans.offers(profile, pulled(groups = null), today))
        for (broken in listOf("", "{", "[]", "null", "{\"schema_version\":1}", "{\"schema_version\":2,\"offers\":[],\"profiles\":[]}", realOffers.take(200))) {
            assertEquals(broken, none, ReportPlans.offers(profile, pulled(offers = broken), today))
            assertEquals(broken, none, ReportPlans.offers(profile, pulled(groups = broken), today))
        }
        assertSame(ReportPlan.Unknown, ReportPlans.choose(none, "de.lidl-connect.smart-s"))
        assertTrue(ReportWords.PLAN_NO_LIST, "unknown is the only choice" in ReportWords.PLAN_NO_LIST)
        // Without the identity table the brand's key stands in for its name.
        assertEquals("Smart S (de.lidl-connect)", ReportPlans.offers(profile, pulled(identity = null), today).single { it.id.endsWith("smart-s") }.label)
    }

    @Test
    fun `the automatic week report can never hold a plan`() {
        val week = Json.parseToJsonElement(File(ownerRoot, "assets/ocd-week-report.schema.json").readText()).jsonObject
        assertEquals(setOf("app_version", "carrier", "device", "results", "schema", "schema_version", "week"), week.getValue("properties").jsonObject.keys)
        assertEquals(JsonPrimitive(false), week["additionalProperties"])
        val build = WeekReports::class.java.declaredMethods.filter { it.name == "build" }
        assertTrue(build.isNotEmpty())
        for (method in build) assertFalse(method.parameterTypes.any { ReportPlan::class.java.isAssignableFrom(it) })
        // The week builder has no path to the picker: not in its files, and no other job can call the report that takes a plan.
        for (name in listOf("WeekReport.kt", "OwnerWeekReport.kt", "WeekQuestionActivity.kt")) {
            assertNull(name, Regex("\\bReportPlans?\\b|\\bCarrierOffer\\b|pickerDir|IssueReport\\.(prefill|report)\\b|pickPlan").find(source(name)))
        }
        assertEquals(listOf("OwnerActivity.kt"), sources.filter { "IssueReport.prefill(" in it.readText() }.map { it.name })
        // It stays UI state: on the screen's state only, and reset when the report closes.
        assertEquals(listOf("OwnerActivity.kt", "OwnerScreen.kt"), sources.filter { Regex("\\breportPlan\\b").containsMatchIn(it.readText()) }.map { it.name }.sorted())
        assertTrue("reportPlan = ReportPlan.Unknown" in source("OwnerActivity.kt").substringAfter("fun closeReport()").substringBefore("\n    }"))
        assertTrue("plan: ReportPlan = ReportPlan.Unknown" in source("OwnerActivity.kt").substringAfter("fun report(heldBack").substringBefore("{"))
    }

    @Test
    fun `the words about the public issue and git history come before the choice and before submit`() {
        assertTrue("It goes in the public report under your GitHub name" in ReportWords.PLAN_TEXT)
        for (needed in listOf("The issue is public, under your own GitHub account", "any plan in this report", "older versions stay in git history")) {
            assertTrue(needed, needed in ReportWords.FORM_TEXT)
        }
        val draft = source("OwnerScreen.kt").substringAfter("private fun ReportDraft(").substringBefore("Round 47 item 4: the one picker")
        assertTrue(draft.indexOf("ReportWords.FORM_TEXT") in 0 until draft.indexOf("PlanPicker("))
        assertTrue(draft.indexOf("PlanPicker(") < draft.indexOf("ReportWords.FORM_OPEN"))
        val form = requireNotNull(javaClass.getResourceAsStream("/public/ocd-check-report.yml")).use { it.readBytes().decodeToString() }
        assertTrue("Anyone can link your GitHub name to the carrier and any plan in the report" in form)
        assertTrue(form.indexOf("older versions stay in git history") in 0 until form.indexOf("id: report"))
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
        // The list is read from the pull's files alone: no SIM, phone, account or network reaches it, and nothing is preselected.
        val plan = source("ReportPlan.kt")
        assertEquals(
            setOf("io.github.open_carrier_data.check.core", "java.io", "kotlinx.serialization.json"),
            Regex("(?m)^import (.*)\\.[A-Za-z]+$").findAll(plan).map { it.groupValues[1] }.toSet(),
        )
        assertNull(Regex("\\b(spn|gid1|imsi|ussd|ts43)\\b", RegexOption.IGNORE_CASE).find(plan.substringAfter("fun offers(").substringBefore("fun choose(")))
        assertEquals(listOf(String::class.java, File::class.java, String::class.java), ReportPlans::class.java.declaredMethods.single { it.name == "offers" }.parameterTypes.toList())
        assertTrue(Regex("reportPlan: ReportPlan = ReportPlan\\.Unknown").containsMatchIn(source("OwnerActivity.kt")))
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
        val shared = File(ownerRoot, "java").walkTopDown().filter { it.extension == "kt" && "/owner/" !in it.path }.toList()
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
