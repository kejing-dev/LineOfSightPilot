package io.embodit.dronesight.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import io.embodit.dronesight.domain.CourseGeometry
import io.embodit.dronesight.domain.Ev
import io.embodit.dronesight.domain.FlightMode
import io.embodit.dronesight.domain.Grade
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.Leniency
import io.embodit.dronesight.domain.Orientation
import io.embodit.dronesight.domain.Session
import io.embodit.dronesight.domain.Task
import io.embodit.dronesight.domain.Tolerance
import io.embodit.dronesight.domain.Touchdown
import io.embodit.dronesight.domain.Waypoints
import io.embodit.dronesight.domain.WindLevel
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.platform.Sound
import io.embodit.dronesight.platform.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the user (or a debug hook, through the same events) can do. */
sealed class Ui {
    object WelcomeOk : Ui()
    data class FloorFound(val y: Float) : Ui()
    object FloorTimeout : Ui()
    /** Trigger / pinch on the floor / panel "放在这里". */
    object PlaceHere : Ui()
    data class Pick(val lesson: LessonId) : Ui()
    object TakeOff : Ui()
    object Pause : Ui()
    object Resume : Ui()
    /** A / X controller key: pause or continue. */
    object PauseKey : Ui()
    object PrevLesson : Ui()
    object NextLesson : Ui()
    object OpenSettings : Ui()
    object CloseSettings : Ui()
    object Replace : Ui()
    object RecallPanel : Ui()
    object Retry : Ui()
    object Next : Ui()
    object ToPicker : Ui()
    object ToggleSticks : Ui()
    object ToggleHints : Ui()
    data class Mode(val mode: FlightMode) : Ui()
    data class Wind(val wind: WindLevel) : Ui()
    data class Tol(val tolerance: Tolerance) : Ui()
    data class SetPrefs(val why: String, val f: (Prefs) -> Prefs) : Ui()
    object ResetAsk : Ui()
    object ResetCancel : Ui()
    object ResetYes : Ui()
    object QuitAsk : Ui()
    object QuitCancel : Ui()
    object QuitYes : Ui()
    object BlockRetry : Ui()
    data class Background(val on: Boolean) : Ui()
}

/**
 * The state machine of the app (design states S00-S15, interactions I1-I22). Panel controls, scene callbacks and the
 * debug receiver all come in through [onEvent] / the `on*` callbacks; the running [Session] is stepped by the scene's
 * frame system and reports back with [onSessionEvents] / [onSnap]. Main thread only.
 */
object DsController {
    private val _state = MutableStateFlow(DsUiState())
    val state: StateFlow<DsUiState> = _state

    /** The flight of the current lesson (READY..RESULT); the scene steps it. */
    var session: Session? = null
        private set

    var activity: Activity? = null
    private var store: Store? = null
    private var sound: Sound? = null
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private val nearCount = HashMap<LessonId, Int>()
    private val levels = HashMap<LessonId, Int>()
    private var stickOverride: Boolean? = null
    private var chainHintUntil = 0L

    val soundOrNull: Sound?
        get() = sound

    fun start(context: Context) {
        activity = findActivity(context)
        if (started) return
        started = true
        val st = Store(context.applicationContext)
        store = st
        val prefs = st.prefs()
        sound = Sound(context.applicationContext).also { it.setVolume(prefs.volume, prefs.muted); it.start() }
        _state.value = DsUiState(prefs = prefs, best = st.best(), passed = st.passed(), waypointRound = st.round, welcomeSeen = st.welcomeSeen, hint = Hint.STARTING)
        DsLog.i("CONTROLLER start state=${_state.value.designState} prefs=$prefs")
    }

