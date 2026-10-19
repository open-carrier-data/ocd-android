package io.github.open_carrier_data.check.core

/**
 * A read-only hint for Vodafone Germany's DNS location filter on its ePDG
 * name. Sources: the owner's OCD Lab research note ocd-lab-20261018, "Why
 * some resolvers get no answer for Vodafone's ePDG" (measured 2026-10-19),
 * and the S20 project's report "Per-domain DNS for Wi-Fi calling"
 * (2026-10-19).
 *
 * Vodafone's name servers answer `epdg.epc.mnc002.mcc262.pub.3gppnetwork.org`
 * (an alias of `epdg.epc.drz1.vodafone-ip.de`) only for lookups they place in
 * Germany: by the EDNS Client Subnet (ECS) a resolver passes on, or by the
 * resolver's own address. Quad9's default service and Cloudflare send no ECS
 * and got an empty answer, so Wi-Fi calling fails without any refusal by the
 * carrier. Every SIM whose home network is 26202 asks for that name: Vodafone
 * and its MVNOs such as LIDL Connect.
 *
 * This is a note about the DNS path, not a service status: it never becomes,
 * changes or ranks an OCD value. The app has no internet permission and does
 * not look the name up itself. The hint rests on what the deep check read on
 * the phone: the phone's own lookup in the IWLAN log (Android's IMS), the
 * shell's lookup, the Private DNS class and the Wi-Fi's resolver class. It
 * names a likely cause, never a proof.
 */
internal object VodafoneDnsHint {
    const val CODE = "vodafone_de_epdg_dns_filter"

    /** Home network codes whose SIMs ask for Vodafone Germany's ePDG name. */
    private val HOMES = setOf("26202")

    /** Networks on which the shell's lookup went through a real resolver. */
    private val REAL_NETWORKS = setOf(LookupNetwork.WIFI, LookupNetwork.MOBILE)

    fun appliesTo(homeMccmnc: String?): Boolean = homeMccmnc in HOMES

    /**
     * The hint, or null. It shows for a 26202 SIM that is not signed in over
     * Wi-Fi when a lookup on the phone got no address, or when the resolver
     * the phone uses is a kind that got the empty answer and no lookup on the
     * phone got an address.
     */
    fun finding(deep: DeepCheckReading, homeMccmnc: String?, samsung: Boolean): Finding? {
        if (!appliesTo(homeMccmnc)) return null
        val ims = deep.ims
        // Signed in over Wi-Fi: the phone's lookup got an address.
        if (ims.registered == true && ims.transport == ImsTransport.IWLAN) return null
        val lookup = deep.iwlan?.phoneLookup?.takeIf { it.standardName }
        val phoneEmpty = lookup?.resolved == false
        val shellEmpty = deep.epdgName == EpdgName.DOES_NOT_RESOLVE && deep.lookupNetwork == LookupNetwork.WIFI
        val answered = lookup?.resolved == true || (deep.epdgName == EpdgName.RESOLVES && deep.lookupNetwork in REAL_NETWORKS)
        val privateDns = deep.privateDns
        val strict = privateDns?.strict == true
        val noEcsPrivateDns = strict && privateDns?.ecs == false
        val publicWifi = !strict && deep.lookupNetwork == LookupNetwork.WIFI &&
            (deep.wifiResolver == WifiResolver.PUBLIC || deep.wifiResolver == WifiResolver.MIXED)
        val failed = phoneEmpty || shellEmpty
        // A lookup on the phone that got the address outweighs a resolver's class.
        if (!failed && (answered || !(noEcsPrivateDns || publicWifi))) return null

        val seen = buildList {
            if (phoneEmpty) add("the phone's own lookup of that name got no address")
            if (shellEmpty) add("the lookup over this Wi-Fi did not resolve it")
        }.joinToString(", and ")
        val resolver = when {
            strict && privateDns?.ecs == false ->
                "This phone sends every lookup to its Private DNS server, ${privateDns.plain}, which sends no ECS."
            strict && privateDns?.ecs == true ->
                "This phone's Private DNS is already ${privateDns.plain}, which passes ECS and usually gets the address; " +
                    "if the lookups keep failing, try the other server named below, or the router fix."
            strict ->
                "This phone sends every lookup to its Private DNS server, which this app does not know; " +
                    "whether it passes ECS or asks from Germany is not known."
            publicWifi ->
                "This Wi-Fi gives the phone ${deep.wifiResolver?.plain}; a public resolver without ECS gets the empty answer."
            deep.wifiResolver == WifiResolver.LOCAL ->
                "On this Wi-Fi the phone asks the router, which passes the lookup on to its upstream resolver; " +
                    "if that is Quad9's default service or Cloudflare, the answer comes back empty."
            else -> ""
        }
        val settingsPath = if (samsung) {
            "Settings, Connections, More connection settings, Private DNS"
        } else {
            "Settings, Network & internet, Private DNS"
        }
        val text = buildString {
            append("Likely cause on this SIM's network (home network 26202: Vodafone Germany and MVNOs on it, such as LIDL Connect): ")
            append("Vodafone's name servers give out the address of its Wi-Fi calling server (ePDG, ")
            append("epdg.epc.mnc002.mcc262.pub.3gppnetwork.org, an alias of epdg.epc.drz1.vodafone-ip.de) only to lookups ")
            append("they place in Germany, by the EDNS Client Subnet (ECS) a resolver passes on or by the resolver's own address. ")
            append("Other lookups get an empty answer, and Wi-Fi calling then fails without any refusal by the carrier. ")
            append("Measured on 2026-10-19, Quad9's default service (9.9.9.9) and Cloudflare (1.1.1.1) got the empty answer. ")
            if (seen.isNotEmpty()) append("Seen on this phone: $seen. ")
            if (resolver.isNotEmpty()) append("$resolver ")
            append("Fixes: set the router to the internet provider's own DNS servers (AVM's advice for Wi-Fi calling on a ")
            append("FRITZ!Box), or on this phone only set Private DNS to dns11.quad9.net (Quad9 with ECS) or dns.google ")
            append("($settingsPath), then switch Wi-Fi calling off and on. ")
            append("Both of those pass ECS: the first three parts of your IP address then go to the name servers of every ")
            append("name the phone looks up, not only Vodafone's. ")
            append("Outside Germany an ECS resolver passes a foreign address, which the filter turns away too. ")
            append("This is a likely cause, not a proof: the app has no internet permission, does not look the name up ")
            append("itself, and reads only the lookups made on the phone.")
        }
        return Finding(CODE, text)
    }
}
