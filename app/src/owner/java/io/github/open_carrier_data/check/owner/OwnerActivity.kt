package io.github.open_carrier_data.check.owner

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.IwlanDumps
import io.github.open_carrier_data.check.owner.core.LogEvent
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.ui.MainActivity
import io.github.open_carrier_data.check.ui.OcdCheckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class OwnerUi(
    /** The first read is done: until then the screen shows no status, so nothing moves on load. */
    val loaded: Boolean = false,
    val gate: OwnerGate? = null,
    val state: OwnerState = OwnerState(),
    /** The SIMs the runner tests (with what OCD matching reads); null until read. */
    val sims: List<OwnerSim>? = null,
    /** The same SIMs as Settings names them; null when the list is not readable. */
    val simViews: List<SimView>? = null,
    /** The read-only check's T2 passes on this phone model. */
    val calls: List<CallsTested> = emptyList(),
    /** OCD's access point names for each SIM (by subscription ID) in the pulled release; missing: not known. */
    val apnCandidates: Map<Int, Int> = emptyMap(),
    /** The data release the app ships ("2026.10"), shown when none is pulled. */
    val bundledTag: String? = null,
    /** Steps of the owner's or the night's test still to run. */
    val stepsToGo: Int = 0,
    val message: String? = null,
    /** Counts the messages, so the same text shown twice is shown twice. */
    val messageId: Long = 0,
    /** Wi-Fi calling details from the IWLAN dump, read when the owner asks; never the device log. */
    val details: List<String>? = null,
) {
    /** Test now may start: system access, a SIM, nothing running, not paused. */
    val canTest: Boolean get() = gate?.canWrite == true && sims?.isNotEmpty() == true && stepsToGo == 0 && state.stop == null
}

class OwnerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FileStateStore(app)
    private val _ui = MutableStateFlow(OwnerUi())
    val ui: StateFlow<OwnerUi> = _ui

    init {
        // The bundled data is large; read its tag apart, so the first screen does not wait for it.
        viewModelScope.launch {
            val tag = withContext(Dispatchers.IO) { OwnerData.bundledTag(getApplication()) }
            _ui.value = _ui.value.copy(bundledTag = tag)
        }
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
        val views = withContext(Dispatchers.IO) { OwnerData.simViews(context) }
        val calls = withContext(Dispatchers.IO) { OwnerData.callsTested(context) }
        val wm = WorkManager.getInstance(context)
        val state = store.read()
        val run = withContext(Dispatchers.IO) { runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.RUN).get() }.getOrNull() }.orEmpty()
        _ui.value = _ui.value.copy(
            loaded = true,
            gate = gate,
            state = state,
            sims = sims,
            simViews = views,
            calls = calls,
            apnCandidates = withContext(Dispatchers.IO) { candidates(state, sims) },
            stepsToGo = OwnerScheduler.pending(run),
        )
    }

    private var candidateCache: Pair<String, Map<Int, Int>>? = null

    /** How many access point names OCD's pulled release has for each SIM; read once per release and SIM set. */
    private fun candidates(state: OwnerState, sims: List<OwnerSim>?): Map<Int, Int> {
        val data = OwnerData(getApplication(), store)
        val tag = state.release?.tag?.takeIf { data.currentDir() != null } ?: return emptyMap()
        val key = tag + sims.orEmpty().joinToString { "${it.subId}/${it.identity.mccmnc}" }
        candidateCache?.takeIf { it.first == key }?.let { return it.second }
        val counts = sims.orEmpty().mapNotNull { sim -> runCatching { sim.subId to QuietPlan.apnCandidates(data.apnRows(sim), sim).size }.getOrNull() }.toMap()
        candidateCache = key to counts
        return counts
    }

    private fun say(text: String) {
        _ui.value = _ui.value.copy(message = text, messageId = _ui.value.messageId + 1)
    }

    /** Checks for a new data release, then says how it went: up to date, updated, offline, or failed. */
    fun pull() = viewModelScope.launch {
        val context = getApplication<Application>()
        val startedMs = System.currentTimeMillis()
        val before = store.read().release?.tag
        OwnerScheduler.pull(context, force = false)
        if (!online(context)) {
            say("No connection. It updates when you're online")
            return@launch
        }
        say("Checking for an update")
        // The pull runs as work; its log line says how it ended.
        repeat(90) {
            delay(1_000)
            val state = store.read()
            val ended: LogEvent? = state.log.lastOrNull { it.atMs >= startedMs && (it.kind == "pull" || (it.kind == "error" && it.text.startsWith("pull:"))) }
            if (ended != null) {
                val after = state.release?.tag
                say(
                    when {
                        ended.kind == "error" -> "Couldn't check for an update. See the log in Details"
                        after != null && after != before -> "Updated to ${after.removePrefix("data-")}"
                        else -> "Up to date"
                    },
                )
                refresh()
                return@launch
            }
        }
    }

    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

    /** Starts a test. The headline turns to "Testing" (TalkBack hears it), so only a failure to start is said. */
    fun test(offs: Boolean, voiceCheck: Boolean, apns: Boolean) = viewModelScope.launch {
        val line = runCatching { OwnerScheduler.runTests(getApplication(), offs = offs, voiceCheckMs = if (voiceCheck) 120_000 else 0, apns = apns) }.getOrNull()
        if (line?.startsWith("queued") != true) say("Couldn't start the test")
        refresh()
    }

    fun stopTest() = viewModelScope.launch {
        OwnerScheduler.stopTests(getApplication())
        say("Test stopped")
        refresh()
    }

    fun checkNow() {
        OwnerScheduler.checkNow(getApplication())
        say("Checking")
    }

    /** Wi-Fi calling's state from the IWLAN dump (no device log, so Android asks for nothing). */
    fun details() = viewModelScope.launch {
        val context = getApplication<Application>()
        val lines = withContext(Dispatchers.IO) {
            runCatching {
                val handle = OwnerAccess.open(context)
                try {
                    val phone = handle.phone
                    val registry = phone.registryText()
                    val iwlan = phone.iwlanServiceText() ?: return@runCatching null
                    val qns = phone.qnsText()
                    OwnerData.sims(context).flatMap { sim ->
                        val r = IwlanDumps.reading(registry, iwlan, qns, sim.slot)
                        val tunnel = when (r?.tunnelUp) {
                            true -> "up"
                            false -> "down"
                            null -> "not seen"
                        }
                        listOf(
                            "Slot ${sim.slot + 1}: Wi-Fi calling tunnel $tunnel",
                            "Slot ${sim.slot + 1}: last refusal ${r?.lastRefusal ?: "none"}",
                        )
                    }
                } finally {
                    handle.close()
                }
            }.getOrNull() ?: listOf("Wi-Fi calling details can't be read")
        }
        _ui.value = _ui.value.copy(details = lines)
    }

    fun revertAll() {
        OwnerScheduler.revertAll(getApplication())
        say("Undoing changes")
    }

    fun rebootProbe() {
        OwnerScheduler.rebootProbe(getApplication())
        say("Restart check started")
    }

    fun cleanup() {
        OwnerScheduler.cleanup(getApplication())
        say("Removing leftovers")
    }

    fun setSchedule(on: Boolean) = viewModelScope.launch {
        store.update { it.copy(scheduleOn = on).logged(System.currentTimeMillis(), "schedule", if (on) "background checks and night tests on" else "background checks and night tests off") }
        OwnerScheduler.ensureQuiet(getApplication(), on)
        refresh()
    }

    fun clearStop() = viewModelScope.launch {
        store.update { it.copy(stop = null).logged(System.currentTimeMillis(), "stop", "the owner cleared the stop flag") }
        say("Changes resumed")
        refresh()
    }

    fun issueUrl(): String? = store.read().runs.lastOrNull { it.features.isNotEmpty() }?.let(IssueReport::url)
}

/**
 * The owner screen, opened from Settings > Network & internet > Open Carrier
 * Data (the build has no launcher icon): the headline, the evidence ladder of
 * each feature, mobile data, the automatic tests, and in Details the test
 * options, the tools, the carrier data, the safety notes and the log
 * ([OwnerScreen]). Everything here also runs without it, in the background.
 */
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
        val actions = OwnerActions(
            back = ::finish,
            testNow = { viewModel.test(it.offs, it.voice, it.apns) },
            stopTest = { viewModel.stopTest() },
            revertAll = viewModel::revertAll,
            pull = { viewModel.pull() },
            setSchedule = { viewModel.setSchedule(it) },
            checkNow = viewModel::checkNow,
            cleanup = viewModel::cleanup,
            readDetails = { viewModel.details() },
            rebootProbe = viewModel::rebootProbe,
            clearStop = { viewModel.clearStop() },
            grantShizuku = { runCatching { Shizuku.requestPermission(4713) } },
            sendReport = ::openIssue,
            openCheck = { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CALL_TEST, true)) },
        )
        setContent {
            OcdCheckTheme {
                val ui by viewModel.ui.collectAsState()
                OwnerScreen(ui, actions)
            }
        }
    }

    private fun openIssue() {
        val url = viewModel.issueUrl()
        if (url == null) {
            // The button waits for a finished test, so a missing report is the privacy check's.
            android.widget.Toast.makeText(this, "Report held back: it may contain private data", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Opens the prefilled form; the owner reads it and submits it himself.
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
