package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.TimeUnit

/*
 * OCD round 30, "testsDecide", the app's side:
 * (a) the newest values go in at the next idle moment (CarrierSettings installs on the app's
 *     `activate`), and in that same moment every changed value of the SIM is tested together, not
 *     one feature a night;
 * (b) on a measured failure the app splits the changed values to find the one at fault and tries
 *     the candidates OCD knows, as non-persistent overrides: OCD's other current values for that
 *     key and profile (the Google lane included, the most specific first), the switch's other
 *     value, Android's default. The first that passes wins and becomes a measured record. A value
 *     counts as failed only when another value passes under the same conditions; otherwise it is
 *     an outage, the newest value stays, the record reads "no value passed" and a source review
 *     opens. The previous dataset's value is never a candidate as such, and nothing is invented;
 * (c) voice loss: when IMS voice is lost after a change and no candidate restores it, the build's
 *     dataset is the last candidate. When only that restores calls, CarrierSettings holds the
 *     build's dataset (round 31's voice-loss hold), the next idle moment at home splits the newest
 *     dataset's differences from the build's to find the failing values, and the newest dataset
 *     serves again with the winners in their place; a second loss under the same differing values
 *     is a stop event. When not even the build's dataset restores calls, the newest dataset stays
 *     (the cause is the network) and it is a stop event.
 * Round 31 ("keys"): a key that the ROM's device override (or LineageOS's fixed key) sets on this
 * phone is the device's, not OCD's: no candidate is tried for it and no record written ([DeviceKeys]).
 * Groups A and B are never tested, overridden or recorded. X5's offs are tested as overrides and
 * released only by a record of a call that went ACTIVE (or an SMS delivered) with the off in
 * effect. Caps: 10 dialled test calls a day per SIM, 6 IMS re-registrations caused by OCD an hour
 * and 30 a day; work over a cap continues in the next idle moment.
 *
 * Everything here is pure; the Android side applies the overrides and measures.
 */

/** What a key is tested by (the tests of rounds 26 to 29). */
@Serializable
enum class TestClass(val wire: String, val plain: String) {
    VOICE("voice", "calls out over LTE"),
    WIFI_CALLING("wifi_calling", "Wi-Fi calling registration and calls out over Wi-Fi"),
    SMS("sms", "SMS over IMS"),
    VIDEO("video", "video registration with voice unchanged"),
    OTHER("other", "no regression: IMS with the same services, mobile data validated"),
}

object KeyClasses {
    /** The keys of OCD's closed list that the IMS stack reads for calls (with ims.* and imsvoice.*). */
    private val VOICE_KEYS = setOf(
        "carrier_volte_available_bool", "carrier_volte_override_wfc_provisioning_bool", "carrier_volte_provisioned_bool",
        "carrier_volte_provisioning_required_bool", "carrier_ims_gba_required_bool", "carrier_supports_ss_over_ut_bool",
        "carrier_allow_turnoff_ims_bool", "enhanced_4g_lte_on_by_default_bool", "editable_enhanced_4g_lte_bool", "hide_enhanced_4g_lte_bool",
        "volte_replacement_rat_int", "carrier_allow_transfer_ims_call_bool", "support_ims_conference_call_bool", "ims_conference_size_limit_int",
        "is_ims_conference_size_enforced_bool", "support_ims_conference_event_package_bool", "allow_hold_in_ims_call", "vonr_enabled_bool",
        "vonr_setting_visibility_bool", "carrier_nr_availabilities_int_array",
    )
    private val WFC_KEYS = setOf(
        "carrier_wfc_ims_available_bool", "carrier_wfc_supports_wifi_only_bool", "carrier_default_wfc_ims_enabled_bool",
        "carrier_default_wfc_ims_mode_int", "carrier_default_wfc_ims_roaming_enabled_bool", "carrier_default_wfc_ims_roaming_mode_int",
        "editable_wfc_mode_bool", "editable_wfc_roaming_mode_bool", "carrier_promote_wfc_on_call_fail_bool", "wifi_calls_can_be_hd_audio",
        "allow_merge_wifi_calls_when_vowifi_off_bool",
    )

    fun of(key: String): TestClass = when {
        key == OwnerFeature.VIDEO_CALLING.key || key.startsWith("imsvt.") -> TestClass.VIDEO
        key.startsWith("imssms.") || key == OwnerFeature.SMS_OVER_IMS.key -> TestClass.SMS
        // Non-emergency iwlan.* keys count as voice and IMS keys tested over Wi-Fi (testsDecide's conditions).
        key in WFC_KEYS || key.startsWith("imswfc.") || key.startsWith("iwlan.") -> TestClass.WIFI_CALLING
        key in VOICE_KEYS || key.startsWith("ims.") || key.startsWith("imsvoice.") -> TestClass.VOICE
        else -> TestClass.OTHER
    }
}

