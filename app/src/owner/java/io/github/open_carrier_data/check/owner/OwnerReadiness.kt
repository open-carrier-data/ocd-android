package io.github.open_carrier_data.check.owner

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import io.github.open_carrier_data.check.owner.core.CallSeen
import io.github.open_carrier_data.check.owner.core.CallsSeen
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.ReadyLook
import io.github.open_carrier_data.check.owner.core.ReadyResult
import io.github.open_carrier_data.check.owner.core.Readiness
import io.github.open_carrier_data.check.owner.core.SimPhase
import io.github.open_carrier_data.check.owner.core.SlotLook
import io.github.open_carrier_data.check.owner.core.StateStore
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The call watch's record across restarts ([CallsSeen]): [PhoneStateReceiver] writes it for
 * every call the system announces, an emergency call included and also before the first unlock,
 * so it lives in device-protected storage. Times only, never a number. It is what lets a restart
 * refuse changes only when a call ended just before it ([io.github.open_carrier_data.check.owner.core.RestartWindow]).
 */
object CallSightings {
    private const val FILE = "call-sightings.json"
    private val LOCK = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, FILE)

    private fun load(context: Context): CallsSeen? = runCatching { json.decodeFromString(CallsSeen.serializer(), file(context).readText()) }.getOrNull()

    private fun save(context: Context, seen: CallsSeen) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(json.encodeToString(CallsSeen.serializer(), seen))
        tmp.renameTo(target)
    }

    /**
     * The record, for the refusals. It starts now when there is none (the watch runs from here on), and
     * again when the app was force-stopped or updated since it started: a stopped app hears no broadcast.
     */
    fun read(context: Context): CallsSeen = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val seen = load(context)
        val fresh = when {
            seen == null -> CallsSeen(now)
            stoppedSince(context, seen.sinceMs) -> seen.copy(sinceMs = now)
            else -> return seen
        }
        runCatching { save(context, fresh) }
        fresh
    }

    /** The app was just updated: its receivers were gone for a moment, so the watch covers from now on. */
    fun restart(context: Context) = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        runCatching { save(context, (load(context) ?: CallsSeen(now)).copy(sinceMs = now)) }
    }

    /** A call rang or went off hook: a new sighting, unless it continues the open one of this system server. */
    fun started(context: Context) = change(context) { s, now, server ->
        val last = s.calls.lastOrNull()
        if (last != null && last.endMs == null && last.server == server) {
            s
        } else {
            val own = OwnCall.sinceElapsedMs != 0L && OwnCall.untilElapsedMs == 0L
            s.copy(calls = (s.calls + CallSeen(now, own = own, server = server)).takeLast(CallsSeen.MAX_CALLS))
        }
    }

    /** The phone went idle: the open call of this system server ended. */
    fun ended(context: Context) = change(context) { s, now, server ->
        val last = s.calls.lastOrNull()
        if (last != null && last.endMs == null && last.server == server) s.copy(calls = s.calls.dropLast(1) + last.copy(endMs = now)) else s
    }

    /** The call watch saw Telecom call it an emergency call: the sightings since [sinceMs] were one (or one is added). */
    fun emergency(context: Context, sinceMs: Long) = change(context) { s, now, server ->
        val marked = s.calls.map { if (it.startMs >= sinceMs - 60_000 && !it.own) it.copy(emergency = true) else it }
        if (marked.any { it.emergency && it.startMs >= sinceMs - 60_000 }) s.copy(calls = marked) else s.copy(calls = (s.calls + CallSeen(sinceMs, now, emergency = true, server = server)).takeLast(CallsSeen.MAX_CALLS))
    }

    private fun change(context: Context, f: (CallsSeen, Long, String) -> CallsSeen) = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        val before = load(context) ?: CallsSeen(now)
        val after = f(before, now, server())
        if (after != before || !file(context).isFile) save(context, after)
    }

    /** The system server this runs under: the kernel's boot ID and zygote's PID (a soft restart starts a new zygote). */
    private fun server(): String {
        val boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull().orEmpty()
        val zygote = runCatching { android.system.Os.getppid() }.getOrNull() ?: 0
        return "$boot/$zygote"
    }

    /** Whether the app was force-stopped (or stopped by the user, or updated) after [sinceMs]: its receivers were off. */
    private fun stoppedSince(context: Context, sinceMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val reasons = buildSet {
            add(ApplicationExitInfo.REASON_USER_REQUESTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(ApplicationExitInfo.REASON_USER_STOPPED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)
                add(ApplicationExitInfo.REASON_PACKAGE_UPDATED)
            }
        }
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16)
                .any { it.timestamp > sinceMs && it.reason in reasons }
        }.getOrDefault(true)
    }
}

/**
 * The readiness looks on the phone ([Readiness]): each slot's SIM state from TelephonyManager,
 * CarrierSettings's served config from `dumpsys carrier_config`, and IMS from the route's reading.
 */
object OwnerReady {
    /** Waits (at most [Readiness.MAX_WAIT_MS]) until the phone is ready for a test; the SIMs as they are then. */
    suspend fun await(context: Context, phone: OwnerPhone, store: StateStore, maxMs: Long = Readiness.MAX_WAIT_MS): Pair<ReadyResult, List<OwnerSim>> {
        var sims = emptyList<OwnerSim>()
        val result = Readiness.await(
            look = {
                sims = runCatching { OwnerData.sims(context) }.getOrDefault(emptyList())
                look(context, phone, store, sims)
            },
            nowMs = phone::uptimeMs,
            sleep = phone::sleep,
            maxMs = maxMs,
        )
        return result to sims
    }

    private suspend fun look(context: Context, phone: OwnerPhone, store: StateStore, sims: List<OwnerSim>): ReadyLook {
        val tm = context.getSystemService(TelephonyManager::class.java)
        @Suppress("DEPRECATION")
        val count = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount else tm.phoneCount }.getOrDefault(0)
        val cc = if (sims.isEmpty()) null else phone.carrierConfigText()
        val slots = (0 until maxOf(count, (sims.maxOfOrNull { it.slot } ?: -1) + 1)).map { slot ->
            val sim = sims.firstOrNull { it.slot == slot }
            val state = runCatching { tm.getSimState(slot) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
            when (val phase = phase(state, sim != null)) {
                SimPhase.LOADED -> {
                    val ims = runCatching { OwnerSession(phone, store, sim!!).observe().ims }.getOrNull()
                    SlotLook(slot, phase, CcDump.served(cc, slot), ims?.registered, ims?.state, ims?.lastDisconnect != null)
                }
                else -> SlotLook(slot, phase)
            }
        }
        return ReadyLook(phone.uptimeMs(), slots)
    }

    /** A listed SIM is loaded; READY, NOT_READY, UNKNOWN or loaded without its subscription may be coming; the rest needs nothing or the owner. */
    fun phase(simState: Int, listed: Boolean): SimPhase = when {
        listed -> SimPhase.LOADED
        simState in setOf(SIM_STATE_LOADED, TelephonyManager.SIM_STATE_READY, TelephonyManager.SIM_STATE_NOT_READY, TelephonyManager.SIM_STATE_UNKNOWN) -> SimPhase.COMING
        else -> SimPhase.NONE
    }

    /** TelephonyManager.SIM_STATE_LOADED, a system API constant (10). */
    private const val SIM_STATE_LOADED = 10
}
