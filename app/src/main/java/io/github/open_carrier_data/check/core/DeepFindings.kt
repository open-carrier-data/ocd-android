package io.github.open_carrier_data.check.core

/**
 * Findings from the Shizuku deep check, ported from phone_check.py
 * `compare()`. Same rules as [Findings]: "signed in" is the IMS registration
 * the phone shows, never a claim that calls connect; no finding says OCD is
 * wrong or that a carrier lacks a feature.
 */
internal object DeepFindings {
    fun compare(deep: DeepCheckReading, readings: PhoneReadings, resolution: Resolution): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += ims(deep.ims, readings, resolution)
        wifiCalling(deep, resolution)?.let { findings += it }
        if (deep.apnList?.rows.orEmpty().any { it.edited == ApnEdited.BY_USER }) {
            findings += Finding(
                "apn_edited_by_user",
                "The phone's APN list for this network has a row someone added or changed by hand. " +
                    "A hand-made APN can differ from the carrier's own.",
            )
        }
        return findings
    }

    private fun ocdSays(resolution: Resolution, capability: String): String =
        if (resolution.stack.isEmpty()) {
            "OCD has no profile for this SIM."
        } else {
            "OCD's answer for ${Capabilities.label(capability)} is ${resolution.answer(capability).wire}."
        }

    private fun services(ims: ImsReading): String =
        ims.services.joinToString(", ") { it.plain }.ifEmpty { "no service this app can name" }

    private fun ims(ims: ImsReading, readings: PhoneReadings, resolution: Resolution): Finding = when (ims.source) {
        ImsSource.NOT_FOUND -> Finding(
            "ims_not_found",
            "The deep check found no Samsung IMS service on this phone. Reading the IMS state of other phones " +
                "is not built yet, so this app does not guess whether the phone is signed in for calls.",
        )
        ImsSource.UNREADABLE -> Finding(
            "ims_unreadable",
            "The deep check could not read Samsung's IMS state (the command failed or took too long). " +
                "This app does not guess whether the phone is signed in for calls.",
        )
        ImsSource.SAMSUNG -> when (ims.registered) {
            null -> Finding(
                "ims_no_task",
                "Samsung's IMS shows nothing for this SIM's slot, so whether the phone is signed in for calls is not known.",
            )
            true -> signedIn(ims, resolution)
            false -> notSignedIn(ims, readings, resolution)
        }
    }

    private fun signedIn(ims: ImsReading, resolution: Resolution): Finding {
        val note = "Signed in means the phone registered with the carrier's IMS; it does not show that a call connects."
        if (!ims.hasCalls) {
            return Finding(
                "ims_signed_in_without_calls",
                "Samsung's IMS is signed in, but not for calls (services: ${services(ims)}). " +
                    "${ocdSays(resolution, "volte")} $note",
            )
        }
        return when (ims.transport) {
            ImsTransport.IWLAN -> Finding(
                "wifi_calling_signed_in",
                "Wi-Fi calling: signed in over Wi-Fi (services: ${services(ims)}). ${ocdSays(resolution, "vowifi")} $note",
            )
            ImsTransport.LTE -> Finding(
                "volte_signed_in",
                "VoLTE: signed in for calls over 4G (services: ${services(ims)}). ${ocdSays(resolution, "volte")} $note",
            )
            ImsTransport.NR -> Finding(
                "vonr_signed_in",
                "VoNR: signed in for calls over 5G (services: ${services(ims)}). ${ocdSays(resolution, "vonr")} $note",
            )
            ImsTransport.OTHER, null -> Finding(
                "ims_signed_in",
                "Signed in for calls (services: ${services(ims)}), over a network type this app cannot name. $note",
            )
        }
    }

    private fun notSignedIn(ims: ImsReading, readings: PhoneReadings, resolution: Resolution): Finding {
        val network = readings.network
        val data = network?.psWwanType?.takeIf { it != 0 } ?: network?.dataNetworkType
        val state = ims.state?.let { " (Samsung's state: ${it.replace('_', ' ')})" }.orEmpty()
        if (Radio.isLte(data) || data == Radio.NR) {
            val volte = resolution.takeIf { it.stack.isNotEmpty() }?.answer("volte")
            val causes = if (volte?.tablesTurnOn == true) {
                " Possible causes: VoLTE is not in the plan, the carrier has not approved this phone model, " +
                    "the phone's own settings, or the IMS APN."
            } else {
                ""
            }
            return Finding(
                "volte_not_signed_in",
                "Not signed in for calls over 4G: the phone uses ${Radio.plain(data)} for data, but Samsung's IMS is " +
                    "not registered$state. ${ocdSays(resolution, "volte")}$causes",
            )
        }
        return Finding(
            "ims_not_signed_in",
            "Not signed in for calls over IMS$state. The phone is not on 4G or 5G for data right now " +
                "(${Radio.plain(data)}), so VoLTE cannot sign in here.",
        )
    }

    private fun wifiCalling(deep: DeepCheckReading, resolution: Resolution): Finding? {
        val ims = deep.ims
        val onWifi = deep.lookupNetwork == LookupNetwork.WIFI
        // Without any network, ping also says "unknown host"; that is no DNS answer.
        val dnsFails = deep.epdgName == EpdgName.DOES_NOT_RESOLVE && deep.lookupNetwork != LookupNetwork.NONE
        val over = if (deep.lookupNetwork == LookupNetwork.NOT_KNOWN) "on the phone's network" else "over ${deep.lookupNetwork.plain}"
        val name = resolution.identity.mccmnc.let(HomeNetwork::of)?.epdgFqdn ?: "the ePDG name"
        val dnsAdvice = if (deep.privateDnsActive == true) {
            "Private DNS is already on and does not resolve it either; try another Private DNS server, for example dns.google."
        } else {
            "Set Private DNS to a public resolver, for example dns.google (Settings, Network, Private DNS), " +
                "or fix the router's DNS, then switch Wi-Fi calling off and on."
        }
        val hint = "Some carriers use another server name, so this is a strong hint, not proof."
        val signedInOverWifi = ims.registered == true && ims.transport == ImsTransport.IWLAN
        if (deep.wifiCallingSwitch == WifiCallingSwitch.ON && !signedInOverWifi) {
            return when {
                dnsFails -> Finding(
                    "wifi_calling_not_signed_in_epdg_dns",
                    "Wi-Fi calling is switched on but not signed in over Wi-Fi, and the standard name of the " +
                        "carrier's Wi-Fi calling server ($name) does not resolve $over. " +
                        "${ocdSays(resolution, "vowifi")} Likely cause: this network's DNS. $dnsAdvice $hint" +
                        if (onWifi) "" else " The phone was not on Wi-Fi during the check; run it again on the Wi-Fi network in question.",
                )
                !onWifi && deep.lookupNetwork != LookupNetwork.NOT_KNOWN -> Finding(
                    "wifi_calling_on_no_wifi",
                    "Wi-Fi calling is switched on, but the phone was not on Wi-Fi during the check " +
                        "(${deep.lookupNetwork.plain}), so it could not sign in over Wi-Fi. Run the check again on Wi-Fi.",
                )
                else -> Finding(
                    "wifi_calling_not_signed_in",
                    "Wi-Fi calling is switched on but not signed in over Wi-Fi. ${ocdSays(resolution, "vowifi")} " +
                        "Likely: the plan option, the carrier's back-off after failed attempts, or the phone prefers " +
                        "the mobile network.",
                )
            }
        }
        if (dnsFails && onWifi && !signedInOverWifi) {
            return Finding(
                "epdg_name_not_resolving",
                "The standard name of the carrier's Wi-Fi calling server ($name) does not resolve on this Wi-Fi network. " +
                    "If you switch Wi-Fi calling on, it may not sign in here. $dnsAdvice $hint",
            )
        }
        return null
    }
}
