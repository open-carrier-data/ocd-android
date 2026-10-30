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

    /** The stack: matching profiles by (specificity, id in byte order); the serving profile is the last. */
    fun <P> stack(candidates: List<P>, sim: SimId, matchOf: (P) -> Match, idOf: (P) -> String): List<P> =
        candidates.filter { matches(matchOf(it), sim) }
            .sortedWith { a, b -> matchOf(a).specificity.compareTo(matchOf(b).specificity).takeIf { it != 0 } ?: compareBytes(idOf(a), idOf(b)) }

    fun stack(profiles: List<Profile>, sim: SimId): List<Profile> = stack(profiles, sim, { it.match }, { it.id })

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
