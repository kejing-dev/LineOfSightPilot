package io.embodit.dronesight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.dronesight.R
import io.embodit.dronesight.domain.Diff
import io.embodit.dronesight.domain.FlightMode
import io.embodit.dronesight.domain.Grade
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.Orientation
import io.embodit.dronesight.domain.Task
import io.embodit.dronesight.domain.Tolerance
import io.embodit.dronesight.domain.WindLevel
import io.embodit.dronesight.ui.DsController
import io.embodit.dronesight.ui.DsUiState
import io.embodit.dronesight.ui.Hint
import io.embodit.dronesight.ui.HintMode
import io.embodit.dronesight.ui.Lang
import io.embodit.dronesight.ui.Screen
import io.embodit.dronesight.ui.Ui
import io.embodit.dronesight.ui.VStickMode
import io.embodit.dronesight.ui.debug.probe
import io.embodit.dronesight.ui.theme.DsColors
import io.embodit.dronesight.ui.theme.DsType
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PILL_PAD = 12

/** A small status pill: opaque dark slate with light text (13:1), readable on any glass / room behind it. */
@Composable
private fun Pill(text: String, bg: Color = DsColors.TagBg, ink: Color = DsColors.TagInk, name: String = "pill", modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = PILL_PAD.dp, vertical = 4.dp).probe(name), contentAlignment = Alignment.Center) {
        Text(text = text, style = DsType.label, color = ink, maxLines = 1, onTextLayout = fits("pill/$name"))
    }
}

enum class KeyKind { PRIMARY, DANGER, NORMAL, ON }

/**
 * A text key with an optional icon. Primary (interaction yellow), danger (error red) and "on" (passable green) are
 * filled with the dark ink (12 / 6.6 / 9:1); normal keys use the system fill with labelPrimary.
 */
@Composable
private fun Key(text: String, name: String, icon: Int? = null, kind: KeyKind = KeyKind.NORMAL, height: Dp = 48.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val container = when (kind) {
        KeyKind.PRIMARY -> DsColors.Interaction
        KeyKind.DANGER -> DsColors.DangerFill
        KeyKind.ON -> DsColors.Passable
        KeyKind.NORMAL -> PicoTheme.colorScheme.fillTertiary
    }
    val ink = when (kind) {
        KeyKind.NORMAL -> PicoTheme.colorScheme.labelPrimary
        KeyKind.DANGER -> DsColors.OnError
        else -> DsColors.OnFill
    }
    // danger keys: bold strokes as well as the darker ink, so the label stays >= 4.5:1 even where it renders thin
    // (20 sp bold on the lighter DangerFill: at 16 sp the strokes are 1-2 px in a 2160-px capture and anti-aliasing pulled
    // them to ~4.2:1 on the old red)
    val style = if (kind == KeyKind.DANGER) DsType.button.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else DsType.button
    Button(
        onClick = onClick,
        modifier = modifier.probe(name).height(height),
        size = ButtonDefaults.Regular.copy(height = height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, maxLines = 1, color = ink, style = style, overflow = TextOverflow.Ellipsis, onTextLayout = fits("key/$name"))
    }
}

/** A key that does nothing right now (S00, S07, S09, S13): a plain dark pill with readable text instead of a faded button. */
@Composable
private fun IdleKey(text: String, name: String, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(modifier.height(height).clip(CircleShape).background(DsColors.TagBg).probe(name), contentAlignment = Alignment.Center) {
        Text(text = text, maxLines = 1, color = DsColors.TagInk, style = DsType.button, onTextLayout = fits("key/$name"))
    }
}

@Composable
private fun IconKey(icon: Int, description: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        size = IconButtonDefaults.iconButtonSize(width = 56.dp, height = 48.dp),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = DsColors.Interaction,
        selectedItemContentColor = DsColors.OnFill,
    )

/**
 * Segments at the Regular size (48 dp, items 40 = 48 - 2 x 4, theremin lesson); the selected item is interaction yellow
 * + ink. [itemWidth] is the minimum: an item whose label needs more (zh「只在前两课」, v0.1.1 OVERFLOW seg/set_hints) grows
 * to the widest label + 26 dp (all items equal: the control shares its width equally), so no label is cut.
 */
@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, name: String, itemWidth: Int, enabled: Boolean = true, onPick: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = DsType.label
    val widths =
        remember(options.map { it.second }, itemWidth, style, density) {
            // SegmentControl shares its width equally between the items (BOUNDS: three 80-dp items for 76 / 76 / 98), and an
            // item keeps ~12 dp padding a side: every item takes the widest label (+10 %: the item's own text measures a
            // little wider than the measurer, OVERFLOW 只在前两课 at exactly label + 26) + 26 dp
            val w = options.maxOf { (_, label) -> maxOf(itemWidth.dp, with(density) { (measurer.measure(label, style, maxLines = 1).size.width * 1.1f).toDp() } + 26.dp) }
            options.map { w }
        }
    SegmentControl(modifier = Modifier.width(widths.fold(8.dp) { a, b -> a + b }).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        options.forEachIndexed { i, (v, label) ->
            val sel = v == selected
            SegmentItem(
                selected = sel,
                onClick = { if (enabled) onPick(v) },
                modifier = Modifier.width(widths[i]).height(40.dp).probe("${name}_$v"),
                textStyle = DsType.label,
                colors = segColors(),
                title = { Text(text = label, maxLines = 1, color = if (sel) DsColors.OnFill else PicoTheme.colorScheme.labelPrimary, style = DsType.label, onTextLayout = fits("seg/${name}_$v")) },
            )
        }
    }
}

// ================================================================================================= copy helpers

fun lessonName(id: LessonId) = when (id) {
    LessonId.L1 -> R.string.l1_name; LessonId.L2 -> R.string.l2_name; LessonId.L3 -> R.string.l3_name; LessonId.L4 -> R.string.l4_name
    LessonId.L5 -> R.string.l5_name; LessonId.L6 -> R.string.l6_name; LessonId.L7 -> R.string.l7_name; LessonId.F1 -> R.string.f1_name; LessonId.F2 -> R.string.f2_name
}

