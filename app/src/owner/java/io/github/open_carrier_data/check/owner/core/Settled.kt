package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.ProvenBearer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * Settled results (OCD rule round 29, "corrections into the database"): what the pointer
 * report says this phone measured under rounds 26 to 28, so that OCD's public bot records the
 * owner's results as "re-tested by OCD" (observed/retested.json, 90 days). A settled result is
 * - a pass at L2 (calls out) or L3 (works, calls both ways) on the entry's bearer, from this
 *   phone's call ledger on this ROM build;
 * - SMS both ways (a data SMS to the phone's own number sent and received over IMS);
 * - a clean measured failure under rounds 26 to 28: the measured outgoing failure, the measured
 *   incoming failure, or "no audio stream received" twice on passive calls (the same rules that
 *   revert a provisional keep).
 * It names the level, the bearer, the method and the day, never a value, and only entries of
 * OCD's closed list carry it: volte:on, vowifi:on and sms_over_ims:on. Nothing here changes a
 * setting; it only says what the report carries.
 */

/** One settled result as the report's `settled` field carries it (schemas/ocd-check-report.schema.json). */
@Serializable
data class SettledLevel(
    /** l2 or l3 (round 26's ladder); for a failure, the rung it failed at. */
    val level: String,
    /** lte or iwlan; null for SMS over IMS when the bearer was not recorded. */
    val bearer: String?,
    /** mailbox_call, real_call, both legs (outgoing+incoming), or self_sms. */
    val method: String,
    /** The UTC date the level was reached (the newer leg). */
    val measuredOn: String,
    val failed: Boolean = false,
    /** A failure's code, for example calls_out_failed. */
    val reason: String? = null,
) {
    /** What automatic submission remembers: the level and pass or failure, never the day. */
    val key: String get() = "$level/${if (failed) "failed" else "passed"}/${bearer ?: "-"}"
}

object Settled {
    const val L2 = "l2"
    const val L3 = "l3"
    const val SELF_SMS = "self_sms"
    const val VOLTE_ON = "volte:on"
    const val VOWIFI_ON = "vowifi:on"
    const val SMS_ON = "sms_over_ims:on"

    /** The ROM build as the report names it: Build.ID and the build's UTC time in seconds (ro.build.date.utc). */
    val BUILD = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31} [0-9]{9,11}")

    fun publicBuild(id: String, timeMs: Long): String? = "$id ${timeMs / 1000}".takeIf { BUILD.matches(it) }

    private fun day(atMs: Long): String = Instant.ofEpochMilli(atMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /**
     * The settled result of a voice feature's on value on [sim] and [build] (the call ledger's
     * build key), from calls at or after [sinceMs] and not after [nowMs], within 90 days: the
     * newer of the newest pass (L3 with incoming, else L2) and the newest clean measured failure.
     * Null without either, and for a feature without a proven bearer (VoNR, video calling).
     */
    fun voice(calls: List<CallRecord>, sim: SimKey, feature: OwnerFeature, build: String, sinceMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SettledLevel? {
        val bearer = CallLevels.bearerOf(feature) ?: return null
        val mine = calls.filter { it.sim?.sameCard(sim) == true && it.build == build && it.atMs in sinceMs..nowMs && nowMs - it.atMs < CallLevels.VALID_MS }
        val out = CallLevels.callsOutEvidence(mine, sim, bearer, build, sinceMs, nowMs)
        val incoming = out?.let { CallLevels.incomingEvidence(mine, sim, bearer, build, sinceMs, nowMs) }
        val passAt = out?.let { maxOf(it.atMs, incoming?.atMs ?: 0L) }
        val failure = failure(mine, bearer, zone)
        if (failure != null && (passAt == null || failure.second.atMs > passAt)) {
            val (level, call, reason) = failure
            return SettledLevel(level, bearer.wire, call.method.wire, day(call.atMs), failed = true, reason = reason)
        }
        out ?: return null
        return SettledLevel(if (incoming != null) L3 else L2, bearer.wire, Retested.method(out, incoming), day(passAt!!))
    }

    /** The newest clean measured failure on [bearer]: (level, the call, the reason code). */
    private fun failure(mine: List<CallRecord>, bearer: ProvenBearer, zone: ZoneId): Triple<String, CallRecord, String>? {
        val found = mutableListOf<Triple<String, CallRecord, String>>()
        mine.filter { it.direction == CallDirection.OUTGOING && CallLevels.callsOut(it).result == "failed" && Provisional.imsOn(it) == bearer }
            .maxByOrNull { it.atMs }?.let { found += Triple(L2, it, "calls_out_failed") }
        mine.mapNotNull { r -> CallLevels.incomingFailure(r)?.takeIf { it.bearer == bearer }?.let { r to it } }
            .maxByOrNull { it.first.atMs }?.let { (r, f) -> found += Triple(L3, r, IssueReport.code("incoming_failed_${f.kind}")) }
        if (CallLevels.noStreamTwice(mine, CallDirection.OUTGOING, bearer, zone)) {
            CallLevels.noStreamCalls(mine, CallDirection.OUTGOING, bearer).maxByOrNull { it.atMs }?.let { found += Triple(L2, it, "calls_out_no_audio_stream_twice") }
        }
        if (CallLevels.noStreamTwice(mine, CallDirection.INCOMING, bearer, zone)) {
            CallLevels.noStreamCalls(mine, CallDirection.INCOMING, bearer).maxByOrNull { it.atMs }?.let { found += Triple(L3, it, "incoming_no_audio_stream_twice") }
        }
        return found.maxByOrNull { it.second.atMs }
    }

    /**
     * SMS both ways on [sim] and [build]: the newest data SMS to the phone's own number that went
     * out and came back over IMS by Telephony's own word on each leg ([SelfSms.bothWays]), within
     * 90 days. A check without its build or without either leg proven never settles.
     */
    fun sms(checks: List<SelfSmsCheck>, sim: SimKey, build: String, nowMs: Long): SettledLevel? {
        val newest = checks.filter {
            val at = it.receivedAtMs ?: it.sentAtMs
            it.sim.sameCard(sim) && it.build == build && SelfSms.bothWays(it) && at <= nowMs && nowMs - at < CallLevels.VALID_MS
        }.maxByOrNull { it.receivedAtMs ?: it.sentAtMs } ?: return null
        val bearer = newest.wifiAtSend?.let { if (it) ProvenBearer.IWLAN.wire else ProvenBearer.LTE.wire }
        return SettledLevel(L3, bearer, SELF_SMS, day(newest.receivedAtMs ?: newest.sentAtMs))
    }

    /**
     * The settled results for [run]'s SIM, by entry, as of [nowMs]: only when the run names the
     * data commit (round 29 S13) and was recorded on this ROM build ([publicBuild], the report's
     * device.build), since a level holds per build. [romKey] is the call ledger's build key. For
     * a value Open Carrier Data keeps, only calls since it took effect count.
     */
    fun forRun(run: RunResult, state: OwnerState, romKey: String, publicBuild: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Map<String, SettledLevel> {
        val cell = run.cell ?: return emptyMap()
        val sim = run.sim ?: return emptyMap()
        if (publicBuild == null || cell.romBuild != publicBuild || cell.dataCommit?.let(DataSource.SHA::matches) != true) return emptyMap()
        val out = LinkedHashMap<String, SettledLevel>()
        for (feature in listOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI)) {
            val since = listOfNotNull(
                state.overrides.lastOrNull { it.hold == Hold.KEPT && it.sim.sameCard(sim) && it.key == feature.key && it.value }?.sinceMs,
                state.layer.lastOrNull { it.sim.sameCard(sim) && it.key == feature.key && it.boolValue == true && it.removing == null && !it.probe }?.appliedAtMs,
            ).maxOrNull() ?: 0L
            voice(state.calls, sim, feature, romKey, since, nowMs, zone)?.let { out[QuietPlan.entry(feature, true)] = it }
        }
        sms(state.selfSms, sim, romKey, nowMs)?.let { out[SMS_ON] = it }
        return out
    }

    /**
     * [r] with [s] attached, as rounds 26 to 28 read it, or [r] unchanged when the run's own
     * result says something else (a value that did not sign in, or was not tested):
     * - a pass at L3 is works with the T2 call on the bearer, a pass at L2 is signed_in;
     * - SMS both ways is works;
     * - a failure turns a signed-in entry into failed with its reason, not kept.
     */
    fun apply(r: EntryResult, s: SettledLevel?): EntryResult {
        s ?: return r
        if (r.result !in setOf(Pointers.WORKS, Pointers.SIGNED_IN)) return r
        val t2 = when (r.entry) {
            VOLTE_ON -> listOf("t2" to ProvenBearer.LTE.wire)
            VOWIFI_ON -> listOf("t2" to "iwlan")
            else -> null
        }
        return when {
            r.entry == SMS_ON -> if (!s.failed && s.level == L3 && s.method == SELF_SMS) r.copy(result = Pointers.WORKS, settled = s) else r
            t2 == null -> r
            s.failed -> r.copy(result = Pointers.FAILED, reason = s.reason, calls = emptyList(), kept = false, settled = s)
            s.level == L3 -> r.copy(result = Pointers.WORKS, calls = t2, settled = s)
            else -> r.copy(result = Pointers.SIGNED_IN, calls = emptyList(), settled = s)
        }
    }
}
