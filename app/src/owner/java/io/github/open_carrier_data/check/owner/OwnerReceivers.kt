package io.github.open_carrier_data.check.owner

import android.Manifest
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
import io.github.open_carrier_data.check.owner.core.OwnerState

/**
 * While any override or APN row of the owner build is live, an ongoing
 * notification says so and offers a one-tap "Revert all" (round 25 OB5). The
 * notification only reverts; it never starts a test.
 */
object OwnerNotifications {
    private const val CHANNEL = "owner_live"
    private const val ID = 4712

    fun refresh(context: Context, state: OwnerState) {
        val manager = NotificationManagerCompat.from(context)
        if (!state.anyLive && state.stop == null) {
            manager.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live test settings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the owner build holds a carrier setting on this phone"
            },
        )
        val trials = state.overrides.count { it.hold == Hold.TRIAL } + state.apns.count { it.hold == Hold.TRIAL }
        val kept = state.overrides.count { it.hold == Hold.KEPT } + state.apns.count { it.hold == Hold.KEPT }
        val text = buildString {
            if (state.stop != null) append("Stopped: ").append(state.stop.take(80)).append(". ")
            if (kept > 0) append("$kept kept: ").append(state.overrides.filter { it.hold == Hold.KEPT }.joinToString { "${it.key.substringBefore("_bool")}=${it.value}" }).append(". ")
            if (trials > 0) append("$trials under test.")
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, OwnerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val revert = PendingIntent.getBroadcast(
            context, 1, Intent(context, RevertAllReceiver::class.java).setAction(RevertAllReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("OCD Check owner: carrier settings changed on this phone")
            .setContentText(text.ifBlank { "Live settings" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Revert all", revert)
            .build()
        runCatching { manager.notify(ID, notification) }
    }
}

/** The notification's "Revert all": queues the revert, nothing else. Not exported. */
class RevertAllReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) OwnerScheduler.revertAll(context)
    }

    companion object {
        const val ACTION = "io.github.open_carrier_data.check.owner.REVERT_ALL"
    }
}

/**
 * Boot and SIM changes: the cleanup pass reverts leftovers, clears a keep of
 * a SIM that left its slot, and applies again the keeps of the SIM now in
 * the slot (the owner's "re-apply per matching SIM at boot or SIM change").
 * The carrier config broadcast also follows the owner build's own writes;
 * the pass is idempotent, so that ends at once.
 */
class OwnerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                OwnerScheduler.cleanup(context, afterBoot = true)
                OwnerScheduler.ensureWeekly(context, FileStateStore(context).read().scheduleOn)
            }
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                val state = FileStateStore(context).read()
                // KEEP: a pass already queued or running is never cancelled by its own writes.
                if (state.anyLive) OwnerScheduler.cleanup(context, afterBoot = true, policy = ExistingWorkPolicy.KEEP)
            }
        }
    }
}
