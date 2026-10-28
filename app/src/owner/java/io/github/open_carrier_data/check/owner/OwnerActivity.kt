package io.github.open_carrier_data.check.owner

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.CallsOutMode
import io.github.open_carrier_data.check.owner.core.CallsTested
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.Retested
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.sameCard
import io.github.open_carrier_data.check.owner.core.IssueReport
import io.github.open_carrier_data.check.owner.core.IwlanDumps
import io.github.open_carrier_data.check.owner.core.LogEvent
import io.github.open_carrier_data.check.owner.core.ObservedCell
import io.github.open_carrier_data.check.owner.core.ObservedReports
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.WifiCallingDetails
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

data class OwnerUi(
    /** The first read is done: until then the screen shows no status, so nothing moves on load. */
    val loaded: Boolean = false,
    val gate: OwnerGate? = null,
    val state: OwnerState = OwnerState(),
    /** The SIMs the runner tests (with what OCD matching reads); null until read. */
    val sims: List<OwnerSim>? = null,
    /** The same SIMs as Settings names them; null when the list is not readable. */
    val simViews: List<SimView>? = null,
    /** How far this phone's own measured calls went per feature and SIM (round 26: calls out, works). */
    val calls: List<CallsTested> = emptyList(),
    /** OCD's access point names for each SIM (by subscription ID) at the pinned commit; missing: not known. */
    val apnCandidates: Map<Int, Int> = emptyMap(),
    /** One plain line per entry others reported working or contested on this phone's cell ([ObservedReports.lines]). */
    val reportLines: List<String> = emptyList(),
    /** The data the app ships ("2026.10"), shown when no commit is pinned yet. */
    val bundledTag: String? = null,
    /** Steps of the owner's or the night's test still to run. */
    val stepsToGo: Int = 0,
    val message: String? = null,
    /** Counts the messages, so the same text shown twice is shown twice. */
    val messageId: Long = 0,
    /** Wi-Fi calling details from the IWLAN dump, read when the owner asks; never the device log. */
    val details: List<String>? = null,
    /** Round 26: "re-tested by OCD" from this phone's measured calls, and the last measured calls, in plain words. */
    val retestedLines: List<String> = emptyList(),
    val callLines: List<String> = emptyList(),
    /** The MMS line per SIM ("not checked, the network no longer offers MMS", "access point reachable"). */
    val mmsLines: List<String> = emptyList(),
    val selfSmsLine: String? = null,
    /** Round 30: CarrierSettings's dataset installer as it last answered (serving, changes halted); null before the first look. */
    val dataset: io.github.open_carrier_data.check.owner.core.DatasetStatus? = null,
    /** Round 29: the owner entered a token for automatic reports ([OwnerToken]); its value is never in the UI state. */
    val tokenSet: Boolean = false,
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
    }

    suspend fun refresh() {
        val context = getApplication<Application>()
        val gate = withContext(Dispatchers.Default) { OwnerAccess.gate(context) }
        val sims = runCatching { OwnerData.sims(context) }.getOrNull()
        val views = withContext(Dispatchers.IO) { OwnerData.simViews(context) }
        val wm = WorkManager.getInstance(context)
        val state = store.read()
        val calls = withContext(Dispatchers.IO) { OwnerData.callsTested(context, state) }
        val run = withContext(Dispatchers.IO) { runCatching { wm.getWorkInfosForUniqueWork(OwnerScheduler.RUN).get() }.getOrNull() }.orEmpty()
        val testNow = withContext(Dispatchers.IO) { OwnerScheduler.testNowSteps(context) }
        val keys = withContext(Dispatchers.IO) { candidates(state, sims) }
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val ended = withContext(Dispatchers.IO) { OwnerMms.ended(context) }
        val dataset = withContext(Dispatchers.IO) { runCatching { datasetStatus() }.getOrNull() }
        _ui.value = _ui.value.copy(
            dataset = dataset,
            loaded = true,
            gate = gate,
            state = state,
            sims = sims,
            simViews = views,
            calls = calls,
            retestedLines = Retested.current(state.retested, now).map { it.line(zone) },
            callLines = state.calls.takeLast(3).reversed().map { CallLevels.line(it, zone) },
            mmsLines = sims.orEmpty().map { sim ->
                val line = MmsProbe.line(state.mms.lastOrNull { it.sim.sameCard(sim.key) }, MmsProbe.ended(ended, sim.identity.mccmnc), zone)
                if (sims.orEmpty().size > 1) "${sim.name}: $line" else line
            },
            selfSmsLine = SelfSms.line(state.selfSms.lastOrNull(), zone),
            apnCandidates = keys.mapValues { it.value.size },
            reportLines = withContext(Dispatchers.IO) { runCatching { reportLines(state, sims, keys) }.getOrDefault(emptyList()) },
            stepsToGo = OwnerScheduler.pending(run) + testNow,
            tokenSet = withContext(Dispatchers.IO) { OwnerToken.isSet(context) },
        )
    }

    private var datasetCache: Pair<Long, io.github.open_carrier_data.check.owner.core.DatasetStatus>? = null

    /** The installer's status from CarrierSettings, read at most every 15 s (one binder call). */
    private fun datasetStatus(): io.github.open_carrier_data.check.owner.core.DatasetStatus {
        val now = System.currentTimeMillis()
        datasetCache?.takeIf { now - it.first < 15_000 && now >= it.first }?.let { return it.second }
        val status = OwnerDatasetStep.status(getApplication(), store)
        datasetCache = now to status
        return status
    }

    /** The emergency data test by hand (its first use, with adb logcat streamed). */
    fun emergencyData(slot: Int) {
        OwnerScheduler.emergencyData(getApplication(), slot)
        say("Emergency data test queued")
    }

    /** Round 31: the default emergency APN cycle's first use by hand (adb logcat streamed): install and restore, no EIMS request. */
    fun emergencyDefaultFirstUse(slot: Int) {
        OwnerScheduler.emergencyDefaultFirstUse(getApplication(), slot)
        say("Emergency default APN first use queued")
    }

    /** The owner records the emergency data test's first use: passed, or not meaningful on this phone. */
    fun emergencyFirstUse(result: String) = viewModelScope.launch {
        if (result !in setOf(io.github.open_carrier_data.check.owner.core.EmergencyFirstUse.PASSED, io.github.open_carrier_data.check.owner.core.EmergencyFirstUse.NOT_MEANINGFUL)) return@launch
        store.update { it.copy(emergencyFirstUse = io.github.open_carrier_data.check.owner.core.EmergencyFirstUse(result, System.currentTimeMillis())).logged(System.currentTimeMillis(), "emergency_data", "the owner recorded the first use: $result") }
        refresh()
    }

    private var candidateCache: Pair<String, Map<Int, Set<String>>>? = null

    /** The keys of OCD's access point names at the pinned commit for each SIM; read once per pin and SIM set. */
    private fun candidates(state: OwnerState, sims: List<OwnerSim>?): Map<Int, Set<String>> {
        val data = OwnerData(getApplication(), store)
        val pin = state.pin?.commit?.takeIf { data.currentDir() != null } ?: return emptyMap()
        val key = pin + sims.orEmpty().joinToString { "${it.subId}/${it.identity.mccmnc}" }
        candidateCache?.takeIf { it.first == key }?.let { return it.second }
        val keys = sims.orEmpty().mapNotNull { sim -> runCatching { sim.subId to QuietPlan.apnCandidates(data.apnRows(sim), sim).map { it.key }.toSet() }.getOrNull() }.toMap()
        candidateCache = key to keys
        return keys
    }

    private var cellCache: Pair<String, List<Pair<OwnerSim, ObservedCell>>>? = null

    /** Details' lines on others' reports for each SIM's own cell; the cells are read once per pin and SIM set. */
    private fun reportLines(state: OwnerState, sims: List<OwnerSim>?, apnKeys: Map<Int, Set<String>>): List<String> {
        if (state.observed?.pointers.isNullOrEmpty() || sims.isNullOrEmpty()) return emptyList()
        val context = getApplication<Application>()
        val key = (state.pin?.commit ?: "bundled") + sims.joinToString { "${it.subId}/${it.identity.mccmnc}" }
        val cells = cellCache?.takeIf { it.first == key }?.second
            ?: OwnerData.observedCells(context, OwnerWorker.device(context), sims, OwnerData(context, store).bundle()).also { cellCache = key to it }
        val simCells = cells.map { (sim, cell) ->
            ObservedReports.SimCell(sim.key, sim.name, cell, QuietPlan.FEATURE_ENTRIES.toSet() + apnKeys[sim.subId].orEmpty().map(QuietPlan::apnEntry))
        }
        return ObservedReports.lines(simCells, state, System.currentTimeMillis())
    }

    private fun say(text: String) {
        _ui.value = _ui.value.copy(message = text, messageId = _ui.value.messageId + 1)
    }

    /** Checks main for a newer validated commit, then says how it went: up to date, updated, offline, or failed. */
    fun pull() = viewModelScope.launch {
        val context = getApplication<Application>()
        val startedMs = System.currentTimeMillis()
        val before = store.read().pin?.commit
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
                val after = state.pin
                say(
                    when {
                        ended.kind == "error" -> "Couldn't check for an update. See the log in Details"
                        after != null && after.commit != before -> "Updated to the data of ${after.commitDate.take(10)}"
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
        when {
            line?.startsWith("queued already") == true -> say("A test is already running")
            line?.startsWith("queued") != true -> say("Couldn't start the test")
        }
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
                    val sims = OwnerData.sims(context)
                    sims.flatMap { sim ->
                        WifiCallingDetails.lines(IwlanDumps.reading(registry, iwlan, qns, sim.slot), sim.name.takeIf { sims.size > 1 })
                    }
                } finally {
                    handle.close()
                }
            }.getOrNull() ?: listOf("Wi-Fi calling details can't be read")
        }
        _ui.value = _ui.value.copy(details = lines)
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

    /** Round 26: the app's own mailbox call, live or as a dry run that only logs what it would dial. */
    fun setCallsOutLive(on: Boolean) = viewModelScope.launch {
        val mode = if (on) CallsOutMode.LIVE else CallsOutMode.DRY_RUN
        store.update { it.copy(callsOutMode = mode).logged(System.currentTimeMillis(), "calls_out", if (on) "mailbox calls on: at night, muted, only when your own calls showed nothing" else "mailbox calls off: the app only notes what it would dial") }
        refresh()
    }

    fun dryRunCallsOut() {
        OwnerScheduler.dryRunCallsOut(getApplication())
        say("Checking the mailbox call without dialling. See the log")
    }

    /** One test SMS to the phone's own number now, at the owner's request; it may cost an SMS. */
    fun selfSmsNow() {
        OwnerScheduler.selfSmsNow(getApplication())
        say("Sending a test SMS to your own number. See the log")
    }

    /** The optional data SMS to the phone's own number; off by default, as it may cost an SMS. */
    fun setSelfSms(on: Boolean) = viewModelScope.launch {
        store.update { it.copy(selfSmsOn = on).logged(System.currentTimeMillis(), "self_sms", if (on) "the weekly test SMS to your own number is on" else "the weekly test SMS is off") }
        refresh()
    }

    /**
     * Round 31 (SM9), ROM builds: the person's opt-in for the active tests, off by default. On, the
     * mailbox calls go live and the test SMS is on (both only where the allowlist marks them free);
     * off, both go back off and only the passive checks run. The owner build has its own switches.
     */
    fun setActiveTests(on: Boolean) = viewModelScope.launch {
        if (OwnerTester.build == io.github.open_carrier_data.check.owner.core.TesterBuild.OWNER) return@launch
        val now = System.currentTimeMillis()
        store.update {
            it.copy(
                activeTests = io.github.open_carrier_data.check.owner.core.ActiveTestsOptIn(on, now),
                callsOutMode = if (on) CallsOutMode.LIVE else CallsOutMode.DRY_RUN,
                selfSmsOn = on,
            ).logged(now, "opt_in", if (on) "the person turned the test calls on (mailbox calls, test SMS, setting trials, the emergency data test where the ROM has it on)" else "the person turned the test calls off: only the passive checks run")
        }
        // The tester declaration (SM7) follows at once in the dataset step.
        OwnerScheduler.dataset(getApplication())
        refresh()
    }

    /** Round 29: stores the owner's fine-grained token for automatic reports, encrypted; never logged or shown. */
    fun saveToken(text: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        val context = getApplication<Application>()
        if (!OwnerTester.policy(store.read()).sendsWithToken) {
            done(false)
            return@launch
        }
        val problem = withContext(Dispatchers.IO) { OwnerToken.save(context, text) }
        if (problem != null) {
            say(problem)
            done(false)
            return@launch
        }
        store.update { it.copy(autoRefusedAtMs = null, autoLine = null).logged(System.currentTimeMillis(), "report", "the owner entered a token for automatic reports") }
        done(true)
        say("Token saved. Settled results are sent automatically")
        OwnerScheduler.autoReport(context)
        refresh()
    }

    /** Removes the token and its key; reports open in the browser again. */
    fun removeToken() = viewModelScope.launch {
        withContext(Dispatchers.IO) { OwnerToken.remove(getApplication()) }
        store.update { it.copy(autoRefusedAtMs = null, autoLine = null).logged(System.currentTimeMillis(), "report", "the owner removed the token; reports open in the browser for him to submit") }
        say("Token removed")
        refresh()
    }

    fun clearStop() = viewModelScope.launch {
        // The emergency data test's own stop stays until a judged round; Resume clears the app's flag and CarrierSettings's "changes halted".
        store.update { it.copy(stop = null).logged(System.currentTimeMillis(), "stop", "the owner tapped Resume") }
        withContext(Dispatchers.IO) { runCatching { OwnerDatasetStep.resume(getApplication(), store) } }
        datasetCache = null
        say("Changes resumed")
        refresh()
    }

    /**
     * The prefilled "OCD Check report" issue of the last finished test, or
     * null when the privacy scan holds it back. A run recorded before the
     * report cell existed is named with this phone as it is now, and with the
     * SIM's SPN and GID1 prefix only when that SIM is still in.
     */
    fun report(open: (IssueReport.Prefilled?) -> Unit) = viewModelScope.launch {
        val context = getApplication<Application>()
        val prefilled = withContext(Dispatchers.IO) {
            runCatching {
                val run = store.read().runs.lastOrNull { it.features.isNotEmpty() } ?: return@runCatching null
                val cell = run.cell ?: run.sim?.let { key ->
                    val data = OwnerData(context, store)
                    val bundle = data.bundle()
                    val sim = OwnerData.sims(context).firstOrNull { it.subId == key.subId && it.identity.mccmnc == key.mccmnc }
                    val profile = bundle.profiles.firstOrNull { it.profileId == run.profileId }
                    OwnerData.reportCell(context, OwnerWorker.device(context), sim, profile, data.currentDir() != null, bundle)
                } ?: return@runCatching null
                // Round 29: the settled results of the run's SIM on this build ride along, as in the automatic report.
                val settled = runCatching { OwnerAutoReports.settled(context, store.read(), run.copy(cell = cell)) }.getOrDefault(emptyMap())
                IssueReport.prefill(run, cell, OwnerData.callsTested(context), settled)
            }.getOrNull()
        }
        if (prefilled != null) {
            // The owner build's own trace of what it opened, for the owner's checks; the report holds no identifier.
            val url = prefilled.url
            val parts = url.chunked(LOG_CHUNK)
            Log.i(REPORT_TAG, "report ${prefilled.hash}, URL ${url.length} characters in ${parts.size} parts")
            parts.forEachIndexed { index, part -> Log.i(REPORT_TAG, "part ${index + 1}/${parts.size}: $part") }
            // The hash also tells the owner's own report in OCD's observed/summary.json from others' reports.
            store.update {
                it.copy(reportsOpened = (it.reportsOpened - prefilled.hash + prefilled.hash).takeLast(OwnerState.MAX_OPENED))
                    .logged(System.currentTimeMillis(), "report", "opened the prefilled OCD Check report ${prefilled.hash}; nothing is sent until the owner submits it")
            }
        }
        open(prefilled)
    }

    companion object {
        const val REPORT_TAG = "OcdCheckReport"
        private const val LOG_CHUNK = 3_000
    }
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
            pull = { viewModel.pull() },
            setSchedule = { viewModel.setSchedule(it) },
            checkNow = viewModel::checkNow,
            cleanup = viewModel::cleanup,
            readDetails = { viewModel.details() },
            rebootProbe = viewModel::rebootProbe,
            clearStop = { viewModel.clearStop() },
            grantShizuku = { runCatching { Shizuku.requestPermission(4713) } },
            sendReport = ::openIssue,
            setCallsOutLive = { viewModel.setCallsOutLive(it) },
            dryRunCallsOut = viewModel::dryRunCallsOut,
            setSelfSms = { viewModel.setSelfSms(it) },
            selfSmsNow = { viewModel.selfSmsNow() },
            setActiveTests = { viewModel.setActiveTests(it) },
            saveToken = { text, done -> viewModel.saveToken(text, done) },
            removeToken = { viewModel.removeToken() },
        )
        // The adb extras, once per start: never again when the activity is recreated (one may cost an SMS).
        if (savedInstanceState == null) extras(intent)
        // The screen reads the state while it is shown only; with the screen off or the page closed, nothing polls.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    viewModel.refresh()
                    delay(3_000)
                }
            }
        }
        setContent {
            OcdCheckTheme {
                val ui by viewModel.ui.collectAsState()
                OwnerScreen(ui, actions)
            }
        }
    }

    /** `am start` on an open page (singleTop) brings its intent here; the adb extras work then too. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extras(intent)
    }

    /** The adb extras (the activity is guarded by MODIFY_PHONE_STATE, which the shell holds). */
    private fun extras(intent: Intent?) {
        intent ?: return
        // `--ez calls_out_dry_run true`: a dry run only, never a call.
        if (intent.getBooleanExtra(EXTRA_CALLS_OUT_DRY_RUN, false)) viewModel.dryRunCallsOut()
        // Round 30's emergency data test, first use over adb with `adb logcat` streamed: `--ei emergency_data_test 0` runs one
        // session on the SIM in slot 1 (no call, nothing sent); `--es emergency_data_first_use passed` (or not_meaningful) records
        // the owner's reading of the radio log.
        intent.getIntExtra(EXTRA_EMERGENCY_DATA_TEST, -1).takeIf { it in 0..3 }?.let { viewModel.emergencyData(it) }
        // Round 31: `--ei emergency_default_first_use 0` runs the default emergency APN cycle's first use on the SIM in slot 1:
        // the temporary set without its emergency row, the check of Android's default sos profile, the restore; no EIMS request.
        intent.getIntExtra(EXTRA_EMERGENCY_DEFAULT_FIRST_USE, -1).takeIf { it in 0..3 }?.let { viewModel.emergencyDefaultFirstUse(it) }
        intent.getStringExtra(EXTRA_EMERGENCY_DATA_FIRST_USE)?.let { viewModel.emergencyFirstUse(it) }
        // `--ez self_sms_now true` is "Send a test SMS now"; it may cost an SMS.
        if (intent.getBooleanExtra(EXTRA_SELF_SMS_NOW, false)) viewModel.selfSmsNow()
        // `--ez test_now true` is "Test now" with the screen's default options: pull, hand over, install and test, now.
        if (intent.getBooleanExtra(EXTRA_TEST_NOW, false)) {
            val options = TestOptions()
            Log.i(TEST_NOW_TAG, "test now requested by intent")
            viewModel.test(options.offs, options.voice, options.apns)
        }
    }

    companion object {
        const val EXTRA_TEST_NOW = "test_now"
        const val TEST_NOW_TAG = "OcdCheckTestNow"
        const val EXTRA_CALLS_OUT_DRY_RUN = "calls_out_dry_run"
        const val EXTRA_EMERGENCY_DATA_TEST = "emergency_data_test"
        const val EXTRA_EMERGENCY_DATA_FIRST_USE = "emergency_data_first_use"
        const val EXTRA_EMERGENCY_DEFAULT_FIRST_USE = "emergency_default_first_use"
        const val EXTRA_SELF_SMS_NOW = "self_sms_now"
    }

    private fun openIssue() = viewModel.report { prefilled ->
        if (prefilled == null) {
            // The button waits for a finished test, so a missing report is the privacy check's or the data digest's.
            android.widget.Toast.makeText(this, "Report held back: it may contain private data, or the test has no data digest", android.widget.Toast.LENGTH_LONG).show()
            return@report
        }
        // Opens the prefilled form; the owner reads it and submits it himself. The app sends nothing.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefilled.url)))
            // The bot's comment gives the same hash for the same text.
            android.widget.Toast.makeText(this, "Report ${prefilled.hash.take(19)}…", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No browser to open the report", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
