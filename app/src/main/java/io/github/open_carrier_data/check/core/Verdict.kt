package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, item 7: the verdict model. It keeps two reads apart and
 * combines them by a fixed table:
 *   - a refusal class (from a modem/IMS code when the build is privileged, or
 *     from user-plane behaviour when not), and
 *   - an account-state read (a balance, a carrier-SMS class, or unknown).
 *
 * One signal gives a hedged "probably"; two agreeing signals give the plain
 * statement. The verdict is shown and goes into the owner's report; it never
 * says a feature is "booked" or "not booked", never names a tariff, never shows
 * a number (a short, already-masked balance figure is allowed), and it changes
 * no value, starts no candidate and writes no record. Whether it may mark a
 * failed test "not counted" is [TestBlame], bounded so it can only ever decline
 * to blame OCD, never mask a real failure.
 */

/** Which feature the verdict is about. */
@Serializable
enum class VerdictFeature(val label: String) {
    MOBILE_DATA("Mobile data"),
    VOLTE("VoLTE"),
    VOWIFI("Wi-Fi calling"),
    SMS_OVER_IMS("SMS over IMS"),
    MMS("MMS"),
}

/** The account-state read, from a balance (item 4) or a carrier SMS (item 5). Only a fresh read is passed in. */
@Serializable
enum class AccountState(val wire: String) {
    /** Balance at or near zero, or a carrier SMS said the option is paused or the volume is used up. */
    BLOCKED("blocked"),

    /** A carrier SMS said the volume is nearly used up, or the balance is low. */
    LOW("low"),

    /** The option is active, or a top-up was done, or a balance is well above zero. */
    OK("ok"),

    /** No account read, or it was stale. */
    UNKNOWN("unknown"),
}

/**
 * OCD rule round 43: which access point a refused setup used, told only by its APN row ID matching
 * OCD's own record (the test row of a running trial, or a row OCD inserted and keeps), never by its
 * name or values. Only this class is kept, never the row's name or any other field. The read-only
 * builds write no rows and never name one.
 */
@Serializable
enum class ApnRowClass(val wire: String, val plain: String) {
    TEST("test", "OCD test row"),
    OCD("ocd", "OCD's row"),
    OTHER("other", "other"),
}

/** A refusal read, from a code or from user-plane behaviour. [code] is printed only when it is a 3GPP cause number. */
@Serializable
data class Refusal(
    val cause: CauseClass,
    /** The 3GPP cause number to show, or null (a user-plane class, or a SIP class). */
    val code: Int? = null,
    /** True only for a definite temporary 3GPP cause (#26, #34, #38): it counts as a signal on its own for [TestBlame]. */
    val temporaryDefinite: Boolean = false,
    /** The user-plane signature, when the refusal came from the probe ladder rather than a code. */
    val userPlane: UserPlaneClass? = null,
    /** The W1 ePDG-name comparison's finding, when the Wi-Fi-calling refusal came from it ([EpdgW1]). */
    val epdg: EpdgClass? = null,
    /** Round 43, owner and rom: which access point a refused setup used ([ApnRowClass]); null when not told. Round 44 picks a #33's words by it. */
    val apnRow: ApnRowClass? = null,
)

/** The verdict shown and reported: the plain line, the class, and whether two signals agreed. */
@Serializable
data class CauseVerdict(
    val feature: VerdictFeature,
    val line: String,
    val cause: CauseClass,
    /** True when a refusal class and an account read agreed, so the line is a plain statement, not "probably". */
    val confident: Boolean,
)

