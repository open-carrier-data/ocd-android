package io.github.open_carrier_data.check

import android.content.Context
import android.content.Intent
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
import io.github.open_carrier_data.check.owner.OwnerActivity
import io.github.open_carrier_data.check.owner.OwnerNotifications
import io.github.open_carrier_data.check.owner.OwnerScheduler

/**
 * The owner build's entry points (src/owner only; debug and release compile
 * the no-op in src/readonly). On start: the weekly schedule is registered
 * (unless the owner turned it off), the leftover cleanup is queued, and the
 * live-settings notification is refreshed.
 */
object OwnerHook {
    const val PRESENT = true

    fun onAppStart(context: Context) {
        val state = FileStateStore(context).read()
        OwnerScheduler.ensureWeekly(context, state.scheduleOn)
        OwnerScheduler.cleanup(context, policy = ExistingWorkPolicy.KEEP)
        OwnerNotifications.refresh(context, state)
    }

    @Composable
    fun Entry() {
        val context = LocalContext.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Owner build", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This build tests and applies carrier settings on the owner's S20. The check below stays read-only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { context.startActivity(Intent(context, OwnerActivity::class.java)) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Open Carrier Data owner screen")
                }
            }
        }
    }
}
