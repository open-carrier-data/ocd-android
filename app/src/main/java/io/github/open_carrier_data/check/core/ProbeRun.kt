package io.github.open_carrier_data.check.core

/*
 * OCD rule round 42, item 2: the live probe ladder. [ProbeLadder.classify] is the
 * merged, reviewed classifier (D1-D5 outcomes in, a [UserPlaneClass] out); this
 * file is the runner that fills [ProbeResults] by actually touching the network,
 * plus the pure policy that bounds when a run may happen.
 *
 * Every target is fixed here, never taken from a reply or a server (the ruling's
 * condition: a target from a reply could be steered to a tracking host). No request
 * carries a device or SIM identifier. D4 follows no redirect and keeps a redirect's
 * host only as "on the carrier allowlist" or "another host". D5 (the carrier
 * self-care page) ships only in the owner and rom builds and only against the fixed
 * login-free per-network allowlist; its body is never kept.
 *
 * The socket work is in [ProbeNet], which needs an Android [android.net.Network];
 * it has no JVM tests. The bounds are in [ProbePolicy], which is pure and tested.
 */

/** The fixed targets of the mobile-data ladder. No value here is ever read from a reply or a server. */
object ProbeTargets {
    /** D2a/D2b and D3c: a stable name to resolve and reach. OCD's own 204 host; a well-known name as the backup. */
    const val DNS_NAME = "connectivitycheck.gstatic.com"

    /** D2b: a public resolver IP to query over UDP 53 (Quad9), so "carrier DNS only" separates from "all DNS". */
    const val PUBLIC_RESOLVER = "9.9.9.9"

    /** D3a/D3b: anycast literals reached by IP (DNS bypassed), TCP 443. */
    const val IPV4_LITERAL = "9.9.9.9"
    const val IPV6_LITERAL = "2620:fe::fe"
    const val TLS_PORT = 443

    /** D4: a plain-HTTP 204 endpoint, redirects never followed. The host and path are fixed. */
    const val HTTP204_HOST = "connectivitycheck.gstatic.com"
    const val HTTP204_PATH = "/generate_204"
    const val HTTP_PORT = 80

    /**
     * D4: the per-network operator self-care / top-up hosts a redirect may point at. A redirect whose
     * host is one of these is kept as [RedirectHost.CARRIER_PORTAL]; any other host as
     * [RedirectHost.OTHER]; no redirect URL is ever stored. Keyed by home MCC+MNC.
     */
    private val PORTAL_HOSTS: Map<String, Set<String>> = mapOf(
        "26201" to setOf("pass.telekom.de"),
        "26206" to setOf("pass.telekom.de"),
        "26202" to setOf("center.vodafone.de", "pass.vodafone.de"),
        "26203" to setOf("start.o2online.de", "pass.o2online.de"),
        "26207" to setOf("start.o2online.de", "pass.o2online.de"),
    )

    /**
     * D5: the login-free carrier self-care pages fetched only in the owner and rom builds. The ruling
     * ships D5 with pass.telekom.de; center.vodafone.de joins only once a measured fetch confirms it
     * answers without a login, so it is not here yet. Only the status class is kept, never the body.
     */
    private val CARRIER_PAGE: Map<String, String> = mapOf(
        "26201" to "http://pass.telekom.de/",
        "26206" to "http://pass.telekom.de/",
    )

    /** Whether [host] (lower-cased, no port) is the operator's own portal for [mccmnc]. */
    fun isPortal(mccmnc: String?, host: String?): Boolean {
        val h = host?.substringBefore(':')?.trim()?.lowercase() ?: return false
        return PORTAL_HOSTS[mccmnc].orEmpty().any { it.equals(h, ignoreCase = true) }
    }

    /** The reduced redirect class: a kept portal host, any other host, or none. */
    fun redirectHost(mccmnc: String?, locationHost: String?): RedirectHost = when {
        locationHost.isNullOrBlank() -> RedirectHost.NONE
        isPortal(mccmnc, locationHost) -> RedirectHost.CARRIER_PORTAL
        else -> RedirectHost.OTHER
    }

    /** The D5 carrier page for [mccmnc], or null where none is on the allowlist (then D5 does not run). */
    fun carrierPage(mccmnc: String?): String? = mccmnc?.let { CARRIER_PAGE[it] }
}

/**
 * The bounds on when the mobile-data ladder may run, pure so they can be tested. The ruling:
 * never while roaming, never with mobile data off, never under the emergency and call refusals
 * (those are checked by the caller before this is consulted); automatic runs only in the owner
 * and rom builds, at most once per SIM per 6 hours, and only after a measured failure; the
 * read-only builds run only on a tap, with a short cooldown and no automatic run; a run never
 * includes the D6 throughput sample. D5 only in owner and rom.
 */
object ProbePolicy {
    /** Owner and rom: at most one automatic run per SIM per 6 hours. */
    val AUTO_EVERY_MS: Long = 6 * 60 * 60 * 1000L

    /** Read-only: a short cooldown between taps, so a burst of HTTP/DNS does not look like scanning. */
    val TAP_COOLDOWN_MS: Long = 2 * 60 * 1000L

    /** Why a run is refused now, or null when it may run. [roaming] and [dataOff] are the hard stops. */
    fun refusal(roaming: Boolean?, dataOff: Boolean): String? = when {
        roaming == null -> "whether the phone is roaming could not be read"
        roaming -> "the phone is roaming"
        dataOff -> "mobile data is switched off"
        else -> null
    }

    /**
     * Whether an automatic owner/rom run is due: only after a measured failure, and only when the
     * last run of this SIM was at least [AUTO_EVERY_MS] ago (or the clock went back).
     */
    fun autoDue(measuredFailure: Boolean, lastRunAtMs: Long?, nowMs: Long): Boolean =
        measuredFailure && (lastRunAtMs == null || nowMs - lastRunAtMs >= AUTO_EVERY_MS || nowMs < lastRunAtMs)

    /** Whether a read-only tap may run now: the short cooldown since the last tap has passed. */
    fun tapAllowed(lastTapAtMs: Long?, nowMs: Long): Boolean =
        lastTapAtMs == null || nowMs - lastTapAtMs >= TAP_COOLDOWN_MS || nowMs < lastTapAtMs
}