private fun lessonTag(id: LessonId) = when (id) {
    LessonId.L1 -> R.string.l1_tag; LessonId.L2 -> R.string.l2_tag; LessonId.L3 -> R.string.l3_tag; LessonId.L4 -> R.string.l4_tag
    LessonId.L5 -> R.string.l5_tag; LessonId.L6 -> R.string.l6_tag; LessonId.L7 -> R.string.l7_tag; LessonId.F1 -> R.string.f1_tag; LessonId.F2 -> R.string.f2_tag
}

private fun lessonIcon(id: LessonId) = when (id) {
    LessonId.L1 -> R.drawable.ic_flight_takeoff; LessonId.L2 -> R.drawable.ic_swap_horiz; LessonId.L3 -> R.drawable.ic_rotate_360; LessonId.L4 -> R.drawable.ic_all_inclusive
    LessonId.L5 -> R.drawable.ic_flight_land; LessonId.L6 -> R.drawable.ic_format_list_numbered; LessonId.L7 -> R.drawable.ic_air; LessonId.F1 -> R.drawable.ic_gamepad; LessonId.F2 -> R.drawable.ic_trip_origin
}

fun taskName(t: Task) = when (t) {
    Task.HOVER_TAIL, Task.HOVER_ANY -> R.string.task_tail; Task.HOVER_NOSE -> R.string.task_nose; Task.SPIN -> R.string.task_spin
    Task.EIGHT -> R.string.task_eight; Task.LAND -> R.string.task_land; Task.FREE -> R.string.f1_name; Task.WAYPOINTS -> R.string.f2_name
}

private fun hintText(h: Hint): Int? = when (h) {
    Hint.NONE -> null
    Hint.STARTING -> R.string.starting
    Hint.S02 -> R.string.h_S02
    Hint.S02_TIMEOUT -> R.string.h_S02timeout
    Hint.S03 -> R.string.h_S03
    Hint.S03_NO_CONTROLLER -> R.string.h_S03noController
    Hint.S05_L1 -> R.string.h_S05_L1
    Hint.S05_L2 -> R.string.h_S05_L2
    Hint.S05_L3 -> R.string.h_S05_L3
    Hint.S05_L4 -> R.string.h_S05_L4
    Hint.S05_L5 -> R.string.h_S05_L5
    Hint.S05_L6 -> R.string.h_S05_L6
    Hint.S05_L7 -> R.string.h_S05_L7
    Hint.S05_F1 -> R.string.h_S05_F1
    Hint.S05_F2 -> R.string.h_S05_F2
    Hint.NOSE_IN -> R.string.h_noseIn
    Hint.HOVER_IN -> R.string.h_hoverIn
    Hint.HOVER_OUT -> R.string.h_hoverOut
    Hint.DONE -> R.string.h_done
    Hint.L4 -> R.string.h_L4
    Hint.L5 -> R.string.h_L5
    Hint.OUT -> R.string.h_S06a
    Hint.LAND_SOFT -> R.string.h_S07_soft
    Hint.LAND_HEAVY -> R.string.h_S07_heavy
    Hint.CRASH -> R.string.h_S09
    Hint.PAUSED -> R.string.h_S15
    Hint.WAYPOINT -> R.string.h_S10
    Hint.ATTI -> R.string.h_atti
    Hint.NEAR_YOU -> R.string.h_nearYou
    Hint.CHAIN_NEXT -> R.string.h_chainNext
    Hint.NOSE_HINT -> R.string.h_noseHint
}

fun clock(s: Float): String {
    val t = s.roundToInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

@Composable
private fun diffText(d: Diff): String = when (d) {
    is Diff.High -> rs(R.string.d_high, d.m)
    is Diff.Low -> rs(R.string.d_low, d.m)
    is Diff.OutCount -> rs(R.string.d_outCount, d.n)
    is Diff.SpinShort -> rs(R.string.d_spinShort, d.deg)
    is Diff.GateMissed -> rs(R.string.d_gateMissed, d.n)
    is Diff.LandOff -> rs(R.string.d_landOff, d.m)
    Diff.LandHard -> rs(R.string.d_landHard)
    Diff.LandHeavy -> rs(R.string.d_landHeavy)
    is Diff.Timeout -> rs(R.string.d_timeout, clock(d.s.toFloat()))
    Diff.NoseOff -> rs(R.string.d_noseOff)
    is Diff.HoverSummary -> rs(R.string.d_hoverSum, d.s, d.maxOffM)
    is Diff.SpinSummary -> rs(R.string.d_spinSum, d.deg)
    is Diff.LandSummary -> rs(R.string.d_landSum, d.offM)
    is Diff.Time -> rs(R.string.d_time, clock(d.s))
}

// ================================================================================================= control panel

/**
 * 飞行 page of the main window (v0.1.2: the system window brings the glass; profile D8 / D21): welcome, the flight
 * page (S00-S07, S09, S14, S15) with the stick pads under it, the result (S08) and the blocked notice (S13).
 */
@Composable
internal fun FlyPage(state: DsUiState, onEvent: (Ui) -> Unit) {
    when (state.screen) {
        Screen.WELCOME -> Box(Modifier.fillMaxWidth().height(348.dp)) { Welcome(state, onEvent) }
        Screen.RESULT -> Box(Modifier.fillMaxWidth().height((if (state.outcomeLesson == LessonId.L6) 328 else 288).dp)) { Result(state, onEvent) }
        Screen.BLOCKED -> Box(Modifier.fillMaxWidth().height(PanelSizes.FLIGHT_H.dp)) { Blocked(state, onEvent) }
        else ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().height(PanelSizes.FLIGHT_H.dp)) { Flight(state, onEvent) }
                if (state.sticksVisible) SticksPad()
            }
    }
}

/** 科目 page of the main window (S04 picker). */
@Composable
internal fun LessonsPage(state: DsUiState, onEvent: (Ui) -> Unit) = Picker(state, onEvent)

/** 设置 page of the main window (S11, S12 confirm inline at the bottom, D26). */
@Composable
internal fun SettingsPage(state: DsUiState, onEvent: (Ui) -> Unit) = Settings(state, onEvent)

