package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.owner.core.DataBasis
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Interrupt
import io.github.open_carrier_data.check.owner.core.OcdApnRow
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PowerDump
import io.github.open_carrier_data.check.owner.core.QuietInputs
import io.github.open_carrier_data.check.owner.core.QuietWindow
import io.github.open_carrier_data.check.owner.core.RegistryText
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TestBasis
import kotlinx.coroutines.delay
import java.io.File
import java.time.ZonedDateTime

/** Reads the silent window's inputs on the phone ([QuietWindow]). */
class QuietReader(private val context: Context) {
    /** [sampleMs] > 0 measures other apps' traffic over that long first, then reads the rest. */
    suspend fun read(phone: OwnerPhone, sampleMs: Long): QuietInputs {
        val traffic = if (sampleMs > 0) {
            val before = othersBytes()
            delay(sampleMs)
            QuietWindow.perMinute(before, othersBytes(), sampleMs)
        } else {
            null
        }
        val battery = battery(context)
        val now = ZonedDateTime.now()
        return QuietInputs(
            interactive = runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull(),
            screenOffForMs = PowerDump.screenOffForMs(phone.powerText()),
            plugged = battery.first,
            batteryPercent = battery.second,
            inCall = inCall(context, phone.registryText()),
            audioIdle = audioIdle(context),
            roaming = roaming(context),
            wifiConnected = phone.wifiConnected(),
            trafficBytesPerMinute = traffic,
            nextAlarmInMs = runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()
                ?.let { it - System.currentTimeMillis() },
            minuteOfDay = now.hour * 60 + now.minute,
        )
    }

    companion object {
        /** Plugged in, and the battery level in percent; nulls when the sticky battery broadcast says nothing. */
        fun battery(context: Context): Pair<Boolean?, Int?> {
            val intent = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return null to null
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 }?.let { it != 0 }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return plugged to (if (level >= 0 && scale > 0) level * 100 / scale else null)
        }

        /** A call ringing, dialling, up or held: Telecom's view and the registry's, either one is enough. */
        @SuppressLint("MissingPermission")
        fun inCall(context: Context, registry: String?): Boolean? {
            val telecom = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull()
            return if (telecom == true || RegistryText.anyCallActive(registry)) true else telecom?.let { false }
        }

        /** No call app holds the audio (mode normal) and nothing plays. */
        fun audioIdle(context: Context): Boolean? = runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return null
            audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive
        }.getOrNull()

        @SuppressLint("MissingPermission")
        fun roaming(context: Context): Boolean? = runCatching {
            val subs = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
            subs.any { tm.createForSubscriptionId(it).isNetworkRoaming }
        }.getOrNull()

        /** Every app's traffic but the owner build's own, in bytes; null when a counter is unsupported. */
        fun othersBytes(): Long? {
            val total = listOf(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes())
            val own = listOf(TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()))
            if ((total + own).any { it == TrafficStats.UNSUPPORTED.toLong() }) return null
            return total.sum() - own.sum()
        }
    }
}

/**
 * Stops a background run at once: the screen on, the charger pulled, a call
 * ringing or up, or the audio mode leaving normal (a VoIP call, a ringtone).
 */
class AndroidInterrupt(private val context: Context) : Interrupt {
    @SuppressLint("MissingPermission")
    override suspend fun reason(): String? {
        if (runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() != false) return "screen_on"
        if (QuietReader.battery(context).first != true) return "unplugged"
        if (runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull() != false) return "call"
        if (runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull() != AudioManager.MODE_NORMAL) return "audio_mode"
        return null
    }
}

/**
 * The revert alarm, armed before each background change (the report of
 * 2026-10-20): an exact alarm that also fires in doze queues the cleanup,
 * which reverts a trial a killed process left. Disarmed when the step ends.
 * The app is on the power-save allowlist (sysconfig), which also lets it set
 * exact alarms; without that the alarm is inexact.
 */
object RevertAlarm {
    const val ACTION = "io.github.open_carrier_data.check.owner.REVERT_ALARM"
    const val AFTER_MS = 10 * 60_000L

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 7, Intent(context, RevertAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun arm(context: Context, afterMs: Long = AFTER_MS) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + afterMs
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(context))
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(context))
            }
        }
    }

    fun disarm(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(intent(context)) }
    }
}

/** The revert alarm went off: a trial still recorded means its step died; the cleanup reverts it. Not exported. */
class RevertAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RevertAlarm.ACTION) return
        val state = FileStateStore(context).read()
        if (state.trialsLive()) OwnerScheduler.cleanup(context, policy = ExistingWorkPolicy.KEEP)
    }
}

internal fun OwnerState.trialsLive(): Boolean = overrides.any { it.hold == Hold.TRIAL } || apns.any { it.hold == Hold.TRIAL }

/** What the results rest on: the SIM's OCD data, the ROM build, the app build ([TestBasis]). */
object OwnerBasis {
    /**
     * The ROM and app build, with the data the app holds as [TestBasis.release]
     * (the pinned main commit, or "bundled"). A record takes the SIM's own data
     * key instead ([dataKeys], [TestBasis.forSim]); the ROM build alone is what
     * the calls code reads.
     */
    fun current(context: Context, state: OwnerState): TestBasis {
        val apk = File(context.applicationInfo.sourceDir)
        return TestBasis(
            release = state.pin?.let { "main@${it.commit.take(12)}" } ?: "bundled",
            rom = "${Build.FINGERPRINT}@${Build.TIME}",
            app = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})/${apk.length()}/${apk.lastModified()}",
        )
    }

    /** Round 29 F4: each SIM's own data key: its OCD answers on this phone and OCD's APN rows of its scope ([rows]). */
    fun dataKeys(context: Context, bundle: OcdBundle, sims: List<OwnerSim>, rows: (OwnerSim) -> List<OcdApnRow>): Map<SimKey, String> {
        val device = OwnerWorker.device(context)
        return sims.associate { sim -> sim.key to DataBasis.key(OwnerRunner.answers(bundle, sim, device), rows(sim), sim.identity) }
    }

    /** The basis one SIM's results are recorded against. */
    fun forSim(context: Context, state: OwnerState, bundle: OcdBundle, sim: OwnerSim, rows: List<OcdApnRow>): TestBasis =
        current(context, state).forSim(dataKeys(context, bundle, listOf(sim)) { rows }, sim.key)
}
