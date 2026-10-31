package io.embodit.dronesight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * D27 lenient judging, the pre-QA gate (prompts/build.md 2026-10-31): >= 10 deliberately sloppy flights (nose 15-20°
 * or 25-30° off, twice as slow, 10 cm off, in and out of the box) — every one must pass. Flights are recorded as
 * per-step drone positions / headings fed through the real [Session] judges (the drone is placed each step as a
 * sensor would report it, then the session steps).
 */
class SloppyTest {
    /** You stand where the default placement puts you: 3 m from the pad, 16° off, eyes 1.6 m. */
    private val head = Waypoints.DEFAULT_HEAD
    private val dt = 0.05f

    private fun session(id: LessonId, tol: Tolerance = Tolerance.LENIENT, rings: List<WaypointRing> = emptyList()) =
        Session(id, CourseGeometry(2.5f, 1.5f, tol), Leniency(tol), rings).also {
            it.takeOff()
            it.flight.hover(Vec3(0f, 1.5f, 0f), 0f)
            it.resync()
        }

    /** Plays a recorded path: at time t the drone is at pos(t) with heading yaw(t). */
    private fun fly(s: Session, seconds: Float, pos: (Float) -> Vec3, yaw: (Float) -> Float) {
        var t = 0f
        while (t < seconds && s.outcome == null && !s.goalReached) {
            s.flight.hover(pos(t), yaw(t))
            s.step(dt, Sticks.ZERO, head)
            t += dt
        }
    }

    /** Lands it gently where it is (a soft touch-down of 0.3 m/s). */
    private fun land(s: Session, at: Vec3 = Vec3(0.1f, 0f, 0.05f), vy: Float = -0.3f) {
        s.flight.hover(Vec3(at.x, 0.05f, at.z), s.flight.yaw)
        s.flight.forceVertical(vy)
        var n = 0
        while (s.outcome == null && n++ < 200) s.step(dt, Sticks(ly = if (vy < -0.5f) -0.6f else -0.3f), head)
    }

    /** Heading whose nose is [offDeg] away from "tail-in" (nose pointing away from you). */
    private fun tailIn(at: Vec3, offDeg: Float): Float = Yaw.of(Vec3(at.x - head.x, 0f, at.z - head.z)) + Math.toRadians(offDeg.toDouble()).toFloat()

    private fun noseIn(at: Vec3, offDeg: Float): Float = tailIn(at, 180f + offDeg)

    private fun assertPass(s: Session) {
        val o = s.outcome
        assertNotNull("no outcome (t=${s.t} task=${s.task} box=${s.boxTime} spin=${s.spinDeg} gate=${s.nextGate})", o)
        assertEquals("grade ${o!!.grade} diffs=${o.diffs}", Grade.PASS, o.grade)
    }

