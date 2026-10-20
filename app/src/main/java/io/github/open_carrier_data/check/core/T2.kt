package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * T2, the test round 19 defines and round 23 (LB3, LB4) applies: a measured
 * outgoing call and a measured incoming call from the person's second line,
 * both on the feature's bearer, and the person's yes on audio for both. This
 * ports OCD Lab's `t2_assess` (private tools/ocd_lab/lab_ocd.py, main
 * 0d12145b) to the app's stored call tests. The app adds two narrower rules:
 * the outgoing call must be measured as outgoing, and a watch that saw more
 * than one call counts for nothing. A T2 result stays in the app's private
 * storage; it never becomes, withholds or ranks an OCD value.
 *
 * Round 26 (automatic check 2, rule changes 2 and 8): a call counts only
 * with the bearer proof ([BearerProof]): its call type agrees with the ims
 * data connection's transport, and for LTE with the data RAT, at ACTIVE and
 * at hang-up. On PHH IMS 13 is the default call type, so the call type alone
 * never counts. A call whose readings disagree, or that ran on another bearer,
 * is "not counted, retry", never a failure. Wi-Fi calling no longer needs
 * airplane mode: the bearer proof showing IWLAN at ACTIVE and at hang-up
 * replaces it. In this read-only app the person's yes stays; there it can
 * only make a pointer, never a status (round 26 rule change 1).
 */

/** The features T2 covers, with the call network type their calls must show (lab T2_CALL_CODES). */
@Serializable
enum class T2Feature(val wire: String, val bearerCode: Int, val label: String, val bearerPlain: String) {
    VOLTE("volte", Radio.LTE, "VoLTE", "4G (LTE)"),
    VOWIFI("vowifi", Radio.IWLAN, "Wi-Fi calling", "Wi-Fi (IWLAN)");

    /** OCD's capability key. */
    val capability: String get() = wire

    /** The bearer the round 26 bearer proof must show. */
    val provenBearer: ProvenBearer get() = if (this == VOWIFI) ProvenBearer.IWLAN else ProvenBearer.LTE

    /** The IMS transport a registration for this feature is on. */
    val transport: ImsTransport get() = if (this == VOWIFI) ImsTransport.IWLAN else ImsTransport.LTE
}

/** One T2 test's calls, assessed: which counted, the first failure, and what did not count. */
data class T2Assessment(
    val feature: T2Feature,
    val outgoing: CallTestRecord?,
    val incoming: CallTestRecord?,
    /** The first failure (lab `failed`): a call that did not go active, or a "no" on audio. A bearer never fails (round 26). */
    val failed: String?,
    /** Calls that count for nothing, with the reason (lab `not_counted`). */
    val notCounted: List<String>,
) {
    /** Both calls counted and nothing failed. */
    val passed: Boolean get() = outgoing != null && incoming != null && failed == null

    /** The test is over: passed or failed. */
    val concluded: Boolean get() = passed || failed != null

    val plain: String
        get() = when {
            passed -> "T2 passed for ${feature.label}: an outgoing and an incoming call over ${feature.bearerPlain}, " +
                "call network type ${feature.bearerCode} with the bearer proven, audio confirmed."
            failed != null -> "T2 not passed for ${feature.label}: ${reason(failed)}. A new test can start again."
            else -> buildString {
                append("T2 for ${feature.label}: ")
                append(
                    listOfNotNull(
                        if (outgoing != null) "the outgoing call counted" else "no outgoing call counted yet",
                        if (incoming != null) "the incoming call counted" else "no incoming call counted yet",
                    ).joinToString(", "),
                )
                append('.')
                if (notCounted.isNotEmpty()) {
                    append(" Not counted: ").append(notCounted.distinct().joinToString("; ") { reason(it) }).append('.')
                }
            }
        }

    private fun reason(code: String): String {
        val call = if (code.startsWith("mt_")) "the incoming call" else "the outgoing call"
        return when {
            code.endsWith("_call_did_not_connect") -> "$call did not go active"
            code.endsWith("_bearer_not_proven") ->
                "$call's bearer was not proven (the call type, the IMS data connection and the data network did not agree at ACTIVE and at hang-up); try again"
            "_other_bearer_" in code -> {
                val seen = code.substringAfter("_other_bearer_")
                val types = if (seen == "none") "no call network type" else "call network type ${seen.replace('_', ' ')}"
                "$call ran with $types, not ${feature.bearerCode} (${feature.bearerPlain}); try again on ${feature.bearerPlain}"
            }
            code.endsWith("_audio_no") -> "you said the audio of $call did not work"
            code.endsWith("_no_audio_answer") -> "$call had no yes on audio"
            code.endsWith("_several_calls") -> "$call's watch saw more than one call"
            code == "mo_not_outgoing" -> "the watch did not see an outgoing call"
            code == "mt_not_incoming" -> "the phone did not ring, so the watch did not see an incoming call"
            code == "mt_from_mailbox" -> "the incoming number was a mailbox short code; a mailbox call never counts as the incoming call"
            code == "mt_not_confirmed_second_line" -> "the incoming call was not confirmed as coming from your second line"
            else -> code
        }
    }
}

