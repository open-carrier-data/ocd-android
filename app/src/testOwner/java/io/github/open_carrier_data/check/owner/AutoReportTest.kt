package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.BearerProof
import io.github.open_carrier_data.check.core.BearerReading
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallQualityReading
import io.github.open_carrier_data.check.core.DisconnectReading
import io.github.open_carrier_data.check.core.ImsTransport
import io.github.open_carrier_data.check.core.MediaRule
import io.github.open_carrier_data.check.core.MediaWindowBuilder
import io.github.open_carrier_data.check.core.PrivacyScan
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.RtpFlow
import io.github.open_carrier_data.check.owner.core.AutoReport
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.EntryResult
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PostResult
import io.github.open_carrier_data.check.owner.core.ReportCell
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SchemaSubset
import io.github.open_carrier_data.check.owner.core.SelfSmsCheck
import io.github.open_carrier_data.check.owner.core.Settled
import io.github.open_carrier_data.check.owner.core.SettledLevel
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TokenForm
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * OCD round 29, "corrections into the database": the settled results the pointer report
 * carries (a pass at L2 or L3, SMS both ways, a clean measured failure under rounds 26 to 28),
 * the automatic report that sends only those with the owner's token, and the token's form. The
 * reports are checked against the copy of OCD's public schema in testOwner/resources/public.
 */
class AutoReportTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/public/$name")) { name }.use { it.readBytes().decodeToString() }

    private val schema = Json.parseToJsonElement(resource("ocd-check-report.schema.json")).jsonObject
    private val form = resource("ocd-check-report.yml")

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = TimeUnit.DAYS.toMillis(1)
    private val sim = SimKey(3, "26202", 2397)
    private val rom = "samsung/x1sxxx/x1s:16/CP2A.260605.016/1792814506:userdebug/test-keys@1792814506000"
    private val build = "CP2A.260605.016 1792814506"
    private val digest = "sha256:36d5f7ebb084a1a915c4961f8a5f3a11c70150b0fca7f68ccc050ffbe3989d37"
    private val commit = "e32fd75687d01e7271b640c3adb41c52d42af6a8"
    private val profile = "open.26202.8cebd7550c67"

    /** 2026-10-24 03:00 in Berlin. */
    private val t0 = ZonedDateTime.of(2026, 10, 24, 3, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun reading(bearer: ProvenBearer) =
        if (bearer == ProvenBearer.IWLAN) BearerReading(bearer.callNetworkType, listOf(ImsTransport.IWLAN), "lte") else BearerReading(bearer.callNetworkType, listOf(ImsTransport.LTE), "lte")

    /** One measured call on AOSP ImsStack's counters (round 28's media rule), as ProvisionalTest builds them. */
    private fun call(
        at: Long,
        direction: CallDirection,
        bearer: ProvenBearer = ProvenBearer.LTE,
        method: CallMethod = CallMethod.REAL_CALL,
        active: Boolean = true,
        activeMs: Long = 45_000,
        rate: Long = 50,
        end: DisconnectReading = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE"),
        hungUp: Boolean? = null,
        build: String = rom,
    ): CallRecord {
        val first = CallQualityReading(1_000, rate, rate)
        val last = CallQualityReading(activeMs, rate * activeMs / 1000, rate * activeMs / 1000)
        val proof = BearerProof.of(active, reading(bearer), reading(bearer))
        val window = MediaWindowBuilder().apply {
            var t = 1_000L
            while (t <= activeMs) {
                add(CallQualityReading(t, rate * t / 1000, rate * t / 1000))
                t += 1_000
            }
        }.window()
        return CallRecord(
            atMs = at, slot = 0, sim = sim, method = method, direction = direction,
            rang = direction == CallDirection.INCOMING, answered = true, active = active,
            bearerAtStart = BearerReading(0, reading(bearer).imsPdn, "lte"), bearerAtActive = reading(bearer), bearerAtHangUp = reading(bearer),
            bearer = proof,
            rtp = if (active) RtpFlow.of(first, last, false) else null,
            media = if (active) MediaRule.judge(window, CallRecord.kindOf(method, direction), "phh_ims", build, proof, end) else null,
            activeMs = if (active) activeMs else null,
            disconnect = end, disconnectFresh = true, hungUpByPhone = hungUp,
            fromMailbox = if (direction == CallDirection.INCOMING) false else null,
            build = build, stack = "aosp_imsstack",
        )
    }

    private fun mailbox(at: Long, bearer: ProvenBearer = ProvenBearer.LTE) =
        call(at, CallDirection.OUTGOING, bearer, CallMethod.MAILBOX_CALL, activeMs = 15_000, end = DisconnectReading(501, "CODE_USER_TERMINATED"), hungUp = true)

    private val cell = ReportCell(
        model = "SM-G981B", codename = "x1s", androidMajor = 16, romFamily = "lineageos", buildType = "userdebug", imsStack = "aosp_imsstack",
        spn = "LIDL Connect", appVersion = "0.3.0-owner", dataDigest = digest, dataSource = "pulled", dataCommit = commit, romBuild = build,
    )

    private fun run(startedMs: Long = t0) = RunResult(
        id = "r29", startedMs = startedMs, scheduled = true, sim = sim, simName = "LIDL Connect", profileId = profile,
        features = listOf(
            FeatureResult(OwnerFeature.VOLTE, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.SMS_OVER_IMS, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.VOWIFI, true, true, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), offReason = "off_tests_not_chosen", best = true),
            FeatureResult(OwnerFeature.VONR, false, false, OcdAnswer(true, "carrier_profile", profile, capability = "supported"), onWorks = false, onReason = "device_not_capable", best = false),
        ),
        cell = cell,
    )

    private val calls = emptyList<CallsTested>()

    // --- the levels the report carries ---

    @Test
    fun `calls out is L2 with one leg, an answered incoming call on the same bearer makes it L3`() {
        val out = mailbox(t0 - day)
        val l2 = Settled.voice(listOf(out), sim, OwnerFeature.VOLTE, rom, 0, t0, zone)!!
        assertEquals(SettledLevel("l2", "lte", "mailbox_call", "2026-10-23"), l2)
        val incoming = call(t0 - 1_800_000, CallDirection.INCOMING)
        val l3 = Settled.voice(listOf(out, incoming), sim, OwnerFeature.VOLTE, rom, 0, t0, zone)!!
        assertEquals(SettledLevel("l3", "lte", "mailbox_call+real_call", "2026-10-24"), l3)
        // Another bearer, another build, a call before the value took effect, a future call or VoNR: nothing.
        assertNull(Settled.voice(listOf(out), sim, OwnerFeature.VOWIFI, rom, 0, t0, zone))
        assertNull(Settled.voice(listOf(out.copy(build = "other")), sim, OwnerFeature.VOLTE, rom, 0, t0, zone))
        assertNull(Settled.voice(listOf(out), sim, OwnerFeature.VOLTE, rom, t0 - 3_600_000, t0, zone))
        assertNull(Settled.voice(listOf(mailbox(t0 + day)), sim, OwnerFeature.VOLTE, rom, 0, t0, zone))
        assertNull(Settled.voice(listOf(out), sim, OwnerFeature.VONR, rom, 0, t0, zone))
        // A level holds 90 days.
        assertNull(Settled.voice(listOf(out), sim, OwnerFeature.VOLTE, rom, 0, t0 + 91 * day, zone))
    }

    @Test
    fun `a clean measured failure settles when it is newer than the newest pass`() {
        val pass = mailbox(t0 - 2 * day, ProvenBearer.IWLAN)
        val failedMailbox = call(t0 - day, CallDirection.OUTGOING, ProvenBearer.IWLAN, CallMethod.MAILBOX_CALL, active = false, end = DisconnectReading(501, "CODE_USER_TERMINATED"))
        val failed = Settled.voice(listOf(pass, failedMailbox), sim, OwnerFeature.VOWIFI, rom, 0, t0, zone)!!
        assertEquals(SettledLevel("l2", "iwlan", "mailbox_call", "2026-10-23", failed = true, reason = "calls_out_failed"), failed)
        // A newer pass wins again.
        val again = mailbox(t0 - 3_600_000, ProvenBearer.IWLAN)
        assertEquals("l2", Settled.voice(listOf(pass, failedMailbox, again), sim, OwnerFeature.VOWIFI, rom, 0, t0, zone)!!.let { if (it.failed) "failed" else it.level })
        // A measured incoming failure: answered, never ACTIVE.
        val dropped = call(t0 - 3_600_000, CallDirection.INCOMING, ProvenBearer.IWLAN, active = false)
        val incoming = Settled.voice(listOf(pass, dropped), sim, OwnerFeature.VOWIFI, rom, 0, t0, zone)!!
        assertEquals(SettledLevel("l3", "iwlan", "real_call", "2026-10-24", failed = true, reason = "incoming_failed_answered_not_active"), incoming)
        // A real call that was not answered by the callee says nothing about this phone.
        assertNull(Settled.voice(listOf(call(t0 - day, CallDirection.OUTGOING, active = false)), sim, OwnerFeature.VOLTE, rom, 0, t0, zone))
    }

    @Test
    fun `SMS both ways settles only with IMS read on the way out and back, on this build`() {
        val both = SelfSmsCheck(sim, t0 - day, "0123456789abcdef", "received", receivedAtMs = t0 - day + 5_000, imsAtSend = true, imsAtReceive = true, build = rom, wifiAtSend = false)
        assertEquals(SettledLevel("l3", "lte", "self_sms", "2026-10-23"), Settled.sms(listOf(both), sim, rom, t0))
        assertNull("IMS not read on the way back (today's receiver)", Settled.sms(listOf(both.copy(imsAtReceive = null)), sim, rom, t0))
        assertNull(Settled.sms(listOf(both.copy(result = "not_received")), sim, rom, t0))
        assertNull(Settled.sms(listOf(both.copy(build = null)), sim, rom, t0))
        assertNull(Settled.sms(listOf(both.copy(build = "other")), sim, rom, t0))
    }

    @Test
    fun `the run's own result decides whether a level attaches`() {
        val signedIn = EntryResult("volte:on", ocd = true, phone = true, tested = false, result = "signed_in", reason = null)
        val l3 = SettledLevel("l3", "lte", "mailbox_call+real_call", "2026-10-24")
        assertEquals(EntryResult("volte:on", true, true, false, "works", null, calls = listOf("t2" to "lte"), settled = l3), Settled.apply(signedIn, l3))
        val l2 = SettledLevel("l2", "lte", "real_call", "2026-10-24")
        assertEquals("signed_in", Settled.apply(signedIn, l2).result)
        val fail = SettledLevel("l2", "lte", "mailbox_call", "2026-10-24", failed = true, reason = "calls_out_failed")
        val kept = signedIn.copy(phone = false, tested = true, kept = true, restored = true)
        val failed = Settled.apply(kept, fail)
        assertEquals(listOf("failed", "calls_out_failed"), listOf(failed.result, failed.reason))
        assertFalse("a failed value is not kept", failed.kept)
        // A value that did not sign in, or was not tested, keeps the run's own result and carries no level.
        val notSignedIn = signedIn.copy(result = "failed", reason = "on_not_signed_in")
        assertEquals(notSignedIn, Settled.apply(notSignedIn, l3))
        val sms = EntryResult("sms_over_ims:on", true, true, false, "signed_in", null)
        assertEquals("works", Settled.apply(sms, SettledLevel("l3", null, "self_sms", "2026-10-24")).result)
    }

    @Test
    fun `levels attach only to a run on this ROM build that names the data commit`() {
        val state = OwnerState(calls = listOf(mailbox(t0 - day), call(t0 - 3_600_000, CallDirection.INCOMING)))
        val found = Settled.forRun(run(), state, rom, build, t0, zone)
        assertEquals(setOf("volte:on"), found.keys)
        assertEquals("l3", found.getValue("volte:on").level)
        assertEquals(emptyMap<String, SettledLevel>(), Settled.forRun(run(), state, rom, "CP2A.260605.016 1793000000", t0, zone))
        assertEquals(emptyMap<String, SettledLevel>(), Settled.forRun(run().copy(cell = cell.copy(dataCommit = null, dataRelease = "data-2026.10")), state, rom, build, t0, zone))
        // A value Open Carrier Data keeps counts calls only since it took effect.
        val keep = LiveOverride(slot = 0, sim = sim, key = OwnerFeature.VOLTE.key, value = true, hold = Hold.KEPT, sinceMs = t0 - 2 * day)
        assertEquals("l3", Settled.forRun(run(), state.copy(overrides = listOf(keep)), rom, build, t0, zone).getValue("volte:on").level)
        assertNull("the outgoing call came before the keep", Settled.forRun(run(), state.copy(overrides = listOf(keep.copy(sinceMs = t0 - 3 * 3_600_000))), rom, build, t0, zone)["volte:on"])
    }

    // --- the report ---

    private val settled = mapOf(
        "volte:on" to SettledLevel("l3", "lte", "mailbox_call+real_call", "2026-10-24"),
        "vowifi:on" to SettledLevel("l2", "iwlan", "mailbox_call", "2026-10-24"),
    )

    @Test
    fun `the prefilled report carries the levels and the build and passes the public schema`() {
        val report = IssueReport.report(run(), cell, calls, settled = settled)!!
        assertEquals(emptyList<String>(), SchemaSubset.problems(report, schema))
        assertEquals(build, report.getValue("device").jsonObject.getValue("build").jsonPrimitive.content)
        val byEntry = report.getValue("results").jsonArray.associate { it.jsonObject.getValue("entry").jsonPrimitive.content to it.jsonObject }
        assertEquals("""{"level":"l3","bearer":"lte","method":"mailbox_call+real_call","measured_on":"2026-10-24"}""", byEntry.getValue("volte:on")["settled"].toString())
        assertEquals("works", byEntry.getValue("volte:on").getValue("result").jsonPrimitive.content)
        assertEquals("""[{"test":"t2","network":"lte"}]""", byEntry.getValue("volte:on")["call_types"].toString())
        assertEquals("signed_in", byEntry.getValue("vowifi:on").getValue("result").jsonPrimitive.content)
        assertNull(byEntry.getValue("vonr:on")["settled"])
        assertTrue("the build is a fixed-shape field, not a phone number", PrivacyScan.scan(report, hex = PrivacyScan.hexFields + "report.device.build").isEmpty())
        assertNotNull(IssueReport.prefill(run(), cell, calls, settled))
        // Without a level nothing changes, and a run before round 29 names no build.
        val plain = IssueReport.report(run(), cell.copy(romBuild = null), calls)!!
        assertNull(plain.getValue("device").jsonObject["build"])
        assertEquals(emptyList<String>(), SchemaSubset.problems(plain, schema))
    }

    @Test
    fun `the automatic report carries only the settled results, in the form's shape`() {
        val issue = IssueReport.automatic(run(), cell, calls, settled)!!
        assertEquals("[OCD Check report] SM-G981B on 26202 (LIDL Connect)", issue.title)
        val report = Json.parseToJsonElement(issue.text).jsonObject
        assertEquals(listOf("volte:on", "vowifi:on"), report.getValue("results").jsonArray.map { it.jsonObject.getValue("entry").jsonPrimitive.content })
        assertEquals(emptyList<String>(), SchemaSubset.problems(report, schema))
        // The body GitHub writes for the form: the bot reads the report under "Report" and a ticked box under "Privacy check".
        assertTrue(Regex("(?m)^      label: ${IssueReport.REPORT_HEADING}$").containsMatchIn(form))
        assertTrue(Regex("(?m)^      label: ${IssueReport.PRIVACY_HEADING}$").containsMatchIn(form))
        assertTrue(issue.body.startsWith("### Report\n\n```json\n{\n"))
        assertTrue(issue.body.contains("\n```\n\n### Privacy check\n\n- [X] "))
        assertEquals(IssueReport.hash(issue.text), issue.hash)
        // The same body, for a check with the public bot's tool (tools/observed_reports.py check_issue).
        java.io.File("build/test-results").mkdirs()
        java.io.File("build/test-results/ocd-check-auto-issue.md").writeText(issue.body)
        // The bot scans the text outside the report too: no identifier, and never the word its scan reads as a secret.
        val outside = issue.body.replace(issue.text, "")
        assertTrue(PrivacyScan.scanValue("body", outside).isEmpty())
        assertFalse(outside.contains("token", ignoreCase = true))
        assertNull("nothing settled: nothing to send", IssueReport.automatic(run(), cell, calls, emptyMap()))
        assertNull("an identifier stops it", IssueReport.automatic(run(), cell.copy(spn = "+491701234567"), calls, settled))
    }

    // --- what is sent, and when ---

    @Test
    fun `a settled result is sent once, again only when it changes or after 14 days`() {
        val state = OwnerState(runs = listOf(run()))
        val plan = AutoReport.plan(state, { calls }, { settled }, t0)
        assertEquals(1, plan.size)
        assertEquals(2, plan[0].keys.size)
        val sent = AutoReport.after(state, plan[0], PostResult.Created(301), t0, zone)
        assertEquals("Sent #301 on 2026-10-24", sent.autoLine)
        assertTrue("the owner's own report is not others'", plan[0].issue.hash in sent.reportsOpened)
        assertEquals(listOf(301, 301), sent.autoSent.map { it.issue })
        assertTrue(sent.log.last().text.startsWith("sent report #301 automatically (sha256:"))
        assertEquals(emptyList<AutoReport.Due>(), AutoReport.plan(sent, { calls }, { settled }, t0 + 13 * day))
        assertEquals(1, AutoReport.plan(sent, { calls }, { settled }, t0 + 14 * day).size)
        // Calls out becomes works: a new key, sent at once.
        val works = settled + ("vowifi:on" to SettledLevel("l3", "iwlan", "mailbox_call+real_call", "2026-10-25"))
        val next = AutoReport.plan(sent, { calls }, { works }, t0 + day)
        assertEquals(1, next.size)
        assertEquals(listOf("vowifi:on"), AutoReport.due(next[0].keys, sent.autoSent, t0 + day).map { it.split("|")[6] })
        // Nothing settled, or the newest run of the SIM without a cell: nothing.
        assertEquals(emptyList<AutoReport.Due>(), AutoReport.plan(state, { calls }, { emptyMap() }, t0))
    }

    @Test
    fun `a refused token stops sending until a token is entered again, and nothing logs the token`() {
        val state = OwnerState(runs = listOf(run()))
        val due = AutoReport.plan(state, { calls }, { settled }, t0).single()
        val refused = AutoReport.after(state, due, PostResult.Refused(401), t0, zone)
        assertNotNull(refused.autoRefusedAtMs)
        assertTrue(refused.autoLine!!.startsWith("GitHub refused the token (HTTP 401)"))
        assertEquals(emptyList<AutoReport.Due>(), AutoReport.plan(refused, { calls }, { settled }, t0 + day))
        assertEquals(1, AutoReport.plan(refused.copy(autoRefusedAtMs = null), { calls }, { settled }, t0 + day).size)
        val failed = AutoReport.after(state, due, PostResult.Failed("no connection (UnknownHostException)"), t0, zone)
        assertEquals(emptyList<String>(), failed.autoSent.map { it.key })
        val everything = listOf(refused, failed).joinToString { it.encode() }
        assertFalse(everything.contains("github_pat_"))
        assertFalse(everything.contains("Bearer"))
    }

    @Test
    fun `only a fine-grained token is taken`() {
        assertNull(TokenForm.problem("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab"))
        assertNull("blank space around a pasted token is fine", TokenForm.problem("  github_pat_11ABCDEFG0123456789_abcdefghij \n"))
        assertTrue(TokenForm.problem("ghp_0123456789abcdefghijklmnopqrstuvwxyzAB")!!.startsWith("This is a classic token"))
        assertEquals("Enter the token", TokenForm.problem(" "))
        val wrong = TokenForm.problem("not-a-token-1234")!!
        assertFalse("the reason never echoes the text", wrong.contains("not-a-token"))
    }

    @Test
    fun `the build is named without the fingerprint, the incremental or the builder`() {
        assertEquals(build, Settled.publicBuild("CP2A.260605.016", 1_792_814_506_123L))
        assertNull(Settled.publicBuild("eng.bela 20261024", 1_792_814_506_000L))
        assertNull(Settled.publicBuild("", 1_792_814_506_000L))
        val pattern = schema.getValue("properties").jsonObject.getValue("device").jsonObject.getValue("properties").jsonObject
            .getValue("build").jsonObject.getValue("pattern").jsonPrimitive.content
        assertEquals("^" + Settled.BUILD.pattern + "$", pattern)
        assertTrue(SchemaSubset.problems(JsonObject(IssueReport.report(run(), cell, calls)!!.toMutableMap()), schema).isEmpty())
    }
}
