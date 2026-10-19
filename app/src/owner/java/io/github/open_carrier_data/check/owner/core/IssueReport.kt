package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.PrivacyScan
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/**
 * The owner's "upload": a GitHub issue URL on OCD's public repository,
 * prefilled for its "Wrong or missing carrier data" form. The app only opens
 * the URL in the browser; the owner reads it and submits it himself. Nothing
 * is sent by the app. The text holds no IMSI, ICCID, IMEI, number, SIP URI or
 * address, and [PrivacyScan] checks it again before the URL is built. A
 * result never moves an OCD value by itself (round 23 decision 5).
 */
object IssueReport {
    const val NEW_ISSUE = "${ReleaseSource.REPOSITORY_URL}/issues/new"
    const val TEMPLATE = "wrong-or-missing-carrier-data.yml"

    /** GitHub refuses very long URLs; the evidence is cut to fit. */
    const val MAX_URL = 7_500

    private val COUNTRIES = mapOf("262" to "Germany", "232" to "Austria", "228" to "Switzerland", "204" to "Netherlands", "208" to "France")

    fun country(mccmnc: String?): String = mccmnc?.take(3)?.let { COUNTRIES[it] ?: "MCC $it" } ?: "unknown"

    fun title(run: RunResult): String =
        "[carrier-data] OCD Check owner test: ${run.simName ?: "SIM"} (${run.sim?.mccmnc ?: "?"}) on ${run.device ?: "phone"}"

    fun evidence(run: RunResult): String = buildString {
        append("Measured by the OCD Check owner build (local, non-persistent CarrierConfig overrides, reverted with proof unless kept).\n")
        append("Release: ").append(run.releaseTag ?: "?").append("; profile: ").append(run.profileId ?: "none").append('\n')
        append("Stack: ").append(run.stack ?: "?").append('\n')
        append("| Feature | Phone | OCD (basis) | On | Off | Best | Applied |\n|---|---|---|---|---|---|---|\n")
        for (f in run.features) {
            append("| ").append(f.feature.label)
            append(" | ").append(onOff(f.baseline))
            append(" | ").append(onOff(f.ocd.value)).append(" (").append(f.ocd.basis)
            f.ocd.recordSource?.let { append(", ").append(it).append('/').append(f.ocd.recordStack) }
            append(")")
            append(" | ").append(cell(if (f.baseline == true) f.baselineWorks else f.onWorks, if (f.baseline == true) "phone value" else f.onReason))
            append(" | ").append(cell(if (f.baseline == false) f.baselineWorks?.not() else f.offWorks, if (f.baseline == false) "phone value" else f.offReason))
            append(" | ").append(onOff(f.best))
            append(" | ").append(if (f.applied) "yes" else "no")
            f.refused?.let { append(" (refused: ").append(it).append(')') }
            append(" |\n")
        }
        if (run.apns.isNotEmpty()) {
            append("\nAPN rows (internet):\n")
            for (a in run.apns) {
                append("- ").append(a.label).append(": ").append(a.status)
                a.dataWorks?.let { append(", data ").append(if (it) "validated" else "not validated") }
                if (a.kept) append(", kept")
                append('\n')
            }
        }
        append("\n\"On\" means IMS registered with the feature's service (T1), not a call test. A voice off counts only with a call measured.")
    }

    fun problem(run: RunResult): String {
        val differs = run.features.filter { it.best != null && it.ocd.value != null && it.best != it.ocd.value }
        return if (differs.isEmpty()) {
            "No difference found: where OCD gives a value, the best working value on this phone matches it."
        } else {
            "The best working value on this phone differs from OCD's for: " +
                differs.joinToString { "${it.feature.label} (OCD ${onOff(it.ocd.value)}, measured best ${onOff(it.best)})" }
        }
    }

    /** The prefilled issue URL, or null when the privacy scan finds an identifier. */
    fun url(run: RunResult): String? {
        val fields = linkedMapOf(
            "template" to TEMPLATE,
            "title" to title(run),
            "carrier" to (run.simName ?: "unknown"),
            "country" to country(run.sim?.mccmnc),
            "problem" to problem(run),
            "safe_match" to "MCC/MNC: ${run.sim?.mccmnc ?: "?"}" + (run.sim?.carrierId?.let { ", Android carrier ID: $it" } ?: ""),
            "device_os" to "${run.device ?: "?"}, ${run.stack ?: "?"}",
            "test_date" to Instant.ofEpochMilli(run.startedMs).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
            "evidence" to evidence(run),
        )
        for ((name, value) in fields) {
            if (PrivacyScan.scan(JsonPrimitive(value), name).isNotEmpty()) return null
        }
        fun build(map: Map<String, String>) = NEW_ISSUE + "?" + map.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        var url = build(fields)
        if (url.length > MAX_URL) {
            val over = url.length - MAX_URL + 200
            val evidence = fields.getValue("evidence")
            fields["evidence"] = evidence.take((evidence.length - over / 3).coerceAtLeast(200)) + "\n(cut to fit the URL)"
            url = build(fields)
        }
        return url
    }

    private fun onOff(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "-"
    }

    private fun cell(works: Boolean?, reason: String?): String = when (works) {
        true -> "works" + (reason?.let { " ($it)" } ?: "")
        false -> "fails" + (reason?.let { " ($it)" } ?: "")
        null -> "not tested" + (reason?.let { " ($it)" } ?: "")
    }
}
