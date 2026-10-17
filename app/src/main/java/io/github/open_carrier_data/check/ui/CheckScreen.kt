package io.github.open_carrier_data.check.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.core.ApnEdited
import io.github.open_carrier_data.check.core.ApnListParser
import io.github.open_carrier_data.check.core.Capabilities
import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.CarrierKeys
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.FiveG
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.phone.ShizukuStatus

@Composable
fun CheckScreen(viewModel: CheckViewModel) {
    val state by viewModel.state.collectAsState()
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, viewModel::refresh)
            state.bundleError?.let {
                Section("The bundled OCD data could not be read") { Note(it) }
                return@Column
            }
            val bundle = state.bundle ?: run {
                LoadingRow("Loading OCD data")
                return@Column
            }
            if (!state.permissionGranted) PermissionCard(onResult = viewModel::refresh)
            if (state.sims.size > 1) SimPicker(state, viewModel::selectSim)
            val readings = state.readings
            when {
                readings == null -> LoadingRow("Reading this phone")
                !readings.hasTelephony -> Section("No mobile phone function") {
                    Note("This device has no SIM or mobile network support, so there is nothing to check.")
                }
                readings.sim == null -> Section("No active SIM") {
                    Note("Insert a SIM, or turn on the SIM in Settings, then tap Check again.")
                }
                else -> {
                    SimSection(state)
                    NetworkSection(state)
                    CarrierSettingsSection(state, bundle)
                    ConnectionsSection(state)
                    DeepCheckSection(state, viewModel)
                    OcdSection(state, bundle)
                }
            }
            if (state.findings.isNotEmpty()) {
                Section("Findings") {
                    Note("Where this phone's readings and OCD's values meet. None of these says a feature works.")
                    state.findings.forEach { Bullet(it.text) }
                }
            }
            Section("What no carrier setting shows") {
                Text(bundle.limits.gates, style = MaterialTheme.typography.bodyMedium)
                Source(bundle)
            }
            ReportSection(state, viewModel)
            Footer()
        }
    }
}

