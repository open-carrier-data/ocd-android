package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable

/*
 * Readings the refusals and the evidence need, ported from OCD Lab
 * (lab_parsers.py registry_block, parse_registration_infos, parse_roaming,
 * emergency_state, any_call_active, parse_aosp_ims's platform and user
 * flags). Booleans and short words only: no number, identity or address
 * survives a parser.
 */

object RegistryText {
    private val PHONE_ID = Regex("(?m)^\\s*Phone Id=(\\d+)\\s*$")

    /** The `Phone Id=N` block of the "last known state" section. */
    fun block(text: String, phoneId: Int): String {
        val start = Regex("(?m)^\\s*Phone Id=$phoneId\\s*$").find(text) ?: return ""
        val rest = text.substring(start.range.last + 1)
        val end = Regex("(?m)^\\s*Phone Id=\\d+\\s*$|^\\S").find(rest)
        return if (end != null) rest.substring(0, end.range.first) else rest
    }

    fun phoneIds(text: String): List<Int> = PHONE_ID.findAll(text).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    /** True when any phone's mCallState is not idle. An unreadable registry counts as busy, so nothing changes. */
    fun anyCallActive(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val states = phoneIds(text).flatMap { id -> Regex("(?m)^\\s*mCallState=(\\d)").findAll(block(text, id)).map { it.groupValues[1] }.toList() }
        return states.isEmpty() || states.any { it != "0" }
    }
}

/** The WWAN packet-switched registration: what the network answered at attach. */
@Serializable
data class PsRegistration(val state: String, val rat: String, val rejectCause: Int)

object RegistrationParser {
    private val NRI = Regex(
        "NetworkRegistrationInfo\\{ domain=(\\w+) transportType=(\\w+) registrationState=(\\w+).*?accessNetworkTechnology=(\\w+) rejectCause=(\\d+)",
    )

    fun wwanPs(block: String): PsRegistration? = NRI.findAll(block)
        .firstOrNull { it.groupValues[1] == "PS" && it.groupValues[2] == "WWAN" }
        ?.let { PsRegistration(it.groupValues[3].lowercase(), it.groupValues[4].lowercase(), it.groupValues[5].toInt()) }
}

@Serializable
data class RoamingReading(
    /** null: no registered WWAN entry, so roaming cannot be told. */
    val roaming: Boolean?,
    val why: List<String>,
    /** The registered PLMN's digits (a network code, not an identity). */
    val servingPlmn: String?,
)

object RoamingParser {
    private val NRI_START = Regex("NetworkRegistrationInfo\\{ domain=(\\w+) transportType=(\\w+) registrationState=(\\w+)")
    private val NETWORK_STATE = Regex("networkRegistrationState=(\\w+)")
    private val ROAMING_TYPE = Regex("roamingType=(\\w+)")
    private val RPLMN = Regex("rRplmn=(\\d*)")
    private val DISPLAY = Regex("TelephonyDisplayInfo \\{[^}]*?isRoaming=(true|false)")

    private data class Entry(val state: String, val networkState: String?, val roamingType: String?, val rplmn: String?)

    /**
     * Lab `parse_roaming`: any registered WWAN entry whose framework state,
     * network state or roaming type says roaming counts, and so does
     * `isRoaming=true` in TelephonyDisplayInfo. National roaming shown as home
     * therefore counts too. No registered WWAN entry gives null, never false.
     */
    fun parse(block: String): RoamingReading {
        val starts = NRI_START.findAll(block).toList()
        val entries = LinkedHashMap<String, Entry>()
        starts.forEachIndexed { index, match ->
            val (domain, transport, state) = match.destructured
            if (transport != "WWAN") return@forEachIndexed
            val end = if (index + 1 < starts.size) starts[index + 1].range.first else block.length
            val chunk = block.substring(match.range.first, end)
            val plmn = RPLMN.find(chunk)?.groupValues?.get(1).orEmpty()
            entries.putIfAbsent(
                "wwan_${domain.lowercase()}",
                Entry(
                    state = state.lowercase(),
                    networkState = NETWORK_STATE.find(chunk)?.groupValues?.get(1)?.lowercase(),
                    roamingType = ROAMING_TYPE.find(chunk)?.groupValues?.get(1)?.lowercase(),
                    rplmn = plmn.takeIf { Regex("\\d{5,6}").matches(it) && it.trim('0').isNotEmpty() },
                ),
            )
        }
        val registered = entries.filterValues { it.state == "home" || it.state == "roaming" }
        val display = DISPLAY.findAll(block).lastOrNull()?.groupValues?.get(1)?.let { it == "true" }
        val reasons = mutableListOf<String>()
        for ((name, entry) in registered.toSortedMap()) {
            if (entry.state == "roaming") reasons += "${name}_state"
            if (entry.networkState == "roaming") reasons += "${name}_network_state"
            if (entry.roamingType != null && entry.roamingType != "not_roaming") reasons += "${name}_roaming_type_${entry.roamingType}"
        }
        if (display == true) reasons += "display_is_roaming"
        val serving = listOf("wwan_ps", "wwan_cs").firstNotNullOfOrNull { registered[it]?.rplmn }
        return RoamingReading(
            roaming = if (reasons.isNotEmpty()) true else if (registered.isNotEmpty()) false else null,
            why = reasons,
            servingPlmn = serving,
        )
    }
}

