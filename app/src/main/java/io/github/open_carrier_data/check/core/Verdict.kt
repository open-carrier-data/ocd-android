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
    /** Round 43, owner and rom: which access point a refused setup used ([ApnRowClass]); null when not told. */
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

        // The account-block line (the LIDL case), for mobile data.
        if (feature == VerdictFeature.MOBILE_DATA && (accountBlocks || refusal?.cause == CauseClass.BARRED)) {
            val bal = balance?.let { ": balance $it" } ?: ""
            return if (agree || (accountBlocks && refusal?.userPlane != null)) {
                CauseVerdict(feature, "Mobile data blocked by the account$bal", CauseClass.BARRED, confident = true)
            } else {
                CauseVerdict(feature, "Mobile data probably blocked by the account$bal", CauseClass.BARRED, confident = false)
            }
        }

        refusal ?: return null
        val code = refusal.code?.let { " (code $it)" } ?: ""
        val line = when (refusal.cause) {
            CauseClass.NOT_BOOKED -> "Refused for ${feature.subject()}, probably the tariff$code"
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
        return CauseVerdict(feature, line, refusal.cause, confident)
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
        val where = when (refusal.apnRow) {
            ApnRowClass.TEST -> " for the access point under test"
            ApnRowClass.OCD -> " for OCD's access point"
            ApnRowClass.OTHER, null -> ""
        }
        val accountToo = if (feature == VerdictFeature.MOBILE_DATA && account == AccountState.BLOCKED) {
            "; the account also reads as blocked" + (balance?.let { " (balance $it)" } ?: "")
        } else {
            ""
        }
        return CauseVerdict(feature, "${feature.label} refused by the network$where; this phone's modem software hides the reason$accountToo", CauseClass.HIDDEN, confident = false)
    }

    private fun phoneSide(refusal: Refusal): String = refusal.epdg?.line ?: when (refusal.userPlane) {
        UserPlaneClass.PRIVATE_DNS_BROKEN -> "Phone-side: the Private DNS server can't be reached on mobile data"
        UserPlaneClass.PHONE_RESTRICTION -> "Phone-side: a phone setting (VPN, Data Saver or a data switch) is blocking it"
        else -> "Phone-side: a fault on the phone, not the network"
    }

    private fun VerdictFeature.subject(): String = if (this == VerdictFeature.MOBILE_DATA) "mobile data" else "this SIM"
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
