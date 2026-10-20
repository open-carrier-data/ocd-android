package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.open_carrier_data.check.owner.core.CheckedLine
import io.github.open_carrier_data.check.owner.core.FeatureLadder
import io.github.open_carrier_data.check.owner.core.Hold
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.MobileDataLine
import io.github.open_carrier_data.check.owner.core.Rung
import io.github.open_carrier_data.check.owner.core.ScheduleLine
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.StatusSentence
import io.github.open_carrier_data.check.owner.core.Tone
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/** What the screen's buttons do; the activity wires them to [OwnerViewModel]. */
class OwnerActions(
    val back: () -> Unit,
    val testNow: (TestOptions) -> Unit,
    val revertAll: () -> Unit,
    val pull: () -> Unit,
    val setSchedule: (Boolean) -> Unit,
    val checkNow: () -> Unit,
    val cleanup: () -> Unit,
    val readDetails: () -> Unit,
    val rebootProbe: () -> Unit,
    val clearStop: () -> Unit,
    val grantShizuku: () -> Unit,
    val sendReport: () -> Unit,
    val openCheck: () -> Unit,
)

/** The test options in Details; "Test now" and "Test again" both use them. Off values run at night on their own. */
data class TestOptions(val offs: Boolean = false, val voice: Boolean = false, val apns: Boolean = true)

/**
 * The four status colours (the design of 2026-10-20): each means one thing
 * only. The light variants are darker so text in them reaches WCAG AA
 * (4.5:1) on Material You's light surfaces; [graphic] is for the ladder's
 * marks (3:1 is enough for them, so the light ones stay brighter).
 */
private object StatusColors {
    private val worksLight = Color(0xFF227659)
    private val changedLight = Color(0xFF896119)
    private val problemLight = Color(0xFFC42F2A)
    private val notPossibleLight = Color(0xFF5F6975)
    private val worksMarkLight = Color(0xFF2B9670)
    private val changedMarkLight = Color(0xFFAE7B20)
    private val problemMarkLight = Color(0xFFD9534F)
    private val notPossibleMarkLight = Color(0xFF7B8694)
    private val worksDark = Color(0xFF2FA37A)
    private val changedDark = Color(0xFFD89A2B)
    private val problemDark = Color(0xFFDF6F6B)
    private val notPossibleDark = Color(0xFF8A94A0)

