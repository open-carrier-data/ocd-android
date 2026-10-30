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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.OwnerHook
import io.github.open_carrier_data.check.core.ApnEdited
import io.github.open_carrier_data.check.core.ApnListParser
import io.github.open_carrier_data.check.core.AudioAnswer
import io.github.open_carrier_data.check.core.CallDirection
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.Capabilities
import io.github.open_carrier_data.check.core.CapabilityValue
import io.github.open_carrier_data.check.core.CarrierKeys
import io.github.open_carrier_data.check.core.DeepCheckReading
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.EpdgName
import io.github.open_carrier_data.check.core.Findings
import io.github.open_carrier_data.check.core.FiveG
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.ImsSource
import io.github.open_carrier_data.check.core.LabelHints
import io.github.open_carrier_data.check.core.LookupNetwork
import io.github.open_carrier_data.check.core.OcdBundle
import io.github.open_carrier_data.check.core.Radio
import io.github.open_carrier_data.check.core.ReportResult
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SwitchStep
import io.github.open_carrier_data.check.core.SwitchSteps
import io.github.open_carrier_data.check.core.T2Feature
import io.github.open_carrier_data.check.core.T2Status
import io.github.open_carrier_data.check.core.ToggleAnswer
import io.github.open_carrier_data.check.core.UserToggleRecord
import io.github.open_carrier_data.check.phone.SettingsScreens
import io.github.open_carrier_data.check.phone.ShizukuStatus

