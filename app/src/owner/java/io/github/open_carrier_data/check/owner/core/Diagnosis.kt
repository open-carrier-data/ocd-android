package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.CauseVerdict
import io.github.open_carrier_data.check.core.CauseVerdicts
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.EpdgW1
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.Refusal
import io.github.open_carrier_data.check.core.RejectCauseClass
import io.github.open_carrier_data.check.core.HiddenDetails
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.SipRegClass
import io.github.open_carrier_data.check.core.TestBlame
import io.github.open_carrier_data.check.core.UserPlaneClass
import io.github.open_carrier_data.check.core.VerdictFeature
import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, items 1, 3 and 7, on the owner (and rom) build. This is the
 * pure assembler: the privileged passive read's fields and the IMS registration
 * reader's classes come in, a per-feature verdict comes out. It holds no phone
 * call; the readers in OwnerApis fill [DiagnosisInputs], and the screen and the
 * owner report show [Diagnosis.verdicts]. Every field is a code, a class or a
 * boolean, never an address, a credential or an identifier.
 */

/**
 * One passive read of the fail causes and user-plane state the owner build reads with its
 * privileged permissions (item 1, D0/W0). Nulls where the phone gave nothing. The data-fail
 * causes are the integer DataFailCause (= the 3GPP cause) of each APN's last
 * PreciseDataConnectionState; the reject cause is NetworkRegistrationInfo.getRejectCause().
 */
@Serializable
data class DiagnosisInputs(
    val roaming: Boolean = false,
    /** PreciseDataConnectionState.getLastCauseCode() for the default internet APN, or null. */
    val defaultDataCause: Int? = null,
    /** …for the IMS APN. */
    val imsDataCause: Int? = null,
    /** …for the MMS APN. */
    val mmsDataCause: Int? = null,
    /** …for the IWLAN (Wi-Fi calling) transport. */
    val iwlanDataCause: Int? = null,
    /** NetworkRegistrationInfo.getRejectCause() for the WWAN packet-switched registration. */
    val rejectCause: Int? = null,
    /**
     * The SIM's own cellular internet network is connected: read on that network (its
     * TelephonyNetworkSpecifier), never the default network, which is the Wi-Fi while Wi-Fi is on.
     * Null when not read.
     */
    val cellularUp: Boolean? = null,
    /**
     * Android's NetworkMonitor validated the SIM's cellular network (NetworkCapabilities VALIDATED), read
     * on that network, not the default one; null when not read or when no cellular network is up.
     */
    val dataValidated: Boolean? = null,
    /** NetworkCapabilities.isPrivateDnsBroken(), read on the SIM's cellular network, not the default one. */
    val privateDnsBroken: Boolean = false,
    /** The SIM's cellular network uses Private DNS in strict mode (its LinkProperties name a Private DNS server). */
    val privateDnsStrict: Boolean = false,
    /** The probe ladder's D2c: the strict Private DNS server did not answer over the cellular network; null when it was not asked. */
    val privateDnsFails: Boolean? = null,
    /** A VPN holds the default network (NetworkCapabilities: not NOT_VPN). */
    val vpnActive: Boolean = false,
    /** Data Saver is restricting background data (ConnectivityManager.getRestrictBackgroundStatus()). */
    val dataSaver: Boolean = false,
    /** Mobile data is off by a user, policy, carrier or thermal reason (isDataEnabledForReason); null when all on. */
    val dataOffReason: String? = null,
    /** The user-plane signature from the probe ladder, when it has run (item 2); null otherwise. */
    val userPlane: UserPlaneClass? = null,
    /** The IMS REGISTER's SIP response code (SipDetails), 0/null when the stack did not fill it. */
    val sipResponseCode: Int? = null,
    /** The ImsReasonInfo code of the last IMS sign-out. */
    val imsReasonCode: Int? = null,
    /** The last IMS sign-out was a timeout. */
    val imsTimeout: Boolean = false,
    /** IMS is registered for voice now; when true no VoLTE/VoWiFi refusal is read. */
    val imsRegistered: Boolean? = null,
    /** The account-state read (item 4/5): a balance or a carrier SMS class; UNKNOWN until those run. */
    val account: AccountState = AccountState.UNKNOWN,
    /** A short, already-masked balance figure to show, or null. */
    val balance: String? = null,
    /** This SIM's network ended MMS (the published statements / mms-ended list). */
    val mmsEnded: Boolean = false,
    /** W1 (item 2): the standard ePDG name through the Wi-Fi network's resolver, when it was asked within 6 hours. */
    val epdgWifi: EpdgLookup? = null,
    /** W1: the same name through the cellular network's resolver. */
    val epdgCellular: EpdgLookup? = null,
    /**
     * OCD rule round 43: the newest refused setup of the internet APN on WWAN, from telephony.registry's
     * dated local log ([io.github.open_carrier_data.check.core.SetupAttempts]): a DISCONNECTED state after
     * a CONNECTING state of the same profile, newer than the last connected line for that APN type, with
     * no later state for that profile; only a refusal cause (a classified code or a hidden one). Android
     * drops a DISCONNECTED state from the states it replays, so the passive listener above never sees it.
     */
    val dataSetup: SetupRefusal? = null,
    /** …of the IMS APN on WWAN (VoLTE). */
    val imsSetup: SetupRefusal? = null,
    /** …of the MMS APN on WWAN. */
    val mmsSetup: SetupRefusal? = null,
    /** Mobile data is on for every reason (user, policy, carrier, thermal), each read; null when not read. */
    val dataOnAllReasons: Boolean? = null,
) {
    /**
     * The silent block (the LIDL case): the SIM's cellular internet network is up and Android's
     * NetworkMonitor, which re-validates it about every 10 minutes even while Wi-Fi is the default,
     * does not validate it. Every cause stays 0 then, so no code would ever show it. Unreadable counts
     * as no failure: nothing runs on a guess.
     */
    val cellularUnvalidated: Boolean get() = cellularUp == true && dataValidated == false

    /** Strict Private DNS cannot reach its server over the SIM's cellular network: the broken bit, or the ladder's D2c. */
    val privateDnsUnreachable: Boolean get() = privateDnsStrict && (privateDnsBroken || privateDnsFails == true)

    /**
     * Round 43: mobile data is still down, both read: on for every reason, and no cellular internet
     * network up for this SIM. Unreadable counts as not down, so an old refusal never shows on a guess.
     */
    val dataStillDown: Boolean get() = dataOnAllReasons == true && cellularUp == false

    /** Round 43: the log's refused internet setup with a refusal cause (never AOSP's -1 to -6), only while mobile data is still down. */
    val dataSetupRefusal: SetupRefusal? get() = dataSetup?.takeIf { dataStillDown && DataFailCauseClass.refusal(it.cause) }

    /** Round 43: the log's refused IMS setup with a refusal cause, only while IMS is not registered (read). */
    val imsSetupRefusal: SetupRefusal? get() = imsSetup?.takeIf { imsRegistered == false && DataFailCauseClass.refusal(it.cause) }

    /**
     * A measured mobile-data failure, the gate of the automatic probe ladder and the balance read: a
     * non-zero fail cause on the default APN, an attach reject, the SIM's cellular network up but
     * not validated ([cellularUnvalidated]), or (round 43) a refused internet setup in the registry's
     * log while mobile data is still down ([dataSetupRefusal]: a classified or a hidden cause, never
     * AOSP's -1 to -6). A working connection reports getLastCauseCode() 0 (DataFailCause.NONE),
     * which is no failure.
     */
    val measuredDataFailure: Boolean get() = (defaultDataCause ?: 0) != 0 || rejectCause != null || cellularUnvalidated || dataSetupRefusal != null
}

