package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.ImsService
import io.github.open_carrier_data.check.core.OcdBundle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How one run tests. */
data class RunOptions(
    /** Test the off value too (the owner's "on and off"). */
    val testOffs: Boolean = true,
    /**
     * Interactive runs only: how long a voice off waits for the owner to place
     * a call himself (the app never dials). 0 in scheduled runs, where a voice
     * off is then never kept.
     */
    val voiceCheckMs: Long = 0,
    val settleMs: Long = 120_000,
    val pollMs: Long = 10_000,
    val apnSettleMs: Long = 90_000,
    /** Test OCD's internet APN rows the phone lacks. */
    val apns: Boolean = true,
    /** A scheduled run: refused until the first-use checks are done. */
    val scheduled: Boolean = false,
    /**
     * VoLTE while IMS runs over Wi-Fi: switch Wi-Fi off for the measurement
     * (lab `--wifi-off-test`) and always back on afterwards. Without it, VoLTE
     * cannot be observed then and its test is skipped with [IMS_ON_WIFI].
     */
    val wifiOffForVolte: Boolean = true,
    /** Video calling on: PHH IMS can take minutes to add video to a registration. */
    val videoSettleMs: Long = 300_000,
    /** How long Wi-Fi gets to connect again after the measurement. */
    val wifiBackMs: Long = 60_000,
)

/**
 * The owner's loop for one SIM (the owner direction, 2026-10-19): for each
 * feature, test the value the phone does not have now (on or off), measure,
 * revert with proof, then apply the best working value as a keep. Then the
 * same for OCD's internet APN rows. Each step is short enough for one
 * WorkManager run.
 */
