package io.embodit.dronesight.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.Sticks
import io.embodit.dronesight.domain.Vec3
import io.embodit.dronesight.domain.WindLevel
import io.embodit.dronesight.domain.Yaw
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.ui.DsController
import io.embodit.dronesight.ui.Lang
import io.embodit.dronesight.ui.Screen
import io.embodit.dronesight.ui.Ui
import io.embodit.dronesight.ui.components.SettingsScroll
import io.embodit.dronesight.ui.components.VirtualSticks
import io.embodit.dronesight.ui.debug.Probe
import io.embodit.dronesight.ui.window.PANEL_ID
import io.embodit.dronesight.ui.window.PANEL_TAG
import io.embodit.dronesight.ui.window.WindowCtl

/**
 * Debug builds only (QA hooks, design qaHooks). Every command goes through the same events as the panels
 * (DsController.onEvent) or feeds the same inputs as the sticks / the head / the flight sensors (debug sticks, virtual
 * pads, the drone's position for the `fly` macros, fast-forwarded time): no command writes an app state directly
 * (factory rule 2026-10-29). Usage and the screenshot list: AGENTS.md.
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.dronesight.DEBUG -p io.embodit.dronesight --es cmd 'lesson L2'"
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    private fun ev(e: Ui): String {
        DsController.onEvent(e)
        return "state=${DsController.state.value.designState}"
    }

    /** Walks the start pages with the user's own events until the picker (S04) is up. */
    private fun toPicker() {
        repeat(6) {
            when (DsController.state.value.screen) {
                Screen.WELCOME -> DsController.onEvent(Ui.WelcomeOk)
                Screen.FINDING_FLOOR -> Probe.host?.scene()?.forceFloor = true
                Screen.PLACING -> DsController.onEvent(Ui.PlaceHere)
                Screen.SETTINGS -> DsController.onEvent(Ui.CloseSettings)
                Screen.EXIT_CONFIRM -> DsController.onEvent(Ui.QuitCancel)
                Screen.RESULT, Screen.READY -> DsController.onEvent(Ui.ToPicker)
                Screen.PAUSED -> DsController.onEvent(Ui.Resume)
                else -> Unit
            }
        }
    }

    private fun lesson(id: LessonId): String {
        val s = DsController.state.value
        when (s.screen) {
            Screen.PICKER, Screen.RESULT -> DsController.onEvent(Ui.Pick(id))
            Screen.READY -> {
                DsController.onEvent(Ui.ToPicker)
                DsController.onEvent(Ui.Pick(id))
            }
            Screen.FLYING, Screen.PAUSED -> {
                // land it where it is first (the user would have to land too): the macro picks after the result
                return "fly the current lesson to its end first (state=${s.designState})"
            }
            else -> {
                toPicker()
                if (DsController.state.value.screen == Screen.PICKER) DsController.onEvent(Ui.Pick(id))
            }
        }
        return "state=${DsController.state.value.designState} lesson=${DsController.state.value.lesson}"
    }

    private fun xy(s: String): Pair<Float, Float> {
        val p = s.split(",")
        return (p.getOrNull(0)?.toFloatOrNull() ?: 0f) to (p.getOrNull(1)?.toFloatOrNull() ?: 0f)
    }

    /** Takes off (same TakeOff event) and puts the drone in the air at [local] / [yaw] (sensor-level placement). */
    private fun airborne(local: Vec3, yaw: Float?): String {
        val sc = Probe.host?.scene() ?: return "no scene"
        if (DsController.state.value.screen == Screen.READY) DsController.onEvent(Ui.TakeOff)
        if (DsController.state.value.screen == Screen.PAUSED) DsController.onEvent(Ui.Resume)
        sc.debugHover(local, yaw)
        return "hover at ${DsLog.pos(local)}"
    }

    private fun noseInYaw(at: Vec3): Float? {
        val h = Probe.host?.scene()?.headLocal() ?: return null
        return Yaw.of(Vec3(h.x - at.x, 0f, h.z - at.z))
    }

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        ctx = context
        val sc = Probe.host?.scene()
        val arg = s?.trim().orEmpty()
        val args = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sess = DsController.session
        val geo = sess?.course
        val result: String =
            when (cmd) {
                // ---------------------------------------------------------------- pages (user events)
                "firstRun" -> { DsController.debugFirstRun(); "first run" }
                "welcomeOk" -> ev(Ui.WelcomeOk)
                "place" -> ev(Ui.PlaceHere)
                "picker" -> { toPicker(); "state=${DsController.state.value.designState}" }
                "lesson" -> lesson(LessonId.valueOf(arg.uppercase()))
                "takeoff" -> ev(Ui.TakeOff)
                "pause" -> ev(Ui.Pause)
                "resume" -> ev(Ui.Resume)
                "key" -> ev(Ui.PauseKey)
                "retry" -> ev(Ui.Retry)
                "next" -> ev(Ui.Next)
                "prev" -> ev(Ui.PrevLesson)
                "nextLesson" -> ev(Ui.NextLesson)
                "toPicker" -> ev(Ui.ToPicker)
                "settings" -> {
                    if (DsController.state.value.screen != Screen.SETTINGS) DsController.onEvent(Ui.OpenSettings)
                    SettingsScroll.request = when (arg) { "bottom" -> Int.MAX_VALUE; "top", "" -> 0; else -> arg.toIntOrNull() ?: 0 }
                    SettingsScroll.nonce++
                    "state=${DsController.state.value.designState} scroll=$arg"
                }
                "closeSettings" -> ev(Ui.CloseSettings)
                "replace" -> ev(Ui.Replace)
                "recall" -> ev(Ui.RecallPanel)
                // v0.1.2 main window: the TabBar's own event, and the window's open / close as the chip / × do it
                "tab" -> ev(Ui.Tab(when (arg) { "lessons" -> io.embodit.dronesight.ui.Tab.LESSONS; "settings" -> io.embodit.dronesight.ui.Tab.SETTINGS; else -> io.embodit.dronesight.ui.Tab.FLY }))
                "window" -> when (arg) {
                    "close" -> { WindowCtl.navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG); "close sent, state=${WindowCtl.panelState}" }
                    "open" -> { WindowCtl.reopen("debug"); "open sent, state=${WindowCtl.panelState}" }
                    else -> "state=${WindowCtl.panelState} created=${WindowCtl.createdEpoch} dpM=${WindowCtl.dpM}"
                }
                "quitAsk" -> { if (DsController.state.value.screen != Screen.SETTINGS) DsController.onEvent(Ui.OpenSettings); ev(Ui.QuitAsk) }
                "quitCancel" -> ev(Ui.QuitCancel)
                "quitYes" -> ev(Ui.QuitYes)
                "resetAsk" -> ev(Ui.ResetAsk)
                "block" -> { sc?.forceBlock = "head-not-ready"; DsController.onBlocked("head-not-ready"); "blocked" }
                "unblock" -> { sc?.forceBlock = null; ev(Ui.BlockRetry) }
                "vsticks" -> ev(Ui.ToggleSticks)
                "hints" -> ev(Ui.ToggleHints)
                "mode" -> ev(Ui.Mode(if (arg.startsWith("a", true)) io.embodit.dronesight.domain.FlightMode.ATTI else io.embodit.dronesight.domain.FlightMode.GPS))
                "wind" -> when (arg) {
                    "gust" -> { sess?.flight?.wind?.gustNow(); "gust" }
                    else -> ev(Ui.Wind(WindLevel.valueOf(arg.uppercase())))
                }
                "tol" -> ev(Ui.Tol(if (arg.startsWith("s", true)) io.embodit.dronesight.domain.Tolerance.STANDARD else io.embodit.dronesight.domain.Tolerance.LENIENT))
                "lang" -> ev(Ui.SetPrefs("lang") { it.copy(lang = when (arg) { "zh" -> Lang.ZH; "en" -> Lang.EN; else -> Lang.SYSTEM }) })
                "mute" -> ev(Ui.SetPrefs("mute") { it.copy(muted = arg != "off") })
                "volume" -> ev(Ui.SetPrefs("volume") { it.copy(volume = arg.toIntOrNull() ?: 60) })
                "poles" -> ev(Ui.SetPrefs("poleDist") { it.copy(poleDist = arg.toFloatOrNull()?.coerceIn(2f, 5f) ?: 2.5f) })
                "seated" -> ev(Ui.SetPrefs("seated") { it.copy(seated = arg == "1") })
                "bigtag" -> ev(Ui.SetPrefs("bigTag") { it.copy(bigTag = arg != "0") })
                "best" -> { DsController.debugBest(LessonId.valueOf(args[0].uppercase()), args.getOrNull(1)?.toFloatOrNull() ?: 0f); "best" }
                "round" -> { DsController.debugRound(arg.toIntOrNull() ?: 1); "round" }
                "waypoint" -> { DsController.debugRound(args.getOrNull(1)?.toIntOrNull() ?: arg.toIntOrNull() ?: 1); lesson(LessonId.F2) }
                // ---------------------------------------------------------------- inputs (same paths as the hardware)
                "lite" -> {
                    if (args.getOrNull(0) == "unlit") { sc?.debugUnlitDrone(); "unlit drone" } else { sc?.lite?.set(args.getOrNull(0) ?: "", args.getOrNull(1) != "0"); "lite=${sc?.lite}" }
                }
                "floorscan" -> { sc?.forceScan = arg != "0"; "floorscan=${sc?.forceScan}" }
                "nofloor" -> { sc?.forceNoFloor = arg != "0"; "nofloor=${sc?.forceNoFloor}" }
                "floor" -> { sc?.forceFloor = arg != "0"; "floor=${sc?.forceFloor}" }
                "nocontroller" -> { DsController.debugNoController(arg.ifEmpty { "1" }); "nocontroller=$arg hasController=${DsController.state.value.hasController}" }
                "fit" -> { if (arg == "reset") io.embodit.dronesight.ui.components.TextFit.reset(); io.embodit.dronesight.ui.components.TextFit.summary() }
                "ray" -> { val (x, z) = xy(arg); sc?.debugRay = if (arg == "off") null else Vec3(x, 0f, z); "ray=${sc?.debugRay}" }
                "stick" -> {
                    // stick l x,y r x,y [ms]
                    var l = 0f to 0f
                    var r = 0f to 0f
                    var ms = 500L
                    var i = 0
                    while (i < args.size) {
                        when (args[i]) {
                            "l" -> { l = xy(args.getOrNull(i + 1) ?: ""); i++ }
                            "r" -> { r = xy(args.getOrNull(i + 1) ?: ""); i++ }
                            else -> args[i].toLongOrNull()?.let { ms = it }
                        }
                        i++
                    }
                    sc?.debugStick(Sticks(l.first, l.second, r.first, r.second), ms)
                    "stick l=$l r=$r ms=$ms"
                }
                "vstick" -> {
                    if (arg == "off") { VirtualSticks.clear(); "vstick off" } else {
                        val side = args.getOrNull(0) ?: "r"
                        val (x, y) = xy(args.getOrNull(1) ?: "")
                        val ms = args.getOrNull(2)?.toLongOrNull() ?: 2000L
                        if (side == "l") { VirtualSticks.lx = x; VirtualSticks.ly = y } else { VirtualSticks.rx = x; VirtualSticks.ry = y }
                        VirtualSticks.version.intValue++
                        main.postDelayed({ VirtualSticks.clear() }, ms)
                        "vstick $side=($x,$y) ms=$ms"
                    }
                }
                "speed" -> { sc?.timeScale = arg.toFloatOrNull()?.coerceIn(0.1f, 20f) ?: 1f; "timeScale=${sc?.timeScale}" }
                "freeze" -> { sc?.frozen = arg != "0"; "frozen=${sc?.frozen}" }
                "simulate" -> { sc?.debugSimulate(arg.toFloatOrNull() ?: 1f); "simulated" }
                // ---------------------------------------------------------------- flight positions (sensor-level)
                "fly" -> if (sess == null || geo == null || sc == null) "no session (pick a lesson first)" else {
                    val box = geo.hoverBox.center
                    when (args.getOrNull(0)) {
                        "hover" -> airborne(box, 0f)
                        "nosein" -> airborne(box, noseInYaw(box))
                        "spin" -> { airborne(box, 0f); sc.debugStick(Sticks(0.6f, 0f, 0f, 0f), (args.getOrNull(1)?.toLongOrNull() ?: 3000L)); "spinning" }
                        "gate" -> if (sess.lesson == LessonId.F2) {
                            // waypoint run: through rings 1..N-1 for real, stop before ring N
                            val n = (args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, sess.rings.size)
                            airborne(Vec3(0f, 0.8f, 0f), null)
                            val pts = ArrayList<Pair<Vec3, Float?>>()
                            for (r in sess.rings.take(n - 1)) {
                                pts += (r.center - r.normal * 0.3f) to Yaw.of(r.normal)
                                pts += (r.center + r.normal * 0.3f) to Yaw.of(r.normal)
                            }
                            val r = sess.rings[n - 1]
                            pts += (r.center - r.normal * 0.6f) to Yaw.of(r.normal)
                            sc.debugPath(pts)
                            "ring $n next"
                        } else {
                            val n = (args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 4)
                            airborne(Vec3(0.3f, 1.2f, 0.3f), 0f)
                            val pts = ArrayList<Pair<Vec3, Float?>>()
                            for (g in geo.gates.take(n - 1)) {
                                val yaw = Yaw.of(g.normal)
                                pts += (g.at - g.normal * 0.3f + Vec3(0f, 1.2f, 0f)) to yaw
                                pts += (g.at + g.normal * 0.3f + Vec3(0f, 1.2f, 0f)) to yaw
                            }
                            val g = geo.gates[n - 1]
                            pts += (g.at - g.normal * 0.6f + Vec3(0f, 1.2f, 0f)) to Yaw.of(g.normal)
                            sc.debugPath(pts)
                            "gate $n next"
                        }
                        "chain" -> {
                            // L6: do items 1 .. k-1 for real (box + fast-forwarded time), stop at item k
                            val k = (args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 5)
                            airborne(box, 0f)
                            if (k >= 2) { sc.debugSimulate(21f) }
                            if (k >= 3) { sc.debugHover(box, noseInYaw(box)); sc.debugSimulate(21f) }
                            if (k >= 4) { sc.debugHover(box, 0f); sc.debugStick(Sticks(0.6f, 0f, 0f, 0f), 60_000L); sc.debugSimulate(8f); sc.debugStick(Sticks.ZERO, 1) }
                            if (k >= 5) { sc.debugPath(geo.gates.flatMap { g -> listOf((g.at - g.normal * 0.3f + Vec3(0f, 1.2f, 0f)) to Yaw.of(g.normal), (g.at + g.normal * 0.3f + Vec3(0f, 1.2f, 0f)) to Yaw.of(g.normal)) }) }
                            "chain item $k task=${sess.task}"
                        }
                        "out" -> airborne(Vec3(3.4f, 1.2f, 0f), 0f)
                        "left" -> sc.pointBeforePanel()?.let { airborne(it, 0f) } ?: "no panel"
                        "land" -> {
                            val kind = args.getOrNull(1) ?: "soft"
                            val at = if (kind == "off") Vec3(1.0f, 0f, 0f) else Vec3(0.1f, 0f, 0.05f)
                            // up to the hover height first (L5 "先上到 1.5 m"), then the last few cm with the given sink rate
                            airborne(Vec3(at.x, geo.hoverM, at.z), null)
                            val vy = when (kind) { "heavy" -> -0.7f; "crash" -> -1.4f; else -> -0.3f }
                            main.postDelayed({
                                sc.debugHover(Vec3(at.x, 0.05f, at.z), null)
                                sess.flight.forceVertical(vy)
                                sc.debugStick(Sticks(0f, if (vy < -1f) -1f else -0.3f, 0f, 0f), 800)
                            }, 300)
                            "landing $kind"
                        }
                        "crash" -> {
                            airborne(Vec3(0.1f, geo.hoverM, 0f), null)
                            main.postDelayed({
                                sc.debugHover(Vec3(0.1f, 0.05f, 0f), null)
                                sess.flight.forceVertical(-1.5f)
                                sc.debugStick(Sticks(0f, -1f, 0f, 0f), 800)
                            }, 300)
                            "crash"
                        }
                        else -> "fly hover|nosein|spin [ms]|gate N|chain K|out|left|land soft|heavy|off|crash"
                    }
                }
                // ---------------------------------------------------------------- whole design states (macros of the above)
                "state" -> state(arg.uppercase())
                // ---------------------------------------------------------------- diagnostics
                "view" -> { sc?.viewYawDeg = arg.toFloatOrNull() ?: 0f; DsController.onEvent(Ui.RecallPanel); "view=${sc?.viewYawDeg} (panel re-placed; course: replace + place)" }
                "where" -> sc?.where() ?: "no scene"
                "probe3d" -> (sc?.probe3d() ?: emptyList()).size.toString() + " lines"
                "panels" -> (sc?.panelLines() ?: emptyList()).also { it.forEach(DsLog::i) }.size.toString() + " panels"
                "instances" -> (sc?.logInstances() ?: emptyList()).joinToString(" | ")
                "ftest" -> { sc?.debugFTest(arg != "off"); "ftest=${arg != "off"}" }
                "bounds" -> {
                    val panel = arg.ifEmpty { "window" }
                    val size = Probe.sizeOf(panel)
                    for ((name, b) in Probe.controlsOf(panel)) DsLog.i(String.format(java.util.Locale.US, "BOUNDS panel=%s name=%s cx=%.0f cy=%.0f w=%.0f h=%.0f panelPx=%s", panel, name, b.cx, b.cy, b.w, b.h, size))
                    "bounds $panel ${Probe.controlsOf(panel).size}"
                }
                "dump" -> DsController.state.value.let { "DUMP state=${it.designState} lesson=${it.lesson} hint=${it.hint} snap=${it.snap} outcome=${it.outcome} prefs=${it.prefs}" }
                else -> "unknown command '$cmd'"
            }
        DsLog.i("CMD $cmd $arg -> $result")
    }

    /** `state Sxx`: reaches a design state with the user's events + the flight inputs above (QA screenshots). */
    private fun state(id: String): String {
        val sc = Probe.host?.scene()
        return when (id) {
            "S01" -> { DsController.debugFirstRun(); "S01" }
            "S02" -> { sc?.forceNoFloor = false; sc?.forceFloor = false; if (DsController.state.value.screen == Screen.WELCOME) DsController.onEvent(Ui.WelcomeOk); "S02 (looking 5 s)" }
            "S03" -> { toPicker(); DsController.onEvent(Ui.Replace); "S03" }
            "S04" -> { toPicker(); "S04" }
            "S05" -> lesson(LessonId.L1)
            "S06" -> { lesson(LessonId.L1); run0("fly hover") }
            "S06A" -> { lesson(LessonId.L1); run0("fly out") }
            "S07" -> { lesson(LessonId.L5); run0("fly land soft") }
            "S08" -> { lesson(LessonId.L5); run0("fly land soft"); "S08 in ~1.5 s" }
            "S09" -> { lesson(LessonId.L1); run0("fly crash") }
            "S10" -> { lesson(LessonId.F2); DsController.onEvent(Ui.TakeOff); "S10 after the climb" }
            "S11" -> { if (DsController.state.value.screen != Screen.SETTINGS) DsController.onEvent(Ui.OpenSettings); "S11" }
            "S12" -> { if (DsController.state.value.screen != Screen.SETTINGS) DsController.onEvent(Ui.OpenSettings); DsController.onEvent(Ui.QuitAsk); "S12" }
            "S13" -> { sc?.forceBlock = "head-not-ready"; DsController.onBlocked("head-not-ready"); "S13" }
            "S14" -> { if (!DsController.state.value.vsticksOn) DsController.onEvent(Ui.ToggleSticks); if (!DsController.state.value.isFlight) lesson(LessonId.L1); "S14" }
            "S15" -> { lesson(LessonId.L1); run0("fly hover"); DsController.onEvent(Ui.Pause); "S15" }
            else -> "state S01..S15 | S06A"
        }
    }

    private fun run0(line: String): String {
        val c = ctx ?: return "no context"
        val parts = line.split(" ", limit = 2)
        run(c, parts[0], parts.getOrNull(1), null)
        return line
    }

    private var ctx: Context? = null
}