/** The diagnosis of one SIM: the per-feature verdicts, and whether a failed test should not count. */
data class Diagnosis(
    val verdicts: List<CauseVerdict>,
    /** The mobile-data refusal, kept so the test linkage ([TestBlame]) can read it. */
    val dataRefusal: Refusal?,
    /** The account-state read that went into the verdict, for the test linkage. */
    val account: AccountState = AccountState.UNKNOWN,
    /**
     * Phone-side factors named beside the verdict, never as the carrier's fault and never as a refusal
     * that could set a test aside: "Phone-side: Private DNS (strict) cannot reach its server over this network".
     */
    val factors: List<String> = emptyList(),
    /**
     * Round 43's Details, never in the line: "Mobile data: Android cause UNKNOWN (0x10000), modem value -7;
     * access point: other", and an unknown Wi-Fi calling (IWLAN) value, which gets no line.
     */
    val details: List<String> = emptyList(),
) {
    /** The headline line for the screen and the report: the mobile-data verdict first, else the first feature's. */
    val line: String? get() = (verdicts.firstOrNull { it.feature == VerdictFeature.MOBILE_DATA } ?: verdicts.firstOrNull())?.line

    /** The headline and the phone-side factors, as the log keeps them; null when there is neither. */
    val summary: String? get() = (listOfNotNull(line) + factors).joinToString("; ").ifEmpty { null }

    /**
     * Item 7, bounded: may a failed data or IMS test be set aside as "not counted, retry" because the
     * cause is an account block or a transient fault, not an OCD value? Never a pass, and its caller
     * must still let the voice-loss hold, the X5 revert and the emergency refusals fire regardless.
     */
    fun notCounted(): Boolean = TestBlame.notCounted(dataRefusal, account)
}

