package io.github.open_carrier_data.check.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.R
import io.github.open_carrier_data.check.core.OcdBundle
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OCD's rule round 32, U1: a ROM that bundles a read-only build may set a daily check instead of the
 * person's tap (the overlayable `ocd_daily_data_check`, false by default). Only in the read-only
 * builds; the owner and ROM tester builds pull through their own path. The job runs the same pull
 * ([PublicDataPhone.pull]), at most one download a day on metered data, and writes nothing but the
 * app's own files.
 */
object DailyDataCheck {
    const val JOB_ID = 4_032_001

    fun enabled(context: Context): Boolean = BuildConfig.TESTER.isEmpty() && runCatching { context.resources.getBoolean(R.bool.ocd_daily_data_check) }.getOrDefault(false)

    /** Schedules the daily check when the ROM set it, and cancels it otherwise. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(context)) {
            if (scheduler.getPendingJob(JOB_ID) != null) scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, DailyDataJob::class.java))
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
    }
}

/** The daily check's job: one pull on its own thread. */
class DailyDataJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (!DailyDataCheck.enabled(this)) return false
        thread(name = "ocd-daily-data-check") {
            runCatching {
                val bundled = assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip)
                PublicDataPhone(this).pull(bundled)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
