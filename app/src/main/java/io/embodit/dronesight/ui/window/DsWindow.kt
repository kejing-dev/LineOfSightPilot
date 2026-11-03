package io.embodit.dronesight.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.dronesight.R
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.ui.DsController
import io.embodit.dronesight.ui.Screen
import io.embodit.dronesight.ui.Tab
import io.embodit.dronesight.ui.Ui
import io.embodit.dronesight.ui.components.FlyPage
import io.embodit.dronesight.ui.components.LessonsPage
import io.embodit.dronesight.ui.components.LocalDsResources
import io.embodit.dronesight.ui.components.PanelSizes
import io.embodit.dronesight.ui.components.SettingsPage
import io.embodit.dronesight.ui.components.localizedResources
import io.embodit.dronesight.ui.components.rs
import io.embodit.dronesight.ui.debug.LocalProbePanel
import io.embodit.dronesight.ui.theme.DsTheme

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "ControlPanel"
const val PANEL_TAG = "main"

/**
 * The main window's state shared by the Stage and the window (one process, native-window-move §3.4): the window
 * reports its lifecycle, the Stage opens / recalls it with its navigator. Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    /** Bumped on every ON_CREATE: the scene re-estimates where the system put the window. */
    var createdEpoch by mutableIntStateOf(0)
        private set

    /** Metres per window dp, from the window's LocalPhysicalLengthConverter (the Stage's 0.8 mm until it reports). */
    @Volatile var dpM = 0.0008f

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var askedAtMs = 0L

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    /** The Stage shows "打开面板" while the window is closed / minimised, or never came up within 8 s of asking. */
    fun chipWanted(now: Long): Boolean =
        when (panelState) {
            "ON_DESTROY", "ON_STOP" -> !recallPending
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    fun openWindow(why: String) {
        val nav = navigator ?: return DsLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        DsLog.i("WINDOW open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { DsLog.w("WINDOW open failed: $it") }
    }

    /** The chip: a minimised window is restored where it was, a closed one opens in front of you. */
    fun reopen(why: String) {
        if (panelState == "ON_STOP" && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
            DsLog.i("WINDOW restore why=$why")
            return
        }
        openWindow(why)
    }

    /**
     * "叫回面板": the system has no call to move a window, and a second open of an open window only focuses it, so a
     * recall closes it and opens it again once it reported ON_DESTROY (an open right after close is swallowed, §3.5).
     */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        DsLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { DsLog.w("WINDOW close failed: $it"); recallPending = false }
    }

    fun onLifecycle(event: String) {
        panelState = event
        DsLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") createdEpoch++
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage in the Shared Space, §7.7). */
    fun closeForQuit() {
        recallPending = false
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }
}

/** Which TabBar page shows for a controller screen. */
fun tabOf(screen: Screen): String =
    when (screen) {
        Screen.PICKER -> "lessons"
        Screen.SETTINGS, Screen.EXIT_CONFIRM -> SETTINGS_PAGE
        else -> "fly"
    }

/**
 * The content of the main window (design window model single_panel_with_popup, now one system Planar window): pages
 * 飞行 (welcome / placing / flight + stick pads / result / blocked) and 科目 (picker), then 设置 with 退出应用 last.
 */
@Composable
fun DsWindow() {
    val context = LocalContext.current
    val state by DsController.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.takeIf { it in 0.0002f..0.005f }?.let {
            WindowCtl.dpM = it
            DsLog.i(String.format(java.util.Locale.US, "WINDOW dp=%.5f m (%.2f x %.2f m)", it, PanelSizes.WINDOW_W * it, PanelSizes.WINDOW_H * it))
        }
    }
    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    val onEvent: (Ui) -> Unit = DsController::onEvent
    CompositionLocalProvider(LocalDsResources provides resources, LocalProbePanel provides "window") {
        DsTheme {
            MainWindow(
                pages =
                    listOf(
                        MainWindowPage("fly", rs(R.string.tab_fly), R.drawable.ic_flight_takeoff) { FlyPage(state, onEvent) },
                        MainWindowPage("lessons", rs(R.string.tab_lessons), R.drawable.ic_format_list_numbered) { LessonsPage(state, onEvent) },
                    ),
                settings = MainWindowSettings(rs(R.string.settings), R.drawable.ic_settings) { SettingsPage(state, onEvent) },
                current = tabOf(state.screen),
                onSelect = { id -> onEvent(Ui.Tab(when (id) { "lessons" -> Tab.LESSONS; SETTINGS_PAGE -> Tab.SETTINGS; else -> Tab.FLY })) },
            )
        }
    }
}
