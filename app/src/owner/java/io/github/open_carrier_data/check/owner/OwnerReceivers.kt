package io.github.open_carrier_data.check.owner

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CarrierConfigManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import io.github.open_carrier_data.check.R
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Notice
import io.github.open_carrier_data.check.owner.core.Notices
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.StatusSentence

/**
 * The owner hears little (2026-10-20, "so the user does not notice it that
 * much"). While a test runs, a quiet notification (the lowest importance: no
 * sound, no status-bar icon, folded at the bottom of the shade) says so with
 * "Stop test" and "Safe mode". Round 30 removed Undo: the way back is safe
 * mode, which CarrierSettings provides and which works without this app.
 * Routine work posts nothing. A normal notification comes only when a value
 * changed or something failed ([Notices]); a failure carries "Safe mode" too.
 */
object OwnerNotifications {
    private const val LIVE_CHANNEL = "owner_live_quiet"
    private const val EVENTS_CHANNEL = "owner_events"

    /** The channel of the builds before 2026-10-20 (low importance, with an icon); removed. */
    private const val OLD_LIVE_CHANNEL = "owner_live"
    private const val LIVE_ID = 4712
    private const val EVENT_ID = 4714

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel(OLD_LIVE_CHANNEL) }
        manager.createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL, "Active changes", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Open Carrier Data is testing or has changed a setting"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(EVENTS_CHANNEL, "Changes and failures", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a setting is changed or a test fails"
            },
        )
    }

    private fun open(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, OwnerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // allowed() checks POST_NOTIFICATIONS first; lint does not follow it into the helper.
    @SuppressLint("MissingPermission")
    fun refresh(context: Context, state: OwnerState) {
        val manager = NotificationManagerCompat.from(context)
        if (!state.anyKept) {
            manager.cancel(LIVE_ID)
            return
        }
        if (!allowed(context)) return
        channels(context)
        val testing = state.overrides.any { it.hold == Hold.TRIAL } || state.apns.any { it.hold == Hold.TRIAL } || state.trial30 != null
        val text = StatusSentence.keptChanges(state).joinToString(", ")
        val notification = NotificationCompat.Builder(context, LIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (testing) "Testing a carrier setting" else Notices.CHANGED_TITLE)
            .apply { if (!testing && text.isNotEmpty()) setContentText(text) }
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context))
            .apply {
                if (testing) addAction(0, "Stop test", action(context, NoticeActionReceiver.ACTION_STOP, 2))
                if (state.datasetStatus?.available == true && state.datasetStatus.safeMode.on != true) addAction(0, "Safe mode", action(context, NoticeActionReceiver.ACTION_SAFE_MODE, 3))
            }
            .build()
        runCatching { manager.notify(LIVE_ID, notification) }
    }

    /** A change or a failure; replaces the previous one. */
    @SuppressLint("MissingPermission")
    fun notice(context: Context, notice: Notice) {
        if (!allowed(context)) return
        channels(context)
        val notification = NotificationCompat.Builder(context, EVENTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(notice.title)
            .setContentText(notice.text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text.take(600)))
            .setAutoCancel(true)
            .setContentIntent(open(context))
            .apply {
                val status = FileStateStore(context).read().datasetStatus
                if (notice.failed && status?.available == true && !status.safeMode.on) addAction(0, "Safe mode", action(context, NoticeActionReceiver.ACTION_SAFE_MODE, 4))
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(EVENT_ID, notification) }
    }

    private fun action(context: Context, what: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, NoticeActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The notification's "Stop test" and "Safe mode": each queues its work, nothing else. Not exported. */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> OwnerScheduler.stopTests(context)
            // Safe mode on, through CarrierSettings (round 30): the build's dataset serves and tests stop until the owner turns it off.
            ACTION_SAFE_MODE -> OwnerScheduler.safeMode(context, on = true)
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.open_carrier_data.check.owner.STOP_TEST"
        const val ACTION_SAFE_MODE = "io.github.open_carrier_data.check.owner.SAFE_MODE_ON"
    }
}

/**
 * The background service's triggers, with no screen open (the owner's
 * "system service that does all of this in the background"). Boot and SIM
 * changes: the cleanup pass reverts leftovers, clears a keep of
 * a SIM that left its slot, and applies again the keeps of the SIM now in
 * the slot (the owner's "re-apply per matching SIM at boot or SIM change").
 * The carrier config broadcast also follows the owner build's own writes;
 * the pass is idempotent, so that ends at once.
 */
class OwnerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // A boot, or an update of the app itself (an adb install over the system copy).
            // A boot, a soft restart (BOOT_COMPLETED comes again; the window counts from the system server's start), or the app's own update.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                OwnerScheduler.cleanup(context, afterBoot = true, serverUpMs = serverUpMs())
                OwnerScheduler.ensureQuiet(context, FileStateStore(context).read().scheduleOn)
            }
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                val state = FileStateStore(context).read()
                // KEEP: a pass already queued or running is never cancelled by its own writes.
                if (state.anyLive) OwnerScheduler.cleanup(context, afterBoot = true, policy = ExistingWorkPolicy.KEEP, serverUpMs = serverUpMs())
                // Round 30's voice-loss trigger: a few minutes after any carrier config change while an installed dataset serves.
                if (state.datasetStatus?.available == true && state.trial30 == null) OwnerScheduler.voiceCheck(context)
            }
        }
    }

    /**
     * How long the system server has run, from its start property when the
     * app may read it; else 0, as if it had just started (the cleanup then
     * waits the full 10 minutes).
     */
    private fun serverUpMs(): Long = runCatching {
        val start = (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "sys.system_server.start_elapsed") as String).toLong()
        (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
    }.getOrDefault(0L)
}