/** Emergency callback mode and emergency calls over every phone of the device (lab `emergency_state`). Booleans only. */
@Serializable
data class EmergencyReading(
    val ecbm: Boolean?,
    val inEmergencyCall: Boolean?,
    val emergencySmsMode: Boolean?,
    /** The registry's mOutgoingCallEmergencyNumber is set: an outgoing emergency call since the system server started. */
    val outgoingEmergencyCallSinceBoot: Boolean?,
    /** A hash of that field, only to see a second emergency call; the number itself is never kept. */
    val outgoingMark: Int? = null,
)

object EmergencyParser {
    private val ECM_DUMP = Regex("\\bisInEcm\\(\\)=(true|false)")
    private val ECM_STATE = Regex("\\bmIsPhoneInEcmState = (true|false)")
    private val IN_CALL = Regex("\\bisInEmergencyCall\\(\\)=(true|false)")
    private val SMS_MODE = Regex("\\bisInEmergencySmsMode=(true|false)")
    private val OUTGOING = Regex("mOutgoingCallEmergencyNumber=(.*)")
    private val ECBM_DURATION = Regex("mECBMDuration=(-?\\d+)")
    private val SCBM_DURATION = Regex("mSCBMDuration=(-?\\d+)")

    fun parse(registry: String, phoneText: String, ecmProperty: String?): EmergencyReading {
        val outgoing = mutableListOf<Boolean>()
        var mark: Int? = null
        val ecbmRegistry = mutableListOf<Boolean>()
        val scbmRegistry = mutableListOf<Boolean>()
        for (phone in RegistryText.phoneIds(registry)) {
            val block = RegistryText.block(registry, phone)
            OUTGOING.findAll(block).lastOrNull()?.let {
                val value = it.groupValues[1].trim()
                val set = value.isNotEmpty() && value != "null"
                outgoing += set
                if (set) mark = (mark ?: 0) * 31 + value.hashCode()
            }
            ECBM_DURATION.findAll(block).lastOrNull()?.let { ecbmRegistry += it.groupValues[1].toLong() != 0L }
            SCBM_DURATION.findAll(block).lastOrNull()?.let { scbmRegistry += it.groupValues[1].toLong() != 0L }
        }
        val ecmDump = ECM_DUMP.findAll(phoneText).map { it.groupValues[1] == "true" }.toList() +
            ECM_STATE.findAll(phoneText).map { it.groupValues[1] == "true" }.toList()
        val inCall = IN_CALL.findAll(phoneText).map { it.groupValues[1] == "true" }.toList()
        val smsMode = SMS_MODE.findAll(phoneText).map { it.groupValues[1] == "true" }.toList()
        val property = ecmProperty?.trim()?.takeIf { it.isNotEmpty() }.asBool()
        fun anyOf(values: List<Boolean>): Boolean? = if (values.any { it }) true else if (values.isNotEmpty()) false else null
        return EmergencyReading(
            ecbm = if (ecmDump.any { it } || property == true || ecbmRegistry.any { it }) true else if (ecmDump.isNotEmpty()) false else null,
            inEmergencyCall = anyOf(inCall),
            emergencySmsMode = if (smsMode.any { it } || scbmRegistry.any { it }) true else if (smsMode.isNotEmpty()) false else null,
            outgoingEmergencyCallSinceBoot = anyOf(outgoing),
            outgoingMark = mark,
        )
    }
}

/** The ImsManager flags of one phone in `dumpsys activity service com.android.phone` (lab `parse_aosp_ims` platform and user). */
@Serializable
data class ImsFlags(
    val volteByPlatform: Boolean? = null,
    val vtByPlatform: Boolean? = null,
    val wfcByPlatform: Boolean? = null,
    val imsOverNrByPlatform: Boolean? = null,
    val volteByUser: Boolean? = null,
    val vtByUser: Boolean? = null,
    val wfcByUser: Boolean? = null,
) {
    companion object {
        private val HEADER = Regex("(?m)^\\s*GsmCdmaPhone extends:\\s*$")
        private val PHONE_ID = Regex("mPhoneId=(\\d+)")

        fun parse(text: String, phoneId: Int): ImsFlags {
            val section = text.split(HEADER).drop(1).firstOrNull { part ->
                PHONE_ID.find(part)?.groupValues?.get(1)?.toIntOrNull() == phoneId
            } ?: return ImsFlags()
            fun flag(name: String): Boolean? = Regex("\\b$name = (true|false)\\b").find(section)?.groupValues?.get(1)?.let { it == "true" }
            return ImsFlags(
                volteByPlatform = flag("isVolteEnabledByPlatform"),
                vtByPlatform = flag("isVtEnabledByPlatform"),
                wfcByPlatform = flag("isWfcEnabledByPlatform"),
                imsOverNrByPlatform = flag("isImsOverNrEnabledByPlatform"),
                volteByUser = flag("isEnhanced4gLteModeSettingEnabledByUser"),
                vtByUser = flag("isVtEnabledByUser"),
                wfcByUser = flag("isWfcEnabledByUser"),
            )
        }
    }
}
