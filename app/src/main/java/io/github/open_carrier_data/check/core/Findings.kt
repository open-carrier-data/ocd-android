package io.github.open_carrier_data.check.core

/**
 * One plain-language finding. The code is a fixed machine string and is the
 * only part that goes into the report; the text stays on the screen.
 */
data class Finding(val code: String, val text: String)

/**
 * Port of phone_check.py `compare()` for what a normal app can see.
 *
 * Rules (round 9 judges, app conditions for stage 2):
 * - A finding compares phone readings with OCD values. It never says OCD is
 *   wrong, that a carrier does not support something, or that a feature works.
 * - No operator service-status claim is hard-coded. Context about gates is
 *   quoted from the README Limits text that ships in the data bundle.
 * - IMS registration is invisible to a normal app: say it needs the deep
 *   check, never guess. With the deep check, its findings take that place
 *   ([DeepFindings]).
 */
object Findings {
    const val NEEDS_DEEP_CHECK = "needs_deep_check"

    fun compare(readings: PhoneReadings, resolution: Resolution?, bundle: OcdBundle): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (bundle.isStale(readings.checkedOn)) {
            findings += Finding(
                "data_stale",
                "The OCD data in this app is past its stale_after date (${bundle.source.staleAfter}). " +
                    "Update the app before you rely on it.",
            )
        }
        if (!readings.hasTelephony) {
            return findings + Finding("no_telephony", "This device has no mobile phone function: nothing to compare.")
        }
        val sim = readings.sim ?: return findings + Finding("no_sim", "No active SIM: nothing to compare.")
        if (resolution == null) {
            return findings + Finding("no_sim_code", "The SIM gives no network code (MCC/MNC): nothing to compare.")
        }
        val code = resolution.identity.mccmnc

        if (resolution.stack.isEmpty()) {
            findings += Finding("no_profile", "OCD has no profile for this SIM's network code $code.")
        } else if (!sim.spn.isNullOrBlank() && resolution.stack.all { it.match.specificity == 0 }) {
            findings += Finding(
                "spn_not_in_ocd",
                "OCD has no profile for this SIM's name \"${sim.spn}\" on $code, so the SIM gets the " +
                    "network's general profile (${resolution.stack.last().displayName}). " +
                    "From OCD's README: ${bundle.limits.mvnoMerge}",
            )
        }
        if (resolution.notCheckable.isNotEmpty()) findings += hiddenSelectors(resolution, code)

        findings += carrierSettings(readings, resolution)

        val deep = readings.deep
        if (deep == null) {
            findings += Finding(
                NEEDS_DEEP_CHECK,
                "Whether this phone is signed in for VoLTE or Wi-Fi calling (IMS registration) is hidden from " +
                    "normal apps, and so is whether the Wi-Fi network lets Wi-Fi calling reach the carrier's server. " +
                    "This app does not guess either; the optional deep check (Shizuku) reads both.",
            )
        } else {
            findings += DeepFindings.compare(deep, readings, resolution)
        }

        val network = readings.network
        if (network?.roaming == true) {
            val serving = network.servingMccMnc?.let { " (serving network $it, SIM network $code)" }.orEmpty()
            findings += Finding(
                "roaming",
                "The phone is roaming now$serving. OCD's values describe the SIM's carrier tables; roaming limits " +
                    "are one of the gates no carrier setting shows.",
            )
        }
        if (FiveG.classify(network) == FiveG.NSA_ICON_ONLY) {
            findings += Finding("five_g_icon_only", FiveG.NSA_ICON_ONLY.plain)
        }

        val differences = findings.count { it.code.startsWith("phone_") }
        if (differences == 0 && resolution.stack.isNotEmpty()) {
            findings += Finding(
                "no_difference",
                "No difference between this phone's carrier settings and OCD's answers that a normal app can show.",
            )
        }
        return findings
    }

    private fun hiddenSelectors(resolution: Resolution, code: String): Finding {
        val aside = resolution.notCheckable
        val needs = aside.flatMap { it.needs }.distinct().map { need ->
            when (need) {
                "gid1" -> "GID1 (readable with the phone status permission)"
                "gid2" -> "GID2"
                "iccid" -> "the SIM serial (ICCID)"
                "imsi" -> "the subscriber number (IMSI)"
                else -> need
            }
        }
        val names = aside.map { it.profile.displayName }.distinct()
        val shown = names.take(4).joinToString(", ") + if (names.size > 4) " and ${names.size - 4} more" else ""
        return Finding(
            "hidden_selectors",
            "${aside.size} more OCD profile(s) for $code could apply to this SIM, but they match on " +
                "${needs.joinToString(" or ")}, which this app cannot read: $shown. " +
                "If your SIM is one of them, OCD's answer for it may differ.",
        )
    }

    private fun carrierSettings(readings: PhoneReadings, resolution: Resolution): List<Finding> {
        val settings = readings.carrierSettings
        if (!readings.permissionGranted) {
            return listOf(
                Finding(
                    "settings_need_permission",
                    "This phone's carrier settings can be read only with the phone status permission.",
                ),
            )
        }
        if (settings == null || !settings.readable) {
            return listOf(Finding("settings_unreadable", "This phone returned no carrier settings for this SIM."))
        }
        val findings = mutableListOf<Finding>()
        if (settings.identifiedCarrier == false) {
            findings += Finding(
                "settings_default",
                "This phone applies no carrier-specific settings to this SIM, so the carrier settings shown " +
                    "are Android's defaults.",
            )
        }
        if (resolution.stack.isNotEmpty()) {
            for (key in CarrierKeys.booleans) {
                val capability = key.capability ?: continue
                val phone = settings.booleans[key.key] ?: continue
                val ocd = resolution.answer(capability)
                val label = Capabilities.label(capability)
                when {
                    !phone && ocd.tablesTurnOn -> findings += Finding(
                        "phone_off_ocd_${ocd.wire}:$capability",
                        "$label: OCD's answer is ${ocd.wire}; this phone's carrier settings have it off " +
                            "(${key.key} = false). " +
                            (if (ocd == CapabilityValue.CONDITIONAL) "OCD's sources disagree for this SIM, often between phone models. " else "") +
                            "Carrier settings come from the phone's own software and can differ between phone " +
                            "models and software versions.",
                    )
                    phone && ocd == CapabilityValue.UNSUPPORTED -> findings += Finding(
                        "phone_on_ocd_unsupported:$capability",
                        "$label: OCD's answer is unsupported; this phone's carrier settings have it on (${key.key} = true).",
                    )
                    phone && ocd == CapabilityValue.UNKNOWN && key.androidDefault == false -> findings += Finding(
                        "phone_on_ocd_unknown:$capability",
                        "$label: OCD has no value (unknown); this phone's carrier settings have it on (${key.key} = true).",
                    )
                }
            }
        }
        val device = readings.device
        if (device.manufacturer.equals("samsung", ignoreCase = true) && device.romFamily == RomFamily.STOCK) {
            findings += Finding(
                "samsung_own_ims",
                "On Samsung's own software, Samsung's IMS uses its own tables, so the Android carrier settings " +
                    "shown here may not be what this phone uses for calls.",
            )
        }
        return findings
    }
}
