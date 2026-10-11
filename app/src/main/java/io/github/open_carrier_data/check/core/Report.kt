package io.github.open_carrier_data.check.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The report and whether it may leave the app. */
data class ReportResult(val json: JsonObject, val text: String, val hits: List<PrivacyScan.Hit>) {
    /** Copy and share are offered only when the scan found nothing. */
    val shareable: Boolean get() = hits.isEmpty()
}

/**
 * The redacted report the user can read, copy or share.
 *
 * Only allowlisted keys (see [allowedPaths]); no free text (findings are
 * codes); a date, never a time of day; CarrierConfig only as a per-key
 * comparison with OCD (`matches_ocd`), never as values or a dump. The report
 * has no field for IMSI, ICCID, IMEI, TAC, a phone number, location, cell
 * IDs, the build fingerprint, build user or host, the baseband, APN
 * credentials or anything emergency-related.
 */
object Report {
    const val SCHEMA = "ocd-check-report.v1"
    const val ACCESS_TIER = "normal_app"

    private val pretty = Json { prettyPrint = true }

    fun build(
        readings: PhoneReadings,
        resolution: Resolution?,
        findings: List<Finding>,
        bundle: OcdBundle,
        appVersion: String,
        includeGid1: Boolean,
    ): ReportResult {
        val json = buildJsonObject {
            put("schema", SCHEMA)
            putJsonObject("app") {
                put("name", "OCD Check")
                put("version", appVersion)
                put("access_tier", ACCESS_TIER)
            }
            putJsonObject("ocd_data") {
                put("source_commit", bundle.source.commit)
                put("source_commit_date", bundle.source.commitDate)
                put("checks_through", bundle.source.checksThrough)
                put("stale_after", bundle.source.staleAfter)
            }
            put("checked_on", readings.checkedOn.toString())
            put("phone_writes_performed", "none")
            putJsonObject("device") {
                put("model", readings.device.model)
                put("codename", readings.device.codename)
                put("android_major", readings.device.androidMajor)
                put("rom_family", readings.device.romFamily.wire)
            }
            readings.sim?.let { sim(it, includeGid1) }
            readings.network?.let { network(it) }
            readings.carrierSettings?.let { carrierSettings(it, resolution) }
            readings.dataConnections?.let { connections ->
                putJsonArray("data_connections") {
                    for (connection in connections) {
                        add(buildJsonObject {
                            connection.apn?.let { put("apn", it) }
                            putJsonArray("serves") { connection.serves.forEach { add(it) } }
                            put("set_up_over", Radio.wire(connection.setUpOver))
                        })
                    }
                }
            }
            put("ims_registration", Findings.NEEDS_DEEP_CHECK)
            resolution?.let { ocd(it, bundle) }
            putJsonArray("findings") { findings.forEach { add(it.code) } }
        }
        val hits = PrivacyScan.scan(json) + unexpectedKeys(json).map { PrivacyScan.Hit(it, "key_not_allowlisted") }
        return ReportResult(json, pretty.encodeToString(JsonObject.serializer(), json), hits)
    }

    private fun JsonObjectBuilder.sim(sim: SimReading, includeGid1: Boolean) = putJsonObject("sim") {
        sim.homeMccMnc?.let { put("home_mccmnc", it) }
        sim.spn?.let { put("spn", it) }
        sim.carrierId?.let { put("android_carrier_id", it) }
        sim.carrierName?.let { put("android_carrier_name", it) }
        sim.specificCarrierId?.let { put("specific_carrier_id", it) }
        sim.specificCarrierName?.let { put("specific_carrier_name", it) }
        if (sim.gid1Status == Gid1Status.READ && sim.gid1 != null) {
            if (includeGid1) put("gid1", sim.gid1)
            put("gid1_status", if (includeGid1) "included" else "left_out_by_user")
        } else {
            put("gid1_status", sim.gid1Status.wire)
        }
    }

    private fun JsonObjectBuilder.network(network: NetworkReading) = putJsonObject("network") {
        network.servingMccMnc?.let { put("serving_mccmnc", it) }
        network.roaming?.let { put("roaming", it) }
        put("data_network", Radio.wire(network.psWwanType?.takeIf { it != 0 } ?: network.dataNetworkType))
        network.voiceNetworkType?.let { put("voice_network", Radio.wire(it)) }
        put("five_g", FiveG.classify(network).wire)
        put("five_g_icon", Radio.iconWire(network.iconOverride))
        network.nrSignalReported?.let { put("nr_signal_reported", it) }
    }

