package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.Capabilities
import io.github.open_carrier_data.check.core.IwlanReading
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.core.TwoAnswers
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * The owner screen and the Settings entry in plain words (the redesign of
 * 2026-10-20 and its review): the headline at the top, one evidence ladder
 * per feature, and the lines for mobile data and the automatic tests. Pure
 * functions over the state the owner build keeps; the technical record stays
 * in the state, the report and the log, and nothing here changes the phone.
 *
 * Vocabulary: "check" is read-only, "test" changes a setting for a while,
 * "changes" are what Open Carrier Data keeps, and the way back is disabling
 * the app in Android's Settings (round 31; Undo and safe mode are gone). Mobile
 * data uses Android's own "access point names".
 */

/** One active SIM as the screen names it: the name Settings shows, and what ties it to the state's records. */
data class SimView(val slot: Int, val subId: Int, val mccmnc: String, val name: String) {
    /** The state's records for this SIM. The carrier ID is left out: the subscription and network code name the card. */
    fun owns(key: SimKey): Boolean = key.subId == subId && key.mccmnc == mccmnc
}

/**
 * How far this phone's own measured calls went for a feature on a SIM's
 * network (round 26): calls out (L2) or works, calls both ways (L3). From the
 * owner build's call ledger ([CallLevels.level]), never from a person's yes.
 */
data class CallsTested(val feature: OwnerFeature, val mccmnc: String?, val level: CallsLevel = CallsLevel.WORKS)

/** The rungs of the evidence ladder, in order: each needs the one before it (round 26: "signed in -> calls out -> works"). */
enum class Rung(val plain: String) {
    NETWORK_OFFERS("Network offers it"),
    SWITCHED_ON("Switched on"),
    SIGNED_IN("Signed in"),
    CALLS_OUT("Calls out"),
    CALLS_BOTH_WAYS("Calls both ways"),
}

/** What a status colour means. The screen gives each of the first four one fixed colour; neutral takes the theme's. */
enum class Tone { WORKS, CHANGED, PROBLEM, NOT_POSSIBLE, NEUTRAL }

/**
 * One feature's row: how far the evidence goes ([reached] of [rungs]), the
 * colour it takes and the status in plain words. A feature that cannot work
 * now shows a single mark instead of a ladder ([notPossible]); one that never
 * works on this phone ([unsupported]: the modem, the IMS stack) leaves the
 * list for one footer line. [kept]: the value Open Carrier Data keeps, if any.
 */
data class FeatureLadder(
    val feature: OwnerFeature,
    val rungs: List<Rung>,
    val reached: Int,
    val tone: Tone,
    val text: String,
    val notPossible: Boolean = false,
    val unsupported: Boolean = false,
    val kept: Boolean? = null,
    /** The effective switch; null when not known yet. */
    val switchedOn: Boolean? = null,
    /** Off on the phone, but it signed in when tested on. */
    val wouldWork: Boolean = false,
) {
    /** The row for TalkBack, for example "VoLTE: signed in, calls untested, step 3 of 4". */
    val description: String get() = "${feature.label}: ${text.replaceFirstChar { it.lowercase() }}, step $reached of ${rungs.size}"
}

