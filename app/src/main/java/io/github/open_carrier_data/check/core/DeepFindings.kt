package io.github.open_carrier_data.check.core

/**
 * Findings from the Shizuku deep check, ported from phone_check.py
 * `compare()` and OCD Lab's ladder. Same rules as [Findings]: "signed in" is
 * the IMS registration the phone shows, never a claim that calls connect; no
 * finding says OCD is wrong or that a carrier lacks a feature.
 */
internal object DeepFindings {
    fun compare(deep: DeepCheckReading, readings: PhoneReadings, resolution: Resolution): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += ims(deep, readings, resolution)
        wifiCalling(deep, resolution)?.let { findings += it }
        heldBack(deep)?.let { findings += it }
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

    /** Whose IMS the finding talks about. */
    private fun stack(ims: ImsReading): String = if (ims.source == ImsSource.AOSP) "Android's IMS" else "Samsung's IMS"

    private fun ims(deep: DeepCheckReading, readings: PhoneReadings, resolution: Resolution): Finding {
        val ims = deep.ims
        return when (ims.source) {
            ImsSource.NOT_FOUND -> Finding(
                "ims_not_found",
                "The deep check found neither Samsung's IMS nor Android's own IMS state for this SIM's slot, so this app " +
                    "does not guess whether the phone is signed in for calls.",
            )
            ImsSource.UNREADABLE -> Finding(
                "ims_unreadable",
                "The deep check could not read the IMS state (the command failed or took too long). " +
                    "This app does not guess whether the phone is signed in for calls.",
            )
            ImsSource.SAMSUNG, ImsSource.AOSP -> when (ims.registered) {
                null -> Finding(
                    "ims_no_task",
                    "${stack(ims)} shows nothing for this SIM's slot, so whether the phone is signed in for calls is not known.",
                )
                true -> signedIn(ims, resolution)
                false -> notSignedIn(deep, readings, resolution)
            }
        }
    }

    private fun signedIn(ims: ImsReading, resolution: Resolution): Finding {
        val note = "Signed in means the phone registered with the carrier's IMS; it does not show that a call connects."
        val stale = ims.registrationLogTransport?.let {
            " The IMS registration log still names ${it.plain}; the IMS data connection is on ${ims.transport?.plain}, " +
                "which the app takes as the network."
        }.orEmpty()
        if (!ims.hasCalls) {
            return Finding(
                "ims_signed_in_without_calls",
                "${stack(ims)} is signed in, but not for calls (services: ${services(ims)}). " +
                    "${ocdSays(resolution, "volte")} $note",
            )
        }
        return when (ims.transport) {
            ImsTransport.IWLAN -> Finding(
                "wifi_calling_signed_in",
                "Wi-Fi calling: signed in over Wi-Fi (services: ${services(ims)}).$stale ${ocdSays(resolution, "vowifi")} $note",
            )
            ImsTransport.LTE -> Finding(
                "volte_signed_in",
                "VoLTE: signed in for calls over 4G (services: ${services(ims)}).$stale ${ocdSays(resolution, "volte")} $note",
            )
            ImsTransport.NR -> Finding(
                "vonr_signed_in",
                "VoNR: signed in for calls over 5G (services: ${services(ims)}).$stale ${ocdSays(resolution, "vonr")} $note",
            )
            ImsTransport.WWAN -> Finding(
                "ims_signed_in_mobile",
                "Signed in for calls over the mobile network (services: ${services(ims)}); the radio type is not known.$stale $note",
            )
            ImsTransport.OTHER, null -> Finding(
                "ims_signed_in",
                "Signed in for calls (services: ${services(ims)}), over a network type this app cannot name. $note",
            )
        }
    }

    private fun notSignedIn(deep: DeepCheckReading, readings: PhoneReadings, resolution: Resolution): Finding {
        val ims = deep.ims
        val network = readings.network
        val data = network?.psWwanType?.takeIf { it != 0 } ?: network?.dataNetworkType
        val state = ims.state?.let {
            " (${if (ims.source == ImsSource.AOSP) "Android's" else "Samsung's"} state: ${it.replace('_', ' ')})"
        }.orEmpty()
        val lastSignOut = ims.lastDisconnect?.let { " Last sign-out reason: ${it.code}${it.name?.let { name -> " ($name)" }.orEmpty()}." }.orEmpty()
        if (Radio.isLte(data) || data == Radio.NR) {
            val vops = deep.networkOffer?.lteVops
            // The network's own offer is measured; a cell without VoPS explains a missing registration by itself.
            if (Radio.isLte(data) && vops == LteVops.NOT_SUPPORTED) {
                return Finding(
                    "volte_not_signed_in_no_vops",
                    "Not signed in for calls over 4G$state, and the LTE cell reports that it does not offer voice over " +
                        "IMS (VoPS) here. ${ocdSays(resolution, "volte")}$lastSignOut",
                )
            }
            val volte = resolution.takeIf { it.stack.isNotEmpty() }?.answer("volte")
            val causes = if (volte?.tablesTurnOn == true) {
                " Possible causes: VoLTE is not in the plan, the carrier has not approved this phone model, " +
                    "the phone's own settings, or the IMS APN."
            } else {
                ""
            }
            return Finding(
                "volte_not_signed_in",
                "Not signed in for calls over 4G: the phone uses ${Radio.plain(data)} for data, but ${stack(ims)} is " +
                    "not registered$state. ${ocdSays(resolution, "volte")}$causes$lastSignOut",
            )
        }
        return Finding(
            "ims_not_signed_in",
            "Not signed in for calls over IMS$state. The phone is not on 4G or 5G for data right now " +
                "(${Radio.plain(data)}), so VoLTE cannot sign in here.$lastSignOut",
        )
    }

