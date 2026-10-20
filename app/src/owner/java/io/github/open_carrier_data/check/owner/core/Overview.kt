package io.github.open_carrier_data.check.owner.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * The owner screen and the Settings entry in plain words (the redesign of
 * 2026-10-20): the status sentence at the top, one evidence ladder per
 * feature, and the lines for mobile data, the automatic checks and the last
 * check. Pure functions over the state the owner build keeps; the technical
 * record stays in the state, the screen's Details and the log, and nothing
 * here changes the phone.
 */

/** One active SIM as the screen names it: the name Settings shows, and what ties it to the state's records. */
data class SimView(val slot: Int, val subId: Int, val mccmnc: String, val name: String) {
    /** The state's records for this SIM. The carrier ID is left out: the subscription and network code name the card. */
    fun owns(key: SimKey): Boolean = key.subId == subId && key.mccmnc == mccmnc
}

/** A T2 pass of the read-only check's guided call test, on this phone model, for a SIM's network ([io.github.open_carrier_data.check.core.T2Record]). */
data class CallsTested(val feature: OwnerFeature, val mccmnc: String?)

/** The rungs of the evidence ladder, in order: each needs the one before it. */
enum class Rung(val plain: String) {
    NETWORK_OFFERS("Network offers it"),
    SWITCHED_ON("Switched on"),
    SIGNED_IN("Signed in"),
    CALLS_TESTED("Calls tested"),
}

/** What a status colour means. The screen gives each of the first four one fixed colour; neutral takes the theme's. */
enum class Tone { WORKS, CHANGED, PROBLEM, NOT_POSSIBLE, NEUTRAL }

/**
 * One feature's row: how far the evidence goes ([reached] of [rungs]), the
 * colour it takes, the status in plain words, and the same for TalkBack.
 * A feature that cannot work shows a single mark instead of a ladder.
 */
data class FeatureLadder(
    val feature: OwnerFeature,
    val rungs: List<Rung>,
    val reached: Int,
    val tone: Tone,
    val text: String,
    val spoken: String,
    val notPossible: Boolean = false,
) {
    /** The row's content description, for example "VoLTE: signed in, calls not tested yet". */
    val description: String get() = "${feature.label}: $spoken"
}

