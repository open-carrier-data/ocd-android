package io.github.open_carrier_data.check

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingWorkPolicy
import io.github.open_carrier_data.check.owner.FileStateStore
import io.github.open_carrier_data.check.owner.OwnerAccess
import io.github.open_carrier_data.check.owner.OwnerActivity
import io.github.open_carrier_data.check.owner.OwnerNotifications
import io.github.open_carrier_data.check.owner.OwnerScheduler

/**
 * Where the check screen meets the background work, in the one build (rule rounds 46 and 47). What runs depends on
 * how the app is installed, never on who signed it: a privileged system app in a ROM runs the background work; a hand
 * install holds no privileged permission and stays passive plus the checks the person taps.
 */
object OwnerHook {
    /** A privileged system app in a ROM, the one install that can test or install data. */
    fun tester(context: Context): Boolean = OwnerAccess.privilegedGranted(context)

    fun onAppStart(context: Context) {
        if (!tester(context)) return
        hideLauncher(context)
        val state = FileStateStore(context).read()
        OwnerScheduler.ensureQuiet(context, state.scheduleOn)
        OwnerScheduler.cleanup(context, policy = ExistingWorkPolicy.KEEP)
        OwnerNotifications.refresh(context, state)
    }

    /** A ROM's system app has no launcher icon: its screen is the Settings entry. */
    fun hideLauncher(context: Context) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, "${context.packageName}.ui.Launcher"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** A card at the top of the check screen, only where the tests can run. */
    @Composable
    fun Entry() {
        val context = LocalContext.current
        if (!tester(context)) return
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Tests and carrier data", style = MaterialTheme.typography.titleMedium)
                Text("This page only reads. Tests and carrier data are on the Open Carrier Data page.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { context.startActivity(Intent(context, OwnerActivity::class.java)) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Open Carrier Data")
                }
            }
        }
    }
}
