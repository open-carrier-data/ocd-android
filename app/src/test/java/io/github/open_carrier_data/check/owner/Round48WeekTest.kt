package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.MediaRule
import io.github.open_carrier_data.check.core.MediaWindowBuilder
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.EntryRecord
import io.github.open_carrier_data.check.owner.core.MainNet
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.OnItsOwn
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.Pointers
import io.github.open_carrier_data.check.owner.core.Relay
import io.github.open_carrier_data.check.owner.core.SimCheck
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TestBasis
import io.github.open_carrier_data.check.owner.core.WeekBuffer
import io.github.open_carrier_data.check.owner.core.WeekCell
import io.github.open_carrier_data.check.owner.core.WeekClock
import io.github.open_carrier_data.check.owner.core.WeekDevice
import io.github.open_carrier_data.check.owner.core.WeekQuestion
import io.github.open_carrier_data.check.owner.core.WeekReports
import io.github.open_carrier_data.check.owner.core.WeekState
import io.github.open_carrier_data.check.owner.core.WeekStep
import io.github.open_carrier_data.check.owner.core.WeekSwitch
import io.github.open_carrier_data.check.owner.core.WeekWords
import io.github.open_carrier_data.check.core.SimIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * OCD rule round 48, item 2, the app part: one active yes, a closed report, and every limit on the send. Each test names
 * the condition it holds. No test sends anything: the step runs against [Fake].
 */
class Round48WeekTest {
    private val schema = Json.parseToJsonElement(File("src/main/assets/${WeekReports.SCHEMA_ASSET}").readText()).jsonObject
    private val day = TimeUnit.DAYS.toMillis(1)

    /** Wednesday 2026-11-04 12:00 UTC, in 2026-W45. */
    private val now = 1_793_793_600_000L
    private val monday = 1_793_577_600_000L
    private val closed = "2026-W44"
    private val current = "2026-W45"
    private val sim = SimKey(3, "26203", 2397)
    private val profile = "open.26203.8cebd7550c67"
    private val rom = "lineage-23.2"
    private val row = "internet.example|default|IPV4V6"
    private val cell = WeekCell("SM-G981B", "lineageos", 16, "aosp_imsstack", "0.6")

    private fun buffer(results: Map<String, String> = mapOf("volte:on" to "signed_in"), week: String = closed, key: SimKey = sim) =
        WeekBuffer(week, key, profile, results)

    private fun yes(buffers: List<WeekBuffer> = listOf(buffer())) = WeekState(
        asked = true, yes = true, yesAtMs = monday - 14 * day, buffers = buffers,
        closedWeek = closed, sendAtMs = monday + day, mainHeldAtMs = now - day,
    )

    private inner class Fake(
        override var privileged: Boolean = true,
        override var switch: WeekSwitch = WeekSwitch.ASK,
        override var relayUrl: String = "https://relay.example/v1/week-report",
        var network: MainNet = MainNet(unmetered = true, roaming = false, dataSaver = false),
        var call: Boolean? = false,
        var flag: Boolean = true,
        var taken: Boolean = true,
        var rows: Set<String> = emptySet(),
        var cellNow: WeekCell = cell,
        val onPost: () -> Unit = {},
    ) : WeekDevice {
        val posts = mutableListOf<String>()
        var flagReads = 0
        override val rom: String get() = this@Round48WeekTest.rom
        override val schema: JsonObject get() = this@Round48WeekTest.schema
        override fun net() = network
        override fun inCall() = call
        override fun cell() = cellNow
        override fun onList(model: String) = model == "SM-G981B"
        override suspend fun ocdRows(sim: SimKey) = rows
        override fun relayOpen(): Boolean {
            flagReads++
            return flag
        }
        override suspend fun post(body: ByteArray): Boolean {
            posts += body.decodeToString()
            onPost()
            return taken
        }
    }

    private fun step(store: MemoryStore, fake: Fake, at: Long = now): String? = runBlocking { WeekStep(store, fake) { 3 * day }.run(at) }

    private fun state(w: WeekState) = MemoryStore(OwnerState(week = w))

    private fun report(results: Map<String, String>, rows: Set<String> = emptySet(), c: WeekCell = cell, carrier: String = profile, onList: (String) -> Boolean = { it == "SM-G981B" }) =
        WeekReports.build(closed, c, carrier, results, rows) { onList(it) }

    private fun pairs(report: JsonObject?) = report!!.getValue("results").jsonArray.associate {
        it.jsonObject.getValue("entry").jsonPrimitive.content to it.jsonObject.getValue("result").jsonPrimitive.content
    }

    // --- the yes ---

