package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42 (cause diagnosis). This file is the shared brain that tells
 * apart WHY a carrier feature fails, built from the report
 * "Telling carrier failure causes apart". It holds only pure classifiers:
 * numbers and states in, a cause class out. No class here reads the phone,
 * sends traffic or keeps a value; the builds feed it the codes and behaviour
 * they are allowed to read, and the verdict ([Verdict]) pairs its output with an
 * account-state read.
 *
 * Nothing here ever stores or shows an address, a credential or an identifier:
 * its inputs are integer codes, booleans and fixed enums only.
 */

/**
 * The eight cause classes of the report, each with its letter and a plain
 * sentence fragment. The letters match the report's table (a..h).
 */
@Serializable
enum class CauseClass(val letter: String, val wire: String, val plain: String) {
    NOT_BOOKED("a", "not_booked", "not in the plan for this SIM"),
    BARRED("b", "barred", "barred, suspended or out of credit"),
    WRONG_SETTINGS("c", "wrong_settings", "wrong settings, or the network refused the access point name"),
    NETWORK_WIDE("d", "network_wide", "shut down network-wide"),
    TEMPORARY("e", "temporary", "a temporary network fault"),
    ROAMING("f", "roaming", "roaming is not allowed here"),
    DEVICE_NOT_ALLOWED("g", "device_not_allowed", "the device is not allowed by the network"),
    PHONE_SIDE("h", "phone_side", "a phone-side cause"),

    /** The code or behaviour is known but does not sort cleanly (for example an ESM #30/#31 OCS/PCRF refusal to re-test with credit). */
    AMBIGUOUS("?", "ambiguous", "an ambiguous refusal; re-test"),

    /** Nothing readable named a cause. */
    UNKNOWN("", "unknown", "the cause could not be read");

