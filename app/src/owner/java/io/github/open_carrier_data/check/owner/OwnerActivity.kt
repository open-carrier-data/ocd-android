package io.github.open_carrier_data.check.owner

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.ui.OcdCheckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date

data class OwnerUi(
    val gate: OwnerGate? = null,
    val state: OwnerState = OwnerState(),
    val sims: List<OwnerSim>? = null,
    val weekly: String = "not read",
    val run: String = "idle",
    val message: String? = null,
)

class OwnerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FileStateStore(app)
    private val _ui = MutableStateFlow(OwnerUi())
    val ui: StateFlow<OwnerUi> = _ui

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
    }

    suspend fun refresh() {
        val context = getApplication<Application>()
        val gate = withContext(Dispatchers.Default) { OwnerAccess.gate(context) }
        val sims = runCatching { OwnerData.sims(context) }.getOrNull()
        val wm = WorkManager.getInstance(context)
        val weekly = withContext(Dispatchers.IO) {
            runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.WEEKLY).get() }.getOrNull()
        }?.firstOrNull { !it.state.isFinished }?.let { info ->
            "registered (${info.state.name.lowercase()}), next run after ${DateFormat.getDateTimeInstance().format(Date(info.nextScheduleTimeMillis))}"
        } ?: "not registered"
        val run = withContext(Dispatchers.IO) { runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.RUN).get() }.getOrNull() }.orEmpty()
        val running = run.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        _ui.value = _ui.value.copy(
            gate = gate,
            state = store.read(),
            sims = sims,
            weekly = weekly,
            run = if (running > 0) "$running step(s) to go" else "idle",
        )
    }

    private fun say(text: String) {
        _ui.value = _ui.value.copy(message = text)
    }

    fun pull() {
        OwnerScheduler.pull(getApplication(), force = false)
        say("Pull queued: latest release from GitHub, every file checked against SHA256SUMS.")
    }

    fun test(offs: Boolean, voiceCheck: Boolean, apns: Boolean) = viewModelScope.launch {
        val line = OwnerScheduler.runTests(getApplication(), scheduled = false, offs = offs, voiceCheckMs = if (voiceCheck) 120_000 else 0, apns = apns)
        say(line)
        refresh()
    }

    fun revertAll() {
        OwnerScheduler.revertAll(getApplication())
        say("Revert all queued.")
    }

    fun rebootProbe() {
        OwnerScheduler.rebootProbe(getApplication())
        say("Reboot check queued: VoLTE is set to the value it has and left live. Reboot when the log says so.")
    }

    fun cleanup() {
        OwnerScheduler.cleanup(getApplication())
        say("Leftover check queued.")
    }

    fun setSchedule(on: Boolean) = viewModelScope.launch {
        store.update { it.copy(scheduleOn = on).logged(System.currentTimeMillis(), "schedule", if (on) "weekly tests on" else "weekly tests off") }
        OwnerScheduler.ensureWeekly(getApplication(), on)
        refresh()
    }

    fun clearStop() = viewModelScope.launch {
        store.update { it.copy(stop = null).logged(System.currentTimeMillis(), "stop", "the owner cleared the stop flag") }
        refresh()
    }

    fun issueUrl(): String? = store.read().runs.lastOrNull { it.features.isNotEmpty() }?.let(IssueReport::url)
}