object T2 {
    /**
     * Lab `t2_assess` for one feature over the calls of one T2 test.
     *
     * - The outgoing call counts when it went active, the bearer proof
     *   passed for the feature's bearer (LTE for VoLTE, IWLAN for Wi-Fi
     *   calling), every call network type read during it is the feature's
     *   code, and the person said yes on audio.
     * - The incoming call counts on the same terms, and only when the phone
     *   rang, the incoming number was not a mailbox short code, and the
     *   person confirmed it came from their second line. A mailbox call
     *   proves downlink audio only and never counts as the incoming call.
     * - A call that did not go active, or a "no" on audio, fails the feature.
     *   A bearer that is not proven, or proven to be another one, does not
     *   count and asks for a retry (round 26).
     */
    fun assess(feature: T2Feature, calls: List<CallTestRecord>): T2Assessment {
        val code = feature.bearerCode
        var outgoing: CallTestRecord? = null
        var incoming: CallTestRecord? = null
        var failed: String? = null
        val notCounted = mutableListOf<String>()
        for (call in calls) {
            val direction = if (call.intended == CallDirection.INCOMING) "mt" else "mo"
            val watch = call.watch
            // App: a watch that saw several calls cannot say which call its readings belong to.
            if (watch.callsSeen > 1) {
                notCounted += "${direction}_several_calls"
                continue
            }
            val codes = watch.callNetworkTypes.toSet()
            if (!watch.wentActive) {
                failed = failed ?: "${direction}_call_did_not_connect"
                continue
            }
            // Round 26: the call type alone never counts; readings that disagree, or another bearer, ask for a retry.
            if (codes.isNotEmpty() && codes != setOf(code)) {
                notCounted += "${direction}_other_bearer_${codes.sorted().joinToString("_")}"
                continue
            }
            val proof = watch.bearerProof
            if (proof == null || !proof.counted) {
                notCounted += "${direction}_bearer_not_proven"
                continue
            }
            if (proof.bearer != feature.provenBearer) {
                notCounted += "${direction}_other_bearer_${proof.bearer?.callNetworkType ?: "none"}"
                continue
            }
            if (call.heard == AudioAnswer.NO) {
                failed = failed ?: "${direction}_audio_no"
                continue
            }
            if (call.heard != AudioAnswer.YES) {
                notCounted += "${direction}_no_audio_answer"
                continue
            }
            if (direction == "mo") {
                if (watch.direction != CallDirection.OUTGOING) {
                    notCounted += "mo_not_outgoing"
                } else {
                    outgoing = outgoing ?: call
                }
            } else {
                when {
                    !watch.ringingSeen -> notCounted += "mt_not_incoming"
                    watch.incomingIsMailbox == true -> notCounted += "mt_from_mailbox"
                    call.secondLine != true -> notCounted += "mt_not_confirmed_second_line"
                    else -> incoming = incoming ?: call
                }
            }
        }
        return T2Assessment(feature, outgoing, incoming, failed, notCounted)
    }
}

/**
 * A feature that passed T2 on this phone, kept in the app's private storage.
 * The phone and SIM fields are round 19's cell key (device, carrier profile,
 * software stack). Only passes are kept; a failed test shows on screen and
 * its calls stay in the call list.
 */
@Serializable
data class T2Record(
    val date: String,
    val model: String,
    val codename: String,
    val romFamily: String,
    val androidMajor: Int,
    val imsStack: String,
    val homeMccMnc: String?,
    val spn: String?,
    val ocdProfile: String?,
    val feature: T2Feature,
    val bearerCode: Int,
    val session: Int,
    /** user_toggle when the person turned the feature on in the guided switch step before the test (round 23 AP3). */
    val configOrigin: ConfigOrigin,
) {
    /** Round 23 LB4: "works" only with T2. */
    val plain: String
        get() = "works on this phone: T2 passed (an outgoing and an incoming call over ${feature.bearerPlain}, " +
            "call network type $bearerCode, audio confirmed" +
            (if (configOrigin == ConfigOrigin.USER_TOGGLE) ", after you turned it on yourself" else ", with the settings as found") +
            ", $date)"

    /** The same phone, software and SIM as [readings]. */
    fun sameCell(readings: PhoneReadings): Boolean {
        val device = readings.device
        val sim = readings.sim ?: return false
        return model == device.model && codename == device.codename && romFamily == device.romFamily.wire &&
            androidMajor == device.androidMajor && homeMccMnc == sim.homeMccMnc && spn == sim.spn
    }
}

/** What the app may say about one feature on this phone (round 23 LB4, round 18's wording). */
object T2Status {
    fun line(feature: T2Feature, log: CallTestLog, readings: PhoneReadings): String {
        log.t2.lastOrNull { it.feature == feature && it.sameCell(readings) }?.let { return it.plain }
        val ims = readings.deep?.ims ?: return "registration not read (needs the deep check); no T2 on this phone and SIM yet"
        val registered = ims.registered == true && ims.hasCalls && ims.transport == feature.transport
        return if (registered) {
            "registered over ${feature.bearerPlain} with these settings; no T2 on this phone and SIM yet, so this is not a call test"
        } else {
            "not registered over ${feature.bearerPlain} now; no T2 on this phone and SIM yet"
        }
    }
}