object Diagnoses {
    /** The phone-side reasons, read passively, that explain mobile data without a code. */
    private fun phoneSide(i: DiagnosisInputs): Refusal? = when {
        i.dataOffReason != null -> Refusal(CauseClass.PHONE_SIDE, userPlane = UserPlaneClass.PHONE_RESTRICTION)
        i.vpnActive || i.dataSaver -> Refusal(CauseClass.PHONE_SIDE, userPlane = UserPlaneClass.PHONE_RESTRICTION)
        i.privateDnsBroken -> Refusal(CauseClass.PHONE_SIDE, userPlane = UserPlaneClass.PRIVATE_DNS_BROKEN)
        else -> null
    }

    /** A readable code from the live state: what DataFailCauseClass classifies. */
    private fun coded(c: Int): Refusal? {
        val cls = DataFailCauseClass.of(c)
        return if (cls != CauseClass.UNKNOWN) Refusal(cls, code = c, temporaryDefinite = cls == CauseClass.TEMPORARY && c in setOf(26, 34, 38)) else null
    }

    /** A reject cause explains "no data at all" when the session never came up. */
    private fun reject(i: DiagnosisInputs): Refusal? = i.rejectCause?.let { rc ->
        RejectCauseClass.of(rc, i.roaming).takeIf { it != CauseClass.UNKNOWN }?.let { Refusal(it, code = rc) }
    }

    /**
     * The mobile-data refusal: a code first (the live state's, then the registry log's refused setup),
     * then (round 43) a hidden refusal, below an attach reject cause, then the probe ladder's
     * user-plane signature, then the passive phone-side reasons. With no refused setup in the log the
     * order is round 42's.
     */
    private fun dataRefusal(i: DiagnosisInputs): Refusal? {
        i.defaultDataCause?.let { c -> coded(c)?.let { return it } }
        val setup = i.dataSetupRefusal?.refusal()
        if (setup != null && setup.cause != CauseClass.HIDDEN) return setup
        if (setup != null) return reject(i) ?: setup
        i.userPlane?.takeIf { it != UserPlaneClass.PATH_CLEAN }?.let { return Refusal(it.cause, userPlane = it) }
        reject(i)?.let { return it }
        return phoneSide(i)
    }

    /**
     * A Wi-Fi-calling or VoLTE refusal from the IWLAN/IMS data cause or the SIP class, only when IMS is
     * not registered. For Wi-Fi calling, W1's comparison ([epdg]) comes after a code that names the
     * cause and before the SIP class: it sharpens a phone-side IWLAN code (a DNS failure, for one)
     * or the lack of a code into the Wi-Fi side's DNS, and never displaces a refusal code.
     */
    private fun imsRefusal(dataCause: Int?, i: DiagnosisInputs, epdg: Refusal? = null, setup: SetupRefusal? = null): Refusal? {
        if (i.imsRegistered == true) return null
        val code = dataCause?.let { coded(it) }
        if (code != null && code.cause != CauseClass.PHONE_SIDE) return code
        epdg?.let { return it }
        // Round 43, VoLTE: the registry log's refused IMS setup on WWAN (a code, else the hidden class), only while IMS is not registered.
        setup?.refusal()?.let { return it }
        code?.let { return it }
        val sip = SipRegClass.of(i.sipResponseCode, i.imsReasonCode, i.imsTimeout)
        return if (sip.cause != CauseClass.UNKNOWN) Refusal(sip.cause) else null
    }

    fun of(i: DiagnosisInputs): Diagnosis {
        val verdicts = mutableListOf<CauseVerdict>()
        val data = dataRefusal(i)
        CauseVerdicts.of(VerdictFeature.MOBILE_DATA, data, i.account, i.balance)?.let { verdicts += it }
        // Wi-Fi calling never reads the registry log's setups: its causes come from AOSP's IWLAN service, not the modem.
        CauseVerdicts.of(VerdictFeature.VOWIFI, imsRefusal(i.iwlanDataCause, i, EpdgW1.refusal(i.epdgWifi, i.epdgCellular)), AccountState.UNKNOWN)?.let { verdicts += it }
        CauseVerdicts.of(VerdictFeature.VOLTE, imsRefusal(i.imsDataCause, i, setup = i.imsSetupRefusal), AccountState.UNKNOWN)?.let { verdicts += it }
        if (i.mmsEnded) CauseVerdicts.of(VerdictFeature.MMS, null, AccountState.UNKNOWN, mmsEnded = true)?.let { verdicts += it }
        else (i.mmsDataCause?.let { coded(it) } ?: i.mmsSetup?.refusal())?.let { r ->
            CauseVerdicts.of(VerdictFeature.MMS, r, AccountState.UNKNOWN)?.let { verdicts += it }
        }
        // Strict Private DNS that cannot reach its server is the phone's, never the carrier's: named beside the
        // verdict, unless it already is the mobile-data verdict ("the Private DNS server can't be reached").
        val factors = listOfNotNull(
            "Phone-side: ${ProbeLadder.PRIVATE_DNS_FACTOR}".takeIf { i.privateDnsUnreachable && data?.userPlane != UserPlaneClass.PRIVATE_DNS_BROKEN },
        )
        return Diagnosis(verdicts, data, i.account, factors, details(i, verdicts))
    }

