package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, item 2, W1: the ePDG name lookup, as pure logic. The runner
 * ([ProbeNet.epdgLookup]) looks up the SIM's standard ePDG name
 * (epdg.epc.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org, [HomeNetwork.epdgFqdn]) once
 * through the current Wi-Fi network's resolver and once through the cellular
 * network's resolver. Each lookup is kept only as "resolves", "no address" or
 * "fails"; no address is ever kept or shown. W2 (an IKE_SA_INIT to the ePDG) is not
 * built: no IKE packet and no hand-built packet goes to a carrier server.
 *
 * Only a comparison names a cause: the cellular resolver gives an address and the
 * Wi-Fi resolver does not, so the Wi-Fi side's DNS keeps Wi-Fi calling from finding
 * its server (the Vodafone ECS case in [VodafoneDnsHint]). One resolver's non-answer
 * alone is never evidence: a carrier may not publish the standard name at all.
 */

/** One resolver's answer for the standard ePDG name. Never an address. */
@Serializable
enum class EpdgLookup(val wire: String, val plain: String) {
    RESOLVES("resolves", "resolves"),

    /** The resolver answered (NOERROR or NXDOMAIN) without an address. */
    NO_ADDRESS("no_address", "no address"),

    /** An error, a timeout, a SERVFAIL or REFUSED, or the network did not come up. */
    FAILS("fails", "fails");

    companion object {
        /**
         * From one DnsResolver answer: [addresses] how many came back (only the count; null on an
         * error or a timeout) and [rcode] the DNS response code.
         */
        fun of(addresses: Int?, rcode: Int?): EpdgLookup = when {
            addresses == null -> FAILS
            addresses > 0 -> RESOLVES
            rcode == 0 || rcode == 3 -> NO_ADDRESS
            else -> FAILS
        }
    }
}

/** The W1 comparison's finding for the Wi-Fi-calling verdict, a phone-side (here: the Wi-Fi side's) class. */
@Serializable
enum class EpdgClass(val wire: String, val line: String) {
    WIFI_DNS_NO_ADDRESS(
        "wifi_dns_no_address",
        "Wi-Fi side: this Wi-Fi's DNS gives no address for the carrier's Wi-Fi calling server; the mobile network's DNS does",
    ),
    WIFI_DNS_FAILS(
        "wifi_dns_fails",
        "Wi-Fi side: this Wi-Fi's DNS does not answer for the carrier's Wi-Fi calling server; the mobile network's DNS does",
    ),
}

object EpdgW1 {
    /** The finding, or null: only when the cellular resolver gives an address and the Wi-Fi resolver does not. */
    fun classify(wifi: EpdgLookup?, cellular: EpdgLookup?): EpdgClass? = when {
        cellular != EpdgLookup.RESOLVES -> null
        wifi == EpdgLookup.NO_ADDRESS -> EpdgClass.WIFI_DNS_NO_ADDRESS
        wifi == EpdgLookup.FAILS -> EpdgClass.WIFI_DNS_FAILS
        else -> null
    }

    /** The Wi-Fi-calling refusal W1 names, or null. */
    fun refusal(wifi: EpdgLookup?, cellular: EpdgLookup?): Refusal? =
        classify(wifi, cellular)?.let { Refusal(CauseClass.PHONE_SIDE, epdg = it) }

    /** The record of one run: each resolver that was asked and its class, never the name's addresses. */
    fun record(wifi: EpdgLookup?, cellular: EpdgLookup?): String = listOfNotNull(
        wifi?.let { "Wi-Fi resolver ${it.plain}" },
        cellular?.let { "mobile network's resolver ${it.plain}" },
    ).joinToString("; ").ifEmpty { "no resolver asked" }
}

/** Which resolvers one W1 run asks. */
data class EpdgPlan(val wifi: Boolean, val cellular: Boolean)

/**
 * The bounds on W1 (round 42 item 2, owner and rom): an automatic run only after a measured
 * Wi-Fi-calling failure, at most once per SIM per 6 hours ([ProbePolicy.autoDue]); the owner's
 * "check now" while Wi-Fi calling is on or failing. An automatic run puts no traffic on a cellular
 * link the person is not using: with Wi-Fi the default it asks only the Wi-Fi resolver, plus the
 * cellular one when cellular data itself is failing; the full Wi-Fi-versus-cellular comparison runs
 * on "check now". The caller checks roaming, mobile data off ([ProbePolicy.refusal]) and the
 * emergency and call refusals first.
 */
object EpdgPolicy {
    /**
     * A measured Wi-Fi-calling failure: a non-zero IWLAN fail cause, or Wi-Fi calling switched on
     * with Wi-Fi connected while IMS is not registered at all.
     */
    fun wifiCallingFailure(wfcOn: Boolean?, wifiConnected: Boolean, imsRegistered: Boolean?, iwlanCause: Int?): Boolean =
        (iwlanCause ?: 0) != 0 || (wfcOn == true && wifiConnected && imsRegistered == false)

    fun plan(
        checkNow: Boolean,
        wfcOn: Boolean?,
        wifiCallingFailure: Boolean,
        wifiConnected: Boolean,
        cellularDataFailing: Boolean,
        cellularInUse: Boolean,
        lastRunAtMs: Long?,
        nowMs: Long,
    ): EpdgPlan? {
        val due = if (checkNow) wfcOn == true || wifiCallingFailure else ProbePolicy.autoDue(wifiCallingFailure, lastRunAtMs, nowMs)
        if (!due) return null
        val cellular = checkNow || cellularDataFailing || cellularInUse
        if (!wifiConnected && !cellular) return null
        return EpdgPlan(wifi = wifiConnected, cellular = cellular)
    }
}