/** One served value an install changed for a SIM: the batch is made of these. */
@Serializable
data class Changed(val key: String, val old: JsonElement? = null, val new: JsonElement? = null, val source: String = "", val cls: TestClass = KeyClasses.of(key))

/** A value to try as a non-persistent override. [value] null: Android's default (the key removed). */
@Serializable
data class Candidate(val value: JsonElement?, val origin: String) {
    val androidDefault: Boolean get() = value == null
    val plain: String get() = if (value == null) "Android's default" else value.toString().take(80)

    companion object {
        const val SPLIT_OLD = "split: the value before the change, for the split only"
        const val ALTERNATIVE = "alternative"
        const val GOOGLE = "google"
        const val OTHER_VALUE = "other_value"
        const val ANDROID_DEFAULT = "android_default"
        const val BUILD = "build_dataset"
        const val X5_OFF = "x5_off"
        /** Round 31's voice-loss split: the newest dataset's value, set over the held build's dataset. */
        const val HELD_NEWEST = "held: the newest dataset's value"
    }
}

/**
 * The candidates for a failing key (testsDecide (b)), in order: OCD's other current values for
 * the key and profile from any family, the most specific profile first ([stack] generic to
 * specific, each profile's `alternatives` and `values`), then the Google value, then a bool's
 * other value, then Android's default. Never the failing value, never a value twice, and never
 * the previous dataset's value unless it is one of these on its own.
 */
