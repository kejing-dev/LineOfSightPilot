package io.embodit.dronesight.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** The seven lessons and two free modes (design contentInventory). [limitS] counts from the take-off. */
enum class LessonId(val limitS: Float?, val tasks: List<Task>) {
    L1(90f, listOf(Task.HOVER_TAIL)),
    L2(90f, listOf(Task.HOVER_NOSE)),
    L3(90f, listOf(Task.SPIN)),
    L4(150f, listOf(Task.EIGHT)),
    L5(60f, listOf(Task.LAND)),
    L6(300f, listOf(Task.HOVER_TAIL, Task.HOVER_NOSE, Task.SPIN, Task.EIGHT, Task.LAND)),
    L7(75f, listOf(Task.HOVER_ANY)),
    F1(null, listOf(Task.FREE)),
    F2(null, listOf(Task.WAYPOINTS));

    val judged: Boolean
        get() = this != F1

    val isLesson: Boolean
        get() = this != F1 && this != F2

    fun next(): LessonId? = when (this) { L1 -> L2; L2 -> L3; L3 -> L4; L4 -> L5; L5 -> L6; L6 -> L7; L7 -> null; F1 -> F2; F2 -> null }

    fun prev(): LessonId? = when (this) { L1 -> null; L2 -> L1; L3 -> L2; L4 -> L3; L5 -> L4; L6 -> L5; L7 -> L6; F1 -> L7; F2 -> F1 }

    companion object {
        val all = values().toList()
    }
}

enum class Task { HOVER_TAIL, HOVER_NOSE, HOVER_ANY, SPIN, EIGHT, LAND, FREE, WAYPOINTS }

enum class Grade { PASS, NEAR, RETRY }

/** One "差在哪" line (design copy.results.diffs); the panel turns it into words. */
sealed class Diff {
    data class High(val m: Float) : Diff()
    data class Low(val m: Float) : Diff()
    data class OutCount(val n: Int) : Diff()
    data class SpinShort(val deg: Int) : Diff()
    data class GateMissed(val n: Int) : Diff()
    data class LandOff(val m: Float) : Diff()
    object LandHard : Diff()
    object LandHeavy : Diff()
    data class Timeout(val s: Int) : Diff()
    object NoseOff : Diff()
    /** Pass summary: seconds in the box and the largest height miss. */
    data class HoverSummary(val s: Int, val maxOffM: Float) : Diff()
    data class SpinSummary(val deg: Int) : Diff()
    data class LandSummary(val offM: Float) : Diff()
    data class Time(val s: Float) : Diff()
}

data class Outcome(
    val grade: Grade,
    val diffs: List<Diff>,
    val timeS: Float,
    /** L6: per task done or not. */
    val chain: List<Boolean> = emptyList(),
    val touchdown: Touchdown? = null,
    val offsetM: Float? = null,
    /** Best-score value for the picker chip: seconds in the box / degrees / gates / landing offset / waypoint time. */
    val score: Float? = null,
)

/** Things that happened during one step (sounds, flashes, hints). */
sealed class Ev {
    object TookOff : Ev()
    object BoxIn : Ev()
    object BoxOut : Ev()
    data class TaskDone(val index: Int) : Ev()
    data class ChainNext(val next: Task) : Ev()
    data class GatePassed(val n: Int) : Ev()
    data class GateWrong(val n: Int) : Ev()
    data class Ring(val n: Int) : Ev()
    data class Landed(val kind: Touchdown, val offsetM: Float) : Ev()
    object Crash : Ev()
    object TimeUp : Ev()
    object OutOn : Ev()
    object OutOff : Ev()
    object Finished : Ev()
}

/**
 * Lenient-judging knobs (profile D27): the base tolerance of the design (lenient by default) plus an adaptive level
 * 0..2 that the controller raises after two "almost" results in a row on the same lesson.
 */
data class Leniency(val tolerance: Tolerance = Tolerance.LENIENT, val level: Int = 0) {
    /** Extra slack around the drawn hover box (metres). */
    val boxGrow: Float
        get() = 0.08f * level

