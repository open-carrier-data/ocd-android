/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * The system broadcasts the dataset acts on, all protected (only the system sends them):
 * - LOCKED_BOOT_COMPLETED and BOOT_COMPLETED: the boot check (8.1), the daily alarm and the
 *   15-minute check of the OCD app's on/off (round 31);
 * - PHONE_STATE: the emergency-call sighting and the end of a call (8.4);
 * - CARRIER_CONFIG_CHANGED: the crash guard and the served-versus-effective check (8.3).
 * The boot check runs on CarrierSettings's own worker (an APN install can take minutes); the
 * rest runs off the main thread. Nothing here throws.
 */
class OcdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
                OcdAndroid.armDaily(context, force = true)
                OcdAndroid.armAppCheck(context, force = true)
                val why = if (action == Intent.ACTION_BOOT_COMPLETED) "boot_completed" else "locked_boot_completed"
                OcdAndroid.runOnWorker { OcdAndroid.engine(context).bootCheck(why) }
                return
            }
        }
        val result = goAsync()
        Thread {
            try {
                when (action) {
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        OcdAndroid.onCallState(context, idle = state == TelephonyManager.EXTRA_STATE_IDLE)
                    }
                    CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                        val sub = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        OcdAndroid.onConfigChanged(context, sub)
                    }
                }
            } catch (e: Throwable) {
                Log.e(OcdAndroid.TAG, "$action not handled", e)
            } finally {
                result.finish()
            }
        }.start()
    }
}

/**
 * The dataset's own alarms (not exported): the daily check, the retry of waiting reloads and
 * installs, and the light check of the OCD app's on/off (R31.4).
 */
class OcdAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            OcdAndroid.ACTION_DAILY -> OcdAndroid.runOnWorker { OcdAndroid.engine(context).bootCheck("daily") }
            OcdAndroid.ACTION_RETRY -> OcdAndroid.runOnWorker { OcdAndroid.engine(context).processPending("retry") }
            OcdAndroid.ACTION_APP_CHECK -> OcdAndroid.runOnWorker { OcdAndroid.engine(context).appCheck("alarm") }
        }
    }
}
