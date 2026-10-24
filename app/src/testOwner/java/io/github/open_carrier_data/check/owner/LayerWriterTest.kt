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
import io.github.open_carrier_data.check.core.RtpFlow
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.CallsOutNight
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.LayerAction
import io.github.open_carrier_data.check.owner.core.LayerCheck
import io.github.open_carrier_data.check.owner.core.LayerClass
import io.github.open_carrier_data.check.owner.core.LayerClient
import io.github.open_carrier_data.check.owner.core.LayerData
import io.github.open_carrier_data.check.owner.core.LayerEntryView
import io.github.open_carrier_data.check.owner.core.LayerFirstUse
import io.github.open_carrier_data.check.owner.core.LayerInputs
import io.github.open_carrier_data.check.owner.core.LayerKeys
import io.github.open_carrier_data.check.owner.core.LayerLevel
import io.github.open_carrier_data.check.owner.core.LayerPlan
import io.github.open_carrier_data.check.owner.core.LayerPlanner
import io.github.open_carrier_data.check.owner.core.LayerProof
import io.github.open_carrier_data.check.owner.core.LayerPut
import io.github.open_carrier_data.check.owner.core.LayerRecord
import io.github.open_carrier_data.check.owner.core.LayerResult
import io.github.open_carrier_data.check.owner.core.LayerSimInput
import io.github.open_carrier_data.check.owner.core.LayerSimView
import io.github.open_carrier_data.check.owner.core.LayerStatus
import io.github.open_carrier_data.check.owner.core.LayerValues
import io.github.open_carrier_data.check.owner.core.LayerWriter
import io.github.open_carrier_data.check.owner.core.LiveOverride
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.ProvisionalKeep
import io.github.open_carrier_data.check.owner.core.Provisional
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.SelfSmsCheck
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.StatusSentence
import io.github.open_carrier_data.check.owner.core.UndoneEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** A stand-in for CarrierSettings's provider for one SIM: it serves what it holds into the fake phone's own-bundle layer, with the suffix. */
class FakeLayer(private val phone: FakePhone, var available: Boolean = true) : LayerClient {
    var firstUse = setOf(1, 2, 3, 4)
    var stopFlag: String? = null
    var liveness = 0L
    val entries = linkedMapOf<String, LayerPut>()
    var baseline = mapOf<String, JsonElement?>(OwnerFeature.SMS_OVER_IMS.key to JsonPrimitive(false), OwnerFeature.VOLTE.key to JsonPrimitive(false))
    var carried = setOf<String>()
    var leases = mutableMapOf<String, Long>()
    /** False: the reload "happens" but the values never show (an apply that does not take). */
    var shows = true
    /** False: removals do not show either (a revert that cannot be proven). */
    var removalsShow = true
    var waitingFor: String? = null
    val calls = mutableListOf<String>()

    private val base = mapOf("carrier_config_version_string" to "vodafone_de-1080000000010", OwnerFeature.SMS_OVER_IMS.key to "false", OwnerFeature.VOLTE.key to "false")

    fun suffix(): String? = if (entries.isEmpty()) null else "+ocd${entries.size}-0123456"

    private fun serve(applies: Boolean) {
        if (!applies) return
        phone.defaultApp = base + entries.mapValues { LayerValues.dumpForm(it.value.value)!! } +
            (suffix()?.let { mapOf("carrier_config_version_string" to base.getValue("carrier_config_version_string") + it) } ?: emptyMap())
    }

    override fun status() = if (!available) LayerStatus.absent("not on this ROM build") else LayerStatus(
        available = true, stop = stopFlag, firstUse = firstUse, ownerOk = true, livenessUntilMs = liveness, entryCount = entries.size,
    )

    override fun list(subId: Int): LayerSimView? = if (!available) null else LayerSimView(
        entries.values.map { p ->
            LayerEntryView(
                p.key, p.type, p.value, p.cls.wire, p.origin, p.level.wire, p.sinceMs, leases[p.key] ?: p.leaseUntilMs, p.voiceMeasured, p.smsMeasured,
                p.bearers, p.method, p.ocdValue, "eng.1", "s:1", p.key in carried, baseline[p.key], served = true, state = "served",
            )
        },
        servedSuffix = suffix().orEmpty(),
    )

    override fun put(subId: Int, profile: String, data: LayerData, entries: List<LayerPut>, livenessUntilMs: Long): LayerResult {
        calls += "put ${entries.joinToString { it.key }}"
        if (stopFlag != null) return LayerResult(false, "stop flag set")
        entries.forEach { this.entries[it.key] = it }
        liveness = livenessUntilMs
        waitingFor?.let { return LayerResult(true, pending = true, waitingFor = it, suffix = suffix()) }
        serve(shows)
        return LayerResult(true, reloaded = true, suffix = suffix())
    }

