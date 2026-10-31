package io.embodit.dronesight.ui

import io.embodit.dronesight.domain.FlightMode
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.Orientation
import io.embodit.dronesight.domain.Outcome
import io.embodit.dronesight.domain.StickInvert
import io.embodit.dronesight.domain.Task
import io.embodit.dronesight.domain.Tolerance
import io.embodit.dronesight.domain.Touchdown
import io.embodit.dronesight.domain.WindLevel

/** The control panel's pages and the flight phases (design states S00-S15). */
enum class Screen { STARTING, WELCOME, FINDING_FLOOR, PLACING, PICKER, READY, FLYING, LANDING, RESULT, CRASH, SETTINGS, EXIT_CONFIRM, BLOCKED, PAUSED }

enum class Lang { SYSTEM, ZH, EN }

enum class HintMode { ON, OFF, FIRST_TWO }

enum class VStickMode { AUTO, ALWAYS, OFF }

/** Persisted settings (design persistence.what). */
data class Prefs(
    val volume: Int = 60,
    val muted: Boolean = false,
    val mode: FlightMode = FlightMode.GPS,
    val wind: WindLevel = WindLevel.NONE,
    val tolerance: Tolerance = Tolerance.LENIENT,
    val poleDist: Float = 2.5f,
    val hoverH: Float = 1.5f,
    val ceiling: Float = 2.6f,
    val floorOffset: Float = 0f,
    val seated: Boolean = false,
    val invert: StickInvert = StickInvert(),
    val slow: Boolean = false,
    val hints: HintMode = HintMode.FIRST_TWO,
    val vsticks: VStickMode = VStickMode.AUTO,
    val altLine: Boolean = true,
    val bigTag: Boolean = false,
    val lang: Lang = Lang.SYSTEM,
)

/** Hint line keys (design copy.hints). */
enum class Hint {
    NONE, STARTING, S02, S02_TIMEOUT, S03, S03_NO_CONTROLLER,
    S05_L1, S05_L2, S05_L3, S05_L4, S05_L5, S05_L6, S05_L7, S05_F1, S05_F2,
    NOSE_IN, HOVER_IN, HOVER_OUT, DONE, L4, L5, OUT, LAND_SOFT, LAND_HEAVY, CRASH, PAUSED, WAYPOINT, ATTI, NEAR_YOU, CHAIN_NEXT, NOSE_HINT,
}

/** What the panels show about the flight; changes a few times a second at most (never per frame through Compose). */
data class FlightSnap(
    val task: Task = Task.FREE,
    val value: Int = 0,
    val target: Int = 0,
    val gate: Int = 0,
    val gates: Int = 0,
    val chainIndex: Int = 0,
    val chainSize: Int = 1,
    val inBox: Boolean = false,
    val outside: Boolean = false,
    val nearYou: Boolean = false,
    val heightDm: Int = 0,
    val orientation: Orientation = Orientation.TAIL_IN,
    val gusting: Boolean = false,
    val timeS: Int = 0,
    val airborne: Boolean = false,
    val landingOffsetCm: Int? = null,
    val touchdown: Touchdown? = null,
)

data class DsUiState(
    val screen: Screen = Screen.STARTING,
    /** Where settings / pause return to. */
    val back: Screen = Screen.PICKER,
    val lesson: LessonId = LessonId.L1,
    val prefs: Prefs = Prefs(),
    val best: Map<LessonId, Float> = emptyMap(),
    val passed: Set<LessonId> = emptySet(),
    val waypointRound: Int = 1,
    val welcomeSeen: Boolean = false,
    /** Hardware controllers deliver (ControllerTrackingProvider SUPPORTED). */
    val hasController: Boolean = true,
    /** null = not looked yet, true = FLOOR plane found, false = estimated. */
    val floorFound: Boolean? = null,
    val hint: Hint = Hint.NONE,
    val hintArg: String = "",
    val snap: FlightSnap = FlightSnap(),
    val outcome: Outcome? = null,
    val outcomeLesson: LessonId = LessonId.L1,
    val blockReason: String? = null,
    val resetConfirm: Boolean = false,
    val vsticksOn: Boolean = false,
    /** Course placed at least once (S03 has an end). */
    val placed: Boolean = false,
    /** S03 was entered from settings / picker "重摆场地". */
    val replaceFrom: Screen? = null,
    /** Bumped when the panel should be re-placed in front of the head (first show, "叫回面板"). */
    val panelEpoch: Int = 0,
    /** Bumped when a new flight session starts (scene resets the drone). */
    val sessionEpoch: Int = 0,
    /** D27 adaptive level of the current lesson and whether the last result loosened it. */
    val leniencyLevel: Int = 0,
    val loosened: Boolean = false,
    val loading: Boolean = true,
) {
    val isFlight: Boolean
        get() = screen == Screen.READY || screen == Screen.FLYING || screen == Screen.LANDING || screen == Screen.CRASH || screen == Screen.PAUSED

    /** Direction hints (arrows on the floor) for the current lesson. */
    val directionHints: Boolean
        get() = when (prefs.hints) { HintMode.ON -> true; HintMode.OFF -> false; HintMode.FIRST_TWO -> lesson == LessonId.L1 || lesson == LessonId.L2 }

    /** Virtual stick panel shown (S14): auto without controllers, or switched on; only in flight-related pages. */
    val sticksVisible: Boolean
        get() = vsticksOn && (screen == Screen.PLACING || isFlight)

    /** Design state id, overlays appended: "S06+S06a+S14". */
    val designState: String
        get() {
            val main =
                when (screen) {
                    Screen.STARTING -> "S00"
                    Screen.WELCOME -> "S01"
                    Screen.FINDING_FLOOR -> "S02"
                    Screen.PLACING -> "S03"
                    Screen.PICKER -> "S04"
                    Screen.READY -> "S05"
                    Screen.FLYING -> if (lesson == LessonId.F2) "S10" else "S06"
                    Screen.LANDING -> "S07"
                    Screen.RESULT -> "S08"
                    Screen.CRASH -> "S09"
                    Screen.SETTINGS -> "S11"
                    Screen.EXIT_CONFIRM -> "S12"
                    Screen.BLOCKED -> "S13"
                    Screen.PAUSED -> "S15"
                }
            val sb = StringBuilder(main)
            if (snap.outside && (screen == Screen.FLYING)) sb.append("+S06a")
            if (sticksVisible) sb.append("+S14")
            return sb.toString()
        }
}