object Ladders {
    /** The order of "Calls and messages". */
    val ORDER = listOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI, OwnerFeature.SMS_OVER_IMS, OwnerFeature.VIDEO_CALLING, OwnerFeature.VONR)

    /** The features the guided call test (T2) covers; only they have the fourth rung. */
    val CALL_TESTED = setOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI)

    /** Results that no later test changes: the S20's modem (VoNR), video on PHH IMS. */
    private val STATIC = setOf("device_not_capable", OwnerRunner.NOT_AVAILABLE, OwnerRunner.IMS_STACK_NO_VIDEO)
    private const val NETWORK_BLOCK = "blocked_at_network_offers"
    private const val USER_SWITCH_BLOCK = "blocked_at_user_switch"
    private val NEEDS_WIFI = setOf("wifi_not_connected", "blocked_at_wifi_connected")

    fun rungs(feature: OwnerFeature): List<Rung> = if (feature in CALL_TESTED) Rung.entries else Rung.entries.dropLast(1)

    fun of(state: OwnerState, sim: SimView, calls: List<CallsTested> = emptyList()): List<FeatureLadder> = ORDER.map { of(it, state, sim, calls) }

    /**
     * The ladder of [feature] on [sim], from the newest records: the entry
     * results (each test's and each light check's), the last run's feature
     * result, the live overrides and the call tests.
     *
     * - Network offers it: IMS registered with it, or the phone's own carrier
     *   settings switch it on, or OCD's data says the carrier offers it; not
     *   when an on-test stopped at the network's offer.
     * - Switched on: the effective switch (a value Open Carrier Data keeps,
     *   else the phone's own).
     * - Signed in: IMS registered with the feature's service on its bearer.
     * - Calls tested: a T2 pass on this phone model for the SIM's network.
     */
    fun of(feature: OwnerFeature, state: OwnerState, sim: SimView, calls: List<CallsTested> = emptyList()): FeatureLadder {
        val rungs = rungs(feature)
        val mine = state.entries.filter { sim.owns(it.sim) }
        val on = mine.lastOrNull { it.entry == QuietPlan.entry(feature, true) }
        val off = mine.lastOrNull { it.entry == QuietPlan.entry(feature, false) }
        val result = state.runs.lastOrNull { run -> run.sim?.let(sim::owns) == true && run.features.any { it.feature == feature } }
            ?.features?.lastOrNull { it.feature == feature }
        val overrides = state.overrides.filter { it.slot == sim.slot && sim.owns(it.sim) && it.key == feature.key }
        val kept = overrides.lastOrNull { it.hold == Hold.KEPT }?.value
        val trial = overrides.any { it.hold == Hold.TRIAL }

        val onReason = on?.outcome ?: result?.onReason
        val staticReason = on?.outcome?.takeIf { it in STATIC } ?: result?.onReason?.takeIf { it in STATIC && on == null }
        val switchOn: Boolean? = kept
            ?: listOfNotNull(on, off).maxByOrNull { it.atMs }?.effective
            ?: result?.let { if (it.applied) it.best else it.baseline }
        val onWorks: Boolean? = on?.works ?: result?.let { if (it.baseline == true) it.baselineWorks else it.onWorks }
        val networkOffers = when {
            onWorks == true -> true
            onReason == NETWORK_BLOCK -> false
            switchOn == true -> true
            result?.ocd?.value == true -> true
            else -> false
        }
        val signedIn = switchOn == true && onWorks == true
        val callsTested = feature in CALL_TESTED && calls.any { it.feature == feature && (it.mccmnc == null || it.mccmnc == sim.mccmnc) }
        val reached = listOf(networkOffers, switchOn == true, signedIn, callsTested).take(rungs.size).takeWhile { it }.count()

        fun ladder(tone: Tone, text: String, spoken: String) = FeatureLadder(feature, rungs, reached, tone, text, spoken)
        fun notPossible(text: String, spoken: String) = FeatureLadder(feature, rungs, 0, Tone.NOT_POSSIBLE, text, spoken, notPossible = true)

        val worksText = when {
            rungs.size < Rung.entries.size -> "Works"
            callsTested -> "Works, calls tested"
            else -> "Works, calls not tested yet"
        }
        val worksSpoken = when {
            rungs.size < Rung.entries.size -> "signed in"
            callsTested -> "signed in, calls tested"
            else -> "signed in, calls not tested yet"
        }
        return when {
            staticReason != null -> {
                val why = if (staticReason == "device_not_capable") "modem" else "software"
                notPossible("Not possible on this phone ($why)", "not possible on this phone because of its $why")
            }
            trial -> ladder(Tone.CHANGED, "Being tested now", "being tested now")
            kept == true && onWorks == false -> ladder(Tone.PROBLEM, "Switched on by Open Carrier Data, but not signed in", "switched on by Open Carrier Data, but not signed in")
            kept == true -> ladder(Tone.CHANGED, "Switched on by Open Carrier Data", "switched on by Open Carrier Data, ${if (signedIn) worksSpoken else "not checked yet"}")
            kept == false -> ladder(Tone.CHANGED, "Switched off by Open Carrier Data", "switched off by Open Carrier Data")
            switchOn == true && onWorks == true -> ladder(Tone.WORKS, worksText, worksSpoken)
            switchOn == true && onWorks == false -> ladder(Tone.PROBLEM, "Switched on, but not signed in", "switched on, but not signed in")
            switchOn == true -> ladder(Tone.NEUTRAL, "Switched on, not checked yet", "switched on, not checked yet")
            switchOn == false -> when {
                onWorks == true -> ladder(Tone.NEUTRAL, "Off, works when switched on", "switched off, works when switched on")
                onReason == NETWORK_BLOCK -> notPossible("Not offered by your network here", "not offered by your network here")
                onReason == USER_SWITCH_BLOCK -> ladder(Tone.NEUTRAL, "Off in your call settings", "switched off in your call settings")
                on?.works == false -> notPossible("Didn't sign in when tested", "switched off, it didn't sign in when tested")
                onReason in NEEDS_WIFI -> ladder(Tone.NEUTRAL, "Off, tested when Wi-Fi is on", "switched off, tested when Wi-Fi is on")
                else -> ladder(Tone.NEUTRAL, "Off, not tested yet", if (networkOffers) "network offers it, switched off, not tested yet" else "switched off, not tested yet")
            }
            else -> ladder(Tone.NEUTRAL, "Not checked yet", "not checked yet")
        }
    }
}

