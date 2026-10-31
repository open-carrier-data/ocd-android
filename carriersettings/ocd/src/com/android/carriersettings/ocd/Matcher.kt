/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * A SIM as onLoadConfig sees it (spec 4.1). The IMSI and ICCID live only in memory, to match:
 * this class has no toString with them, and nothing stores, logs, hashes or returns them.
 */
class SimId(
    val mcc: String,
    val mnc: String,
    val spn: String? = null,
    val imsi: String? = null,
    val gid1: String? = null,
    val gid2: String? = null,
    val iccid: String? = null,
    val carrierId: Int = -1,
    val specificCarrierId: Int = -1,
    /**
     * The phone's Android carrier ID table, whose rules weigh a profile's carrier-ID selector in
     * the resolution order (OCD rule round 45, item 3). Null: every carrier-ID selector counts at
     * the MCC/MNC-only weight.
     */
    val carrierIdTable: CarrierIdTable? = null,
) {
    val network: String get() = "$mcc-$mnc"

    /** No SIM: MCC, MNC, SPN, IMSI and GID1 all empty (LineageOS's CarrierConfigService test). */
    val noSim: Boolean get() = mcc.isEmpty() && mnc.isEmpty() && spn.isNullOrEmpty() && imsi.isNullOrEmpty() && gid1.isNullOrEmpty()

    override fun toString(): String = "SimId(network=$network)"

    companion object {
        val NO_SIM = SimId("", "")
    }
}

/**
 * The phone's Android carrier ID table (`content://carrier_id/all`, CarrierIdProvider), as the
 * resolution order reads it. The Android side reads the provider ([OcdAndroid]); tests give
 * rows of the AOSP carrier list.
 */
interface CarrierIdTable {
    /**
     * CarrierResolver's score of each rule the table holds for [carrierId] on [mccmnc] (MCC and
     * MNC without a dash): empty when it holds none, null when the table cannot be read.
     */
    fun ruleWeights(carrierId: Int, mccmnc: String): List<Int>?

    /** A carrier-ID selector counts at the MCC/MNC-only weight: no rule there, or no table ([unreadable]). */
    fun fallback(carrierId: Int, mccmnc: String, unreadable: Boolean) {}

    companion object {
        /** CarrierResolver.CarrierMatchingRule's score of a rule, from the provider's columns it has (non-null). */
        val COLUMN_WEIGHTS = linkedMapOf(
            "mccmnc" to 256, "imsi_prefix_xpattern" to 128, "iccid_prefix" to 64, "gid1" to 32, "gid2" to 16,
            "plmn" to 8, "privilege_access_rule" to 4, "spn" to 2, "apn" to 1,
        )

        fun weightOf(columns: Set<String>): Int = COLUMN_WEIGHTS.entries.sumOf { (c, w) -> if (c in columns) w else 0 }
    }
}

/**
 * OCD's resolution order (rule round 45, item 3), generic to specific:
 * 1. Android's weight: CarrierResolver's score of the fields the profile pins (MCC/MNC 256, IMSI
 *    prefix 128, ICCID prefix 64, GID1 32, GID2 16, SPN 2). A carrier-ID selector stays a match
 *    condition; it adds the fields of the phone's carrier ID table's rules behind the ID on the
 *    SIM's network (the lowest-weight rule; MCC/MNC alone, logged, when there is none or the table
 *    cannot be read), and at equal weight it ranks above a raw profile.
 * 2. Longer prefix or more fixed digits: IMSI (digits other than x), ICCID, GID1, GID2, each the
 *    shortest of the profile's values.
 * 3. An OCD profile above a fill-only one.
 * 4. Fewer list values. 5. Fewer network codes.
 * 6. The profile ID in byte order, which OCD's conflict checker keeps from deciding between
 *    profiles whose served values differ.
 * All of it comes from profiles.json's match and kind, so the dataset format is unchanged.
 */