object Candidates {
    fun of(key: String, failing: JsonElement?, type: String?, stack: List<JsonObject>, googleValue: JsonElement?, androidDefault: JsonElement? = null): List<Candidate> {
        val out = mutableListOf<Candidate>()
        fun add(value: JsonElement?, origin: String) {
            if (value != null && (value is JsonNull || (type != null && !TypedValues.fits(type, value)))) return
            if (value == null) {
                if (failing == null || out.any { it.androidDefault }) return
                if (androidDefault != null && TypedValues.same(androidDefault, failing)) return
            } else {
                if (TypedValues.same(value, failing) || out.any { TypedValues.same(it.value, value) }) return
            }
            out += Candidate(value, origin)
        }
        for (profile in stack.asReversed()) {
            val id = (profile["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ((profile["values"] as? JsonObject)?.get(key))?.let { add(it, "${Candidate.ALTERNATIVE}:$id") }
            for (alt in ((profile["alternatives"] as? JsonObject)?.get(key) as? JsonArray).orEmpty()) {
                val o = alt as? JsonObject ?: continue
                val families = (o["families"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("+")
                o["value"]?.let { add(it, "${Candidate.ALTERNATIVE}:$id:$families") }
            }
        }
        googleValue?.let { add(it, Candidate.GOOGLE) }
        TypedValues.bool(failing)?.let { add(JsonPrimitive(!it), Candidate.OTHER_VALUE) }
        add(null, Candidate.ANDROID_DEFAULT)
        return out
    }
}

/**
 * Round 31 ("keys"): the values the ROM sets last on this phone, its device override (on LineageOS
 * the `carrier_config_override.xml` overlay) and LineageOS's fixed key, are facts about the device,
 * not about a carrier. A test of such a key would measure the override's value, and its record would
 * claim that a value passed which never ran. So the app tries no candidate for them and writes no
 * record. CarrierSettings names them in `status.device_override` and as the source of those keys in
 * `explain` (R31.3). A round 30 build serves the same device values as per-device records
 * (`device_record:`), which the same rule covers until that build is gone.
 */
object DeviceKeys {
    /** explain's source codes of a value the ROM sets for the device (R31.3). */
    val SOURCES = setOf("device_override", "lineageos_fixed")
    const val ROUND30_RECORD = "device_record:"

    fun owned(source: String): Boolean = source in SOURCES || SOURCES.any { source.startsWith("$it:") } || source.startsWith(ROUND30_RECORD)

    /** The keys [explain] serves from the device (by their source), and the keys [status] names as the override's. */
    fun of(explain: ExplainView?, status: DatasetStatus? = null): Set<String> =
        explain?.keys?.filterValues { owned(it.source) }?.keys.orEmpty() + status?.deviceOverride?.keys.orEmpty()

    const val WHY = "set by the ROM's device override on this phone: the device's value, not Open Carrier Data's"
}

/**
 * The guard for round 30's override trials: never a group A or B key or the version string, never
 * a key the ROM's device override sets ([DeviceKeys], round 31), and keys only by name shape. It
 * adds to [KeyGuard] (the five switches of the legacy trials), which stays as it is.
 */
object TrialGuard {
    private val NAME = Regex("[A-Za-z0-9_.]{1,128}")

    fun problem(key: String, datasetA: Set<String> = emptySet(), device: Set<String> = emptySet()): String? = when {
        !NAME.matches(key) -> "not a carrier config key"
        key in device -> DeviceKeys.WHY
        else -> Groups.untestable(key, datasetA)
    }

    fun check(keys: Collection<String>, datasetA: Set<String> = emptySet(), device: Set<String> = emptySet()) {
        for (k in keys) problem(k, datasetA, device)?.let { throw SafetyException("trial_key_refused", "$k: $it") }
    }
}

/** One key of a round 30 override trial: its registry type (null: not known) and value; a null value is Android's default. */
data class TypedOverride(val type: String?, val value: JsonElement?)

/** Whether voice service is gone on this slot: IMS not registered for voice and no circuit-switched service. Null: unreadable. */
fun Observation.voiceLost(): Boolean? {
    val imsVoice = registeredWith(io.github.open_carrier_data.check.core.ImsService.MMTEL)
    val circuit = cs?.state
    return when {
        imsVoice == true -> false
        circuit in setOf("home", "roaming") -> false
        imsVoice == false -> true
        else -> null
    }
}

/** IMS and data as one trial reads them, before and after a change. */
@Serializable
data class TrialObs(
    val imsRegistered: Boolean?,
    val imsOnWifi: Boolean?,
    val services: Set<String>,
    val dataValidated: Boolean?,
    /** Mobile service (data registration) at home. */
    val inService: Boolean?,
    val voiceLost: Boolean?,
)

/** A calls-out measurement on one bearer within the trial, from the call ledger (rounds 26 to 28). */
@Serializable
data class CallEvidence(val bearer: String, val result: String, val level: String? = null, val method: String? = null) {
    companion object {
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val NOT_COUNTED = "not_counted"
    }
}

@Serializable
enum class TrialOutcome { PASS, MEASURED_FAIL, NOT_COUNTED }

/** What one trial showed. [level]/[method]/[bearer]: the strongest pass measured, for a record. */
@Serializable
data class TrialVerdict(
    val outcome: TrialOutcome,
    val failed: Set<TestClass> = emptySet(),
    val reason: String = "",
    val level: String = "no_regression",
    val method: String = "override_trial",
    val bearer: String? = null,
) {
    val passed: Boolean get() = outcome == TrialOutcome.PASS
}

object TrialJudge {
    const val MMTEL = "mmtel"
    const val SMSIP = "smsip"
    const val VIDEO = "mmtel-video"

    /**
     * One trial's verdict (rounds 26 to 28 as round 30 reads them). Not counted, and so never a
     * failure: no mobile service, IMS not registered before, a call that was not counted. A
     * measured failure: IMS lost its registration or a service it had, mobile data no longer
     * validated, a calls-out call that failed. [sms]: SMS both ways over IMS measured (true),
     * failed (false) or not run (null).
     */
    fun judge(classes: Set<TestClass>, before: TrialObs, after: TrialObs, calls: List<CallEvidence>, sms: Boolean?): TrialVerdict {
        if (after.inService == false || before.inService == false) return TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "no mobile service")
        if (after.imsRegistered == null || after.dataValidated == null && before.dataValidated == true) return TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "IMS or data unreadable")
        if (before.imsRegistered != true) return TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "IMS was not registered before the change")
        val failed = linkedSetOf<TestClass>()
        val reasons = mutableListOf<String>()
        if (after.imsRegistered != true) {
            failed += TestClass.entries
            reasons += "IMS lost its registration"
        } else {
            val lost = before.services - after.services
            if (MMTEL in lost) {
                failed += TestClass.VOICE
                failed += TestClass.WIFI_CALLING
            }
            if (SMSIP in lost) failed += TestClass.SMS
            if (VIDEO in lost) failed += TestClass.VIDEO
            if (lost.isNotEmpty()) {
                failed += TestClass.OTHER
                reasons += "IMS lost ${lost.sorted().joinToString()}"
            }
        }
        if (before.dataValidated == true && after.dataValidated == false) {
            failed += TestClass.OTHER
            reasons += "mobile data no longer validated"
        }
        for (c in calls) {
            when (c.result) {
                CallEvidence.FAILED -> {
                    failed += if (c.bearer == "iwlan") TestClass.WIFI_CALLING else TestClass.VOICE
                    reasons += "calls out over ${c.bearer} failed"
                }
                CallEvidence.NOT_COUNTED -> if (failed.isEmpty()) return TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "a calls-out call was not counted over ${c.bearer}")
            }
        }
        if (failed.isNotEmpty()) return TrialVerdict(TrialOutcome.MEASURED_FAIL, failed, reasons.joinToString("; "))
        val pass = calls.filter { it.result == CallEvidence.PASSED }.maxByOrNull { if (it.level == "L3") 2 else 1 }
        return when {
            pass != null -> TrialVerdict(TrialOutcome.PASS, reason = "calls out over ${pass.bearer}", level = pass.level ?: "L2", method = "override_trial", bearer = pass.bearer)
            sms == true && TestClass.SMS in classes -> TrialVerdict(TrialOutcome.PASS, reason = "SMS both ways over IMS", level = "sms_both_ways", bearer = if (after.imsOnWifi == true) "iwlan" else "lte")
            else -> TrialVerdict(TrialOutcome.PASS, reason = "IMS with the same services" + if (after.dataValidated == true) ", mobile data validated" else "", level = "no_regression")
        }
    }
}

