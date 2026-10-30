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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.open_carrier_data.check.owner.core.CheckedLine
import io.github.open_carrier_data.check.owner.core.FeatureLadder
import io.github.open_carrier_data.check.owner.core.CallsOutMode
import io.github.open_carrier_data.check.owner.core.HeaderNotes
import io.github.open_carrier_data.check.owner.core.Ladders
import io.github.open_carrier_data.check.owner.core.LeftoversLine
import io.github.open_carrier_data.check.owner.core.PointerLedger
import io.github.open_carrier_data.check.owner.core.MobileDataLine
import io.github.open_carrier_data.check.owner.core.ScheduleLine
import io.github.open_carrier_data.check.owner.core.SimView
import io.github.open_carrier_data.check.owner.core.Status
import io.github.open_carrier_data.check.owner.core.StatusKind
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
    val stopTest: () -> Unit,
    val pull: () -> Unit,
    val setSchedule: (Boolean) -> Unit,
    val checkNow: () -> Unit,
    val cleanup: () -> Unit,
    val readDetails: () -> Unit,
    val rebootProbe: () -> Unit,
    val clearStop: () -> Unit,
    val grantShizuku: () -> Unit,
    val sendReport: () -> Unit,
    val setCallsOutLive: (Boolean) -> Unit,
    val dryRunCallsOut: () -> Unit,
    val setSelfSms: (Boolean) -> Unit,
    /** One test SMS to the phone's own number now. */
    val selfSmsNow: () -> Unit = {},
    /** Round 42 item 4: the opt-in for the balance USSD read (off by default). */
    val setBalanceUssd: (Boolean) -> Unit = {},
    /** Round 42 item 5: the opt-in for reading the carrier's own plan SMS (off by default). */
    val setCarrierSms: (Boolean) -> Unit = {},
    /** Round 31 (SM9), ROM builds only: the person's opt-in for the active tests. */
    val setActiveTests: (Boolean) -> Unit = {},
    /** Round 29: stores the owner's token for automatic reports; the callback says whether it was stored. */
    val saveToken: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    val removeToken: () -> Unit = {},
)

/** The options under "When you tap Test now" in Details. Off values run at night on their own. */
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

/** Settings' card corners (LineageOS 23): large on the outside of a group, small between its items. */
private val OUTER = 20.dp
private val INNER = 4.dp