/** The sentence at the top of the screen and under the Settings entry, and the colour it means. */
data class Status(val tone: Tone, val headline: String)

object StatusSentence {
    /**
     * In this order: no SIM; a stop event; a failed check; a test running; the
     * changes Open Carrier Data keeps; a feature the phone has on that is not
     * signed in; nothing checked yet; else everything works. [sims] null: not
     * readable (the SIM list is then not judged).
     */
    fun of(state: OwnerState, sims: List<SimView>?, ladders: List<FeatureLadder>, running: Boolean = false): Status {
        val kept = keptChanges(state)
        val problems = ladders.filter { it.tone == Tone.PROBLEM }.map { it.feature.label }.distinct()
        val checked = state.runs.isNotEmpty() || state.entries.isNotEmpty() || state.lastResult != null
        return when {
            sims != null && sims.isEmpty() -> Status(Tone.NEUTRAL, "No SIM card, so there's nothing to check")
            state.stop != null -> Status(Tone.PROBLEM, "A check failed — changes are paused")
            state.lastResult?.failed == true -> Status(Tone.PROBLEM, "A check failed — reverted")
            running || state.liveTrial() -> Status(Tone.CHANGED, "A test is running")
            kept.isNotEmpty() -> Status(Tone.CHANGED, improvements(kept))
            problems.isNotEmpty() -> Status(
                Tone.PROBLEM,
                "Your phone uses its own settings, but ${and(problems)} ${if (problems.size == 1) "isn't" else "aren't"} signed in",
            )
            !checked -> Status(Tone.NEUTRAL, "Not checked yet")
            else -> Status(Tone.WORKS, "Everything works with your phone's own settings")
        }
    }

    /** What Open Carrier Data keeps on the phone, one plain item each. */
    fun keptChanges(state: OwnerState): List<String> =
        state.overrides.filter { it.hold == Hold.KEPT }.map { "${OwnerFeature.ofKey(it.key)?.label ?: it.key} switched ${if (it.value) "on" else "off"}" } +
            state.apns.filter { it.hold == Hold.KEPT }.map { "mobile data entry added" }

    private fun improvements(kept: List<String>): String {
        val names = kept.distinct()
        val count = "${kept.size} ${if (kept.size == 1) "improvement" else "improvements"} active"
        return if (names.size <= 2) "$count: ${and(names)}" else count
    }

    private fun and(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}

/** The line under the status sentence: when the phone was last checked, and on which SIM. */
object CheckedLine {
    fun of(
        state: OwnerState,
        sims: List<SimView>?,
        stepsToGo: Int,
        nowMs: Long,
        zone: ZoneId,
        time: (Long) -> String,
        date: (Long) -> String,
    ): String {
        val names = sims.orEmpty().map { it.name }.distinct().let { if (it.size <= 1) it.firstOrNull() else it.dropLast(1).joinToString(", ") + " and " + it.last() }
        if (sims != null && sims.isEmpty()) return "Put a SIM card in to check its calling and data settings."
        if (stepsToGo > 0) return "Testing${names?.let { " on $it" } ?: ""}, $stepsToGo ${if (stepsToGo == 1) "step" else "steps"} to go."
        val last = listOfNotNull(state.lastCheckAtMs, state.lastResult?.atMs, state.runs.lastOrNull()?.endedMs).maxOrNull()
            ?: return "Open Carrier Data checks on its own at night. Tap Test now to check right away."
        return "Checked ${whenWord(last, nowMs, zone, time, date)}${names?.let { " on $it" } ?: ""}."
    }