/** "打开面板" on the Stage (native-window-move §3.3): only while the main window is closed or minimised. */
@Composable
internal fun OpenChip(state: DsUiState?) {
    Box(Modifier.size(PanelSizes.OPEN_W.dp, PanelSizes.OPEN_H.dp), contentAlignment = Alignment.Center) {
        if (state == null) return@Box
        if (io.embodit.dronesight.ui.window.WindowCtl.levelHint) {
            // q4-3 / D9: the main window waits for a level head; not a key, nothing to press
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(DsColors.TagBg).padding(horizontal = 16.dp, vertical = 6.dp).probe("look_level"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(text = rs(R.string.look_level), style = DsType.title.copy(fontSize = 26.sp, lineHeight = 32.sp), color = DsColors.TagInk, maxLines = 1, onTextLayout = fits("chip/look_level"))
                Text(text = rs(R.string.look_level_2), style = DsType.body, color = DsColors.TagInk, maxLines = 1, onTextLayout = fits("chip/look_level_2"))
            }
            return@Box
        }
        Key(rs(R.string.open_panel), "open_panel", R.drawable.ic_open_with, KeyKind.PRIMARY, 56.dp) { io.embodit.dronesight.ui.window.WindowCtl.reopen("chip") }
    }
}

/**
 * Page title row: title, an optional chip, the trailing control and settings. The title takes what is left and fits
 * itself (20 sp on one line, else 16 sp on two, else 14 sp on two: the 48-dp row holds two lines), never an ellipsis;
 * a dynamic chip gets a FIXED width ([chipWidth], its widest text for this lesson), so a timer running up can never
 * squeeze the title (QA q2-1, profile D13).
 */
@Composable
private fun TitleRow(title: String, onEvent: (Ui) -> Unit, state: DsUiState, chip: @Composable ((Modifier) -> Unit)? = null, chipWidth: Dp? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitTitle(title, Modifier.weight(1f))
        chip?.invoke(if (chipWidth != null) Modifier.width(chipWidth) else Modifier)
        trailing?.invoke()
    }
}

@Composable
private fun FitTitle(title: String, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        val measurer = rememberTextMeasurer()
        val big = DsType.title
        val mid = DsType.titleSmall.copy(lineHeight = 19.sp)
        val small = DsType.titleSmall.copy(fontSize = 14.sp, lineHeight = 17.sp)
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val (style, lines) =
            remember(title, maxW, maxH, big, mid, small) {
                listOf(big to 1, mid to 2, small to 2).firstOrNull { (s, n) ->
                    val r = measurer.measure(title, s, maxLines = n, constraints = Constraints(maxWidth = maxW.coerceAtLeast(1)))
                    !r.hasVisualOverflow && r.size.height <= maxH
                } ?: (small to 2)
            }
        Text(text = title, style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = lines, modifier = Modifier.probe("title"), onTextLayout = fits("control/title"))
    }
}

/** Width of a chip slot: the widest of [texts] in the chip style, plus the pill's padding. */
@Composable
private fun chipWidth(texts: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = DsType.label
    return remember(texts, style, density) {
        val px = texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
        with(density) { px.toDp() } + (2 * PILL_PAD + 4).dp
    }
}

@Composable
private fun MuteSwitch(state: DsUiState, onEvent: (Ui) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(if (state.prefs.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = rs(R.string.mute), modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(6.dp))
        // checked = sound on; off = muted (design: "静音开关一眼可见")
        Switch(checked = !state.prefs.muted, onCheckedChange = { on -> onEvent(Ui.SetPrefs("mute") { it.copy(muted = !on) }) }, modifier = Modifier.probe("mute"))
    }
}

@Composable
private fun chipText(state: DsUiState): String? {
    val sn = state.snap
    return when (state.screen) {
        Screen.READY -> if (state.lesson == LessonId.L6) rs(R.string.chip_full) else if (state.lesson == LessonId.F1) rs(R.string.chip_free) else rs(R.string.chip_ready)
        Screen.LANDING -> rs(R.string.chip_landed)
        Screen.FLYING, Screen.PAUSED, Screen.CRASH -> when {
            state.lesson == LessonId.F1 -> rs(R.string.chip_free)
            state.lesson == LessonId.F2 -> rs(R.string.chip_round, state.waypointRound, sn.gate.coerceAtMost(sn.gates), sn.gates, clock(sn.timeS.toFloat()))
            state.lesson == LessonId.L6 -> rs(R.string.chip_chain, sn.chainIndex + 1, progressShort(sn))
            else -> progressLong(sn)
        }
        else -> null
    }
}

/**
 * Every chip text the lesson can show on its flight pages, at its widest (two-digit counts, a timer past ten minutes):
 * the chip slot is sized once from these, so the title next to it never moves while you fly.
 */
@Composable
private fun chipCandidates(state: DsUiState): List<String> {
    val l = state.lesson
    val out = mutableListOf(rs(R.string.chip_ready), rs(R.string.chip_landed))
    when (l) {
        LessonId.F1 -> out += rs(R.string.chip_free)
        LessonId.F2 -> {
            val n = io.embodit.dronesight.domain.Waypoints.count(state.waypointRound)
            out += rs(R.string.chip_round, state.waypointRound, n, n, clock(5999f))
        }
        LessonId.L6 -> {
            out += rs(R.string.chip_full)
            for (t in l.tasks) out += rs(R.string.chip_chain, 5, widestShort(t))
        }
        else -> for (t in l.tasks) out += widestLong(t)
    }
    // digits drawn at their widest
    return out.map { it.replace(Regex("[0-9]"), "8") }
}

@Composable
private fun widestShort(t: Task): String = when (t) {
    Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> "88/88 s"
    Task.SPIN -> "888°"
    Task.EIGHT -> "8/4"
    Task.LAND -> rs(R.string.chip_landing)
    else -> ""
}

@Composable
private fun widestLong(t: Task): String = when (t) {
    Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> rs(R.string.chip_box, 88, 88)
    Task.SPIN -> rs(R.string.chip_spin, 888)
    Task.EIGHT -> rs(R.string.chip_gate, 8, 8)
    Task.LAND -> listOf(rs(R.string.chip_up, 8.8f), rs(R.string.chip_landing)).maxBy { it.length }
    else -> ""
}

@Composable
private fun progressShort(sn: io.embodit.dronesight.ui.FlightSnap): String = when (sn.task) {
    Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> "${sn.value}/${sn.target} s"
    Task.SPIN -> "${sn.value}°"
    Task.EIGHT -> "${(sn.gate - 1).coerceAtLeast(0)}/4"
    Task.LAND -> rs(R.string.chip_landing)
    else -> ""
}