    override fun remove(subId: Int, keys: List<String>?, reason: String): LayerResult {
        calls += "remove ${keys?.joinToString()}"
        val n = keys?.count { entries.remove(it) != null } ?: entries.size.also { entries.clear() }
        waitingFor?.let { return LayerResult(true, pending = true, waitingFor = it, count = n) }
        serve(removalsShow)
        return LayerResult(true, reloaded = true, count = n)
    }

    override fun renew(subId: Int, keys: List<String>, untilMs: Long): LayerResult {
        calls += "renew ${keys.joinToString()}"
        keys.forEach { leases[it] = untilMs }
        return LayerResult(true, count = keys.size)
    }

    override fun liveness(untilMs: Long): LayerResult {
        calls += "liveness"
        liveness = untilMs
        return LayerResult(true)
    }

    override fun stop(reason: String): LayerResult {
        calls += "stop"
        stopFlag = reason
        entries.clear()
        serve(true)
        return LayerResult(true, reloaded = true)
    }

    override fun clearAll(reason: String): LayerResult {
        calls += "clear"
        val n = entries.size
        entries.clear()
        waitingFor?.let { return LayerResult(true, pending = true, waitingFor = it, count = n) }
        serve(true)
        return LayerResult(true, reloaded = true, count = n)
    }

    override fun clearStop(): LayerResult {
        stopFlag = null
        return LayerResult(true)
    }
}

