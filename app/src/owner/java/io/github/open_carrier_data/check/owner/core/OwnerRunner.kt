package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.LteVops
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.Radio
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How one run tests. */
data class RunOptions(
    /** Test the off value too (the owner's "on and off"). */
    val testOffs: Boolean = true,
    /**
     * Interactive runs only: how long a voice off waits for the owner to place
     * a call himself (the app never dials). 0 in background runs, where a
     * voice off is then never kept.
     */
    val voiceCheckMs: Long = 0,
    /** How long a trial gets to reach its outcome (the report of 2026-10-20: 120 s). */
    val settleMs: Long = 120_000,
    /** After each revert: how long the phone gets to show its baseline again (120 s). */
    val restoreMs: Long = 120_000,
    val pollMs: Long = 10_000,
    val apnSettleMs: Long = 90_000,
    /** Test OCD's internet APN rows the phone lacks. */
    val apns: Boolean = true,
    /** A scheduled run: refused until the first-use checks are done. */
    val scheduled: Boolean = false,
    /**
     * Only this value of the feature (a background entry, [QuietPlan]); null
     * tests the value the phone does not have and observes the one it has, as
     * the owner's run does.
     */
    val only: Boolean? = null,
    /** Only these OCD APN rows (their keys), and at most [maxApnRows] inserted; null: every candidate. */
    val apnKeys: Set<String>? = null,
    val maxApnRows: Int = Int.MAX_VALUE,
    /** A background run in the silent window: what stops it at once (screen on, a call, unplugged). Null in the owner's own runs. */
    val interrupt: Interrupt? = null,
    /** What the results rest on; each entry tested is recorded against it for the triggers and the rotation ([QuietPlan]). */
    val basis: TestBasis? = null,
    /** Why video calling cannot work on this build (static checks), from the app side; empty: no static reason. */
    val videoUnavailable: List<String> = emptyList(),
)

/**
 * The owner's loop for one SIM (the owner direction, 2026-10-19, and the
 * quiet design of 2026-10-20): for each feature, observe the value the phone
 * has, test the other one, revert with proof and see the baseline come back,
 * then apply the best working value as a keep. Then the same for OCD's
 * internet APN rows. Each step is short enough for one WorkManager run.
 *
 * What it changes is only what cannot outlive the test: a non-persistent
 * CarrierConfig override, and an APN row no stock row can match, deleted by
 * its own ID. Never Wi-Fi, never a persistent setting.
 */