@Serializable
enum class CampaignPhase { BATCH, SPLIT, CANDIDATES, DONE }

/** One SIM's tests of one install, carried across idle moments when a cap stops it (persisted). */
@Serializable
data class Campaign(
    val id: String,
    val subId: Int,
    val slot: Int,
    val mccmnc: String,
    val commit: String,
    val datasetDigest: String,
    val startedAtMs: Long,
    val changes: List<Changed>,
    /** Why it runs: an install, a new SIM or build, or the 14-day re-test. */
    val why: String = "install",
    val phase: CampaignPhase = CampaignPhase.BATCH,
    val suspects: List<String> = emptyList(),
    /** In SPLIT: the half left changed in the current trial. */
    val half: List<String> = emptyList(),
    val culprit: String? = null,
    val candidates: List<Candidate> = emptyList(),
    val candidateIndex: Int = 0,
    val winners: Map<String, Candidate> = emptyMap(),
    val noValue: List<String> = emptyList(),
    val failedClasses: Set<TestClass> = emptySet(),
    val trials: Int = 0,
    val note: String = "",
    val endedAtMs: Long? = null,
    val lastVerdict: TrialVerdict? = null,
    /** IMS and data before the install (read right before `activate`): what every trial of an install is compared with. */
    val baseline: TrialObs? = null,
    /**
     * Round 31's voice-loss split: the build's dataset is held, so [changes] (old: the build's value,
     * new: the newest dataset's) are not served; each trial sets the newest values as overrides
     * ([CampaignPlanner.onHeld]).
     */
    val held: Boolean = false,
) {
    val done: Boolean get() = phase == CampaignPhase.DONE
}

/** One trial to run: the overrides on top of what CarrierSettings serves, and the tests to run. */
data class TrialPlan(val label: String, val overrides: Map<String, Candidate>, val classes: Set<TestClass>)

/** What a finished trial settled, for the records. */
sealed interface Settlement {
    data class Winner(val key: String, val failing: JsonElement?, val candidate: Candidate, val verdict: TrialVerdict, val trials: Int) : Settlement
    data class NoValuePassed(val key: String, val failing: JsonElement?, val tried: List<Candidate>, val verdict: TrialVerdict) : Settlement
    data class Passed(val verdict: TrialVerdict) : Settlement
}

object CampaignPlanner {
    /** Trials one campaign may take in all before it ends as not decided (it starts again at the next trigger). */
    const val MAX_TRIALS = 24

    fun start(id: String, sim: OwnerSim, commit: String, datasetDigest: String, changes: List<Changed>, datasetA: Set<String>, nowMs: Long, why: String = "install", device: Set<String> = emptySet()): Campaign =
        Campaign(id, sim.subId, sim.slot, sim.identity.mccmnc, commit, datasetDigest, nowMs, changes.filter { TrialGuard.problem(it.key, datasetA, device) == null }, why)

    /** Why a campaign runs when it is round 31's voice-loss split. */
    const val VOICE_LOSS_SPLIT = "voice_loss_split"

