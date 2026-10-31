package io.github.open_carrier_data.check.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.AudioAnswer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CallTestRecord
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.ConfigOrigin
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.Mailbox
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.PhoneSwitches
import io.github.open_carrier_data.check.core.HiddenLines
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbeNet
import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.Resolution
import io.github.open_carrier_data.check.core.SettingsScreen
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.StatementDisplay
import io.github.open_carrier_data.check.core.StatementView
import io.github.open_carrier_data.check.core.SwitchStep
import io.github.open_carrier_data.check.core.SwitchSteps
import io.github.open_carrier_data.check.core.T2
import io.github.open_carrier_data.check.core.T2Assessment
import io.github.open_carrier_data.check.core.T2Feature
import io.github.open_carrier_data.check.core.T2Record
import io.github.open_carrier_data.check.core.ToggleAnswer
import io.github.open_carrier_data.check.core.UserToggleRecord
import io.github.open_carrier_data.check.phone.CallTestPhone
import io.github.open_carrier_data.check.phone.CallTestStore
import io.github.open_carrier_data.check.phone.PhoneReader
import io.github.open_carrier_data.check.phone.PublicDataPhone
import io.github.open_carrier_data.check.phone.PulledData
import io.github.open_carrier_data.check.phone.ShizukuAccess
import io.github.open_carrier_data.check.phone.ShizukuStatus
import io.github.open_carrier_data.check.phone.SimChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class UiState(
    val loading: Boolean = true,
    val bundle: OcdBundle? = null,
    val bundleError: String? = null,
    val permissionGranted: Boolean = false,
    val sims: List<SimChoice> = emptyList(),
    val selectedSubId: Int? = null,
    val readings: PhoneReadings? = null,
    val resolution: Resolution? = null,
    val findings: List<Finding> = emptyList(),
    val includeGid1: Boolean = false,
    val showReport: Boolean = false,
    val report: ReportResult? = null,
    val shizuku: ShizukuStatus? = null,
    val deepRunning: Boolean = false,
    val deepProblem: String? = null,
    val call: CallTestUi = CallTestUi(),
    val switches: SwitchUi = SwitchUi(),
    /** Round 32 U1: the data pulled from OCD's validated main, in use instead of the bundled snapshot; null: the bundled one. */
    val pulled: PulledData? = null,
    val pulling: Boolean = false,
    /** The last pull's plain line. */
    val pullLine: String? = null,
    /** Round 32 part A: what the SIM's operator states on its own pages, from the pulled data; null without it. */
    val statements: StatementDisplay? = null,
    /** Round 42 item 2 (read-only tap): the probe ladder is running now. */
    val probing: Boolean = false,
    /** Round 42 item 2 (read-only tap): the last probe's plain line, or a note why it could not run. */
    val probeLine: String? = null,
) {
    /** The read-only builds pull with a tap (U1); the owner and ROM builds pull through their own tester. */
    val canPull: Boolean get() = BuildConfig.TESTER.isEmpty()

    /** What the deep check would run for: the selected SIM's slot and home network. */
    val deepTarget: DeepCheckTarget?
        get() {
            val sim = readings?.sim ?: return null
            val slot = SimSlot.of(sim.slotIndex) ?: return null
            return DeepCheckTarget(slot, HomeNetwork.of(sim.homeMccMnc))
        }
}

/** Where the guided call test stands. */
enum class CallPhase { READY, STARTING, WATCHING, STOPPING, ASK, DISCARDED }

/** One T2 test under way: its number, the feature, where the settings came from, and its calls so far. */
data class T2Session(val number: Int, val feature: T2Feature, val configOrigin: ConfigOrigin, val calls: List<CallTestRecord> = emptyList()) {
    val assessment: T2Assessment get() = T2.assess(feature, calls)
}

