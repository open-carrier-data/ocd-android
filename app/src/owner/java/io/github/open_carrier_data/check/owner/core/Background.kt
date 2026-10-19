package io.github.open_carrier_data.check.owner.core

import java.util.concurrent.TimeUnit

/**
 * The owner build's background schedule, as plain functions: when the
 * weekly job first runs, and what the screen and the Settings entry say
 * about it.
 */
object WeeklySchedule {
    val PERIOD_MS: Long = TimeUnit.DAYS.toMillis(7)

    /**
     * The first weekly run comes one period after the last test run (or after
     * now, with none), never at once: a periodic job without a delay would run
     * at the first idle, charging moment after it is registered.
     */
    fun initialDelayMs(lastRunMs: Long?, nowMs: Long): Long =
        ((lastRunMs ?: nowMs) + PERIOD_MS - nowMs).coerceIn(0, PERIOD_MS)

    /**
     * The schedule line. WorkManager's next time is when the job may run at
     * the earliest; once that has passed, the job runs at the phone's next
     * idle, charging moment on Wi-Fi, so a past time is never shown.
     */
    fun line(on: Boolean, registered: Boolean, nextMs: Long?, nowMs: Long, format: (Long) -> String): String = when {
        !on -> "off"
        !registered -> "on, not registered yet (registered at the next boot or when this screen opens)"
        nextMs == null || nextMs <= 0 -> "on; next run when the phone is idle, charging and on Wi-Fi"
        nextMs <= nowMs -> "on; due now: runs the next time the phone is idle, charging and on Wi-Fi"
        else -> "on; next run from ${format(nextMs)}, when the phone is idle, charging and on Wi-Fi"
    }
}

/** The summary line under "Open Carrier Data" in Settings > Network & internet. */
object SettingsSummary {
    fun line(state: OwnerState, privileged: Boolean, format: (Long) -> String): String {
        val parts = mutableListOf<String>()
        if (state.stop != null) return "Stopped after a stop event; open to review"
        val kept = state.overrides.count { it.hold == Hold.KEPT } + state.apns.count { it.hold == Hold.KEPT }
        val trials = state.overrides.count { it.hold == Hold.TRIAL } + state.apns.count { it.hold == Hold.TRIAL }
        parts += when {
            trials > 0 || state.wifiOffSinceMs != null -> "Test running"
            kept > 0 -> "$kept kept"
            else -> "Phone's own carrier settings"
        }
        parts += state.runs.lastOrNull()?.let { "last test ${format(it.startedMs)}" } ?: "not tested yet"
        if (!privileged) parts += "no privileged access"
        return parts.joinToString(" · ")
    }
}

/**
 * Retries a start that can fail for a moment (Shizuku's user service: a bind
 * right after the previous service process exited loses its record, "unable
 * to find token"). [block] gets the attempt number from 1 and returns null on
 * a failure; between attempts [pause] runs (the caller unbinds and waits).
 */
suspend fun <T : Any> retrying(attempts: Int, pause: suspend (attempt: Int) -> Unit, block: suspend (attempt: Int) -> T?): T? {
    for (attempt in 1..attempts) {
        block(attempt)?.let { return it }
        if (attempt < attempts) pause(attempt)
    }
    return null
}
