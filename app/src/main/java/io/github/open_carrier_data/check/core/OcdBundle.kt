package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/** The data asset written by tools/build_bundle.py from OCD's public lookup.json. */
@Serializable
data class OcdBundle(
    val format: String,
    val source: BundleSource,
    @SerialName("capability_keys") val capabilityKeys: List<String>,
    val limits: Limits,
    val profiles: List<OcdProfile>,
) {
    private val byMccMnc: Map<String, List<OcdProfile>> by lazy {
        val index = HashMap<String, MutableList<OcdProfile>>()
        for (profile in profiles) {
            for (code in profile.match.mccmnc) index.getOrPut(code) { mutableListOf() } += profile
        }
        index
    }

    /** Every profile whose match lists this network code, in file order. */
    fun profilesFor(mccmnc: String): List<OcdProfile> = byMccMnc[mccmnc].orEmpty()

    /** True after the snapshot's stale_after day. */
    fun isStale(today: LocalDate): Boolean =
        runCatching { today.isAfter(LocalDate.parse(source.staleAfter)) }.getOrDefault(true)

    companion object {
        const val FORMAT = "ocd-check-bundle.v1"
        // Not ".gz": the Android build would gunzip the asset and drop the suffix.
        const val ASSET_NAME = "ocd-lookup.json.gzip"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): OcdBundle {
            val bundle = json.decodeFromString(serializer(), text)
            require(bundle.format == FORMAT) { "unknown bundle format ${bundle.format}" }
            require(bundle.source.resolutionOrder == "generic_to_specific") {
                "unknown resolution order ${bundle.source.resolutionOrder}"
            }
            return bundle
        }

        fun readGzip(input: InputStream): OcdBundle =
            GZIPInputStream(input).use { parse(it.readBytes().decodeToString()) }
    }
}

@Serializable
data class BundleSource(
    val repository: String,
    val commit: String,
    @SerialName("commit_date") val commitDate: String,
    @SerialName("checks_through") val checksThrough: String,
    @SerialName("stale_after") val staleAfter: String,
    @SerialName("data_digest") val dataDigest: String? = null,
    @SerialName("lookup_schema_version") val lookupSchemaVersion: Int? = null,
    @SerialName("resolution_order") val resolutionOrder: String,
)

/** Text from the "Limits to read before you ship" section of OCD's README, shipped with the data. */
@Serializable
data class Limits(
    @SerialName("readme_section") val readmeSection: String,
    @SerialName("value_meanings") val valueMeanings: Map<String, String>,
    val gates: String,
    @SerialName("missing_switch") val missingSwitch: String,
    @SerialName("not_verified") val notVerified: String,
    @SerialName("tables_not_tests") val tablesNotTests: String,
    @SerialName("mvno_merge") val mvnoMerge: String,
) {
    fun meaning(value: CapabilityValue): String = valueMeanings[value.wire].orEmpty()
}

@Serializable
data class OcdProfile(
    @SerialName("profile_id") val profileId: String,
    @SerialName("display_name") val displayName: String,
    val match: OcdMatch,
    val capabilities: Map<String, String> = emptyMap(),
    @SerialName("newest_entry") val newestEntry: String? = null,
) {
    fun capability(key: String): CapabilityValue = CapabilityValue.fromWire(capabilities[key])
}

/** OCD's match dimensions: OR within a list, AND between dimensions. */
@Serializable
data class OcdMatch(
    val mccmnc: List<String> = emptyList(),
    val spn: List<String> = emptyList(),
    @SerialName("gid1_prefixes") val gid1Prefixes: List<String> = emptyList(),
    @SerialName("gid2_prefixes") val gid2Prefixes: List<String> = emptyList(),
    @SerialName("iccid_prefixes") val iccidPrefixes: List<String> = emptyList(),
    @SerialName("imsi_prefix_patterns") val imsiPrefixPatterns: List<String> = emptyList(),
    @SerialName("android_carrier_ids") val androidCarrierIds: List<Long> = emptyList(),
) {
    /** resolve_carrier_profiles.specificity: how many of the six optional dimensions are set. */
    val specificity: Int
        get() = listOf(
            gid1Prefixes, gid2Prefixes, iccidPrefixes, imsiPrefixPatterns, spn, androidCarrierIds,
        ).count { it.isNotEmpty() }

    /** Names of the dimensions this match uses, mccmnc first, in OCD's key spelling. */
    val dimensionNames: List<String>
        get() = buildList {
            add("mccmnc")
            if (spn.isNotEmpty()) add("spn")
            if (gid1Prefixes.isNotEmpty()) add("gid1_prefixes")
            if (gid2Prefixes.isNotEmpty()) add("gid2_prefixes")
            if (iccidPrefixes.isNotEmpty()) add("iccid_prefixes")
            if (imsiPrefixPatterns.isNotEmpty()) add("imsi_prefix_patterns")
            if (androidCarrierIds.isNotEmpty()) add("android_carrier_ids")
        }
}
