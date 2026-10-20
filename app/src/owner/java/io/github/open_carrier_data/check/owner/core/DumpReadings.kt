package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.IwlanHold
import io.github.open_carrier_data.check.core.IwlanReading
import kotlinx.serialization.Serializable

/*
 * Readings that replace the device log and close the soft-restart gap
 * (2026-10-20). An app that reads logcat makes Android ask the owner for
 * "access to all device logs", and a privileged app cannot skip that; the
 * owner build no longer reads the log at all. The IWLAN state comes from
 * `dumpsys telephony.registry` (DUMP) and from the IWLAN data service's and
 * QNS's own dumps (`dumpsys activity service`, DUMP plus PACKAGE_USAGE_STATS).
 * Only words, states and counts leave the parsers: no address, no number.
 */

/** The IMS data connection over Wi-Fi from `dumpsys telephony.registry`: up or not, and the last IWLAN fail cause. */
object IwlanRegistry {
    private val ENTRY = Regex(
        "state: (\\w+), transport: (WLAN|WWAN), id: -?\\d+, netId: -?\\d+, network type: (\\w+), APN Setting: \\[ApnSetting\\] ([^\\n]*?)(?=Pair\\{|\\n|$)",
    )
    private val CAUSE = Regex("fail cause: ([A-Z][A-Z0-9_]{0,79})\\(")
    private val LOG_LINE = Regex("(?m)^\\s*\\S+ - notifyDataConnectionForSubscriber: phoneId=(\\d+) .*$")
    private val IMS = Regex("(?:, |\\| )ims(?:,| \\|)")

    /**
     * The IWLAN reading for [phoneId]: the last known state's IMS entry on
     * WLAN, else the newest WLAN line for the IMS APN in the local log. The
     * network's back-off is not in this dump ([IwlanDumps] reads QNS for it);
     * the ePDG selector's failure is (IWLAN_EPDG_SELECTOR_SERVER_SELECTION_FAILED).
     */
    fun reading(registry: String?, phoneId: Int): IwlanReading? {
        if (registry.isNullOrBlank()) return null
        val block = RegistryText.block(registry, phoneId)
        val current = entries(block).lastOrNull { it.wlan && it.ims }
        val logged = LOG_LINE.findAll(registry).filter { it.groupValues[1] == phoneId.toString() }
            .flatMap { entries(it.value) }.lastOrNull { it.wlan && it.ims }
        val entry = current ?: logged ?: return IwlanReading(tunnelUp = null)
        val up = entry.state == "CONNECTED" || (entry.state == "HANDOVERINPROGRESS" && current != null)
        val refusal = entry.cause?.takeIf { it.startsWith("IWLAN_") }
        return IwlanReading(
            tunnelUp = up,
            lastRefusal = refusal,
            selectionFailed = !up && refusal != null && "SERVER_SELECTION_FAILED" in refusal,
        )
    }

    private data class Entry(val state: String, val wlan: Boolean, val ims: Boolean, val cause: String?)

    private fun entries(text: String): List<Entry> = ENTRY.findAll(text).map { m ->
        val apn = m.groupValues[4]
        Entry(
            state = m.groupValues[1],
            wlan = m.groupValues[2] == "WLAN",
            ims = IMS.containsMatchIn(", $apn,"),
            cause = CAUSE.find(apn)?.groupValues?.get(1)?.takeIf { it != "NONE" },
        )
    }.toList()
}

/**
 * The IWLAN data service's dump (`dumpsys activity service
 * com.google.android.iwlan/.IwlanDataService`) and QNS's
 * (`com.android.telephony.qns`), merged with the registry: the IMS tunnel's
 * state, the IWLAN errors since the service started, and whether QNS holds
 * Wi-Fi back for IMS (throttled by the network, or a fallback after a failed
 * connection), which the log used to show as a back-off.
 */
object IwlanDumps {
    private val TUNNEL = Regex("Tunnel state for APN: ims\\s+Current State of this tunnel: -?\\d+ (\\w+)")
    private val ERROR = Regex("TYPE: (IWLAN_[A-Z0-9_]{1,74})\\s*:\\s*\\d+")
    private val WLAN_RESTRICTION = Regex("RestrictInfo\\[WLAN\\] : ([^}]*?)(?:, \\d+=RestrictInfo|\\}|$)", RegexOption.MULTILINE)
    private val RESTRICT_TYPE = Regex("RESTRICT_TYPE_([A-Z_]+)")
    private val QUALIFIED = Regex("mLastQualifiedAccessNetworkTypes=([A-Z|]*)")

