package io.github.open_carrier_data.check.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import io.github.open_carrier_data.check.core.SettingsScreen

/**
 * Opens a Settings screen for the guided switch step (OCD round 23, AP3),
 * with public Android intent actions only. Opening a screen changes
 * nothing; the person flips their own switch there. No `<queries>` entry is
 * needed: the app tries each screen in turn and falls back to the general
 * network settings when one does not exist.
 */
object SettingsScreens {
    fun intent(screen: SettingsScreen, subId: Int?): Intent = when (screen) {
        SettingsScreen.SIM_NETWORK -> Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            subId?.takeIf { it >= 0 }?.let { putExtra(Settings.EXTRA_SUB_ID, it) }
        }
        SettingsScreen.CALL_SETTINGS -> Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)
        SettingsScreen.NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }

    /** Starts the first of [screens] that exists; null when none does. */
    fun open(context: Context, screens: List<SettingsScreen>, subId: Int?): SettingsScreen? {
        for (screen in screens) {
            try {
                context.startActivity(intent(screen, subId))
                return screen
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return null
    }
}