@OptIn(ExperimentalMaterial3Api::class)
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
        // Settings' page colour behind, its item colour on the cards (measured on LineageOS 23).
        val page = MaterialTheme.colorScheme.surfaceContainer
        Scaffold(
            topBar = {
                TopAppBar(
                    // Settings' positions: the back circle and the title sit further in than Material's defaults.
                    title = { Text("Open Carrier Data", Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        // Settings' own back button: the arrow in a tonal circle.
                        IconButton(
                            onClick = actions.back,
                            modifier = Modifier.padding(start = 16.dp),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = page, scrolledContainerColor = page),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = page,
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

/** Animations off in the system (Remove animations, or the animator scale at 0): the screen then moves nothing. */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
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

@Composable
private fun OwnerContent(ui: OwnerUi, options: TestOptions, setOptions: (TestOptions) -> Unit, actions: OwnerActions, reduced: Boolean) {
    // The subscription list names the SIMs as Settings does; without it, the runner's own list.
    val sims = ui.simViews ?: ui.sims?.map { SimView(it.slot, it.subId, it.identity.mccmnc, it.name) }
    val ladders = sims.orEmpty().associateWith { Ladders.of(ui.state, it, ui.calls) }
    val running = ui.stepsToGo > 0
    // The SIM of the run under way: its record has no end yet.
    val testing = ui.state.runs.lastOrNull { it.endedMs == null }?.sim?.let { key -> sims.orEmpty().firstOrNull { it.owns(key) } }
    val status = StatusSentence.of(ui.state, sims, ladders.values.flatten(), running = running, testing = testing)
    // The header's text starts where the cards' text does: one left edge for every line on the page.
    Column(Modifier.padding(horizontal = 16.dp)) { StatusHeader(ui, sims, status, options, actions) }

    // Before the first check and without a SIM there is nothing to show yet: the headline and Test now are the page.
    if (status.kind != StatusKind.NO_SIM && status.kind != StatusKind.NOT_CHECKED) {
        val two = ladders.size > 1
        for ((sim, rows) in ladders) {
            SectionTitle(if (two) "Calls and messages on ${sim.name}" else "Calls and messages")
            Group {
                rows.filterNot { it.unsupported }.forEach { ladder ->
                    item { key(sim.slot, ladder.feature) { FeatureRow(ladder, reduced) } }
                }
            }
            Ladders.unsupportedLine(rows)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
        }
        for (sim in sims.orEmpty()) {
            SectionTitle(if (two) "Mobile data on ${sim.name}" else "Mobile data")
            Group {
                item { Summary(MobileDataLine.of(ui.state, sim, ui.apnCandidates[sim.subId]), Modifier.padding(16.dp)) }
            }
        }
    }
    CarrierData(ui, actions)

    var open by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.padding(top = 24.dp))
    Group {
        item {
            SwitchRow("Test automatically", ScheduleLine.of(ui.state.scheduleOn), ui.state.scheduleOn, actions.setSchedule)
        }
        item { DetailsRow(open, reduced) { open = it } }
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
private fun StatusHeader(ui: OwnerUi, sims: List<SimView>?, status: Status, options: TestOptions, actions: OwnerActions) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    val line = CheckedLine.of(
        status, ui.state, sims, ui.stepsToGo, System.currentTimeMillis(), zone,
        time = { timeFormat.format(Date(it)) },
        date = { dateFormat.format(Date(it)) },
        scheduleOn = ui.state.scheduleOn,
        canTest = ui.canTest,
    )
    Text(
        status.headline,
        style = MaterialTheme.typography.headlineSmall,
        // Only a change or a problem takes its colour; the ladder carries the rest.
        color = if (status.tone == Tone.CHANGED || status.tone == Tone.PROBLEM) StatusColors.text(status.tone) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
    line?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    HeaderNotes.of(ui.state, ui.gate?.canWrite, ui.gate?.ownerPhone).forEach { (tone, text) ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StatusColors.text(tone), modifier = Modifier.padding(top = 8.dp))
    }
    // At most one filled button: the action the state calls for.
    val running = ui.stepsToGo > 0
    val paused = ui.state.stop != null
    val test = ui.canTest
    if (running || paused || test) {
        FlowRow(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running -> OutlinedButton(onClick = actions.stopTest) { Text("Stop test") }
                paused -> Button(onClick = actions.clearStop) { Text("Resume changes") }
                else -> if (test) FilledTonalButton(onClick = { actions.testNow(options) }) { Text("Test now") }
            }
        }
    }
}

/** A section's title above its card, in Settings' style and inset. */
/**
 * Rounds 30 and 31: the carrier data on the phone, as CarrierSettings reports it: whether the newest
 * data or the ROM's own serves, a pause after a stop event, and the tests. Round 31 removed safe
 * mode: the way back is disabling this app in Android's Settings, which the section says in plain words.
 */
@Composable
private fun CarrierData(ui: OwnerUi, actions: OwnerActions) {
    val words = io.github.open_carrier_data.check.owner.core.DatasetWords.of(ui.dataset, ui.state)
    SectionTitle(words.title)
    Group {
        for ((i, line) in words.lines.withIndex()) {
            item {
                key("dataset", i) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (i == 0) Title(line) else Summary(line)
                    }
                }
            }
        }
        if (words.available) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Summary(io.github.open_carrier_data.check.owner.core.DatasetWords.WAY_BACK)
                }
            }
        }
    }
    words.halted?.let {
        Text(
            "Tests and changes are paused: $it. Tap Resume changes when calls work",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.text(Tone.PROBLEM),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** The items of one card group, as Settings draws them. */
private class GroupScope {
    val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/** Settings' card group: items 2dp apart in the item colour, larger corners on the outside. */
@Composable
private fun Group(build: GroupScope.() -> Unit) {
    val items = GroupScope().apply(build).items
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, content ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(groupShape(i, items.size))
                    .background(MaterialTheme.colorScheme.surfaceBright),
            ) { content() }
        }
    }
}

