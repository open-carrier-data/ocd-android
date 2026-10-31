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

/** The profiles OCD gives one SIM, generic to specific, in OCD's resolution order ([ResolutionOrder]). */
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
 * OCD's resolution order (rule round 45, item 3), as OcdCarrierSettings and
 * `tools/resolve_carrier_profiles.py --order android` sort a SIM's profiles,
 * generic to specific:
 * 1. Android's weight: lookup.json's `weight` for the SIM's network code, the
 *    score Android's CarrierResolver gives the fields the profile pins (MCC/MNC
 *    256, IMSI prefix 128, ICCID prefix 64, GID1 32, GID2 16, SPN 2, a
 *    carrier-ID selector by Android's rules behind the ID). Data without the
 *    field gets the same score from the match, a carrier-ID selector at the
 *    MCC/MNC-only weight, as the phone does without its carrier ID table. At
 *    equal weight a profile with a carrier-ID selector ranks above.
 * 2. Longer prefix or more fixed digits: IMSI (digits other than x), ICCID,
 *    GID1, GID2, each the shortest of the profile's values.
 * 3. An OCD profile above a fill-only one (`fill.` IDs; lookup.json has none).
 * 4. Fewer list values. 5. Fewer network codes.
 * 6. The profile ID in byte order.
 */
object ResolutionOrder {
    val STEPS = listOf("weight", "carrier_id", "prefix", "ocd_before_fill_only", "fewer_values", "fewer_networks", "profile_id")
    private const val MCCMNC = 256

    /** The score from the match alone, a carrier-ID selector at the MCC/MNC-only weight. */
    fun rawWeight(m: OcdMatch): Int = MCCMNC +
        (if (m.imsiPrefixPatterns.isNotEmpty()) 128 else 0) + (if (m.iccidPrefixes.isNotEmpty()) 64 else 0) +
        (if (m.gid1Prefixes.isNotEmpty()) 32 else 0) + (if (m.gid2Prefixes.isNotEmpty()) 16 else 0) + (if (m.spn.isNotEmpty()) 2 else 0)

    /**
     * Step 1 from Android's rules: [rules] gives the scores of the carrier ID table's rules for
     * a carrier ID on a network code (OCD's resolver computes lookup.json's weight this way).
     */
    fun weight(m: OcdMatch, mccmnc: String, rules: (Long, String) -> List<Int>): Int {
        val raw = rawWeight(m)
        if (m.androidCarrierIds.isEmpty()) return raw
        return m.androidCarrierIds.flatMap { id -> rules(id, mccmnc).ifEmpty { listOf(MCCMNC) } }.minOf { raw or it }
    }

    fun weight(p: OcdProfile, mccmnc: String): Int = p.weight[mccmnc] ?: rawWeight(p.match)

    class Key(val values: List<Comparable<*>>, val id: String)

    fun key(p: OcdProfile, mccmnc: String): Key {
        val m = p.match
        fun shortest(values: List<String>, imsi: Boolean = false): Int =
            values.minOfOrNull { v -> if (imsi) v.count { it != 'x' && it != 'X' } else v.length } ?: 0
        val count = m.spn.size + m.gid1Prefixes.size + m.gid2Prefixes.size + m.iccidPrefixes.size + m.imsiPrefixPatterns.size + m.androidCarrierIds.size
        return Key(
            listOf(
                weight(p, mccmnc), if (m.androidCarrierIds.isNotEmpty()) 1 else 0,
                shortest(m.imsiPrefixPatterns, imsi = true), shortest(m.iccidPrefixes), shortest(m.gid1Prefixes), shortest(m.gid2Prefixes),
                if (p.profileId.startsWith("fill.")) 0 else 1, -count, -m.mccmnc.size,
            ),
            p.profileId,
        )
    }

    /** The step that orders [a] below or above [b] (its sign), or null when equal. */
    fun compareStep(a: Key, b: Key): Pair<String, Int>? {
        val steps = listOf(0, 1, 2, 2, 2, 2, 3, 4, 5)
        for (i in a.values.indices) {
            @Suppress("UNCHECKED_CAST")
            val c = (a.values[i] as Comparable<Any>).compareTo(b.values[i])
            if (c != 0) return STEPS[steps[i]] to c
        }
        val c = compareUtf8(a.id, b.id)
        return if (c != 0) STEPS[6] to c else null
    }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    fun comparator(mccmnc: String): Comparator<OcdProfile> =
        Comparator { a, b -> compareStep(key(a, mccmnc), key(b, mccmnc))?.second ?: 0 }

    /** Why the last profile of an ordered stack serves: `default`, `only`, or the step above the one below it. */
    fun reason(stack: List<OcdProfile>, mccmnc: String): String = when (stack.size) {
        0 -> "default"
        1 -> "only"
        else -> compareStep(key(stack[stack.size - 2], mccmnc), key(stack.last(), mccmnc))?.first ?: "profile_id"
    }
}

/**
 * Port of `profile_matches` and `resolve` from OCD's
 * tools/resolve_carrier_profiles.py in the resolution order of rule round 45
 * (`--order android`, [ResolutionOrder]), limited to what a normal app can read.
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
        val order = ResolutionOrder.comparator(identity.mccmnc)
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
