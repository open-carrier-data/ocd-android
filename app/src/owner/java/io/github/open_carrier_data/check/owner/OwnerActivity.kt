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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.IwlanDumps
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.QuietSchedule
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.phone.CallTestStore
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
import java.text.DateFormat
import java.util.Date

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
    /** OCD's internet entries for each SIM (by subscription ID) in the pulled release; missing: not known. */
    val apnCandidates: Map<Int, Int> = emptyMap(),
    /** The schedule in the technical words of the Details. */
    val weekly: String = "not read",
    /** Whether the hourly quiet job is registered with WorkManager. */
    val scheduleRegistered: Boolean = false,
    /** Steps of the owner's or the night's test still to run. */
    val stepsToGo: Int = 0,
    val message: String? = null,
    /** Counts the messages, so the same text shown twice is shown twice. */
    val messageId: Long = 0,
    /** Wi-Fi calling details from the IWLAN and QNS dumps, read when the owner asks; never the device log. */
    val details: List<String>? = null,
) {
    val canTest: Boolean get() = gate?.canWrite == true && sims?.isNotEmpty() == true && stepsToGo == 0
}

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
        val views = withContext(Dispatchers.IO) { OwnerData.simViews(context) }
        val calls = withContext(Dispatchers.IO) {
            runCatching { CallTestStore(context).load().t2 }.getOrDefault(emptyList())
                .filter { it.model == Build.MODEL }
                .mapNotNull { pass -> OwnerFeature.ofWire(pass.feature.wire)?.let { CallsTested(it, pass.homeMccMnc) } }
        }
        val wm = WorkManager.getInstance(context)
        val state = store.read()
        val info = withContext(Dispatchers.IO) {
            runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.QUIET).get() }.getOrNull()
        }?.firstOrNull { !it.state.isFinished }
        val weekly = QuietSchedule.line(state.scheduleOn, info != null, state, System.currentTimeMillis()) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
        val run = withContext(Dispatchers.IO) { runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.RUN).get() }.getOrNull() }.orEmpty()
        _ui.value = _ui.value.copy(
            loaded = true,
            gate = gate,
            state = state,
            sims = sims,
            simViews = views,
            calls = calls,
            apnCandidates = withContext(Dispatchers.IO) { candidates(state, sims) },
            weekly = weekly,
            scheduleRegistered = info != null,
            stepsToGo = OwnerScheduler.pending(run),
        )
    }

    private var candidateCache: Pair<String, Map<Int, Int>>? = null

    /** How many internet entries OCD's pulled release has for each SIM; read once per release and SIM set. */
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

    fun pull() {
        OwnerScheduler.pull(getApplication(), force = false)
        say("Checking for an update. Every file is verified before it's used.")
    }

    fun test(offs: Boolean, voiceCheck: Boolean, apns: Boolean) = viewModelScope.launch {
        val line = OwnerScheduler.runTests(getApplication(), offs = offs, voiceCheckMs = if (voiceCheck) 120_000 else 0, apns = apns)
        say(if (line.startsWith("queued")) "Test started. Your own settings come back after each step." else "No SIM card, so there's nothing to test.")
        refresh()
    }

    fun checkNow() {
        OwnerScheduler.checkNow(getApplication())
        say("Checking now, without changing anything.")
    }

    /** Wi-Fi calling's state from the dumps (no device log, so Android asks for nothing). */
    fun details() = viewModelScope.launch {
        val context = getApplication<Application>()
        val lines = withContext(Dispatchers.IO) {
            runCatching {
                val handle = OwnerAccess.open(context)
                try {
                    val phone = handle.phone
                    val registry = phone.registryText()
                    val iwlan = phone.iwlanServiceText()
                    val qns = phone.qnsText()
                    OwnerData.sims(context).flatMap { sim ->
                        val r = IwlanDumps.reading(registry, iwlan, qns, sim.slot)
                        listOf(
                            "Slot ${sim.slot + 1}: IMS tunnel over Wi-Fi ${when (r?.tunnelUp) { true -> "up"; false -> "down"; null -> "not seen" }}",
                            "Slot ${sim.slot + 1}: QNS qualifies ${IwlanDumps.qualifiedForIms(qns, sim.slot) ?: "-"} for IMS; Wi-Fi restrictions ${IwlanDumps.restrictions(qns, sim.slot).ifEmpty { listOf("none") }.joinToString()}",
                            "Slot ${sim.slot + 1}: last IWLAN refusal ${r?.lastRefusal ?: "none"}${if (r?.selectionFailed == true) " (the ePDG selector found no server)" else ""}",
                        )
                    } + listOfNotNull(
                        "IWLAN dump unreadable (PACKAGE_USAGE_STATS missing?)".takeIf { iwlan == null },
                        "QNS dump unreadable".takeIf { qns == null },
                    )
                } finally {
                    handle.close()
                }
            }.getOrElse { listOf("not readable: ${it.message}") }
        }
        _ui.value = _ui.value.copy(details = lines)
    }

    fun revertAll() {
        OwnerScheduler.revertAll(getApplication())
        say("Putting your phone's own settings back.")
    }

    fun rebootProbe() {
        OwnerScheduler.rebootProbe(getApplication())
        say("Reboot check queued: VoLTE is set to the value it has and left live. Reboot when the log says so.")
    }

    fun cleanup() {
        OwnerScheduler.cleanup(getApplication())
        say("Looking for leftovers.")
    }

    fun setSchedule(on: Boolean) = viewModelScope.launch {
        store.update { it.copy(scheduleOn = on).logged(System.currentTimeMillis(), "schedule", if (on) "background checks and night tests on" else "background checks and night tests off") }
        OwnerScheduler.ensureQuiet(getApplication(), on)
        refresh()
    }

    fun clearStop() = viewModelScope.launch {
        store.update { it.copy(stop = null).logged(System.currentTimeMillis(), "stop", "the owner cleared the stop flag") }
        say("Changes are allowed again.")
        refresh()
    }

    fun issueUrl(): String? = store.read().runs.lastOrNull { it.features.isNotEmpty() }?.let(IssueReport::url)
}

/**
 * The owner screen, opened from Settings > Network & internet > Open Carrier
 * Data (the build has no launcher icon): the status in one sentence, the
 * evidence ladder of each feature, mobile data, the automatic checks, the
 * carrier data, and in Details the access, test options, Wi-Fi calling
 * details, the report, the tools and the log ([OwnerScreen]). Everything
 * here also runs without it, in the background.
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
            revertAll = viewModel::revertAll,
            pull = viewModel::pull,
            setSchedule = { viewModel.setSchedule(it) },
            checkNow = viewModel::checkNow,
            cleanup = viewModel::cleanup,
            readDetails = { viewModel.details() },
            rebootProbe = viewModel::rebootProbe,
            clearStop = { viewModel.clearStop() },
            grantShizuku = { runCatching { Shizuku.requestPermission(4713) } },
            sendReport = ::openIssue,
            openCheck = { startActivity(Intent(this, MainActivity::class.java)) },
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
            android.widget.Toast.makeText(this, "There's no finished test to report yet, or the privacy check held the report back.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Opens the prefilled form; the owner reads it and submits it himself.
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