private fun groupShape(index: Int, count: Int): Shape {
    val top: Dp = if (index == 0) OUTER else INNER
    val bottom: Dp = if (index == count - 1) OUTER else INNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun Summary(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

/** A Settings switch row: title, summary, the switch at the end; the whole row toggles. */
@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Title(title)
            summary?.let { Summary(it) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A full-width tool row: title and an optional summary; the whole row is the button. */
@Composable
private fun ToolRow(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        summary?.let { Summary(it) }
    }
}

/**
 * One feature as a Settings list item: the name, the status under it, and
 * the ladder at the end, so the ladders of all rows form one column. At large
 * font sizes the ladder goes under the status and grows with the text.
 * TalkBack reads the row as one sentence.
 */
@Composable
private fun FeatureRow(ladder: FeatureLadder, reduced: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.3f
    val scale = fontScale.coerceIn(1f, 1.6f)
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clearAndSetSemantics { contentDescription = ladder.description }
        .padding(horizontal = 16.dp, vertical = 12.dp)
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Title(ladder.feature.label)
            Summary(ladder.text)
            EvidenceLadder(ladder, reduced, scale)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(ladder.feature.label)
                Summary(ladder.text)
            }
            Spacer(Modifier.width(16.dp))
            EvidenceLadder(ladder, reduced, scale)
        }
    }
}

/**
 * The evidence ladder: one rung per kind of evidence, joined by segments,
 * filled in the status colour as far as the evidence goes and hollow after.
 * Every ladder takes the width of four rungs, so a three-rung ladder lines up
 * with the others. A feature that cannot work now gets one grey cross. It
 * fills once when a new result comes in while the screen is open, never on
 * load, and not at all with animations off.
 */