    private fun JsonObjectBuilder.carrierSettings(settings: CarrierSettingsReading, resolution: Resolution?) =
        putJsonObject("carrier_settings") {
            put("readable", settings.readable)
            settings.identifiedCarrier?.let { put("identified_carrier", it) }
            if (!settings.readable) return@putJsonObject
            putJsonObject("keys") {
                for (key in CarrierKeys.booleans) {
                    val capability = key.capability ?: continue
                    val ocd = resolution?.takeIf { it.stack.isNotEmpty() }?.answer(capability)
                    putJsonObject(key.key) {
                        put("ocd_capability", capability)
                        put("ocd_value", ocd?.wire ?: "no_profile")
                        put("matches_ocd", ocd?.let { CarrierKeys.matchesOcd(settings.booleans[key.key], it) })
                    }
                }
            }
        }

    private fun JsonObjectBuilder.ocd(resolution: Resolution, bundle: OcdBundle) = putJsonObject("ocd") {
        putJsonArray("profiles") {
            for (profile in resolution.stack) {
                add(buildJsonObject {
                    put("profile_id", profile.profileId)
                    put("display_name", profile.displayName)
                    putJsonArray("match_keys") { profile.match.dimensionNames.forEach { add(it) } }
                    profile.newestEntry?.let { put("newest_entry", it) }
                })
            }
        }
        resolution.mostSpecific?.let { most ->
            put("answer_from", most.profileId)
            putJsonObject("capabilities") {
                // Only the known keys: a key a later snapshot adds stays out until the allowlist names it.
                for (key in Capabilities.labels.keys.filter { it in bundle.capabilityKeys }) {
                    put(key, resolution.answer(key).wire)
                }
            }
        }
        if (resolution.notCheckable.isNotEmpty()) {
            putJsonArray("not_checkable") {
                for (item in resolution.notCheckable) {
                    add(buildJsonObject {
                        put("profile_id", item.profile.profileId)
                        putJsonArray("needs") { item.needs.forEach { add(it) } }
                    })
                }
            }
        }
    }

    /** Every key path a report may hold. Array items are written as `[]`. */
    val allowedPaths: Set<String> by lazy {
        val fixed = listOf(
            "schema",
            "app", "app.name", "app.version", "app.access_tier",
            "ocd_data", "ocd_data.source_commit", "ocd_data.source_commit_date",
            "ocd_data.checks_through", "ocd_data.stale_after",
            "checked_on", "phone_writes_performed",
            "device", "device.model", "device.codename", "device.android_major", "device.rom_family",
            "sim", "sim.home_mccmnc", "sim.spn", "sim.android_carrier_id", "sim.android_carrier_name",
            "sim.specific_carrier_id", "sim.specific_carrier_name", "sim.gid1", "sim.gid1_status",
            "network", "network.serving_mccmnc", "network.roaming", "network.data_network",
            "network.voice_network", "network.five_g", "network.five_g_icon", "network.nr_signal_reported",
            "carrier_settings", "carrier_settings.readable", "carrier_settings.identified_carrier",
            "carrier_settings.keys",
            "data_connections", "data_connections[].apn", "data_connections[].serves",
            "data_connections[].set_up_over",
            "ims_registration",
            "ocd", "ocd.profiles", "ocd.profiles[].profile_id", "ocd.profiles[].display_name",
            "ocd.profiles[].match_keys", "ocd.profiles[].newest_entry",
            "ocd.answer_from", "ocd.capabilities",
            "ocd.not_checkable", "ocd.not_checkable[].profile_id", "ocd.not_checkable[].needs",
            "findings",
        )
        val settingKeys = CarrierKeys.booleans.flatMap { key ->
            val base = "carrier_settings.keys.${key.key}"
            listOf(base, "$base.ocd_capability", "$base.ocd_value", "$base.matches_ocd")
        }
        val capabilityKeys = Capabilities.labels.keys.map { "ocd.capabilities.$it" }
        (fixed + settingKeys + capabilityKeys).toSet()
    }

    /** Key paths in [json] that are not in [allowedPaths]. */
    fun unexpectedKeys(json: JsonElement): List<String> {
        val found = mutableListOf<String>()
        fun walk(element: JsonElement, path: String) {
            when (element) {
                is JsonObject -> for ((key, child) in element) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    if (childPath !in allowedPaths) found += "report.$childPath"
                    walk(child, childPath)
                }
                is JsonArray -> element.forEach { walk(it, "$path[]") }
                else -> Unit
            }
        }
        walk(json, "")
        return found
    }
}