object CauseVerdicts {
    /**
     * Builds the verdict for a failed feature from a [refusal] (optional) and an [account] read
     * (optional), plus a [balance] string the caller has already masked (null when none). A
     * feature that passed gets no verdict. MMS that the network ended is its own line.
     */
    fun of(
        feature: VerdictFeature,
        refusal: Refusal?,
        account: AccountState,
        balance: String? = null,
        mmsEnded: Boolean = false,
    ): CauseVerdict? {
        if (mmsEnded && feature == VerdictFeature.MMS) {
            return CauseVerdict(feature, "MMS ended on this network", CauseClass.NETWORK_WIDE, confident = true)
        }
        // Round 43: a hidden refusal keeps its own words. The account-block branch below never takes it over;
        // a fresh blocked account read is added after them.
        if (refusal?.cause == CauseClass.HIDDEN) return hidden(feature, refusal, account, balance)
        if (refusal == null && account == AccountState.UNKNOWN) return null

        val accountBlocks = account == AccountState.BLOCKED
        // Two signals agree when a refusal that points at the account (barred/out of credit) meets a blocked account read.
        val agree = refusal != null && refusal.cause == CauseClass.BARRED && accountBlocks

        // The account-block line (the LIDL case), for mobile data. Round 44: never in place of a coded wrong-settings
        // refusal or a #33 ([keepsWords]); a fresh blocked account read is added after their words instead.
        if (feature == VerdictFeature.MOBILE_DATA && !keepsWords(refusal) && (accountBlocks || refusal?.cause == CauseClass.BARRED)) {
            val bal = balance?.let { ": balance $it" } ?: ""
            return if (agree || (accountBlocks && refusal?.userPlane != null)) {
                CauseVerdict(feature, "Mobile data blocked by the account$bal", CauseClass.BARRED, confident = true)
            } else {
                CauseVerdict(feature, "Mobile data probably blocked by the account$bal", CauseClass.BARRED, confident = false)
            }
        }

        refusal ?: return null
        val code = refusal.code?.let { " (code $it)" } ?: ""
        val accountToo = if (keepsWords(refusal)) accountToo(feature, account, balance) else ""
        val line = when (refusal.cause) {
            // Round 44: a #33 on a WWAN feature reads by the refused setup's access point, and never falls through to
            // the AMBIGUOUS line below ("re-test with credit" would send a wrong access point name to a top-up).
            CauseClass.NOT_BOOKED ->
                if (refusal.code == 33 && feature in HIDDEN_FEATURES) return code33(feature, refusal.apnRow, accountToo)
                else "Refused for ${feature.subject()}, probably the tariff$code"
            CauseClass.WRONG_SETTINGS ->
                if (feature == VerdictFeature.MOBILE_DATA) "The network refused the access point name$code: probably wrong settings"
                else "Refused: probably wrong settings$code"
            CauseClass.TEMPORARY -> "Temporary network fault$code"
            CauseClass.ROAMING -> "Roaming is not allowed here$code"
            CauseClass.DEVICE_NOT_ALLOWED -> "Device not allowed by the network$code"
            CauseClass.BARRED -> "Barred or suspended$code"
            CauseClass.NETWORK_WIDE -> "Shut down network-wide$code"
            CauseClass.PHONE_SIDE -> phoneSide(refusal)
            CauseClass.AMBIGUOUS -> "An ambiguous refusal$code; re-test with credit"
            CauseClass.HIDDEN, CauseClass.UNKNOWN -> return null
        }
        // Temporary and phone-side lines are a single plain signal; the tariff/settings lines stay hedged by the word "probably".
        val confident = refusal.cause in setOf(CauseClass.TEMPORARY, CauseClass.PHONE_SIDE, CauseClass.DEVICE_NOT_ALLOWED, CauseClass.ROAMING)
        return CauseVerdict(feature, line + accountToo, refusal.cause, confident)
    }

    /** The features a hidden refusal can name: the WWAN ones. Wi-Fi calling's causes come from AOSP's IWLAN service, not the modem. */
    val HIDDEN_FEATURES = setOf(VerdictFeature.MOBILE_DATA, VerdictFeature.VOLTE, VerdictFeature.MMS)

