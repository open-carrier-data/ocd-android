package io.github.open_carrier_data.check.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTestLog
import io.github.open_carrier_data.check.core.CallTestRecord
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.HeardMailbox
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.Mailbox
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.Resolution
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.phone.CallTestPhone
import io.github.open_carrier_data.check.phone.CallTestStore
import io.github.open_carrier_data.check.phone.PhoneReader
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
) {
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

data class CallTestUi(
    /** This SIM's mailbox from the bundled table, or null: then no test is offered. */
    val mailbox: Mailbox? = null,
    val phase: CallPhase = CallPhase.READY,
    /** The watch so far, read once a second while the app is in front. */
    val live: CallWatchSummary? = null,
    /** What the stopped watch saw, until the person answers. */
    val result: CallWatchSummary? = null,
    val airplaneAtStart: Boolean? = null,
    val wifiAtStart: Boolean? = null,
    val problem: String? = null,
    val log: CallTestLog = CallTestLog(),
)

class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = PhoneReader(application)
    private val shizuku = ShizukuAccess(application)
    private val callPhone = CallTestPhone(application)
    private val callStore = CallTestStore(application)
    private val callTargets by lazy { callPhone.targets() }
    private var callSession: ShizukuAccess.CallWatchSession? = null
    private var callPoll: Job? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

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
        val start = _state.value
        val readings = start.readings ?: return
        val bundle = start.bundle ?: return
        val target = start.deepTarget ?: return
        if (start.deepRunning) return
        viewModelScope.launch {
            _state.update { it.copy(deepRunning = true, deepProblem = null) }
            val deep = try {
                shizuku.run(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val status = if (deep == null) shizuku.status() else null
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
                        val updated = readings.copy(deep = deep)
                        current.copy(
                            deepRunning = false,
                            readings = updated,
                            findings = Findings.compare(updated, current.resolution, bundle),
                        ).withReport()
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, permissionGranted = reader.hasPhonePermission(), deepProblem = null) }
            val bundle = _state.value.bundle ?: loadBundle() ?: return@launch
            val sims = reader.simChoices()
            val selected = _state.value.selectedSubId?.takeIf { id -> sims.any { it.subId == id } }
                ?: sims.firstOrNull()?.subId
            val readings = reader.read(selected)
            val resolution = withContext(Dispatchers.Default) {
                readings.sim?.identity()?.let { Matcher.resolve(bundle, it) }
            }
            val findings = Findings.compare(readings, resolution, bundle)
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
                ).withReport()
            }
        }
    }

    fun refreshIfPermissionChanged() {
        val current = _state.value
        if (!current.loading && current.permissionGranted != reader.hasPhonePermission()) refresh()
    }

    fun selectSim(subId: Int) {
        if (subId == _state.value.selectedSubId) return
        _state.update { it.copy(selectedSubId = subId) }
        refresh()
    }

    fun setIncludeGid1(include: Boolean) = _state.update { it.copy(includeGid1 = include).withReport() }

    fun setShowReport(show: Boolean) = _state.update { it.copy(showReport = show) }

    /** Starts watching the call state. The person dials; the app never does. */
    fun startCallWatch() {
        val start = _state.value
        val slot = start.deepTarget?.slot ?: return
        if (start.call.mailbox == null || start.call.phase != CallPhase.READY) return
        _state.update { it.copy(call = it.call.copy(phase = CallPhase.STARTING, problem = null, live = null, result = null)) }
        viewModelScope.launch {
            val airplane = callPhone.airplaneMode()
            val wifi = callPhone.onWifi()
            val session = try {
                shizuku.startCallWatch(slot)
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

    /** Keeps the test with the person's answer, on this phone only. */
    fun answerCallTest(heard: HeardMailbox) {
        val current = _state.value
        val call = current.call
        val result = call.result ?: return
        val mailbox = call.mailbox ?: return
        val readings = current.readings ?: return
        if (call.phase != CallPhase.ASK || !result.connected) return
        val device = readings.device
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
            heard = heard,
        )
        _state.update { it.copy(call = it.call.copy(phase = CallPhase.READY, result = null)) }
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { callStore.add(record) }
            _state.update { it.copy(call = it.call.copy(log = log)) }
        }
    }

    fun discardCallTest() = _state.update { it.copy(call = it.call.copy(phase = CallPhase.READY, result = null)) }

    fun deleteCallTests() {
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { callStore.clear() }
            _state.update { it.copy(call = it.call.copy(log = log)) }
        }
    }

    private suspend fun loadBundle(): OcdBundle? = try {
        withContext(Dispatchers.IO) {
            getApplication<Application>().assets.open(OcdBundle.ASSET_NAME).use(OcdBundle::readGzip)
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
}
