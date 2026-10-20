package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.EntryRecord
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.LedgerRow
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.ObservedCell
import io.github.open_carrier_data.check.owner.core.ObservedInfo
import io.github.open_carrier_data.check.owner.core.ObservedPull
import io.github.open_carrier_data.check.owner.core.ObservedReports
import io.github.open_carrier_data.check.owner.core.ObservedSource
import io.github.open_carrier_data.check.owner.core.ObservedStore
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Plan
import io.github.open_carrier_data.check.owner.core.PlannedEntry
import io.github.open_carrier_data.check.owner.core.PlanWhy
import io.github.open_carrier_data.check.owner.core.PointerLedger
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.ReportPointer
import io.github.open_carrier_data.check.owner.core.SchemaSubset
import io.github.open_carrier_data.check.owner.core.SimCheck
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.StatusSentence
import io.github.open_carrier_data.check.owner.core.TestBasis
import io.github.open_carrier_data.check.owner.core.UndoneEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Others' reports from OCD's observed/summary.json (public #286) in the owner
 * build: the anti-fake design of 2026-10-20 lets a report only move a test of
 * this phone's own cell earlier. The fixture under `resources/observed/` was
 * written by the public `tools/observed_reports.py rebuild` (origin/main
 * 303d3dcb) from five report issues: alice's S20 report (#7), the report this
 * S20 opened on 2026-10-20 (#21, the owner's own, by its hash), carol's S20
 * report with a clean Wi-Fi calling off failure and a VoLTE off call (#22), an
 * A50 (#23) and the S20 on Android 15 (#24). `resources/public/` holds the
 * public summary of 2026-10-20 (no reports yet).
 */
class ObservedTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val hour = TimeUnit.HOURS.toMillis(1)

    /** 2026-10-25, 12:00 UTC: after every report of the fixture. */
    private val now = LocalDate.of(2026, 10, 25).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(name)) { name }.use { it.readBytes() }

    /** The owner build's own copy of the public schema, as the app reads it from its assets. */
    private val schemaFile = listOf(File("src/owner/assets/${ObservedSource.SCHEMA_ASSET}"), File("app/src/owner/assets/${ObservedSource.SCHEMA_ASSET}")).first { it.isFile }
    private val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject

    private val publicSummary = resource("/public/observed-summary.json")
    private val fixture = resource("/observed/summary.json")

    /** The hash the app showed when it opened the S20's report on 2026-10-20 (public test CAPTURED_S20_HASH). */
    private val own = "sha256:40683d9b410665fa60d450d8c92092d19342df89be6aa929280313a52f39ec16"

    /** alice's report #7. */
    private val alice = "sha256:f128fc8cd4c1d72fd5a324f3530dfa19efb34b4b353933318adceb41fed35852"

    private val s20 = ObservedCell("SM-G981B", "x1s", "open.26202.8cebd7550c67", "26202", "lineageos", "phh_ims", 16)

    /** GitHub's raw files as the fixture holds them; anything else is a 404. Records what was asked. */
    private class Raw(val summary: ByteArray?, val records: Map<Int, ByteArray> = emptyMap()) {
        val asked = mutableListOf<String>()
        fun fetch(url: String, max: Long): ByteArray {
            asked += url
            if (url == ObservedSource.SUMMARY_URL) return summary ?: throw IOException("Unable to resolve host raw.githubusercontent.com")
            val issue = Regex("issue-(\\d+)\\.json$").find(url)?.groupValues?.get(1)?.toInt()
            return records[issue] ?: throw IOException("HTTP 404")
        }
    }

    private fun records(): Map<Int, ByteArray> = listOf(7, 21, 22, 23, 24).associateWith { resource("/observed/reports/issue-$it.json") }

    private fun pull(raw: Raw, hashes: Set<String> = setOf(own)): ObservedPull =
        ObservedReports.pull(raw::fetch, schema, "SM-G981B", "x1s", hashes, now)

    private fun fetched(pull: ObservedPull): ObservedPull.Fetched {
        assertTrue("$pull", pull is ObservedPull.Fetched)
        return pull as ObservedPull.Fetched
    }

    @Test
    fun `the owner build checks the summary against OCD's public schema, and today's public summary passes`() {
        assertTrue(schema.getValue("\$id").jsonPrimitive.content.endsWith("/schemas/observed-summary.schema.json"))
        assertEquals(emptyList<String>(), SchemaSubset.problems(Json.parseToJsonElement(publicSummary.decodeToString()), schema))
        val raw = Raw(publicSummary)
        val got = fetched(pull(raw))
        assertEquals(0, got.info.cells)
        assertEquals(0, got.info.counted)
        assertTrue(got.info.pointers.isEmpty())
        assertEquals(listOf(ObservedSource.SUMMARY_URL), raw.asked)
        assertEquals("https://raw.githubusercontent.com/open-carrier-data/open-carrier-data/main/observed/summary.json", ObservedSource.SUMMARY_URL)
        assertTrue(ObservedReports.line(got, "SM-G981B"), ObservedReports.line(got, "SM-G981B").endsWith(": 0 cell(s), 0 counted report(s); nothing others reported for SM-G981B"))
    }

    @Test
    fun `only this phone's cells, and only works, signed in and contested point, and the owner's own reports are not others'`() {
        val raw = Raw(fixture, records())
        val got = fetched(pull(raw))
        assertEquals(18, got.info.cells)
        assertEquals(5, got.info.counted)
        assertEquals(1, got.info.own)
        assertEquals(0, got.unchecked)
        val mine = ObservedReports.forCell(got.info, s20)
        // #7 and #21 both say VoLTE on: alice's "works" stays, the owner's own "signed in" goes.
        assertEquals(ReportPointer(s20, "volte:on", works = 1, signedIn = 0, contested = 0, lastReport = "2026-10-21"), mine["volte:on"])
        assertEquals(1, mine.getValue("sms_over_ims:on").signedIn)
        assertEquals(1, mine.getValue("vowifi:on").signedIn)
        assertEquals(ReportPointer(s20, "volte:off", works = 1, lastReport = "2026-10-22"), mine["volte:off"])
        assertEquals(ReportPointer(s20, "vowifi:off", contested = 1, lastReport = "2026-10-22"), mine["vowifi:off"])
        // service_off, not_possible, not_tested and failures that are not clean point at nothing.
        assertEquals(setOf("volte:on", "volte:off", "sms_over_ims:on", "vowifi:on", "vowifi:off"), mine.keys)
        // The S20 on Android 15 is another cell; the A50 is another phone and is not kept at all.
        assertEquals(1, ObservedReports.forCell(got.info, s20.copy(androidMajor = 15)).size)
        assertTrue(got.info.pointers.none { it.cell.codename == "a50" })
        assertTrue(ObservedReports.forCell(got.info, s20.copy(carrierProfile = "open.26203.000000000000")).isEmpty())
        // Only the issues of this model's pointing cells are read, newest first, and only to find the owner's own.
        assertEquals(listOf(24, 22, 21, 7).map(ObservedSource::recordUrl), raw.asked.drop(1))
        // Without an opened report no record is read, and the owner's "signed in" counts like anyone's.
        val plain = Raw(fixture, records())
        val all = fetched(pull(plain, hashes = emptySet()))
        assertEquals(listOf(ObservedSource.SUMMARY_URL), plain.asked)
        assertEquals(1, ObservedReports.forCell(all.info, s20).getValue("volte:on").signedIn)
        assertEquals(2, ObservedReports.forCell(all.info, s20).getValue("sms_over_ims:on").signedIn)
        // When every report of an entry is the owner's own, nothing points at it.
        val ownOnly = fetched(pull(Raw(fixture, records()), hashes = setOf(own, alice)))
        assertNull(ObservedReports.forCell(ownOnly.info, s20)["volte:on"])
        assertNull(ObservedReports.forCell(ownOnly.info, s20)["sms_over_ims:on"])
        // A record that cannot be read counts as someone else's, which can only move a test earlier.
        val missing = fetched(pull(Raw(fixture, records() - 21)))
        assertEquals(1, missing.unchecked)
        assertEquals(1, ObservedReports.forCell(missing.info, s20).getValue("volte:on").signedIn)
        val broken = fetched(pull(Raw(fixture, records() + (21 to "{\"issue\": 21}".toByteArray()))))
        assertEquals(1, broken.unchecked)
        // The hashes come from the state's list and from the log lines of builds before it.
        val state = OwnerState(reportsOpened = listOf(alice)).logged(now, "report", "opened the prefilled OCD Check report $own; nothing is sent until the owner submits it")
        assertEquals(setOf(alice, own), ObservedReports.ownHashes(state))
    }

    /** A state with a kept value, records, an undo, a ledger and a previous summary: what no pull may touch. */
    private fun busyState(previous: ObservedInfo?) = OwnerState(
        overrides = listOf(LiveOverride(0, OwnerFixtures.SIM.key, OwnerFeature.VOLTE.key, true, Hold.KEPT, now - day)),
        entries = listOf(EntryRecord(OwnerFixtures.SIM.key, "volte:on", now - day, basis, true, "ims_registered_with_service", true)),
        undone = listOf(UndoneEntry(OwnerFixtures.SIM.key, "vowifi:off", now - day)),
        ledger = listOf(LedgerRow("SM-G981B|lineageos|16|26202|2397", "volte:on", PointerLedger.OCD, passed = 1, firstMs = now, lastMs = now)),
        observed = previous,
        rebootCleared = true,
    )

    private val previous = ObservedInfo(now - 2 * day, "sha256:" + "ab".repeat(32), cells = 1, counted = 1, pointers = listOf(ReportPointer(s20, "volte:off", works = 1, lastReport = "2026-10-20")))

    private fun mutated(change: (MutableMap<String, JsonElement>) -> Unit): ByteArray =
        JsonObject(Json.parseToJsonElement(fixture.decodeToString()).jsonObject.toMutableMap().also(change)).toString().toByteArray()

    private fun cell0(change: (MutableMap<String, JsonElement>) -> Unit): ByteArray = mutated { top ->
        val cells = top.getValue("cells").jsonArray.toMutableList()
        cells[0] = JsonObject(cells[0].jsonObject.toMutableMap().also(change))
        top["cells"] = JsonArray(cells)
    }

    @Test
    fun `a malformed or oversized summary is ignored and the previous copy stays`() {
        val bad = mapOf(
            "not JSON" to "{\"schema_version\": 1,".toByteArray(),
            "not UTF-8" to byteArrayOf(0x7b, 0xff.toByte(), 0x7d),
            "an unknown key" to mutated { it["imsi"] = JsonPrimitive("262021234567890") },
            "another schema version" to mutated { it["schema_version"] = JsonPrimitive(2) },
            "verified" to mutated { it["verification"] = JsonPrimitive("verified") },
            "a value in a cell" to cell0 { it["value"] = JsonPrimitive("carrier_volte_available_bool=true") },
            "a re-tested status from outside" to cell0 { it["retested_by_ocd"] = JsonPrimitive("re-tested by OCD") },
            "a profile of another shape" to cell0 { it["carrier_profile"] = JsonPrimitive("26202") },
            "a negative count" to cell0 { it["held"] = JsonPrimitive(-1) },
            "a list" to "[]".toByteArray(),
            "oversized" to fixture + ByteArray((ObservedSource.MAX_SUMMARY_BYTES - fixture.size + 1).toInt()) { ' '.code.toByte() },
        )
        val dir = Files.createTempDirectory("observed").toFile()
        val files = ObservedStore(dir)
        files.save("previous copy")
        for ((name, bytes) in bad) {
            val result = ObservedReports.keep(pull(Raw(bytes, records())), files)
            assertTrue(name, result is ObservedPull.Kept)
            val reason = (result as ObservedPull.Kept).reason
            if (name == "oversized") assertTrue(reason, reason.startsWith("larger than 2048 KB"))
            if (name == "an unknown key") assertTrue(reason, "unknown key imsi" in reason && "262021234567890" !in reason)
            assertEquals(name, "previous copy", files.file.readText())
            val before = busyState(previous)
            val after = ObservedReports.applied(before, result, "SM-G981B", now, verbose = true)
            assertEquals(name, before.copy(log = emptyList()), after.copy(log = emptyList()))
            assertTrue(after.log.last().text, after.log.last().text.startsWith("observed/summary.json kept the previous copy: "))
        }
        // The size cap is what the app asks GitHub for, so an oversized answer is never read whole.
        val raw = Raw(publicSummary)
        pull(raw)
        assertEquals(ObservedSource.SUMMARY_URL, raw.asked.single())
    }

    @Test
    fun `offline keeps the old copy, and a summary that passes replaces only the summary`() {
        val dir = Files.createTempDirectory("observed").toFile()
        val files = ObservedStore(dir)
        files.save("previous copy")
        val offline = ObservedReports.keep(pull(Raw(null)), files)
        assertEquals("not fetched (IOException: Unable to resolve host raw.githubusercontent.com)", (offline as ObservedPull.Kept).reason)
        assertEquals("previous copy", files.file.readText())
        val before = busyState(previous)
        val kept = ObservedReports.applied(before, offline, "SM-G981B", now, verbose = false)
        assertEquals(previous, kept.observed)
        assertEquals(before.copy(log = emptyList()), kept.copy(log = emptyList()))
        // The background logs a failure once, not every day.
        assertEquals(kept.log, ObservedReports.applied(kept, offline, "SM-G981B", now + day, verbose = false).log)
        // A summary that passes: the file and the state's summary change, and nothing else does.
        val good = ObservedReports.keep(pull(Raw(fixture, records())), files)
        assertEquals(fixture.decodeToString(), files.file.readText())
        val after = ObservedReports.applied(before, good, "SM-G981B", now, verbose = false)
        assertEquals((good as ObservedPull.Fetched).info, after.observed)
        assertEquals(before.copy(observed = null, log = emptyList()), after.copy(observed = null, log = emptyList()))
        assertTrue(after.log.last().text, after.log.last().text.contains("18 cell(s), 5 counted report(s); 6 test(s) of SM-G981B others reported working or contested; 1 own report(s) left out"))
        // The same summary again in the background: nothing new, no log line.
        assertEquals(after.log, ObservedReports.applied(after, good, "SM-G981B", now + day, verbose = false).log)
    }

    // ---------------------------------------------------------------- the night plan

    private val sim = OwnerFixtures.SIM
    private val basis = TestBasis("data-2026.10@sha256:36d5f7ebb084", "lineage/x1s@1", "0.3.0-owner(3)/1/1")

    /** The S20 at home, as QuietTest's: what it sees is settled by the light check; VoLTE on and off, SMS off and Wi-Fi calling off need changes. */
    private fun check(apnToTest: List<String> = emptyList()) = SimCheck(
        sim = sim,
        effective = mapOf(OwnerFeature.VOLTE to true, OwnerFeature.SMS_OVER_IMS to true, OwnerFeature.VIDEO_CALLING to false, OwnerFeature.VOWIFI to true, OwnerFeature.VONR to false),
        observed = mapOf("sms_over_ims:on" to true, "video_calling:off" to true, "vowifi:on" to true, "vonr:off" to true),
        static = mapOf("vonr:on" to "device_not_capable", "video_calling:on" to "not_available_on_this_build"),
        apnToTest = apnToTest,
    )

    private val changes = listOf("volte:on", "volte:off", "sms_over_ims:off", "vowifi:off")

    private fun record(entry: String, at: Long, works: Boolean? = true, failures: Int = 0, observed: Boolean = false, b: TestBasis = basis) =
        EntryRecord(sim.key, entry, at, b, works, "x", null, failures, observed)

    private fun pointer(entry: String, reported: String = "2026-10-24", works: Int = 1, contested: Int = 0) =
        ReportPointer(s20, entry, works = works, contested = contested, lastReport = reported)

    private fun plan(records: List<EntryRecord>, reports: List<ReportPointer>, at: Long = now, undone: List<UndoneEntry> = emptyList(), onOnly: Boolean = false): Plan =
        QuietPlan.plan(listOf(check()), records, basis, at, known = setOf(sim.key), onOnly = onOnly, undone = undone, reports = mapOf(sim.key to reports.associateBy { it.entry }))

    @Test
    fun `a reported entry is due the next night, ahead of the rotation, and only until it is tested here`() {
        val steady = changes.map { record(it, now - 3 * day) }
        assertTrue(plan(steady, emptyList()).all.isEmpty())
        // Others reported VoLTE off works after the phone's last test of it: due tonight, as itself, never a trigger.
        val moved = plan(steady, listOf(pointer("volte:off")))
        assertEquals(listOf("volte:off"), moved.entries.map { it.entry })
        assertEquals(PlanWhy.REPORTED, moved.entries.single().why)
        assertFalse(moved.triggered)
        assertTrue("a report never makes a test leave the night", QuietPlan.nightOnly(moved, null, now))
        // Ahead of an older rotation entry; behind a trigger.
        val rotation = steady.map { if (it.entry == "vowifi:off") it.copy(atMs = now - 20 * day) else it }
        assertEquals(listOf("volte:off", "vowifi:off"), plan(rotation, listOf(pointer("volte:off"))).due.map { it.entry })
        assertEquals(listOf("vowifi:off"), plan(rotation, emptyList()).due.map { it.entry })
        val trigger = steady.map { if (it.entry == "sms_over_ims:off") it.copy(basis = basis.copy(app = "old")) else it }
        assertEquals(listOf("sms_over_ims:off", "volte:off"), plan(trigger, listOf(pointer("volte:off"))).due.map { it.entry })
        // Tested here on a later day: answered, back to its rotation.
        val tested = steady.map { if (it.entry == "volte:off") it.copy(atMs = now + day) else it }
        assertTrue(plan(tested, listOf(pointer("volte:off")), at = now + day + hour).all.isEmpty())
        // A report older than the last test moves nothing; nor does one with only other results.
        assertTrue(plan(steady, listOf(pointer("volte:off", reported = "2026-10-21"))).all.isEmpty())
        assertTrue(plan(steady, listOf(pointer("volte:off", works = 0))).all.isEmpty())
        // Contested moves it too: the phone tests it, a failure here switches nothing off that the test would not.
        assertEquals(listOf("vowifi:off"), plan(steady, listOf(pointer("vowifi:off", works = 0, contested = 1))).entries.map { it.entry })
        // A report never skips, pauses, or brings back what the owner undid or the pause holds.
        val paused = steady.map { if (it.entry == "volte:off") it.copy(works = null, failures = 2, atMs = now - 3 * day) else it }
        assertEquals(plan(paused, emptyList()).paused, plan(paused, listOf(pointer("volte:off"))).paused)
        assertTrue(plan(paused, listOf(pointer("volte:off"))).all.isEmpty())
        assertTrue(plan(steady, listOf(pointer("volte:off")), undone = listOf(UndoneEntry(sim.key, "volte:off", now - day))).all.isEmpty())
        assertTrue("after a ROM flash or a data release only on values", plan(steady, listOf(pointer("volte:off")), onOnly = true).all.isEmpty())
        // Entries the light check settles stay settled, and a report on anything outside the release's list finds nothing.
        val outside = listOf(pointer("vowifi:on"), pointer("vonr:on"), pointer("apn:evil.example|default|IP"), pointer("carrier_volte_available_bool:on"))
        assertTrue(plan(steady, outside).all.isEmpty())
        // Another cell's reports do not reach the plan: forCell keeps only this SIM's own.
        val info = ObservedInfo(now, "sha256:" + "00".repeat(32), 1, 1, listOf(pointer("volte:off").copy(cell = s20.copy(imsStack = "samsung_ims"))))
        assertTrue(ObservedReports.forCell(info, s20).isEmpty())
    }

    @Test
    fun `a report can only change the order, never the set of tests or any kept value`() {
        val random = Random(20261020)
        val apnPool = listOf("web.vodafone.de|default,dun|IPV4V6", "internet|default|IP", "web.vodafone.de|default|IPV4V6")
        val other = OwnerSim(slot = 1, subId = 7, identity = OwnerFixtures.LIDL.copy(mccmnc = "26203"), name = "O2", carrierId = 1)
        repeat(600) { round ->
            val checks = listOf(sim, other).filter { it == sim || random.nextBoolean() }.map { s ->
                val all = QuietPlan.FEATURE_ENTRIES
                SimCheck(
                    sim = s,
                    effective = OwnerFeature.entries.associateWith { if (random.nextInt(4) == 0) null else random.nextBoolean() },
                    failingKeeps = OwnerFeature.entries.filter { random.nextInt(8) == 0 },
                    observed = all.filter { random.nextInt(4) == 0 }.associateWith { random.nextBoolean() },
                    static = all.filter { random.nextInt(8) == 0 }.associateWith { "device_not_capable" },
                    apnToTest = apnPool.filter { random.nextBoolean() },
                )
            }
            val universe = checks.associate { c -> c.sim.key to (QuietPlan.FEATURE_ENTRIES + c.apnToTest.map(QuietPlan::apnEntry)).toSet() }
            val records = checks.flatMap { c ->
                (QuietPlan.FEATURE_ENTRIES + apnPool.map(QuietPlan::apnEntry)).filter { random.nextInt(3) > 0 }.map { e ->
                    EntryRecord(
                        c.sim.key, e, now - random.nextLong(40 * day), if (random.nextInt(5) == 0) basis.copy(app = "old") else basis,
                        listOf(true, false, null).random(random), "x", listOf(true, false, null).random(random), failures = random.nextInt(4),
                    )
                }
            }
            val undone = checks.flatMap { c -> QuietPlan.FEATURE_ENTRIES.filter { random.nextInt(10) == 0 }.map { UndoneEntry(c.sim.key, it, now) } }
            val names = QuietPlan.FEATURE_ENTRIES + apnPool.map(QuietPlan::apnEntry) + listOf("apn:evil.example|default|IP", "carrier_volte_available_bool:on", "volte:maybe")
            val reports = (checks.map { it.sim.key } + SimKey(99, "26201", null)).associateWith {
                names.filter { random.nextInt(3) == 0 }.associateWith { e ->
                    ReportPointer(
                        s20, e, works = random.nextInt(3), signedIn = random.nextInt(2), contested = random.nextInt(2),
                        lastReport = LocalDate.of(2026, 10, 25).minusDays(random.nextLong(45)).toString(),
                    )
                }
            }
            val onOnly = random.nextInt(4) == 0
            val nightUsed = random.nextInt(4) == 0
            fun planWith(r: Map<SimKey, Map<String, ReportPointer>>) =
                QuietPlan.plan(checks, records, basis, now, known = setOf(sim.key), onOnly = onOnly, nightUsed = nightUsed, undone = undone, reports = r)
            val without = planWith(emptyMap())
            val with = planWith(reports)
            val where = "round $round"
            // The same reverts, pauses and re-apply: a report never skips, pauses or removes anything.
            assertEquals(where, without.reverts, with.reverts)
            assertEquals(where, without.paused, with.paused)
            assertEquals(where, without.reapply, with.reapply)
            // Every test due without reports is due with them, as the same test for the same reason.
            fun shape(p: PlannedEntry) = listOf(p.slot, p.sim, p.entry, p.why, p.sinceMs)
            val withShapes = with.due.map(::shape)
            for (p in without.due) assertTrue("$where: ${p.entry}", shape(p) in withShapes)
            // What a report adds is only an entry of the phone's own list, due earlier because a report points at it.
            for (p in with.due) {
                assertTrue("$where: ${p.entry}", p.entry in universe.getValue(p.sim))
                assertTrue(where, undone.none { it.sim == p.sim && it.entry == p.entry })
                if (without.due.none { it.sim == p.sim && it.entry == p.entry }) {
                    assertEquals(where, PlanWhy.REPORTED, p.why)
                    assertTrue(where, p.reported && reports.getValue(p.sim).containsKey(p.entry))
                    assertFalse(where, p.triggered)
                }
            }
            // The same triggers, and the night's budget: one feature, at most one off and two on values.
            assertEquals(where, without.due.filter { it.triggered }.map(::shape).toSet(), with.due.filter { it.triggered }.map(::shape).toSet())
            if (nightUsed) assertTrue(where, with.entries.isEmpty())
            assertTrue(where, with.entries.map { it.slot to it.group }.distinct().size <= 1)
            assertTrue(where, with.entries.count { !it.isOn } <= QuietPlan.MAX_OFF_PER_NIGHT && with.entries.count { it.isOn } <= QuietPlan.MAX_ON_PER_NIGHT)
            if (with.entries.isNotEmpty() && with.entries.none { it.triggered }) assertTrue(where, QuietPlan.nightOnly(with, null, now))
        }
    }

    @Test
    fun `reports never count toward works`() {
        val view = SimView(slot = 0, subId = sim.subId, mccmnc = "26202", name = "LIDL Connect")
        val base = OwnerState(
            rebootCleared = true,
            entries = listOf(record("volte:on", now - day, works = true), record("vowifi:on", now - hour, observed = true)),
        )
        val reported = base.copy(observed = fetched(pull(Raw(fixture, records()), hashes = emptySet())).info)
        assertEquals(Ladders.of(base, view), Ladders.of(reported, view))
        assertEquals(StatusSentence.of(base, listOf(view), Ladders.of(base, view)), StatusSentence.of(reported, listOf(view), Ladders.of(reported, view)))
        assertEquals(PointerLedger.summary(base.ledger), PointerLedger.summary(reported.ledger))
    }

    // ---------------------------------------------------------------- Details

    private val internal = Regex("TRIAL|wfc_override|same_as_phone_row|_bool|[Rr]evert|\\blive\\b|\\bkept\\b|\\bapplied\\b|[Oo]verride|\\bentr(y|ies)\\b|signed_in|reported_by_others")

    @Test
    fun `Details says in one plain line per test what others reported and what the phone does about it`() {
        val info = fetched(pull(Raw(fixture, records()))).info
        val apns = setOf("apn:web.vodafone.de|default,supl|IPV4V6")
        val cells = listOf(ObservedReports.SimCell(sim.key, sim.name, s20, QuietPlan.FEATURE_ENTRIES.toSet() + apns))
        val records = listOf(
            record("volte:on", now - 5 * day), record("volte:off", now - 10 * day), record("sms_over_ims:off", now - 3 * day), record("vowifi:off", now - 5 * day),
            record("sms_over_ims:on", now - hour, observed = true), record("vowifi:on", now - hour, observed = true),
        )
        val state = OwnerState(rebootCleared = true, entries = records, observed = info)
        assertEquals(
            listOf(
                "Others reported VoLTE on works on this phone model — tested first tonight",
                "Others reported VoLTE off works on this phone model — tested first tonight",
                "Others reported SMS over IMS on signs in on this phone model — checked on this phone without a test",
                "Others reported Wi-Fi calling on signs in on this phone model — checked on this phone without a test",
                "Others reported Wi-Fi calling off fails on this phone model — tested on a later night",
            ),
            ObservedReports.lines(cells, state, now),
        )
        // The plan takes the same feature tonight.
        val reports = mapOf(sim.key to ObservedReports.forCell(info, s20))
        val night = QuietPlan.plan(listOf(check()), records, basis, now, known = setOf(sim.key), reports = reports)
        assertEquals(listOf("volte:off", "volte:on"), night.entries.map { it.entry })
        assertEquals(setOf(PlanWhy.REPORTED), night.entries.map { it.why }.toSet())
        // After the night: tested since; a pause, an undo and the switched-off background say so.
        val after = state.copy(entries = records.map { if (it.entry.startsWith("volte:")) it.copy(atMs = now + day) else it })
        assertEquals("Others reported VoLTE on works on this phone model — tested on this phone since", ObservedReports.lines(cells, after, now + day + hour).first())
        assertEquals("Others reported Wi-Fi calling off fails on this phone model — tested first tonight", ObservedReports.lines(cells, after, now + day + hour).last())
        val paused = state.copy(entries = records.map { if (it.entry == "vowifi:off") it.copy(works = null, failures = 2) else it })
        assertEquals("Others reported Wi-Fi calling off fails on this phone model — paused on this phone after two failed tests", ObservedReports.lines(cells, paused, now).last())
        val undone = state.copy(undone = listOf(UndoneEntry(sim.key, "volte:on", now)))
        assertEquals("Others reported VoLTE on works on this phone model — not tested at night since you undid it", ObservedReports.lines(cells, undone, now).first())
        val off = state.copy(scheduleOn = false)
        assertEquals("Others reported VoLTE on works on this phone model — tested when you tap Test now, night tests are off", ObservedReports.lines(cells, off, now).first())
        // Two SIMs: each line names its SIM; a cell without reports has no line; nothing outside the release's list shows.
        val two = cells + ObservedReports.SimCell(SimKey(7, "26203", 1), "O2", s20.copy(mccmnc = "26203", carrierProfile = null), QuietPlan.FEATURE_ENTRIES.toSet())
        assertTrue(ObservedReports.lines(two, state, now).all { it.startsWith("LIDL Connect: Others reported ") })
        assertTrue(ObservedReports.lines(listOf(cells[0].copy(entries = apns)), state, now).isEmpty())
        // Plain words: no internal term, no final full stop.
        val mixed = state.copy(observed = info.copy(pointers = info.pointers + ReportPointer(s20, apns.single(), works = 1, contested = 1, lastReport = "2026-10-24")))
        val texts = listOf(state, after, paused, undone, off, mixed).flatMap { ObservedReports.lines(cells, it, now) }
        assertTrue(texts.contains("Others reported the access point name web.vodafone.de (default, supl) works, and others that it fails, on this phone model — tested first tonight"))
        for (text in texts) {
            assertFalse(text, internal.containsMatchIn(text))
            assertFalse(text, text.endsWith("."))
        }
        // No summary yet, or nothing for this phone: no lines.
        assertTrue(ObservedReports.lines(cells, state.copy(observed = null), now).isEmpty())
    }
}
