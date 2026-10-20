package io.github.open_carrier_data.check.owner.core

import java.time.ZonedDateTime

/*
 * The silent window (the owner, 2026-10-20: "as automatic as possible but
 * still as silent as possible"; the conditions from the research report
 * "Safe APN tests and quiet background checks" of the same day). The light
 * read-only check runs whenever the screen is off and no call is up. Trials
 * start only at night while the phone lies unused on its charger; a running
 * trial stops and reverts at once when the screen comes on, a call rings, the
 * audio mode changes or the charger is pulled ([Interrupt]). Android's own
 * "device idle" ignores calls and audio, so the app checks them itself.
 */

/** What the silent window reads, in one pass. A null is unreadable and keeps the window closed, but where noted. */
data class QuietInputs(
    /** The screen is on (PowerManager.isInteractive). */
    val interactive: Boolean?,
    /** How long the screen has been off (dumpsys power, realtime). */
    val screenOffForMs: Long?,
    val plugged: Boolean?,
    val batteryPercent: Int?,
    /** A call rings, dials, is up or on hold (Telecom, the registry). */
    val inCall: Boolean?,
    /** The audio mode is normal and nothing plays (no VoIP call, no music, no ringtone). */
    val audioIdle: Boolean?,
    val roaming: Boolean?,
    val wifiConnected: Boolean?,
    /** Other apps' traffic, scaled to one minute; the owner build's own is left out. */
    val trafficBytesPerMinute: Long?,
    /** Time to the next alarm clock; null: none set. */
    val nextAlarmInMs: Long?,
    /** Local time, minutes after midnight. */
    val minuteOfDay: Int,
)

/** Why the silent window is closed. */
enum class QuietBlock(val wire: String, val plain: String) {
    SCREEN_ON("screen_on", "the screen is on"),
    SCREEN_ON_RECENTLY("screen_on_recently", "the screen was on in the last hour"),
    NOT_CHARGING("not_charging", "the phone is not on its charger"),
    BATTERY_LOW("battery_low", "the battery is under 50 %"),
    IN_CALL("in_call", "a call is ringing or up"),
    AUDIO_IN_USE("audio_in_use", "audio is playing or a call app holds the audio"),
    ROAMING("roaming", "the phone is roaming"),
    NO_WIFI("no_wifi", "the phone is not on Wi-Fi"),
    DATA_IN_USE("data_in_use", "an app is using the network"),
    ALARM_SOON("alarm_soon", "an alarm rings within 45 minutes"),
    OUTSIDE_NIGHT("outside_night", "trials wait for the night (02:00 to 05:00)"),
    NOT_HEALTHY("not_healthy", "the phone's IMS or mobile service is not as it should be; trials wait"),
}

object QuietWindow {
    /** The screen has been off at least this long before a trial ("no recent screen-on"). */
    const val SCREEN_OFF_MS = 60 * 60_000L

    /** Other apps' traffic below this per minute counts as no foreground data use. */
    const val TRAFFIC_BYTES_PER_MINUTE = 50 * 1024L
    const val MIN_BATTERY_PERCENT = 50

    /** The next alarm must be further away than this. */
    const val ALARM_MARGIN_MS = 45 * 60_000L

    /** The traffic sample before the first trial step of a night, and before each later step. */
    const val FIRST_SAMPLE_MS = 60_000L
    const val STEP_SAMPLE_MS = 20_000L

    /** The night window for trials, local time: 02:00 to 05:00. */
    const val NIGHT_FROM_MINUTE = 2 * 60
    const val NIGHT_UNTIL_MINUTE = 5 * 60

    /** A night's trials end this long after its first step started. */
    const val SESSION_MS = 20 * 60_000L

    fun night(minuteOfDay: Int): Boolean = minuteOfDay in NIGHT_FROM_MINUTE until NIGHT_UNTIL_MINUTE

    /** The light read-only check: the screen off and no call. Null: it may run. */
    fun checkBlocker(i: QuietInputs): QuietBlock? = when {
        i.interactive != false -> QuietBlock.SCREEN_ON
        i.inCall != false -> QuietBlock.IN_CALL
        else -> null
    }