@Composable
private fun progressLong(sn: io.embodit.dronesight.ui.FlightSnap): String = when (sn.task) {
    Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> rs(R.string.chip_box, sn.value, sn.target)
    Task.SPIN -> rs(R.string.chip_spin, sn.value)
    Task.EIGHT -> rs(R.string.chip_gate, sn.gate.coerceAtMost(4), 4)
    Task.LAND -> if (sn.value * 10 < sn.target * 10 && sn.heightDm < 12) rs(R.string.chip_up, sn.target / 10f) else rs(R.string.chip_landing)
    else -> ""
}

@Composable
private fun Flight(state: DsUiState, onEvent: (Ui) -> Unit) {
    val title =
        when {
            state.screen == Screen.STARTING || state.screen == Screen.FINDING_FLOOR || state.screen == Screen.PLACING || state.screen == Screen.BLOCKED -> rs(R.string.app_name)
            state.lesson == LessonId.L6 && (state.screen == Screen.FLYING || state.screen == Screen.PAUSED) -> rs(taskName(state.snap.task))
            else -> rs(lessonName(state.lesson))
        }
    val placing = state.screen == Screen.PLACING
    val inFlightPages = state.isFlight
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val chip = chipText(state)
        val slot = if (state.screen == Screen.STARTING || state.screen == Screen.FINDING_FLOOR || state.screen == Screen.PLACING || state.screen == Screen.BLOCKED) null else chipWidth(chipCandidates(state))
        TitleRow(title, onEvent, state, chip = chip?.let { c -> { m -> Pill(c, name = "chip", modifier = m) } }, chipWidth = slot) { MuteSwitch(state, onEvent) }
        // row 2: mode / wind
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            val en = inFlightPages
            Segments(listOf(FlightMode.GPS to rs(R.string.mode_gps), FlightMode.ATTI to rs(R.string.mode_atti)), DsController.currentMode(), "mode", 92, en) { onEvent(Ui.Mode(it)) }
            Spacer(Modifier.weight(1f))
            Segments(listOf(WindLevel.NONE to rs(R.string.wind_none), WindLevel.LIGHT to rs(R.string.wind_light), WindLevel.GUSTY to rs(R.string.wind_gusty)), DsController.currentWind(), "wind", 72, en) { onEvent(Ui.Wind(it)) }
        }
        // row 3: previous / main / next
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lessonNav = state.screen == Screen.READY
            IconKey(R.drawable.ic_chevron_left, rs(R.string.prev_cd), "prev", enabled = lessonNav && state.lesson.prev() != null) { onEvent(Ui.PrevLesson) }
            MainKey(state, onEvent, Modifier.weight(1f))
            IconKey(R.drawable.ic_chevron_right, rs(R.string.next_cd), "next", enabled = lessonNav && state.lesson.next() != null) { onEvent(Ui.NextLesson) }
        }
        // row 4: the hint line (status colours only as an opaque block behind dark-on-light / light-on-dark text)
        HintLine(state)
        // row 5: stick pads / direction hints / A key
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(rs(R.string.vsticks), "vsticks_toggle", R.drawable.ic_gamepad, if (state.vsticksOn) KeyKind.ON else KeyKind.NORMAL, 48.dp) { onEvent(Ui.ToggleSticks) }
            Key(rs(R.string.dir_hints), "hints_toggle", R.drawable.ic_explore, if (state.directionHints) KeyKind.ON else KeyKind.NORMAL, 48.dp) { onEvent(Ui.ToggleHints) }
            Spacer(Modifier.weight(1f))
            // the A / X shortcut only exists with controllers; without them the main key is the pause
            if (state.hasController) Text(text = rs(R.string.key_pause), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("control/key_pause"))
        }
        if (placing) Unit
    }
}

@Composable
private fun MainKey(state: DsUiState, onEvent: (Ui) -> Unit, modifier: Modifier) {
    when (state.screen) {
        Screen.PLACING -> Key(rs(R.string.place_here), "main", R.drawable.ic_check, KeyKind.PRIMARY, 56.dp, modifier) { onEvent(Ui.PlaceHere) }
        Screen.READY -> Key(rs(R.string.takeoff), "main", R.drawable.ic_flight_takeoff, KeyKind.PRIMARY, 56.dp, modifier) { onEvent(Ui.TakeOff) }
        Screen.FLYING -> Key(rs(R.string.pause), "main", R.drawable.ic_pause, KeyKind.PRIMARY, 56.dp, modifier) { onEvent(Ui.Pause) }
        Screen.PAUSED -> Key(rs(R.string.resume), "main", R.drawable.ic_play_arrow, KeyKind.PRIMARY, 56.dp, modifier) { onEvent(Ui.Resume) }
        Screen.LANDING -> IdleKey(rs(R.string.chip_landed), "main", modifier)
        Screen.CRASH -> IdleKey(rs(R.string.d_landHard), "main", modifier)
        Screen.FINDING_FLOOR -> IdleKey(rs(R.string.h_S02), "main", modifier)
        else -> IdleKey(rs(R.string.starting), "main", modifier)
    }
}

@Composable
private fun HintLine(state: DsUiState) {
    val res = hintText(state.hint)
    val text = when {
        res == null -> ""
        state.hint == Hint.CHAIN_NEXT -> rs(res, runCatching { rs(taskName(Task.valueOf(state.hintArg))) }.getOrDefault(state.hintArg))
        else -> rs(res)
    }
    val (bg, ink) = when (state.hint) {
        Hint.OUT, Hint.NEAR_YOU, Hint.LAND_HEAVY -> DsColors.WarnBg to DsColors.WarnInk
        Hint.CRASH -> DsColors.ErrBg to DsColors.ErrInk
        else -> Color.Transparent to PicoTheme.colorScheme.labelPrimary
    }
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = if (bg == Color.Transparent) 0.dp else 10.dp).probe("hint"), verticalAlignment = Alignment.CenterVertically) {
        if (state.screen == Screen.STARTING || (state.screen == Screen.FINDING_FLOOR && state.hint == Hint.S02)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = DsType.label, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = fits("control/hint"))
    }
}

// ================================================================================================= S01 welcome