class OwnerRunner(
    private val phone: OwnerPhone,
    private val store: StateStore,
    private val device: DeviceInfo,
) {
    private fun log(kind: String, text: String) = store.update { it.logged(phone.nowMs(), kind, text) }

    /** Tests one feature on one SIM and applies its best working value. Never throws; a refusal is in the result. */
    suspend fun testFeature(sim: OwnerSim, feature: OwnerFeature, ocd: OcdAnswer, options: RunOptions): FeatureResult {
        val session = OwnerSession(phone, store, sim, device.model)
        val start = FeatureResult(feature, null, null, ocd)
        if (options.scheduled && !store.read().rebootCleared) return start.copy(refused = FIRST_USE_PENDING)
        var wifiTurnedOff = false
        try {
            session.guard(callWaitMs = 0)
            session.takeSnapshot()
            var base = session.observe()
            if (feature == OwnerFeature.VOLTE && imsOnWifi(base)) {
                // VoLTE cannot be seen while IMS runs over Wi-Fi: measure it with Wi-Fi off, as the lab does.
                if (!options.wifiOffForVolte) {
                    log("wifi_off", "VoLTE on slot ${sim.slot + 1}: IMS runs over Wi-Fi and the Wi-Fi-off measurement is not chosen; VoLTE not tested")
                    return start.copy(baseline = base.switch(feature), refused = IMS_ON_WIFI)
                }
                wifiTurnedOff = true
                base = wifiOff(session, options)
            }
            val baseline = base.switch(feature) ?: return start.copy(refused = "switch_unreadable")
            val baselineWorks = Evidence.working(feature, base)
            var result = start.copy(baseline = baseline, baselineWorks = baselineWorks)
            val other = !baseline
            val skip = skipReason(feature, other, base, options)
            if (skip == null) {
                val trial = trial(session, feature, other, options)
                result = if (other) {
                    result.copy(onWorks = trial.works, onReason = trial.reason)
                } else {
                    result.copy(offWorks = trial.works, offReason = trial.reason, offVoiceMeasured = trial.voiceMeasured)
                }
                if (trial.proof?.ok == false) return result.copy(refused = "revert_not_proven")
                if (trial.proof == null) return result.copy(refused = "revert_waits_for_call")
                if (trial.lostVoice) return result.copy(refused = "lost_voice")
            } else if (other) {
                result = result.copy(onReason = skip)
            } else {
                result = result.copy(offReason = skip)
            }
            return choose(session, result)
        } catch (e: SafetyException) {
            runCatching { session.revertTrials("error: ${e.reason}") }
            log("refused", "${feature.wire} on slot ${sim.slot}: ${e.reason}")
            return start.copy(refused = e.reason)
        } finally {
            // Also on an error or a cancelled worker: Wi-Fi always comes back on.
            if (wifiTurnedOff) withContext(NonCancellable) { wifiBack(options) }
        }
    }

    private fun imsOnWifi(o: Observation): Boolean = o.ims.registered == true && o.ims.transport?.isWifi == true && o.wifiConnected == true

    /**
     * Switches Wi-Fi off (recorded first, so a crash leaves a record the
     * cleanup acts on) and waits for IMS to leave Wi-Fi. Returns the
     * observation to measure from.
     */
    private suspend fun wifiOff(session: OwnerSession, options: RunOptions): Observation {
        session.guard(callWaitMs = 0)
        store.update { it.copy(wifiOffSinceMs = phone.nowMs()) }
        if (!phone.setWifiEnabled(false)) throw SafetyException("wifi_off_refused", "the phone refused to switch Wi-Fi off")
        log("wifi_off", "VoLTE on slot ${session.slot + 1}: IMS runs over Wi-Fi; Wi-Fi switched off for the measurement")
        var waited = 0L
        var o = session.observe()
        while (waited < options.settleMs && !(o.ims.registered == true && o.ims.transport?.isWifi == false)) {
            if (o.callActive) throw SafetyException(Refusal.CALL_ACTIVE.wire, Refusal.CALL_ACTIVE.plain)
            phone.sleep(options.pollMs)
            waited += options.pollMs
            o = session.observe()
        }
        log(
            "wifi_off",
            if (o.ims.transport?.isWifi == false) "IMS registered over ${o.ims.transport} ${waited / 1000} s after Wi-Fi went off" else "IMS did not register over mobile within ${waited / 1000} s of Wi-Fi off",
        )
        return o
    }

    /** Wi-Fi on again, then a wait for it to connect. The record stays while the phone refuses, for the cleanup. */
    private suspend fun wifiBack(options: RunOptions) {
        if (store.read().wifiOffSinceMs == null) return
        if (!restoreWifi("end of the VoLTE measurement")) return
        var waited = 0L
        while (waited < options.wifiBackMs && phone.wifiConnected() != true) {
            phone.sleep(2_000)
            waited += 2_000
        }
        log("wifi_restore", if (phone.wifiConnected() == true) "Wi-Fi connected again after ${waited / 1000} s" else "Wi-Fi switched on, not yet connected after ${waited / 1000} s")
    }

    /** Switches Wi-Fi back on when the state records it off. Returns false while the phone refuses. */
    suspend fun restoreWifi(reason: String): Boolean {
        if (store.read().wifiOffSinceMs == null) return true
        val ok = phone.setWifiEnabled(true)
        if (ok) store.update { it.copy(wifiOffSinceMs = null) }
        log("wifi_restore", "Wi-Fi switched back on ($reason)${if (ok) "" else ": refused, the cleanup tries again"}")
        return ok
    }

    private fun skipReason(feature: OwnerFeature, value: Boolean, base: Observation, options: RunOptions): String? = when {
        value && feature == OwnerFeature.VONR && DeviceCapability.vonrNotCapable(device.model) -> "device_not_capable"
        value && feature == OwnerFeature.VOWIFI && base.wifiConnected != true -> "wifi_not_connected"
        value && feature == OwnerFeature.VOWIFI && base.iwlan?.heldBackBy != null -> "wifi_calling_backoff"
        !value && !options.testOffs -> "off_tests_not_chosen"
        !value && !store.read().rebootCleared -> FIRST_USE_PENDING
        else -> null
    }

    private class Trial(val works: Boolean?, val reason: String, val voiceMeasured: Boolean?, val proof: RevertProof?, val lostVoice: Boolean = false)

    /** One value on, settle, measure, revert with proof. */
    private suspend fun trial(session: OwnerSession, feature: OwnerFeature, value: Boolean, options: RunOptions): Trial {
        session.apply(feature, value)
        val videoOn = feature == OwnerFeature.VIDEO_CALLING && value
        val settleMs = if (videoOn) maxOf(options.videoSettleMs, options.settleMs) else options.settleMs
        var waited = 0L
        var verdict = Verdict(Outcome.WAIT, "not_yet")
        var voice: Boolean? = null
        var callSeen = false
        var voiceRegistered = false
        var videoSeen = false
        while (waited < settleMs) {
            phone.sleep(options.pollMs)
            waited += options.pollMs
            val o = session.observe()
            if (o.ims.registered == true && ImsService.MMTEL in o.ims.services) voiceRegistered = true
            if (o.ims.registered == true && ImsService.MMTEL_VIDEO in o.ims.services) videoSeen = true
            if (o.callActive) {
                callSeen = true
                if (o.callNetworkType != null) voice = true
            }
            if (o.callActive && !(feature.voice && !value && options.voiceCheckMs > 0)) {
                // A call that starts during a test stops it; the revert waits for the call to end.
                verdict = Verdict(Outcome.WAIT, "call_started")
                break
            }
            verdict = Evidence.decide(feature, value, o)
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
        log("trial", "${feature.wire}=$value: ${verdict.outcome} (${verdict.reason}), voice ${voice ?: "not measured"}")
        if (voice == false) session.stop("lost_voice with ${feature.wire} off")
        val timedOut = waited >= settleMs && verdict.outcome == Outcome.WAIT && verdict.reason != "call_started"
        val reason = when {
            // IMS registered for voice the whole time, but never offered video: the stack does not do video.
            timedOut && videoOn && voiceRegistered && !videoSeen -> IMS_STACK_NO_VIDEO
            timedOut -> "settle_timeout"
            else -> verdict.reason
        }
        if (reason == IMS_STACK_NO_VIDEO) log("trial", "video_calling=true: IMS registered for voice for ${settleMs / 1000} s but never with video; not supported by this IMS stack")
        return Trial(works, reason, voice, proof, lostVoice = voice == false)
    }

    /**
     * The best working value (the owner's "apply the best working value"):
     * on when on works; else off, when off works and, for a voice feature,
     * voice was measured with it off; else the phone's own value. A kept value
     * that no longer works is dropped (smart revert).
     */
    private suspend fun choose(session: OwnerSession, r: FeatureResult): FeatureResult {
        val baseline = r.baseline ?: return r
        val feature = r.feature
        val onWorks = if (baseline) r.baselineWorks else r.onWorks
        val offOk = if (!baseline) true else r.offWorks == true && (!feature.voice || r.offVoiceMeasured == true)
        val (best, why) = when {
            onWorks == true -> true to "on registered with its service"
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
        if (best != null && best != baseline) {
            if (keptNow != null) session.dropKeep(feature, "replaced by a better value")
            session.apply(feature, best, Hold.KEPT, why)
            applied = true
            log("keep", "${feature.wire}=$best kept on slot ${session.slot}: $why")
        } else if (best == null && keptNow != null) {
            session.dropKeep(feature, "the kept value no longer works")
            log("smart_revert", "${feature.wire}: the kept value no longer works; back to the phone's own value")
        }
        return r.copy(best = best, bestWhy = why, applied = applied)
    }

    /** OCD's internet APN rows for the SIM the phone lacks: insert, prefer, measure, remove unless data works only with it. */
    suspend fun testApns(sim: OwnerSim, rows: List<OcdApnRow>, options: RunOptions): List<ApnResult> {
        if (!options.apns) return emptyList()
        if (options.scheduled && !store.read().rebootCleared) return listOf(ApnResult("all rows", "", FIRST_USE_PENDING))
        val session = OwnerSession(phone, store, sim, device.model)
        try {
            session.guard(callWaitMs = 0)
        } catch (e: SafetyException) {
            return listOf(ApnResult("all rows", "", "refused_${e.reason}"))
        }
        val candidates = rows.filter { ApnConf.match(it, sim.identity) == ApnMatch.MATCHES && it.isInternet }
            .filter { runCatching { KeyGuard.checkApn(it) }.isSuccess }
            .distinctBy { it.key }
        val phoneRows = phone.apnQuery(sim.identity.mccmnc) ?: return listOf(ApnResult("all rows", "", "refused_apn_table_unreadable"))
        val data = phone.mobileData(sim.subId)
        if (data.enabled != true) return candidates.map { ApnResult(it.label, it.key, "refused_mobile_data_off") }
        var dataWorks = data.validated == true
        val out = mutableListOf<ApnResult>()
        for (row in candidates) {
            if (phoneRows.any { it.key == row.key }) {
                out += ApnResult(row.label, row.key, "present")
                continue
            }
            try {
                session.guard(callWaitMs = 0)
                val tested = apnTrial(sim, row, options, keepIfWorks = !dataWorks)
                if (tested.kept) dataWorks = true
                out += tested
                if (tested.status == "revert_not_proven") break
            } catch (e: SafetyException) {
                out += ApnResult(row.label, row.key, "refused_${e.reason}")
            }
        }
        return out
    }

    private suspend fun apnTrial(sim: OwnerSim, row: OcdApnRow, options: RunOptions, keepIfWorks: Boolean): ApnResult {
        val values = ApnColumns.values(row)
        // The provider merges an insert that matches a phone row on its UNIQUE columns into that row; never insert one.
        val conflicts = phone.apnConflicts(values) ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        if (conflicts.isNotEmpty()) {
            log("apn_trial", "${row.label}: the phone has this APN already (row ${conflicts.joinToString()}: same provider key, other name or types); not inserted")
            return ApnResult(row.label, row.key, SAME_AS_PHONE_ROW)
        }
        val before = phone.apnQuery(sim.identity.mccmnc) ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        val marked = phone.apnQueryMarked() ?: return ApnResult(row.label, row.key, "refused_apn_table_unreadable")
        val maxIdBefore = (before + marked).maxOfOrNull { it.id } ?: 0L
        val preferred = phone.preferredApn(sim.subId)
        if (!preferred.readable) return ApnResult(row.label, row.key, "refused_preferred_apn_unreadable")
        val record = LiveApn(sim.key, null, row.label, row.key, Hold.TRIAL, phone.nowMs(), preferred.id, true, madePreferred = false, maxIdBefore = maxIdBefore)
        store.update { it.copy(apns = it.apns + record) }
        var live = record
        try {
            val inserted = phone.apnInsert(values)
            val id = phone.apnQuery(sim.identity.mccmnc)?.firstOrNull { it.id > maxIdBefore && it.key == row.key && it.name?.endsWith(ApnColumns.MARKER) == true }?.id
            if (!inserted || id == null) {
                // A phone row that now carries the marker was merged into: stop, never delete it.
                val changed = phone.apnQueryMarked()?.filter { it.id <= maxIdBefore }
                if (changed == null || changed.isNotEmpty()) {
                    store.update { s -> s.copy(apns = s.apns.filterNot { it.sinceMs == record.sinceMs && it.apnKey == record.apnKey }) }
                    OwnerSession(phone, store, sim, device.model).stop("apn_insert_merged: ${row.label} changed phone row(s) ${changed?.map { it.id } ?: "unreadable"}; restore the APN list")
                    return ApnResult(row.label, row.key, "insert_merged_into_phone_row")
                }
                revertApn(sim, live)
                return ApnResult(row.label, row.key, if (!inserted) "insert_failed" else "inserted_row_not_found")
            }
            live = live.copy(rowId = id, madePreferred = true)
            store.update { s -> s.copy(apns = s.apns.map { if (it.sinceMs == record.sinceMs && it.apnKey == record.apnKey) live else it }) }
            phone.setPreferredApn(sim.subId, id)
            var waited = 0L
            var works = false
            while (waited < options.apnSettleMs) {
                phone.sleep(options.pollMs)
                waited += options.pollMs
                if (phone.mobileData(sim.subId).validated == true) {
                    works = true
                    break
                }
                if (RegistryText.anyCallActive(phone.registryText())) break
            }
            log("apn_trial", "${row.label}: data ${if (works) "validated" else "not validated"}")
            if (works && keepIfWorks && store.read().rebootCleared) {
                store.update { s -> s.copy(apns = s.apns.map { if (it.rowId == id) it.copy(hold = Hold.KEPT) else it }) }
                log("keep", "APN ${row.label} kept as the preferred APN: mobile data worked only with it")
                return ApnResult(row.label, row.key, "inserted", dataWorks = true, kept = true)
            }
            val ok = revertApn(sim, live)
            return ApnResult(row.label, row.key, if (ok) "inserted" else "revert_not_proven", dataWorks = works)
        } catch (e: SafetyException) {
            revertApn(sim, live)
            throw e
        }
    }

    /** Restores the preferred APN, deletes the row, proves both. A failure sets the stop flag. */
    suspend fun revertApn(sim: OwnerSim, live: LiveApn): Boolean {
        val session = OwnerSession(phone, store, sim, device.model)
        session.waitIdle(8 * 60_000)
        if (live.madePreferred) phone.setPreferredApn(sim.subId, live.preferredBefore)
        // Only a row added after the record: a phone row (at or below maxIdBefore) is never deleted as ours.
        val floor = live.maxIdBefore ?: Long.MAX_VALUE
        val id = live.rowId ?: phone.apnQuery(sim.identity.mccmnc)?.firstOrNull { it.id > floor && it.key == live.apnKey && it.name?.endsWith(ApnColumns.MARKER) == true }?.id
        if (id != null) phone.apnDelete(id)
        val gone = id == null || phone.apnQuery(sim.identity.mccmnc)?.none { it.id == id } == true
        val preferredBack = !live.madePreferred || phone.preferredApn(sim.subId).let { it.readable && it.id == live.preferredBefore }
        val ok = gone && preferredBack
        if (ok) {
            store.update { s -> s.copy(apns = s.apns.filterNot { it.apnKey == live.apnKey && it.sim == live.sim }) }
        } else {
            session.stop("apn_revert_not_proven for ${live.label}")
        }
        log("apn_revert", "${live.label}: row ${if (gone) "removed" else "STILL THERE"}, preferred APN ${if (preferredBack) "restored" else "NOT restored"}")
        return ok
    }

    /**
     * The start-up and boot pass (smart revert, the owner's "leftover cleanup
     * on start" and "re-apply per matching SIM at boot or SIM change"):
     * - a trial left by a crash or a kill is reverted with proof;
     * - a trial still live after a reboot is a stop event (overrides must not outlive a reboot);
     * - a keep whose SIM is gone from its slot is cleared;
     * - a keep of a SIM in its slot that a reboot dropped is applied again;
     * - APN trials are removed, and so are marked rows the state does not keep.
     * Returns a one-line summary. Writes pass the same guards; when one
     * refuses, the summary says so and the caller retries later.
     */
    suspend fun reconcile(sims: List<OwnerSim>): String {
        val notes = mutableListOf<String>()
        if (store.read().wifiOffSinceMs != null) {
            notes += if (restoreWifi("leftover of an interrupted VoLTE measurement")) {
                "Wi-Fi switched back on after an interrupted VoLTE measurement"
            } else {
                "Wi-Fi restore refused; retried later"
            }
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
        // APN trials, and marked rows nobody keeps.
        for (apn in store.read().apns.filter { it.hold == Hold.TRIAL }) {
            val sim = sims.firstOrNull { it.key == apn.sim } ?: sims.firstOrNull { it.identity.mccmnc == apn.sim.mccmnc }
            if (sim == null) {
                notes += "APN trial ${apn.label}: its SIM is not in; cleanup waits"
                continue
            }
            notes += "APN trial ${apn.label}: ${if (revertApn(sim, apn)) "removed" else "removal FAILED"}"
        }
        val keptIds = store.read().apns.mapNotNull { it.rowId }.toSet()
        val strays = phone.apnQueryMarked()?.filter { it.id !in keptIds }.orEmpty()
        if (strays.isNotEmpty() && store.read().stop != null) {
            // After a stop a marked row may be a phone row the provider merged an insert into; the owner decides.
            notes += "marked APN row(s) ${strays.map { it.id }} left for the owner (stop flag set)"
        } else {
            strays.forEach { row ->
                phone.apnDelete(row.id)
                notes += "removed a marked APN row the state does not keep (${row.apn})"
            }
        }
        val summary = if (notes.isEmpty()) "no leftovers" else notes.joinToString("; ")
        store.update { it.copy(lastCleanup = summary).logged(phone.nowMs(), "cleanup", summary).probeSettled() }
        return summary
    }

    /** Clear the slot and re-apply only this SIM's keeps; the proof is that the override layers hold exactly those. */
    private suspend fun revertWithoutSnapshot(session: OwnerSession, sim: OwnerSim): Boolean {
        if (!session.waitIdle(8 * 60_000)) return false
        phone.ccClear(sim.slot, sim.subId)
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
        if (store.read().wifiOffSinceMs != null) notes += if (restoreWifi("revert all")) "Wi-Fi switched back on" else "Wi-Fi restore refused"
        for (slot in store.read().overrides.map { it.slot }.toSortedSet()) {
            val sim = sims.firstOrNull { it.slot == slot }
            if (sim == null) {
                notes += "slot ${slot + 1}: no SIM; Android dropped the slot's config with it"
                store.update { s -> s.copy(overrides = s.overrides.filterNot { it.slot == slot }) }
                continue
            }
            val session = OwnerSession(phone, store, sim)
            // Every value of the slot becomes a trial of this clear; the records stay until the proof holds.
            store.update { s -> s.copy(overrides = s.overrides.map { if (it.slot == slot) it.copy(hold = Hold.TRIAL) else it }) }
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

        /** VoLTE not tested: IMS runs over Wi-Fi and the Wi-Fi-off measurement was not chosen. */
        const val IMS_ON_WIFI = "ims_on_wifi"

        /** The phone has the row already as far as the provider is concerned (same UNIQUE columns); an insert would merge into it. */
        const val SAME_AS_PHONE_ROW = "same_as_phone_row"

        /** Video calling on: IMS stayed registered for voice through the whole window and never offered video. */
        const val IMS_STACK_NO_VIDEO = "not_supported_by_this_ims_stack"
        const val PROBE_WHY = "first-use check 3"

        /** OCD's answer for every feature, for the plan and the report. */
        fun answers(bundle: OcdBundle, sim: OwnerSim, device: DeviceInfo): Map<OwnerFeature, OcdAnswer> =
            OwnerFeature.entries.associateWith { OcdValues.answer(bundle, sim.identity, it, device) }
    }
}

/** The reboot check is no longer pending once its override is gone from the record. */
internal fun OwnerState.probeSettled(): OwnerState =
    if (rebootProbeAtMs != null && overrides.none { it.why == OwnerRunner.PROBE_WHY }) copy(rebootProbeAtMs = null) else this