    /**
     * Round 43's words, never with "(code N)": "Mobile data refused by the network; this phone's modem
     * software hides the reason", "... for the access point under test; ..." or "... for OCD's access
     * point; ..." (VoLTE and MMS alike), and for mobile data with a fresh blocked account read
     * "; the account also reads as blocked (balance 0.10 EUR)" after them. Never confident.
     */
    private fun hidden(feature: VerdictFeature, refusal: Refusal, account: AccountState, balance: String?): CauseVerdict? {
        if (feature !in HIDDEN_FEATURES) return null
        return CauseVerdict(feature, "${refusedBy(feature, refusal.apnRow)}; this phone's modem software hides the reason${accountToo(feature, account, balance)}", CauseClass.HIDDEN, confident = false)
    }

    /**
     * Round 44: a #33 ("requested service option not subscribed") on mobile data, VoLTE or MMS. Vodafone
     * Germany answers a made-up access point name with #33, other cores with #27, so #33 alone cannot tell
     * "not in the plan" from "an access point name the network does not know for this SIM". The words go by
     * the refused setup's access point, told only by its row ID (round 43's [ApnRowClass]), never by name:
     * OCD's kept row, which already worked on this SIM, "Refused for mobile data, probably the tariff
     * (code 33)" (NOT_BOOKED); an OCD test row, whatever its value, "Mobile data refused by the network for
     * the access point under test (code 33): a wrong access point name, or not in the plan" (AMBIGUOUS); any
     * other row, or one not told (a live code with no matching setup, the read-only builds), "Refused for
     * mobile data (code 33): not in the plan, or an access point name the network does not know for this
     * SIM" (AMBIGUOUS). MMS alike. VoLTE keeps the tariff words on every row but a test row: its IMS APN
     * comes from carrier data and is "ims" nearly everywhere. Never confident. [DataFailCauseClass.of] keeps
     * NOT_BOOKED for 33, so the gate, the balance read and [TestBlame] do not change.
     */
    private fun code33(feature: VerdictFeature, row: ApnRowClass?, accountToo: String): CauseVerdict {
        val tariff = "Refused for ${feature.subject()}, probably the tariff (code 33)"
        val (line, cause) = when (row) {
            ApnRowClass.TEST -> "${refusedBy(feature, row)} (code 33): a wrong access point name, or not in the plan" to CauseClass.AMBIGUOUS
            ApnRowClass.OCD -> tariff to CauseClass.NOT_BOOKED
            ApnRowClass.OTHER, null ->
                if (feature == VerdictFeature.VOLTE) tariff to CauseClass.NOT_BOOKED
                else "Refused for ${feature.subject()} (code 33): not in the plan, or an access point name the network does not know for this SIM" to CauseClass.AMBIGUOUS
        }
        return CauseVerdict(feature, line + accountToo, cause, confident = false)
    }

    /** Round 43's opening, shared with round 44's test-row words: "Mobile data refused by the network", then where. */
    private fun refusedBy(feature: VerdictFeature, row: ApnRowClass?): String {
        val where = when (row) {
            ApnRowClass.TEST -> " for the access point under test"
            ApnRowClass.OCD -> " for OCD's access point"
            ApnRowClass.OTHER, null -> ""
        }
        return "${feature.label} refused by the network$where"
    }

    /**
     * Round 44: a coded refusal that keeps its words beside a blocked account read: wrong settings (#27,
     * #28, #29, #50, #51) or a #33, which networks give an access point name that is not in the
     * subscription. No top-up fixes those. BARRED, the ambiguous #30/#31 (where no credit can be the
     * cause), the user-plane classes and every other class keep round 42's account-block line.
     */
    private fun keepsWords(refusal: Refusal?): Boolean =
        refusal != null && refusal.code != null &&
            (refusal.cause == CauseClass.WRONG_SETTINGS || (refusal.cause == CauseClass.NOT_BOOKED && refusal.code == 33))