@Composable
private fun Welcome(state: DsUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.app_name), style = DsType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("welcome/title"))
        }
        WelcomeLine(R.drawable.ic_gamepad, rs(if (state.hasController) R.string.welcome_1 else R.string.welcome_1_nc))
        WelcomeLine(R.drawable.ic_height, rs(R.string.welcome_2))
        WelcomeLine(R.drawable.ic_info, rs(R.string.welcome_3))
        Spacer(Modifier.weight(1f))
        Key(rs(R.string.welcome_ok), "welcome_ok", R.drawable.ic_flight_takeoff, KeyKind.PRIMARY, 56.dp, Modifier.fillMaxWidth()) { onEvent(Ui.WelcomeOk) }
    }
}

@Composable
private fun WelcomeLine(icon: Int, text: String) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = fits("welcome/line"))
    }
}

// ================================================================================================= S04 picker

@Composable
private fun Picker(state: DsUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleRow(rs(R.string.picker_title), onEvent, state) {
            Key(rs(R.string.replace), "replace", R.drawable.ic_open_with, KeyKind.NORMAL, 48.dp) { onEvent(Ui.Replace) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in LessonId.all) LessonRow(state, id) { onEvent(Ui.Pick(id)) }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.tol_label), style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, onTextLayout = fits("picker/tol_label"))
            Spacer(Modifier.weight(1f))
            Segments(listOf(Tolerance.LENIENT to rs(R.string.tol_lenient), Tolerance.STANDARD to rs(R.string.tol_standard)), state.prefs.tolerance, "tol", 104) { onEvent(Ui.Tol(it)) }
        }
    }
}

@Composable
private fun bestText(state: DsUiState, id: LessonId): String {
    if (id == LessonId.F2) return rs(R.string.round_n, state.waypointRound)
    if (id == LessonId.L7 && !state.hasController) return rs(R.string.use_controller)
    val b = state.best[id]
    val passed = id in state.passed
    return when {
        id == LessonId.F1 -> rs(R.string.chip_free)
        b == null && passed -> rs(R.string.passed)
        b == null -> rs(R.string.not_yet)
        id == LessonId.L3 -> rs(R.string.best_deg, b.toInt())
        id == LessonId.L4 -> if (passed) rs(R.string.passed) else rs(R.string.best_gates, b.toInt())
        id == LessonId.L5 -> rs(R.string.best_off, b)
        id == LessonId.L6 -> if (passed) rs(R.string.best_time, clock(b)) else rs(R.string.not_yet)
        passed -> rs(R.string.best_s, b.toInt())
        else -> rs(R.string.best_s_open, b.toInt())
    }
}

@Composable
private fun LessonRow(state: DsUiState, id: LessonId, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp).probe("card_$id"),
        size = ButtonDefaults.Regular.copy(height = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // pictogram on its own sky-blue disc: white glyph 4.1:1 against the disc, not against the glass
            Box(Modifier.size(32.dp).clip(CircleShape).background(DsColors.SkyBlue), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(lessonIcon(id)), contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = rs(lessonName(id)), style = DsType.titleSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = fits("picker/name_$id"))
                Text(text = rs(lessonTag(id)), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = fits("picker/tag_$id"))
            }
            Spacer(Modifier.width(8.dp))
            Pill(bestText(state, id), name = "best_$id")
        }
    }
}

// ================================================================================================= S08 result

@Composable
private fun Result(state: DsUiState, onEvent: (Ui) -> Unit) {
    val o = state.outcome ?: return
    val id = state.outcomeLesson
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleRow(rs(lessonName(id)), onEvent, state, chip = { m ->
            when (o.grade) {
                Grade.PASS -> Pill(rs(R.string.res_pass), DsColors.Passable, DsColors.OnFill, "grade", m)
                Grade.NEAR -> Pill(rs(R.string.res_near), DsColors.Alert, DsColors.OnFill, "grade", m)
                Grade.RETRY -> Pill(rs(R.string.res_retry), DsColors.Error, DsColors.OnError, "grade", m)
            }
        })
        Text(text = o.diffs.map { diffText(it) }.joinToString(" · "), style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(48.dp).probe("diffs"), onTextLayout = fits("result/diffs"))
        if (o.chain.isNotEmpty()) {
            val tasks = id.tasks
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tasks.forEachIndexed { i, t ->
                    val ok = o.chain.getOrElse(i) { false }
                    Pill((if (ok) "✓ " else "✗ ") + rs(taskName(t)), if (ok) DsColors.TagBg else DsColors.ErrBg, if (ok) DsColors.TagInk else DsColors.ErrInk, "chain_$i")
                }
            }
        }
        val mode = rs(if (DsController.currentMode() == FlightMode.GPS) R.string.mode_gps else R.string.mode_atti)
        val wind = rs(when (DsController.currentWind()) { WindLevel.NONE -> R.string.wind_none; WindLevel.LIGHT -> R.string.wind_light; WindLevel.GUSTY -> R.string.wind_gusty })
        Text(text = rs(R.string.res_meta, clock(o.timeS), mode, wind) + if (state.loosened) "  ·  " + rs(R.string.loosened) else "", style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("meta"), onTextLayout = fits("result/meta"))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.retry), "retry", R.drawable.ic_replay, KeyKind.PRIMARY, 56.dp, Modifier.weight(1f)) { onEvent(Ui.Retry) }
            Key(rs(if (id == LessonId.F2) R.string.next_round else R.string.next_lesson), "next_lesson", R.drawable.ic_chevron_right, KeyKind.NORMAL, 56.dp, Modifier.weight(1f)) { onEvent(Ui.Next) }
            Key(rs(R.string.to_picker), "to_picker", null, KeyKind.NORMAL, 48.dp, Modifier.weight(0.8f)) { onEvent(Ui.ToPicker) }
        }
        Text(text = rs(R.string.res_small), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("small_print"), onTextLayout = fits("result/small_print"))
    }
}

// ================================================================================================= S11 settings

@Composable
private fun SetRow(icon: Int, label: String, height: Dp = 52.dp, note: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = fits("settings/row"))
            if (note != null) Text(text = note, style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = fits("settings/row_note"))
        }
        Spacer(Modifier.width(8.dp))
        control()
    }
}