    /**
     * Round 43's Details: the raw value of each hidden refusal shown (and the modem value when the
     * phone service's dump was read), with the access point class; and an unknown Wi-Fi calling value,
     * which goes to Details only and never gets a line.
     */
    private fun details(i: DiagnosisInputs, verdicts: List<CauseVerdict>): List<String> {
        val hidden = verdicts.filter { it.cause == CauseClass.HIDDEN }.mapNotNull { v ->
            when (v.feature) {
                VerdictFeature.MOBILE_DATA -> i.dataSetupRefusal
                VerdictFeature.VOLTE -> i.imsSetupRefusal
                VerdictFeature.MMS -> i.mmsSetup
                else -> null
            }?.let { HiddenDetails.line(v.feature, it.cause, it.modemValue, it.apnRow ?: io.github.open_carrier_data.check.core.ApnRowClass.OTHER) }
        }
        val iwlan = i.iwlanDataCause?.takeIf { DataFailCauseClass.hidden(it) && i.imsRegistered != true }
            ?.let { HiddenDetails.line(VerdictFeature.VOWIFI, it) }
        return hidden + listOfNotNull(iwlan)
    }
}

/**
 * OCD rule round 43: which access point a refused setup used, by the APN row ID the registry line
 * prints. Only a row in OCD's own record counts: the test row of a running trial ([Hold.TRIAL]) or
 * a row OCD inserted and keeps ([Hold.KEPT]), for this SIM, never matched by APN name or values. The
 * row's edited state must still be the one OCD's insert writes (USER_EDITED); any other state (a
 * carrier or unedited row under a reused ID after the table was rebuilt, a carrier edit) gets the
 * plain words. Only the class is kept.
 */
object ApnRows {
    /** The edited state OCD's own insert writes ([ApnColumns.USER_EDITED], edited=1), as the registry line prints it. */
    const val OCD_EDITED = "USER_EDITED"

    fun classOf(rowId: Long?, edited: String?, apns: List<LiveApn>, subId: Int): io.github.open_carrier_data.check.core.ApnRowClass {
        val other = io.github.open_carrier_data.check.core.ApnRowClass.OTHER
        if (rowId == null || edited != OCD_EDITED) return other
        val live = apns.firstOrNull { it.rowId == rowId && it.sim.subId == subId } ?: return other
        return if (live.hold == Hold.TRIAL) io.github.open_carrier_data.check.core.ApnRowClass.TEST else io.github.open_carrier_data.check.core.ApnRowClass.OCD
    }
}

/**
 * Round 43: one SIM's current verdict for the owner screen, replaced at every check and dropped when
 * the check names nothing. The line and the phone-side factors as the log has them, and the Details;
 * classes, codes and plain words only, never an identifier, an APN name or dump text.
 */
@Serializable
data class DiagnosisMark(
    val slot: Int,
    val subId: Int,
    val atMs: Long,
    /** The headline verdict line (mobile data first), without the phone-side factors. */
    val line: String?,
    val factors: List<String> = emptyList(),
    val details: List<String> = emptyList(),
) {
    /** The line as the screen shows it, the phone-side factors after it. */
    fun shown(): String? = (listOfNotNull(line) + factors).joinToString("; ").ifEmpty { null }

    companion object {
        /** [marks] with [slot]'s mark replaced by [diagnosis]'s, or dropped when it names nothing. */
        fun put(marks: List<DiagnosisMark>, slot: Int, subId: Int, diagnosis: Diagnosis?, atMs: Long): List<DiagnosisMark> {
            val rest = marks.filter { it.slot != slot }
            val mark = diagnosis?.takeIf { it.summary != null }?.let { DiagnosisMark(slot, subId, atMs, it.line, it.factors, it.details) }
            return (rest + listOfNotNull(mark)).sortedBy { it.slot }
        }
    }
}