/** The owner tools: data, the write gate, live settings, tests, upload, schedule and the log. */
class OwnerActivity : ComponentActivity() {
    private val viewModel: OwnerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
        }
        io.github.open_carrier_data.check.OwnerHook.onAppStart(applicationContext)
        setContent { OcdCheckTheme { OwnerScreen(viewModel, ::openIssue) } }
    }

    private fun openIssue() {
        val url = viewModel.issueUrl()
        if (url == null) {
            android.widget.Toast.makeText(this, "No finished test run, or the privacy scan stopped the report.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Opens the prefilled form; the owner reads it and submits it himself.
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun OwnerScreen(viewModel: OwnerViewModel, openIssue: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OCD Check owner tools", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tests and applies carrier settings on this phone. Never an emergency, e911, ECBM, WPS, TTY, RTT or iwlan key; never an " +
                    "emergency call; nothing changes in a call, in emergency callback mode, within 10 minutes of an emergency call, " +
                    "while roaming, or when the state cannot be read. Overrides are never persistent.",
                style = MaterialTheme.typography.bodySmall,
            )
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            val gate = ui.gate
            OwnerCard("Access") {
                if (gate == null) {
                    Text("Reading…")
                } else {
                    Line("Route", gate.route?.plain ?: "none")
                    Line("Privileged system app", if (gate.privilegedGranted) "yes, permissions granted" else "no")
                    Line("Shizuku", if (!gate.shizukuRunning) "not running" else "running as ${if (gate.shizukuRoot) "root" else "shell"}${if (gate.shizukuPermitted) "" else ", not granted"}")
                    Line("Build type", gate.buildType)
                    Line("Writes", if (gate.canWrite) "allowed" else "refused: ${gate.reason}")
                    if (gate.shizukuRunning && !gate.shizukuPermitted) {
                        OutlinedButton(onClick = { runCatching { Shizuku.requestPermission(4713) } }) { Text("Grant Shizuku") }
                    }
                }
                ui.state.stop?.let {
                    Text("STOPPED: $it", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { viewModel.clearStop() }) { Text("Clear the stop flag (after review)") }
                }
            }
            OwnerCard("Data") {
                Text(ui.state.release.line(), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = viewModel::pull) { Text("Pull the latest release") }
            }
            OwnerCard("SIMs") {
                val sims = ui.sims
                when {
                    sims == null -> Text("Reading…")
                    sims.isEmpty() -> Text("No SIM, nothing to test.")
                    else -> sims.forEach { Line("Slot ${it.slot + 1}", "${it.name} (${it.identity.mccmnc})") }
                }
            }
            OwnerCard("Live on this phone") {
                val s = ui.state
                if (!s.anyLive) Text("Nothing. The phone runs its own carrier settings.")
                s.overrides.forEach { Line("Slot ${it.slot + 1} ${it.key}", "${it.value} (${if (it.hold == Hold.KEPT) "kept" else "under test"})") }
                s.apns.forEach { Line("APN ${it.label}", if (it.hold == Hold.KEPT) "kept" else "under test") }
                Line("Leftover check", s.lastCleanup ?: "not run yet")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::revertAll, enabled = s.anyLive) { Text("Revert all") }
                    OutlinedButton(onClick = viewModel::cleanup) { Text("Check for leftovers") }
                }
            }
            OwnerCard("First use (round 25)") {
                Text(
                    "1. Only after the S20 build's boot check, never together with another build change. " +
                        "2. The first apply on the cable, with a lab --read before and after. " +
                        "3. One override left live through a reboot must be gone afterwards; until then nothing is kept, " +
                        "no off is tested and the weekly run changes nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Line("Check 3", if (ui.state.rebootCleared) "passed" else if (ui.state.rebootProbeAtMs != null) "override live; reboot now" else "not done")
                if (!ui.state.rebootCleared) {
                    OutlinedButton(onClick = viewModel::rebootProbe, enabled = gate?.canWrite == true && ui.sims?.isNotEmpty() == true && ui.state.rebootProbeAtMs == null) {
                        Text("Start check 3")
                    }
                }
            }
            var offs by remember { mutableStateOf(true) }
            var voice by remember { mutableStateOf(false) }
            var apns by remember { mutableStateOf(true) }
            OwnerCard("Test") {
                Line("Run", ui.run)
                Check("Test the off values too", offs) { offs = it }
                Check("Voice check: I place a call myself while a voice feature is off (2 minutes)", voice) { voice = it }
                Check("Test OCD's internet APN rows the phone lacks", apns) { apns = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.test(offs, voice, apns) }, enabled = gate?.canWrite == true && ui.sims?.isNotEmpty() == true) { Text("Test now") }
                    OutlinedButton(onClick = { viewModel.test(offs, voice, apns) }, enabled = gate?.canWrite == true && ui.state.anyLive) { Text("Test again") }
                }
            }
            OwnerCard("Last result") {
                val run = ui.state.runs.lastOrNull()
                if (run == null) {
                    Text("No run yet.")
                } else {
                    Text("${run.simName ?: "-"} ${run.sim?.mccmnc ?: ""}, ${run.releaseTag ?: ""}${run.note?.let { ", $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    run.features.forEach { f ->
                        Line(
                            f.feature.label,
                            "phone ${f.baseline.onOff()}, OCD ${f.ocd.value.onOff()}, best ${f.best.onOff()}" +
                                (if (f.applied) ", applied" else "") + (f.refused?.let { ", refused: $it" } ?: ""),
                        )
                    }
                    run.apns.forEach { Line("APN ${it.label}", it.status + (it.dataWorks?.let { w -> if (w) ", data works" else ", no data" } ?: "")) }
                    OutlinedButton(onClick = openIssue, enabled = run.features.isNotEmpty()) { Text("Report on GitHub (opens a prefilled issue)") }
                }
            }
            OwnerCard("Schedule") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ui.state.scheduleOn, onCheckedChange = { viewModel.setSchedule(it) })
                    Text("  Weekly: pull, then test (idle, charging, Wi-Fi; never in a call or roaming)", style = MaterialTheme.typography.bodySmall)
                }
                Text(ui.weekly, style = MaterialTheme.typography.bodySmall)
            }
            OwnerCard("Log") {
                ui.state.log.takeLast(25).reversed().forEach {
                    Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.atMs))} ${it.kind}: ${it.text}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OwnerCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Boolean?.onOff(): String = when (this) {
    true -> "on"
    false -> "off"
    null -> "-"
}