@Composable
private fun Stepper(value: String, name: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconKey(R.drawable.ic_remove, "-", "${name}_minus", onClick = onMinus)
        Text(text = value, style = DsType.button, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(72.dp).padding(horizontal = 6.dp).probe("${name}_value"), onTextLayout = fits("settings/${name}_value"))
        IconKey(R.drawable.ic_add, "+", "${name}_plus", onClick = onPlus)
    }
}

private fun step(v: Float, d: Float, lo: Float, hi: Float): Float = ((v + d) * 100f).roundToInt().div(100f).coerceIn(lo, hi)

/** Settings page (design panelContents.settingsPage): scrolls in the window; language on top, "退出应用" last (D26). */
@Composable
private fun Settings(state: DsUiState, onEvent: (Ui) -> Unit) {
    val p = state.prefs
    fun pr(why: String, f: (io.embodit.dronesight.ui.Prefs) -> io.embodit.dronesight.ui.Prefs) = onEvent(Ui.SetPrefs(why, f))
    val scroll = rememberScrollState()
    val want = SettingsScroll.request
    val nonce = SettingsScroll.nonce
    LaunchedEffect(state.screen) {
        // the inline confirmation sits at the very bottom: keep it in view
        if (state.screen == Screen.EXIT_CONFIRM) {
            kotlinx.coroutines.delay(100)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    LaunchedEffect(want, nonce) {
        if (want < 0) return@LaunchedEffect
        // twice: the first composition does not know the content height yet
        repeat(2) {
            kotlinx.coroutines.delay(400)
            scroll.scrollTo(if (want == Int.MAX_VALUE) scroll.maxValue else want)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.set_title), style = DsType.title, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), onTextLayout = fits("settings/title"))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // language first (D26: follows the system by default, switch here): 自动 = system, 中 / EN pin it, live
            SetRow(R.drawable.ic_language, rs(R.string.set_lang)) {
                Segments(listOf(Lang.SYSTEM to rs(R.string.lang_auto), Lang.ZH to rs(R.string.lang_zh), Lang.EN to rs(R.string.lang_en)), p.lang, "set_lang", 64) { l -> pr("lang") { it.copy(lang = l) } }
            }
            // 1 volume: slider 0-100 % with low / mid / high snaps (30 / 60 / 90 ±5) + percent + mute
            var vol by remember(p.volume) { mutableFloatStateOf(p.volume.toFloat()) }
            SetRow(R.drawable.ic_volume_up, rs(R.string.set_volume), 56.dp) {
                Slider(value = vol, onValueChange = { vol = it }, modifier = Modifier.width(150.dp).probe("volume"), valueRange = 0f..100f, onValueChangeFinished = {
                    val snapped = listOf(30f, 60f, 90f).firstOrNull { kotlin.math.abs(it - vol) <= 5f } ?: vol
                    vol = snapped
                    pr("volume") { it.copy(volume = snapped.roundToInt()) }
                })
                Text(text = String.format(Locale.US, "%d%%", vol.roundToInt()), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(48.dp).padding(start = 6.dp).probe("volume_pct"), onTextLayout = fits("settings/volume_pct"))
                Switch(checked = !p.muted, onCheckedChange = { on -> pr("mute") { it.copy(muted = !on) } }, modifier = Modifier.probe("mute_settings"))
            }
            SetRow(R.drawable.ic_speed, rs(R.string.set_volume) + " · " + rs(R.string.vol_low) + " / " + rs(R.string.vol_mid) + " / " + rs(R.string.vol_high)) {
                Segments(listOf(30 to rs(R.string.vol_low), 60 to rs(R.string.vol_mid), 90 to rs(R.string.vol_high)), listOf(30, 60, 90).minByOrNull { kotlin.math.abs(it - p.volume) } ?: 60, "vol_step", 60) { v -> pr("volume") { it.copy(volume = v) } }
            }
            SetRow(R.drawable.ic_my_location, rs(R.string.set_mode)) { Segments(listOf(FlightMode.GPS to rs(R.string.mode_gps), FlightMode.ATTI to rs(R.string.mode_atti)), p.mode, "set_mode", 80) { m -> onEvent(Ui.Mode(m)) } }
            SetRow(R.drawable.ic_air, rs(R.string.set_wind)) { Segments(listOf(WindLevel.NONE to rs(R.string.wind_none), WindLevel.LIGHT to rs(R.string.wind_light), WindLevel.GUSTY to rs(R.string.wind_gusty)), p.wind, "set_wind", 72) { w -> onEvent(Ui.Wind(w)) } }
            SetRow(R.drawable.ic_check, rs(R.string.set_tol)) { Segments(listOf(Tolerance.LENIENT to rs(R.string.tol_lenient), Tolerance.STANDARD to rs(R.string.tol_standard)), p.tolerance, "set_tol", 92) { t -> onEvent(Ui.Tol(t)) } }
            SetRow(R.drawable.ic_all_inclusive, rs(R.string.set_pole), if (p.poleDist >= 3f) 72.dp else 52.dp, note = if (p.poleDist >= 3f) rs(R.string.set_pole_note) else null) {
                Stepper(String.format(Locale.US, "%.1f m", p.poleDist), "pole", { pr("poleDist") { it.copy(poleDist = step(it.poleDist, -0.5f, 2f, 5f)) } }) { pr("poleDist") { it.copy(poleDist = step(it.poleDist, 0.5f, 2f, 5f)) } }
            }
            SetRow(R.drawable.ic_height, rs(R.string.set_hover)) {
                Stepper(String.format(Locale.US, "%.1f m", p.hoverH), "hover", { pr("hoverH") { it.copy(hoverH = step(it.hoverH, -0.1f, 1f, 2.5f)) } }) { pr("hoverH") { it.copy(hoverH = step(it.hoverH, 0.1f, 1f, 2.5f)) } }
            }
            SetRow(R.drawable.ic_landscape, rs(R.string.set_ceiling)) {
                Stepper(String.format(Locale.US, "%.1f m", p.ceiling), "ceiling", { pr("ceiling") { it.copy(ceiling = step(it.ceiling, -0.1f, 2.2f, 3.5f)) } }) { pr("ceiling") { it.copy(ceiling = step(it.ceiling, 0.1f, 2.2f, 3.5f)) } }
            }
            val eye = if (p.seated) 1.2f else 1.6f
            SetRow(R.drawable.ic_landscape, rs(R.string.set_floor), 72.dp, note = if (state.floorFound == true) rs(R.string.floor_found) else rs(R.string.floor_est, eye - p.floorOffset)) {
                Stepper(String.format(Locale.US, "%+.2f", p.floorOffset), "floor", { pr("floorOffset") { it.copy(floorOffset = step(it.floorOffset, -0.05f, -0.5f, 0.5f)) } }) { pr("floorOffset") { it.copy(floorOffset = step(it.floorOffset, 0.05f, -0.5f, 0.5f)) } }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(32.dp))
                Key(rs(if (p.seated) R.string.stand else R.string.sit), "seated", null, KeyKind.NORMAL, 48.dp) { pr("seated") { it.copy(seated = !it.seated) } }
            }
            SetRow(R.drawable.ic_joystick, rs(R.string.set_invert), 52.dp) {}
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.width(24.dp))
                InvSwitch(rs(R.string.inv_ly), p.invert.leftY, "inv_ly") { v -> pr("invert") { it.copy(invert = it.invert.copy(leftY = v)) } }
                InvSwitch(rs(R.string.inv_lx), p.invert.leftX, "inv_lx") { v -> pr("invert") { it.copy(invert = it.invert.copy(leftX = v)) } }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.width(24.dp))
                InvSwitch(rs(R.string.inv_ry), p.invert.rightY, "inv_ry") { v -> pr("invert") { it.copy(invert = it.invert.copy(rightY = v)) } }
                InvSwitch(rs(R.string.inv_rx), p.invert.rightX, "inv_rx") { v -> pr("invert") { it.copy(invert = it.invert.copy(rightX = v)) } }
            }
            SetRow(R.drawable.ic_speed, rs(R.string.set_slow)) { Switch(checked = p.slow, onCheckedChange = { v -> pr("slow") { it.copy(slow = v) } }, modifier = Modifier.probe("slow")) }
            SetRow(R.drawable.ic_explore, rs(R.string.set_hints)) { Segments(listOf(HintMode.ON to rs(R.string.hints_on), HintMode.OFF to rs(R.string.hints_off), HintMode.FIRST_TWO to rs(R.string.hints_first2)), p.hints, "set_hints", 76) { h -> pr("hints") { it.copy(hints = h) } } }
            SetRow(R.drawable.ic_gamepad, rs(R.string.set_vsticks)) { Segments(listOf(VStickMode.AUTO to rs(R.string.vs_auto), VStickMode.ALWAYS to rs(R.string.vs_always), VStickMode.OFF to rs(R.string.vs_off)), p.vsticks, "set_vsticks", 72) { v -> pr("vsticks") { it.copy(vsticks = v) } } }
            SetRow(R.drawable.ic_height, rs(R.string.set_altline)) { Switch(checked = p.altLine, onCheckedChange = { v -> pr("altLine") { it.copy(altLine = v) } }, modifier = Modifier.probe("altline")) }
            SetRow(R.drawable.ic_info, rs(R.string.set_bigtag)) { Switch(checked = p.bigTag, onCheckedChange = { v -> pr("bigTag") { it.copy(bigTag = v) } }, modifier = Modifier.probe("bigtag")) }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(rs(R.string.replace), "set_replace", R.drawable.ic_open_with, KeyKind.NORMAL, 48.dp, Modifier.weight(1f)) { onEvent(Ui.Replace) }
                Key(rs(R.string.recall), "set_recall", R.drawable.ic_my_location, KeyKind.NORMAL, 48.dp, Modifier.weight(1f)) { onEvent(Ui.RecallPanel) }
            }
            if (!state.resetConfirm) {
                SetRow(R.drawable.ic_restart_alt, rs(R.string.reset)) { Key(rs(R.string.reset), "reset", null, KeyKind.NORMAL, 48.dp) { onEvent(Ui.ResetAsk) } }
            } else {
                SetRow(R.drawable.ic_restart_alt, rs(R.string.reset_q), 56.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Key(rs(R.string.cancel), "reset_cancel", null, KeyKind.NORMAL, 48.dp) { onEvent(Ui.ResetCancel) }
                        Key(rs(R.string.reset_yes), "reset_yes", null, KeyKind.DANGER, 48.dp) { onEvent(Ui.ResetYes) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).probe("about")) {
                Text(text = rs(R.string.about), style = DsType.titleSmall, color = PicoTheme.colorScheme.labelPrimary, onTextLayout = fits("settings/about"))
                Text(text = rs(R.string.about_1), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("settings/about_1"))
                Text(text = rs(R.string.about_2), style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("settings/about_2"))
            }
            // D26: quit is always the last row; the confirmation opens right here (S12), no second panel
            if (state.screen == Screen.EXIT_CONFIRM) ExitConfirm(onEvent)
            else Key(rs(R.string.quit), "quit", R.drawable.ic_logout, KeyKind.DANGER, 52.dp, Modifier.fillMaxWidth()) { onEvent(Ui.QuitAsk) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Debug seam: settings scroll position request (`settings bottom`). */
object SettingsScroll {
    var request by mutableStateOf(-1)
    var nonce by mutableStateOf(0)
}

@Composable
private fun InvSwitch(label: String, on: Boolean, name: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.width(196.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("settings/inv_$name"))
        Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.probe(name))
    }
}