    companion object {
        fun ofWire(wire: String): CauseClass? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Classifies a mobile-data ESM/5GSM fail cause (the integer from
 * PreciseDataConnectionState.getLastCauseCode(), which equals the 3GPP cause),
 * and the IWLAN DataFailCause values AOSP's IWLAN module maps from the ePDG's
 * IKEv2 notify. Values at or above 0x4000 (16384) are IWLAN path/timeout codes,
 * phone- or path-side, and say nothing about the account.
 *
 * See error_codes_per_feature.md and active_diagnostic_tests.md.
 */
object DataFailCauseClass {
    fun of(cause: Int): CauseClass = when (cause) {
        // ESM / 5GSM (TS 24.301 / 24.501), preserved as DataFailCause.
        33 -> CauseClass.NOT_BOOKED
        8 -> CauseClass.BARRED
        27, 28, 29, 50, 51 -> CauseClass.WRONG_SETTINGS
        26, 34, 38, 66 -> CauseClass.TEMPORARY
        30, 31 -> CauseClass.AMBIGUOUS
        // IWLAN (TS 24.302 notify turned into a DataFailCause by AOSP's Iwlan module).
        9000, 9001, 9002 -> CauseClass.NOT_BOOKED
        9003 -> CauseClass.BARRED
        9006 -> CauseClass.DEVICE_NOT_ALLOWED
        11011 -> CauseClass.ROAMING
        10500 -> CauseClass.TEMPORARY
        else -> when {
            cause in 0x4000..0x4FFF -> CauseClass.PHONE_SIDE
            else -> CauseClass.UNKNOWN
        }
    }

    /**
     * AOSP's own carrier-config retry rule marks these permanent, so a privileged app
     * that sees one also knows Android will not retry it (CarrierConfigManager).
     */
    private val PERMANENT = setOf(8, 27, 28, 29, 30, 32, 33, 35, 50, 51, 111)

    fun permanent(cause: Int): Boolean = cause in PERMANENT
}

/**
 * Classifies an EMM/5GMM attach or registration reject cause
 * (NetworkRegistrationInfo.getRejectCause()). [roaming] decides whether the
 * roaming codes read as roaming (f) or, on the home PLMN, as not subscribed or
 * regional (a).
 */
object RejectCauseClass {
    fun of(cause: Int, roaming: Boolean): CauseClass = when (cause) {
        2, 7 -> CauseClass.NOT_BOOKED
        8 -> CauseClass.BARRED
        3 -> CauseClass.DEVICE_NOT_ALLOWED
        5, 6 -> CauseClass.DEVICE_NOT_ALLOWED
        11, 13, 14, 15 -> if (roaming) CauseClass.ROAMING else CauseClass.NOT_BOOKED
        else -> CauseClass.UNKNOWN
    }
}

/**
 * The SIP registration classes of item 3, from the registration callback. It
 * reads a SIP response code when the stack fills SipDetails, else an
 * ImsReasonInfo code and a timeout flag. On the S20's AOSP ImsStack today only
 * 403, a timeout or (1000, 0) arrive; once the ImsStack fork fills SipDetails
 * the finer classes follow with no app change.
 *
 * The wire names stay small and carry no identity (never the Call-ID, a URI or
 * a phrase).
 */
@Serializable
enum class SipRegClass(val wire: String, val plain: String, val cause: CauseClass) {
    FORBIDDEN("sip_403", "the subscriber is not provisioned or is barred (403)", CauseClass.NOT_BOOKED),
    USER_UNKNOWN("sip_404", "the network does not know this user (404)", CauseClass.NOT_BOOKED),
    TEMPORARY("sip_temporary", "a temporary IMS fault (480/500/503/504/408)", CauseClass.TEMPORARY),
    STACK("sip_stack", "the request was malformed (400/488): a stack problem", CauseClass.PHONE_SIDE),
    TIMEOUT("sip_timeout", "the REGISTER timed out: a path or temporary fault", CauseClass.TEMPORARY),
    DATA_LOST("ims_data_lost", "the IMS data connection was lost: a phone or data-side cause", CauseClass.PHONE_SIDE),
    OTHER("sip_other", "the IMS stack did not keep the SIP reason", CauseClass.UNKNOWN);

    companion object {
        /** ImsReasonInfo codes AOSP sets for a lost IMS data connection (DATA_DISCONNECTED path). */
        private val DATA_LOSS_CODES = setOf(121, 122, 106, 112)

        /**
         * [responseCode]: the SIP status from SipDetails, 0 or null when the stack did not fill it.
         * [reasonCode]: ImsReasonInfo code (1000 REGISTRATION_ERROR and 0 UNSPECIFIED is "not kept").
         * [timeout]: the stack reported a timeout rather than a status.
         */
        fun of(responseCode: Int?, reasonCode: Int?, timeout: Boolean): SipRegClass = when {
            timeout -> TIMEOUT
            reasonCode != null && reasonCode in DATA_LOSS_CODES -> DATA_LOST
            responseCode != null && responseCode >= 400 -> ofSip(responseCode)
            else -> OTHER
        }

        private fun ofSip(code: Int): SipRegClass = when (code) {
            403 -> FORBIDDEN
            404 -> USER_UNKNOWN
            400, 488 -> STACK
            408, 480, 500, 503, 504 -> TEMPORARY
            else -> if (code in 500..599) TEMPORARY else OTHER
        }
    }
}

/**
 * The user-plane signatures a build reads from the probe ladder (D1-D5) or from
 * the passive NetworkCapabilities/LinkProperties state, when no reject code is
 * available (the common German money cases produce no code). This is what lets
 * even a normal app tell an account block apart from a DNS fault or a phone-side
 * fault, exactly the LIDL case.
 */
@Serializable
enum class UserPlaneClass(val wire: String, val plain: String, val cause: CauseClass) {
    /** D4: a plain-HTTP 204 check redirected to an operator page (top up / no data option). */
    PORTAL_REDIRECT("portal_redirect", "mobile data is redirected to the operator's page", CauseClass.BARRED),

    /** The LIDL signature: the session is up, carrier DNS answers, every other connection is dropped with no redirect. */
    ACCOUNT_BLOCK_SIGNATURE("account_block_signature", "mobile data is up but silently blocked (DNS answers, nothing else passes)", CauseClass.BARRED),

    /** D5: a zero-rated carrier self-care page answers while the open internet does not. */
    CARRIER_PAGE_WHILE_INTERNET_FAILS("carrier_page_reachable", "the carrier's own page is reachable while the internet is not", CauseClass.BARRED),

    /** The phone's Private DNS (DoT/DoH) fails while the network's own resolver works (the Quad9-strict trap). */
    PRIVATE_DNS_BROKEN("private_dns_broken", "the Private DNS server can't be reached on mobile data", CauseClass.PHONE_SIDE),

    /** Only the carrier's DNS is broken; connecting by IP works. */
    DNS_ONLY_BROKEN("dns_only_broken", "the carrier's DNS does not answer, but connecting by address works", CauseClass.TEMPORARY),

    /** A VPN or Data Saver, or a user/policy data switch, is the cause. */
    PHONE_RESTRICTION("phone_restriction", "a phone setting (VPN, Data Saver or a data switch) is blocking it", CauseClass.PHONE_SIDE),

    /** The ladder found the path clean: the failure is elsewhere or gone. */
    PATH_CLEAN("path_clean", "the data path looks clean", CauseClass.UNKNOWN);
}