    @Test
    fun sloppy01_tailInHoverNose18degOff() {
        val s = session(LessonId.L1)
        val p = Vec3(0f, 1.5f, 0f)
        fly(s, 30f, { p }) { tailIn(p, 18f) }
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy02_tailInHover30cmHighAnd10cmOff() {
        val s = session(LessonId.L1)
        val p = Vec3(0.1f, 1.8f, -0.1f) // box half-height 0.4: 0.3 m high is still inside the lenient band
        fly(s, 30f, { p }) { tailIn(p, -15f) }
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy03_inAndOutOfTheBoxTwiceAsSlow() {
        val s = session(LessonId.L1)
        // wanders out of the box for 2 s every 4 s: 20 s inside take 40 s; nothing is reset (D27)
        fly(s, 80f, { t -> if ((t % 4f) < 2f) Vec3(0.2f, 1.5f, 0f) else Vec3(0.9f, 1.5f, 0f) }) { tailIn(Vec3(0.2f, 1.5f, 0f), 12f) }
        assertTrue("goal after ~40 s, t=${s.t}", s.goalReached)
        land(s, Vec3(0.9f, 0f, 0f))
        assertPass(s)
    }

    @Test
    fun sloppy04_noseInHover25degOff() {
        val s = session(LessonId.L2)
        val p = Vec3(0f, 1.4f, 0.1f)
        fly(s, 30f, { p }) { noseIn(p, 25f) }
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy05_noseInHalfSide30deg() {
        val s = session(LessonId.L2)
        val p = Vec3(-0.15f, 1.6f, 0f)
        fly(s, 30f, { p }) { noseIn(p, -30f) }
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy06_slowSpinHalfSpeedWithWobble() {
        val s = session(LessonId.L3)
        // 20°/s (a quarter of a confident turn), wobbling 10 cm around the centre
        fly(s, 30f, { t -> Vec3(0.1f * sin(t), 1.5f, 0.1f * cos(t)) }) { t -> -Math.toRadians(20.0 * t).toFloat() }
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy07_spinWithShortExitsCountsOnlyInside() {
        val s = session(LessonId.L3)
        fly(s, 40f, { t -> if ((t % 6f) < 5f) Vec3(0.05f, 1.5f, 0f) else Vec3(1f, 1.5f, 0f) }) { t -> Math.toRadians(15.0 * t).toFloat() }
        land(s)
        assertPass(s)
    }

    /** A figure eight along the course path, [lateral] metres to the side, [dy] off the 1.2 m flying height, [speed]. */
    private fun eight(s: Session, lateral: Float, dy: Float, speed: Float) {
        val path = s.course.path(0.05f)
        val n = path.size
        val total = n * 0.05f
        val seconds = total / speed + 1f
        fly(s, seconds, { t ->
            val i = ((t * speed) / 0.05f).toInt().coerceIn(0, n - 1)
            val (p, d) = path[i]
            val side = Vec3(-d.z, 0f, d.x)
            p + side * lateral + Vec3(0f, 1.2f + dy, 0f)
        }) { t ->
            val i = ((t * speed) / 0.05f).toInt().coerceIn(0, n - 1)
            Yaw.of(path[i].second) + Math.toRadians(20.0).toFloat()
        }
    }

    @Test
    fun sloppy08_eight50cmOffTheLineAndSlow() {
        val s = session(LessonId.L4)
        eight(s, 0.5f, 0f, 0.5f)
        assertTrue("all four gates, next=${s.nextGate}", s.goalReached)
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy09_eightLowAndHighInStandard() {
        val s = session(LessonId.L4, Tolerance.STANDARD)
        eight(s, -0.3f, -0.5f, 0.8f) // nose 20° off the path is still "along"
        assertTrue("all four gates, next=${s.nextGate}", s.goalReached)
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy10_landing60cmOffThePad() {
        val s = session(LessonId.L5)
        land(s, Vec3(0.45f, 0f, 0.4f))
        assertPass(s)
    }

    @Test
    fun sloppy11_heavyLandingStillCounts() {
        val s = session(LessonId.L5)
        land(s, Vec3(0.3f, 0f, 0f), -0.75f)
        assertPass(s)
        assertTrue(s.outcome!!.diffs.contains(Diff.LandHeavy))
    }

    @Test
    fun sloppy12_attiHoverAnyHeadingWithExits() {
        val s = session(LessonId.L7)
        fly(s, 40f, { t -> if ((t % 5f) < 3.5f) Vec3(0.3f, 1.25f, -0.2f) else Vec3(0.8f, 1.25f, 0f) }) { t -> t * 0.2f }
        land(s, Vec3(0.8f, 0f, 0f))
        assertPass(s)
    }

    @Test
    fun sloppy13_ringsTenCentimetresOffCentre() {
        val rings = Waypoints.generate(4)
        val s = session(LessonId.F2, rings = rings)
        val pts = ArrayList<Vec3>()
        for (r in rings) {
            val off = Vec3(-r.normal.z, 0f, r.normal.x) * 0.1f + Vec3(0f, 0.08f, 0f)
            pts += r.center - r.normal * 0.3f + off
            pts += r.center + r.normal * 0.3f + off
        }
        var i = 0
        fly(s, pts.size * dt * 2, { t -> pts[((t / dt).toInt()).coerceIn(0, pts.size - 1)].also { i++ } }) { 0f }
        assertTrue("all rings: ${s.nextRing}/${rings.size}", s.goalReached)
        land(s)
        assertPass(s)
    }

    @Test
    fun sloppy14_fullRunSloppyEverywhere() {
        val s = session(LessonId.L6)
        val p = Vec3(0.1f, 1.7f, 0f)
        fly(s, 25f, { p }) { tailIn(p, 17f) }
        assertEquals(Task.HOVER_NOSE, s.task)
        fly(s, 25f, { p }) { noseIn(p, -22f) }
        assertEquals(Task.SPIN, s.task)
        val y0 = s.flight.yaw
        fly(s, 30f, { Vec3(0.1f, 1.5f, 0.1f) }) { t -> y0 + Math.toRadians(16.0 * t).toFloat() }
        assertEquals(Task.EIGHT, s.task)
        eight(s, 0.4f, 0f, 0.6f)
        assertEquals(Task.LAND, s.task)
        land(s, Vec3(0.4f, 0f, 0.3f))
        assertPass(s)
        assertEquals(listOf(true, true, true, true, true), s.outcome!!.chain)
    }

    @Test
    fun adaptiveLeniencyLoosens() {
        val a = Leniency(Tolerance.LENIENT, 0)
        val b = Leniency(Tolerance.LENIENT, 2)
        assertTrue(b.holdS < a.holdS && b.spinNeedDeg < a.spinNeedDeg && b.gateGrow > a.gateGrow && b.orientDeg > a.orientDeg && b.padGrow > a.padGrow)
        assertTrue(Leniency(Tolerance.STANDARD).orientDeg < a.orientDeg)
    }

    @Suppress("unused")
    private val unused = PI
}