// ================================================================================================= S12 exit confirm

// QA #4 q4-1: the confirmation sat on a dark slate card while 取消 kept the glass colours (labelPrimary ink) -> 1.30:1.
// Now it is on the window glass like every other settings row, and 取消 is a plain system Button (factory template:
// default colours, a tertiary fill disappears on the glass).
@Composable
private fun ExitConfirm(onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).probe("exit_confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = rs(R.string.exit_title), style = DsType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("exit/title"))
        Text(text = rs(R.string.exit_body), style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("exit/body"))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onEvent(Ui.QuitCancel) },
                modifier = Modifier.weight(1f).probe("quit_cancel").height(56.dp),
                size = ButtonDefaults.Regular.copy(height = 56.dp),
                shape = CircleShape,
            ) {
                Text(text = rs(R.string.cancel), maxLines = 1, style = DsType.button, onTextLayout = fits("key/quit_cancel"))
            }
            Key(rs(R.string.exit_yes), "quit_yes", R.drawable.ic_logout, KeyKind.DANGER, 56.dp, Modifier.weight(1f)) { onEvent(Ui.QuitYes) }
        }
    }
}

// ================================================================================================= S13 blocked

/** S13 in the main window (it used to be a dialog straight ahead on the Stage). */
@Composable
private fun Blocked(state: DsUiState, onEvent: (Ui) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(DsColors.Error), contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_error), contentDescription = null, modifier = Modifier.size(24.dp), tint = DsColors.OnError)
                }
                Spacer(Modifier.width(10.dp))
                Text(text = rs(R.string.blk_title), style = DsType.title, color = PicoTheme.colorScheme.labelPrimary, onTextLayout = fits("dialog/title"))
            }
            Text(text = rs(if (state.blockReason == "not-in-full-space") R.string.blk_fullspace else R.string.blk_head), style = DsType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("dialog/body"))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Key(rs(R.string.blk_retry), "blk_retry", R.drawable.ic_replay, KeyKind.PRIMARY, 56.dp, Modifier.weight(1f)) { onEvent(Ui.BlockRetry) }
                Key(rs(R.string.blk_quit), "blk_quit", R.drawable.ic_logout, KeyKind.DANGER, 56.dp, Modifier.weight(1f)) { onEvent(Ui.QuitYes) }
            }
        }
    }
}