data class CallTestUi(
    /** This SIM's mailbox from the bundled table, or null: then no test is offered. */
    val mailbox: Mailbox? = null,
    /** The feature the T2 test is for (round 23 LB3: 13 LTE for VoLTE, 18 IWLAN for Wi-Fi calling). */
    val feature: T2Feature = T2Feature.VOLTE,
    /** Features the person turned on in the guided switch step for this SIM; their tests record `user_toggle`. */
    val toggled: Set<T2Feature> = emptySet(),
    val session: T2Session? = null,
    /** The last T2 test that passed or failed, shown until a new one starts. */
    val concluded: T2Assessment? = null,
    /** The call the person was asked to make: outgoing to the mailbox or incoming from their second line. */
    val intended: CallDirection = CallDirection.OUTGOING,
    val phase: CallPhase = CallPhase.READY,
    /** The watch so far, read once a second while the app is in front. */
    val live: CallWatchSummary? = null,
    /** What the stopped watch saw, until the person answers. */
    val result: CallWatchSummary? = null,
    /** The incoming call's audio answer, until the second-line question is answered. */
    val pendingAudio: AudioAnswer? = null,
    val airplaneAtStart: Boolean? = null,
    val wifiAtStart: Boolean? = null,
    val problem: String? = null,
    val log: CallTestLog = CallTestLog(),
)

/** Where the guided switch step stands (round 23 AP3). */
enum class TogglePhase { IN_SETTINGS, REREADING, ASK }

data class ToggleUi(
    val step: SwitchStep,
    val screen: SettingsScreen,
    /** The deep check had results before Settings, so the re-read runs it again. */
    val deepBefore: Boolean,
    val phase: TogglePhase = TogglePhase.IN_SETTINGS,
    val after: PhoneSwitches? = null,
)

data class SwitchUi(
    /** VoLTE and Wi-Fi calling where OCD's value for this phone is on and the phone has them off. */
    val offered: List<SwitchStep> = emptyList(),
    val toggle: ToggleUi? = null,
    /** The last kept step, shown until the next one. */
    val last: UserToggleRecord? = null,
    val problem: String? = null,
)