    /**
     * Trials: every condition of the report. Null when the window is open.
     * [nightOnly] false lets a revert of a failing kept value run outside the
     * night; [traffic] false skips the traffic sample (the look before the
     * sample is worth taking).
     */
    fun trialBlocker(i: QuietInputs, nightOnly: Boolean, traffic: Boolean = true): QuietBlock? = when {
        i.interactive != false -> QuietBlock.SCREEN_ON
        i.screenOffForMs == null || i.screenOffForMs < SCREEN_OFF_MS -> QuietBlock.SCREEN_ON_RECENTLY
        i.plugged != true -> QuietBlock.NOT_CHARGING
        i.batteryPercent == null || i.batteryPercent < MIN_BATTERY_PERCENT -> QuietBlock.BATTERY_LOW
        i.inCall != false -> QuietBlock.IN_CALL
        i.audioIdle != true -> QuietBlock.AUDIO_IN_USE
        i.roaming != false -> QuietBlock.ROAMING
        i.wifiConnected != true -> QuietBlock.NO_WIFI
        i.nextAlarmInMs != null && i.nextAlarmInMs < ALARM_MARGIN_MS -> QuietBlock.ALARM_SOON
        nightOnly && !night(i.minuteOfDay) -> QuietBlock.OUTSIDE_NIGHT
        traffic && (i.trafficBytesPerMinute == null || i.trafficBytesPerMinute > TRAFFIC_BYTES_PER_MINUTE) -> QuietBlock.DATA_IN_USE
        else -> null
    }

    /** Bytes over a sample of [sampleMs], scaled to one minute; null when a counter was unreadable or went back. */
    fun perMinute(beforeBytes: Long?, afterBytes: Long?, sampleMs: Long): Long? {
        if (beforeBytes == null || afterBytes == null || afterBytes < beforeBytes || sampleMs <= 0) return null
        return (afterBytes - beforeBytes) * 60_000L / sampleMs
    }

    /** The night a trial belongs to, as the local date it starts on ("2026-10-20"). */
    fun nightOf(now: ZonedDateTime): String = now.toLocalDate().toString()
}

/**
 * What stops a background run at once: the screen on, a call ringing or up,
 * the audio mode changing, or the charger pulled. Null: carry on. The owner's
 * own runs have none.
 */
fun interface Interrupt {
    suspend fun reason(): String?
}

/** A background run met an [Interrupt]; whatever it changed is reverted, and the run is tried again later. */
class RunInterrupted(val why: String) : SafetyException("$INTERRUPTED$why", "background run stopped: $why") {
    companion object {
        const val INTERRUPTED = "interrupted_"
    }
}

/** `dumpsys power`: how long the screen has been off. Read with DUMP; no other field is kept. */
object PowerDump {
    private val WAKEFULNESS = Regex("(?m)^\\s*mWakefulness=(\\w+)\\s*$")
    private val SLEEP_AGO = Regex("mLastGlobalSleepTimeRealtime=\\d+ \\((\\d+) ms ago\\)")
    private val WAKE_AGO = Regex("mLastGlobalWakeTimeRealtime=\\d+ \\((\\d+) ms ago\\)")
    private val SLEEP_AGO_UPTIME = Regex("mLastSleepTime=\\d+ \\((\\d+) ms ago\\)")
    private val WAKE_AGO_UPTIME = Regex("mLastWakeTime=\\d+ \\((\\d+) ms ago\\)")

    /**
     * Milliseconds since the screen went off; 0 while it is on (Awake), null
     * when the dump does not say. The realtime fields win over the uptime ones,
     * which stop in deep sleep and so read shorter (on the safe side).
     */
    fun screenOffForMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val wakefulness = WAKEFULNESS.find(text)?.groupValues?.get(1) ?: return null
        if (wakefulness.equals("Awake", ignoreCase = true)) return 0
        val sleep = (SLEEP_AGO.find(text) ?: SLEEP_AGO_UPTIME.find(text))?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val wake = (WAKE_AGO.find(text) ?: WAKE_AGO_UPTIME.find(text))?.groupValues?.get(1)?.toLongOrNull()
        // Woken after the last sleep (a wake the dump has not settled yet): the screen counts as just on.
        if (wake != null && wake < sleep) return 0
        return sleep
    }
}