    /**
     * Round 31's voice-loss split over the held build's dataset: [plan] says which values differ from
     * what is served; with the build's dataset served, every change the plan does not set otherwise
     * goes on at its newest value, and the split's "old value" is the build's, served already. No
     * dialled call or SMS: a trial is judged by IMS voice registration and data, which is what the
     * loss showed.
     */
    fun onHeld(c: Campaign, plan: TrialPlan): TrialPlan {
        val out = LinkedHashMap<String, Candidate>()
        for (ch in c.changes) {
            val cand = plan.overrides[ch.key] ?: Candidate(ch.new, Candidate.HELD_NEWEST)
            if (cand.origin == Candidate.SPLIT_OLD) continue
            out[ch.key] = cand
        }
        for ((k, v) in plan.overrides) if (c.changes.none { it.key == k }) out[k] = v
        return plan.copy(overrides = out, classes = plan.classes - setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS))
    }

    /** [c] without the changes the ROM's device override sets ([DeviceKeys]): for a campaign opened before they were known. */
    fun withoutDevice(c: Campaign, device: Set<String>): Campaign =
        if (c.changes.none { it.key in device } || c.phase != CampaignPhase.BATCH) c else c.copy(changes = c.changes.filterNot { it.key in device })

    /**
     * Round 31 (SM8, SM9): the passive check only. On an IMS stack the tests are not calibrated on,
     * or in a ROM build whose person did not turn the active tests on, a campaign runs its batch trial
     * without overrides, calls or SMS (IMS registration, data validation, the no-regression check)
     * and ends after one counted trial: it splits nothing, tries no candidate and settles nothing.
     */
    fun passive(c: Campaign, verdict: TrialVerdict, why: String, nowMs: Long): Campaign {
        val counted = c.copy(trials = c.trials + 1, lastVerdict = verdict, note = verdict.reason)
        return when (verdict.outcome) {
            TrialOutcome.NOT_COUNTED -> if (counted.trials >= MAX_TRIALS) counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "not decided after ${counted.trials} trials: ${verdict.reason}") else counted
            TrialOutcome.PASS -> counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "passive check passed (${verdict.reason}); $why")
            TrialOutcome.MEASURED_FAIL -> counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "passive check failed (${verdict.reason}); no candidate is tried: $why")
        }
    }

    private fun classes(c: Campaign, keys: Collection<String>): Set<TestClass> =
        (c.changes.filter { it.key in keys }.map { it.cls } + TestClass.OTHER + c.failedClasses).toSet()

    /** The next trial of [c], or null when it is done. */
    fun next(c: Campaign): TrialPlan? = when (c.phase) {
        CampaignPhase.DONE -> null
        // An install tests what its changes touch; a new SIM, a new build or the 14-day re-test runs every test of rounds 26 to 29.
        CampaignPhase.BATCH -> TrialPlan(
            if (c.changes.isEmpty()) "the served values (${c.why})" else "all ${c.changes.size} changed values together",
            c.winners,
            classes(c, c.changes.map { it.key }) + if (c.why == "install") emptySet() else setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS),
        )
        CampaignPhase.SPLIT -> {
            val back = (c.suspects - c.half.toSet()).associateWith { k -> Candidate(c.changes.first { it.key == k }.old, Candidate.SPLIT_OLD) }
            TrialPlan("split: ${c.half.size} of ${c.suspects.size} suspected values changed", c.winners + back, classes(c, c.half))
        }
        CampaignPhase.CANDIDATES -> {
            val k = c.culprit!!
            val cand = c.candidates[c.candidateIndex]
            TrialPlan("$k = ${cand.plain} (${cand.origin})", c.winners + (k to cand), classes(c, listOf(k)))
        }
    }

    /**
     * [c] after the trial [next] gave was judged [verdict]; [candidatesOf] lists a key's
     * candidates (from the dataset; see [Candidates]). Not counted leaves the campaign where it
     * was, for the next idle moment.
     */
    fun advance(c: Campaign, verdict: TrialVerdict, candidatesOf: (Changed) -> List<Candidate>, nowMs: Long): Pair<Campaign, List<Settlement>> {
        val counted = c.copy(trials = c.trials + 1, lastVerdict = verdict, note = verdict.reason)
        if (verdict.outcome == TrialOutcome.NOT_COUNTED) {
            return (if (counted.trials >= MAX_TRIALS) counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "not decided after ${counted.trials} trials: ${verdict.reason}") else counted) to emptyList()
        }
        val out = mutableListOf<Settlement>()
        var n = when (c.phase) {
            CampaignPhase.DONE -> counted
            CampaignPhase.BATCH -> if (verdict.passed) {
                out += Settlement.Passed(verdict)
                counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "all changed values passed: ${verdict.reason}")
            } else {
                val failed = verdict.failed.ifEmpty { TestClass.entries.toSet() }
                val suspects = c.changes.filter { it.key !in c.winners && it.key !in c.noValue && (it.cls in failed || it.cls == TestClass.OTHER) }.map { it.key }
                    .ifEmpty { c.changes.filter { it.key !in c.winners && it.key !in c.noValue }.map { it.key } }
                narrow(counted.copy(failedClasses = failed), suspects, candidatesOf, nowMs, out, verdict)
            }
            CampaignPhase.SPLIT -> narrow(counted, if (verdict.passed) c.suspects - c.half.toSet() else c.half, candidatesOf, nowMs, out, verdict)
            CampaignPhase.CANDIDATES -> {
                val k = c.culprit!!
                val changed = c.changes.first { it.key == k }
                if (verdict.passed) {
                    // A failure counts only now that another value passed under the same conditions.
                    out += Settlement.Winner(k, changed.new, c.candidates[c.candidateIndex], verdict, counted.trials)
                    counted.copy(phase = CampaignPhase.BATCH, winners = c.winners + (k to c.candidates[c.candidateIndex]), culprit = null, candidates = emptyList(), candidateIndex = 0, suspects = emptyList(), half = emptyList())
                } else if (c.candidateIndex + 1 < c.candidates.size) {
                    counted.copy(candidateIndex = c.candidateIndex + 1)
                } else {
                    // No value passed: an outage, the newest value stays, a source review opens, and the test repeats next time.
                    out += Settlement.NoValuePassed(k, changed.new, c.candidates, verdict)
                    counted.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, noValue = c.noValue + k, note = "no value passed for $k; the newest value stays")
                }
            }
        }
        if (!n.done && n.trials >= MAX_TRIALS) n = n.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "not decided within $MAX_TRIALS trials; the newest values stay")
        return n to out
    }

    private fun narrow(c: Campaign, suspects: List<String>, candidatesOf: (Changed) -> List<Candidate>, nowMs: Long, out: MutableList<Settlement>, verdict: TrialVerdict): Campaign = when {
        suspects.isEmpty() -> c.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, note = "the failure is not in a value the app may test; the newest values stay")
        suspects.size == 1 -> {
            val k = suspects.single()
            val changed = c.changes.first { it.key == k }
            val list = candidatesOf(changed)
            if (list.isEmpty()) {
                out += Settlement.NoValuePassed(k, changed.new, emptyList(), verdict)
                c.copy(phase = CampaignPhase.DONE, endedAtMs = nowMs, noValue = c.noValue + k, note = "no candidate for $k; the newest value stays")
            } else {
                c.copy(phase = CampaignPhase.CANDIDATES, suspects = suspects, half = emptyList(), culprit = k, candidates = list, candidateIndex = 0)
            }
        }
        else -> c.copy(phase = CampaignPhase.SPLIT, suspects = suspects, half = suspects.take(suspects.size / 2))
    }
}