    @Test
    fun `silence, a build default and a builder's choice never count, and the switch has no on state`() {
        assertEquals(WeekSwitch.ASK, WeekSwitch.of("ask"))
        for (value in listOf("off", "on", "yes", "true", "", null)) assertEquals(value.toString(), WeekSwitch.OFF, WeekSwitch.of(value))
        assertEquals(setOf("ASK", "OFF"), WeekSwitch.entries.map { it.name }.toSet())
        assertTrue(File("src/main/res/values/config.xml").readText().contains("""<string name="ocd_week_report" translatable="false">ask</string>"""))
        // A fresh install sends nothing, on the best network, with a filled buffer.
        val fake = Fake()
        val store = state(WeekState(buffers = listOf(buffer()), closedWeek = closed, sendAtMs = monday, mainHeldAtMs = now))
        assertNull(step(store, fake))
        assertEquals(0, fake.posts.size + fake.flagReads)
        assertFalse(WeekState().yes)
    }

    @Test
    fun `only a tap on Yes sets the yes`() {
        val root = "src/main/java/io/github/open_carrier_data/check/owner"
        val code = File(root).walkTopDown().filter { it.extension == "kt" }.toList()
        val setters = code.filter { Regex("\\byes = (true|yes)\\b|WeekReports\\.answered\\(").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("WeekReport.kt", "OwnerWeekReport.kt"), setters)
        val answers = code.filter { Regex("OwnerWeekReport\\.answer\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("WeekQuestionActivity.kt"), answers)
        val activity = File("$root/WeekQuestionActivity.kt").readText()
        assertFalse("no extra can answer", Regex("\\bintent\\.|Extra\\(").containsMatchIn(activity))
        assertTrue(activity.contains("onClick = { answer(true) }") && activity.contains("onClick = { answer(false) }"))
        // Equal Yes and No: the same button, the same width.
        assertEquals(2, Regex("OutlinedButton\\(onClick = \\{ answer\\((true|false)\\) \\}, modifier = Modifier\\.weight\\(1f\\)\\)").findAll(activity).count())
    }

    @Test
    fun `the question is asked once per install, with a SIM, and a no or a dismissed one stays no`() {
        assertTrue(WeekQuestion.due(WeekState(), privileged = true, switch = WeekSwitch.ASK, sim = true))
        assertFalse("no SIM yet", WeekQuestion.due(WeekState(), privileged = true, switch = WeekSwitch.ASK, sim = false))
        assertFalse("a hand install", WeekQuestion.due(WeekState(), privileged = false, switch = WeekSwitch.ASK, sim = true))
        assertFalse("the build is off", WeekQuestion.due(WeekState(), privileged = true, switch = WeekSwitch.OFF, sim = true))
        assertFalse("never again", WeekQuestion.due(WeekState(asked = true), privileged = true, switch = WeekSwitch.ASK, sim = true))
        val no = WeekReports.answered(WeekState(), yes = false, nowMs = now)
        assertTrue(no.asked && !no.yes)
        // A dismissed notification: asked is set when it went out, and yes stays false.
        val glue = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWeekReport.kt").readText()
        val ask = glue.substringAfter("fun askOnce(").substringBefore("fun mayAnswer(")
        assertTrue(ask.contains("it.week.copy(asked = true)"))
        assertFalse(ask.contains("yes"))
        assertTrue(ask.contains("nothing asked, the answer stays no"))
        val receivers = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerReceivers.kt").readText()
        assertTrue(receivers.contains("areNotificationsEnabled()"))
        assertEquals(2, Regex("OwnerWeekReport\\.askOnce\\(context\\)").findAll(receivers.substringAfter("class OwnerBootReceiver")).count())
        assertTrue(receivers.substringAfter("class OwnerBootReceiver").let { it.indexOf("privilegedGranted") < it.indexOf("askOnce") })
    }

    @Test
    fun `the question reads as ruled, and no text says anonymous`() {
        val ruled = "Help improve carrier settings? Once a week, on Wi-Fi and never while roaming, this phone can send Open Carrier Data which carrier features and data settings " +
            "signed in or worked, with your phone model, ROM family, Android version, IMS software and carrier. It sends no name, number or SIM ID, no location, no times and no call details. " +
            "Your internet address reaches OCD's relay at Cloudflare (USA) while sending and OCD does not keep it. Results are published only for groups of phones and hold no ID, " +
            "so what was sent cannot be taken back. You can stop any time in Settings > About phone > Open Carrier Data."
        assertEquals(ruled, "${WeekQuestion.TITLE} ${WeekQuestion.BODY}")
        assertEquals(listOf("Details", "No", "Yes"), listOf(WeekQuestion.DETAILS, WeekQuestion.NO, WeekQuestion.YES))
        val code = File("src/main/java/io/github/open_carrier_data/check/owner/core/WeekReport.kt").readText() + WeekQuestion.DETAILS_TEXT
        assertFalse(code.contains("anonym", ignoreCase = true))
        assertTrue("8 weeks" in WeekQuestion.DETAILS_TEXT && "not checked by OCD" in WeekQuestion.DETAILS_TEXT)
        assertTrue(WeekQuestion.DETAILS_TEXT.endsWith("https://github.com/open-carrier-data/open-carrier-data/blob/main/docs/privacy.md"))
    }

    @Test
    fun `the first-screen line says what this install does, the send included where it can happen`() {
        assertEquals(
            "On its own, this app only reads the phone's state, installs new carrier data, repairs a failure it measured and, if you said yes, sends OCD one weekly summary. " +
                "It makes no calls and sends no SMS unless you tap for it.",
            OnItsOwn.line(privileged = true, switch = WeekSwitch.ASK),
        )
        assertEquals(OnItsOwn.LINE, OnItsOwn.line(privileged = false, switch = WeekSwitch.ASK))
        assertEquals(OnItsOwn.LINE, OnItsOwn.line(privileged = true, switch = WeekSwitch.OFF))
        assertFalse("sends OCD" in OnItsOwn.LINE)
    }

    // --- who sends ---

    @Test
    fun `a hand install never sends, reads no flag and holds no question`() {
        val fake = Fake(privileged = false)
        val store = state(yes())
        assertNull(step(store, fake))
        assertEquals(0, fake.posts.size + fake.flagReads)
        assertEquals(yes(), store.state.week)
    }

    @Test
    fun `a build set to off ends a yes and clears the buffer`() {
        val fake = Fake(switch = WeekSwitch.OFF)
        val store = state(yes())
        assertNull(step(store, fake))
        assertEquals(0, fake.posts.size)
        assertFalse(store.state.week.yes)
        assertTrue(store.state.week.buffers.isEmpty())
    }

    @Test
    fun `off stops the send at once and clears the buffer, between two SIMs too`() {
        val w = WeekReports.off(yes().copy(last = null))
        assertFalse(w.yes)
        assertTrue(w.buffers.isEmpty())
        assertNull(w.sendAtMs)
        lateinit var store: MemoryStore
        val fake = Fake(onPost = { store.state = store.state.copy(week = WeekReports.off(store.state.week)) })
        store = state(yes(listOf(buffer(), buffer(key = SimKey(4, "26202", 2)))))
        step(store, fake)
        assertEquals(1, fake.posts.size)
    }

    // --- the limits on the send ---

    @Test
    fun `one POST per SIM in a closed week, marked done first, and never a second one`() {
        val fake = Fake()
        val store = state(yes(listOf(buffer(), buffer(key = SimKey(4, "26202", 2)), buffer(week = current))))
        assertEquals("$closed: 2 of 2 sent", step(store, fake))
        assertEquals(2, fake.posts.size)
        assertEquals(1, fake.flagReads)
        val w = store.state.week
        assertEquals(closed, w.doneWeek)
        assertEquals(listOf(current), w.buffers.map { it.week })
        assertEquals(fake.posts, w.last!!.reports)
        assertEquals(2, w.last!!.sent)
        assertNull(step(store, fake, now + day))
        assertEquals(2, fake.posts.size)
    }

    @Test
    fun `only on an unmetered network, never roaming, with Data Saver on or in a call, and an unreadable condition closes`() {
        val open = MainNet(unmetered = true, roaming = false, dataSaver = false)
        for (net in listOf(
            open.copy(unmetered = false), open.copy(unmetered = null), open.copy(roaming = true), open.copy(roaming = null),
            open.copy(dataSaver = true), open.copy(dataSaver = null),
        )) {
            val fake = Fake(network = net)
            val store = state(yes())
            assertNull(step(store, fake))
            assertEquals(net.toString(), 0, fake.posts.size + fake.flagReads)
            assertEquals("it waits for a better moment in the week", listOf(closed), store.state.week.buffers.map { it.week })
        }
        for (call in listOf(true, null)) {
            val fake = Fake(call = call)
            assertNull(step(state(yes()), fake))
            assertEquals(0, fake.posts.size)
        }
    }

    @Test
    fun `a random moment in the running week, drawn once, and nothing before it`() {
        val fake = Fake()
        val store = state(yes().copy(closedWeek = "2026-W43", sendAtMs = null))
        assertNull(step(store, fake, monday + day))
        assertEquals(closed, store.state.week.closedWeek)
        assertEquals(monday + 3 * day, store.state.week.sendAtMs)
        assertEquals(0, fake.posts.size)
        assertNotNull(step(store, fake, monday + 3 * day))
        assertEquals(1, fake.posts.size)
    }

    @Test
    fun `a missed week is dropped when its buffer turns seven days old, and no queue grows`() {
        val fake = Fake(network = MainNet(unmetered = false, roaming = false, dataSaver = false))
        val store = state(yes(listOf(buffer(), buffer(week = current))))
        step(store, fake)
        step(store, fake, now + 7 * day)
        assertEquals(listOf(current), store.state.week.buffers.map { it.week })
        step(store, fake, now + 14 * day)
        assertTrue(store.state.week.buffers.isEmpty())
        assertEquals(0, fake.posts.size)
    }

    @Test
    fun `relay_open can only stop, and absent, false or a broken file means no send`() {
        assertTrue(Relay.open("""{"schema_version": 1, "relay_open": true}"""))
        for (closedFlag in listOf("""{"relay_open": false}""", "{}", """{"relay_open": "true"}""", """{"relay_open": 1}""", "not json", "", "[true]", """{"relay_open": null}""")) {
            assertFalse(closedFlag, Relay.open(closedFlag))
        }
        val fake = Fake(flag = false)
        val store = state(yes())
        assertEquals("$closed: not sent, OCD's relay is closed", step(store, fake))
        assertEquals(0, fake.posts.size)
        // The week is over for this phone: no retry, and the report stays readable on the screen.
        assertTrue(store.state.week.buffers.isEmpty())
        assertEquals(0, store.state.week.last!!.sent)
        assertEquals(1, store.state.week.last!!.reports.size)
        assertNull(step(store, fake, now + 3_600_000))
        assertEquals(1, fake.flagReads)
    }

    @Test
    fun `a last pulled main older than 30 days means no send, and a clock that went back closes too`() {
        for (held in listOf(now - 31 * day, now + day, null)) {
            val fake = Fake()
            val store = state(yes().copy(mainHeldAtMs = held))
            assertEquals("$closed: not sent, this phone has not reached OCD's data for 30 days", step(store, fake))
            assertEquals(0, fake.posts.size + fake.flagReads)
        }
        assertTrue(WeekReports.fresh(yes().copy(mainHeldAtMs = now - 30 * day), now))
    }

    @Test
    fun `the URL is compiled in, a placeholder keeps the send closed, and relay_open cannot redirect`() {
        assertFalse("the placeholder never opens", Relay.ready())
        assertTrue(Relay.URL.startsWith("https://"))
        assertTrue(Relay.ready("https://relay.example/v1/week-report"))
        for (bad in listOf("http://relay.example/x", "https://user@relay.example/x", "https://relay.invalid/x", "relay.example", "")) assertFalse(bad, Relay.ready(bad))
        val fake = Fake(relayUrl = Relay.URL)
        val store = state(yes())
        assertEquals("$closed: not sent, this build has no relay address yet", step(store, fake))
        assertEquals(0, fake.posts.size + fake.flagReads)
        val glue = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWeekReport.kt").readText()
        assertTrue(glue.contains("override val relayUrl: String get() = Relay.URL"))
        assertTrue(glue.contains("network.openConnection(URL(Relay.URL))"))
        assertEquals(1, Regex("URL\\(").findAll(glue).count())
        assertFalse("not a resource a build could overlay", File("src/main/res/values/config.xml").readText().contains("relay.invalid"))
    }

    @Test
    fun `any answer but a 2xx drops the week, with no retry and no notice`() {
        val fake = Fake(taken = false)
        val store = state(yes())
        assertEquals("$closed: 0 of 1 sent", step(store, fake))
        assertTrue(store.state.week.buffers.isEmpty())
        assertNull(step(store, fake, now + day))
        assertEquals(1, fake.posts.size)
        val code = File("src/main/java/io/github/open_carrier_data/check/owner/core/WeekReport.kt").readText() +
            File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWeekReport.kt").readText().substringAfter("private class AndroidWeekDevice")
        assertFalse(code.contains("notice("))
    }

    @Test
    fun `the POST is HTTPS on the checked network, with no redirect, no cookie and a constant User-Agent`() {
        val glue = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWeekReport.kt").readText()
        for (needed in listOf(
            "as HttpsURLConnection", "instanceFollowRedirects = false", "useCaches = false", "NET_CAPABILITY_NOT_METERED", "NET_CAPABILITY_NOT_ROAMING",
            "setRequestProperty(\"User-Agent\", Relay.USER_AGENT)", "connection.responseCode in 200..299",
        )) assertTrue(needed, needed in glue)
        assertEquals("ocd-android", Relay.USER_AGENT)
        val all = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(all.contains("CookieHandler") || all.contains("CookieManager"))
        assertEquals(1, Regex("Relay\\.USER_AGENT").findAll(glue).count())
    }

    // --- the report ---

    @Test
    fun `the report holds the listed fields only, and passes its closed schema and the privacy scan`() {
        val report = report(mapOf("volte:on" to "signed_in", "vowifi:off" to "service_off"))!!
        assertTrue(WeekReports.clean(report, schema))
        assertEquals(setOf("schema", "schema_version", "week", "app_version", "device", "carrier", "results"), report.keys)
        assertEquals(setOf("model", "rom_family", "android_major", "ims_stack"), report.getValue("device").jsonObject.keys)
        for (r in report.getValue("results").jsonArray) assertEquals(setOf("entry", "result"), r.jsonObject.keys)
        assertEquals("ocd-week-report", report.getValue("schema").jsonPrimitive.content)
        assertEquals(closed, report.getValue("week").jsonPrimitive.content)
        // One extra field is refused whole.
        val extra = JsonObject(report + ("plan" to report.getValue("week")))
        assertFalse(WeekReports.clean(extra, schema))
        val text = WeekReports.text(report)
        for (never in listOf("plan", "bearer", "codename", "\"build\"", "spn", "gid1", "commit", "digest", "reason", "method", "measured_on", "call_types", "roaming", "tested_on")) {
            assertFalse(never, text.contains(never, ignoreCase = true))
        }
    }

    @Test
    fun `a model off OCD's public list reads other, and the list is OCD's`() {
        assertEquals("SM-G981B", report(mapOf("volte:on" to "signed_in"))!!.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
        val free = report(mapOf("volte:on" to "signed_in"), c = cell.copy(model = "Bob's phone"))!!
        assertEquals("other", free.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals("Krüger&Matz _LIVE5_KM0450", report(mapOf("volte:on" to "signed_in"), c = cell.copy(model = "Krüger&Matz _LIVE5_KM0450")) { true }!!.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
        // Outside the relay's shape: other too, so the report is not refused.
        for (odd in listOf("two\nlines", "x".repeat(97), "")) {
            val listed = report(mapOf("volte:on" to "signed_in"), c = cell.copy(model = odd)) { true }!!
            assertEquals(odd, "other", listed.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
            assertTrue(WeekReports.clean(listed, schema))
        }
        val models = GZIPInputStream(File("src/main/assets/${WeekReports.MODELS_ASSET}").inputStream()).bufferedReader().readLines()
        assertTrue(models.size > 40_000)
        assertEquals(models.sorted(), models)
        assertTrue("SM-G981B" in models && "SM-G980F" in models)
    }

    @Test
    fun `the carrier is the OCD profile, or the home network only when no profile matches`() {
        assertEquals(profile, WeekReports.carrier(profile, "26203"))
        assertEquals("26203", WeekReports.carrier(null, "26203"))
        assertEquals("310260", WeekReports.carrier(null, "310260"))
        assertEquals("the relay takes open profiles only", "26203", WeekReports.carrier("fill.26203.8cebd7550c67", "26203"))
        assertEquals("26203", WeekReports.carrier("Lidl Connect", "26203"))
        assertNull(WeekReports.carrier(null, "x"))
        assertTrue(WeekReports.clean(report(mapOf("volte:on" to "signed_in"), carrier = "26203")!!, schema))
    }

    @Test
    fun `on an uncalibrated stack only signed_in, service_off and not_possible are sent`() {
        val phh = cell.copy(imsStack = "phh_ims")
        val results = mapOf("volte:on" to "works", "vowifi:on" to "failed", "vonr:on" to "not_possible", "sms_over_ims:on" to "signed_in", "video_calling:off" to "service_off")
        assertEquals(setOf("vonr:on", "sms_over_ims:on", "video_calling:off"), pairs(report(results, c = phh)).keys)
        assertEquals(results.keys, pairs(report(results)).keys)
        assertNull("nothing left, nothing sent", report(mapOf("volte:on" to "works"), c = phh))
    }

    @Test
    fun `only OCD's own rows for the SIM's network go, never one with the emergency type`() {
        val own = "apn:$row"
        assertEquals(setOf(own), pairs(report(mapOf(own to "works"), setOf(row))).keys)
        assertNull("a row OCD does not have: the person's or an employer's", report(mapOf(own to "works"), emptySet()))
        for (types in listOf("default,emergency", "emergency", "*", "ims,sos")) {
            val key = "internet.example|$types|IP"
            assertNull(types, report(mapOf("apn:$key" to "works"), setOf(key)))
        }
        assertFalse(WeekReports.rowAllowed("apn:not a key", setOf("not a key")))
    }

    @Test
    fun `at most 16 results and 4 KB, each entry once, the switches first`() {
        val rows = (1..20).map { "apn${"x".repeat(50)}$it.example|default,supl,mms,dun,hipri,fota,ims,cbs,ia,xcap,mcx|IPV4V6" }.toSet()
        val results = rows.associate { "apn:$it" to "works" } + mapOf("vowifi:on" to "signed_in", "volte:on" to "signed_in")
        val report = report(results, rows)!!
        val entries = report.getValue("results").jsonArray.map { it.jsonObject.getValue("entry").jsonPrimitive.content }
        assertTrue(entries.size <= WeekReports.MAX_RESULTS)
        assertEquals(entries.toSet().size, entries.size)
        assertEquals(listOf("volte:on", "vowifi:on"), entries.take(2))
        assertTrue(WeekReports.text(report).toByteArray().size <= Relay.MAX_BODY_BYTES)
        assertTrue(WeekReports.clean(report, schema))
    }

    // --- what goes into a week ---

    private fun check(observed: Map<String, Boolean> = emptyMap(), static: Map<String, String> = emptyMap()) =
        SimCheck(OwnerSim(0, sim.subId, SimIdentity(sim.mccmnc, null, null, null), "SIM", sim.carrierId), emptyMap(), observed = observed, static = static)

    @Test
    fun `the light check gives signed_in, service_off and not_possible, and a value not seen working is no result`() {
        val seen = WeekReports.passive(check(mapOf("volte:on" to true, "vowifi:off" to true, "vonr:on" to false), mapOf("video_calling:on" to "not_available")))
        assertEquals(mapOf("volte:on" to Pointers.SIGNED_IN, "vowifi:off" to Pointers.SERVICE_OFF, "video_calling:on" to Pointers.NOT_POSSIBLE), seen)
    }

    @Test
    fun `an entry keeps one result per SIM for the week, and nothing is kept before the yes`() {
        assertEquals(WeekState(), WeekReports.noted(WeekState(), current, sim, profile, mapOf("volte:on" to "signed_in")))
        val w = yes(emptyList())
        val once = WeekReports.noted(w, current, sim, profile, mapOf("volte:on" to "signed_in", "vonr:on" to "not_possible"))
        val twice = WeekReports.noted(once, current, sim, profile, mapOf("volte:on" to "not_possible", "vonr:on" to "signed_in"))
        assertEquals(1, twice.buffers.size)
        assertEquals(mapOf("volte:on" to "signed_in", "vonr:on" to "signed_in"), twice.buffers.single().results)
        assertEquals("the same look writes nothing", twice, WeekReports.noted(twice, current, sim, profile, mapOf("volte:on" to "signed_in")))
        assertEquals("no carrier, no buffer", w, WeekReports.noted(w, current, sim, null, mapOf("volte:on" to "signed_in")))
    }

    @Test
    fun `the quiet job drops what it saw while roaming, unknown included`() {
        val work = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerWork.kt").readText()
        assertTrue(work.contains("if (look.roaming == false && state.week.yes) {"))
        assertEquals(1, Regex("WeekReports\\.noted\\(").findAll(work).count())
    }

    private fun reading(bearer: ProvenBearer) =
        if (bearer == ProvenBearer.IWLAN) BearerReading(bearer.callNetworkType, listOf(ImsTransport.IWLAN), "lte") else BearerReading(bearer.callNetworkType, listOf(ImsTransport.LTE), "lte")

    private fun call(at: Long, direction: CallDirection, roaming: Boolean? = false, active: Boolean = true, bearer: ProvenBearer = ProvenBearer.LTE): CallRecord {
        val proof = BearerProof.of(active, reading(bearer), reading(bearer))
        val end = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE")
        val window = MediaWindowBuilder().apply {
            var t = 1_000L
            while (t <= 45_000) {
                add(CallQualityReading(t, 50 * t / 1000, 50 * t / 1000))
                t += 1_000
            }
        }.window()
        return CallRecord(
            atMs = at, slot = 0, sim = sim, method = CallMethod.REAL_CALL, direction = direction,
            rang = direction == CallDirection.INCOMING, answered = true, active = active,
            bearerAtStart = BearerReading(0, reading(bearer).imsPdn, "lte"), bearerAtActive = reading(bearer), bearerAtHangUp = reading(bearer),
            bearer = proof,
            media = if (active) MediaRule.judge(window, CallRecord.kindOf(CallMethod.REAL_CALL, direction), "phh_ims", rom, proof, end) else null,
            activeMs = if (active) 45_000 else null,
            disconnect = end, disconnectFresh = true,
            fromMailbox = if (direction == CallDirection.INCOMING) false else null,
            build = rom, stack = "aosp_imsstack", roaming = roaming,
        )
    }

    private val from = monday - 7 * day
    private val out = call(from + day, CallDirection.OUTGOING)
    private val incoming = call(from + 2 * day, CallDirection.INCOMING)

    private fun measured(calls: List<CallRecord>, week: WeekState = WeekState()) = WeekReports.measured(OwnerState(calls = calls, week = week), sim, from, monday, rom)

    @Test
    fun `the ladder gives the rung, one flag per bearer, and works needs both directions`() {
        assertEquals(mapOf("volte:on" to Pointers.WORKS), measured(listOf(out, incoming)))
        assertEquals("one outgoing call reaches calls_out at most", mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out)))
        assertTrue("an incoming call alone is no rung", measured(listOf(incoming)).isEmpty())
        val wifi = listOf(call(from + day, CallDirection.OUTGOING, bearer = ProvenBearer.IWLAN))
        assertEquals(mapOf("vowifi:on" to WeekReports.CALLS_OUT), measured(wifi))
        assertEquals(mapOf("volte:on" to Pointers.WORKS, "vowifi:on" to WeekReports.CALLS_OUT), measured(wifi + out + incoming))
        // Another bearer's incoming call does not lift it.
        assertEquals(mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out, incoming.copy(bearer = null))))
        assertTrue("no vonr or video result comes from a call", measured(listOf(out, incoming)).keys.none { it.startsWith("vonr") || it.startsWith("video_calling") })
    }

    @Test
    fun `a rung counts only for a week with a passing call, on this build, after the last voice install and the yes`() {
        val earlier = out.copy(atMs = from - day)
        assertEquals("an old leg with one passing call this week", mapOf("volte:on" to Pointers.WORKS), measured(listOf(earlier, incoming)))
        assertTrue("no passing call this week: the entry stays signed_in", measured(listOf(earlier, incoming.copy(atMs = from - day))).isEmpty())
        assertTrue("the running week waits", measured(listOf(out.copy(atMs = monday))).isEmpty())
        assertTrue("another build holds no rung", measured(listOf(out.copy(build = "other"))).isEmpty())
        val install = WeekReports.voiceInstalled(WeekState(), sim, "abc", from + 2 * day + 1)
        assertTrue("calls before the install hold no rung", measured(listOf(out), install).isEmpty())
        assertEquals("the old incoming leg is gone with it", mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out.copy(atMs = from + 3 * day), incoming), install))
        assertEquals("another SIM's install changes nothing", mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out), WeekReports.voiceInstalled(WeekState(), SimKey(9, "26202", 1), "abc", now)))
        assertTrue("a pass before the yes proves nothing yet", measured(listOf(out), WeekState(yes = true, yesAtMs = from + 2 * day)).isEmpty())
        assertEquals(WeekReports.voiceInstalled(WeekState(), sim, "abc", now).voiceInstalls, WeekReports.off(WeekReports.voiceInstalled(WeekState(), sim, "abc", now)).voiceInstalls)
    }

    @Test
    fun `a failure or a hold in the week replaces works and calls_out, whatever came first`() {
        val failed = call(from + day, CallDirection.OUTGOING).copy(method = CallMethod.MAILBOX_CALL, active = false, disconnect = DisconnectReading(501, "CODE_USER_TERMINATED"), hungUpByPhone = false, media = null, activeMs = null)
        val later = out.copy(atMs = from + 5 * day)
        assertEquals("a later pass does not hide it", mapOf("volte:on" to Pointers.FAILED), measured(listOf(failed, later, incoming)))
        assertEquals(mapOf("volte:on" to Pointers.FAILED), measured(listOf(out, incoming, failed.copy(atMs = from + 6 * day))))
        assertEquals("a hold has no bearer, so it takes the rung it finds", mapOf("volte:on" to Pointers.FAILED), measured(listOf(out, incoming), WeekState(callHoldAtMs = from + 3 * day)))
        assertTrue("a hold with no rung adds nothing", measured(emptyList(), WeekState(callHoldAtMs = from)).isEmpty())
        assertEquals("a hold of another week changes nothing", mapOf("volte:on" to Pointers.WORKS), measured(listOf(out, incoming), WeekState(callHoldAtMs = from - 1)))
        val dataset = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerDataset.kt").readText()
        assertTrue(dataset.contains("week = it.week.copy(callHoldAtMs = now)"))
    }

    @Test
    fun `nothing from roaming, several calls, a mailbox notification or an uncalibrated stack`() {
        for (notHome in listOf(true, null)) assertEquals(mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out, incoming.copy(roaming = notHome))))
        assertTrue(measured(listOf(out.copy(roaming = true))).isEmpty())
        assertEquals(mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out, incoming.copy(severalCalls = true))))
        assertEquals(mapOf("volte:on" to WeekReports.CALLS_OUT), measured(listOf(out, incoming.copy(fromMailbox = true))))
        assertTrue(measured(listOf(out.copy(stack = "phh_ims"))).isEmpty())
        // A call whose bearer proof did not count holds no rung.
        assertTrue(measured(listOf(out.copy(bearer = null))).isEmpty())
        val merged = WeekReports.merged(mapOf("volte:on" to "signed_in"), mapOf("volte:on" to "works"), "phh_ims")
        assertEquals("an uncalibrated stack keeps what the light check saw", mapOf("volte:on" to "signed_in"), merged)
        assertEquals(mapOf("volte:on" to "works"), WeekReports.merged(mapOf("volte:on" to "signed_in"), mapOf("volte:on" to "works"), "aosp_imsstack"))
        val calls = File("src/main/java/io/github/open_carrier_data/check/owner/OwnerCalls.kt").readText()
        assertTrue("read as the call starts", calls.indexOf("val roaming = QuietReader.roaming(context)") in 1 until calls.indexOf("while (SystemClock.elapsedRealtime() - started < MAX_MS)"))
    }

    @Test
    fun `calls_out is a result of a calibrated stack only, and only the rung leaves`() {
        assertTrue(WeekReports.CALLS_OUT in WeekReports.RESULTS && WeekReports.CALLS_OUT !in WeekReports.UNCALIBRATED_RESULTS)
        assertTrue(WeekReports.clean(report(mapOf("volte:on" to WeekReports.CALLS_OUT))!!, schema))
        assertNull(report(mapOf("volte:on" to WeekReports.CALLS_OUT), c = cell.copy(imsStack = "phh_ims")))
        val store = MemoryStore(OwnerState(calls = listOf(out, incoming), week = yes()))
        val fake = Fake()
        step(store, fake)
        assertEquals(mapOf("volte:on" to "works"), pairs(Json.parseToJsonElement(fake.posts.single()).jsonObject))
        for (never in listOf("packet", "duration", "count", "direction", "outgoing")) assertFalse(never, fake.posts.single().contains(never, ignoreCase = true))
    }

    @Test
    fun `the words say what the rungs mean and what a yes reveals`() {
        for (needed in listOf("calls both ways by packet counts", "outgoing only", "No sound was checked", "nothing here speaks for emergency calls", "the flag shows that calls happened that week")) {
            assertTrue(needed, needed in WeekQuestion.DETAILS_TEXT)
            assertTrue(needed, needed in WeekWords.MEANS)
        }
        assertTrue(File("src/main/java/io/github/open_carrier_data/check/owner/OwnerScreen.kt").readText().contains("Summary(WeekWords.MEANS)"))
    }

    @Test
    fun `an emergency call leaves nothing to count`() {
        val calls = File("src/main/java/io/github/open_carrier_data/check/owner/core/Calls.kt").readText()
        assertTrue(calls.contains("if (summary.emergencySeen) return null"))
        val code = File("src/main/java/io/github/open_carrier_data/check/owner/core/WeekReport.kt").readText()
        assertFalse(code.contains("emergencySessions") || code.contains("EmergencyData"))
    }

    @Test
    fun `a measured call result outweighs the passive one, and a row counts only when a test inserted it`() {
        val basis = TestBasis("r", rom, "a")
        val inserted = EntryRecord(sim, "apn:$row", from + day, basis, works = false, outcome = "inserted")
        val present = EntryRecord(sim, "apn:other.example|default|IP", from + day, basis, works = true, outcome = "present")
        val state = OwnerState(entries = listOf(inserted, present), calls = listOf(out, incoming))
        assertEquals(mapOf("volte:on" to Pointers.WORKS, "apn:$row" to Pointers.FAILED), WeekReports.measured(state, sim, from, monday, rom))
        val store = MemoryStore(state.copy(week = yes()))
        val fake = Fake(rows = setOf(row))
        step(store, fake)
        val sent = Json.parseToJsonElement(fake.posts.single()).jsonObject
        assertEquals(mapOf("volte:on" to "works", "apn:$row" to "failed"), pairs(sent))
    }

    // --- the clock ---

    @Test
    fun `weeks are ISO weeks in UTC, and a report names the last closed one`() {
        assertEquals(current, WeekClock.of(now))
        assertEquals(closed, WeekClock.closed(now))
        assertEquals(monday, WeekClock.startMs(now))
        assertEquals(current, WeekClock.of(monday))
        assertEquals(closed, WeekClock.of(monday - 1))
        // 2027-01-01 is a Friday in 2026-W53.
        assertEquals("2026-W53", WeekClock.of(1_798_761_600_000L))
    }

    @Test
    fun `an old state file reads, and the buffer leaves no ID behind on the wire`() {
        assertEquals(WeekState(), OwnerState.decode("{}").week)
        val state = OwnerState(week = yes())
        assertEquals(state.week, OwnerState.decode(state.encode()).week)
        val text = WeekReports.text(report(mapOf("volte:on" to "signed_in"))!!)
        assertFalse("subId" in text)
    }
}