    @Composable
    fun text(tone: Tone): Color {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return when (tone) {
            Tone.WORKS -> if (dark) worksDark else worksLight
            Tone.CHANGED -> if (dark) changedDark else changedLight
            Tone.PROBLEM -> if (dark) problemDark else problemLight
            Tone.NOT_POSSIBLE -> if (dark) notPossibleDark else notPossibleLight
            Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    @Composable
    fun graphic(tone: Tone): Color {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return when (tone) {
            Tone.WORKS -> if (dark) worksDark else worksMarkLight
            Tone.CHANGED -> if (dark) changedDark else changedMarkLight
            Tone.PROBLEM -> if (dark) problemDark else problemMarkLight
            Tone.NOT_POSSIBLE -> if (dark) notPossibleDark else notPossibleMarkLight
            Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

private val GUTTER = 16.dp
private val DOT = 12.dp
private val SEGMENT = 14.dp
private val LADDER_WIDTH = DOT * 4 + SEGMENT * 3

/** Animations off in the system (Remove animations, or the animator scale at 0): the screen then moves nothing. */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OwnerScreen(ui: OwnerUi, actions: OwnerActions) {
    val snackbar = remember { SnackbarHostState() }
    // Each message once: a recreated screen (a theme or font change) does not show the last one again.
    var shownId by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(ui.messageId) {
        if (ui.messageId > shownId) {
            shownId = ui.messageId
            ui.message?.let { snackbar.showSnackbar(it) }
        }
    }
    var options by rememberSaveable(stateSaver = TestOptionsSaver) { mutableStateOf(TestOptions()) }
    val reduced = reducedMotion()
    val context = LocalContext.current
    val base = MaterialTheme.typography
    val typography = remember(base) { deviceTypography(context, base) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography, shapes = MaterialTheme.shapes) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Open Carrier Data") },
                    navigationIcon = {
                        // Settings' own back button: the arrow in a tonal circle.
                        IconButton(
                            onClick = actions.back,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = GUTTER)
                    .padding(top = 8.dp, bottom = 32.dp),
            ) {
                // Nothing until the first read, so no ladder fills on load.
                if (ui.loaded) OwnerContent(ui, options, { options = it }, actions, reduced)
            }
        }
    }
}

/**
 * The phone's own UI font, as Settings uses it: Android's DeviceDefault text
 * styles name it in config_bodyFontFamily and config_headlineFontFamily (on
 * the S20's LineageOS build a font overlay sets both to Google Sans Flex).
 * Without them, or when the family is not on the phone, the platform
 * default stays.
 */
@SuppressLint("DiscouragedApi") // The two config strings are framework-internal; getIdentifier is the only way to read them.
private fun deviceTypography(context: Context, base: Typography): Typography {
    fun family(config: String): FontFamily? {
        val id = context.resources.getIdentifier(config, "string", "android")
        val name = if (id == 0) null else runCatching { context.resources.getString(id) }.getOrNull()
        if (name.isNullOrBlank()) return null
        val device = DeviceFontFamilyName(name)
        return FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { Font(device, it) })
    }
    val body = family("config_bodyFontFamily") ?: return base
    val headline = family("config_headlineFontFamily") ?: body
    fun TextStyle.of(family: FontFamily) = copy(fontFamily = family)
    return base.copy(
        displayLarge = base.displayLarge.of(headline),
        displayMedium = base.displayMedium.of(headline),
        displaySmall = base.displaySmall.of(headline),
        headlineLarge = base.headlineLarge.of(headline),
        headlineMedium = base.headlineMedium.of(headline),
        headlineSmall = base.headlineSmall.of(headline),
        titleLarge = base.titleLarge.of(headline),
        titleMedium = base.titleMedium.of(headline),
        titleSmall = base.titleSmall.of(headline),
        bodyLarge = base.bodyLarge.of(body),
        bodyMedium = base.bodyMedium.of(body),
        bodySmall = base.bodySmall.of(body),
        labelLarge = base.labelLarge.of(body),
        labelMedium = base.labelMedium.of(body),
        labelSmall = base.labelSmall.of(body),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnerContent(ui: OwnerUi, options: TestOptions, setOptions: (TestOptions) -> Unit, actions: OwnerActions, reduced: Boolean) {
    // The subscription list names the SIMs as Settings does; without it, the runner's own list.
    val sims = ui.simViews ?: ui.sims?.map { SimView(it.slot, it.subId, it.identity.mccmnc, it.name) }
    val ladders = sims.orEmpty().associateWith { Ladders.of(ui.state, it, ui.calls) }
    val status = StatusSentence.of(ui.state, sims, ladders.values.flatten(), running = ui.stepsToGo > 0)
    StatusHeader(ui, sims, status.headline, options, actions)

    for ((sim, rows) in ladders) {
        Section(if (ladders.size > 1) "Calls and messages on ${sim.name}" else "Calls and messages") {
            Text(
                "Each row shows how far it got: ${Rung.entries.joinToString(", ") { it.plain.lowercase() }}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            rows.forEach { ladder ->
                key(sim.slot, ladder.feature) { FeatureRow(ladder, reduced) }
            }
        }
    }
    for (sim in sims.orEmpty()) {
        Section(if (sims.orEmpty().size > 1) "Mobile data on ${sim.name}" else "Mobile data") {
            Text(MobileDataLine.of(ui.state, sim, ui.apnCandidates[sim.subId]), style = MaterialTheme.typography.bodyLarge)
        }
    }

    Spacer(Modifier.padding(top = 20.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = ui.state.scheduleOn, role = Role.Switch, onValueChange = actions.setSchedule)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionTitle("Automatic checks")
            Text(
                ScheduleLine.of(ui.state.scheduleOn, ui.scheduleRegistered),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = ui.state.scheduleOn, onCheckedChange = null)
    }

    Section("Carrier data") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val release = ui.state.release
            Text(
                release?.tag?.removePrefix("data-")?.let { "Open Carrier Data $it" } ?: "Open Carrier Data 2026.10, built in",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(onClick = actions.pull) { Text("Check for update") }
        }
    }

    DetailsSection(ui, options, setOptions, actions, reduced)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHeader(ui: OwnerUi, sims: List<SimView>?, headline: String, options: TestOptions, actions: OwnerActions) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    val checked = CheckedLine.of(
        ui.state, sims, ui.stepsToGo, System.currentTimeMillis(), zone,
        time = { timeFormat.format(Date(it)) },
        date = { dateFormat.format(Date(it)) },
    )
    Text(
        headline,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
    Text(
        checked,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    val gate = ui.gate
    val notes = buildList {
        if (ui.state.stop != null) {
            add(Tone.PROBLEM to "Open Carrier Data stopped changing settings because something didn't go back as expected. Read the log in Details, then allow changes again.")
        }
        if (gate != null && !gate.canWrite) {
            add(
                Tone.NEUTRAL to if (!gate.ownerPhone) {
                    "This phone can only be checked here; Open Carrier Data changes settings only on the owner's phone."
                } else {
                    "Tests can't run right now: Open Carrier Data has no system access. Details says why."
                },
            )
        }
        if (!ui.state.rebootCleared) add(Tone.NEUTRAL to "Changes wait for the first-use check in Details.")
    }
    notes.forEach { (tone, text) ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StatusColors.text(tone), modifier = Modifier.padding(top = 8.dp))
    }
    FlowRow(
        Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { actions.testNow(options) }, enabled = ui.canTest) { Text("Test now") }
        if (ui.state.anyLive) OutlinedButton(onClick = actions.revertAll) { Text("Revert all") }
        if (ui.state.stop != null) OutlinedButton(onClick = actions.clearStop) { Text("Allow changes again") }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

/** A Settings-like open section: a plain heading and its content, set apart by space only. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(title, Modifier.padding(bottom = 4.dp))
        content()
    }
}

/**
 * One feature: its name, then the ladder with the status beside it (under
 * it at large font sizes, where the ladder also grows). TalkBack reads the
 * row as one sentence.
 */
@Composable
private fun FeatureRow(ladder: FeatureLadder, reduced: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.3f
    val scale = fontScale.coerceIn(1f, 1.6f)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = ladder.description }
            .padding(vertical = 8.dp),
    ) {
        Text(ladder.feature.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        val status: @Composable () -> Unit = {
            Text(ladder.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stacked) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EvidenceLadder(ladder, reduced, scale)
                status()
            }
        } else {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EvidenceLadder(ladder, reduced, scale)
                Spacer(Modifier.width(12.dp))
                status()
            }
        }
    }
}

/**
 * The evidence ladder: one rung per kind of evidence, joined by segments,
 * filled in the status colour as far as the evidence goes and hollow after.
 * A feature that cannot work gets one grey cross. It fills once when a new
 * result comes in while the screen is open, never on load, and not at all
 * with animations off.
 */
@Composable
private fun EvidenceLadder(ladder: FeatureLadder, reduced: Boolean, scale: Float = 1f) {
    val fill = StatusColors.graphic(ladder.tone)
    val hollow = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.outlineVariant
    val target = ladder.reached.toFloat()
    val progress = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (progress.value != target) {
            val steps = abs(progress.value - target)
            if (reduced) progress.snapTo(target) else progress.animateTo(target, tween(durationMillis = (220 * steps).toInt(), easing = FastOutSlowInEasing))
        }
    }
    Canvas(Modifier.size(LADDER_WIDTH * scale, 24.dp * scale)) {
        val dot = DOT.toPx() * scale
        val gap = SEGMENT.toPx() * scale
        val stroke = 2.dp.toPx() * scale
        val cy = size.height / 2
        fun cx(i: Int) = i * (dot + gap) + dot / 2
        if (ladder.notPossible) {
            val r = dot / 2 - 1.dp.toPx() * scale
            drawLine(fill, Offset(cx(0) - r, cy - r), Offset(cx(0) + r, cy + r), stroke * 1.25f, StrokeCap.Round)
            drawLine(fill, Offset(cx(0) - r, cy + r), Offset(cx(0) + r, cy - r), stroke * 1.25f, StrokeCap.Round)
            return@Canvas
        }
        val p = progress.value
        val n = ladder.rungs.size
        for (i in 0 until n - 1) {
            val start = cx(i) + dot / 2
            drawLine(track, Offset(start, cy), Offset(start + gap, cy), stroke)
            val f = (p - (i + 1)).coerceIn(0f, 1f)
            if (f > 0f) drawLine(fill, Offset(start, cy), Offset(start + gap * f, cy), stroke)
        }
        for (i in 0 until n) {
            val center = Offset(cx(i), cy)
            drawCircle(hollow, radius = dot / 2 - stroke / 2, center = center, style = Stroke(stroke))
            val f = (p - i).coerceIn(0f, 1f)
            if (f > 0f) drawCircle(fill.copy(alpha = fill.alpha * f), radius = dot / 2, center = center)
        }
    }
}

/** Details: closed by default; the chevron turns once on each toggle. */
@Composable
private fun DetailsSection(ui: OwnerUi, options: TestOptions, setOptions: (TestOptions) -> Unit, actions: OwnerActions, reduced: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else tween(200), label = "chevron")
    Spacer(Modifier.padding(top = 20.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = if (open) "Hide details" else "Show details", role = Role.Button) { open = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionTitle("Details")
            Text(
                "Access, tests, report and log",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn))
    }
    AnimatedVisibility(
        visible = open,
        enter = if (reduced) EnterTransition.None else expandVertically(tween(220)) + fadeIn(tween(220)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200)) + fadeOut(tween(160)),
    ) {
        Column(Modifier.fillMaxWidth()) { DetailsContent(ui, options, setOptions, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsContent(ui: OwnerUi, options: TestOptions, setOptions: (TestOptions) -> Unit, actions: OwnerActions) {
    val gate = ui.gate
    val state = ui.state
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    DetailBlock("Access") {
        if (gate == null) {
            Line("Reading…")
        } else {
            Line("Route: ${gate.route?.plain ?: "none"}")
            Line(
                "Privileged system app: " + when {
                    !gate.privilegedGranted -> "no"
                    gate.overrideApi == true -> "yes, permissions granted, overrideConfig reachable"
                    else -> "permissions granted, but overrideConfig is not reachable"
                },
            )
            // Shizuku is only the fallback when the privileged permissions are missing.
            if (!gate.privilegedGranted) {
                Line("Shizuku: ${if (!gate.shizukuRunning) "not running" else "running as ${if (gate.shizukuRoot) "root" else "shell"}${if (gate.shizukuPermitted) "" else ", not granted"}"}")
            }
            Line("Build type: ${gate.buildType}")
            Line("Writes: ${if (gate.canWrite) "allowed" else "refused: ${gate.reason}"}")
        }
        val sims = ui.sims
        Line(
            "SIMs: " + when {
                sims == null -> "reading…"
                sims.isEmpty() -> "none, nothing to test"
                else -> sims.joinToString { "slot ${it.slot + 1} ${it.name} (${it.identity.mccmnc})" }
            },
        )
        Line("Data: ${state.release.line()}")
        Line("Background: ${ui.weekly}")
        Line("Last reconcile: ${state.lastCleanup ?: "not run yet"}")
        state.stop?.let { Line("Stopped: $it", StatusColors.text(Tone.PROBLEM)) }
        Buttons {
            if (gate != null && !gate.privilegedGranted && gate.shizukuRunning && !gate.shizukuPermitted) {
                OutlinedButton(onClick = actions.grantShizuku) { Text("Grant Shizuku access") }
            }
            if (state.stop != null) OutlinedButton(onClick = actions.clearStop) { Text("Allow changes again") }
        }
    }
    DetailBlock("Safety") {
        Line(
            "Tests and applies carrier settings on this phone. Never an emergency, e911, ECBM, WPS, TTY, RTT or iwlan key; never an " +
                "emergency call; nothing changes in a call, in emergency callback mode, within 10 minutes of an emergency call, " +
                "while roaming, or when the state cannot be read. Overrides are never persistent.",
        )
    }
    if (!state.rebootCleared) {
        DetailBlock("First use (round 25)") {
            Line(
                "1. Only after the S20 build's boot check, never together with another build change. " +
                    "2. The first apply on the cable, with a lab --read before and after. " +
                    "3. One override left live through a reboot must be gone afterwards; until then nothing is kept, " +
                    "no off is tested and the night run changes nothing.",
            )
            Line("Check 3: ${if (state.rebootProbeAtMs != null) "override live; reboot now" else "not done"}")
            Buttons {
                OutlinedButton(onClick = actions.rebootProbe, enabled = gate?.canWrite == true && ui.sims?.isNotEmpty() == true && state.rebootProbeAtMs == null) {
                    Text("Start check 3")
                }
            }
        }
    }
    DetailBlock("Test options") {
        Line("Run: ${if (ui.stepsToGo > 0) "${ui.stepsToGo} step(s) to go" else "idle"}")
        CheckRow("Test the off values too (otherwise the night tries them, one at a time)", options.offs) { setOptions(options.copy(offs = it)) }
        CheckRow("Voice check: I place a call myself while a voice feature is off (2 minutes)", options.voice) { setOptions(options.copy(voice = it)) }
        CheckRow("Test Open Carrier Data's internet APN rows the phone lacks", options.apns) { setOptions(options.copy(apns = it)) }
        Buttons {
            OutlinedButton(onClick = { actions.testNow(options) }, enabled = ui.canTest && state.runs.isNotEmpty()) { Text("Test again") }
        }
    }
    DetailBlock("Last test") {
        state.lastResult?.let { Line("${dateTime.format(Date(it.atMs))}: ${it.text}") }
        val run = state.runs.lastOrNull()
        if (run == null) {
            Line("No test yet.")
        } else {
            Line("${if (run.background) "Night run" else "Run"}: ${run.simName ?: "-"} ${run.sim?.mccmnc ?: ""}, ${run.releaseTag ?: ""}${run.note?.let { ", $it" } ?: ""}")
            run.plan?.let { Line("Due: $it") }
            run.features.forEach { f ->
                Line(
                    "${f.feature.label}: phone ${f.baseline.onOff()}, OCD ${f.ocd.value.onOff()}, best ${f.best.onOff()}" +
                        (if (f.applied) ", applied" else "") + (f.refused?.let { ", refused: $it" } ?: "") +
                        (f.method?.let { ", measured by $it${if (f.freshRegister == true) " (fresh REGISTER over LTE)" else ""}" } ?: "") +
                        (if (f.restored == false) ", baseline NOT back in time" else "") +
                        (f.bestWhy?.let { " ($it)" } ?: ""),
                )
            }
            run.apns.forEach { Line("APN ${it.label}: ${it.status}${it.dataWorks?.let { w -> if (w) ", data works" else ", no data" } ?: ""}") }
        }
        val kept = state.overrides.filter { it.hold == Hold.KEPT }.map { "slot ${it.slot + 1} ${it.key}=${it.value}, kept for ${it.sim.label}${it.why?.let { w -> ": $w" } ?: ""}" } +
            state.apns.filter { it.hold == Hold.KEPT }.map { "APN ${it.label} kept as the preferred APN" } +
            state.overrides.filter { it.hold == Hold.TRIAL }.map { "slot ${it.slot + 1} ${it.key}=${it.value}, under test" } +
            state.apns.filter { it.hold == Hold.TRIAL }.map { "APN ${it.label} under test" }
        Line("Live values: ${if (kept.isEmpty()) "none, the phone runs its own carrier settings" else kept.joinToString("; ")}")
    }
    DetailBlock("Wi-Fi calling details") {
        Line("From the IWLAN and QNS dumps; the device log is never read.")
        ui.details?.forEach { Line(it) }
        Buttons { OutlinedButton(onClick = actions.readDetails) { Text("Read Wi-Fi calling details") } }
    }
    DetailBlock("Report") {
        Line("Opens the last test as a prefilled report on GitHub. You read it and send it yourself; the app sends nothing.")
        Buttons { OutlinedButton(onClick = actions.sendReport, enabled = state.runs.any { it.features.isNotEmpty() }) { Text("Send report") } }
    }
    DetailBlock("Tools") {
        Buttons {
            OutlinedButton(onClick = actions.checkNow) { Text("Check without changing anything") }
            OutlinedButton(onClick = actions.cleanup) { Text("Check for leftovers") }
            OutlinedButton(onClick = actions.openCheck) { Text("Open the read-only check") }
        }
    }
    DetailBlock("Log") {
        val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        val entries = state.log.takeLast(100).reversed()
        if (entries.isEmpty()) {
            Line("Nothing logged yet.")
        } else {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEach {
                        Text(
                            "${time.format(Date(it.atMs))} ${it.kind}: ${it.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Line(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private val TestOptionsSaver = androidx.compose.runtime.saveable.listSaver<TestOptions, Boolean>(
    save = { listOf(it.offs, it.voice, it.apns) },
    restore = { TestOptions(it[0], it[1], it[2]) },
)

private fun Boolean?.onOff(): String = when (this) {
    true -> "on"
    false -> "off"
    null -> "-"
}
