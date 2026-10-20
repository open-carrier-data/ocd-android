package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.AospImsParser
import io.github.open_carrier_data.check.core.PreciseCallState
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.SimSlot

/**
 * Applies and reverts the owner build's overrides on one slot (lab
 * `Session`, ported). Every write:
 * 1. passes [KeyGuard];
 * 2. passes the stop flag and the refusals ([Refusals], failing closed);
 * 3. is written to the state before its command runs;
 * 4. is checked in the dump afterwards.
 *
 * Every revert clears the slot, re-applies the values the owner build keeps,
 * and proves the result against the snapshot taken before the first change
 * ([RevertProof]). A revert that cannot be proven sets the stop flag, which
 * turns the write path off until the owner clears it (round 23 stop rule).
 */
class OwnerSession(
    private val phone: OwnerPhone,
    private val store: StateStore,
    val sim: OwnerSim,
    private val model: String = "",
) {
    private var snapshot: CcDump? = null
    val slot: Int get() = sim.slot

    private fun log(kind: String, text: String) {
        store.update { it.logged(phone.nowMs(), kind, text) }
    }

    suspend fun dump(): CcDump {
        val text = phone.carrierConfigText()?.takeIf { "Phone Id = " in it }
            ?: throw SafetyException(Refusal.CONFIG_UNREADABLE.wire, Refusal.CONFIG_UNREADABLE.plain)
        return CcDump.parse(text)
    }

    /** How long the running system_server has run; stores its first sighting. Null when the route cannot tell. */
    suspend fun serverUpMs(): Long? {
        val start = phone.serverStart() ?: return null
        val (up, sighting) = ServerClock.upMs(start, store.read().serverSeen)
        if (sighting != store.read().serverSeen) {
            store.update { it.copy(serverSeen = sighting).logged(phone.nowMs(), "server", "system server ${sighting.key.substringAfter('/')} first seen ${start.elapsedMs / 1000} s after boot; up ${up / 1000} s") }
        }
        return up
    }

    /** The refusals now; stores the emergency sighting for the next check. */
    suspend fun safety(): SafetyVerdict {
        val serverUp = serverUpMs()
        val input = SafetyInputs(
            registry = phone.registryText(),
            phoneDump = phone.phoneText(),
            ecmProperty = phone.ecmProperty(),
            slot = slot,
            homeMccMnc = sim.identity.mccmnc,
            nowMs = phone.nowMs(),
            uptimeMs = phone.uptimeMs(),
            seen = store.read().emergencySeen,
            emergencyApi = phone.emergencyApi(),
            serverUpMs = serverUp,
        )
        val verdict = Refusals.check(input)
        if (verdict.seen != store.read().emergencySeen) store.update { it.copy(emergencySeen = verdict.seen) }
        if (verdict.refusal == Refusal.EMERGENCY_STATE_UNREADABLE) log("unreadable", "slot $slot emergency state: ${Refusals.unreadable(input, verdict).joinToString()}")
        return verdict
    }

    /** Throws when a write may not run now. A call gets [callWaitMs] to end first (lab: 60 s). */
    suspend fun guard(callWaitMs: Long = 60_000) {
        store.read().stop?.let { throw SafetyException(Refusal.STOPPED.wire, "stop flag: $it") }
        var waited = 0L
        while (true) {
            val refusal = safety().refusal ?: return
            if (refusal != Refusal.CALL_ACTIVE || waited >= callWaitMs) throw SafetyException(refusal.wire, refusal.plain)
            phone.sleep(2_000)
            waited += 2_000
        }
    }

    /** The dump before the first change. The slot may hold only the overrides the owner build keeps there. */
    suspend fun takeSnapshot(): CcDump {
        snapshot?.let { return it }
        val cc = dump()
        val present = cc.overrides(slot).values.fold(emptyMap<String, String>()) { a, b -> a + b }
        val kept = store.read().kept(slot)
        val ours = present.keys == kept.keys && kept.all { (k, v) -> present[k].asBool() == v }
        if (present.isNotEmpty() && !ours) {
            throw SafetyException(Refusal.FOREIGN_OVERRIDES.wire, "slot $slot holds overrides ${present.keys.sorted()} the owner build did not set")
        }
        snapshot = cc
        log("snapshot", "slot $slot fingerprint ${cc.fingerprint(false).take(19)}; kept ${kept.keys.sorted()}")
        return cc
    }

    /** Applies one value. [hold] TRIAL is reverted at the end of its test; KEPT stays. */
    suspend fun apply(feature: OwnerFeature, value: Boolean, hold: Hold = Hold.TRIAL, why: String? = null) {
        KeyGuard.check(feature.key)
        guard()
        val before = takeSnapshot().effective(slot)[feature.key]
        // Registered first: from here on any exit finds it and reverts it. A trial on a kept key remembers the keep for its revert.
        store.update { state ->
            val keep = state.overrides.firstOrNull { it.slot == slot && it.key == feature.key && it.hold == Hold.KEPT }
            val displaced = if (hold == Hold.TRIAL) keep?.value else null
            state.copy(
                overrides = state.overrides.filterNot { it.slot == slot && it.key == feature.key } +
                    LiveOverride(slot, sim.key, feature.key, value, hold, phone.nowMs(), before, if (displaced != null) keep?.why else why, keptBefore = displaced),
            )
        }
        val result = phone.ccSet(slot, sim.subId, feature.key, value)
        // The loader applies an override on its own handler; give the dump a few seconds to show it.
        var after = dump()
        var tries = 0
        fun taken(d: CcDump) = d.effective(slot)[feature.key].asBool() == value && d.phones[slot]?.get(CcLayer.OVERRIDE)?.containsKey(feature.key) == true
        while (result.ok && !taken(after) && tries++ < 3) {
            phone.sleep(1_000)
            after = dump()
        }
        val effective = after.effective(slot)[feature.key].asBool()
        val inLayer = after.phones[slot]?.get(CcLayer.OVERRIDE)?.containsKey(feature.key) == true
        log("apply", "${feature.wire}=$value ($hold) on slot $slot: ${result.reason ?: "ok"}, effective $effective")
        if (!result.ok || effective != value || !inLayer) {
            throw SafetyException("set_value_did_not_take", "${feature.key} did not take (${result.reason ?: effective})")
        }
    }

    /**
     * Reverts every trial of the slot: clear, re-apply the kept values, prove.
     * Waits for a call to end for up to [callWaitMs]; returns null when the
     * call outlasts it (the trial stays recorded, and the cleanup reverts it
     * later). A proof that fails sets the stop flag.
     */
    suspend fun revertTrials(reason: String, callWaitMs: Long = 8 * 60_000): RevertProof? {
        val trials = store.read().overrides.filter { it.slot == slot && it.hold == Hold.TRIAL }
        if (trials.isEmpty()) return RevertProof.of(takeSnapshot(), takeSnapshot(), slot, store.read().kept(slot))
        if (!waitIdle(callWaitMs)) {
            log("revert_waits", "slot $slot: a call is up; the revert waits")
            return null
        }
        val base = takeSnapshot()
        val cleared = phone.ccClear(slot, sim.subId)
        // A keep a trial displaced is a keep again.
        store.update { it.keepsRestored(slot) }
        val keep = store.read().kept(slot)
        for ((key, value) in keep) {
            KeyGuard.check(key)
            phone.ccSet(slot, sim.subId, key, value)
        }
        // Keys kept at the snapshot and let go now go back to the phone's own value.
        val atSnapshot = CcLayer.OVERRIDES.flatMap { base.phones[slot]?.get(it)?.keys.orEmpty() }.toSet()
        val dropped = trials.map { it.key }.filter { it in atSnapshot && it !in keep }.toSet()
        val trialKeys = trials.map { it.key }.toSet()
        val proof = RevertProof.of(base, dump(), slot, keep, dropped)
        log("revert", "slot $slot ($reason): clear ${cleared.reason ?: "ok"}, kept ${keep.keys.sorted()}, proof ${if (proof.ok) "holds" else "FAILED"}")
        if (proof.ok) {
            store.update { s -> s.copy(overrides = s.overrides.filterNot { it.slot == slot && it.hold == Hold.TRIAL && it.key in trialKeys }) }
        } else {
            stop("revert_not_proven on slot $slot: ${proof.unexpectedDifferences.take(3).map { "${it.where}.${it.key}" }}")
        }
        return proof
    }

    /** Drops one kept value: the slot goes back to the phone's own value for that key. */
    suspend fun dropKeep(feature: OwnerFeature, reason: String): Boolean {
        val kept = store.read().overrides.firstOrNull { it.slot == slot && it.key == feature.key && it.hold == Hold.KEPT } ?: return true
        store.update { s -> s.copy(overrides = s.overrides.map { if (it == kept) it.copy(hold = Hold.TRIAL, keptBefore = null) else it }) }
        return revertTrials("drop keep: $reason")?.ok == true
    }

    suspend fun waitIdle(maxMs: Long): Boolean {
        var waited = 0L
        while (RegistryText.anyCallActive(phone.registryText())) {
            if (waited >= maxMs) return false
            phone.sleep(2_000)
            waited += 2_000
        }
        return true
    }

    fun stop(reason: String) {
        store.update { it.copy(stop = reason).logged(phone.nowMs(), "stop", reason) }
    }

    /**
     * One read of the slot for the evidence ladder. [iwlan] also reads the
     * IWLAN service's and QNS's dumps (Wi-Fi calling's tunnel and back-off);
     * without it the IWLAN state comes from the registry alone.
     */
    suspend fun observe(iwlan: Boolean = false): Observation {
        val registry = phone.registryText().orEmpty()
        val phoneText = phone.phoneText()
        val cc = dump()
        val keys = OwnerFeature.entries.map { it.key } + "carrier_nr_availabilities_int_array"
        val effective = cc.effective(slot)
        val simSlot = SimSlot.of(slot) ?: throw SafetyException("slot", "slot $slot out of range")
        val reg = RegistryParser.parse(registry.lineSequence(), simSlot)
        val api = phone.imsApi(slot, sim.subId)
        val aosp = api?.state() ?: phoneText?.let { AospImsParser.parse(it.lineSequence(), simSlot) }
        // From the dumps, never the device log: reading logcat asks the owner for log access (2026-10-20).
        val iwlanReading = if (iwlan) IwlanDumps.reading(registry, phone.iwlanServiceText(), phone.qnsText(), slot) else IwlanRegistry.reading(registry, slot)
        return Observation(
            config = keys.mapNotNull { key -> effective[key]?.let { key to it } }.toMap(),
            ims = AospImsParser.reading(aosp, reg, dumpRead = api != null || phoneText != null),
            flags = api?.flags() ?: phoneText?.let { ImsFlags.parse(it, slot) } ?: ImsFlags(),
            ps = RegistrationParser.wwanPs(RegistryText.block(registry, slot)),
            lteVops = reg?.lteVops,
            nrVops = reg?.nrVops,
            wifiConnected = phone.wifiConnected(),
            iwlan = iwlanReading,
            callActive = RegistryText.anyCallActive(registry),
            callNetworkType = reg?.call?.networkType?.takeIf { reg.call.foreground == PreciseCallState.ACTIVE },
        )
    }

    val deviceModel: String get() = model
}