    /** Round 43's clause after the words, mobile data only, with a fresh blocked account read: "; the account also reads as blocked (balance 0.10 EUR)". */
    private fun accountToo(feature: VerdictFeature, account: AccountState, balance: String?): String =
        if (feature == VerdictFeature.MOBILE_DATA && account == AccountState.BLOCKED) {
            "; the account also reads as blocked" + (balance?.let { " (balance $it)" } ?: "")
        } else {
            ""
        }

    private fun phoneSide(refusal: Refusal): String = refusal.epdg?.line ?: when (refusal.userPlane) {
        UserPlaneClass.PRIVATE_DNS_BROKEN -> "Phone-side: the Private DNS server can't be reached on mobile data"
        UserPlaneClass.PHONE_RESTRICTION -> "Phone-side: a phone setting (VPN, Data Saver or a data switch) is blocking it"
        else -> "Phone-side: a fault on the phone, not the network"
    }

    /** Round 44: the feature, never "this SIM": the screen shows one line per slot, and a VoLTE-only refusal would read as the whole SIM refused. */
    private fun VerdictFeature.subject(): String = if (this == VerdictFeature.MOBILE_DATA) "mobile data" else label
}

/**
 * Item 7, bounded: whether a failed data or IMS test may be marked "not counted, retry" because the
 * cause is an account block or a transient fault, not an OCD value. It never turns a failure into a
 * pass or a kept value, and its caller must never let it suppress the voice-loss hold, the X5 revert
 * or the emergency and call refusals (those fire regardless of the verdict). "Not counted" needs two
 * agreeing signals (a refusal that points at the account AND a blocked account read) or a definite
 * temporary 3GPP cause (#26, #34, #38). A single hedged signal never suppresses a test.
 */
object TestBlame {
    fun notCounted(refusal: Refusal?, account: AccountState): Boolean {
        // Round 43: a hidden refusal is a measured failure; it can be OCD's own wrong access point.
        if (refusal?.cause == CauseClass.HIDDEN) return false
        if (refusal?.temporaryDefinite == true) return true
        val accountBlocks = account == AccountState.BLOCKED
        val refusalPointsAtAccount = refusal?.cause == CauseClass.BARRED
        // The user-plane account-block signature plus a blocked account read is also two agreeing signals.
        val userPlaneBlock = refusal?.userPlane == UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE ||
            refusal?.userPlane == UserPlaneClass.PORTAL_REDIRECT ||
            refusal?.userPlane == UserPlaneClass.CARRIER_PAGE_WHILE_INTERNET_FAILS
        return accountBlocks && (refusalPointsAtAccount || userPlaneBlock)
    }
}

/**
 * OCD rule round 43, item 2: the wait clause after a shown refusal line, ". The phone waits N minutes
 * before trying again" ("1 minute" when N is 1). N is the time left until the dated end of Android's
 * wait ([RetryWaits]), worked out again each time the line is shown and rounded up to whole minutes,
 * so nobody looks again before Android retries. Under one minute, or no wait read: no clause. The
 * clause changes nothing else: not the cause class, the confident flag, the gate or [TestBlame], and
 * no cause is inferred from the length of the wait.
 */
object RetryWait {
    private const val MINUTE_MS = 60_000L
    private val CLAUSE = Regex("\\. The phone waits \\d+ minutes? before trying again")

    fun clause(untilMs: Long?, nowMs: Long): String? {
        val left = (untilMs ?: return null) - nowMs
        if (left < MINUTE_MS) return null
        val n = (left + MINUTE_MS - 1) / MINUTE_MS
        return ". The phone waits $n ${if (n == 1L) "minute" else "minutes"} before trying again"
    }

    /** [line] with the clause after it, when there is one. */
    fun after(line: String, untilMs: Long?, nowMs: Long): String = line + clause(untilMs, nowMs).orEmpty()

    /** A logged line without its clause: the check that decides whether the line changed ignores the minutes. */
    fun strip(text: String): String = CLAUSE.replace(text, "")
}
