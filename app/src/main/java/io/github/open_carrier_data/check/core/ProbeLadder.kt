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
    /**
     * D2a: one of the network's own (carrier) DNS servers, asked directly over UDP 53 (no cache, no
     * Private DNS), gave an address; null when the network lists no DNS server.
     */
    val carrierDnsOk: Boolean? = null,
    /** D2a: whether each of the network's DNS servers answered with an address, in their order; never the addresses. */
    val carrierDnsAnswers: List<Boolean> = emptyList(),
    /** D2b: a public resolver answers over UDP 53 on the cellular network. */
    val publicDnsOk: Boolean? = null,
    /** D2c: the strict Private DNS server answered on this network (uncached); null when Private DNS is not strict. */
    val privateDnsOk: Boolean? = null,
    /** Private DNS is in strict mode on this network (opportunistic mode falls back to the network's resolver, so it never breaks a lookup). */
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

/**
 * Whether a network's Private DNS is in strict mode, as ConnectivityService decides it: the phone's own
 * setting (strict: "hostname") applies to every network that needs validation (INTERNET, NOT_RESTRICTED,
 * TRUSTED). The network's LinkProperties name the server too, but not always: on the S20 on 2026-10-30
 * the background cellular network's LinkProperties showed no Private DNS while the resolver used strict
 * mode on it (Android refreshes those fields only on a later LinkProperties update). Opportunistic mode
 * falls back to the network's resolver, so it never breaks a lookup and is not strict.
 */
object PrivateDnsMode {
    fun strict(serverName: String?, settingMode: String?, appliesToNetwork: Boolean): Boolean =
        !serverName.isNullOrBlank() || (appliesToNetwork && settingMode?.trim()?.lowercase() == "hostname")
}

object ProbeLadder {
    /**
     * The user-plane class the ladder points to. Read in the ladder's own order so the first step
     * that explains the failure decides, exactly as the runner stops at it.
     */
    fun classify(r: ProbeResults): UserPlaneClass {
        // D1: the phone's own stack is blocked (a firewall, a per-app restriction).
        if (r.socketBlocked || r.loopbackOk == false) return UserPlaneClass.PHONE_RESTRICTION
        // D4: a redirect to the operator's page (top up / no data option).
        if (r.redirect == RedirectHost.CARRIER_PORTAL) return UserPlaneClass.PORTAL_REDIRECT
        // D5: the carrier's own page answers while the open internet does not.
        if (r.carrierPageOk == true && r.http204Clean != true && r.hostOk != true) {
            return UserPlaneClass.CARRIER_PAGE_WHILE_INTERNET_FAILS
        }
        // The LIDL signature: DNS answers, nothing else passes, and there is no redirect. A strict Private DNS
        // that fails too is a consequence (its TLS connection is dropped like every other) and never the cause
        // here; it is named beside the verdict as a phone-side factor ([privateDnsFactor]).
        val dnsAnswers = r.carrierDnsOk == true || r.publicDnsOk == true
        val nothingElsePasses = r.http204Clean != true && r.hostOk != true && r.ipv4LiteralOk != true && r.ipv6LiteralOk != true
        if (dnsAnswers && nothingElsePasses && r.redirect == RedirectHost.NONE) {
            return UserPlaneClass.ACCOUNT_BLOCK_SIGNATURE
        }
        // D2c: strict Private DNS fails while the network's own resolver answers and the path otherwise
        // works (the Quad9-strict trap): phone-side, never the carrier.
        if (r.privateDnsOn && r.privateDnsOk == false && dnsAnswers) {
            return UserPlaneClass.PRIVATE_DNS_BROKEN
        }
        // Only the carrier DNS is broken; connecting by IP works.
        if (r.carrierDnsOk == false && (r.ipv4LiteralOk == true || r.ipv6LiteralOk == true)) {
            return UserPlaneClass.DNS_ONLY_BROKEN
        }
        return UserPlaneClass.PATH_CLEAN
    }

    /** The phone-side factor named beside a verdict when a strict Private DNS cannot reach its server. */
    const val PRIVATE_DNS_FACTOR = "Private DNS (strict) cannot reach its server over this network"

    /**
     * Whether strict Private DNS failed on this network while it is not itself the class: a phone-side
     * factor shown beside the verdict (for example beside the account-block signature, where every TLS
     * connection is dropped), never blamed on the carrier.
     */
    fun privateDnsFactor(r: ProbeResults): Boolean =
        r.privateDnsOn && r.privateDnsOk == false && classify(r) != UserPlaneClass.PRIVATE_DNS_BROKEN

    /**
     * Each step's outcome in plain words, for the owner's log and report: only "ok", "fails", "answered"
     * or a redirect class, never an address or a host. A step that did not run says so.
     */
    fun steps(r: ProbeResults): String = listOf(
        "D1 " + when {
            r.socketBlocked -> "this app's sockets are blocked"
            else -> "loopback ${okOrFails(r.loopbackOk)}"
        },
        "D2a " + when {
            r.carrierDnsAnswers.isNotEmpty() -> "carrier resolvers (UDP 53, direct): ${r.carrierDnsAnswers.count { it }} of ${r.carrierDnsAnswers.size} answered"
            r.carrierDnsOk == null -> "carrier resolver not run (the network lists none)"
            else -> "carrier resolver ${answered(r.carrierDnsOk)}"
        },
        "D2b public resolver (UDP 53) ${answered(r.publicDnsOk)}",
        "D2c " + if (!r.privateDnsOn) "Private DNS not strict" else "Private DNS (strict) ${okOrFails(r.privateDnsOk)}",
        "D3 IPv4 literal ${okOrFails(r.ipv4LiteralOk)}, IPv6 literal ${okOrFails(r.ipv6LiteralOk)}, host ${okOrFails(r.hostOk)}",
        "D4 " + when (r.http204Clean) {
            true -> "204"
            false -> "no 204"
            null -> "no answer"
        } + ", " + when (r.redirect) {
            RedirectHost.NONE -> "no redirect"
            RedirectHost.CARRIER_PORTAL -> "redirect to the carrier's page"
            RedirectHost.OTHER -> "redirect to another host"
        },
        "D5 " + when (r.carrierPageOk) {
            null -> "not run"
            true -> "carrier page answered"
            false -> "carrier page did not answer"
        },
    ).joinToString("; ")

    private fun okOrFails(v: Boolean?): String = when (v) {
        true -> "ok"
        false -> "fails"
        null -> "not run"
    }

    private fun answered(v: Boolean?): String = when (v) {
        true -> "answered"
        false -> "did not answer"
        null -> "not run"
    }
}
