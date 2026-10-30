package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, item 2: the cellular-bound probe ladder's classifier. The
 * runner binds each step to the cellular Network and fills [ProbeResults]; this
 * pure function turns the step outcomes into a [UserPlaneClass], which the
 * verdict pairs with an account-state read. It holds no socket and keeps no
 * address: every field is a boolean or a small enum, and a redirect host is kept
 * only as "on the carrier allowlist" or not.
 *
 * The ladder stops at the first step that explains the failure, so the classifier
 * reads the steps in the same order: phone-side first, then DNS, then the portal
 * and carrier-page account signals, then the silent-block signature.
 */

/** A redirect's destination, reduced so no arbitrary host is kept. */
@Serializable
enum class RedirectHost { NONE, CARRIER_PORTAL, OTHER }

/** The outcomes of the mobile-data ladder (D1-D5). Null where a step did not run. */
@Serializable
data class ProbeResults(
    /** D1: a loopback/local socket works (the phone's own stack is not firewalled off). */
    val loopbackOk: Boolean? = null,
    /** D1: ConnectivityManager.onBlockedStatusChanged reported the socket blocked for this uid. */
    val socketBlocked: Boolean = false,
    /** D2: the network's own (carrier) resolver answers. */
    val carrierDnsOk: Boolean? = null,
    /** D2: a public resolver answers over UDP 53 on the cellular network. */
    val publicDnsOk: Boolean? = null,
    /** D2: the Private DNS (DoT/DoH) path works; null when Private DNS is off. */
    val privateDnsOk: Boolean? = null,
    val privateDnsOn: Boolean = false,
    /** D3: a TCP/HTTP connection to an IPv4 literal works (DNS bypassed). */
    val ipv4LiteralOk: Boolean? = null,
    /** D3: to an IPv6 literal. */
    val ipv6LiteralOk: Boolean? = null,
    /** D3: to a host name (DNS + TCP). */
    val hostOk: Boolean? = null,
    /** D4: the plain-HTTP 204 check returned 204 (a clean path). */
    val http204Clean: Boolean? = null,
    /** D4: where a 3xx/200 redirect pointed. */
    val redirect: RedirectHost = RedirectHost.NONE,
    /** D5: a zero-rated carrier self-care page answered. */
    val carrierPageOk: Boolean? = null,
)

object ProbeLadder {
    /**
     * The user-plane class the ladder points to. Read in the ladder's own order so the first step
     * that explains the failure decides, exactly as the runner stops at it.
     */
    fun classify(r: ProbeResults): UserPlaneClass {
        // D1: the phone's own stack is blocked (a firewall, a per-app restriction).
        if (r.socketBlocked || r.loopbackOk == false) return UserPlaneClass.PHONE_RESTRICTION
        // D2: Private DNS is on and fails, while the network's own resolver works (the Quad9-strict trap).
        if (r.privateDnsOn && r.privateDnsOk == false && (r.carrierDnsOk == true || r.publicDnsOk == true)) {
            return UserPlaneClass.PRIVATE_DNS_BROKEN
        }
        // D4: a redirect to the operator's page (top up / no data option).
        if (r.redirect == RedirectHost.CARRIER_PORTAL) return UserPlaneClass.PORTAL_REDIRECT
        // D5: the carrier's own page answers while the open internet does not.
        if (r.carrierPageOk == true && r.http204Clean != true && r.hostOk != true) {
            return UserPlaneClass.CARRIER_PAGE_WHILE_INTERNET_FAILS
        }
        // The LIDL signature: DNS answers, nothing else passes, and there is no redirect.
        val dnsAnswers = r.carrierDnsOk == true || r.publicDnsOk == true
        val nothingElsePasses = r.http204Clean != true && r.hostOk != true && r.ipv4LiteralOk != true && r.ipv6LiteralOk != true
        if (dnsAnswers && nothingElsePasses && r.redirect == RedirectHost.NONE) {
            return UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE
        }
        // Only the carrier DNS is broken; connecting by IP works.
        if (r.carrierDnsOk == false && (r.ipv4LiteralOk == true || r.ipv6LiteralOk == true)) {
            return UserPlaneClass.DNS_ONLY_BROKEN
        }
        return UserPlaneClass.PATH_CLEAN
    }
}