object Ladders {
    /** The order of "Calls and messages". */
    val ORDER = listOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI, OwnerFeature.SMS_OVER_IMS, OwnerFeature.VIDEO_CALLING, OwnerFeature.VONR)

    /** The features whose calls the phone measures (VoLTE over LTE, Wi-Fi calling over Wi-Fi); only they have the call rungs. */
    val CALL_TESTED = setOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI)

    /** Results that no later test changes: the S20's modem (VoNR), video on PHH IMS. */
    private val STATIC = setOf("device_not_capable", OwnerRunner.NOT_AVAILABLE, OwnerRunner.IMS_STACK_NO_VIDEO)
    private const val NETWORK_BLOCK = "blocked_at_network_offers"
    private const val USER_SWITCH_BLOCK = "blocked_at_user_switch"
    private val NEEDS_WIFI = setOf("wifi_not_connected", "blocked_at_wifi_connected")

    fun rungs(feature: OwnerFeature): List<Rung> = if (feature in CALL_TESTED) Rung.entries else Rung.entries.take(Rung.SIGNED_IN.ordinal + 1)

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
     * - Calls out: a measured outgoing call on the feature's bearer passed
     *   (the app's mailbox call or a real call of the owner's, round 26 L2).
     * - Calls both ways: also a measured incoming call on that bearer (L3).
     *   "Calls out" never claims anything about incoming calls.
     */
    fun of(feature: OwnerFeature, state: OwnerState, sim: SimView, calls: List<CallsTested> = emptyList()): FeatureLadder {
        val rungs = rungs(feature)
        val mine = state.entries.filter { sim.owns(it.sim) }
        val on = mine.lastOrNull { it.entry == QuietPlan.entry(feature, true) }
        val off = mine.lastOrNull { it.entry == QuietPlan.entry(feature, false) }
        val result = state.runs.lastOrNull { run -> run.sim?.let(sim::owns) == true && run.features.any { it.feature == feature } }
            ?.features?.lastOrNull { it.feature == feature }
        val overrides = state.overrides.filter { it.slot == sim.slot && sim.owns(it.sim) && it.key == feature.key }
        // A value kept as an override (builds before round 30; round 30 keeps none).
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
        val levels = if (feature in CALL_TESTED) calls.filter { it.feature == feature && (it.mccmnc == null || it.mccmnc == sim.mccmnc) }.map { it.level } else emptyList()
        val callsBoth = CallsLevel.WORKS in levels
        val callsOut = callsBoth || CallsLevel.CALLS_OUT in levels
        val reached = listOf(networkOffers, switchOn == true, signedIn, callsOut, callsBoth).take(rungs.size).takeWhile { it }.count()
        val provisional = state.provisional.firstOrNull { it.slot == sim.slot && sim.owns(it.sim) && it.feature == feature }

        fun ladder(tone: Tone, text: String, wouldWork: Boolean = false) =
            FeatureLadder(feature, rungs, reached, tone, text, kept = kept, switchedOn = switchOn, wouldWork = wouldWork)
        fun notPossible(text: String) = FeatureLadder(feature, rungs, 0, Tone.NOT_POSSIBLE, text, notPossible = true, kept = kept, switchedOn = switchOn)

        val worksText = when {
            rungs.size < Rung.entries.size -> "Signed in"
            callsBoth && reached >= rungs.size -> "Works: calls both ways"
            callsOut && reached >= Rung.CALLS_OUT.ordinal + 1 -> "Calls out work"
            else -> "Signed in, calls untested"
        }
        // A changed voice value kept for now: calls out first, then incoming (round 26 check 6).
        val keptText = when {
            provisional == null -> "Switched on by Open Carrier Data"
            provisional.callsOutAtMs == null -> "Switched on by Open Carrier Data, calls out not checked yet"
            else -> "Switched on by Open Carrier Data, calls out work, incoming not yet seen"
        }
        return when {
            // The reason (modem or software) goes to the report and the log only.
            staticReason != null -> FeatureLadder(feature, rungs, 0, Tone.NOT_POSSIBLE, "Not supported on this phone", notPossible = true, unsupported = true)
            trial -> ladder(Tone.CHANGED, "Testing now")
            kept == true && onWorks == false -> ladder(Tone.PROBLEM, "Switched on by Open Carrier Data, not signed in")
            kept == true && provisional == null && callsBoth && reached >= rungs.size -> ladder(Tone.CHANGED, "Switched on by Open Carrier Data, calls both ways")
            kept == true -> ladder(Tone.CHANGED, keptText)
            kept == false -> ladder(Tone.CHANGED, "Switched off by Open Carrier Data")
            switchOn == true && onWorks == true -> ladder(Tone.WORKS, worksText)
            switchOn == true && onWorks == false -> ladder(Tone.PROBLEM, "On, but not signed in")
            switchOn == true -> ladder(Tone.NEUTRAL, "On, not checked yet")
            switchOn == false -> when {
                onWorks == true -> ladder(Tone.NEUTRAL, "Off, works when switched on", wouldWork = true)
                onReason == NETWORK_BLOCK -> notPossible("Not offered by your network")
                onReason == USER_SWITCH_BLOCK -> ladder(Tone.NEUTRAL, "Off in your call settings")
                on?.works == false -> notPossible("Didn't sign in when tested")
                onReason in NEEDS_WIFI -> ladder(Tone.NEUTRAL, "Off, test needs Wi-Fi")
                else -> ladder(Tone.NEUTRAL, "Off, not tested yet")
            }
            else -> ladder(Tone.NEUTRAL, "Not checked yet")
        }
    }

    /**
     * The muted line after the rows for the features that never work on this
     * phone, for example "Video calling and VoNR aren't supported on this
     * phone"; null when there are none.
     */
    fun unsupportedLine(ladders: List<FeatureLadder>): String? {
        val names = ladders.filter { it.unsupported }.map { it.feature.label }.distinct()
        if (names.isEmpty()) return null
        return "${joinAnd(names)} ${if (names.size == 1) "isn't" else "aren't"} supported on this phone"
    }
}