class LayerWriterTest {
    private val sim = OwnerFixtures.SIM
    private val key = sim.key
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = ZonedDateTime.of(2026, 10, 24, 3, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val build = "rom-1"
    private val sms = OwnerFeature.SMS_OVER_IMS.key
    private val volte = OwnerFeature.VOLTE.key
    private val ready = LayerStatus(available = true, firstUse = setOf(1, 2, 3, 4), ownerOk = true, livenessUntilMs = now + day, entryCount = 0)

    private fun reading(bearer: ProvenBearer) =
        if (bearer == ProvenBearer.IWLAN) BearerReading(bearer.callNetworkType, listOf(ImsTransport.IWLAN), "lte") else BearerReading(bearer.callNetworkType, listOf(ImsTransport.LTE), "lte")

    /** A measured call as ProvisionalTest builds one: PHH's once-a-second reports judged by round 28's media rule. */
    private fun call(at: Long, direction: CallDirection, bearer: ProvenBearer = ProvenBearer.LTE, answered: Boolean = true, active: Boolean = true, activeMs: Long = 45_000, b: String = build): CallRecord {
        val end = DisconnectReading(510, "CODE_USER_TERMINATED_BY_REMOTE")
        val atActive = reading(bearer)
        val proof = BearerProof.of(active, atActive, atActive)
        val window = MediaWindowBuilder().apply {
            var t = 1_000L
            while (t <= activeMs) {
                add(CallQualityReading(t, 50 * t / 1000, 50 * t / 1000))
                t += 1_000
            }
        }.window()
        return CallRecord(
            atMs = at, slot = 0, sim = key, method = CallMethod.REAL_CALL, direction = direction,
            rang = direction == CallDirection.INCOMING, answered = answered, active = active,
            bearerAtStart = BearerReading(0, atActive.imsPdn, "lte"), bearerAtActive = atActive, bearerAtHangUp = atActive, bearer = proof,
            rtp = if (active) RtpFlow.of(CallQualityReading(1_000, 50, 50), CallQualityReading(activeMs, 50 * activeMs / 1000, 50 * activeMs / 1000), false) else null,
            media = if (active) MediaRule.judge(window, CallRecord.kindOf(CallMethod.REAL_CALL, direction), "phh_ims", b, proof, end) else null,
            activeMs = if (active) activeMs else null, disconnect = end, disconnectFresh = true,
            fromMailbox = if (direction == CallDirection.INCOMING) false else null, build = b, stack = "phh_ims",
        )
    }

    private fun input(
        state: OwnerState,
        view: LayerSimView? = LayerSimView(),
        refusal: Refusal? = null,
        observed: Map<String, Boolean> = emptyMap(),
        below: Map<String, String> = mapOf(sms to "false", volte to "false"),
        voiceLost: Boolean? = false,
        status: LayerStatus = ready,
        quiet: Boolean = true,
        at: Long = now,
        b: String = build,
    ) = LayerInputs(
        listOf(
            LayerSimInput(
                sim, view, below, mapOf(OwnerFeature.VOLTE to true, OwnerFeature.VOWIFI to false), observed, healthy = true, voiceLost = voiceLost,
                refusal = refusal, answers = mapOf(OwnerFeature.SMS_OVER_IMS to OcdAnswer(true, "carrier_profile"), OwnerFeature.VOLTE to OcdAnswer(true, "carrier_profile")),
                profileId = "de.lidl",
            ),
        ),
        state, status, b, at, quiet, zone,
    )

    private fun keep(k: String, value: Boolean = true, since: Long = now - day) = LiveOverride(0, key, k, value, Hold.KEPT, since, before = "false")

    private fun sent(at: Long, received: Boolean) =
        SelfSmsCheck(
            key, at, "0123456789abcdef", if (received) "received" else "sent", if (received) at + 5_000 else null, imsAtSend = true,
            imsAtReceive = if (received) true else null, sentVia = SelfSms.IMS, receivedVia = if (received) SelfSms.IMS else null,
        )

    private fun entry(k: String, value: Boolean, level: LayerLevel = LayerLevel.DURABLE, cls: LayerClass = LayerKeys.classOf(k)!!, since: Long = now - day, lease: Long = now + 5 * day, baseline: Boolean? = false, carried: Boolean = false, state: String = "served", bearers: List<String> = emptyList(), method: String = "") =
        LayerEntryView(k, "bool", JsonPrimitive(value), cls.wire, "ocd", level.wire, since, lease, bearers = bearers, method = method, baseline = baseline?.let(::JsonPrimitive), carriedOver = carried, served = state == "served", state = state)

    // --- keys and classes ---

    @Test
    fun `keys - classes, and never a safety key, an emergency-path key or VoNR`() {
        assertEquals(LayerClass.VOICE_IMS, LayerKeys.classOf(volte))
        assertEquals(LayerClass.VOICE_IMS, LayerKeys.classOf(OwnerFeature.VOWIFI.key))
        assertEquals(LayerClass.VOICE_IMS, LayerKeys.classOf("ims.sip_over_ipsec_enabled_bool"))
        assertEquals(LayerClass.SMS, LayerKeys.classOf(sms))
        assertEquals(LayerClass.VIDEO, LayerKeys.classOf(OwnerFeature.VIDEO_CALLING.key))
        assertEquals(LayerClass.OTHER, LayerKeys.classOf("show_ims_registration_status_bool"))
        for (k in listOf("vonr_enabled_bool", "carrier_use_ims_first_for_emergency_bool", "iwlan.epdg_static_address_string", "rtt_supported_bool", "call_forwarding_blocks_while_roaming_string_array")) {
            assertNull(k, LayerKeys.classOf(k))
            assertNotNull(k, LayerKeys.guard(k))
        }
        assertEquals(setOf(ProvenBearer.LTE), LayerKeys.bearers(volte, emptyMap()))
        assertEquals(setOf(ProvenBearer.LTE, ProvenBearer.IWLAN), LayerKeys.bearers("ims.sip_over_ipsec_enabled_bool", mapOf(OwnerFeature.VOLTE to true, OwnerFeature.VOWIFI to true)))
    }

    @Test
    fun `the put object has exactly CarrierSettings's entry fields`() {
        val put = LayerPut(sms, "bool", JsonPrimitive(true), LayerClass.SMS, "ocd", LayerLevel.DURABLE, now, now + day)
        assertEquals(
            setOf("key", "type", "value", "class", "origin", "level", "since_ms", "lease_until_ms", "voice_measured", "sms_measured", "bearers", "method", "ocd_value", "build", "stack"),
            put.json().keys,
        )
    }

    // --- the class checks ---

    @Test
    fun `voice - provisional after calls out on the bearer, durable after works`() {
        val out = call(now - 3_600_000, CallDirection.OUTGOING)
        val incoming = call(now - 1_800_000, CallDirection.INCOMING)
        assertNull(LayerCheck.voice(emptyList(), key, setOf(ProvenBearer.LTE), build, now - day, now))
        assertEquals(LayerLevel.PROVISIONAL, LayerCheck.voice(listOf(out), key, setOf(ProvenBearer.LTE), build, now - day, now))
        assertEquals(LayerLevel.DURABLE, LayerCheck.voice(listOf(out, incoming), key, setOf(ProvenBearer.LTE), build, now - day, now))
        // Per build: a call on another ROM build counts nothing; every bearer it affects needs its own calls.
        assertNull(LayerCheck.voice(listOf(out.copy(build = "rom-0")), key, setOf(ProvenBearer.LTE), build, now - day, now))
        assertNull(LayerCheck.voice(listOf(out), key, setOf(ProvenBearer.LTE, ProvenBearer.IWLAN), build, now - day, now))
        assertEquals("real_call+real_call", LayerCheck.voiceMethod(listOf(out, incoming), key, setOf(ProvenBearer.LTE), build, now - day, now))
    }

    @Test
    fun `sms, video and no regression`() {
        assertNull(LayerCheck.sms(emptyList(), key, true, now - day))
        assertEquals(LayerLevel.PROVISIONAL, LayerCheck.sms(listOf(sent(now - 600_000, false)), key, true, now - day))
        assertEquals(LayerLevel.DURABLE, LayerCheck.sms(listOf(sent(now - 600_000, true)), key, true, now - day))
        // Only what happened since the value took effect counts; an off needs an SMS delivered while off.
        assertNull(LayerCheck.sms(listOf(sent(now - 2 * day, true)), key, true, now - day))
        assertEquals(LayerLevel.DURABLE, LayerCheck.sms(listOf(sent(now - 600_000, true)), key, false, now - day))
        // Durable needs SMS both ways by Telephony's word on each leg; IMS registered at both ends is not enough (2026-10-24).
        val registeredOnly = sent(now - 600_000, true).copy(sentVia = null, receivedVia = null)
        assertNull(LayerCheck.sms(listOf(registeredOnly), key, true, now - day))
        assertEquals(LayerLevel.PROVISIONAL, LayerCheck.sms(listOf(sent(now - 600_000, true).copy(receivedVia = null)), key, true, now - day))
        assertEquals(LayerLevel.PROVISIONAL, LayerCheck.sms(listOf(sent(now - 600_000, true).copy(receivedVia = SelfSms.CS)), key, true, now - day))
        // Sent over CS is not sent over IMS, whatever the registration said.
        assertNull(LayerCheck.sms(listOf(sent(now - 600_000, false).copy(sentVia = SelfSms.CS)), key, true, now - day))
        assertNull(LayerCheck.sms(listOf(sent(now - 600_000, true).copy(imsAtSend = false)), key, true, now - day))
        assertEquals(LayerLevel.DURABLE, LayerCheck.video(true, true))
        assertNull(LayerCheck.video(true, false))
        val before = io.github.open_carrier_data.check.owner.core.ImsShape(true, false, setOf("mmtel", "smsip"))
        assertTrue(LayerCheck.noRegression(before, before, true))
        assertFalse(LayerCheck.noRegression(before, before.copy(services = setOf("mmtel")), true))
        assertFalse(LayerCheck.noRegression(before, before.copy(wifi = true), true))
        assertFalse(LayerCheck.noRegression(before, before, false))
    }

    // --- the planner: what goes in ---

    @Test
    fun `a keep goes in once its class check passed, at home, in the silent window, after first use`() {
        val state = OwnerState(overrides = listOf(keep(sms)), selfSms = listOf(sent(now - 600_000, false)))
        val plan = LayerPlanner.plan(input(state))
        val put = plan.actions.single() as LayerAction.Put
        assertEquals(listOf(sms), put.entries.map { it.key })
        assertEquals(LayerLevel.PROVISIONAL, put.entries.single().level)
        assertEquals("ocd", put.entries.single().origin)
        assertEquals(listOf(OwnerFeature.SMS_OVER_IMS), put.dropKeeps)
        assertEquals(now + 7 * day, plan.livenessUntilMs)
        // Not while roaming (S7), not in a refusal, not outside the silent window, not before first-use checks 1 to 4 (S15), not after a stop.
        assertTrue(LayerPlanner.plan(input(state, refusal = Refusal.ROAMING)).actions.isEmpty())
        assertTrue(LayerPlanner.plan(input(state, refusal = Refusal.CALL_ACTIVE)).actions.isEmpty())
        assertTrue(LayerPlanner.plan(input(state, quiet = false)).actions.isEmpty())
        assertTrue(LayerPlanner.plan(input(state, status = ready.copy(firstUse = setOf(1, 2, 3)))).actions.isEmpty())
        // Nothing unverified: without its SMS check the keep stays a non-persistent override.
        assertTrue(LayerPlanner.plan(input(state.copy(selfSms = emptyList()))).actions.isEmpty())
        // An entry exists only where it differs from the build's value; an undone value waits for Test now.
        assertTrue(LayerPlanner.plan(input(state, below = mapOf(sms to "true"))).actions.isEmpty())
        assertTrue(LayerPlanner.plan(input(state.copy(undone = listOf(UndoneEntry(key, "sms_over_ims:on", now))))).actions.isEmpty())
        // On a ROM without the layer nothing happens at all.
        assertEquals(LayerPlan(notes = listOf("no runtime layer: not on this ROM build")), LayerPlanner.plan(input(state, status = LayerStatus.absent("not on this ROM build"))))
    }

    @Test
    fun `voice keeps - provisional after calls out with round 26's 7 days, offs only with voice measured, VoNR never`() {
        val applied = now - 2 * day
        val provisional = ProvisionalKeep(0, key, OwnerFeature.VOLTE, true, ProvenBearer.LTE, applied, Provisional.nightEnd(applied, zone), callsOutAtMs = applied + 3_600_000)
        val state = OwnerState(overrides = listOf(keep(volte, since = applied)), provisional = listOf(provisional))
        val put = LayerPlanner.plan(input(state)).actions.single() as LayerAction.Put
        assertEquals(LayerLevel.PROVISIONAL, put.entries.single().level)
        assertEquals(applied + 7 * day, put.entries.single().leaseUntilMs)
        assertEquals(listOf("lte"), put.entries.single().bearers)
        // Without calls out yet: not in.
        assertTrue(LayerPlanner.plan(input(state.copy(provisional = listOf(provisional.copy(callsOutAtMs = null))))).actions.isEmpty())
        // A voice off (baseline on) needs a call that went ACTIVE with it off (X5).
        val off = OwnerState(overrides = listOf(keep(volte, value = false)))
        assertTrue(LayerPlanner.plan(input(off, below = mapOf(volte to "true"))).actions.isEmpty())
        val measured = off.copy(runs = listOf(RunResult("r", now - day, scheduled = false, features = listOf(FeatureResult(OwnerFeature.VOLTE, true, true, OcdAnswer(true, "carrier_profile"), best = false, applied = true, offVoiceMeasured = true)))))
        val offPut = LayerPlanner.plan(input(measured, below = mapOf(volte to "true"))).actions.single() as LayerAction.Put
        assertTrue(offPut.entries.single().voiceMeasured)
        assertEquals("correction", offPut.entries.single().origin)
        // VoNR: a changed value has no bearer proof and never enters.
        val vonr = OwnerState(overrides = listOf(keep(OwnerFeature.VONR.key)))
        assertTrue(LayerPlanner.plan(input(vonr, below = mapOf(OwnerFeature.VONR.key to "false"))).actions.isEmpty())
    }

    // --- the planner: what goes out, what is renewed ---

    @Test
    fun `failures revert at once, also while roaming`() {
        val served = LayerSimView(listOf(entry(sms, true)))
        val failing = LayerPlanner.plan(input(OwnerState(), served, observed = mapOf("sms_over_ims:on" to false))).actions.single() as LayerAction.Remove
        assertTrue(failing.failure)
        assertEquals(listOf(sms), failing.keys)
        val roaming = LayerPlanner.plan(input(OwnerState(), served, refusal = Refusal.ROAMING, observed = mapOf("sms_over_ims:on" to false))).actions.single()
        assertTrue(roaming is LayerAction.Remove)
        // X5: voice lost with a voice off served reverts at once, roaming or not.
        val off = LayerSimView(listOf(entry(volte, false, LayerLevel.PROVISIONAL, baseline = true)))
        assertTrue(LayerPlanner.plan(input(OwnerState(), off, refusal = Refusal.ROAMING, voiceLost = true)).actions.single() is LayerAction.Remove)
        // An entry the build now serves goes (S8); a lease that ran out goes; the first-use probe stays until Undo.
        assertTrue(LayerPlanner.plan(input(OwnerState(), LayerSimView(listOf(entry(sms, true, baseline = true))))).actions.single() is LayerAction.Remove)
        assertTrue(LayerPlanner.plan(input(OwnerState(), LayerSimView(listOf(entry(sms, true, state = "lease ran out", lease = now - 1))))).actions.single() is LayerAction.Remove)
        assertTrue(LayerPlanner.plan(input(OwnerState(), LayerSimView(listOf(entry(sms, true, baseline = true, method = LayerFirstUse.PROBE))))).actions.isEmpty())
    }

    @Test
    fun `renewals only at home, by a light check that saw it working, never for a carried-over entry`() {
        val served = LayerSimView(listOf(entry(sms, true, lease = now + 2 * day)))
        val renew = LayerPlanner.plan(input(OwnerState(), served, observed = mapOf("sms_over_ims:on" to true))).actions.single() as LayerAction.Renew
        assertEquals(now + 7 * day, renew.untilMs)
        assertTrue(LayerPlanner.plan(input(OwnerState(), served, refusal = Refusal.ROAMING, observed = mapOf("sms_over_ims:on" to true))).actions.isEmpty())
        assertTrue(LayerPlanner.plan(input(OwnerState(), served)).actions.isEmpty())
        // Plenty of lease left: no write.
        assertTrue(LayerPlanner.plan(input(OwnerState(), LayerSimView(listOf(entry(sms, true, lease = now + 6 * day + 3_600_000))), observed = mapOf("sms_over_ims:on" to true))).actions.isEmpty())
        val carried = LayerSimView(listOf(entry(sms, true, lease = now + 2 * day, carried = true)))
        assertTrue(LayerPlanner.plan(input(OwnerState(layer = listOf(record(sms, carriedAt = now - day))), carried, observed = mapOf("sms_over_ims:on" to true))).actions.isEmpty())
    }

    private fun record(k: String, level: LayerLevel = LayerLevel.DURABLE, applied: Long = now - day, carriedAt: Long? = null) =
        LayerRecord(0, key, k, JsonPrimitive(true), LayerKeys.classOf(k)!!, level, "ocd", applied, applied, carriedAtMs = carriedAt)

    @Test
    fun `provisional voice entries - round 26 rule 7 in the layer`() {
        val applied = now - 2 * day
        val view = LayerSimView(listOf(entry(volte, true, LayerLevel.PROVISIONAL, since = applied, lease = applied + 7 * day, bearers = listOf("lte"))))
        val base = OwnerState(layer = listOf(record(volte, LayerLevel.PROVISIONAL, applied)), calls = listOf(call(applied + 3_600_000, CallDirection.OUTGOING)))
        // No incoming yet: hold (no renewal of a provisional voice value).
        assertTrue(LayerPlanner.plan(input(base, view, observed = mapOf("volte:on" to true))).actions.isEmpty())
        // A measured incoming failure on its bearer: reverted, as a failure.
        val failed = base.copy(calls = base.calls + call(applied + 7_200_000, CallDirection.INCOMING, active = false))
        assertTrue((LayerPlanner.plan(input(failed, view)).actions.single() as LayerAction.Remove).failure)
        // Works (calls both ways): promoted to durable, only at home and in the silent window.
        val works = base.copy(calls = base.calls + call(applied + 7_200_000, CallDirection.INCOMING))
        val promoted = LayerPlanner.plan(input(works, view)).actions.single() as LayerAction.Put
        assertEquals(LayerLevel.DURABLE, promoted.entries.single().level)
        assertTrue(LayerPlanner.plan(input(works, view, refusal = Refusal.ROAMING)).actions.isEmpty())
        // 7 days without incoming: reverted.
        assertTrue(LayerPlanner.plan(input(base, view, at = applied + 7 * day + 1)).actions.single() is LayerAction.Remove)
    }

    @Test
    fun `carried over after a new build - provisional again after calls out, reverted when the first eligible night passed or after 7 days`() {
        val carriedAt = now - day
        val view = LayerSimView(listOf(entry(volte, true, carried = true, bearers = listOf("lte"))))
        val state = OwnerState(layer = listOf(record(volte, carriedAt = carriedAt)))
        assertTrue(LayerPlanner.plan(input(state, view, b = "rom-2")).actions.isEmpty())
        val again = state.copy(calls = listOf(call(carriedAt + 3_600_000, CallDirection.OUTGOING, b = "rom-2")))
        val put = LayerPlanner.plan(input(again, view, b = "rom-2")).actions.single() as LayerAction.Put
        assertEquals(LayerLevel.PROVISIONAL, put.entries.single().level)
        assertEquals(carriedAt + 7 * day, put.entries.single().leaseUntilMs)
        // The carry-over came in the night of 2026-10-23; the calls-out attempt of the next night (2026-10-24) found nothing.
        val nightPassed = state.copy(callsOutNights = listOf(CallsOutNight(key, OwnerFeature.VOLTE, ProvenBearer.LTE, "2026-10-24")))
        assertTrue(LayerPlanner.plan(input(nightPassed, view, b = "rom-2", at = now + day)).actions.single() is LayerAction.Remove)
        assertTrue(LayerPlanner.plan(input(state, view, b = "rom-2", at = carriedAt + 7 * day)).actions.single() is LayerAction.Remove)
        // The record notes the carry-over the first time the provider lists it.
        val marked = LayerPlanner.markCarried(listOf(record(volte)), mapOf(key to view), now)
        assertEquals(now, marked.single().carriedAtMs)
    }

    @Test
    fun `a stop event of the app's clears the whole layer, and a record the layer no longer holds is forgotten`() {
        val state = OwnerState(stop = "lost_voice", layer = listOf(record(sms)))
        assertEquals("lost_voice", LayerPlanner.plan(input(state, status = ready.copy(entryCount = 1))).stop)
        assertNull(LayerPlanner.plan(input(state, status = ready.copy(stop = "already", entryCount = 0))).stop)
        assertEquals(emptyList<LayerRecord>(), LayerPlanner.reconcile(listOf(record(sms)), mapOf(key to LayerSimView()), now))
        assertEquals(1, LayerPlanner.reconcile(listOf(record(sms)), emptyMap(), now).size)
    }

    // --- the writer and its dump proofs ---

    @Test
    fun `an apply is proven in CarrierSettings's own bundle with its suffix, then the override goes with proof`() = runBlocking {
        val phone = FakePhone()
        val layer = FakeLayer(phone)
        phone.store.state = OwnerState(rebootCleared = true, overrides = listOf(keep(sms, since = phone.now - day)), selfSms = listOf(sent(phone.now - 600_000, false)))
        phone.overrides = mutableMapOf(sms to "true")
        val plan = LayerPlanner.plan(input(phone.store.state, LayerSimView(), at = phone.now))
        val notes = LayerWriter(phone, phone.store, layer).run(plan, listOf(sim), LayerData("0123456789abcdef0123456789abcdef01234567", "", "2026-10-24"))
        assertTrue(notes.toString(), notes.any { it.startsWith("put") })
        val dump = CcDump.parse(phone.carrierConfigText())
        assertEquals("+ocd1-0123456", LayerProof.suffix(dump, 0))
        assertEquals("true", dump.phones[0]?.get("mConfigFromDefaultApp")?.get(sms))
        // The override is gone and the state keeps the layer record instead of the keep.
        assertTrue(phone.overrides.isNullOrEmpty())
        assertTrue(phone.store.state.overrides.none { it.key == sms })
        assertEquals(listOf(sms), phone.store.state.layer.map { it.key })
        assertEquals("true", phone.effective(sms))
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `an apply that does not show is reverted, and a revert that cannot be proven is a stop event`() = runBlocking {
        val phone = FakePhone()
        val layer = FakeLayer(phone).apply { shows = false }
        phone.store.state = OwnerState(rebootCleared = true, overrides = listOf(keep(sms, since = phone.now - day)), selfSms = listOf(sent(phone.now - 600_000, false)))
        phone.overrides = mutableMapOf(sms to "true")
        LayerWriter(phone, phone.store, layer).run(LayerPlanner.plan(input(phone.store.state, LayerSimView(), at = phone.now)), listOf(sim), LayerData("", "", ""))
        assertTrue(layer.entries.isEmpty())
        assertTrue(phone.store.state.layer.isEmpty())
        // The keep stays a non-persistent override.
        assertEquals("true", phone.overrides?.get(sms))

        val stuck = FakePhone()
        val stuckLayer = FakeLayer(stuck)
        stuckLayer.entries[sms] = LayerPut(sms, "bool", JsonPrimitive(true), LayerClass.SMS, "ocd", LayerLevel.DURABLE, stuck.now, stuck.now + day)
        stuck.defaultApp = mapOf(sms to "true", "carrier_config_version_string" to "vodafone_de-1080000000010+ocd1-0123456")
        stuck.store.state = OwnerState(rebootCleared = true, layer = listOf(record(sms)))
        stuckLayer.removalsShow = false
        val remove = LayerAction.Remove(0, key, sim.subId, listOf(sms), "the light check saw it not working", failure = true)
        LayerWriter(stuck, stuck.store, stuckLayer).run(LayerPlan(listOf(remove)), listOf(sim), LayerData("", "", ""))
        assertTrue(stuck.store.state.stop.orEmpty().startsWith("layer_revert_not_proven"))
        assertTrue("stop" in stuckLayer.calls)
    }

    @Test
    fun `a revert whose reload waits is recorded, and proven later`() = runBlocking {
        val phone = FakePhone()
        val layer = FakeLayer(phone).apply { waitingFor = "a call is up" }
        layer.entries[sms] = LayerPut(sms, "bool", JsonPrimitive(true), LayerClass.SMS, "ocd", LayerLevel.DURABLE, phone.now, phone.now + day)
        phone.store.state = OwnerState(rebootCleared = true, layer = listOf(record(sms)))
        LayerWriter(phone, phone.store, layer).run(LayerPlan(listOf(LayerAction.Remove(0, key, sim.subId, listOf(sms), "undo"))), listOf(sim), LayerData("", "", ""))
        assertEquals("a call is up", phone.store.state.layer.single().removing)
        assertNull(phone.store.state.stop)
    }

    @Test
    fun `undo all clears the layer, is remembered until Test now, and works without the app's other state`() = runBlocking {
        val phone = FakePhone()
        val layer = FakeLayer(phone)
        layer.entries[sms] = LayerPut(sms, "bool", JsonPrimitive(true), LayerClass.SMS, "ocd", LayerLevel.DURABLE, phone.now, phone.now + day)
        phone.defaultApp = mapOf(sms to "true", "carrier_config_version_string" to "vodafone_de-1080000000010+ocd1-0123456")
        phone.store.state = OwnerState(rebootCleared = true, layer = listOf(record(sms)))
        LayerWriter(phone, phone.store, layer).undo(listOf(sim), null, null)
        assertTrue("clear" in layer.calls)
        assertTrue(phone.store.state.layer.isEmpty())
        assertEquals(listOf("sms_over_ims:on"), phone.store.state.undone.map { it.entry })
        assertNull(LayerProof.suffix(CcDump.parse(phone.carrierConfigText()), 0))
    }

    @Test
    fun `the first-use probe is the build's own value and changes nothing`() {
        val put = LayerPlanner.probe(sim, mapOf(sms to "true"), emptyMap(), "de.lidl", now)!!
        assertEquals(JsonPrimitive(true), put.entries.single().value)
        assertEquals(LayerFirstUse.PROBE, put.entries.single().method)
        assertTrue(put.records.single().probe)
        assertNull(LayerPlanner.probe(sim, emptyMap(), emptyMap(), null, now))
    }

    @Test
    fun `the screen counts what is kept after restart as Open Carrier Data's change`() {
        val state = OwnerState(layer = listOf(record(volte)), entries = emptyList())
        val view = SimView(0, sim.subId, "26202", "LIDL Connect")
        val ladder = io.github.open_carrier_data.check.owner.core.Ladders.of(OwnerFeature.VOLTE, state, view)
        assertEquals(true, ladder.kept)
        assertEquals("Open Carrier Data switched on VoLTE", StatusSentence.of(state, listOf(view), listOf(ladder)).headline)
        assertEquals(listOf("VoLTE switched on"), StatusSentence.keptChanges(state))
        // The probe is not a change.
        assertTrue(StatusSentence.keptChanges(OwnerState(layer = listOf(record(sms).copy(probe = true)))).isEmpty())
        val line = LayerWriter.describe(entry(volte, true, LayerLevel.PROVISIONAL, bearers = listOf("lte"), method = "real_call"), null, zone)
        assertEquals("VoLTE: on", line.title)
        assertEquals("In effect", line.state)
        assertTrue(line.details.joinToString().contains("kept for now, checked by calls (real call) over LTE"))
    }

    @Test
    fun `the provider's list parses, and an absent provider answers nothing`() {
        val json = """{"subscription":3,"slot":0,"carrier_id":2397,"profile":"de.lidl","build":"eng.1","stack":"s:1","carried_over":false,"served_suffix":"+ocd1","served_at_ms":1,"reason":"served 1","pending":false,
            "entries":[{"key":"$sms","type":"bool","value":true,"class":"sms","origin":"ocd","level":"durable","since_ms":5,"lease_until_ms":9,"voice_measured":false,"sms_measured":false,"bearers":[],"method":"self_sms","ocd_value":true,"build":"eng.1","stack":"s:1","carried_over":false,"baseline":false,"served":true,"state":"served"}]}"""
        val view = LayerWriter.view(json, "+ocd1", 0, "ok", null)
        assertEquals(1, view.entries.size)
        assertEquals(JsonPrimitive(false), view.entries.single().baseline)
        assertTrue(view.entries.single().served)
        assertEquals(LayerSimView(servedSuffix = "", file = "absent"), LayerWriter.view(null, "", 0, "absent", null))
        assertFalse(LayerClient.Absent.status().available)
        assertFalse(LayerClient.Absent.put(3, "", LayerData("", "", ""), emptyList(), 0).ok)
    }

    @Test
    fun `the layer suffix is read from CarrierSettings's own bundle`() {
        val dump = CcDump.parse(OwnerFixtures.withOverride(OwnerFixtures.CC, "mConfigFromDefaultApp", mapOf("carrier_config_version_string" to "vodafone_de-1080000000010+ocd2-0123456", sms to "true")))
        assertEquals("+ocd2-0123456", LayerProof.suffix(dump, 0))
        assertTrue(LayerProof.applied(dump, 0, listOf(LayerPut(sms, "bool", JsonPrimitive(true), LayerClass.SMS, "ocd", LayerLevel.DURABLE, 0, 1)), "+ocd2-0123456"))
        assertFalse(LayerProof.applied(dump, 0, listOf(LayerPut(sms, "bool", JsonPrimitive(false), LayerClass.SMS, "ocd", LayerLevel.DURABLE, 0, 1)), null))
        assertTrue(LayerProof.removed(dump, 0, listOf(sms), mapOf(sms to JsonPrimitive(true))))
        assertFalse(LayerProof.removed(dump, 0, listOf(sms), mapOf(sms to JsonPrimitive(false))))
    }
}