object Order {
    val STEPS = listOf("weight", "carrier_id", "prefix", "ocd_before_fill_only", "fewer_values", "fewer_networks", "profile_id")
    const val MCCMNC_WEIGHT = 256

    fun rawWeight(m: Match): Int = MCCMNC_WEIGHT +
        (if (m.imsi != null) 128 else 0) + (if (m.iccid != null) 64 else 0) + (if (m.gid1 != null) 32 else 0) +
        (if (m.gid2 != null) 16 else 0) + (if (m.spn != null) 2 else 0)

    /** Step 1 on network [mccmnc] (no dash). */
    fun weight(m: Match, mccmnc: String, table: CarrierIdTable?): Int {
        val raw = rawWeight(m)
        val ids = m.carrierIds ?: return raw
        var weight: Int? = null
        for (id in ids) {
            val rules = table?.ruleWeights(id, mccmnc)
            val used = if (rules.isNullOrEmpty()) {
                table?.fallback(id, mccmnc, unreadable = rules == null)
                listOf(MCCMNC_WEIGHT)
            } else rules
            for (rule in used) {
                val combined = raw or rule
                weight = if (weight == null) combined else minOf(weight, combined)
            }
        }
        return weight ?: raw
    }

    fun fixedLength(imsi: Boolean, value: String): Int = if (imsi) value.count { it != 'x' && it != 'X' } else value.length

    class Key(
        val weight: Int,
        val carrierId: Int,
        val prefix: IntArray,
        val ocd: Int,
        val values: Int,
        val networks: Int,
        val id: String,
    ) {
        /** The first step of [STEPS] where [this] and [other] differ, or null; and the sign of the comparison. */
        fun compareStep(other: Key): Pair<String, Int>? {
            fun step(i: Int, c: Int): Pair<String, Int>? = if (c != 0) STEPS[i] to c else null
            step(0, weight.compareTo(other.weight))?.let { return it }
            step(1, carrierId.compareTo(other.carrierId))?.let { return it }
            for (i in prefix.indices) step(2, prefix[i].compareTo(other.prefix[i]))?.let { return it }
            step(3, ocd.compareTo(other.ocd))?.let { return it }
            step(4, other.values.compareTo(values))?.let { return it }
            step(5, other.networks.compareTo(networks))?.let { return it }
            step(6, compareBytes(id, other.id))?.let { return it }
            return null
        }
    }

    fun key(m: Match, mccmnc: String, fillOnly: Boolean, id: String, table: CarrierIdTable?): Key {
        fun shortest(values: List<String>?, imsi: Boolean = false): Int = values?.minOf { fixedLength(imsi, it) } ?: 0
        return Key(
            weight(m, mccmnc, table),
            if (m.carrierIds != null) 1 else 0,
            intArrayOf(shortest(m.imsi, imsi = true), shortest(m.iccid), shortest(m.gid1), shortest(m.gid2)),
            if (fillOnly) 0 else 1,
            listOfNotNull(m.spn, m.imsi, m.gid1, m.gid2, m.iccid).sumOf { it.size } + (m.carrierIds?.size ?: 0),
            m.mccmnc.size,
            id,
        )
    }

    val COMPARATOR: Comparator<Key> = Comparator { a, b -> a.compareStep(b)?.second ?: 0 }

    /** Why the last profile of an ordered stack serves: `default`, `only`, or the step above the one below it. */
    fun reason(keys: List<Key>): String = when (keys.size) {
        0 -> "default"
        1 -> "only"
        else -> keys[keys.size - 2].compareStep(keys.last())?.first ?: "profile_id"
    }
}

/**
 * OCD's matcher (spec 4.2): MCC and MNC compared exactly as strings, then TelephonyProvider's
 * MVNO semantics per dimension (UiccController.mvnoMatches), with Java's char-by-char
 * equalsIgnoreCase (Kotlin's `equals(ignoreCase = true)` on the JVM is exactly that).
 */
