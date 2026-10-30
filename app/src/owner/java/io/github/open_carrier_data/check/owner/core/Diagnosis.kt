package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.AccountState
import io.github.open_carrier_data.check.core.CauseClass
import io.github.open_carrier_data.check.core.CauseVerdict
import io.github.open_carrier_data.check.core.CauseVerdicts
import io.github.open_carrier_data.check.core.DataFailCauseClass
import io.github.open_carrier_data.check.core.Refusal
import io.github.open_carrier_data.check.core.RejectCauseClass
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
    /** The default mobile network validated (NetworkCapabilities VALIDATED); null when not read. */
    val dataValidated: Boolean? = null,
    /** NetworkCapabilities.isPrivateDnsBroken() on the default network. */
    val privateDnsBroken: Boolean = false,
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
)

/** The diagnosis of one SIM: the per-feature verdicts, and whether a failed test should not count. */
data class Diagnosis(
    val verdicts: List<CauseVerdict>,
    /** The mobile-data refusal, kept so the test linkage ([TestBlame]) can read it. */
    val dataRefusal: Refusal?,
    /** The account-state read that went into the verdict, for the test linkage. */
    val account: AccountState = AccountState.UNKNOWN,
) {
    /** The headline line for the screen and the report: the mobile-data verdict first, else the first feature's. */
    val line: String? get() = (verdicts.firstOrNull { it.feature == VerdictFeature.MOBILE_DATA } ?: verdicts.firstOrNull())?.line

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

    /** The mobile-data refusal: a code first, then the probe ladder's user-plane signature, then the passive phone-side reasons. */
    private fun dataRefusal(i: DiagnosisInputs): Refusal? {
        i.defaultDataCause?.let { c ->
            val cls = DataFailCauseClass.of(c)
            if (cls != CauseClass.UNKNOWN) {
                return Refusal(cls, code = c, temporaryDefinite = cls == CauseClass.TEMPORARY && c in setOf(26, 34, 38))
            }
        }
        i.userPlane?.takeIf { it != UserPlaneClass.PATH_CLEAN }?.let { return Refusal(it.cause, userPlane = it) }
        // A reject cause explains "no data at all" when the session never came up.
        i.rejectCause?.let { rc ->
            val cls = RejectCauseClass.of(rc, i.roaming)
            if (cls != CauseClass.UNKNOWN) return Refusal(cls, code = rc)
        }
        return phoneSide(i)
    }

    /** A Wi-Fi-calling or VoLTE refusal from the IWLAN/IMS data cause or the SIP class, only when IMS is not registered. */
    private fun imsRefusal(dataCause: Int?, i: DiagnosisInputs): Refusal? {
        if (i.imsRegistered == true) return null
        dataCause?.let { c ->
            val cls = DataFailCauseClass.of(c)
            if (cls != CauseClass.UNKNOWN) return Refusal(cls, code = c, temporaryDefinite = cls == CauseClass.TEMPORARY && c in setOf(26, 34, 38))
        }
        val sip = SipRegClass.of(i.sipResponseCode, i.imsReasonCode, i.imsTimeout)
        return if (sip.cause != CauseClass.UNKNOWN) Refusal(sip.cause) else null
    }

    fun of(i: DiagnosisInputs): Diagnosis {
        val verdicts = mutableListOf<CauseVerdict>()
        val data = dataRefusal(i)
        CauseVerdicts.of(VerdictFeature.MOBILE_DATA, data, i.account, i.balance)?.let { verdicts += it }
        CauseVerdicts.of(VerdictFeature.VOWIFI, imsRefusal(i.iwlanDataCause, i), AccountState.UNKNOWN)?.let { verdicts += it }
        CauseVerdicts.of(VerdictFeature.VOLTE, imsRefusal(i.imsDataCause, i), AccountState.UNKNOWN)?.let { verdicts += it }
        if (i.mmsEnded) CauseVerdicts.of(VerdictFeature.MMS, null, AccountState.UNKNOWN, mmsEnded = true)?.let { verdicts += it }
        else if (i.mmsDataCause != null) {
            val cls = DataFailCauseClass.of(i.mmsDataCause)
            if (cls != CauseClass.UNKNOWN) CauseVerdicts.of(VerdictFeature.MMS, Refusal(cls, code = i.mmsDataCause), AccountState.UNKNOWN)?.let { verdicts += it }
        }
        return Diagnosis(verdicts, data, i.account)
    }
}