/** Which of the headlines applies; the line under it and the buttons follow from it. */
enum class StatusKind { NO_SIM, PAUSED, FAILED, RUNNING, KEPT_BROKEN, KEPT, OWN_NOT_SIGNED_IN, NOT_CHECKED, WORKS, SIGNED_IN }

/** The headline at the top of the screen and under the Settings entry, and the colour it means. */
data class Status(val tone: Tone, val headline: String, val kind: StatusKind)

object StatusSentence {
    /**
     * In this order: no SIM; paused (a stop event); a failed test; a test
     * running; a change Open Carrier Data keeps that isn't signed in; the
     * changes it keeps; a feature the phone has on that isn't signed in;
     * nothing checked yet; else what is signed in ([signedIn]). [sims]
     * null: not readable (the SIM list is then not judged). [testing]: the
     * SIM a running test is on, named only when 2 SIMs are active.
     */
    fun of(state: OwnerState, sims: List<SimView>?, ladders: List<FeatureLadder>, running: Boolean = false, testing: SimView? = null): Status {
        val problems = ladders.filter { it.tone == Tone.PROBLEM }
        val broken = problems.filter { it.kept == true }.map { it.feature.label }.distinct()
        val own = problems.filter { it.kept == null }.map { it.feature.label }.distinct()
        val checked = state.runs.isNotEmpty() || state.entries.isNotEmpty() || state.lastResult != null
        val keptAny = state.heldSwitches().isNotEmpty() || state.apns.any { it.hold == Hold.KEPT }
        return when {
            sims != null && sims.isEmpty() -> Status(Tone.NEUTRAL, "No SIM card", StatusKind.NO_SIM)
            state.stop != null -> Status(Tone.PROBLEM, "Changes paused: a setting didn't go back", StatusKind.PAUSED)
            state.lastResult?.failed == true -> Status(Tone.PROBLEM, "A test failed — your own settings are back", StatusKind.FAILED)
            running || state.liveTrial() -> {
                val name = testing?.name?.takeIf { sims.orEmpty().size > 1 }
                Status(Tone.CHANGED, if (name != null) "Testing $name" else "Testing", StatusKind.RUNNING)
            }
            broken.isNotEmpty() -> Status(
                Tone.PROBLEM,
                "Open Carrier Data switched on ${joinAnd(broken)}, but ${if (broken.size == 1) "it isn't" else "they aren't"} signed in",
                StatusKind.KEPT_BROKEN,
            )
            keptAny -> Status(Tone.CHANGED, changed(state), StatusKind.KEPT)
            own.isNotEmpty() -> Status(Tone.PROBLEM, "${joinAnd(own)} ${if (own.size == 1) "isn't" else "aren't"} signed in", StatusKind.OWN_NOT_SIGNED_IN)
            !checked -> Status(Tone.NEUTRAL, "Not checked yet", StatusKind.NOT_CHECKED)
            // "Works" needs calls both ways measured (round 26 L3); registration alone reads "signed in".
            ladders.filter { !it.notPossible && it.reached > 0 }.let { live ->
                live.isNotEmpty() && live.all { it.reached >= it.rungs.size }
            } -> Status(Tone.WORKS, "All working on your phone's own settings", StatusKind.WORKS)
            else -> signedIn(ladders)
        }
    }