    /** "today at 1:41 PM", "last night at 3:12 AM", "tonight at 3:12 AM", "yesterday at …", or the date. */
    fun whenWord(atMs: Long, nowMs: Long, zone: ZoneId, time: (Long) -> String, date: (Long) -> String): String {
        val then = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        val day = when {
            days == 0L && then.hour < NIGHT_ENDS -> if (now.hour < NIGHT_ENDS) "tonight" else "last night"
            days == 0L -> "today"
            days == 1L -> "yesterday"
            else -> date(atMs)
        }
        return "$day at ${time(atMs)}"
    }

    private const val NIGHT_ENDS = 6
}

/** The "Mobile data" line: OCD's internet entries for the SIM, against the phone's APN list. */
object MobileDataLine {
    private val ON_PHONE = setOf("present", OwnerRunner.SAME_AS_PHONE_ROW)

    /** [candidates]: how many entries OCD's pulled release has for the SIM ([QuietPlan.apnCandidates]); null when not known. */
    fun of(state: OwnerState, sim: SimView, candidates: Int? = null): String {
        val records = state.entries.filter { sim.owns(it.sim) && it.entry.startsWith(EntryRecord.APN + ":") }
        val statuses: Map<String, String> = if (records.isNotEmpty()) {
            records.associate { it.entry.substringAfter(':') to it.outcome }
        } else {
            state.runs.lastOrNull { run -> run.sim?.let(sim::owns) == true && run.apns.any { it.apnKey.isNotEmpty() } }
                ?.apns?.filter { it.apnKey.isNotEmpty() }?.associate { it.apnKey to it.status }.orEmpty()
        }
        val added = state.apns.filter { it.hold == Hold.KEPT && sim.owns(it.sim) }.map { it.apnKey }.toSet()
        val total = maxOf((statuses.keys + added).size, candidates ?: 0)
        val onPhone = statuses.count { (key, status) -> key !in added && status in ON_PHONE }
        val untested = total - onPhone - added.size
        return when {
            total == 0 && candidates == 0 -> "Open Carrier Data has no entries for this SIM."
            total == 0 -> "Not checked yet. Test now checks them too."
            added.isNotEmpty() -> buildString {
                append(entries(added.size)).append(" added by Open Carrier Data.")
                if (onPhone > 0) append(" ").append(if (onPhone == 1) "1 more is" else "$onPhone more are").append(" already on your phone.")
                if (untested > 0) append(" ").append(notTested(untested))
            }
            untested == 0 && total == 1 -> "The Open Carrier Data entry is already on your phone."
            untested == 0 -> "All $total Open Carrier Data entries are already on your phone."
            onPhone == 0 -> "${entries(total).replaceFirstChar { it.uppercase() }} from Open Carrier Data, not tested yet."
            else -> "$onPhone of $total Open Carrier Data entries are already on your phone. ${notTested(untested)}"
        }
    }

    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    private fun notTested(n: Int) = if (n == 1) "The other one hasn't been tested yet." else "The other $n haven't been tested yet."
}

/** The "Automatic checks" line. */
object ScheduleLine {
    fun of(on: Boolean, registered: Boolean): String = when {
        !on -> "Off. Open Carrier Data checks only when you tap Test now."
        !registered -> "Starting up. Checks quietly at night while charging."
        else -> "Checks quietly at night while charging. Next check: tonight."
    }
}

/** The Settings entry's summary: the screen's status sentence. */
object SettingsSummary {
    fun line(state: OwnerState, privileged: Boolean, sims: List<SimView>?, calls: List<CallsTested> = emptyList()): String {
        val ladders = sims.orEmpty().flatMap { Ladders.of(state, it, calls) }
        val headline = StatusSentence.of(state, sims, ladders).headline
        return if (privileged) headline else "$headline. No system access"
    }
}

/** A live trial on any slot: a test is under way. */
private fun OwnerState.liveTrial(): Boolean = overrides.any { it.hold == Hold.TRIAL } || apns.any { it.hold == Hold.TRIAL }