    private fun findActivity(c: Context): Activity? {
        var x: Context? = c
        while (x != null) {
            if (x is Activity) return x
            x = (x as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun set(why: String, f: (DsUiState) -> DsUiState) {
        val before = _state.value
        val after = f(before)
        if (after == before) return
        _state.value = after
        if (after.designState != before.designState || after.screen != before.screen || after.hint != before.hint) {
            DsLog.i("STATE ${after.designState} screen=${after.screen} lesson=${after.lesson} hint=${after.hint} why=$why")
        }
    }

    private fun course(p: Prefs): CourseGeometry = CourseGeometry(p.poleDist, p.hoverH, p.tolerance)

    // ============================================================================================== scene callbacks

    /** The scene is built and the head pose is valid: S00 -> S01 (first run) or S02. */
    fun onSceneReady() {
        val s = _state.value
        if (s.screen != Screen.STARTING) return
        set("ready") { it.copy(loading = false, screen = if (it.welcomeSeen) Screen.FINDING_FLOOR else Screen.WELCOME, hint = if (it.welcomeSeen) Hint.S02 else Hint.NONE) }
    }

    /** Head tracking (or Full Space) unavailable for 2 s: S13. */
    fun onBlocked(reason: String) {
        val s = _state.value
        if (s.screen == Screen.BLOCKED) return
        if (s.screen == Screen.FLYING) session?.let { /* frozen while blocked */ }
        set("blocked $reason") { it.copy(screen = Screen.BLOCKED, back = if (it.screen == Screen.FLYING) Screen.PAUSED else it.screen, blockReason = reason, loading = false) }
    }

    fun onControllerPresence(has: Boolean) {
        val forced = stickOverride
        val h = forced ?: has
        if (h == _state.value.hasController) return
        set("controllers=$h") { s -> s.copy(hasController = h, vsticksOn = sticksOn(s.prefs.vsticks, h, null)) }
    }

    private fun sticksOn(mode: VStickMode, has: Boolean, manual: Boolean?): Boolean =
        manual ?: when (mode) { VStickMode.ALWAYS -> true; VStickMode.OFF -> false; VStickMode.AUTO -> !has }

    /** Live snapshot of the flight (changes a few times a second). Also drives the hint line while flying. */
    fun onSnap(snap: FlightSnap) {
        set("snap") { s ->
            val h = if (s.screen == Screen.FLYING) flyingHint(s, snap) else s.hint
            s.copy(snap = snap, hint = h, hintArg = if (h == Hint.CHAIN_NEXT) s.hintArg else if (h == s.hint) s.hintArg else "")
        }
    }

    private fun flyingHint(s: DsUiState, snap: FlightSnap): Hint {
        val sess = session ?: return s.hint
        if (System.currentTimeMillis() < chainHintUntil) return Hint.CHAIN_NEXT
        return when {
            snap.outside -> Hint.OUT
            snap.nearYou -> Hint.NEAR_YOU
            sess.goalReached -> Hint.DONE
            s.lesson == LessonId.F2 -> Hint.WAYPOINT
            sess.flight.mode == FlightMode.ATTI && s.lesson != LessonId.L7 -> Hint.ATTI
            snap.task == Task.EIGHT -> if (s.prefs.tolerance == Tolerance.STANDARD) Hint.NOSE_HINT else Hint.L4
            snap.task == Task.LAND -> Hint.L5
            snap.orientation == Orientation.NOSE_IN && snap.task == Task.HOVER_NOSE -> Hint.NOSE_IN
            snap.task == Task.HOVER_TAIL || snap.task == Task.HOVER_NOSE || snap.task == Task.HOVER_ANY || snap.task == Task.SPIN -> if (snap.inBox) Hint.HOVER_IN else if (snap.airborne && snap.heightDm >= 3) Hint.HOVER_OUT else s.hint
            else -> s.hint
        }
    }

    /** What happened in the session's last step (frame system). */
    fun onSessionEvents(evs: List<Ev>) {
        for (e in evs) {
            when (e) {
                Ev.TookOff -> {
                    sound?.takeoff()
                    set("tookOff") { it.copy(screen = Screen.FLYING, hint = Hint.NONE) }
                }
                Ev.BoxIn -> sound?.ding()
                Ev.BoxOut -> sound?.tick()
                is Ev.GatePassed, is Ev.Ring -> sound?.ding()
                is Ev.GateWrong -> sound?.tick()
                is Ev.TaskDone -> sound?.ding()
                is Ev.ChainNext -> {
                    chainHintUntil = System.currentTimeMillis() + 3000
                    set("chain ${e.next}") { it.copy(hint = Hint.CHAIN_NEXT, hintArg = e.next.name) }
                }
                Ev.OutOn -> sound?.beep()
                Ev.OutOff, Ev.TimeUp -> Unit
                is Ev.Landed -> {
                    sound?.landed(e.kind)
                    set("landed ${e.kind}") { it.copy(screen = Screen.LANDING, hint = if (e.kind == Touchdown.SOFT) Hint.LAND_SOFT else Hint.LAND_HEAVY) }
                    main.postDelayed(afterLanding, LANDING_MS)
                }
                Ev.Crash -> {
                    sound?.crash()
                    set("crash") { it.copy(screen = Screen.CRASH, hint = Hint.CRASH) }
                    main.postDelayed(afterCrash, CRASH_MS)
                }
                Ev.Finished -> {
                    val sc = _state.value.screen
                    // timeout (still in the air) -> straight to the result; a landing / crash waits for its own timer
                    if (sc == Screen.FLYING || sc == Screen.PAUSED) showResult("timeout")
                }
            }
        }
    }

    private val afterLanding = Runnable {
        if (_state.value.screen != Screen.LANDING) return@Runnable
        if (_state.value.lesson == LessonId.F1) newSession(LessonId.F1, "free landed") else showResult("landed")
    }

    private val afterCrash = Runnable {
        if (_state.value.screen != Screen.CRASH) return@Runnable
        if (_state.value.lesson == LessonId.F1) newSession(LessonId.F1, "free crash") else showResult("crash")
    }

    private fun showResult(why: String) {
        val sess = session ?: return
        val o = sess.outcome ?: return
        val id = sess.lesson
        // D27: two "almost" in a row on the same lesson loosen it one step (max 2)
        var loosened = false
        when (o.grade) {
            Grade.NEAR -> {
                val n = (nearCount[id] ?: 0) + 1
                if (n >= 2) {
                    val lv = ((levels[id] ?: 0) + 1).coerceAtMost(2)
                    loosened = lv != (levels[id] ?: 0)
                    levels[id] = lv
                    nearCount[id] = 0
                } else nearCount[id] = n
            }
            Grade.PASS -> nearCount[id] = 0
            Grade.RETRY -> Unit
        }
        if (loosened) DsLog.i("LENIENT lesson=$id level=${levels[id]}")
        // best scores
        val st = store
        var best = _state.value.best
        var passed = _state.value.passed
        var round = _state.value.waypointRound
        val score = o.score
        if (o.grade == Grade.PASS) passed = passed + id
        if (score != null) {
            val old = best[id]
            val better = when (id) { LessonId.L5, LessonId.F2, LessonId.L6, LessonId.L4 -> if (id == LessonId.L4) old == null || score > old else old == null || score < old; else -> old == null || score > old }
            if (better && (o.grade == Grade.PASS || id != LessonId.F2)) best = best + (id to score)
        }
        if (id == LessonId.L5 && o.grade == Grade.PASS && o.offsetM != null) best = best + (id to minOf(o.offsetM, best[id] ?: 99f))
        if (id == LessonId.F2 && o.grade == Grade.PASS) round += 1
        st?.saveBest(best, passed, round)
        DsLog.i("RESULT lesson=$id grade=${o.grade} diffs=${o.diffs} t=${"%.1f".format(o.timeS)} score=$score chain=${o.chain} why=$why")
        set("result $why") { it.copy(screen = Screen.RESULT, outcome = o, outcomeLesson = id, best = best, passed = passed, waypointRound = round, loosened = loosened, leniencyLevel = levels[id] ?: 0, hint = Hint.NONE) }
    }

    // ============================================================================================== events

    fun onEvent(e: Ui) {
        val s = _state.value
        when (e) {
            Ui.WelcomeOk -> {
                store?.welcomeSeen = true
                set("welcomeOk") { it.copy(welcomeSeen = true, screen = Screen.FINDING_FLOOR, hint = Hint.S02) }
            }
            is Ui.FloorFound -> if (s.screen == Screen.FINDING_FLOOR) {
                main.removeCallbacks(floorTimeoutEnd)
                set("floor y=${e.y}") { it.copy(floorFound = true, screen = Screen.PLACING, hint = placingHint(it)) }
            }
            Ui.FloorTimeout -> if (s.screen == Screen.FINDING_FLOOR && s.hint != Hint.S02_TIMEOUT) {
                set("floorTimeout") { it.copy(floorFound = false, hint = Hint.S02_TIMEOUT) }
                main.postDelayed(floorTimeoutEnd, 1500)
            }
            Ui.PlaceHere -> if (s.screen == Screen.PLACING) {
                val from = s.replaceFrom
                val sess = session
                val target = if (from != null && sess != null && (from == Screen.READY || from == Screen.RESULT || from == Screen.PAUSED || from == Screen.FLYING)) Screen.READY else Screen.PICKER
                set("placed") { it.copy(placed = true, replaceFrom = null, screen = target, hint = if (target == Screen.READY) readyHint(it.lesson) else Hint.NONE) }
                if (target == Screen.READY) newSession(s.lesson, "re-placed")
            }
            is Ui.Pick -> if (s.screen == Screen.PICKER || s.screen == Screen.RESULT) newSession(e.lesson, "pick")
            Ui.TakeOff -> if (s.screen == Screen.READY) {
                val ok = session?.takeOff() == true
                DsLog.i("TAKEOFF ok=$ok lesson=${s.lesson}")
                if (ok) sound?.spinUp()
            }
            Ui.Pause -> if (s.screen == Screen.FLYING) set("pause") { it.copy(screen = Screen.PAUSED, back = Screen.FLYING, hint = Hint.PAUSED) }
            Ui.Resume -> if (s.screen == Screen.PAUSED) set("resume") { it.copy(screen = Screen.FLYING, hint = Hint.NONE) }
            Ui.PauseKey -> if (s.screen == Screen.FLYING) onEvent(Ui.Pause) else if (s.screen == Screen.PAUSED) onEvent(Ui.Resume)
            Ui.PrevLesson -> if (s.screen == Screen.READY || s.screen == Screen.RESULT) s.lesson.prev()?.let { newSession(it, "prev") }
            Ui.NextLesson -> if (s.screen == Screen.READY || s.screen == Screen.RESULT) s.lesson.next()?.let { newSession(it, "next") }
            Ui.OpenSettings -> when (s.screen) {
                Screen.SETTINGS -> onEvent(Ui.CloseSettings)
                Screen.EXIT_CONFIRM, Screen.BLOCKED, Screen.STARTING -> Unit
                else -> {
                    val back = if (s.screen == Screen.FLYING) Screen.PAUSED else s.screen
                    set("settings") { it.copy(screen = Screen.SETTINGS, back = back, resetConfirm = false, hint = if (back == Screen.PAUSED) Hint.PAUSED else it.hint) }
                }
            }
            Ui.CloseSettings -> if (s.screen == Screen.SETTINGS) set("closeSettings") { it.copy(screen = it.back, resetConfirm = false) }
            Ui.Replace -> if (s.screen == Screen.SETTINGS || s.screen == Screen.PICKER) {
                val from = if (s.screen == Screen.SETTINGS) s.back else Screen.PICKER
                // a flight in the air is ended (the course moves): the drone goes back on the pad
                if (from == Screen.PAUSED || from == Screen.FLYING) session = null
                set("replace") { it.copy(screen = Screen.PLACING, replaceFrom = from, hint = placingHint(it)) }
            }
            Ui.RecallPanel -> set("recall") { it.copy(panelEpoch = it.panelEpoch + 1) }
            Ui.Retry -> if (s.screen == Screen.RESULT) newSession(s.outcomeLesson, "retry")
            Ui.Next -> if (s.screen == Screen.RESULT) {
                val n = if (s.outcomeLesson == LessonId.F2) LessonId.F2 else s.outcomeLesson.next()
                if (n == null || n == LessonId.F1) set("next->picker") { it.copy(screen = Screen.PICKER) } else newSession(n, "next")
            }
            Ui.ToPicker -> if (s.screen == Screen.RESULT || s.screen == Screen.READY) {
                session = null
                set("picker") { it.copy(screen = Screen.PICKER, hint = Hint.NONE) }
            }
            Ui.ToggleSticks -> set("sticks") { it.copy(vsticksOn = !it.vsticksOn) }
            Ui.ToggleHints -> prefs("hints") { it.copy(hints = if (s.directionHints) HintMode.OFF else HintMode.ON) }
            is Ui.Mode -> {
                session?.flight?.mode = e.mode
                set("mode ${e.mode}") { it.copy(hint = if (e.mode == FlightMode.ATTI && it.screen == Screen.FLYING) Hint.ATTI else it.hint) }
                prefs("mode") { it.copy(mode = e.mode) }
            }
            is Ui.Wind -> {
                session?.let { it.flight.wind = io.embodit.dronesight.domain.Wind(e.wind, windDir, System.nanoTime()) }
                prefs("wind") { it.copy(wind = e.wind) }
            }
            is Ui.Tol -> {
                prefs("tolerance") { it.copy(tolerance = e.tolerance) }
                if (s.screen == Screen.READY) newSession(s.lesson, "tolerance")
            }
            is Ui.SetPrefs -> {
                prefs(e.why, e.f)
                val p = _state.value.prefs
                sound?.setVolume(p.volume, p.muted)
                session?.flight?.let { fl ->
                    fl.slow = p.slow
                    fl.params = fl.params.copy(ceilingM = p.ceiling)
                }
                if (e.why == "vsticks") set("vsticksMode") { it.copy(vsticksOn = sticksOn(p.vsticks, it.hasController, null)) }
                if (s.screen == Screen.READY && (e.why == "poleDist" || e.why == "hoverH")) newSession(s.lesson, e.why)
            }
            Ui.ResetAsk -> set("resetAsk") { it.copy(resetConfirm = true) }
            Ui.ResetCancel -> set("resetCancel") { it.copy(resetConfirm = false) }
            Ui.ResetYes -> {
                store?.clearProgress()
                nearCount.clear()
                levels.clear()
                set("reset") { it.copy(resetConfirm = false, best = emptyMap(), passed = emptySet(), waypointRound = 1) }
            }
            Ui.QuitAsk -> if (s.screen == Screen.SETTINGS) set("quitAsk") { it.copy(screen = Screen.EXIT_CONFIRM) }
            Ui.QuitCancel -> if (s.screen == Screen.EXIT_CONFIRM) set("quitCancel") { it.copy(screen = Screen.SETTINGS) }
            Ui.QuitYes -> quit()
            Ui.BlockRetry -> if (s.screen == Screen.BLOCKED) set("blockRetry") { it.copy(screen = Screen.STARTING, blockReason = null, hint = Hint.STARTING) }
            is Ui.Background -> if (e.on && s.screen == Screen.FLYING) set("background") { it.copy(screen = Screen.PAUSED, back = Screen.FLYING, hint = Hint.PAUSED) }
        }
    }

    private val floorTimeoutEnd = Runnable {
        val s = _state.value
        if (s.screen == Screen.FINDING_FLOOR) set("floorEstimated") { it.copy(screen = Screen.PLACING, hint = placingHint(it)) }
    }

    private fun placingHint(s: DsUiState) = if (s.hasController) Hint.S03 else Hint.S03_NO_CONTROLLER

    /** Wind direction of the placed course (course-local yaw, drawn once per placement). */
    var windDir: Float = 0.7f
        private set

    fun onCoursePlaced(windYaw: Float) {
        windDir = windYaw
    }

    private fun readyHint(id: LessonId): Hint =
        when (id) {
            LessonId.L1 -> Hint.S05_L1
            LessonId.L2 -> Hint.S05_L2
            LessonId.L3 -> Hint.S05_L3
            LessonId.L4 -> Hint.S05_L4
            LessonId.L5 -> Hint.S05_L5
            LessonId.L6 -> Hint.S05_L6
            LessonId.L7 -> Hint.S05_L7
            LessonId.F1 -> Hint.S05_F1
            LessonId.F2 -> Hint.S05_F2
        }

    /** A fresh flight of [id]: the drone back on the pad (S05). L7 brings its own defaults (ATTI + light wind). */
    private fun newSession(id: LessonId, why: String) {
        main.removeCallbacks(afterLanding)
        main.removeCallbacks(afterCrash)
        val s = _state.value
        val p = s.prefs
        val lv = levels[id] ?: 0
        val rings = if (id == LessonId.F2) Waypoints.generate(s.waypointRound) else emptyList()
        val sess = Session(id, course(p), Leniency(p.tolerance, lv), rings)
        val mode = if (id == LessonId.L7) FlightMode.ATTI else p.mode
        val wind = if (id == LessonId.L7) WindLevel.LIGHT else if (id == LessonId.F2) maxOf(p.wind, Waypoints.wind(s.waypointRound)) else p.wind
        sess.flight.mode = mode
        sess.flight.slow = p.slow
        sess.flight.params = sess.flight.params.copy(ceilingM = p.ceiling)
        sess.flight.wind = io.embodit.dronesight.domain.Wind(wind, windDir, id.ordinal * 31L + s.sessionEpoch)
        session = sess
        DsLog.i("SESSION lesson=$id mode=$mode wind=$wind level=$lv rings=${rings.size} why=$why")
        set("session $why") { it.copy(screen = Screen.READY, lesson = id, outcome = null, snap = FlightSnap(), sessionEpoch = it.sessionEpoch + 1, leniencyLevel = lv, loosened = false, hint = readyHint(id)) }
    }

    /** The mode / wind shown on the panel (the session's own, L7 has its defaults). */
    fun currentMode(): FlightMode = session?.flight?.mode ?: _state.value.prefs.mode

    fun currentWind(): WindLevel = session?.flight?.wind?.level ?: _state.value.prefs.wind

    private fun prefs(why: String, f: (Prefs) -> Prefs) {
        val before = _state.value.prefs
        val after = f(before)
        if (after == before) return
        store?.savePrefs(after)
        set("prefs $why") { it.copy(prefs = after) }
    }

    private fun quit() {
        DsLog.i("EXIT confirmed")
        session = null
        sound?.release()
        sound = null
        quitHook?.invoke()
        val a = activity
        runCatching { a?.finishAndRemoveTask() }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }

    /** The scene releases trackers / audio / planes before the process ends. */
    var quitHook: (() -> Unit)? = null

    // ============================================================================================== debug seams

    fun debugNoController(on: Boolean) {
        stickOverride = if (on) false else null
        set("nocontroller=$on") { s -> val has = if (on) false else true; s.copy(hasController = has, vsticksOn = sticksOn(s.prefs.vsticks, has, null), hint = if (s.screen == Screen.PLACING) (if (has) Hint.S03 else Hint.S03_NO_CONTROLLER) else s.hint) }
    }

    fun debugFirstRun() {
        store?.clearAll()
        session = null
        set("firstRun") { DsUiState(screen = Screen.WELCOME, loading = false, hasController = it.hasController, vsticksOn = it.vsticksOn, panelEpoch = it.panelEpoch) }
    }

    fun debugBest(id: LessonId, v: Float) {
        val best = _state.value.best + (id to v)
        val passed = _state.value.passed + id
        store?.saveBest(best, passed, _state.value.waypointRound)
        set("best") { it.copy(best = best, passed = passed) }
    }

    fun debugRound(n: Int) {
        set("round") { it.copy(waypointRound = n.coerceAtLeast(1)) }
    }

    private const val LANDING_MS = 1200L
    private const val CRASH_MS = 2000L
}