    /**
     * The last headline, true for every feature with a result: "All signed
     * in on your phone's own settings" only when each of them is on and
     * signed in. A feature that is off but signed in when tested on is named
     * ("Signed in on your phone's own settings; VoLTE is off and would
     * work"); one that is off or on without a result drops the "All".
     */
    private fun signedIn(ladders: List<FeatureLadder>): Status {
        val shown = ladders.filter { !it.notPossible }
        val signed = shown.filter { it.reached >= SIGNED_IN_RUNGS }
        val gaps = shown.filter { it.switchedOn != null && it.reached < SIGNED_IN_RUNGS }
        if (gaps.isEmpty()) return Status(Tone.WORKS, "All signed in on your phone's own settings", StatusKind.SIGNED_IN)
        val would = gaps.filter { it.wouldWork }.map { it.feature.label }.distinct()
        val off = would.takeIf { it.isNotEmpty() }?.let { "${joinAnd(it)} ${if (it.size == 1) "is" else "are"} off and would work" }
        return when {
            signed.isNotEmpty() -> Status(Tone.WORKS, listOfNotNull("Signed in on your phone's own settings", off).joinToString("; "), StatusKind.SIGNED_IN)
            off != null -> Status(Tone.NEUTRAL, off.replaceFirstChar { it.uppercase() }, StatusKind.SIGNED_IN)
            else -> Status(Tone.NEUTRAL, "Nothing signed in yet", StatusKind.SIGNED_IN)
        }
    }

    /** The ladder's rungs up to "Signed in". */
    private val SIGNED_IN_RUNGS = Rung.SIGNED_IN.ordinal + 1

    /**
     * "Open Carrier Data switched on VoLTE and Wi-Fi calling", "…switched on
     * VoLTE and added an access point name", or with 3 or more items
     * "Open Carrier Data changed 3 settings". The access point names count as
     * one item.
     */
    private fun changed(state: OwnerState): String {
        val kept = state.heldSwitches()
        val on = kept.filter { it.value }.map { label(it.key) }.distinct()
        val off = kept.filter { !it.value }.map { label(it.key) }.distinct()
        val apns = state.apns.filter { it.hold == Hold.KEPT }.map { it.sim to it.apnKey }.distinct().size
        val items = on.size + off.size + if (apns > 0) 1 else 0
        if (items >= 3) return "Open Carrier Data changed $items settings"
        val parts = listOfNotNull(
            on.takeIf { it.isNotEmpty() }?.let { "switched on ${joinAnd(it)}" },
            off.takeIf { it.isNotEmpty() }?.let { "switched off ${joinAnd(it)}" },
            apns.takeIf { it > 0 }?.let { "added ${accessPointNames(it, article = true)}" },
        )
        return "Open Carrier Data ${joinAnd(parts)}"
    }

    /**
     * What Open Carrier Data keeps on the phone, one plain item each, for the
     * notification: "VoLTE switched on", "2 access point names added".
     */
    fun keptChanges(state: OwnerState): List<String> {
        val features = state.heldSwitches().map { "${label(it.key)} switched ${if (it.value) "on" else "off"}" }.distinct()
        val apns = state.apns.filter { it.hold == Hold.KEPT }.map { it.sim to it.apnKey }.distinct().size
        return features + listOfNotNull(apns.takeIf { it > 0 }?.let { "${accessPointNames(it)} added" })
    }

    private fun label(key: String): String = OwnerFeature.ofKey(key)?.label ?: key
}

/** "1 access point name" (or "an access point name"), "2 access point names". */
internal fun accessPointNames(n: Int, article: Boolean = false): String = when {
    n == 1 && article -> "an access point name"
    n == 1 -> "1 access point name"
    else -> "$n access point names"
}