    /** Tail-in counts below this nose angle; nose-in above 180 - it. */
    val orientDeg: Float
        get() = (if (tolerance == Tolerance.LENIENT) 60f else 45f) + 10f * level

    val holdS: Float
        get() = 20f - 2.5f * level

    val holdS7: Float
        get() = 15f - 2f * level

    val spinNeedDeg: Float
        get() = 350f - 25f * level

    val gateGrow: Float
        get() = (if (tolerance == Tolerance.LENIENT) 0.15f else 0f) + 0.12f * level

    val padGrow: Float
        get() = 0.1f * level

    val climbBeforeLandM: Float
        get() = (if (tolerance == Tolerance.LENIENT) 1.2f else 1.4f) - 0.1f * level

    val judgeNose: Boolean
        get() = tolerance == Tolerance.STANDARD
}

/** Progress of the current task for the panel chip and the 3D progress arc. */
data class Progress(val task: Task, val value: Float, val target: Float, val inBox: Boolean = false, val gate: Int = 0, val gates: Int = 0) {
    val fraction: Float
        get() = if (target <= 0f) 0f else (value / target).coerceIn(0f, 1f)
}

/**
 * One flight of one lesson: the [FlightModel] plus the judges (design rules.judge, D27 lenient). Time runs from the
 * take-off and stops while paused (the caller does not step a paused session). Every branch ends: goal + landing,
 * early landing, hard landing, time limit (in or out of the box, out of the field, mid-chain, after the goal without
 * landing), all checked in [step]. Pure Kotlin.
 */
