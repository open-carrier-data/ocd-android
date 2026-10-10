package io.github.open_carrier_data.check.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.Finding
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.Report
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.Resolution
import io.github.open_carrier_data.check.phone.PhoneReader
import io.github.open_carrier_data.check.phone.SimChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = PhoneReader(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, permissionGranted = reader.hasPhonePermission()) }
            val bundle = _state.value.bundle ?: loadBundle() ?: return@launch
            val sims = reader.simChoices()
            val selected = _state.value.selectedSubId?.takeIf { id -> sims.any { it.subId == id } }
                ?: sims.firstOrNull()?.subId
            val readings = reader.read(selected)
            val resolution = withContext(Dispatchers.Default) {
                readings.sim?.identity()?.let { Matcher.resolve(bundle, it) }
            }
            val findings = Findings.compare(readings, resolution, bundle)
            _state.update {
                it.copy(
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
