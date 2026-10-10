package io.github.open_carrier_data.check.core

import java.util.Locale

/**
 * What a normal app can read about a SIM for OCD matching. IMSI, ICCID and
 * GID2 are never part of it: Android hides them from normal apps, and this app
 * does not want them.
 *
 * Null means "not read" (for GID1: the phone status permission is missing).
 * An empty string or an empty set means "read, and the SIM has none".
 */
data class SimIdentity(
    val mccmnc: String,
    val spn: String?,
    val gid1: String?,
    /** The SIM's Android carrier ID and specific carrier ID (TelephonyManager), without -1. */
    val carrierIds: Set<Long>?,
)

/** A profile that may apply but matches on something this app cannot read. */
data class NotCheckable(val profile: OcdProfile, val needs: List<String>)

/** The profiles OCD gives one SIM, generic to specific, as resolve_carrier_profiles.resolve orders them. */
data class Resolution(
    val identity: SimIdentity,
    val stack: List<OcdProfile>,
    val notCheckable: List<NotCheckable>,
) {
    /** docs/consume.md: report the most specific profile's value. */
    val mostSpecific: OcdProfile? get() = stack.lastOrNull()

    fun answer(key: String): CapabilityValue = mostSpecific?.capability(key) ?: CapabilityValue.UNKNOWN

    /** Values broader profiles in the stack give where they are not unknown (the host network's values). */
    fun broaderValues(key: String): List<Pair<OcdProfile, CapabilityValue>> =
        stack.dropLast(1).map { it to it.capability(key) }.filter { it.second != CapabilityValue.UNKNOWN }
}

/**
 * Port of `profile_matches`, `specificity` and `resolve` from OCD's
 * tools/resolve_carrier_profiles.py, limited to what a normal app can read.
 *
 * Two deliberate differences, both documented in the README:
 * - The SIM has two carrier IDs (carrier ID and specific carrier ID). A
 *   profile's android_carrier_ids match when either is listed, as Android's
 *   CarrierConfig app compares `cid` (DefaultCarrierConfigService, case "cid").
 *   The Python resolver takes one ID.
 * - A dimension the app cannot read (GID2, ICCID, IMSI, and GID1 without the
 *   permission) does not just fail: the profile is kept aside as "not
 *   checkable", so the app can say a more specific profile may apply. The
 *   matched stack is the same as the resolver's for the same inputs.
 */
object Matcher {
    private enum class Outcome { MATCH, MISMATCH, UNREADABLE }

    fun resolve(bundle: OcdBundle, identity: SimIdentity): Resolution =
        resolve(bundle.profilesFor(identity.mccmnc), identity)

    fun resolve(profiles: List<OcdProfile>, identity: SimIdentity): Resolution {
        val stack = mutableListOf<OcdProfile>()
        val aside = mutableListOf<NotCheckable>()
        for (profile in profiles) {
            if (identity.mccmnc !in profile.match.mccmnc) continue
            val outcomes = dimensions(profile.match, identity)
            when {
                outcomes.any { it.second == Outcome.MISMATCH } -> Unit
                outcomes.all { it.second == Outcome.MATCH } -> stack += profile
                else -> aside += NotCheckable(
                    profile,
                    outcomes.filter { it.second == Outcome.UNREADABLE }.map { it.first },
                )
            }
        }
        val order = compareBy<OcdProfile>({ it.match.specificity }, { it.profileId })
        return Resolution(
            identity = identity,
            stack = stack.sortedWith(order),
            notCheckable = aside.sortedWith(compareBy(order) { it.profile }),
        )
    }

    /** One outcome per dimension the profile uses (mccmnc is checked by the caller). */
    private fun dimensions(match: OcdMatch, identity: SimIdentity): List<Pair<String, Outcome>> = buildList {
        if (match.gid1Prefixes.isNotEmpty()) add("gid1" to prefix(match.gid1Prefixes, identity.gid1))
        if (match.gid2Prefixes.isNotEmpty()) add("gid2" to Outcome.UNREADABLE)
        if (match.iccidPrefixes.isNotEmpty()) add("iccid" to Outcome.UNREADABLE)
        if (match.spn.isNotEmpty()) add("spn" to spn(match.spn, identity.spn))
        if (match.imsiPrefixPatterns.isNotEmpty()) add("imsi" to Outcome.UNREADABLE)
        if (match.androidCarrierIds.isNotEmpty()) add("carrier_id" to carrierId(match.androidCarrierIds, identity.carrierIds))
    }

    /** any_prefix_matches: a case-folded prefix of the SIM's value. */
    private fun prefix(prefixes: List<String>, value: String?): Outcome = when {
        value == null -> Outcome.UNREADABLE
        prefixes.any { value.casefold().startsWith(it.casefold()) } -> Outcome.MATCH
        else -> Outcome.MISMATCH
    }

    /** The SPN equals one of the profile's SPNs, ignoring case. */
    private fun spn(expected: List<String>, value: String?): Outcome = when {
        value == null -> Outcome.UNREADABLE
        expected.any { it.casefold() == value.casefold() } -> Outcome.MATCH
        else -> Outcome.MISMATCH
    }

    private fun carrierId(expected: List<Long>, ids: Set<Long>?): Outcome = when {
        ids == null -> Outcome.UNREADABLE
        ids.any { it in expected } -> Outcome.MATCH
        else -> Outcome.MISMATCH
    }
}

/**
 * Close to Python's str.casefold() for SIM names: full upper-casing turns
 * "ß" into "SS", lower-casing then folds; a final sigma folds to σ as in Python.
 */
internal fun String.casefold(): String =
    uppercase(Locale.ROOT).lowercase(Locale.ROOT).replace('ς', 'σ')