/** [toCallTest]: opened for the call test (the owner build's "Test calls"); the screen scrolls to it once it shows. */
@Composable
fun CheckScreen(viewModel: CheckViewModel, toCallTest: Boolean = false) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()
    var callTestY by remember { mutableStateOf<Int?>(null) }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(callTestY) {
        val y = callTestY
        if (toCallTest && !scrolled && y != null) {
            scrolled = true
            scroll.animateScrollTo(y)
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, viewModel::refresh, viewModel::pullData)
            // Nothing in debug and release; the owner build's entry card.
            OwnerHook.Entry()
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
                    ConnectionsSection(state, viewModel)
                    DeepCheckSection(state, viewModel)
                    SwitchSection(state, viewModel)
                    Column(Modifier.onGloballyPositioned { callTestY = it.positionInParent().y.toInt() }) { CallTestSection(state, viewModel) }
                    OcdSection(state, bundle)
                    StatementsSection(state)
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
private fun Header(state: UiState, onRefresh: () -> Unit, onPull: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("OCD Check", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Compares this phone's SIM, network and carrier settings with Open Carrier Data (OCD). " +
                "It only reads: it changes nothing on the phone. $PRIVACY",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.bundle?.let { bundle ->
            val source = bundle.source
            Note(
                "OCD data: ${bundle.profiles.size} profiles from commit ${source.commit.take(7)} " +
                    "(${source.commitDate}), ${if (state.pulled != null) "downloaded from OCD's validated main" else "shipped with this app"}. " +
                    "Oldest source check ${source.checksThrough}; stale after ${source.staleAfter}.",
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
            // Round 32 U1: the read-only builds pull OCD's newest validated data only on this tap.
            if (state.canPull) OutlinedButton(onClick = onPull, enabled = !state.pulling && state.bundle != null) { Text("Get newest data") }
            if (state.loading || state.pulling) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
        if (state.canPull) state.pullLine?.let { Note(it) }
    }
}

/**
 * The read-only builds' privacy sentence. Round 32 U1 added the tapped data pull; round 42 (item 2)
 * adds the tapped mobile-data path check, so the plain "sends nothing" wording is corrected to
 * disclose both tapped requests, which carry no identifier.
 */
private const val PRIVACY =
    "It sends nothing by itself. Only when you tap Get newest data it fetches OCD's public data from " +
        "GitHub, and only when you tap \"Why is mobile data not working?\" it sends a few kilobytes of " +
        "mobile data to fixed test hosts to find the cause. Both carry no identifier of the phone or the " +
        "SIM. A report leaves the phone only if you copy or share it."

/** Round 32 part A: what the SIM's operator states on its own pages; shown, never used as a value. */
@Composable
private fun StatementsSection(state: UiState) {
    val display = state.statements
    if (display == null || display.empty) {
        if (state.canPull && state.pulled?.statements == null) {
            Section("What the operator states") {
                Note("Operator statements come with OCD's newest data: tap Get newest data.")
            }
        }
        return
    }
    Section("What the operator states") {
        display.about?.let { Label(it) }
        display.lines.forEach { Bullet(it) }
        if (display.brandsOnNetwork.isNotEmpty()) Note("Brands on this network: ${display.brandsOnNetwork.joinToString()}. A phone can't tell their SIMs apart from the network's own.")
        Note(io.github.open_carrier_data.check.core.StatementDisplay.NOTE)
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
                "makes no calls and reads no phone number, IMSI, ICCID or IMEI. It sees no calls through this " +
                "permission; only the optional guided call test reads, through Shizuku, the state and network of " +
                "a call you place yourself.",
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
private fun ConnectionsSection(state: UiState, viewModel: CheckViewModel) {
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
        // Round 42 item 2: the read-only probe ladder, on a tap only. It checks the mobile-data path
        // over the cellular link and sends a few kilobytes of mobile data; no automatic run.
        OutlinedButton(onClick = viewModel::runProbes, enabled = !state.probing) {
            Text("Why is mobile data not working?")
        }
        if (state.probing) Note("Checking the mobile-data path…")
        state.probeLine?.let { Note(it) }
    }
}

@Composable
private fun DeepCheckSection(state: UiState, viewModel: CheckViewModel) {
    val status = state.shizuku ?: return
    val target = state.deepTarget
    Section("Deep check (Shizuku)") {
        Text(
            "Optional. Through Shizuku, the app reads what Android hides from normal apps: whether the phone is " +
                "signed in for VoLTE or Wi-Fi calling (Samsung's IMS, or Android's own IMS on LineageOS and similar " +
                "builds), whether the network offers voice over IMS here, the Wi-Fi calling switch, whether the " +
                "carrier's Wi-Fi calling server name resolves (also in the phone's own lookup), and the phone's APN " +
                "list. It runs the fixed read-only commands below and nothing else, and it changes nothing. Their " +
                "output is reduced to the fields shown here inside the check; the raw text is never kept.",
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
                    "only the first line of its output is read. Samsung's Wi-Fi calling switch is read only on Samsung's " +
                    "IMS; Android's IMS, the registry, the IWLAN log lines and the Wi-Fi's DNS servers only where they apply.",
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
        ImsSource.NOT_FOUND -> Note("Neither Samsung's IMS nor Android's own IMS state was found for this SIM's slot.")
        ImsSource.UNREADABLE -> Note("The IMS state could not be read.")
        ImsSource.SAMSUNG, ImsSource.AOSP -> {
            val samsung = ims.source == ImsSource.SAMSUNG
            Field(
                "Signed in",
                when (ims.registered) {
                    true -> "yes" + (ims.transport?.let { ", over ${it.plain}" } ?: "")
                    false -> "no"
                    null -> "not known (no entry for this SIM's slot)"
                },
                listOfNotNull(
                    if (samsung) "Samsung's IMS" else "Android's IMS",
                    ims.state?.let { "state: ${it.replace('_', ' ')}" },
                ).joinToString(" · "),
                emphasize = true,
            )
            if (ims.registered == true) {
                Field("Services", ims.services.joinToString(", ") { it.plain }.ifEmpty { "none this app can name" })
            }
            ims.imsPdn?.let { Field("IMS data connection", "on ${it.plain}") }
            ims.registrationLogTransport?.let {
                Note(
                    "The IMS registration log still names ${it.plain}. The app takes the network from where the IMS " +
                        "data connection is, because the log can stay behind after a fallback.",
                )
            }
            ims.lastDisconnect?.let { Field("Last sign-out reason", "${it.code}${it.name?.let { name -> " ($name)" }.orEmpty()}") }
            ims.mno?.let { Field("Samsung's carrier table", it) }
        }
    }
    deep.networkOffer?.let { offer ->
        Field(
            "Voice over IMS offered by the network (VoPS)",
            "LTE: ${offer.lteVops?.plain ?: "not reported"} · 5G: ${offer.nrVops?.plain ?: "not reported"}",
            "What the serving cell says it offers. It says nothing about your plan.",
        )
    }
    Label("Wi-Fi calling")
    Field("Switch", deep.wifiCallingSwitch.plain, if (ims.source == ImsSource.AOSP) "The phone's own Wi-Fi calling setting" else null)
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
    deep.privateDns?.let {
        Field(
            "Private DNS",
            it.plain,
            listOfNotNull(
                when (it.ecs) {
                    true -> "Passes ECS (part of your IP address) on to the name servers it asks."
                    false -> "Sends no ECS."
                    null -> null
                },
                if (it.strict) "Every lookup on this phone goes to this server; the Wi-Fi's resolver is not used." else null,
            ).joinToString(" ").ifEmpty { null },
        )
    }
    deep.wifiResolver?.let { Field("This Wi-Fi's DNS resolver", it.plain) }
    deep.iwlan?.let { iwlan ->
        val lookup = iwlan.phoneLookup
        Field(
            "The phone's own lookup of the server",
            when {
                lookup == null -> "not in the log"
                !lookup.standardName -> "for another name"
                lookup.resolved -> "got an address"
                else -> "got no address"
            },
            lookup?.let { "${it.ageMinutes} minutes ago, by Android's Wi-Fi calling service (IWLAN)" },
        )
        Field(
            "Wi-Fi calling tunnel",
            when (iwlan.tunnelUp) {
                true -> "up"
                false -> "down"
                null -> "not in the log"
            },
            iwlan.lastRefusal?.let { "Last refusal: $it${iwlan.lastRefusalCode?.let { code -> " ($code)" }.orEmpty()}" },
        )
        iwlan.heldBackBy?.let { Field("Held back", "by ${it.plain}", iwlan.heldBackMinutes?.let { m -> "about $m more minutes" }) }
    }
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
    if (apns.repeated > 0) {
        Note("${apns.repeated} identical row(s) shown once. Samsung keeps a copy of each row per SIM slot.")
    }
    if (apns.leftOut > 0) {
        Note("${apns.leftOut} row(s) left out: unusual shape, emergency, deleted, or past the limit of ${ApnListParser.MAX_ROWS} rows.")
    }
    Note("Rows for every SIM on this network code, including other MVNOs. User names, passwords, servers and proxies are never read.")
}

/**
 * VoLTE and Wi-Fi calling on this phone: what the app may say about each
 * (round 23 LB4: "registered" from the deep check, "works on this phone"
 * only after T2), and the guided switch step (AP3) where OCD's value for this
 * phone is on and the phone has the feature off. The app opens Settings; the
 * person flips their own switch.
 */
@Composable
private fun SwitchSection(state: UiState, viewModel: CheckViewModel) {
    val readings = state.readings ?: return
    val switches = state.switches
    val context = LocalContext.current
    val device = readings.device
    Section("VoLTE and Wi-Fi calling on this phone") {
        T2Feature.entries.forEach { feature ->
            Field(feature.label, T2Status.line(feature, state.call.log, readings), emphasize = true)
        }
        Note(
            "\"Registered\" is what the deep check reads with the phone's current settings. \"Works on this phone\" " +
                "needs T2: an outgoing and an incoming call over the feature's network, with the audio confirmed (the " +
                "guided call test below).",
        )
        val toggle = switches.toggle
        when {
            toggle != null -> ToggleProgress(toggle, device, viewModel)
            switches.offered.isEmpty() -> Note(
                "No switch step to offer: the app offers one only where OCD's value for this phone is on and the " +
                    "phone has the feature off, in its carrier settings or in your own switch where the deep check reads it.",
            )
            else -> switches.offered.forEach { step ->
                HorizontalDivider()
                Label("Turn ${step.feature.label} on yourself (optional)")
                Field("OCD's value", step.ocd.sourceLine, step.ocd.stackNote(device), emphasize = true)
                Field("This phone", step.phoneLine(device))
                Text(step.todo, style = MaterialTheme.typography.bodyMedium)
                Note(SwitchSteps.where(step.feature, device))
                EmergencyNote()
                OutlinedButton(onClick = {
                    val opened = SettingsScreens.open(context, SwitchSteps.screens(step.feature, device), state.selectedSubId)
                    viewModel.wentToSettings(step, opened)
                }) { Text("Open Settings") }
            }
        }
        switches.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        switches.last?.let { last ->
            HorizontalDivider()
            Note("Kept on this phone: ${last.plain}")
            if (last.answer == ToggleAnswer.TURNED_ON) {
                Text(
                    "Next: the guided call test below, for ${last.feature.label}: an outgoing call to your mailbox and an " +
                        "incoming call from your second line.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToggleProgress(toggle: ToggleUi, device: DeviceInfo, viewModel: CheckViewModel) {
    val step = toggle.step
    HorizontalDivider()
    Label("${step.feature.label}: back from Settings")
    when (toggle.phase) {
        TogglePhase.IN_SETTINGS -> {
            Note("Settings is open at ${UserToggleRecord.screenPlain(toggle.screen.wire)}. The app reads the phone again when you come back.")
            OutlinedButton(onClick = viewModel::cancelToggle) { Text("Cancel") }
        }
        TogglePhase.REREADING -> LoadingRow("Reading the phone again")
        TogglePhase.ASK -> {
            Field("Before", step.phoneLine(device))
            Field("Now", toggle.after?.let { SwitchStep(step.feature, step.ocd, it).phoneLine(device) } ?: "could not be read")
            EmergencyNote()
            Text("Did you turn ${step.feature.label} on?", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.answerToggle(ToggleAnswer.TURNED_ON) }) { Text("Yes, I turned it on") }
                OutlinedButton(onClick = { viewModel.answerToggle(ToggleAnswer.NOT_CHANGED) }) { Text("No") }
                OutlinedButton(onClick = { viewModel.answerToggle(ToggleAnswer.NO_SWITCH) }) { Text("There was no switch") }
            }
        }
    }
}

/** Round 10's note, next to every voice switch the app points to. */
@Composable
private fun EmergencyNote() {
    Text(SwitchSteps.EMERGENCY_NOTE, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
}

/**
 * The guided call test (OCD round 19, T2): the person dials the SIM's own
 * mailbox and has their second line call this phone; the app only watches
 * the call state and the call's network type through the deep check, then
 * asks about the audio and, for the incoming call, the second line.
 */
@Composable
private fun CallTestSection(state: UiState, viewModel: CheckViewModel) {
    val status = state.shizuku ?: return
    val call = state.call
    Section("Guided call test") {
        Text(
            "Two calls for one feature: you call your SIM's own mailbox, and you call this phone from your second " +
                "line (another phone or SIM of yours) and answer it here yourself. This app never places, answers or " +
                "ends a call. While a call runs, the app reads through Shizuku once a second which network it uses " +
                "(4G, 5G or Wi-Fi). Afterwards it asks about the audio. Results stay in this app on this phone; they " +
                "are not in the report.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val mailbox = call.mailbox
        if (mailbox == null) {
            Note(
                "The app's mailbox list has no number for this SIM, so it offers no call test. It shows only a " +
                    "SIM's own mailbox number from that list, never an emergency or service number.",
            )
            return@Section
        }
        val ready = status.ready && state.deepTarget != null
        when {
            !status.ready -> Note("The call test needs the deep check's Shizuku access (see above) to read the call's network.")
            state.deepTarget == null -> Note("The SIM's slot is not known, so the call test cannot run.")
        }
        Field(
            "Your mailbox",
            mailbox.number,
            "${mailbox.brand}, from the app's mailbox list" + if (mailbox.unverified) " (${mailbox.verification})." else ".",
            emphasize = true,
        )
        when (call.phase) {
            CallPhase.READY, CallPhase.STARTING -> T2Plan(call, mailbox.number, ready, viewModel)
            CallPhase.WATCHING, CallPhase.STOPPING -> {
                Text(
                    if (call.intended == CallDirection.INCOMING) {
                        "Watching. Call this phone now from your second line, answer it here yourself, talk briefly, hang up, then come back."
                    } else {
                        "Watching. Dial ${mailbox.number} in your phone app now, listen, hang up, then come back."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                call.live?.let { LiveWatch(it) }
                Button(onClick = viewModel::stopCallWatch, enabled = call.phase == CallPhase.WATCHING) { Text("Stop") }
            }
            CallPhase.ASK -> {
                val result = call.result ?: return@Section
                WatchResult(result, call.intended)
                if (result.connected) {
                    val pending = call.pendingAudio
                    if (call.intended == CallDirection.INCOMING && pending != null) {
                        Text(
                            "Did the call come from your second line (not a mailbox, voicemail or service number)?",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.answerSecondLine(true) }) { Text("Yes") }
                            OutlinedButton(onClick = { viewModel.answerSecondLine(false) }) { Text("No") }
                        }
                    } else {
                        Text(
                            if (call.intended == CallDirection.INCOMING) "Could both of you hear each other?" else "Did you hear the mailbox?",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.answerAudio(AudioAnswer.YES) }) { Text("Yes") }
                            OutlinedButton(onClick = { viewModel.answerAudio(AudioAnswer.NO) }) { Text("No") }
                            OutlinedButton(onClick = { viewModel.answerAudio(AudioAnswer.NOT_SURE) }) { Text("Not sure") }
                        }
                    }
                }
                OutlinedButton(onClick = viewModel::discardCallTest) { Text("Discard") }
            }
            CallPhase.DISCARDED -> {
                Text(
                    "An emergency call showed during the watch, so the app kept nothing from it.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::discardCallTest) { Text("OK") }
            }
        }
        call.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Label("Command it runs, once a second while watching")
        Text("dumpsys telephony.registry", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Note(
            "Only this SIM's call state, the call's network type, whether an emergency call started and whether an " +
                "incoming number is one of the mailbox short codes are taken from it. The incoming number is compared " +
                "where it is read and never kept or shown; numbers and cell details are dropped there.",
        )
        CallTestHistory(call, viewModel)
    }
}

/** The T2 test: which feature, the two calls, and how far it is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun T2Plan(call: CallTestUi, mailbox: String, ready: Boolean, viewModel: CheckViewModel) {
    Label("Feature to test")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T2Feature.entries.forEach { feature ->
            FilterChip(
                selected = call.feature == feature,
                onClick = { viewModel.selectCallFeature(feature) },
                label = { Text("${feature.label} (${feature.bearerCode})") },
            )
        }
    }
    val feature = call.feature
    Note(
        "Both calls count only over ${feature.bearerPlain}, call network type ${feature.bearerCode}, when the IMS data " +
            "connection and the data network agree with it at the start and the end of the call. " +
            if (feature == T2Feature.VOWIFI) {
                "Airplane mode is not needed."
            } else {
                "A call the phone places over Wi-Fi shows type 18 and counts for Wi-Fi calling, not VoLTE."
            },
    )
    if (feature in call.toggled) Note("You turned ${feature.label} on in the switch step above; these calls are kept as user_toggle.")
    call.concluded?.let { done ->
        Text(
            done.plain,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (done.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = viewModel::newT2Test) { Text("Start a new T2 test") }
        return
    }
    val assessment = call.session?.assessment
    assessment?.let { Note(it.plain) }
    Label("1. Outgoing call")
    Bullet("Tap Start, then open your phone app and dial $mailbox yourself. Listen until the mailbox answers, hang up, come back and tap Stop.")
    Button(
        onClick = { viewModel.startCallWatch(CallDirection.OUTGOING) },
        enabled = ready && call.phase == CallPhase.READY,
    ) { Text("Start: outgoing call") }
    Label("2. Incoming call")
    Bullet(
        "Tap Start, then call this phone from your second line. Answer it here yourself, check that both of you hear " +
            "each other, hang up, come back and tap Stop. A call from a mailbox or voicemail does not count.",
    )
    Button(
        onClick = { viewModel.startCallWatch(CallDirection.INCOMING) },
        enabled = ready && call.phase == CallPhase.READY,
    ) { Text("Start: incoming call") }
    if (call.session != null) OutlinedButton(onClick = viewModel::newT2Test) { Text("Start over") }
}

@Composable
private fun CallTestHistory(call: CallTestUi, viewModel: CheckViewModel) {
    val log = call.log
    if (log.tests.isEmpty() && log.t2.isEmpty() && log.toggles.isEmpty()) return
    HorizontalDivider()
    if (log.t2.isNotEmpty()) {
        Label("T2 passed on this phone")
        log.t2.asReversed().forEach { pass ->
            Field(
                "${pass.date} · ${pass.feature.label}",
                pass.plain,
                "${pass.spn ?: pass.homeMccMnc} · ${pass.model} · ${pass.romFamily} · Android ${pass.androidMajor} · ${pass.imsStack.replace('_', ' ')}",
            )
        }
    }
    if (log.tests.isNotEmpty()) {
        Label("Calls on this phone")
        log.tests.asReversed().forEach { test ->
            val title = listOfNotNull(
                test.date,
                test.feature?.let { "T2 ${test.session} ${it.label}" },
                if (test.intended == CallDirection.INCOMING) "incoming" else "${test.brand} ${test.mailbox}",
            ).joinToString(" · ")
            Field(title, test.plain, "${test.model} · ${test.romFamily} · Android ${test.androidMajor} · ${test.imsStack.replace('_', ' ')}")
        }
    }
    if (log.toggles.isNotEmpty()) {
        Label("Switch steps on this phone")
        log.toggles.asReversed().forEach { toggle ->
            Field("${toggle.date} · ${toggle.configOrigin.wire}", toggle.plain)
        }
    }
    Note(
        "Observed on this phone. T2 on one phone does not show that a feature works in general; OCD's tested layer " +
            "also needs repeats and other people's reports, and a result never changes an OCD value.",
    )
    OutlinedButton(onClick = viewModel::deleteCallTests) { Text("Delete these results") }
}

@Composable
private fun LiveWatch(live: CallWatchSummary) {
    Note(
        "Seen so far: " + live.statesSeen.joinToString(", ") { it.wire }.ifEmpty { "nothing yet" } +
            (live.callNetworkType?.let { " · call network type $it (${Radio.plain(it)})" } ?: "") +
            " · ${live.samples} readings",
    )
}

@Composable
private fun WatchResult(result: CallWatchSummary, intended: CallDirection) {
    if (!result.connected) {
        val rang = if (result.ringingSeen) " The phone rang, but the call was not answered." else ""
        Note("No call went off-hook while the app watched (${result.samples} readings).$rang Nothing to keep.")
        return
    }
    val mismatch = result.direction != CallDirection.UNKNOWN && result.direction != intended
    Field("Direction", result.direction.plain + if (mismatch) " (not the call this step asked for)" else "")
    if (intended == CallDirection.INCOMING) {
        Field(
            "The phone rang",
            if (result.ringingSeen) "yes" else "no",
            when (result.incomingIsMailbox) {
                true -> "The incoming number was a mailbox short code, so this does not count as the incoming call."
                false -> "The incoming number was not a mailbox short code. The number itself is never kept or shown."
                null -> "The registry showed no incoming number to compare."
            },
        )
    }
    Field(
        "Call network type",
        result.callNetworkType?.let { "$it, ${Radio.plain(it)}" } ?: "not reported by the phone",
        result.bearer?.plain,
        emphasize = true,
    )
    Field("Went active", if (result.wentActive) "yes" else "no")
    if (result.callsSeen > 1) Note("${result.callsSeen} calls were seen during the watch.")
    if (result.stoppedByLimit) Note("The watch stopped by itself after 15 minutes.")
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
    // Round 22: a supported summary next to off records says so, with or without this model's record.
    val hint = LabelHints.offRecords(bundle, state.resolution, device, capability)
    if (match == null) {
        Note(
            "OCD publishes no file of this phone model for this SIM (none read, older than five years, " +
                "or not bound to one model). The value above is the carrier summary.",
        )
        hint?.let { Note(it.text) }
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
    hint?.let { Note(it.text) }
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