class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = PhoneReader(application)
    private val shizuku = ShizukuAccess(application)
    private val callPhone = CallTestPhone(application)
    private val callStore = CallTestStore(application)
    private val publicData = PublicDataPhone(application)
    private var bundled: OcdBundle? = null
    private val callTargets by lazy { callPhone.targets() }
    private var callSession: ShizukuAccess.CallWatchSession? = null
    private var callPoll: Job? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Round 42 item 2: when the probe ladder last ran on a tap, for the short cooldown. In memory only. */
    private var lastProbeMs: Long? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshShizuku()
        refresh()
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { callStore.load() }
            _state.update { it.copy(call = it.call.copy(log = log)) }
        }
    }

    override fun onCleared() {
        // A watch still running ends with the screen; viewModelScope is gone by now.
        callSession?.let { session -> CoroutineScope(Dispatchers.Main + NonCancellable).launch { session.stop() } }
        callSession = null
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun refreshShizuku() = _state.update { it.copy(shizuku = shizuku.status()) }

    fun requestShizukuPermission() = shizuku.requestPermission()

    /** Runs the deep check for the readings on screen and adds its results to them. */
    fun runDeepCheck() {
        viewModelScope.launch { deepCheck() }
    }

    private suspend fun deepCheck() {
        val start = _state.value
        val readings = start.readings ?: return
        val bundle = start.bundle ?: return
        val target = start.deepTarget ?: return
        if (start.deepRunning) return
        _state.update { it.copy(deepRunning = true, deepProblem = null) }
        val deep = try {
            shizuku.run(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val status = if (deep == null) shizuku.status() else null
        // Round 43: whether mobile data is still down for the SIM on screen, read once, outside the state update.
        val dataDown = if (deep != null && deep.refusals.isNotEmpty()) reader.mobileDataDown(start.selectedSubId) else null
        _state.update { current ->
            when {
                // A new check replaced the readings meanwhile; this result belongs to the old ones.
                current.readings !== readings -> current.copy(deepRunning = false)
                deep == null -> current.copy(
                    deepRunning = false,
                    deepProblem = "The deep check gave no answer. Check that Shizuku is running and try again.",
                    shizuku = status,
                )
                else -> {
                    // Round 43: a hidden refusal shows only while its feature is still down for the SIM on screen.
                    val shown = HiddenLines.shown(deep.refusals, start.selectedSubId, dataDown, deep.ims.registered)
                    val updated = readings.copy(deep = deep.copy(refusals = shown))
                    current.copy(
                        deepRunning = false,
                        readings = updated,
                        findings = Findings.compare(updated, current.resolution, bundle),
                    ).withReport().withSwitchSteps()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _state.update { it.copy(loading = true, permissionGranted = reader.hasPhonePermission(), deepProblem = null) }
        val bundle = _state.value.bundle ?: loadBundle() ?: return
        val sims = reader.simChoices()
        val selected = _state.value.selectedSubId?.takeIf { id -> sims.any { it.subId == id } }
            ?: sims.firstOrNull()?.subId
        val readings = reader.read(selected)
        val resolution = withContext(Dispatchers.Default) {
            readings.sim?.identity()?.let { Matcher.resolve(bundle, it) }
        }
        val findings = Findings.compare(readings, resolution, bundle)
        val pulled = _state.value.pulled
        val statements = readings.sim?.homeMccMnc?.let { code ->
            pulled?.statements?.let { StatementView.forSim(it, pulled.identity, code, resolution?.stack?.map { p -> p.profileId }.orEmpty()) }
        }
        val mailbox = withContext(Dispatchers.IO) {
            val sim = readings.sim
            callTargets?.takeIf { sim != null }?.let { CallTargets.mailboxFor(it, sim!!.homeMccMnc, sim.spn, callPhone::isEmergency) }
        }
        _state.update {
            it.copy(
                call = it.call.copy(mailbox = mailbox),
                loading = false,
                bundle = bundle,
                permissionGranted = readings.permissionGranted,
                sims = sims,
                selectedSubId = selected,
                readings = readings,
                resolution = resolution,
                findings = findings,
                statements = statements,
            ).withReport().withSwitchSteps()
        }
    }

    /**
     * Round 32 U1: pulls OCD's newest validated main commit on the person's tap (read-only builds
     * only). On a metered network at most once a day; a failed pull keeps the data in use whole.
     */
    fun pullData() {
        if (!_state.value.canPull || _state.value.pulling) return
        viewModelScope.launch {
            val base = bundled ?: return@launch
            _state.update { it.copy(pulling = true, pullLine = null) }
            val line = withContext(Dispatchers.IO) { publicData.pull(base) }
            val pulled = withContext(Dispatchers.IO) { publicData.current() }
            _state.update { it.copy(pulling = false, pullLine = line, pulled = pulled, bundle = pulled?.bundle ?: base) }
            reload()
        }
    }

    /**
     * OCD rule round 42, item 2 (the read-only tap): run the cellular mobile-data probe ladder when
     * the person taps, and show the plain cause. The read-only builds hold no CHANGE_NETWORK_STATE,
     * so [ProbeNet] runs on the active network only while mobile data is the default (with Wi-Fi as
     * the default it cannot reach the cellular link). D1-D4 only, never D5 (the carrier self-care
     * page stays out of the read-only builds). A short cooldown between taps. No automatic run.
     */
    fun runProbes() {
        if (_state.value.probing) return
        val now = System.currentTimeMillis()
        if (!ProbePolicy.tapAllowed(lastProbeMs, now)) {
            _state.update { it.copy(probeLine = "Please wait a moment before checking the mobile-data path again.") }
            return
        }
        _state.update { it.copy(probing = true, probeLine = null) }
        viewModelScope.launch {
            val line = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext "This check needs Android 10 or newer."
                val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                    ?: return@withContext "The network state could not be read."
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                when {
                    network == null || caps == null -> "No active network to check."
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        "This checks mobile data over the cellular link. Turn Wi-Fi off, then tap again."
                    else -> {
                        lastProbeMs = now
                        val mccmnc = _state.value.readings?.sim?.homeMccMnc
                        // The network's own LinkProperties (its DNS servers, asked directly) and its Private DNS mode.
                        val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
                        // D1-D4, never D5: the read-only builds keep the carrier self-care page out.
                        val strict = ProbeNet.privateDnsStrict(getApplication(), network)
                        val results = ProbeNet.run(network, link, strict, mccmnc, includeD5 = false, privateDnsBrokenHint = null)
                        // Strict Private DNS that cannot reach its server is named as the phone's, never the carrier's.
                        val factor = if (ProbeLadder.privateDnsFactor(results)) " Phone-side: ${ProbeLadder.PRIVATE_DNS_FACTOR}." else ""
                        "The mobile-data path check: " + ProbeLadder.classify(results).plain + "." + factor + " This check used a few kilobytes of mobile data."
                    }
                }
            }
            _state.update { it.copy(probing = false, probeLine = line) }
        }
    }

    fun refreshIfPermissionChanged() {
        val current = _state.value
        if (!current.loading && current.permissionGranted != reader.hasPhonePermission()) refresh()
    }

    fun selectSim(subId: Int) {
        if (subId == _state.value.selectedSubId) return
        if (_state.value.call.phase != CallPhase.READY) return
        // Another SIM is another T2 cell: the test under way and the switch step start over.
        _state.update {
            it.copy(
                selectedSubId = subId,
                call = it.call.copy(session = null, concluded = null, toggled = emptySet()),
                switches = SwitchUi(),
            )
        }
        refresh()
    }

    fun setIncludeGid1(include: Boolean) = _state.update { it.copy(includeGid1 = include).withReport() }

    fun setShowReport(show: Boolean) = _state.update { it.copy(showReport = show) }

    // --- the guided switch step (round 23 AP3) ---

    /**
     * The person tapped Open Settings and [screen] opened. The app changed
     * nothing; it remembers what it read before, to read again on return.
     */
    fun wentToSettings(step: SwitchStep, screen: SettingsScreen?) {
        if (screen == null) {
            _state.update { it.copy(switches = it.switches.copy(problem = "No Settings screen could be opened on this phone.")) }
            return
        }
        val deepBefore = _state.value.readings?.deep != null
        _state.update { it.copy(switches = it.switches.copy(toggle = ToggleUi(step, screen, deepBefore), problem = null, last = null)) }
    }

    /** Back from Settings: read the phone again, passively, as when the app opens, then ask what the person did. */
    fun backFromSettings() {
        val toggle = _state.value.switches.toggle ?: return
        if (toggle.phase != TogglePhase.IN_SETTINGS) return
        _state.update { it.copy(switches = it.switches.copy(toggle = toggle.copy(phase = TogglePhase.REREADING))) }
        viewModelScope.launch {
            reload()
            if (toggle.deepBefore && shizuku.status().ready) deepCheck()
            val readings = _state.value.readings
            val after = readings?.let { PhoneSwitches.of(toggle.step.feature, it) }
            _state.update { it.copy(switches = it.switches.copy(toggle = toggle.copy(phase = TogglePhase.ASK, after = after))) }
        }
    }

    /** Keeps the step with the person's answer, on this phone only (`user_toggle` when they turned it on). */
    fun answerToggle(answer: ToggleAnswer) {
        val current = _state.value
        val toggle = current.switches.toggle ?: return
        if (toggle.phase != TogglePhase.ASK) return
        val readings = current.readings ?: return
        val step = toggle.step
        val device = readings.device
        val origin = if (answer == ToggleAnswer.TURNED_ON) ConfigOrigin.USER_TOGGLE else ConfigOrigin.AS_FOUND
        val record = UserToggleRecord(
            date = readings.checkedOn.toString(),
            model = device.model,
            codename = device.codename,
            romFamily = device.romFamily.wire,
            androidMajor = device.androidMajor,
            homeMccMnc = readings.sim?.homeMccMnc,
            spn = readings.sim?.spn,
            ocdProfile = step.ocd.profile?.profileId,
            feature = step.feature,
            ocdValue = "on",
            ocdBasis = step.ocd.basis,
            ocdSource = step.ocd.record?.record?.source,
            ocdStack = step.ocd.record?.record?.stack,
            sameStack = step.ocd.sameStack,
            screen = toggle.screen.wire,
            before = step.phone,
            after = toggle.after,
            answer = answer,
            configOrigin = origin,
        )
        _state.update {
            val call = it.call
            it.copy(
                switches = it.switches.copy(toggle = null, last = record),
                // After a switch the person turned on, the call test is offered for that feature as a new T2 test.
                call = if (answer == ToggleAnswer.TURNED_ON && call.phase == CallPhase.READY) {
                    call.copy(feature = step.feature, toggled = call.toggled + step.feature, session = null, concluded = null)
                } else {
                    call
                },
            )
        }
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { callStore.add(record) }
            _state.update { it.copy(call = it.call.copy(log = log)) }
        }
    }

    fun cancelToggle() = _state.update { it.copy(switches = it.switches.copy(toggle = null)) }

    // --- the guided call test (round 19 T2) ---

    /** The feature the next T2 test is for; a change starts a new test. */
    fun selectCallFeature(feature: T2Feature) {
        val call = _state.value.call
        if (call.phase != CallPhase.READY || feature == call.feature) return
        _state.update { it.copy(call = it.call.copy(feature = feature, session = null, concluded = null)) }
    }

    /** Ends the T2 test under way; its calls stay in the list. */
    fun newT2Test() {
        if (_state.value.call.phase != CallPhase.READY) return
        _state.update { it.copy(call = it.call.copy(session = null, concluded = null)) }
    }

    /** Starts watching the call state for [intended]. The person dials or answers; the app never does. */
    fun startCallWatch(intended: CallDirection) {
        val start = _state.value
        val slot = start.deepTarget?.slot ?: return
        if (start.call.mailbox == null || start.call.phase != CallPhase.READY) return
        if (intended != CallDirection.OUTGOING && intended != CallDirection.INCOMING) return
        val mailboxes = callTargets?.let(CallTargets::allMailboxes).orEmpty()
        _state.update {
            it.copy(call = it.call.copy(phase = CallPhase.STARTING, intended = intended, problem = null, live = null, result = null, pendingAudio = null))
        }
        viewModelScope.launch {
            val airplane = callPhone.airplaneMode()
            val wifi = callPhone.onWifi()
            val session = try {
                shizuku.startCallWatch(slot, mailboxes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (session == null) {
                _state.update {
                    it.copy(
                        call = it.call.copy(phase = CallPhase.READY, problem = "The call watch did not start. Check that Shizuku is running."),
                        shizuku = shizuku.status(),
                    )
                }
                return@launch
            }
            callSession = session
            _state.update { it.copy(call = it.call.copy(phase = CallPhase.WATCHING, airplaneAtStart = airplane, wifiAtStart = wifi)) }
            callPoll = launch {
                while (true) {
                    delay(1_000)
                    val live = session.read() ?: continue
                    _state.update { it.copy(call = it.call.copy(live = live)) }
                }
            }
        }
    }

    /** Stops the watch and shows what it saw. An emergency call during the watch discards everything. */
    fun stopCallWatch() {
        val session = callSession ?: return
        if (_state.value.call.phase != CallPhase.WATCHING) return
        callSession = null
        callPoll?.cancel()
        _state.update { it.copy(call = it.call.copy(phase = CallPhase.STOPPING)) }
        viewModelScope.launch {
            val summary = session.stop()
            _state.update {
                val call = it.call
                it.copy(
                    call = when {
                        summary == null -> call.copy(phase = CallPhase.READY, live = null, problem = "The call watch gave no answer, so nothing was kept.")
                        summary.emergencySeen -> call.copy(phase = CallPhase.DISCARDED, live = null, result = null)
                        else -> call.copy(phase = CallPhase.ASK, live = null, result = summary)
                    },
                )
            }
        }
    }

    /** The audio answer. For the incoming call the second-line question follows; for the outgoing call the test is kept. */
    fun answerAudio(answer: AudioAnswer) {
        val call = _state.value.call
        if (call.phase != CallPhase.ASK || call.result?.connected != true) return
        if (call.intended == CallDirection.INCOMING) {
            _state.update { it.copy(call = it.call.copy(pendingAudio = answer)) }
        } else {
            keepCall(answer, secondLine = null)
        }
    }

    /** The incoming call: whether it came from the person's second line. */
    fun answerSecondLine(fromSecondLine: Boolean) {
        val call = _state.value.call
        val audio = call.pendingAudio ?: return
        if (call.phase != CallPhase.ASK || call.intended != CallDirection.INCOMING) return
        keepCall(audio, fromSecondLine)
    }

    /** Keeps the call with the person's answers, on this phone only, and assesses the T2 test it belongs to. */
    private fun keepCall(audio: AudioAnswer, secondLine: Boolean?) {
        val current = _state.value
        val call = current.call
        val result = call.result ?: return
        val mailbox = call.mailbox ?: return
        val readings = current.readings ?: return
        val device = readings.device
        val session = call.session ?: T2Session(
            number = call.log.nextSession(),
            feature = call.feature,
            configOrigin = if (call.feature in call.toggled) ConfigOrigin.USER_TOGGLE else ConfigOrigin.AS_FOUND,
        )
        val record = CallTestRecord(
            date = readings.checkedOn.toString(),
            model = device.model,
            codename = device.codename,
            romFamily = device.romFamily.wire,
            androidMajor = device.androidMajor,
            imsStack = when (readings.deep?.ims?.source) {
                ImsSource.SAMSUNG -> "samsung_ims"
                ImsSource.AOSP -> "aosp_telephony"
                else -> "not_known"
            },
            homeMccMnc = readings.sim?.homeMccMnc,
            spn = readings.sim?.spn,
            ocdProfile = current.resolution?.mostSpecific?.profileId,
            brand = mailbox.brand,
            mailbox = mailbox.number,
            watch = result,
            airplaneModeAtStart = call.airplaneAtStart,
            wifiAtStart = call.wifiAtStart,
            heard = audio,
            intended = call.intended,
            feature = session.feature,
            session = session.number,
            secondLine = secondLine,
            configOrigin = session.configOrigin,
        )
        val updated = session.copy(calls = session.calls + record)
        val assessment = updated.assessment
        val pass = if (assessment.passed) {
            T2Record(
                date = record.date,
                model = record.model,
                codename = record.codename,
                romFamily = record.romFamily,
                androidMajor = record.androidMajor,
                imsStack = record.imsStack,
                homeMccMnc = record.homeMccMnc,
                spn = record.spn,
                ocdProfile = record.ocdProfile,
                feature = session.feature,
                bearerCode = session.feature.bearerCode,
                session = session.number,
                configOrigin = session.configOrigin,
            )
        } else {
            null
        }
        _state.update {
            it.copy(
                call = it.call.copy(
                    phase = CallPhase.READY,
                    result = null,
                    pendingAudio = null,
                    session = if (assessment.concluded) null else updated,
                    concluded = if (assessment.concluded) assessment else null,
                ),
            )
        }
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) {
                val kept = callStore.add(record)
                if (pass != null) callStore.add(pass) else kept
            }
            _state.update { it.copy(call = it.call.copy(log = log)) }
        }
    }

    fun discardCallTest() = _state.update { it.copy(call = it.call.copy(phase = CallPhase.READY, result = null, pendingAudio = null)) }

    fun deleteCallTests() {
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { callStore.clear() }
            _state.update { it.copy(call = it.call.copy(log = log, session = null, concluded = null), switches = it.switches.copy(last = null)) }
        }
    }

    /** The bundled snapshot, and in the read-only builds the last good pull when one is in place (round 32 U1). */
    private suspend fun loadBundle(): OcdBundle? = try {
        withContext(Dispatchers.IO) {
            val base = getApplication<Application>().assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip)
            bundled = base
            val pulled = if (BuildConfig.TESTER.isEmpty()) publicData.current() else null
            val line = if (BuildConfig.TESTER.isEmpty()) publicData.state().lastLine else null
            _state.update { it.copy(pulled = pulled, pullLine = line) }
            pulled?.bundle ?: base
        }.also { bundle -> _state.update { it.copy(bundle = bundle) } }
    } catch (error: Exception) {
        _state.update { it.copy(loading = false, bundleError = error.message ?: error.javaClass.simpleName) }
        null
    }

    private fun UiState.withReport(): UiState {
        val bundle = bundle ?: return this
        val readings = readings ?: return this
        return copy(report = Report.build(readings, resolution, findings, bundle, BuildConfig.VERSION_NAME, includeGid1))
    }

    private fun UiState.withSwitchSteps(): UiState {
        val bundle = bundle ?: return this
        val readings = readings ?: return this
        return copy(switches = switches.copy(offered = SwitchSteps.offered(readings, resolution, bundle)))
    }
}