    private fun wifiCalling(deep: DeepCheckReading, resolution: Resolution): Finding? {
        val ims = deep.ims
        val onWifi = deep.lookupNetwork == LookupNetwork.WIFI
        // Without any network, ping also says "unknown host"; that is no DNS answer.
        val pingFails = deep.epdgName == EpdgName.DOES_NOT_RESOLVE && deep.lookupNetwork != LookupNetwork.NONE
        // The phone's own lookup (AOSP IWLAN's EpdgSelector) is the one that counts for Wi-Fi calling.
        val lookup = deep.iwlan?.phoneLookup?.takeIf { it.standardName }
        val phoneLookupFailed = lookup?.resolved == false
        val dnsFails = pingFails || phoneLookupFailed
        val over = if (deep.lookupNetwork == LookupNetwork.NOT_KNOWN) "on the phone's network" else "over ${deep.lookupNetwork.plain}"
        val name = resolution.identity.mccmnc.let(HomeNetwork::of)?.epdgFqdn ?: "the ePDG name"
        val what = buildList {
            if (pingFails) add("does not resolve $over")
            if (phoneLookupFailed) add("got no address in the phone's own lookup ${age(lookup!!.ageMinutes)}")
        }.joinToString(", and ")
        val resolver = when (deep.wifiResolver) {
            WifiResolver.LOCAL -> " This Wi-Fi gives the phone a resolver in the local network (usually the router), which " +
                "passes the lookup on to its provider's resolver; some resolvers cannot reach a carrier's name servers."
            WifiResolver.PUBLIC, WifiResolver.MIXED -> " This Wi-Fi gives the phone ${deep.wifiResolver.plain}."
            else -> ""
        }
        val dnsAdvice = if (deep.privateDnsActive == true) {
            "Private DNS is already on and does not resolve it either; try another Private DNS server, for example dns.google."
        } else {
            "Set Private DNS to a public resolver, for example dns.google (Settings, Network, Private DNS), " +
                "or fix the router's DNS, then switch Wi-Fi calling off and on."
        }
        val hint = if (phoneLookupFailed) {
            "The phone's own lookup is the one Wi-Fi calling uses."
        } else {
            "Some carriers use another server name, so this is a strong hint, not proof."
        }
        val signedInOverWifi = ims.registered == true && ims.transport == ImsTransport.IWLAN
        if (deep.wifiCallingSwitch == WifiCallingSwitch.ON && !signedInOverWifi) {
            return when {
                dnsFails -> Finding(
                    "wifi_calling_not_signed_in_epdg_dns",
                    "Wi-Fi calling is switched on but not signed in over Wi-Fi, and the standard name of the " +
                        "carrier's Wi-Fi calling server ($name) $what. " +
                        "${ocdSays(resolution, "vowifi")} Likely cause: this network's DNS.$resolver $dnsAdvice $hint" +
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
                "The standard name of the carrier's Wi-Fi calling server ($name) $what on this Wi-Fi network. " +
                    "If you switch Wi-Fi calling on, it may not sign in here.$resolver $dnsAdvice $hint",
            )
        }
        return null
    }

    /** AOSP IWLAN holds Wi-Fi calling back after a failed attempt (lab `iwlan_summary`). */
    private fun heldBack(deep: DeepCheckReading): Finding? {
        val iwlan = deep.iwlan ?: return null
        val by = iwlan.heldBackBy ?: return null
        if (deep.ims.registered == true && deep.ims.transport == ImsTransport.IWLAN) return null
        val minutes = iwlan.heldBackMinutes?.let { " for about $it more minute${if (it == 1L) "" else "s"}" }.orEmpty()
        val after = iwlan.lastRefusal?.let { " (last refusal: $it)" }.orEmpty()
        return Finding(
            "wifi_calling_held_back",
            "Wi-Fi calling is held back$minutes by ${by.plain}$after. Until then the phone does not try again; " +
                "switching Wi-Fi calling off and on, or a restart, may clear the wait.",
        )
    }

    private fun age(minutes: Long): String = when {
        minutes < 2 -> "just now"
        minutes < 120 -> "$minutes minutes ago"
        minutes < 48 * 60 -> "${minutes / 60} hours ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
