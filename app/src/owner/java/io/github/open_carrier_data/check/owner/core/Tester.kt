package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.Mailbox
import kotlinx.serialization.Serializable

/*
 * OCD round 31 ("scope", SM7 to SM12): the tester runs in two builds.
 *
 * - The owner build (build type `owner`): the owner's own phones only. Its token sends settled
 *   results and measured records to main (SM11), and its tests run under the owner's own switches.
 * - The ROM build (build type `rom`): the build another ROM ships in its own image, signed with that
 *   ROM's key (SM5). It pulls, hands the dataset to CarrierSettings and runs the passive checks
 *   (IMS registration, data validation, the no-regression check) without asking. The active tests
 *   (dialled test calls, test SMS, trials that change carrier settings, the emergency data test and,
 *   since round 42, the mobile-data probe ladder and the Wi-Fi-calling name lookup) run only after
 *   the person turns them on (SM9, off by default), and calls and SMS go only where OCD's allowlist
 *   marks them free for that carrier. Nothing leaves the phone by itself: no automatic report, no
 *   token, and results stay on the phone as local records (SM11).
 *
 * In both builds, active verdicts count only on an IMS stack that one calls-out pass has calibrated
 * (SM8); today that is AOSP ImsStack. On any other stack results read "not counted": no candidates,
 * no records, only passive evidence.
 */

/** Which build this is ([io.github.open_carrier_data.check.BuildConfig.TESTER]). */
enum class TesterBuild(val wire: String) {
    OWNER("owner"),
    ROM("rom");

    companion object {
        /** An unknown name is the ROM build, the stricter of the two. */
        fun of(wire: String?): TesterBuild = entries.firstOrNull { it.wire == wire } ?: ROM
    }
}

/** A tester declaration sent to CarrierSettings (R31.5): on or off, and when. */
@Serializable
data class TesterDeclaration(val on: Boolean, val atMs: Long)

/** The person's opt-in for the active tests on a ROM build (SM9). Off by default; the owner build does not use it. */
@Serializable
data class ActiveTestsOptIn(val on: Boolean = false, val atMs: Long? = null)

/** What the tests may do in this build, for this person (SM8 to SM11). Pure. */
data class TesterPolicy(val build: TesterBuild, val optIn: Boolean = false) {
    val owner: Boolean get() = build == TesterBuild.OWNER

    /** Dialled test calls, test SMS, override trials, the emergency data test and the probe ladder with W1 (SM9). */
    val activeTests: Boolean get() = owner || optIn

    /** Settled results and measured records go to main with the owner's token: the owner build only (SM11). */
    val sendsWithToken: Boolean get() = owner

    /** Why a dialled test call to [mailbox] may not go out in this build, or null (SM9). */
    fun callRefusal(mailbox: Mailbox?): String? = when {
        !activeTests -> OPT_IN_OFF
        !owner && mailbox?.freeCalls != true -> NOT_FREE
        else -> null
    }

    /** Why a test SMS may not go out in this build for a SIM whose allowlist entry is [entry], or null (SM9). */
    fun smsRefusal(entry: Mailbox?): String? = when {
        !activeTests -> OPT_IN_OFF
        !owner && entry?.freeSms != true -> NOT_FREE
        else -> null
    }

    /**
     * Why the emergency data test may not run, or null (SM9 and SM10). [buildSwitch]: the ROM
     * builder's switch, which CarrierSettings reports from the build after the device family's
     * first use passed (null when this ROM build does not say). The owner build also runs it
     * after the owner's own recorded first use, which the app re-sends with every hand-off.
     */
    fun emergencyRefusal(buildSwitch: Boolean?, ownerFirstUsePassed: Boolean): String? = when {
        !activeTests -> OPT_IN_OFF
        owner -> if (buildSwitch == true || ownerFirstUsePassed) null else "the first use of the emergency data test has not passed on this phone"
        buildSwitch != true -> "the ROM builder has not turned the emergency data test on in this build"
        else -> null
    }

    companion object {
        const val OPT_IN_OFF = "active_tests_off"
        const val NOT_FREE = "not_marked_free"
    }
}

/** SM8: the IMS stacks whose counters one calls-out pass has confirmed against the stack's own logs. */
object StackCalibration {
    /** Today AOSP ImsStack (the owner's S20, 2026-10-22 onwards). */
    val CALIBRATED = setOf("aosp_imsstack")

    fun counted(stackId: String?): Boolean = stackId in CALIBRATED

    /** The plain reason a result is not counted on [stackId], or null when it counts. */
    fun notCounted(stackId: String?): String? =
        if (counted(stackId)) null else "not counted: the tests are not calibrated on the IMS stack ${stackId?.ifEmpty { null } ?: "this phone runs"}; only passive evidence counts"
}

/** The opt-in's words (SM9): what the app does, in plain words, before the person turns it on. */
object OptInWords {
    const val TITLE = "Test calls on this phone"
    const val OFF = "Off: Open Carrier Data only checks what the phone shows by itself. It makes no calls, sends no SMS, sends no test requests and changes no carrier setting to test it"
    const val ON = "On: Open Carrier Data tests calls on this phone, only where its list marks the call and the SMS free for your carrier"

    /** What the person agrees to, shown before the switch goes on. */
    fun explain(emergencyDataTest: Boolean): String = buildString {
        append("When this is on, Open Carrier Data:\n")
        append("- calls your SIM's own mailbox, muted, at most ${TestCaps.CALLS_PER_DAY} times a day;\n")
        append("- sends SMS to your SIM's own number;\n")
        append("- changes carrier settings for a short while to test them, and puts them back;\n")
        append("- when mobile data or Wi-Fi calling fails, or when you ask for a check, sends a few kilobytes of test requests over mobile data and Wi-Fi to find out why, by itself at most once every 6 hours;\n")
        if (emergencyDataTest) append("- briefly opens the emergency data connection, without making a call.\n")
        append("The calls and SMS go only where Open Carrier Data's list marks the mailbox call and the SMS as free for your carrier. None of it happens during a call or while roaming, and the test requests wait while mobile data is switched off. ")
        append("It never calls an emergency number. Results stay on this phone; apart from those test requests, nothing is sent anywhere by itself. You can turn it off at any time.")
    }
}