/**
 * X5 (spec 5.4): a held off is tried as an override and released only by a record of what it
 * needs: an outgoing call that went ACTIVE on the remaining path (VoLTE's and the provisioning
 * keys' off: over Wi-Fi calling; Wi-Fi calling's off: over LTE), or for SMS over IMS an SMS
 * delivered with the off in effect. "Registered" never proves a voice off.
 */
object X5Test {
    /** The bearer a voice off must still carry a call on; null for SMS over IMS. */
    fun remainingBearer(key: String): String? = when (key) {
        "carrier_volte_available_bool", "carrier_volte_provisioning_required_bool", "carrier_volte_provisioned_bool" -> "iwlan"
        "carrier_wfc_ims_available_bool" -> "lte"
        else -> null
    }

    fun isSms(key: String): Boolean = key == "imssms.sms_over_ims_supported_bool"

    /** Whether the off passed: a calls-out pass on the remaining bearer, or the SMS delivered. */
    fun passed(key: String, calls: List<CallEvidence>, smsDelivered: Boolean?): Boolean =
        if (isSms(key)) smsDelivered == true else calls.any { it.bearer == remainingBearer(key) && it.result == CallEvidence.PASSED }
}

/** The voice-loss trigger (testsDecide (b) and its conditions): always on, at home and roaming. */
object VoiceLoss {
    /**
     * Whether a served value could be the cause: voice is lost while the mobile network is in service
     * (no coverage at all is not ours), and the phone serves an installed dataset (or a local record)
     * rather than the build's. With the build's dataset in service no candidate of ours can help.
     */
    fun applies(voiceLost: Boolean?, status: DatasetStatus, inService: Boolean? = true): Boolean =
        voiceLost == true && inService == true && status.available && status.servingInstalled

    /** The voice-class changes to try first, newest install first; group A never, and never a key the device override sets. */
    fun suspects(changes: List<Changed>, device: Set<String> = emptySet()): List<Changed> =
        (changes.filter { it.cls == TestClass.VOICE || it.cls == TestClass.WIFI_CALLING } + changes.filter { it.cls == TestClass.OTHER }).filterNot { it.key in device }

    /**
     * Round 31's voice-loss split: every value the newest dataset ([newest]'s keys) serves that the
     * build's dataset ([build], served while held) does not, as a change from the build's value to the
     * newest one. Never group A or B, the version string or a key the device override sets.
     */
    fun differences(newest: Map<String, ExplainKey>, build: Map<String, ExplainKey>, datasetA: Set<String>, device: Set<String> = emptySet()): List<Changed> {
        val out = mutableListOf<Changed>()
        for (k in (newest.keys + build.keys).sorted()) {
            if (TrialGuard.problem(k, datasetA, device) != null) continue
            val n = newest[k]?.value
            val b = build[k]?.value
            if (TypedValues.same(n, b)) continue
            out += Changed(k, b, n, newest[k]?.source.orEmpty())
        }
        return out
    }

    /** What a finished split tells CarrierSettings (R31.8), or null while it is not decided. */
    sealed interface SplitEnd {
        /** Winners in place of the failing values: release with `winners`. */
        data class Winners(val keys: List<String>) : SplitEnd
        /** All the newest values passed together: the loss was not theirs; release with `no_failing_value`. */
        data object NoFailingValue : SplitEnd
        /** A value failed and no other passed: the build's dataset stays held, and it is a stop event. */
        data class NoWinner(val keys: List<String>) : SplitEnd
    }