class OwnerRunner(
    private val phone: OwnerPhone,
    private val store: StateStore,
    private val device: DeviceInfo,
) {
    private fun log(kind: String, text: String) = store.update { it.logged(phone.nowMs(), kind, text) }

    /**
     * Tests one feature on one SIM and applies its best working value. Never
     * throws but for a cancelled worker; a refusal is in the result. The
     * result's entries go into the private pointer ledger.
     */
    suspend fun testFeature(sim: OwnerSim, feature: OwnerFeature, ocd: OcdAnswer, options: RunOptions): FeatureResult =
        runFeature(sim, feature, ocd, options).also { ledger(sim, Pointers.entries(it, t2 = false)) }

    /** Tests OCD's internet APN rows the phone lacks (see [runApns]); the results go into the pointer ledger. */
    suspend fun testApns(sim: OwnerSim, rows: List<OcdApnRow>, options: RunOptions): List<ApnResult> =
        runApns(sim, rows, options).also { results -> ledger(sim, results.mapNotNull(Pointers::apn)) }

    private fun ledger(sim: OwnerSim, results: List<EntryResult>) {
        if (results.isEmpty()) return
        val cell = PointerLedger.cell(device, sim.key)
        store.update { it.copy(ledger = PointerLedger.add(it.ledger, cell, results, phone.nowMs())) }
    }

    private suspend fun runFeature(sim: OwnerSim, feature: OwnerFeature, ocd: OcdAnswer, options: RunOptions): FeatureResult {
        val session = OwnerSession(phone, store, sim, device.model)
        val start = FeatureResult(feature, null, null, ocd)
        if (options.scheduled && !store.read().rebootCleared) return start.copy(refused = FIRST_USE_PENDING)
        val startedAt = phone.nowMs()
        var finished = false
        var result = start
        try {
            session.guard(callWaitMs = 0)
            options.interrupt?.reason()?.let { throw RunInterrupted(it) }
            session.takeSnapshot()
            val base = session.observe(iwlan = feature == OwnerFeature.VOWIFI)
            val baseline = base.switch(feature) ?: return start.copy(refused = "switch_unreadable")
            // An on the phone has counts only where it shows without a change (VoLTE not while IMS runs over Wi-Fi).
            result = start.copy(baseline = baseline, baselineWorks = if (baseline && !observable(feature, true, base)) null else Evidence.working(feature, base))
            val other = !baseline
            val wantsBaseline = options.only == null || options.only == baseline
            val wantsOther = options.only == null || options.only == other
            // VoLTE cannot be seen while IMS runs over Wi-Fi: the Wi-Fi-calling-off override moves IMS to LTE, Wi-Fi data stays up.
            if (feature == OwnerFeature.VOLTE && baseline && wantsBaseline && imsOnWifi(base)) {
                result = measureVolteOnLte(session, base, result, options)
                if (result.refused != null) return result
            }
            if (wantsOther) {
                val skip = skipReason(feature, other, base, options)
                if (skip == null) {
                    val trial = trial(session, feature, other, base, options)
                    result = if (other) {
                        result.copy(onWorks = trial.works, onReason = trial.reason)
                    } else {
                        result.copy(offWorks = trial.works, offReason = trial.reason, offVoiceMeasured = trial.voiceMeasured, offCallNetwork = trial.callNetwork)
                    }
                    result = result.copy(restored = trial.restored, sideVolteOn = trial.sideVolteOn ?: result.sideVolteOn)
                    if (trial.proof?.ok == false) return result.copy(refused = "revert_not_proven")
                    if (trial.proof == null) return result.copy(refused = "revert_waits_for_call")
                    if (trial.lostVoice) return result.copy(refused = "lost_voice")
                } else if (other) {
                    result = result.copy(onReason = skip, onWorks = if (skip in QuietPlan.STATIC_SKIPS) false else null)
                } else {
                    result = result.copy(offReason = skip)
                }
            }
            val chosen = choose(session, result)
            finished = true
            val effective = runCatching { session.dump().effective(sim.slot)[feature.key].asBool() }.getOrNull()
            record(sim, chosen, options, effective)
            return chosen
        } catch (e: SafetyException) {
            finished = true
            // An interruption reverts at once; a call that is up keeps the trial recorded for the cleanup, which waits for its end.
            runCatching { session.revertTrials("error: ${e.reason}", callWaitMs = if (e is RunInterrupted) 0 else 8 * 60_000) }
            log(if (e is RunInterrupted) "interrupted" else "refused", "${feature.wire} on slot ${sim.slot}: ${e.reason}")
            val refused = result.copy(refused = e.reason)
            if (e !is RunInterrupted) recordFailure(sim, feature, options, refused)
            return refused
        } finally {
            // A cancelled worker (JobScheduler stopped it: the screen came on, the charger was pulled) reverts its trial here.
            if (!finished) withContext(NonCancellable) { revertLeftTrials(session, startedAt) }
        }
    }

    private fun imsOnWifi(o: Observation): Boolean = o.ims.registered == true && o.ims.transport?.isWifi == true && o.wifiConnected == true

    /** IMS voice over Wi-Fi: Wi-Fi calling registered, the voice path while VoLTE is off. */
    private fun voiceOverWifi(o: Observation): Boolean = imsOnWifi(o) && ImsService.MMTEL in o.ims.services

    /** LTE in service with voice over PS offered and VoLTE switched on: the voice path while Wi-Fi calling is off. */
    private fun lteVoicePath(o: Observation): Boolean = o.onLte && o.lteVops == LteVops.SUPPORTED && o.switch(OwnerFeature.VOLTE) == true

    /**
     * IMS settled on a bearer: the IMS data connection and the registration
     * itself agree. On the S20 (2026-10-20) the data connection was back on
     * Wi-Fi 1 s after a revert, while PHH re-registered over Wi-Fi 24 s later.
     */
    private fun settled(o: Observation): Boolean = o.ims.registered == true && o.ims.transport != null && o.registrationOnPdn

    private fun voiceOverLte(o: Observation): Boolean = settled(o) && o.ims.transport?.isWifi == false && ImsService.MMTEL in o.ims.services

    /**
     * VoLTE's baseline while IMS runs over Wi-Fi (the report of 2026-10-20):
     * the non-persistent override carrier_wfc_ims_available_bool=false makes
     * QNS qualify cellular for IMS, which registers over LTE while Wi-Fi keeps
     * carrying the internet. One change measures two things: VoLTE over LTE,
     * and Wi-Fi calling's service gone with it off. A fresh REGISTER over LTE
     * (the IMS registration callback) is the stronger result; IMS on LTE alone
     * may be the stack keeping its old registration. Only with LTE in service
     * and voice over PS offered, so a voice path stays.
     */
    private suspend fun measureVolteOnLte(session: OwnerSession, base: Observation, r: FeatureResult, options: RunOptions): FeatureResult {
        if (!lteVoicePath(base)) {
            log("volte_method", "VoLTE on slot ${session.slot + 1}: IMS runs over Wi-Fi and LTE offers no voice path now; VoLTE not measured")
            return r.copy(refused = NO_VOICE_PATH)
        }
        val watch = phone.watchRegistrations(session.sim.subId)
        try {
            val changeAt = phone.nowMs()
            session.apply(OwnerFeature.VOWIFI, false, Hold.TRIAL, MEASURE_WHY)
            log("volte_method", "wfc_override: carrier_wfc_ims_available_bool=false (not persistent) on slot ${session.slot + 1} to measure VoLTE over LTE; Wi-Fi stays on")
            var waited = 0L
            var o = session.observe()
            while (waited < options.settleMs && !voiceOverLte(o)) {
                if (o.callActive) throw SafetyException(Refusal.CALL_ACTIVE.wire, Refusal.CALL_ACTIVE.plain)
                pause(options.pollMs, options)
                waited += options.pollMs
                o = session.observe()
            }
            val leftWifi = o.ims.registered == false || (settled(o) && o.ims.transport?.isWifi == false)
            // IMS still on Wi-Fi: the stack did not move, so VoLTE is not measured (not "not working").
            val works = if (leftWifi) Evidence.working(OwnerFeature.VOLTE, o) else null
            val fresh = watch?.events()?.any { it.registered && it.transportType == ImsApi.TRANSPORT_WWAN && it.atMs >= changeAt }
            log(
                "volte_method",
                "wfc_override: ${when (works) { true -> "VoLTE registered over ${o.ims.transport}"; false -> "VoLTE did not register over LTE"; null -> "IMS stayed on Wi-Fi, VoLTE not measured" }} after ${waited / 1000} s; " +
                    "${when (fresh) { true -> "fresh REGISTER over LTE seen"; false -> "no fresh REGISTER seen (the stack may have kept its registration)"; null -> "registrations not watchable on this route" }}; " +
                    "Wi-Fi ${if (o.wifiConnected == true) "still connected" else "not connected"}",
            )
            val proof = session.revertTrials("end of the VoLTE measurement") ?: return r.copy(refused = "revert_waits_for_call")
            if (!proof.ok) return r.copy(refused = "revert_not_proven")
            val restored = restored(session, base, options)
            return r.copy(
                baselineWorks = works,
                method = VOLTE_METHOD_WFC_OVERRIDE,
                freshRegister = fresh,
                sideVowifiOff = if (leftWifi) true else null,
                restored = restored,
            )
        } finally {
            watch?.close()
        }
    }

    /**
     * After a revert: the baseline must show again within [RunOptions.restoreMs]
     * (IMS registered as before, on the same kind of bearer, with the same
     * services). Logged either way; a baseline that does not come back counts
     * as a failure of the entry ([QuietPlan] pauses it after two).
     */
    private suspend fun restored(session: OwnerSession, before: Observation, options: RunOptions): Boolean {
        // The registration itself must be back, not only its data connection.
        fun same(o: Observation) = o.ims.registered == before.ims.registered &&
            (before.ims.registered != true || (settled(o) && o.ims.transport?.isWifi == before.ims.transport?.isWifi && o.ims.services.containsAll(before.ims.services)))
        var waited = 0L
        var o = session.observe()
        while (!same(o) && waited < options.restoreMs) {
            pause(minOf(options.pollMs, 5_000), options)
            waited += minOf(options.pollMs, 5_000)
            o = session.observe()
        }
        val ok = same(o)
        log("restored", if (ok) "slot ${session.slot + 1}: baseline back after ${waited / 1000} s (IMS ${o.ims.transport ?: "-"}, ${o.ims.services.joinToString { it.wire }})" else "slot ${session.slot + 1}: baseline NOT back within ${waited / 1000} s")
        return ok
    }

    /** The trials this test left when its worker was cancelled: reverted now, without waiting for a call (the cleanup does). */
    private suspend fun revertLeftTrials(session: OwnerSession, since: Long) {
        val left = store.read().overrides.any { it.slot == session.slot && it.hold == Hold.TRIAL && it.sinceMs >= since && it.why != PROBE_WHY }
        if (!left) return
        val proof = runCatching { session.revertTrials("stopped", callWaitMs = 0) }.getOrNull()
        log("interrupted", "slot ${session.slot}: the run was stopped; ${if (proof?.ok == true) "trial reverted, proof holds" else "the revert waits for the cleanup"}")
    }

    private fun record(sim: OwnerSim, r: FeatureResult, options: RunOptions, effective: Boolean?) {
        val basis = options.basis ?: return
        val now = phone.nowMs()
        store.update { s -> QuietPlan.records(r, sim.key, basis, now, effective, s.entries).fold(s) { acc, rec -> acc.recorded(rec) } }
    }

    private fun recordFailure(sim: OwnerSim, feature: OwnerFeature, options: RunOptions, r: FeatureResult) {
        val basis = options.basis ?: return
        val value = options.only ?: r.baseline?.not() ?: return
        // A missing voice path or a call is a "not now", not a failure of the entry.
        if (r.refused in QuietPlan.NOT_NOW) return
        val entry = QuietPlan.entry(feature, value)
        val now = phone.nowMs()
        store.update { s -> s.recorded(QuietPlan.failure(sim.key, entry, basis, now, r.refused ?: "failed", s.entries)) }
    }

    /** A pause that, in a background run, looks at the [Interrupt] every second and stops the run at once. */
    private suspend fun pause(ms: Long, options: RunOptions) {
        val interrupt = options.interrupt
        if (interrupt == null) {
            phone.sleep(ms)
            return
        }
        var left = ms
        while (left > 0) {
            interrupt.reason()?.let { throw RunInterrupted(it) }
            val step = minOf(left, INTERRUPT_POLL_MS)
            phone.sleep(step)
            left -= step
        }
        interrupt.reason()?.let { throw RunInterrupted(it) }
    }

    private fun skipReason(feature: OwnerFeature, value: Boolean, base: Observation, options: RunOptions): String? = when {
        value && feature == OwnerFeature.VONR && DeviceCapability.vonrNotCapable(device.model) -> "device_not_capable"
        // PHH IMS has no video, the device flag is off, the carrier offers none: nothing an override can change (2026-10-20).
        value && feature == OwnerFeature.VIDEO_CALLING && options.videoUnavailable.isNotEmpty() -> NOT_AVAILABLE
        value && feature == OwnerFeature.VOWIFI && base.wifiConnected != true -> "wifi_not_connected"
        value && feature == OwnerFeature.VOWIFI && base.iwlan?.heldBackBy != null -> "wifi_calling_backoff"
        !value && !options.testOffs -> "off_tests_not_chosen"
        !value && !store.read().rebootCleared -> FIRST_USE_PENDING
        // Keep a voice path: VoLTE off only while Wi-Fi calling carries voice; Wi-Fi calling off only with LTE voice.
        !value && feature == OwnerFeature.VOLTE && !voiceOverWifi(base) -> NO_VOICE_PATH
        !value && feature == OwnerFeature.VOWIFI && !lteVoicePath(base) -> NO_VOICE_PATH
        !value && feature == OwnerFeature.VONR && base.ims.registered != true -> NO_VOICE_PATH
        else -> null
    }

    private class Trial(
        val works: Boolean?,
        val reason: String,
        val voiceMeasured: Boolean?,
        val proof: RevertProof?,
        val lostVoice: Boolean = false,
        val restored: Boolean? = null,
        val sideVolteOn: Boolean? = null,
        /** The call network type of a call that went active during the trial. */
        val callNetwork: String? = null,
    )

    /** One value on, settle, measure, revert with proof, see the baseline back. */
    private suspend fun trial(session: OwnerSession, feature: OwnerFeature, value: Boolean, base: Observation, options: RunOptions): Trial {
        // VoLTE on while IMS runs over Wi-Fi is seen only with Wi-Fi calling off too: both are trials of this one revert.
        if (feature == OwnerFeature.VOLTE && value && imsOnWifi(base) && base.onLte && base.lteVops == LteVops.SUPPORTED) {
            session.apply(OwnerFeature.VOWIFI, false, Hold.TRIAL, MEASURE_WHY)
        }
        session.apply(feature, value)
        var waited = 0L
        var verdict = Verdict(Outcome.WAIT, "not_yet")
        var voice: Boolean? = null
        var callType: Int? = null
        var callSeen = false
        var voiceRegistered = false
        var videoSeen = false
        var sideVolteOn: Boolean? = null
        val wfcOff = feature == OwnerFeature.VOWIFI && !value
        while (waited < options.settleMs) {
            pause(options.pollMs, options)
            waited += options.pollMs
            val o = session.observe(iwlan = feature == OwnerFeature.VOWIFI && value)
            if (o.ims.registered == true && ImsService.MMTEL in o.ims.services) voiceRegistered = true
            if (o.ims.registered == true && ImsService.MMTEL_VIDEO in o.ims.services) videoSeen = true
            if (o.callActive) {
                callSeen = true
                if (o.callNetworkType != null) {
                    voice = true
                    callType = o.callNetworkType
                }
            }
            if (o.callActive && !(feature.voice && !value && options.voiceCheckMs > 0)) {
                // A call that starts during a test stops it; the revert waits for the call to end.
                verdict = Verdict(Outcome.WAIT, "call_started")
                break
            }
            // VoLTE off keeps Wi-Fi calling as the voice path: if that goes too, revert at once.
            if (feature == OwnerFeature.VOLTE && !value && !voiceOverWifi(o)) {
                verdict = Verdict(Outcome.REVERT, "voice_path_lost")
                break
            }
            verdict = Evidence.decide(feature, value, o)
            if (wfcOff && verdict.outcome == Outcome.SERVICE_OFF) {
                // Wi-Fi calling off: the voice path is VoLTE; the same change measures it (one change, two results).
                if (voiceOverLte(o)) {
                    sideVolteOn = true
                    break
                }
                if (waited >= options.settleMs / 2) {
                    sideVolteOn = false
                    verdict = Verdict(Outcome.SERVICE_OFF, "service_gone_no_lte_voice")
                    break
                }
                continue
            }
            if (verdict.outcome != Outcome.WAIT) break
        }
        if (!value && feature.voice && verdict.outcome == Outcome.SERVICE_OFF && options.voiceCheckMs > 0 && voice != true) {
            log("voice_check", "${feature.wire} off: place a call yourself now; waiting up to ${options.voiceCheckMs / 1000} s")
            var w = 0L
            while (w < options.voiceCheckMs) {
                phone.sleep(2_000)
                w += 2_000
                val o = session.observe()
                if (o.callActive) callSeen = true
                if (o.callNetworkType != null) {
                    voice = true
                    callType = o.callNetworkType
                    break
                }
                if (callSeen && !o.callActive) break
            }
            if (callSeen && voice != true) voice = false
        }
        val proof = session.revertTrials("end of ${feature.wire}=$value")
        val works = when (verdict.outcome) {
            Outcome.REGISTERED, Outcome.SERVICE_OFF -> true
            Outcome.REVERT -> false
            Outcome.WAIT -> if (verdict.reason == "call_started") null else false
        }
        log("trial", "${feature.wire}=$value: ${verdict.outcome} (${verdict.reason}), voice ${voice ?: "not measured"}${sideVolteOn?.let { ", VoLTE over LTE ${if (it) "registered" else "not seen"}" } ?: ""}")
        if (voice == false) session.stop("lost_voice with ${feature.wire} off")
        val timedOut = waited >= options.settleMs && verdict.outcome == Outcome.WAIT && verdict.reason != "call_started"
        val videoOn = feature == OwnerFeature.VIDEO_CALLING && value
        val reason = when {
            // IMS registered for voice the whole time, but never offered video: the stack does not do video.
            timedOut && videoOn && voiceRegistered && !videoSeen -> IMS_STACK_NO_VIDEO
            timedOut -> "settle_timeout"
            else -> verdict.reason
        }
        if (reason == IMS_STACK_NO_VIDEO) log("trial", "video_calling=true: IMS registered for voice for ${options.settleMs / 1000} s but never with video; not supported by this IMS stack")
        val restored = if (proof?.ok == true && verdict.reason != "call_started") restored(session, base, options) else null
        return Trial(
            works, reason, voice, proof, lostVoice = voice == false, restored = restored, sideVolteOn = sideVolteOn,
            callNetwork = callType?.let(Radio::wire),
        )
    }

    /**
     * The best working value (the owner's "apply the best working value"):
     * on when on works; else off, when off works and, for a voice feature,
     * voice was measured with it off; else the phone's own value. A kept value
     * measured not working is dropped (smart revert); one that could not be
     * measured stays.
     */
    private suspend fun choose(session: OwnerSession, r: FeatureResult): FeatureResult {
        val baseline = r.baseline ?: return r
        val feature = r.feature
        val onWorks = if (baseline) r.baselineWorks else r.onWorks
        val offOk = if (!baseline) true else r.offWorks == true && (!feature.voice || r.offVoiceMeasured == true)
        val (best, why) = when {
            onWorks == true -> true to "on registered with its service"
            offOk && !baseline && r.onReason == NOT_AVAILABLE -> false to "not available on this build (IMS stack, device flag, carrier); the phone's own off stays"
            offOk && !baseline && r.onReason == IMS_STACK_NO_VIDEO -> false to "not supported by this IMS stack (registered for voice, never with video); the phone's own off stays"
            offOk && !baseline -> false to "on did not register; the phone's own off stays"
            offOk -> false to if (feature.voice) "on did not register; off kept with voice measured" else "on did not register; service off"
            else -> null to "nothing better measured; the phone's own value stays"
        }
        val keptNow = store.read().overrides.firstOrNull { it.slot == session.slot && it.key == feature.key && it.hold == Hold.KEPT }
        var applied = false
        if (best != null && best != baseline && !store.read().rebootCleared) {
            log("keep_waits", "${feature.wire}=$best would be kept; first-use check 3 (an override gone after a reboot) comes first")
            return r.copy(best = best, bestWhy = "$why; not kept until the reboot check is done", applied = false)
        }
        // The kept value as measured now: the baseline when the slot holds it, else the trial of it.
        val keptWorks = keptNow?.let { if (it.value == baseline) r.baselineWorks else if (it.value) r.onWorks else r.offWorks }
        if (best != null && best != baseline) {
            if (keptNow != null) session.dropKeep(feature, "replaced by a better value")
            session.apply(feature, best, Hold.KEPT, why)
            applied = true
            log("keep", "${feature.wire}=$best kept on slot ${session.slot}: $why")
        } else if (best == null && keptNow != null && keptWorks == false) {
            session.dropKeep(feature, "the kept value no longer works")
            log("smart_revert", "${feature.wire}: the kept value no longer works; back to the phone's own value")
        }
        return r.copy(best = best, bestWhy = why, applied = applied)
    }

    /**
     * The background job's light check of one SIM: reads only (the carrier
     * config, the registry, IMS through the APIs), never a write. It settles
     * every entry it can see without a change (the value the phone has, when
     * IMS shows it) and the static ones, and names the kept values a reload
     * dropped or that IMS observably does not register with.
     */
    suspend fun check(sim: OwnerSim, videoUnavailable: List<String> = emptyList(), apnRows: List<OcdApnRow> = emptyList()): SimCheck {
        val session = OwnerSession(phone, store, sim, device.model)
        val o = session.observe()
        val cc = session.dump()
        val layer = cc.phones[sim.slot]?.get(CcLayer.OVERRIDE).orEmpty()
        val keeps = store.read().overrides.filter { it.slot == sim.slot && it.hold == Hold.KEPT && it.sim == sim.key }
        val missing = keeps.filter { layer[it.key].asBool() != it.value }.map { it.key }
        val observed = LinkedHashMap<String, Boolean>()
        val static = LinkedHashMap<String, String>()
        for (feature in OwnerFeature.entries) {
            val value = o.switch(feature) ?: continue
            if (observable(feature, value, o)) {
                observed[QuietPlan.entry(feature, value)] = if (value) Evidence.working(feature, o) == true else Evidence.decide(feature, false, o).outcome == Outcome.SERVICE_OFF
            }
        }
        if (DeviceCapability.vonrNotCapable(device.model)) static[QuietPlan.entry(OwnerFeature.VONR, true)] = "device_not_capable"
        if (videoUnavailable.isNotEmpty()) static[QuietPlan.entry(OwnerFeature.VIDEO_CALLING, true)] = NOT_AVAILABLE
        val failing = keeps.filter { it.key !in missing }.mapNotNull { k -> OwnerFeature.ofKey(k.key)?.takeIf { observed[QuietPlan.entry(it, k.value)] == false } }
        // OCD's internet rows: the phone has one already (by key, or the same row to the provider), or it is a change to test.
        val apnPresent = LinkedHashMap<String, String>()
        val apnToTest = mutableListOf<String>()
        val candidates = QuietPlan.apnCandidates(apnRows, sim)
        val phoneRows = if (candidates.isEmpty()) emptyList() else phone.apnQuery(sim.identity.mccmnc).orEmpty()
        for (row in candidates) {
            when {
                phoneRows.any { it.key == row.key } -> apnPresent[row.key] = "present"
                phone.apnConflicts(ApnColumns.identity(row))?.isNotEmpty() == true -> apnPresent[row.key] = SAME_AS_PHONE_ROW
                else -> apnToTest += row.key
            }
        }
        return SimCheck(
            sim = sim,
            effective = OwnerFeature.entries.associateWith { o.switch(it) },
            missingKeeps = missing,
            failingKeeps = failing,
            observed = observed,
            static = static,
            apnToTest = apnToTest,
            apnPresent = apnPresent,
            healthy = o.ims.registered == true && ImsService.MMTEL in o.ims.services && o.ps?.state == "home",
            imsLine = "IMS ${if (o.ims.registered == true) "registered over ${o.ims.transport ?: "?"} (${o.ims.services.joinToString { it.wire }})" else "not registered"}",
        )
    }

    /** Whether a value the phone has shows without a change (VoLTE not while IMS runs over Wi-Fi). */
    private fun observable(feature: OwnerFeature, value: Boolean, o: Observation): Boolean {
        val registered = o.ims.registered ?: return false
        if (!value) return true
        return when (feature) {
            OwnerFeature.VOLTE -> !registered || o.ims.transport?.isWifi == false
            OwnerFeature.VOWIFI -> o.wifiConnected == true
            OwnerFeature.VONR -> o.onNr
            OwnerFeature.SMS_OVER_IMS, OwnerFeature.VIDEO_CALLING -> true
        }
    }

    /** OCD's internet APN rows for the SIM the phone lacks: insert, prefer, measure, remove unless data works only with it. */
    private suspend fun runApns(sim: OwnerSim, rows: List<OcdApnRow>, options: RunOptions): List<ApnResult> {
        if (!options.apns) return emptyList()
        if (options.scheduled && !store.read().rebootCleared) return listOf(ApnResult("all rows", "", FIRST_USE_PENDING))
        val session = OwnerSession(phone, store, sim, device.model)
        try {
            session.guard(callWaitMs = 0)
            options.interrupt?.reason()?.let { throw RunInterrupted(it) }
        } catch (e: SafetyException) {
            return listOf(ApnResult("all rows", "", "refused_${e.reason}"))
        }
        val candidates = QuietPlan.apnCandidates(rows, sim).filter { options.apnKeys == null || it.key in options.apnKeys }
        val phoneRows = phone.apnQuery(sim.identity.mccmnc) ?: return listOf(ApnResult("all rows", "", "refused_apn_table_unreadable"))
        val data = phone.mobileData(sim.subId)
        if (data.enabled != true) return candidates.map { ApnResult(it.label, it.key, "refused_mobile_data_off") }
        var dataWorks = data.validated == true
        val out = mutableListOf<ApnResult>()
        var inserted = 0
        for (row in candidates) {
            if (phoneRows.any { it.key == row.key }) {
                out += ApnResult(row.label, row.key, "present")
                continue
            }
            if (inserted >= options.maxApnRows) break
            try {
                session.guard(callWaitMs = 0)
                val tested = apnTrial(sim, row, options, keepIfWorks = !dataWorks)
                if (tested.status != SAME_AS_PHONE_ROW) inserted++
                if (tested.kept) dataWorks = true
                out += tested
                if (tested.status in STOPPING) break
            } catch (e: SafetyException) {
                out += ApnResult(row.label, row.key, "refused_${e.reason}")
                // An interrupted background run stops here; the row it was on is reverted already.
                if (e is RunInterrupted) break
            }
        }
        options.basis?.let { basis ->
            val now = phone.nowMs()
            store.update { s -> out.filter { it.apnKey.isNotEmpty() && !it.status.startsWith("refused_interrupted") }.fold(s) { acc, r -> acc.recorded(QuietPlan.apnRecord(sim.key, r, basis, now, acc.entries)) } }
        }
        return out
    }

    /**
     * One APN row trial (the report of 2026-10-20, as AOSP Settings adds an
     * APN): the test row carries the SIM's carrier ID and an empty numeric,
     * mcc and mnc, so no stock row can share its provider key, and it is
     * inserted as edited, so its delete is a real delete. The preferred APN
     * and a hash of every visible row are recorded first; a null insert
     * answer is a stop; the row is read back by its own ID. The revert puts
     * the preferred APN back, waits for internet on it, deletes the test row
     * by its ID and hashes the other rows again.
     */
    private suspend fun apnTrial(sim: OwnerSim, row: OcdApnRow, options: RunOptions, keepIfWorks: Boolean): ApnResult {
        val carrierId = sim.carrierId?.takeIf { it > 0 } ?: return ApnResult(row.label, row.key, "refused_carrier_id_unknown")
        // The phone has this APN already when one of its rows matches on the provider's identity columns (name and types aside).
        val same = phone.apnConflicts(ApnColumns.identity(row)) ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        if (same.isNotEmpty()) {
            log("apn_trial", "${row.label}: the phone has this APN already (row ${same.joinToString()}: same provider key, other name or types); not inserted")
            return ApnResult(row.label, row.key, SAME_AS_PHONE_ROW)
        }
        val values = ApnColumns.testRow(row, carrierId)
        // Its own key must be free too: a row there would be an earlier test row.
        val own = phone.apnConflicts(values) ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        if (own.isNotEmpty()) {
            OwnerSession(phone, store, sim, device.model).stop("apn_test_row_exists: rows ${own.joinToString()} hold the test row's key; review the APN list")
            return ApnResult(row.label, row.key, "test_row_exists")
        }
        val hash = phone.apnTableHash() ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        val marked = phone.apnQueryMarked() ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        val maxIdBefore = (phone.apnQuery(sim.identity.mccmnc).orEmpty() + marked).maxOfOrNull { it.id } ?: 0L
        val preferred = phone.preferredApn(sim.subId)
        if (!preferred.readable) return ApnResult(row.label, row.key, "refused_preferred_apn_unreadable")
        val record = LiveApn(
            sim.key, null, row.label, row.key, Hold.TRIAL, phone.nowMs(), preferred.id, true, madePreferred = false,
            maxIdBefore = maxIdBefore, testName = values["name"], carrierId = carrierId, tableHash = hash,
        )
        store.update { it.copy(apns = it.apns + record) }
        var live = record
        try {
            val id = phone.apnInsert(values)
            if (id == null || id <= maxIdBefore) {
                // A null answer is a stop: the provider did not add the row the app asked for.
                store.update { s -> s.copy(apns = s.apns.filterNot { it.sinceMs == record.sinceMs && it.apnKey == record.apnKey }) }
                OwnerSession(phone, store, sim, device.model).stop("apn_insert_no_row: ${row.label}${id?.let { " answered row $it, not a new one" } ?: ""}; review the APN list")
                return ApnResult(row.label, row.key, "insert_no_row")
            }
            live = live.copy(rowId = id)
            store.update { s -> s.copy(apns = s.apns.map { if (it.sinceMs == record.sinceMs && it.apnKey == record.apnKey) live else it }) }
            val back = phone.apnRowById(id)
            if (back == null || back.name != values["name"] || back.carrierId != carrierId || !back.numeric.isNullOrEmpty()) {
                val ok = revertApn(sim, live)
                if (ok) OwnerSession(phone, store, sim, device.model).stop("apn_row_read_back: row $id is not the test row as inserted")
                return ApnResult(row.label, row.key, "read_back_differs")
            }
            live = live.copy(madePreferred = true)
            store.update { s -> s.copy(apns = s.apns.map { if (it.rowId == id) live else it }) }
            phone.setPreferredApn(sim.subId, id)
            var waited = 0L
            var works = false
            while (waited < options.apnSettleMs) {
                pause(options.pollMs, options)
                waited += options.pollMs
                if (phone.mobileData(sim.subId).validated == true) {
                    works = true
                    break
                }
                if (RegistryText.anyCallActive(phone.registryText())) break
            }
            log("apn_trial", "${row.label}: test row $id (carrier ID $carrierId), data ${if (works) "validated" else "not validated"}")
            if (works && keepIfWorks && store.read().rebootCleared) {
                store.update { s -> s.copy(apns = s.apns.map { if (it.rowId == id) it.copy(hold = Hold.KEPT) else it }) }
                log("keep", "APN ${row.label} kept as the preferred APN: mobile data worked only with it")
                return ApnResult(row.label, row.key, "inserted", dataWorks = true, kept = true)
            }
            val ok = revertApn(sim, live, options)
            return ApnResult(row.label, row.key, if (ok) "inserted" else "revert_not_proven", dataWorks = works)
        } catch (e: SafetyException) {
            withContext(NonCancellable) { revertApn(sim, live) }
            throw e
        }
    }

    /**
     * Puts the preferred APN back, waits for internet on it, deletes the test
     * row by its own ID (never by marker, never a stock row: only a row added
     * after the record with the test row's own name and carrier ID), and
     * proves it: the row gone, the preferred APN back, every other row as it
     * was. A failure sets the stop flag.
     */
    suspend fun revertApn(sim: OwnerSim, live: LiveApn, options: RunOptions? = null): Boolean {
        val session = OwnerSession(phone, store, sim, device.model)
        session.waitIdle(8 * 60_000)
        if (live.madePreferred) {
            phone.setPreferredApn(sim.subId, live.preferredBefore)
            var waited = 0L
            val limit = options?.apnSettleMs ?: 30_000
            while (waited < limit && phone.mobileData(sim.subId).validated != true) {
                phone.sleep(5_000)
                waited += 5_000
            }
        }
        val floor = live.maxIdBefore ?: Long.MAX_VALUE
        // A crash between the insert and its record: the test row is the one added after the record, with its own name and carrier ID.
        val id = live.rowId ?: live.testName?.let { name ->
            phone.apnQueryMarked()?.firstOrNull { it.id > floor && it.name == name && it.carrierId == live.carrierId && it.numeric.isNullOrEmpty() }?.id
        }
        if (id != null && id > floor) phone.apnDelete(id)
        val gone = id == null || phone.apnRowById(id) == null
        val preferredBack = !live.madePreferred || phone.preferredApn(sim.subId).let { it.readable && it.id == live.preferredBefore }
        val tableSame = live.tableHash == null || phone.apnTableHash() == live.tableHash
        val ok = gone && preferredBack && tableSame
        if (ok) {
            store.update { s -> s.copy(apns = s.apns.filterNot { it.apnKey == live.apnKey && it.sim == live.sim }) }
        } else {
            session.stop("apn_revert_not_proven for ${live.label}: row ${if (gone) "gone" else "still there"}, preferred ${if (preferredBack) "back" else "not back"}, other rows ${if (tableSame) "as before" else "CHANGED"}")
        }
        log("apn_revert", "${live.label}: row ${if (gone) "removed" else "STILL THERE"}, preferred APN ${if (preferredBack) "restored" else "NOT restored"}, other rows ${if (tableSame) "unchanged" else "CHANGED"}")
        return ok
    }

    /**
     * The start-up and boot pass (smart revert, the owner's "leftover cleanup
     * on start" and "re-apply per matching SIM at boot or SIM change"):
     * - a trial left by a crash or a kill is reverted with proof;
     * - a trial still live after a reboot is a stop event (overrides must not outlive a reboot);
     * - a keep whose SIM is gone from its slot is cleared;
     * - a keep of a SIM in its slot that a reboot dropped is applied again;
     * - APN trials are removed by their own row ID; a marked row the state does
     *   not know is named for the owner and never deleted.
     * Returns a one-line summary. Writes pass the same guards; when one
     * refuses, the summary says so and the caller retries later.
     */
    suspend fun reconcile(sims: List<OwnerSim>): String {
        val notes = mutableListOf<String>()
        if (store.read().wifiOffSinceMs != null) {
            // A record from a build before 2026-10-20, which switched Wi-Fi off for VoLTE; this build never does.
            store.update { it.copy(wifiOffSinceMs = null) }
            notes += "an old Wi-Fi-off record was dropped (this build never switches Wi-Fi)"
        }
        val state = store.read()
        val bootAt = phone.nowMs() - phone.uptimeMs()
        val cc = phone.carrierConfigText()?.let(CcDump::parse) ?: return (notes + "carrier config unreadable; cleanup waits").joinToString("; ")
        for (trial in state.overrides.filter { it.hold == Hold.TRIAL }) {
            val present = cc.phones[trial.slot]?.get(CcLayer.OVERRIDE)?.get(trial.key).asBool() == trial.value
            if (trial.sinceMs < bootAt && present) {
                store.update { it.copy(stop = "value_after_reboot: ${trial.key} on slot ${trial.slot}").logged(phone.nowMs(), "stop", "a trial override survived a reboot") }
                notes += "STOP: a trial override survived a reboot"
            } else if (trial.sinceMs < bootAt && !present && state.rebootProbeAtMs != null) {
                // Round 25 first-use check 3, measured: the override did not outlive the reboot.
                store.update { it.copy(rebootCleared = true, rebootProbeAtMs = null).logged(phone.nowMs(), "first_use", "check 3 passed: ${trial.key} on slot ${trial.slot + 1} was gone after the reboot") }
                notes += "first-use check 3 passed: the override was gone after the reboot"
            }
        }
        val slots = (state.overrides.map { it.slot }).toSortedSet()
        for (slot in slots) {
            val sim = sims.firstOrNull { it.slot == slot }
            val entries = store.read().overrides.filter { it.slot == slot }
            val layer = cc.overrides(slot).values.fold(emptyMap<String, String>()) { a, b -> a + b }
            // The reboot check's override waits for the reboot; everything else left as a trial is a leftover.
            val trials = entries.filter { it.hold == Hold.TRIAL && !(it.why == PROBE_WHY && it.sinceMs >= bootAt) }
            val foreignKeeps = entries.filter { it.hold == Hold.KEPT && it.sim != sim?.key }
            if (sim == null) {
                // No SIM in the slot: cc needs a subscription, and Android drops the slot's config with the SIM.
                if (layer.isEmpty()) {
                    store.update { s -> s.copy(overrides = s.overrides.filterNot { it.slot == slot && it.hold == Hold.TRIAL }) }
                    if (trials.isNotEmpty()) notes += "slot ${slot + 1}: trial left by an interrupted test is gone (no SIM, nothing live)"
                } else {
                    notes += "slot ${slot + 1}: overrides live without a SIM; cleanup waits for a SIM"
                }
                continue
            }
            if (trials.isEmpty() && foreignKeeps.isEmpty()) continue
            val session = OwnerSession(phone, store, sim)
            try {
                session.guard(callWaitMs = 0)
                // Keeps of another SIM become trials of the clear: a SIM swap reverts them.
                store.update { s -> s.copy(overrides = s.overrides.map { if (it in foreignKeeps) it.copy(hold = Hold.TRIAL) else it }) }
                val proof = revertWithoutSnapshot(session, sim)
                notes += "slot ${slot + 1}: ${trials.size} leftover trial(s), ${foreignKeeps.size} keep(s) of another SIM reverted, proof ${if (proof) "holds" else "FAILED"}"
            } catch (e: SafetyException) {
                notes += "slot ${slot + 1}: cleanup refused (${e.reason}); retried later"
            }
        }
        // Re-apply this SIM's keeps that a reboot dropped, against the dump as it is now.
        val now = phone.carrierConfigText()?.let(CcDump::parse) ?: cc
        for (sim in sims) {
            val keeps = store.read().overrides.filter { it.slot == sim.slot && it.hold == Hold.KEPT && it.sim == sim.key }
            val effective = now.effective(sim.slot)
            val missing = keeps.filter { effective[it.key].asBool() != it.value || now.phones[sim.slot]?.get(CcLayer.OVERRIDE)?.containsKey(it.key) != true }
            if (missing.isEmpty()) continue
            val session = OwnerSession(phone, store, sim)
            try {
                session.guard(callWaitMs = 0)
                for (keep in missing) {
                    val feature = OwnerFeature.ofKey(keep.key) ?: continue
                    session.apply(feature, keep.value, Hold.KEPT, keep.why)
                }
                notes += "slot ${sim.slot + 1}: re-applied ${missing.joinToString { it.key }} for ${sim.key.label}"
            } catch (e: SafetyException) {
                notes += "slot ${sim.slot + 1}: re-apply refused (${e.reason}); retried later"
            }
        }
        // APN trials, by their own row ID.
        for (apn in store.read().apns.filter { it.hold == Hold.TRIAL }) {
            val sim = sims.firstOrNull { it.key == apn.sim } ?: sims.firstOrNull { it.identity.mccmnc == apn.sim.mccmnc }
            if (sim == null) {
                notes += "APN trial ${apn.label}: its SIM is not in; cleanup waits"
                continue
            }
            notes += "APN trial ${apn.label}: ${if (revertApn(sim, apn)) "removed" else "removal FAILED"}"
        }
        // A marked row the state does not hold: never deleted by marker (it may be a stock row an old build merged into); the owner decides.
        val keptIds = store.read().apns.mapNotNull { it.rowId }.toSet()
        val strays = phone.apnQueryMarked()?.filter { it.id !in keptIds }.orEmpty()
        if (strays.isNotEmpty()) notes += "marked APN row(s) ${strays.map { it.id }} not in the record; left for the owner"
        val summary = if (notes.isEmpty()) "no leftovers" else notes.joinToString("; ")
        store.update { it.copy(lastCleanup = summary).logged(phone.nowMs(), "cleanup", summary).probeSettled() }
        return summary
    }

    /** Clear the slot and re-apply only this SIM's keeps; the proof is that the override layers hold exactly those. */
    private suspend fun revertWithoutSnapshot(session: OwnerSession, sim: OwnerSim): Boolean {
        if (!session.waitIdle(8 * 60_000)) return false
        phone.ccClear(sim.slot, sim.subId)
        // A leftover trial on a kept key gives its keep back (a keep of another SIM stays a trial, so it goes).
        store.update { s -> s.copy(overrides = s.overrides.map { if (it.slot == sim.slot && it.sim != sim.key) it.copy(keptBefore = null) else it }).keepsRestored(sim.slot) }
        val keep = store.read().overrides.filter { it.slot == sim.slot && it.hold == Hold.KEPT && it.sim == sim.key }
        for (k in keep) {
            KeyGuard.check(k.key)
            phone.ccSet(sim.slot, sim.subId, k.key, k.value)
        }
        val after = session.dump()
        val layer = after.overrides(sim.slot).values.fold(emptyMap<String, String>()) { a, b -> a + b }
        val ok = layer.keys == keep.map { it.key }.toSet() && keep.all { layer[it.key].asBool() == it.value }
        if (ok) {
            store.update { s -> s.copy(overrides = s.overrides.filterNot { it.slot == sim.slot && it.hold == Hold.TRIAL }) }
        } else {
            session.stop("revert_not_proven on slot ${sim.slot}")
        }
        return ok
    }

    /** One-tap revert-all: every override of every slot with a SIM, and every APN row. Waits for a call to end. */
    suspend fun revertAll(sims: List<OwnerSim>): String {
        val notes = mutableListOf<String>()
        // The owner's undo is remembered: the night does not keep the same values again for these SIMs.
        store.update { s ->
            val undone = s.overrides.filter { it.hold == Hold.KEPT }.mapNotNull { o -> OwnerFeature.ofKey(o.key)?.let { UndoneEntry(o.sim, QuietPlan.entry(it, o.value), phone.nowMs()) } } +
                s.apns.filter { it.hold == Hold.KEPT }.map { UndoneEntry(it.sim, QuietPlan.apnEntry(it.apnKey), phone.nowMs()) }
            s.copy(undone = (s.undone.filterNot { u -> undone.any { it.sim == u.sim && it.entry == u.entry } } + undone).takeLast(MAX_UNDONE))
        }
        if (store.read().wifiOffSinceMs != null) {
            store.update { it.copy(wifiOffSinceMs = null) }
            notes += "an old Wi-Fi-off record was dropped"
        }
        for (slot in store.read().overrides.map { it.slot }.toSortedSet()) {
            val sim = sims.firstOrNull { it.slot == slot }
            if (sim == null) {
                notes += "slot ${slot + 1}: no SIM; Android dropped the slot's config with it"
                store.update { s -> s.copy(overrides = s.overrides.filterNot { it.slot == slot }) }
                continue
            }
            val session = OwnerSession(phone, store, sim)
            // Every value of the slot becomes a trial of this clear; the records stay until the proof holds.
            store.update { s -> s.copy(overrides = s.overrides.map { if (it.slot == slot) it.copy(hold = Hold.TRIAL, keptBefore = null) else it }) }
            val ok = revertWithoutSnapshot(session, sim)
            notes += "slot ${slot + 1}: ${if (ok) "all overrides cleared, proven" else "NOT proven"}"
        }
        for (apn in store.read().apns) {
            val sim = sims.firstOrNull { it.key == apn.sim } ?: sims.firstOrNull { it.identity.mccmnc == apn.sim.mccmnc }
            if (sim == null) {
                notes += "APN ${apn.label}: its SIM is not in"
                continue
            }
            notes += "APN ${apn.label}: ${if (revertApn(sim, apn)) "removed" else "removal FAILED"}"
        }
        val summary = if (notes.isEmpty()) "nothing was live" else notes.joinToString("; ")
        log("revert_all", summary)
        store.update { it.probeSettled() }
        return summary
    }

    /**
     * Round 25 first-use check 3, step one: sets the VoLTE switch to the value
     * it already has (the lab's probe; nothing changes but a config reload) and
     * leaves it live as a trial. The owner then reboots; the start-up pass
     * after the boot records whether it was gone.
     */
    suspend fun rebootProbe(sim: OwnerSim): String {
        val session = OwnerSession(phone, store, sim, device.model)
        val current = session.observe().switch(OwnerFeature.VOLTE) ?: return "refused: the VoLTE switch cannot be read"
        return try {
            session.apply(OwnerFeature.VOLTE, current, Hold.TRIAL, PROBE_WHY)
            store.update { it.copy(rebootProbeAtMs = phone.nowMs()).logged(phone.nowMs(), "first_use", "check 3: VoLTE=$current left live on slot ${sim.slot + 1}; reboot now") }
            "VoLTE=$current is live as an override; reboot the phone now"
        } catch (e: SafetyException) {
            runCatching { session.revertTrials("probe refused") }
            "refused: ${e.reason}"
        }
    }

    companion object {
        const val FIRST_USE_PENDING = "first_use_reboot_check_pending"

        /** How many undone changes are remembered. */
        const val MAX_UNDONE = 40

        /** VoLTE not tested: IMS runs over Wi-Fi and LTE offers no voice path for the measurement. */
        const val IMS_ON_WIFI = "ims_on_wifi"

        /** An off value not tried now: it would leave no voice path (VoLTE off without Wi-Fi calling, Wi-Fi calling off without LTE voice). */
        const val NO_VOICE_PATH = "no_voice_path"

        /** The phone has the row already as far as the provider is concerned (same UNIQUE columns); an insert would merge into it. */
        const val SAME_AS_PHONE_ROW = "same_as_phone_row"

        /** Video calling on: IMS stayed registered for voice through the whole window and never offered video. */
        const val IMS_STACK_NO_VIDEO = "not_supported_by_this_ims_stack"

        /** Video calling on: the static checks say it cannot work on this build (IMS stack, device flag, carrier). */
        const val NOT_AVAILABLE = "not_available_on_this_build"
        const val PROBE_WHY = "first-use check 3"

        /** The Wi-Fi-calling-off override that moves IMS to LTE for VoLTE's measurement. */
        const val MEASURE_WHY = "VoLTE measurement"
        const val VOLTE_METHOD_WFC_OVERRIDE = "wfc_override"

        /** How often a background run looks at its [Interrupt] while it waits. */
        const val INTERRUPT_POLL_MS = 1_000L

        /** APN results after which the step stops. */
        val STOPPING = setOf("revert_not_proven", "insert_no_row", "test_row_exists", "read_back_differs")

        /** OCD's answer for every feature, for the plan and the report. */
        fun answers(bundle: OcdBundle, sim: OwnerSim, device: DeviceInfo): Map<OwnerFeature, OcdAnswer> =
            OwnerFeature.entries.associateWith { OcdValues.answer(bundle, sim.identity, it, device) }
    }
}

/** The reboot check is no longer pending once its override is gone from the record. */
internal fun OwnerState.probeSettled(): OwnerState =
    if (rebootProbeAtMs != null && overrides.none { it.why == OwnerRunner.PROBE_WHY }) copy(rebootProbeAtMs = null) else this