class Session(
    val lesson: LessonId,
    val course: CourseGeometry,
    val leniency: Leniency = Leniency(course.tolerance),
    val rings: List<WaypointRing> = emptyList(),
) {
    val flight = FlightModel()
    var t = 0f
        private set
    var started = false
        private set
    var taskIndex = 0
        private set
    var outcome: Outcome? = null
        private set

    /** Goal of the current (last) task reached: "bring it back to the pad". */
    var goalReached = false
        private set
    private val done = BooleanArray(lesson.tasks.size)

    // hover
    var boxTime = 0f
        private set
    var inBox = false
        private set
    private var outCount = 0
    private var maxOffAbove = 0f
    private var maxOffBelow = 0f
    private var maxInBoxOff = 0f

    // spin
    var spinDeg = 0f
        private set
    private var lastYaw = 0f

    // eight
    var nextGate = 1
        private set
    var wrongGates = 0
        private set
    private var noseTime = 0f
    private var movingTime = 0f

    // waypoints
    var nextRing = 0
        private set

    // field
    var outside = false
        private set
    private var insideFor = 0f
    private var prev: Vec3 = Vec3.ZERO

    val task: Task
        get() = lesson.tasks[taskIndex.coerceAtMost(lesson.tasks.size - 1)]

    fun progress(): Progress =
        when (task) {
            Task.HOVER_TAIL, Task.HOVER_NOSE -> Progress(task, boxTime, leniency.holdS, inBox)
            Task.HOVER_ANY -> Progress(task, boxTime, leniency.holdS7, inBox)
            Task.SPIN -> Progress(task, abs(spinDeg), leniency.spinNeedDeg, inBox)
            Task.EIGHT -> Progress(task, (nextGate - 1).toFloat(), 4f, gate = nextGate, gates = 4)
            Task.LAND -> Progress(task, flight.maxHeight, leniency.climbBeforeLandM)
            Task.WAYPOINTS -> Progress(task, nextRing.toFloat(), rings.size.toFloat(), gate = nextRing + 1, gates = rings.size)
            Task.FREE -> Progress(task, 0f, 0f)
        }

    /** After a debug placement of the drone: the spin judge starts from the new heading. */
    fun resync() {
        lastYaw = flight.yaw
        prev = flight.pos
    }

    fun takeOff(): Boolean {
        if (outcome != null) return false
        val ok = flight.takeOff()
        if (ok && !started) {
            started = true
            t = 0f
            prev = flight.pos
        }
        return ok
    }

    /**
     * Advances [dt] s. [stick] is already shaped, [head] is your head in course-local metres (for tail-in / nose-in).
     */
    fun step(dt: Float, stick: Sticks, head: Vec3): List<Ev> {
        val evs = ArrayList<Ev>(2)
        if (outcome != null || dt <= 0f) return evs
        val before = prev
        if (flight.phase != FlightPhase.AIR) lastYaw = flight.yaw
        val td = flight.step(dt, if (flight.phase == FlightPhase.AIR) stick else Sticks.ZERO)
        if (started) t += dt
        if (!announced && flight.airborne) {
            announced = true
            evs += Ev.TookOff
        }
        val p = flight.pos
        if (flight.phase == FlightPhase.AIR || td != null) judgeAir(dt, before, p, head, evs)
        field(dt, evs)
        prev = p
        if (td != null) {
            landed(td, evs)
            return evs
        }
        // time limit: every branch of an unfinished flight (in / out of the box, outside the field, mid-chain)
        val limit = lesson.limitS
        if (started && limit != null && t >= limit && outcome == null) {
            evs += Ev.TimeUp
            finish(if (allGoalsDone()) passOutcome(null, null) else Outcome(Grade.RETRY, listOf(Diff.Timeout(limit.toInt())) + missing(), t, chainFlags(), score = scoreNow()), evs)
        }
        return evs
    }

    private fun allGoalsDone(): Boolean {
        val tasks = lesson.tasks
        for (i in tasks.indices) if (tasks[i] != Task.LAND && !done[i]) return false
        return tasks.last() != Task.LAND
    }

    private fun judgeAir(dt: Float, a: Vec3, b: Vec3, head: Vec3, evs: MutableList<Ev>) {
        when (task) {
            Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY, Task.SPIN -> hover(dt, b, head, evs)
            Task.EIGHT -> eight(dt, a, b, evs)
            Task.WAYPOINTS -> ringsStep(dt, a, b, evs)
            Task.LAND, Task.FREE -> Unit
        }
    }

    private fun orientOk(head: Vec3): Boolean {
        val ang = Orient.angleDeg(flight.yaw, flight.pos, head)
        return when (task) {
            Task.HOVER_TAIL -> ang <= leniency.orientDeg
            Task.HOVER_NOSE -> ang >= 180f - leniency.orientDeg
            else -> true
        }
    }

    private fun hover(dt: Float, p: Vec3, head: Vec3, evs: MutableList<Ev>) {
        val box = course.hoverBox
        val nowIn = box.contains(p, leniency.boxGrow)
        if (nowIn != inBox) {
            evs += if (nowIn) Ev.BoxIn else Ev.BoxOut
            if (!nowIn) outCount++
            inBox = nowIn
        }
        val off = box.heightOff(p)
        if (!nowIn) {
            if (off > 0) maxOffAbove = max(maxOffAbove, off)
            if (off < 0) maxOffBelow = max(maxOffBelow, -off)
        } else {
            maxInBoxOff = max(maxInBoxOff, abs(p.y - box.center.y))
        }
        if (task == Task.SPIN) {
            val d = Yaw.wrap(flight.yaw - lastYaw)
            lastYaw = flight.yaw
            if (nowIn) spinDeg += Math.toDegrees(d.toDouble()).toFloat()
            if (!done[taskIndex] && abs(spinDeg) >= leniency.spinNeedDeg) complete(evs)
            return
        }
        lastYaw = flight.yaw
        // the box time is cumulative: time outside is not counted, but nothing is reset (D27)
        if (nowIn && orientOk(head)) boxTime += dt
        val need = if (task == Task.HOVER_ANY) leniency.holdS7 else leniency.holdS
        if (!done[taskIndex] && boxTime >= need) complete(evs)
    }

    private fun eight(dt: Float, a: Vec3, b: Vec3, evs: MutableList<Ev>) {
        val step = Vec3(b.x - a.x, 0f, b.z - a.z)
        val speed = step.length / dt
        if (speed > 0.3f) {
            movingTime += dt
            val ang = Math.toDegrees(kotlin.math.acos((Yaw.dir(flight.yaw).dot(step) / step.length).coerceIn(-1f, 1f).toDouble()))
            if (ang <= 60.0) noseTime += dt
        }
        if (done[taskIndex]) return
        for (g in course.gates) {
            val forward = g.passed(a, b, leniency.gateGrow)
            if (!forward) continue
            if (g.index == nextGate && speed >= MIN_GATE_MPS) {
                evs += Ev.GatePassed(g.index)
                nextGate++
                if (nextGate > 4) {
                    val noseOk = !leniency.judgeNose || movingTime < 1f || noseTime / movingTime >= 0.7f
                    if (noseOk) complete(evs) else noseMiss = true
                }
                return
            } else if (g.index > nextGate && speed >= WRONG_GATE_MPS) {
                wrongGates++
                evs += Ev.GateWrong(g.index)
            }
        }
    }

    private var noseMiss = false
    private var announced = false

    private fun ringsStep(dt: Float, a: Vec3, b: Vec3, evs: MutableList<Ev>) {
        if (nextRing >= rings.size) return
        val r = rings[nextRing]
        if (r.passed(a, b, 0.1f)) {
            evs += Ev.Ring(nextRing + 1)
            nextRing++
            if (nextRing >= rings.size) complete(evs)
        }
    }

    private fun complete(evs: MutableList<Ev>) {
        done[taskIndex] = true
        evs += Ev.TaskDone(taskIndex)
        // L6: the next item starts in the air; only the last (landing) needs the floor
        if (taskIndex < lesson.tasks.size - 1) {
            taskIndex++
            resetTask()
            evs += Ev.ChainNext(task)
        } else {
            goalReached = true
        }
    }

    private fun resetTask() {
        boxTime = 0f
        inBox = false
        spinDeg = 0f
        lastYaw = flight.yaw
        nextGate = 1
        outCount = 0
        maxOffAbove = 0f
        maxOffBelow = 0f
        maxInBoxOff = 0f
    }

    private fun field(dt: Float, evs: MutableList<Ev>) {
        val out = flight.airborne && (flight.outOfBounds() || flight.atCeiling)
        if (out && !outside) {
            outside = true
            insideFor = 0f
            evs += Ev.OutOn
        } else if (!out && outside) {
            insideFor += dt
            if (insideFor >= 1f) {
                outside = false
                evs += Ev.OutOff
            }
        } else if (out) insideFor = 0f
    }

    private fun landed(td: Touchdown, evs: MutableList<Ev>) {
        val p = flight.pos
        val off = hypot(p.x, p.z)
        if (td == Touchdown.HARD) {
            evs += Ev.Crash
            if (lesson == LessonId.F1) {
                outcome = Outcome(Grade.RETRY, listOf(Diff.LandHard), t, touchdown = td)
                evs += Ev.Finished
                return
            }
            finish(Outcome(Grade.RETRY, listOf(Diff.LandHard), t, chainFlags(), td, off, scoreNow()), evs)
            return
        }
        evs += Ev.Landed(td, off)
        val o: Outcome =
            when {
                lesson == LessonId.F1 -> Outcome(Grade.PASS, emptyList(), t, touchdown = td, offsetM = off)
                task == Task.LAND -> landOutcome(td, off)
                goalReached -> passOutcome(td, off)
                else -> early(td, off)
            }
        finish(o, evs)
    }

    private fun landOutcome(td: Touchdown, off: Float): Outcome {
        val radius = course.padRadius + leniency.padGrow
        val heavy = if (td == Touchdown.HEAVY) listOf(Diff.LandHeavy) else emptyList()
        val climbed = flight.maxHeight >= leniency.climbBeforeLandM
        val chainBefore = lesson.tasks.size > 1 && (0 until lesson.tasks.size - 1).any { !done[it] }
        if (chainBefore) return early(td, off)
        if (!climbed) return Outcome(Grade.NEAR, listOf(Diff.Low(leniency.climbBeforeLandM + 0.3f - flight.maxHeight)) + heavy, t, chainFlags(), td, off, off)
        if (off <= radius) {
            done[taskIndex] = true
            return Outcome(Grade.PASS, listOf(Diff.LandSummary(off)) + heavy, t, chainFlags(), td, off, off)
        }
        val miss = off - radius
        return Outcome(if (off <= radius * 2f) Grade.NEAR else Grade.RETRY, listOf(Diff.LandOff(miss)) + heavy, t, chainFlags(), td, off, off)
    }

    private fun passOutcome(td: Touchdown?, off: Float?): Outcome {
        val heavy = if (td == Touchdown.HEAVY) listOf(Diff.LandHeavy) else emptyList()
        val first: Diff =
            when (lesson.tasks.last()) {
                Task.SPIN -> Diff.SpinSummary(abs(spinDeg).toInt())
                Task.EIGHT -> Diff.Time(t)
                Task.WAYPOINTS -> Diff.Time(t)
                else -> Diff.HoverSummary(boxTime.toInt(), maxInBoxOff)
            }
        return Outcome(Grade.PASS, listOf(first) + heavy, t, chainFlags(), td, off, scoreNow())
    }

    /** Landed (softly) before the goal: almost when at least half way, else again; with what was missing. */
    private fun early(td: Touchdown, off: Float): Outcome {
        val frac = if (lesson.tasks.size > 1) done.count { it } / (lesson.tasks.size - 1f) else progress().fraction
        val heavy = if (td == Touchdown.HEAVY) listOf(Diff.LandHeavy) else emptyList()
        return Outcome(if (frac >= 0.5f) Grade.NEAR else Grade.RETRY, missing() + heavy, t, chainFlags(), td, off, scoreNow())
    }

    /** What is missing for the current task (the "差在哪" lines). */
    fun missing(): List<Diff> =
        when (task) {
            Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> buildList {
                if (maxOffAbove > 0.05f && maxOffAbove >= maxOffBelow) add(Diff.High(maxOffAbove))
                else if (maxOffBelow > 0.05f) add(Diff.Low(maxOffBelow))
                if (outCount > 0) add(Diff.OutCount(outCount))
                if (isEmpty()) add(Diff.HoverSummary(boxTime.toInt(), maxInBoxOff))
            }
            Task.SPIN -> listOf(Diff.SpinShort((leniency.spinNeedDeg - abs(spinDeg)).coerceAtLeast(1f).toInt()))
            Task.EIGHT -> if (noseMiss) listOf(Diff.NoseOff) else listOf(Diff.GateMissed(nextGate.coerceAtMost(4)))
            Task.LAND -> listOf(Diff.Low((leniency.climbBeforeLandM - flight.maxHeight).coerceAtLeast(0.05f)))
            Task.WAYPOINTS -> listOf(Diff.GateMissed(nextRing + 1))
            Task.FREE -> emptyList()
        }

    private fun chainFlags(): List<Boolean> = if (lesson.tasks.size > 1) done.toList() else emptyList()

    private fun scoreNow(): Float? =
        when (lesson.tasks.last()) {
            Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY -> boxTime
            Task.SPIN -> abs(spinDeg)
            Task.EIGHT -> (nextGate - 1).toFloat()
            Task.LAND -> null
            Task.WAYPOINTS -> t
            Task.FREE -> null
        }

    private fun finish(o: Outcome, evs: MutableList<Ev>) {
        outcome = o
        evs += Ev.Finished
    }

    companion object {
        const val MIN_GATE_MPS = 0.15f
        const val WRONG_GATE_MPS = 0.4f
    }
}