/** "A", "A and B", "A, B and C". */
internal fun joinAnd(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

/** The line under the headline: what to do next, or when the phone was last checked. Null: no line. */
object CheckedLine {
    fun of(
        status: Status,
        state: OwnerState,
        sims: List<SimView>?,
        stepsToGo: Int,
        nowMs: Long,
        zone: ZoneId,
        time: (Long) -> String,
        date: (Long) -> String,
        scheduleOn: Boolean,
        canTest: Boolean,
    ): String? {
        val last = listOfNotNull(state.lastCheckAtMs, state.lastResult?.atMs, state.runs.lastOrNull()?.endedMs).maxOrNull()
        val checked = last?.let { "Checked ${whenWord(it, nowMs, zone, time, date)}" }
        return when {
            status.kind == StatusKind.NO_SIM -> "Insert a SIM card to check it"
            stepsToGo > 0 -> "$stepsToGo ${if (stepsToGo == 1) "step" else "steps"} to go"
            status.kind == StatusKind.NOT_CHECKED -> when {
                !canTest -> null
                scheduleOn -> "Tap Test now, or wait until tonight"
                else -> "Tap Test now to start"
            }
            status.kind == StatusKind.FAILED -> if (scheduleOn) "Nothing to do. It tries again tonight" else "Nothing to do"
            status.kind == StatusKind.KEPT_BROKEN -> "Disable this app in Android's Settings to go back to the ROM's own data"
            status.kind == StatusKind.OWN_NOT_SIGNED_IN && canTest && checked != null -> "$checked. Tap Test now to look for a fix"
            checked == null -> null
            sims.orEmpty().size > 1 -> "$checked on ${joinAnd(sims.orEmpty().map { it.name }.distinct())}"
            else -> checked
        }
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

/** The coloured notes under the headline line, each with its tone. */
object HeaderNotes {
    /** [canWrite], [ownerPhone]: the access gate; null while it is read. */
    fun of(state: OwnerState, canWrite: Boolean?, ownerPhone: Boolean?): List<Pair<Tone, String>> = buildList {
        // The boot pass does not revert a leftover while paused (every write path checks the stop flag), so a restart alone does not clear it.
        if (state.stop != null) add(Tone.PROBLEM to "See the log in Details before resuming")
        if (canWrite == false) {
            add(Tone.NEUTRAL to if (ownerPhone == false) "This phone can be checked, not changed" else "Tests can't run: no system access. See Details")
        }
        if (!state.rebootCleared) add(Tone.NEUTRAL to "Changes start after the restart check in Details")
    }
}

/** The "Mobile data" line: OCD's access point names for the SIM, against the phone's list. */
object MobileDataLine {
    private val ON_PHONE = setOf("present", OwnerRunner.SAME_AS_PHONE_ROW)

    /** [candidates]: how many OCD's pulled release has for the SIM ([QuietPlan.apnCandidates]); null when not known. */
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
            total == 0 && candidates == 0 -> "No access point names for this SIM"
            total == 0 -> "Not checked yet"
            added.isNotEmpty() -> buildString {
                append("Open Carrier Data added ").append(accessPointNames(added.size, article = true))
                if (onPhone > 0) append(". ").append(onPhone).append(if (onPhone == 1) " was" else " were").append(" already on your phone")
                if (untested > 0) append(", ").append(untested).append(" not tested yet")
            }
            untested == 0 && total == 1 -> "The access point name is on your phone"
            untested == 0 -> "All $total access point names are on your phone"
            onPhone == 0 -> "${accessPointNames(total)}, not tested yet"
            else -> "$onPhone of $total access point names are on your phone, $untested not tested yet"
        }
    }
}

/** The summary of "Remove leftovers": the last cleanup pass, from the log ("None found today", "Removed 1 today"). */
object LeftoversLine {
    private val TRIALS = Regex("(\\d+) leftover trial")

    fun of(log: List<LogEvent>, nowMs: Long, zone: ZoneId, date: (Long) -> String): String {
        val last = log.lastOrNull { it.kind == "cleanup" } ?: return "Not run yet"
        val day = CheckedLine.whenWord(last.atMs, nowMs, zone, { "" }, date).substringBefore(" at ").let { if (it == "tonight" || it == "last night") "today" else it }
        val text = last.text
        val removed = TRIALS.findAll(text).sumOf { it.groupValues[1].toInt() } + Regex("APN trial [^;]*: removed").findAll(text).count()
        return when {
            "refused" in text || "waits" in text || "FAILED" in text -> "Not finished $day. See the log"
            removed > 0 -> "Removed $removed $day"
            else -> "None found $day"
        }
    }
}

/** The summary of the "Test automatically" switch. */
object ScheduleLine {
    fun of(on: Boolean): String = if (on) "At night while charging; keeps what works better" else "Only when you tap Test now"
}

/** The Settings entry's summary: the screen's headline, built with the call tests as on the screen. */
object SettingsSummary {
    fun line(state: OwnerState, privileged: Boolean, sims: List<SimView>?, calls: List<CallsTested> = emptyList()): String {
        val ladders = sims.orEmpty().flatMap { Ladders.of(state, it, calls) }
        val headline = StatusSentence.of(state, sims, ladders).headline
        return if (privileged) headline else "$headline. Can't change settings"
    }
}

/**
 * OCD's rule round 45, item 4, in Details: a SIM's most specific profile in the data the owner build
 * uses ([heading]), its capability values as what the phone is set up to try ([note]), and under a value
 * what the brand states and why it is not one value. The words are the check screen's ([TwoAnswers]);
 * none says a feature works or is booked.
 */
data class ProfileAnswers(val heading: String, val rows: List<Row>) {
    /** One capability: its label and value as the check screen's field shows them, then the lines under it. */
    data class Row(val label: String, val value: String, val lines: List<String>)

    val note: String get() = TwoAnswers.CONFIGURE_NOTE

    /** Every text of the block, in screen order. */
    val texts: List<String> get() = listOf(heading, note) + rows.flatMap { listOf(it.label, it.value) + it.lines }

    companion object {
        /** The block of the SIM [identity] in [bundle]; null when no profile matches. [name]: the SIM's name, given only with two SIMs. */
        fun of(bundle: OcdBundle, identity: SimIdentity, name: String? = null): ProfileAnswers? {
            val resolution = Matcher.resolve(bundle, identity)
            val most = resolution.mostSpecific ?: return null
            val rows = Capabilities.ordered(bundle.capabilityKeys).map { key ->
                Row(Capabilities.label(key), resolution.answer(key).wire, TwoAnswers.lines(most, key))
            }
            val heading = TwoAnswers.configureHeading(most.displayName)
            return ProfileAnswers(name?.let { "$it: $heading" } ?: heading, rows)
        }
    }
}

/** A live trial on any slot: a test is under way. */
private fun OwnerState.liveTrial(): Boolean = overrides.any { it.hold == Hold.TRIAL } || apns.any { it.hold == Hold.TRIAL }

/**
 * "Show Wi-Fi calling details" in plain words (the review of 2026-10-20: not
 * "tunnel up" or "last refusal none"): whether Wi-Fi calling is connected,
 * the last connection attempt that failed and why, and a wait Android holds
 * it back for. [name]: the SIM's name, given only with two SIMs.
 */
object WifiCallingDetails {
    private val CAUSES = mapOf(
        "IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED" to "the Wi-Fi calling server wasn't found",
        "IWLAN_DNS_RESOLUTION_NAME_FAILURE" to "the Wi-Fi calling server's name wasn't found",
        "IWLAN_DNS_RESOLUTION_TIMEOUT" to "looking up the Wi-Fi calling server took too long",
        "IWLAN_IKE_PROTOCOL_EXCEPTION" to "the network refused the secure connection",
        "IWLAN_IKE_NETWORK_LOST_EXCEPTION" to "the Wi-Fi connection dropped while connecting",
        "IWLAN_IKE_INTERNAL_IO_EXCEPTION" to "the secure connection couldn't be set up",
        "IWLAN_NETWORK_FAILURE" to "a network error",
        "IWLAN_TUNNEL_NOT_FOUND" to "the connection was gone",
        "IWLAN_SIM_NOT_READY_EXCEPTION" to "the SIM wasn't ready",
        "IWLAN_IKE_DPD_TIMEOUT" to "the connection stopped answering",
    )

    fun lines(reading: IwlanReading?, name: String? = null): List<String> {
        val prefix = name?.let { "$it: " }.orEmpty()
        val connection = when (reading?.tunnelUp) {
            true -> "Wi-Fi calling is connected"
            false -> "Wi-Fi calling isn't connected"
            null -> "No Wi-Fi calling connection seen"
        }
        val attempt = reading?.lastRefusal?.let { "Last failed attempt: ${cause(it)}" } ?: "No failed attempts"
        val wait = reading?.heldBackBy?.let { by ->
            val minutes = reading.heldBackMinutes?.let { " for about $it more minute${if (it == 1L) "" else "s"}" }.orEmpty()
            "Held back$minutes by ${by.plain}"
        }
        return listOfNotNull(connection, attempt, wait).map { prefix + it }
    }

    /** A known cause in words; another one as the words of its code ("IWLAN_SOME_ERROR" is "some error"). */
    fun cause(code: String): String = CAUSES[code] ?: code.removePrefix("IWLAN_").lowercase().replace('_', ' ')
}