    fun reading(registry: String?, iwlan: String?, qns: String?, slot: Int): IwlanReading? {
        val base = IwlanRegistry.reading(registry, slot)
        val provider = iwlan?.let { section(it, "IwlanDataServiceProvider[$slot]", "IwlanDataServiceProvider[") }
        val tunnel = provider?.let { TUNNEL.find(it)?.groupValues?.get(1) }
        val tunnelUp = tunnel?.let { it == "UP" } ?: base?.tunnelUp
        val errors = provider?.substringAfter("ErrorPolicyManager", "")?.let { p -> ERROR.findAll(p).map { it.groupValues[1] }.toList() }.orEmpty()
        val hold = restrictions(qns, slot).firstNotNullOfOrNull(::hold)
        if (base == null && provider == null && hold == null) return null
        val refusal = base?.lastRefusal ?: errors.lastOrNull()?.takeIf { tunnelUp != true }
        return IwlanReading(
            tunnelUp = tunnelUp,
            lastRefusal = refusal,
            selectionFailed = tunnelUp != true && (base?.selectionFailed == true || errors.any { "SERVER_SELECTION_FAILED" in it }),
            heldBackBy = hold,
        )
    }

    /** QNS's restriction types on WLAN for IMS on [slot] ("THROTTLING", "FALLBACK_ON_DATA_CONNECTION_FAIL", ...); empty: none. */
    fun restrictions(qns: String?, slot: Int): List<String> {
        val ane = qns?.let { section(it, "ANE[ims_$slot]", "ANE[") } ?: return emptyList()
        val text = WLAN_RESTRICTION.find(ane)?.groupValues?.get(1)?.trim() ?: return emptyList()
        if (text.startsWith("No restriction")) return emptyList()
        return RESTRICT_TYPE.findAll(text).map { it.groupValues[1] }.distinct().toList().ifEmpty { listOf("UNKNOWN") }
    }

    /** The access network QNS last qualified for IMS on [slot] ("IWLAN", "EUTRAN"), for the log. */
    fun qualifiedForIms(qns: String?, slot: Int): String? =
        qns?.let { section(it, "ANE[ims_$slot]", "ANE[") }?.let { QUALIFIED.find(it)?.groupValues?.get(1)?.trim('|') }?.takeIf { it.isNotEmpty() }

    /** The guard after a handover is short and normal; a throttle or a fallback after failures holds Wi-Fi calling back. */
    private fun hold(type: String): IwlanHold? = when {
        type == "THROTTLING" -> IwlanHold.NETWORK_BACKOFF_TIMER
        type.startsWith("FALLBACK") || type.contains("RETRY") || type == "UNKNOWN" -> IwlanHold.DATA_SERVICE_RETRY
        else -> null
    }

    private fun section(text: String, header: String, next: String): String? {
        val at = text.indexOf(header)
        if (at < 0) return null
        val rest = text.substring(at + header.length)
        val end = listOf(rest.indexOf(next), rest.indexOf("\nSERVICE ")).filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}

/** When the running system_server started, as far as the app can tell. */
data class ServerStart(
    /** `sys.system_server.start_elapsed`, when the property is readable. */
    val startElapsedMs: Long?,
    /** `sys.system_server.start_count`: 1 after a boot, more after each soft restart. */
    val startCount: Int?,
    /** /proc/sys/kernel/random/boot_id. */
    val bootId: String?,
    /** The app's parent, zygote: a soft restart starts a new one. */
    val zygotePid: Int?,
    val elapsedMs: Long,
)

/** The first time the owner build saw this system_server (boot, zygote and start count), on the elapsed clock. */
@Serializable
data class ServerSighting(val key: String, val firstSeenElapsedMs: Long)

/**
 * The soft-restart gap (2026-10-20): a restart of system_server clears the
 * registry's emergency-call field just as a reboot does, but the elapsed
 * clock runs on, so the 10 minutes after boot do not cover it. The time
 * since the server started comes from its start property (SystemServer sets
 * it at every start) when SELinux lets the app read it; else from the first
 * time the app saw this server (boot ID, zygote's PID, start count), and a
 * server it has not seen before counts as just started.
 */
object ServerClock {
    fun key(start: ServerStart): String = "${start.bootId ?: "-"}/${start.zygotePid ?: "-"}/${start.startCount ?: "-"}"

    /** How long the current system_server has run, and the sighting to keep. */
    fun upMs(start: ServerStart, stored: ServerSighting?): Pair<Long, ServerSighting> {
        val key = key(start)
        val sighting = stored?.takeIf { it.key == key && it.firstSeenElapsedMs <= start.elapsedMs } ?: ServerSighting(key, start.elapsedMs)
        val bySighting = start.elapsedMs - sighting.firstSeenElapsedMs
        val byProperty = start.startElapsedMs?.takeIf { it in 0..start.elapsedMs }?.let { start.elapsedMs - it }
        return (byProperty ?: bySighting) to sighting
    }

    /** The delay that puts a job after the window: [Refusals.EMERGENCY_WINDOW_MS] after the boot or the server start, plus a margin. */
    fun delayAfterWindowMs(elapsedMs: Long, serverUpMs: Long?): Long =
        (Refusals.EMERGENCY_WINDOW_MS - minOf(elapsedMs, serverUpMs ?: elapsedMs)).coerceAtLeast(0) + 30_000
}

/** One IMS registration event from the registration callback, after a change: a fresh REGISTER is the stronger result. */
data class RegistrationEvent(val atMs: Long, val registered: Boolean, val transportType: Int?)

/** IMS registrations of one subscription, counted from when the watch started. */
interface RegistrationWatch {
    fun events(): List<RegistrationEvent>
    fun close()
}