@Composable
private fun Header(state: UiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("OCD Check", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Compares this phone's SIM, network and carrier settings with Open Carrier Data (OCD). " +
                "It only reads: it changes nothing on the phone. It has no internet permission, so it " +
                "cannot send anything; a report leaves the phone only if you copy or share it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.bundle?.let { bundle ->
            val source = bundle.source
            Note(
                "OCD data: ${bundle.profiles.size} profiles from commit ${source.commit.take(7)} " +
                    "(${source.commitDate}). Oldest source check ${source.checksThrough}; " +
                    "stale after ${source.staleAfter}.",
            )
            state.readings?.let { readings ->
                if (bundle.isStale(readings.checkedOn)) {
                    Text(
                        "This data is past its stale-after date. Update the app before you rely on it.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRefresh, enabled = !state.loading) { Text("Check again") }
            if (state.loading) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun PermissionCard(onResult: () -> Unit) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        onResult()
    }
    val blocked = asked && activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE) == false
    Section("Phone status permission") {
        Text(
            "With this permission the app can read the SIM's group ID (GID1), this phone's carrier settings " +
                "and the network type. Android names the permission \"make and manage phone calls\". The app " +
                "makes no calls, sees no calls and reads no phone number, IMSI, ICCID or IMEI.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Note("Without it, the app still shows the SIM's network code, name and carrier IDs, and OCD's values.")
        if (blocked) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Open app settings") }
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("Allow phone status") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimPicker(state: UiState, onSelect: (Int) -> Unit) {
    Section("SIM to check") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.sims.forEach { sim ->
                FilterChip(
                    selected = sim.subId == state.selectedSubId,
                    onClick = { onSelect(sim.subId) },
                    label = { Text(sim.label) },
                )
            }
        }
    }
}

@Composable
private fun SimSection(state: UiState) {
    val sim = state.readings?.sim ?: return
    Section("Your SIM") {
        sim.slotIndex?.let { Field("Slot", "${it + 1}") }
        Field("Network code (MCC/MNC)", sim.homeMccMnc ?: "not given")
        Field("SIM name (SPN)", sim.spn?.ifBlank { null } ?: "none")
        Field("Android carrier ID", idText(sim.carrierId, sim.carrierName))
        Field(
            "Specific carrier ID",
            idText(sim.specificCarrierId, sim.specificCarrierName),
            "Android works out both IDs from SIM details this app never sees.",
        )
        when (sim.gid1Status) {
            Gid1Status.READ -> Field("Group ID (GID1)", sim.gid1.orEmpty(), "Goes into the report only if you switch it on there.")
            Gid1Status.EMPTY -> Field("Group ID (GID1)", "none on this SIM")
            Gid1Status.NOT_PERMITTED -> Field("Group ID (GID1)", "needs the phone status permission")
            Gid1Status.UNAVAILABLE -> Field("Group ID (GID1)", "not available")
        }
    }
}

private fun idText(id: Long?, name: String?): String = when {
    id == null -> "not identified"
    name != null -> "$id ($name)"
    else -> "$id"
}

@Composable
private fun NetworkSection(state: UiState) {
    val readings = state.readings ?: return
    val network = readings.network ?: return
    val needsPermission = "needs the phone status permission"
    Section("Network now") {
        Field("Serving network", network.servingMccMnc ?: "not given")
        Field("Roaming", yesNo(network.roaming))
        val data = network.psWwanType?.takeIf { it != 0 } ?: network.dataNetworkType
        Field("Mobile data", if (data == null && !readings.permissionGranted) needsPermission else Radio.plain(data))
        Field(
            "Calls (voice network)",
            if (network.voiceNetworkType == null && !readings.permissionGranted) needsPermission else Radio.plain(network.voiceNetworkType),
        )
        Field("5G", FiveG.classify(network).plain)
        Field("5G icon", Radio.iconPlain(network.iconOverride))
        Field("5G signal reported", yesNo(network.nrSignalReported))
        Note(
            "Read from public Android calls. The 5G icon follows carrier rules, so it is a hint; " +
                "the radio's own state is hidden from apps.",
        )
    }
}

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "not known"
}

@Composable
private fun CarrierSettingsSection(state: UiState, bundle: OcdBundle) {
    val readings = state.readings ?: return
    Section("Carrier settings on this phone") {
        val settings = readings.carrierSettings
        when {
            !readings.permissionGranted -> Note("Reading them needs the phone status permission.")
            settings == null || !settings.readable -> Note("The phone returned no carrier settings for this SIM.")
            else -> {
                Field(
                    "Settings for this carrier",
                    when (settings.identifiedCarrier) {
                        true -> "carrier-specific settings applied"
                        false -> "Android's defaults only"
                        null -> "not known"
                    },
                )
                val resolution = state.resolution?.takeIf { it.stack.isNotEmpty() }
                for (key in CarrierKeys.booleans) {
                    val capability = key.capability ?: continue
                    val phone = settings.booleans[key.key]
                    val ocd = resolution?.answer(capability)
                    val comparison = when (ocd?.let { CarrierKeys.matchesOcd(phone, it) }) {
                        true -> "agrees with OCD's answer"
                        false -> "differs from OCD's answer"
                        null -> "nothing to compare"
                    }
                    Field(
                        "${Capabilities.label(capability)} switch",
                        when (phone) {
                            true -> "on"
                            false -> "off"
                            null -> "not set"
                        },
                        "${key.key} · OCD: ${ocd?.wire ?: "no profile"} · $comparison",
                    )
                }
                Field(
                    "5G modes the settings allow",
                    CarrierKeys.nrPlain(settings.nrAvailabilities),
                    CarrierKeys.NR_AVAILABILITIES,
                )
                Note("From OCD's README: ${bundle.limits.missingSwitch}")
            }
        }
    }
}

@Composable
private fun ConnectionsSection(state: UiState) {
    val connections = state.readings?.dataConnections ?: return
    Section("Mobile data connections") {
        if (connections.isEmpty()) Note("None visible to this app right now.")
        connections.forEach { connection ->
            Field(
                connection.apn ?: "APN name not visible",
                if (connection.serves.isEmpty()) "serves: not shown" else "serves: ${connection.serves.joinToString(", ")}",
                "set up over ${Radio.plain(connection.setUpOver)}",
            )
        }
        if (connections.any { "ims" in it.serves }) {
            Note("An IMS data connection is up. VoLTE needs it, but it does not show that the phone is signed in for calls.")
        }
        Note("Only the connections Android shows to normal apps. The phone's APN list needs the deep check below.")
    }
}

@Composable
private fun DeepCheckSection(state: UiState, viewModel: CheckViewModel) {
    val status = state.shizuku ?: return
    val target = state.deepTarget
    Section("Deep check (Shizuku)") {
        Text(
            "Optional. Through Shizuku, the app reads what Android hides from normal apps: whether the phone is " +
                "signed in for VoLTE or Wi-Fi calling (Samsung's IMS), Samsung's Wi-Fi calling switch, whether the " +
                "carrier's Wi-Fi calling server name resolves, and the phone's APN list. It runs the fixed read-only " +
                "commands below and nothing else, and it changes nothing. Their output is reduced to the fields " +
                "shown here inside the check; the raw text is never kept.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ShizukuFields(status)
        when {
            !status.running -> Note(
                "Start Shizuku (for example with wireless debugging), then come back. Everything above works without it.",
            )
            status.tooOld -> Note("This app needs Shizuku 11 or newer.")
            status.refused -> Note("Shizuku remembers that you refused. To allow it, open the Shizuku app and switch OCD Check on there.")
            !status.permitted -> Button(onClick = viewModel::requestShizukuPermission) { Text("Allow in Shizuku") }
            target == null -> Note("The SIM's slot is not known, so the deep check cannot run.")
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::runDeepCheck, enabled = !state.deepRunning && !state.loading) { Text("Run deep check") }
                if (state.deepRunning) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }
        if (!status.ready) OutlinedButton(onClick = viewModel::refreshShizuku) { Text("Look for Shizuku again") }
        state.deepProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (target != null) {
            Label("Commands it runs, and only these")
            ShellCommand.entries.mapNotNull { it.argv(target) }.forEach { argv ->
                Text(argv.joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Note(
                "The ePDG lookup sends one DNS query and, if the name resolves, one ping to the carrier's server; " +
                    "only the first line of its output is read. The Wi-Fi calling switch is read only on Samsung's IMS.",
            )
        }
        state.readings?.deep?.let {
            HorizontalDivider()
            DeepResults(it)
            Note("Check again clears these results; run the deep check again after it.")
        }
    }
}

@Composable
private fun ShizukuFields(status: ShizukuStatus) {
    Field("Shizuku app", if (status.installed) "installed" else "not installed")
    Field(
        "Shizuku service",
        when {
            !status.running -> "not running"
            status.tooOld -> "running, but too old"
            status.asRoot -> "running (as root)"
            else -> "running (as the shell user)"
        },
    )
    if (status.running && !status.tooOld) {
        Field("Access for OCD Check", if (status.permitted) "allowed" else if (status.refused) "refused" else "not allowed yet")
    }
}

@Composable
private fun DeepResults(deep: DeepCheckReading) {
    val ims = deep.ims
    Label("IMS (calls over 4G, 5G and Wi-Fi)")
    when (ims.source) {
        ImsSource.NOT_FOUND -> Note("No Samsung IMS service on this phone. Other IMS stacks are not read yet.")
        ImsSource.UNREADABLE -> Note("Samsung's IMS state could not be read.")
        ImsSource.SAMSUNG -> {
            Field(
                "Signed in",
                when (ims.registered) {
                    true -> "yes" + (ims.transport?.let { ", over ${it.plain}" } ?: "")
                    false -> "no"
                    null -> "not known (no entry for this SIM's slot)"
                },
                ims.state?.let { "Samsung's state: ${it.replace('_', ' ')}" },
                emphasize = true,
            )
            if (ims.registered == true) {
                Field("Services", ims.services.joinToString(", ") { it.plain }.ifEmpty { "none this app can name" })
            }
            ims.mno?.let { Field("Samsung's carrier table", it) }
        }
    }
    Label("Wi-Fi calling")
    Field("Switch", deep.wifiCallingSwitch.plain)
    Field(
        "Server name (ePDG)",
        deep.epdgName.plain,
        if (deep.epdgName == EpdgName.NOT_CHECKED) {
            null
        } else if (deep.lookupNetwork == LookupNetwork.NONE) {
            "The phone had no network during the lookup, so this says nothing about DNS."
        } else {
            "Looked up over ${deep.lookupNetwork.plain}" +
                when (deep.privateDnsActive) {
                    true -> ", Private DNS on."
                    false -> ", Private DNS off."
                    null -> "."
                }
        },
    )
    val apns = deep.apnList ?: return
    Label("APN list for this network")
    if (!apns.readable) {
        Note("The APN list could not be read.")
        return
    }
    if (apns.rows.isEmpty()) Note("No APN rows for this network code.")
    apns.rows.forEach { row ->
        val details = listOfNotNull(
            row.protocol?.let { "protocol $it" },
            row.roamingProtocol?.let { "roaming $it" },
            row.carrierEnabled?.let { if (it) "enabled" else "switched off by the carrier" },
            row.mvnoType?.let { "MVNO row (matched by $it)" },
            when (row.edited) {
                ApnEdited.BY_USER -> "added or changed by hand"
                ApnEdited.BY_CARRIER -> "changed by the carrier"
                else -> null
            },
        )
        Field(
            row.apn,
            if (row.types.isEmpty()) "types: not set" else "types: ${row.types.joinToString(", ")}",
            details.joinToString(" · ").ifEmpty { null },
        )
    }
    if (apns.leftOut > 0) {
        Note("${apns.leftOut} row(s) left out: unusual shape, emergency, deleted, or past the limit of ${ApnListParser.MAX_ROWS} rows.")
    }
    Note("Rows for every SIM on this network code, including other MVNOs. User names, passwords, servers and proxies are never read.")
}

@Composable
private fun OcdSection(state: UiState, bundle: OcdBundle) {
    val resolution = state.resolution
    Section("What OCD says for this SIM") {
        Note(bundle.limits.tablesNotTests)
        Note(bundle.limits.notVerified)
        if (resolution == null) {
            Note("The SIM gives no network code, so there is nothing to look up.")
            return@Section
        }
        if (resolution.stack.isEmpty()) {
            Text("OCD has no profile for network code ${resolution.identity.mccmnc}.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Label("Profiles that match, general to specific")
            resolution.stack.forEachIndexed { index, profile ->
                Field(
                    "${index + 1}. ${profile.displayName}",
                    "matches on ${profile.match.dimensionNames.joinToString(", ") { dimensionPlain(it) }}",
                    "${profile.profileId} · newest source entry ${profile.newestEntry ?: "not given"}",
                )
            }
            HorizontalDivider()
            val most = resolution.mostSpecific!!
            Label("Answers from the most specific profile (${most.displayName})")
            for (key in Capabilities.ordered(bundle.capabilityKeys)) {
                val value = resolution.answer(key)
                val broader = resolution.broaderValues(key).filter { it.second != value }
                val note = buildString {
                    append(value.wire.replaceFirstChar(Char::uppercase)).append(": ").append(bundle.limits.meaning(value))
                    if (value == CapabilityValue.UNKNOWN && broader.isNotEmpty()) {
                        val (profile, other) = broader.last()
                        append(" ${profile.displayName} (a broader profile) says ${other.wire}; that is the host network's value.")
                    }
                }
                Field(Capabilities.label(key), value.wire, note, emphasize = true)
                DeviceRecordLine(state, bundle, most.profileId, key)
            }
            bundle.limits.deviceRecords?.takeIf { bundle.records != null }?.let { Note("From OCD's README: $it") }
        }
        if (resolution.notCheckable.isNotEmpty()) {
            HorizontalDivider()
            Label("Profiles this app cannot check")
            resolution.notCheckable.forEach { item ->
                Field(
                    item.profile.displayName,
                    "matches on ${item.needs.joinToString(", ") { dimensionPlain(it) }}",
                    item.profile.profileId,
                )
            }
        }
    }
}

/**
 * For VoLTE, Wi-Fi calling, VoNR and video calling: what this phone model's own
 * file sets, next to the carrier summary above it (OCD's device records). No
 * record keeps the value above as the carrier's, never this phone's.
 */
@Composable
private fun DeviceRecordLine(state: UiState, bundle: OcdBundle, profileId: String, capability: String) {
    val records = bundle.records ?: return
    if (capability !in Findings.deviceRecordCapabilities) return
    val device = state.readings?.device ?: return
    val match = records.recordsFor(profileId, capability, device).firstOrNull()
    if (match == null) {
        Note("Carrier summary: OCD has no file of this phone model that differs from it.")
        return
    }
    val value = when (match.record.state) {
        "on" -> "on"
        "off" -> "off"
        else -> "its files disagree"
    }
    Field(
        "  ${match.whose} says",
        value,
        "${match.sourceLine.replaceFirstChar(Char::uppercase)}. Configuration, not a test." +
            if (match.sameStack) "" else " This phone runs ${device.romFamily.plain}.",
    )
}

private fun dimensionPlain(name: String): String = when (name) {
    "mccmnc" -> "network code"
    "spn" -> "SIM name"
    "gid1", "gid1_prefixes" -> "GID1"
    "gid2", "gid2_prefixes" -> "GID2"
    "iccid", "iccid_prefixes" -> "ICCID (hidden from apps)"
    "imsi", "imsi_prefix_patterns" -> "IMSI (hidden from apps)"
    "carrier_id", "android_carrier_ids" -> "carrier ID"
    else -> name
}

@Composable
private fun ReportSection(state: UiState, viewModel: CheckViewModel) {
    val report = state.report ?: return
    val context = LocalContext.current
    Section("Report") {
        Text(
            "The report holds only: the SIM's network code, name and carrier IDs, GID1 if you switch it on, " +
                "the phone model and its code name, the Android version, the software family, the check results " +
                "(with the deep check: the IMS state, the Wi-Fi calling switch and server name check, and the APN " +
                "list without credentials), and the app and data versions. It has the date, not the time. " +
                "Read it before you share it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.readings?.sim?.gid1Status == Gid1Status.READ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = state.includeGid1, onCheckedChange = viewModel::setIncludeGid1)
                Column(Modifier.weight(1f)) {
                    Text("Include GID1", style = MaterialTheme.typography.bodyLarge)
                    Note("The OCD profiles your SIM matched stay in the report either way; a GID1 profile shows the start of your GID1.")
                }
            }
        }
        OutlinedButton(onClick = { viewModel.setShowReport(!state.showReport) }) {
            Text(if (state.showReport) "Hide report" else "Show report")
        }
        if (state.showReport) ReportBody(report, context)
    }
}

@Composable
private fun ReportBody(report: ReportResult, context: Context) {
    if (!report.shareable) {
        Text(
            "The report did not pass the privacy check, so Copy and Share are off. Fields that failed:",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        report.hits.forEach { Bullet("${it.path.removePrefix("report.")} (${it.rule.replace('_', ' ')})") }
        if (report.hits.any { it.path == "report.sim.gid1" }) Note("Switching GID1 off removes that field.")
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        SelectionContainer {
            Text(
                report.text,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { copy(context, report.text) }) { Text("Copy") }
        OutlinedButton(onClick = { share(context, report.text) }) { Text("Share") }
    }
    Note("Share opens Android's share sheet; you choose where the text goes. The app sends nothing itself.")
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("OCD Check report", text))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "OCD Check report")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share the report"))
}

@Composable
private fun Footer() {
    Note(
        "OCD Check ${BuildConfig.VERSION_NAME}, early test version. App code Apache-2.0. " +
            "Data from Open Carrier Data, CC0 1.0 for the OCD contributors' own rights.",
    )
}

@Composable
private fun Source(bundle: OcdBundle) {
    Note("From OCD's README, \"${bundle.limits.readmeSection}\", commit ${bundle.source.commit.take(7)}.")
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, note: String? = null, emphasize: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
        note?.let { Note(it) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(text)
    }
}