// ================================================================================================= altitude tag

/** The height tag over the drone (panels.altitudeTag): opaque dark slate, not interactive. */
@Composable
internal fun AltitudeTag(state: DsUiState?) {
    val big = state?.prefs?.bigTag == true
    // 56 / 72 dp (design) clipped the second line ("Tail-in") by a few px: the 28 sp figure's font padding takes more than
    // its 32 sp line height (v0.1.1 OVERFLOW tag/orient). 64 / 84 dp hold both lines.
    val w = if (big) 200 else 160
    val h = if (big) 84 else 64
    Box(Modifier.size(PanelSizes.TAG_W.dp, PanelSizes.TAG_H.dp), contentAlignment = Alignment.Center) {
        if (state == null) return@Box
        Column(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(12.dp)).background(DsColors.TagBg).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val sn = state.snap
            val off = sn.landingOffsetCm
            if (state.screen == Screen.LANDING && off != null) {
                Text(text = rs(R.string.tag_off, off / 100f), style = if (big) DsType.tagBig.copy(fontSize = 34.sp()) else DsType.tagBig.copy(fontSize = 22.sp()), color = DsColors.TagInk, maxLines = 1, onTextLayout = fits("tag/off"))
            } else {
                Text(text = String.format(Locale.US, "%.1f m", sn.heightDm / 10f), style = if (big) DsType.tagBig.copy(fontSize = 40.sp()) else DsType.tagBig, color = DsColors.TagInk, maxLines = 1, onTextLayout = fits("tag/height"))
                val o = rs(when (sn.orientation) { Orientation.TAIL_IN -> R.string.tag_tail; Orientation.NOSE_IN -> R.string.tag_nose; Orientation.SIDE -> R.string.tag_side })
                Text(text = o + if (sn.gusting) "  " + rs(R.string.tag_wind) else "", style = if (big) DsType.tagSmall.copy(fontSize = 18.sp()) else DsType.tagSmall, color = DsColors.TagInk, maxLines = 1, onTextLayout = fits("tag/orient"))
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

// ================================================================================================= S14 virtual sticks

/**
 * Two stick pads (panels.virtualSticks), under the flight page in the main window: press anywhere on a pad (ray +
 * trigger, or a pinch) and drag; the offset / 80 dp is the stick, clamped to 1; letting go springs back in 0.15 s.
 * Custom element (no SpatialUI stick exists).
 */
@Composable
private fun SticksPad() {
    Row(Modifier.fillMaxWidth().height(PanelSizes.STICKS_H.dp).probe("sticks"), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Pad(rs(R.string.pad_left), left = true)
        Pad(rs(R.string.pad_right), left = false)
    }
}

@Composable
private fun Pad(label: String, left: Boolean) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    // the debug hook `vstick` writes VirtualSticks directly (the same values a drag writes): show them too
    @Suppress("UNUSED_VARIABLE") val v = VirtualSticks.version.intValue
    val shownX = if (held) dx else (if (left) VirtualSticks.lx else VirtualSticks.rx) * 80f
    val shownY = if (held) dy else -(if (left) VirtualSticks.ly else VirtualSticks.ry) * 80f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = DsType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("sticks/label_$left"))
        Spacer(Modifier.height(4.dp))
        val knob = DsColors.Interaction
        val ring = PicoTheme.colorScheme.labelPrimary
        Box(
            Modifier.size(160.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillTertiary).spatialHoverEffect()
                .probe(if (left) "pad_left" else "pad_right")
                .drawBehind {
                    val c = Offset(size.width / 2, size.height / 2)
                    drawLine(ring.copy(alpha = 0.5f), Offset(c.x, 12f), Offset(c.x, size.height - 12f), strokeWidth = 2f)
                    drawLine(ring.copy(alpha = 0.5f), Offset(12f, c.y), Offset(size.width - 12f, c.y), strokeWidth = 2f)
                }
                .pointerInput(left) {
                    val unit = 80.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        held = true
                        if (left) VirtualSticks.leftHeld = true else VirtualSticks.rightHeld = true
                        fun write(p: Offset) {
                            var x = (p.x - c.x) / unit
                            var y = (p.y - c.y) / unit
                            val l = sqrt(x * x + y * y)
                            if (l > 1f) { x /= l; y /= l }
                            dx = x * 80f
                            dy = y * 80f
                            if (left) { VirtualSticks.lx = x; VirtualSticks.ly = -y } else { VirtualSticks.rx = x; VirtualSticks.ry = -y }
                        }
                        write(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            write(ch.position)
                            ch.consume()
                        }
                        held = false
                        dx = 0f
                        dy = 0f
                        if (left) { VirtualSticks.lx = 0f; VirtualSticks.ly = 0f; VirtualSticks.leftHeld = false } else { VirtualSticks.rx = 0f; VirtualSticks.ry = 0f; VirtualSticks.rightHeld = false }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.offset { IntOffset((shownX * density).roundToInt(), (shownY * density).roundToInt()) }.size(56.dp).clip(CircleShape).background(if (held) knob else knob.copy(alpha = 0.85f)))
        }
    }
}