@Composable
private fun EvidenceLadder(ladder: FeatureLadder, reduced: Boolean, scale: Float = 1f) {
    val fill = StatusColors.graphic(ladder.tone)
    // The unfilled rungs and track in outline: at least 3:1 against the card.
    val hollow = MaterialTheme.colorScheme.outline
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
            drawLine(hollow, Offset(start, cy), Offset(start + gap, cy), stroke)
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

/** "Details" with its chevron: closed by default; TalkBack offers expand and collapse. */
@Composable
private fun DetailsRow(open: Boolean, reduced: Boolean, setOpen: (Boolean) -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else tween(200), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { setOpen(!open) }
            .semantics {
                if (open) {
                    collapse { setOpen(false); true }
                } else {
                    expand { setOpen(true); true }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Title("Details", Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsContent(ui: OwnerUi, options: TestOptions, setOptions: (TestOptions) -> Unit, actions: OwnerActions) {
    val gate = ui.gate
    val state = ui.state
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }

    SectionTitle("When you tap Test now")
    Group {
        item { SwitchRow("Also test switching features off", null, options.offs) { setOptions(options.copy(offs = it)) } }
        item { SwitchRow("Test missing access point names", null, options.apns) { setOptions(options.copy(apns = it)) } }
    }

    val owner = OwnerTester.build == io.github.open_carrier_data.check.owner.core.TesterBuild.OWNER
    // Round 31 (SM9): in a ROM build, the person's one opt-in for the active tests, off by default.
    if (!owner) ActiveTestsOptIn(ui, actions)

    // Round 26: calls are measured by the phone; nobody dials or listens.
    SectionTitle("Calls")
    Group {
        if (owner) item {
            SwitchRow(
                "Test calls out automatically",
                if (state.callsOutMode == CallsOutMode.LIVE) "At night, muted, to your own mailbox, when your own calls didn't show it" else "Off: only notes what it would dial",
                state.callsOutMode == CallsOutMode.LIVE,
                actions.setCallsOutLive,
            )
        }
        item {
            ToolRow(
                "Try the mailbox call without dialling",
                state.log.lastOrNull { it.kind == "calls_out" }?.text?.take(240),
                onClick = actions.dryRunCallsOut,
            )
        }
        if (ui.retestedLines.isNotEmpty() || ui.callLines.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Title("Measured calls")
                    (ui.retestedLines.map { it.replaceFirstChar(Char::uppercase) } + ui.callLines).forEach { Summary(it) }
                }
            }
        }
    }

    SectionTitle("Messages")
    Group {
        if (ui.mmsLines.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.mmsLines.forEach { Summary(it) }
                }
            }
        }
        if (owner) item {
            SwitchRow(
                "Check incoming SMS over IMS",
                "A hidden SMS to your own number about once a week. It may cost an SMS on your tariff. ${ui.selfSmsLine.orEmpty()}".trim(),
                state.selfSmsOn,
                actions.setSelfSms,
            )
        }
        if (owner || state.activeTests.on) item {
            ToolRow("Send a test SMS now", if (owner) "One hidden SMS to your own number; it may cost an SMS" else "One hidden SMS to your own number, only where it is free for your carrier", onClick = actions.selfSmsNow)
        }
    }

    // Round 42 items 4 and 5: the two account-state reads, opt-in and off by default. On the rom build
    // they also ride on the active-tests opt-in (SM9); shown there only once that is on.
    if (owner || state.activeTests.on) {
        SectionTitle("Why a feature fails (account state)")
        Group {
            item {
                SwitchRow(
                    "Read the plan balance",
                    "After a data failure, send your carrier's own free balance code (like *100#) and read the reply. It never presses a menu digit; a menu reply is left for you to read by hand. Home network only, a few times a day at most.",
                    state.balanceUssdOn,
                    actions.setBalanceUssd,
                )
            }
            item {
                SwitchRow(
                    "Read carrier status messages",
                    "Read the plan messages from your carrier's own senders (option paused, volume used up, option active again) to tell the plan's state. It keeps only the state, the network and the time, never the text or the sender, and never replies.",
                    state.carrierSmsOn,
                    actions.setCarrierSms,
                )
            }
        }
    }

    // Round 32 part A: what the operator states on its own pages; shown, never used as a setting (except an MMS end).
    if (ui.statementLines.isNotEmpty()) {
        SectionTitle("What the operator states")
        Group {
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.statementLines.forEach { Summary(it) }
                    Summary(io.github.open_carrier_data.check.core.StatementDisplay.NOTE)
                }
            }
        }
    }

    SectionTitle("Tools")
    Group {
        item { ToolRow("Check without changing anything", onClick = actions.checkNow) }
        item { ToolRow("Show Wi-Fi calling details", ui.details?.joinToString("\n"), onClick = actions.readDetails) }
        item { ToolRow("Remove leftovers", LeftoversLine.of(state.log, System.currentTimeMillis(), zone) { dateFormat.format(Date(it)) }, onClick = actions.cleanup) }
        item {
            ToolRow("Open report on GitHub", "Nothing is sent until you submit it", enabled = state.runs.any { it.features.isNotEmpty() }, onClick = actions.sendReport)
        }
    }

    // Round 29: settled results sent as report issues with the owner's own fine-grained token; without one, nothing is sent.
    // Round 31 (SM11): a ROM build sends nothing by itself and has no token; a report is one the person opens and submits.
    SectionTitle("Reports")
    Group {
        item {
            if (owner) TokenRow(ui, actions) else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Title("Nothing is sent by itself")
                Summary("Results stay on this phone. To share one, tap Open report on GitHub, read it and submit it yourself")
            }
        }
    }

    Spacer(Modifier.padding(top = 24.dp))
    Group {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The pinned main commit's date (round 29), else the bundled snapshot's month.
                val tag = state.pin?.commitDate?.take(10) ?: ui.bundledTag
                Title(if (tag != null) "Carrier data $tag" else "Carrier data", Modifier.weight(1f))
                TextButton(onClick = actions.pull) { Text("Check for update") }
            }
        }
        // The private pointer ledger: how often the entries tested here passed, by what pointed at them.
        PointerLedger.summary(state.ledger)?.let { line ->
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                    Title("Tested on this phone")
                    Summary(line)
                }
            }
        }
        // Others' reports (OCD's observed/summary.json): one line per test they move earlier; they never change a setting.
        if (ui.reportLines.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Title("Reported by others")
                    ui.reportLines.forEach { Summary(it) }
                }
            }
        }
    }

    if (gate != null && !gate.canWrite) {
        Spacer(Modifier.padding(top = 24.dp))
        Group {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Title("No system access: ${gate.plainReason ?: "unknown reason"}")
                    if (!gate.privilegedGranted && gate.shizukuRunning && !gate.shizukuPermitted) {
                        Button(onClick = actions.grantShizuku, modifier = Modifier.padding(top = 12.dp)) { Text("Grant Shizuku access") }
                    }
                }
            }
        }
    }

    if (!state.rebootCleared) {
        SectionTitle("Restart check")
        Group {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Summary("Confirms a restart clears test settings")
                    if (state.rebootProbeAtMs != null) {
                        Text("Now restart your phone", style = MaterialTheme.typography.bodyLarge, color = StatusColors.text(Tone.CHANGED), modifier = Modifier.padding(top = 8.dp))
                    }
                    OutlinedButton(
                        onClick = actions.rebootProbe,
                        enabled = gate?.canWrite == true && ui.sims?.isNotEmpty() == true && state.rebootProbeAtMs == null,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Start restart check") }
                }
            }
        }
    }

    SectionTitle("Safety")
    Group {
        item {
            Summary(
                "Never touches emergency calling. Nothing changes during a call, while roaming, or within 10 minutes of an emergency call. " +
                    "The only number it ever calls is your own mailbox, muted, at night. " +
                    io.github.open_carrier_data.check.owner.core.DatasetWords.WAY_BACK,
                Modifier.padding(16.dp),
            )
        }
    }

    SectionTitle("Log")
    Group { item { Log(state) } }
}