object Matcher {
    fun eqIgnoreCase(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)

    /** GID1[0, len(g)) equals g ignoring case, as TelephonyProvider's substring + equalsIgnoreCase. */
    fun prefixIgnoreCase(prefix: String, value: String?): Boolean =
        value != null && value.length >= prefix.length && value.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

    /** UiccController.imsiMatches: len(p) <= len(IMSI), each p[i] is x, X or IMSI[i]. */
    fun imsiMatches(pattern: String, imsi: String?): Boolean {
        if (imsi == null || pattern.isEmpty() || pattern.length > imsi.length) return false
        for (i in pattern.indices) {
            val c = pattern[i]
            if (c != 'x' && c != 'X' && c != imsi[i]) return false
        }
        return true
    }

    fun iccidPrefix(prefix: String, iccid: String?): Boolean = iccid != null && iccid.startsWith(prefix)

    fun networkMatches(match: Match, sim: SimId): Boolean = match.mccmnc.any { it.mcc == sim.mcc && it.mnc == sim.mnc }

    fun matches(match: Match, sim: SimId): Boolean {
        if (!networkMatches(match, sim)) return false
        match.spn?.let { v -> if (v.none { eqIgnoreCase(it, sim.spn) }) return false }
        match.imsi?.let { v -> if (v.none { imsiMatches(it, sim.imsi) }) return false }
        match.gid1?.let { v -> if (v.none { prefixIgnoreCase(it, sim.gid1) }) return false }
        match.gid2?.let { v -> if (v.none { prefixIgnoreCase(it, sim.gid2) }) return false }
        match.iccid?.let { v -> if (v.none { iccidPrefix(it, sim.iccid) }) return false }
        match.carrierIds?.let { v ->
            val ids = listOf(sim.carrierId, sim.specificCarrierId).filter { it >= 0 }
            if (ids.none { it in v }) return false
        }
        return true
    }

    /**
     * The stack: matching profiles generic to specific in the resolution order ([Order], rule
     * round 45); the serving profile is the last. A fill-only profile's ID starts with `fill.`
     * (the reader refuses one that does not), which [fillOnlyOf] reads by default.
     */
    fun <P> stack(
        candidates: List<P>,
        sim: SimId,
        matchOf: (P) -> Match,
        idOf: (P) -> String,
        fillOnlyOf: (P) -> Boolean = { idOf(it).startsWith("fill.") },
    ): List<P> = ordered(candidates.filter { matches(matchOf(it), sim) }, sim, matchOf, idOf, fillOnlyOf).map { it.first }

    /** Matching profiles with their [Order.Key]s, in the resolution order. */
    fun <P> ordered(
        matching: List<P>,
        sim: SimId,
        matchOf: (P) -> Match,
        idOf: (P) -> String,
        fillOnlyOf: (P) -> Boolean = { idOf(it).startsWith("fill.") },
    ): List<Pair<P, Order.Key>> {
        val mccmnc = sim.mcc + sim.mnc
        return matching.map { it to Order.key(matchOf(it), mccmnc, fillOnlyOf(it), idOf(it), sim.carrierIdTable) }
            .sortedWith { a, b -> Order.COMPARATOR.compare(a.second, b.second) }
    }

    fun stack(profiles: List<Profile>, sim: SimId): List<Profile> = stack(profiles, sim, { it.match }, { it.id }, { it.fillOnly })

    /** A fill item's condition for this SIM (2.5.5), with the matcher's own primitives. */
    fun holds(condition: When?, sim: SimId): Boolean = when (condition?.kind) {
        null -> true
        "spn" -> eqIgnoreCase(sim.spn, condition.value)
        "gid1" -> prefixIgnoreCase(condition.value, sim.gid1)
        "iccid" -> iccidPrefix(condition.value, sim.iccid)
        "imsi" -> imsiMatches(condition.value, sim.imsi)
        else -> false
    }