    fun splitEnd(c: Campaign): SplitEnd? = when {
        !c.done -> null
        c.noValue.isNotEmpty() -> SplitEnd.NoWinner(c.noValue)
        c.winners.isNotEmpty() && c.lastVerdict?.passed == true -> SplitEnd.Winners(c.winners.keys.toList())
        c.winners.isEmpty() && c.lastVerdict?.passed == true -> SplitEnd.NoFailingValue
        // Not decided (the trial cap, or the failure is in a value the app may not test): the build's dataset stays held.
        else -> SplitEnd.NoWinner(emptyList())
    }

    /**
     * The build's bundle as overrides (the final candidate): each key the build serves at its
     * value, and each key only the served bundle has at Android's default. Groups A and B are left
     * to CarrierSettings, which serves them the same way under both datasets, and so are the keys the
     * ROM's device override sets: it applies last under both datasets, and an override would replace it.
     */
    fun buildOverrides(served: Map<String, ExplainKey>, build: Map<String, ExplainKey>, datasetA: Set<String>, device: Set<String> = emptySet()): Map<String, Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        for ((k, v) in build) if (TrialGuard.problem(k, datasetA, device) == null && !TypedValues.same(served[k]?.value, v.value)) out[k] = Candidate(v.value, Candidate.BUILD)
        for (k in served.keys - build.keys) if (TrialGuard.problem(k, datasetA, device) == null && served[k]?.value != null) out[k] = Candidate(null, Candidate.BUILD)
        return out
    }
}

/** One event a cap counts, per SIM: an IMS re-registration caused by OCD (an override set or cleared, an activation) or a dialled test call. */
@Serializable
data class CapEvent(val subId: Int, val mccmnc: String, val kind: String, val atMs: Long) {
    companion object {
        const val REREGISTRATION = "reregistration"
        const val CALL = "call"
    }
}

object TestCaps {
    const val CALLS_PER_DAY = 10
    const val REREGISTRATIONS_PER_HOUR = 6
    const val REREGISTRATIONS_PER_DAY = 30
    val HOUR = TimeUnit.HOURS.toMillis(1)
    val DAY = TimeUnit.DAYS.toMillis(1)
    const val MAX_EVENTS = 400

    private fun mine(events: List<CapEvent>, sim: SimKey, kind: String, nowMs: Long, windowMs: Long) =
        events.count { it.subId == sim.subId && it.mccmnc == sim.mccmnc && it.kind == kind && nowMs - it.atMs in 0 until windowMs }

    /** How many more re-registrations this SIM may have now. */
    fun reregistrationsLeft(events: List<CapEvent>, sim: SimKey, nowMs: Long): Int = minOf(
        REREGISTRATIONS_PER_HOUR - mine(events, sim, CapEvent.REREGISTRATION, nowMs, HOUR),
        REREGISTRATIONS_PER_DAY - mine(events, sim, CapEvent.REREGISTRATION, nowMs, DAY),
    ).coerceAtLeast(0)

    /** How many trials fit: a chain of n trials sets n override bundles and clears once. */
    fun trialsLeft(events: List<CapEvent>, sim: SimKey, nowMs: Long): Int = (reregistrationsLeft(events, sim, nowMs) - 1).coerceAtLeast(0)

    /** Dialled test calls left today; [mailboxCalls] are the app's own calls in the call ledger. */
    fun callsLeft(mailboxCalls: List<CallRecord>, sim: SimKey, nowMs: Long): Int =
        (CALLS_PER_DAY - mailboxCalls.count { it.method == CallMethod.MAILBOX_CALL && it.sim?.sameCard(sim) == true && nowMs - it.atMs in 0 until DAY }).coerceAtLeast(0)

    fun add(events: List<CapEvent>, sim: SimKey, kind: String, nowMs: Long, n: Int = 1): List<CapEvent> =
        (events.filter { nowMs - it.atMs < DAY } + List(n) { CapEvent(sim.subId, sim.mccmnc, kind, nowMs) }).takeLast(MAX_EVENTS)
}

/** What the idle moment reads (testsDecide's conditions), in one pass. A null is unreadable and closes it. */
data class IdleInputs(
    val interactive: Boolean?,
    val screenOffForMs: Long?,
    val inCall: Boolean?,
    /** The last call the phone saw, ringing or up, wall clock. */
    val lastCallSeenAtMs: Long?,
    val mediaActive: Boolean?,
    val recording: Boolean?,
    val ecbm: Boolean?,
    val emergencySmsMode: Boolean?,
    /** The first sighting of an emergency call, wall clock ([EmergencySeen]). */
    val emergencySeenAtMs: Long?,
    /** How long the phone (or the system server, whichever is shorter) has run: the call watch must rule out an emergency call before it. */
    val upMs: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val roaming: Boolean?,
    val nowMs: Long,
    /** The call watch's record across restarts ([RestartWindow]); null: none, so the first hour after a restart waits. */
    val callsSeen: CallsSeen? = null,
)