/**
 * Round 31 (SM9), ROM builds only: the person's opt-in for the active tests (dialled test calls,
 * test SMS, setting trials, and the emergency data test where the ROM builder turned it on). Off by
 * default; the words say what the app then does before the switch goes on.
 */
@Composable
private fun ActiveTestsOptIn(ui: OwnerUi, actions: OwnerActions) {
    val on = ui.state.activeTests.on
    var confirm by remember { mutableStateOf(false) }
    SectionTitle("Tests")
    Group {
        item {
            SwitchRow(io.github.open_carrier_data.check.owner.core.OptInWords.TITLE, if (on) io.github.open_carrier_data.check.owner.core.OptInWords.ON else io.github.open_carrier_data.check.owner.core.OptInWords.OFF, on) { want ->
                if (want) confirm = true else actions.setActiveTests(false)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Turn on test calls?") },
            text = { Text(io.github.open_carrier_data.check.owner.core.OptInWords.explain(ui.dataset?.emergencyTestOn == true)) },
            confirmButton = { TextButton(onClick = { confirm = false; actions.setActiveTests(true) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Automatic reports (OCD round 29): without a token, the field to enter one; with one, what was
 * sent last and "Remove token". The typed text stays in memory only (never saved state, never
 * shown), and the field clears once the token is stored.
 */
@Composable
private fun TokenRow(ui: OwnerUi, actions: OwnerActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title("Send reports automatically")
        if (ui.tokenSet) {
            Summary(listOfNotNull("On: settled results go to Open Carrier Data as issues under your GitHub account", ui.state.autoLine).joinToString(". "))
            OutlinedButton(onClick = actions.removeToken) { Text("Remove token") }
        } else {
            Summary(
                "Off: reports open in the browser for you to submit. To send settled results automatically, enter a fine-grained " +
                    "GitHub token with only Issues read and write on open-carrier-data/open-carrier-data",
            )
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("GitHub token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { actions.saveToken(text) { stored -> if (stored) text = "" } }, enabled = text.isNotBlank()) { Text("Save token") }
        }
    }
}

/** The newest 20 entries inline, the rest behind "Show all"; selectable, so a line can be copied. */
@Composable
private fun Log(state: io.github.open_carrier_data.check.owner.core.OwnerState) {
    var all by rememberSaveable { mutableStateOf(false) }
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val newest = state.log.reversed()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (newest.size > LOG_SHOWN && !all) 4.dp else 16.dp)) {
        if (newest.isEmpty()) {
            Summary("Nothing logged yet")
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (all) newest else newest.take(LOG_SHOWN)).forEach {
                        Text(
                            "${time.format(Date(it.atMs))} ${it.kind}: ${it.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (newest.size > LOG_SHOWN && !all) {
                TextButton(onClick = { all = true }) { Text("Show all") }
            }
        }
    }
}

private const val LOG_SHOWN = 20

private val TestOptionsSaver = androidx.compose.runtime.saveable.listSaver<TestOptions, Boolean>(
    save = { listOf(it.offs, it.voice, it.apns) },
    restore = { TestOptions(it[0], it[1], it[2]) },
)