    /** The picked fill entry, and the entries the SIM reached that the lane does not hold (skipped). */
    data class FillPick(val item: FillItem?, val skippedMissing: List<String>)

    fun pickFill(fill: List<FillItem>, sim: SimId, exists: (String) -> Boolean): FillPick {
        val skipped = ArrayList<String>()
        for (item in fill) {
            if (!holds(item.condition, sim)) continue
            if (exists(item.entry)) return FillPick(item, skipped)
            skipped += item.entry
        }
        return FillPick(null, skipped)
    }
}

/**
 * TelephonyProvider's getSubscriptionMatchingAPNListSynchronized (23.2 lines 4944 to 5050) over
 * composed rows, which are all unedited (spec 4.6).
 */
object ApnSelect {
    /** UiccController.mvnoMatches with TelephonyProvider's type mapping (unknown or empty: no match). */
    fun mvnoMatches(type: String?, data: String, sim: SimId): Boolean = when (type?.lowercase(java.util.Locale.ROOT)) {
        "spn" -> sim.spn != null && sim.spn.equals(data, ignoreCase = true)
        "imsi" -> Matcher.imsiMatches(data, sim.imsi)
        "gid" -> sim.gid1 != null && sim.gid1.length >= data.length && sim.gid1.regionMatches(0, data, 0, data.length, ignoreCase = true)
        "iccid" -> sim.iccid != null && javaSplit(data).any { sim.iccid.startsWith(it) }
        else -> false
    }

    /** Java's String.split(",") : trailing empty strings removed, a lone "" kept. */
    fun javaSplit(s: String): List<String> {
        val parts = s.split(',').toMutableList()
        if (s.isEmpty()) return listOf("")
        while (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        if (parts.size == 1 && parts[0].isEmpty()) return emptyList()
        return parts
    }

    fun select(rows: List<ApnXml.Row>, sim: SimId): List<ApnXml.Row> {
        val mccmnc = sim.mcc + sim.mnc
        val carrierId = sim.specificCarrierId
        val current = ArrayList<ApnXml.Row>()
        val parent = ArrayList<ApnXml.Row>()
        val byCarrierId = ArrayList<ApnXml.Row>()
        val byCarrierIdNonMatching = ArrayList<ApnXml.Row>()
        for (row in rows) {
            val numeric = row.numeric
            val type = row.attrs["mvno_type"]
            val data = row.attrs["mvno_match_data"].orEmpty()
            val isMvno = numeric.isNotEmpty() && numeric == mccmnc && mvnoMatches(type, data, sim)
            val isMno = numeric.isNotEmpty() && numeric == mccmnc && type.isNullOrEmpty()
            val rowCid = row.attrs["carrier_id"]
            val isCid = !rowCid.isNullOrEmpty() && rowCid == carrierId.toString() && carrierId != -1
            when {
                isMvno -> current += row
                isMno -> parent += row
                isCid -> if (numeric.isEmpty()) byCarrierId += row else byCarrierIdNonMatching += row
            }
        }
        val result = when {
            current.isNotEmpty() -> current
            parent.isNotEmpty() -> parent
            else -> byCarrierIdNonMatching
        }
        return result + byCarrierId
    }

    /** The most specific profile of the stack with rows (a profile without rows inherits its parent's). */
    fun apnProfile(stackIds: List<String>, rowsOf: (String) -> Int): String? = stackIds.lastOrNull { rowsOf(it) > 0 }

    /** `same` when every selected row lists apn_profile, else `mismatch`. */
    fun coupling(selected: List<ApnXml.Row>, apnProfile: String?, profilesOfRow: (ApnXml.Row) -> List<String>): String =
        if (apnProfile != null && selected.isNotEmpty() && selected.all { apnProfile in profilesOfRow(it) }) "same" else if (selected.isEmpty() && apnProfile == null) "same" else "mismatch"
}