enum class IdleBlock(val wire: String, val plain: String) {
    SCREEN_ON("screen_on", "the screen is on"),
    SCREEN_ON_RECENTLY("screen_on_recently", "the screen was on in the last 2 minutes"),
    IN_CALL("in_call", "a call is ringing, up or on hold"),
    CALL_RECENT("call_recent", "a call ended less than 10 minutes ago"),
    MEDIA("media", "media is playing or recording"),
    EMERGENCY_MODE("emergency_mode", "the phone is in emergency callback or emergency SMS mode"),
    EMERGENCY_RECENT("emergency_recent", "an emergency call may have ended less than 60 minutes ago"),
    BATTERY_LOW("battery_low", "the battery is under 20 % and not charging"),
    ROAMING("roaming", "the phone is roaming"),
}

/**
 * The phone counts as not in use only when all of testsDecide's conditions hold. No night and no Wi-Fi
 * requirement otherwise. Background runs wait for it, as they must not disturb the person using the
 * phone; "Test now" does not ([OWNER]).
 */
object IdleMoment {
    val SCREEN_OFF_MS = TimeUnit.MINUTES.toMillis(2)
    val CALL_QUIET_MS = TimeUnit.MINUTES.toMillis(10)
    val EMERGENCY_QUIET_MS = TimeUnit.MINUTES.toMillis(60)
    const val MIN_BATTERY = 20

    /**
     * What still stops "Test now" and its trials (2026-10-25): a call, emergency callback or SMS mode, roaming.
     * The 10 minutes after an emergency call are the refusals' ([Refusals]); the screen, media, the battery and
     * the quiet after an ordinary call are the person's, who pressed the button.
     */
    val OWNER = setOf(IdleBlock.IN_CALL, IdleBlock.EMERGENCY_MODE, IdleBlock.ROAMING)

    /** The first condition that does not hold; null in the idle moment. */
    fun blocker(i: IdleInputs): IdleBlock? = blockers(i).firstOrNull()

    /** Every condition that does not hold, in the order above. */
    fun blockers(i: IdleInputs): List<IdleBlock> = buildList {
        if (i.interactive != false) add(IdleBlock.SCREEN_ON)
        if (i.screenOffForMs == null || i.screenOffForMs < SCREEN_OFF_MS) add(IdleBlock.SCREEN_ON_RECENTLY)
        if (i.inCall != false) add(IdleBlock.IN_CALL)
        if (i.lastCallSeenAtMs != null && i.nowMs - i.lastCallSeenAtMs < CALL_QUIET_MS) add(IdleBlock.CALL_RECENT)
        if (i.mediaActive != false || i.recording != false) add(IdleBlock.MEDIA)
        if (i.ecbm != false || i.emergencySmsMode != false) add(IdleBlock.EMERGENCY_MODE)
        val watched = RestartWindow.emergencyAtMs(i.callsSeen)
        if ((i.emergencySeenAtMs != null && i.nowMs - i.emergencySeenAtMs < EMERGENCY_QUIET_MS) ||
            (watched != null && i.nowMs - watched < EMERGENCY_QUIET_MS) ||
            // The first hour after a restart only when the call watch cannot rule out an emergency call just before it.
            (i.upMs < EMERGENCY_QUIET_MS && !RestartWindow.clear(i.callsSeen, i.nowMs - i.upMs, i.nowMs, EMERGENCY_QUIET_MS))
        ) {
            add(IdleBlock.EMERGENCY_RECENT)
        }
        if ((i.batteryPercent == null || i.batteryPercent < MIN_BATTERY) && i.charging != true) add(IdleBlock.BATTERY_LOW)
        if (i.roaming != false) add(IdleBlock.ROAMING)
    }
}

/** When a SIM's tests are due (testsDecide (a)): an install that changed it (F4), a new SIM, build or IMS stack, or 14 days since its last pass. */
object TestsDue {
    val RETEST_MS: Long = TimeUnit.DAYS.toMillis(14)

    /** The SIM's last settled campaign end, from [past]; a new basis key (SIM, build, stack) makes it due at once. */
    fun due(sim: SimKey, basis: String, past: List<CampaignEnd>, nowMs: Long): String? {
        val mine = past.filter { it.subId == sim.subId && it.mccmnc == sim.mccmnc }
        val last = mine.maxByOrNull { it.atMs } ?: return "a new SIM"
        if (last.basis != basis) return "a new build or IMS stack"
        if (nowMs - last.atMs >= RETEST_MS || nowMs < last.atMs) return "14 days since the last test"
        return null
    }
}

/** A finished campaign, kept short for the triggers and the screen. */
@Serializable
data class CampaignEnd(val subId: Int, val mccmnc: String, val atMs: Long, val basis: String, val commit: String, val note: String, val winners: Int = 0, val noValue: Int = 0) {
    companion object {
        const val MAX = 40
    }
}
