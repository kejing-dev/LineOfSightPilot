package io.embodit.dronesight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every branch of a flight ends (pre-QA gate: "超时 / 结束判定全分支生效"): the time limit fires inside the box,
 * outside it, outside the field, mid-chain, after the goal without landing; early / hard landings end it too; a paused
 * flight (not stepped) does not run out of time.
 */
class EndingsTest {
    private val head = Waypoints.DEFAULT_HEAD
    private val dt = 0.05f

    private fun session(id: LessonId) =
        Session(id, CourseGeometry()).also {
            it.takeOff()
            it.flight.hover(Vec3(0f, 1.5f, 0f), 0f)
            it.resync()
        }

    private fun hold(s: Session, at: Vec3, seconds: Float, yaw: Float = 0f) {
        var t = 0f
        while (t < seconds && s.outcome == null) {
            s.flight.hover(at, yaw)
            s.step(dt, Sticks.ZERO, head)
            t += dt
        }
    }

    private fun timeoutIn(o: Outcome?, limit: Int) {
        assertNotNull(o)
        assertEquals(Grade.RETRY, o!!.grade)
        assertTrue("diffs=${o.diffs}", o.diffs.first() == Diff.Timeout(limit))
    }

    @Test
    fun ends01_timeoutWhileInTheBoxButFacingWrong() {
        val s = session(LessonId.L1)
        // in the box, nose towards you the whole time: tail-in never counts
        hold(s, Vec3(0f, 1.5f, 0f), 100f, Yaw.of(Vec3(head.x, 0f, head.z)))
        timeoutIn(s.outcome, 90)
    }

    @Test
    fun ends02_timeoutOutsideTheBox() {
        val s = session(LessonId.L2)
        hold(s, Vec3(1.5f, 1.0f, 0f), 100f)
        timeoutIn(s.outcome, 90)
    }

    @Test
    fun ends03_timeoutOutsideTheField() {
        val s = session(LessonId.L3)
        hold(s, Vec3(3.5f, 1.2f, 0f), 100f)
        assertTrue(s.outside)
        timeoutIn(s.outcome, 90)
    }

    @Test
    fun ends04_timeoutMidChain() {
        val s = session(LessonId.L6)
        hold(s, Vec3(0f, 1.5f, 0f), 22f, Yaw.of(Vec3(0.83f, 0f, -2.88f)))
        assertEquals(Task.HOVER_NOSE, s.task)
        hold(s, Vec3(2f, 1.5f, 0f), 300f)
        timeoutIn(s.outcome, 300)
        assertEquals(listOf(true, false, false, false, false), s.outcome!!.chain)
    }

    @Test
    fun ends05_goalReachedThenTimeoutWithoutLandingIsAPass() {
        val s = session(LessonId.L7)
        hold(s, Vec3(0f, 1.5f, 0f), 100f)
        assertEquals(Grade.PASS, s.outcome!!.grade)
    }

    @Test
    fun ends06_landingLessonNeverLands() {
        val s = session(LessonId.L5)
        hold(s, Vec3(0f, 1.5f, 0f), 70f)
        timeoutIn(s.outcome, 60)
    }

    @Test
    fun ends07_eightTimeoutSaysWhichGate() {
        val s = session(LessonId.L4)
        val g = s.course.gates
        // through gates 1 and 2, then hang around
        for (k in 0..1) {
            s.flight.hover(g[k].at - g[k].normal * 0.3f + Vec3(0f, 1.2f, 0f), 0f); s.step(dt, Sticks.ZERO, head)
            s.flight.hover(g[k].at + g[k].normal * 0.3f + Vec3(0f, 1.2f, 0f), 0f); s.step(dt, Sticks.ZERO, head)
        }
        assertEquals(3, s.nextGate)
        hold(s, Vec3(2f, 1.2f, 2f), 200f)
        timeoutIn(s.outcome, 150)
        assertTrue(s.outcome!!.diffs.contains(Diff.GateMissed(3)))
    }

    @Test
    fun ends08_hardLandingIsAgain() {
        val s = session(LessonId.L1)
        s.flight.hover(Vec3(0f, 0.05f, 0f), 0f)
        s.flight.forceVertical(-1.4f)
        var n = 0
        while (s.outcome == null && n++ < 50) s.step(dt, Sticks(ly = -1f), head)
        assertEquals(Grade.RETRY, s.outcome!!.grade)
        assertEquals(listOf<Diff>(Diff.LandHard), s.outcome!!.diffs)
    }

    @Test
    fun ends09_earlyLandingHalfWayIsAlmost() {
        val s = session(LessonId.L1)
        hold(s, Vec3(0f, 1.5f, 0f), 12f) // 12 of 20 s
        s.flight.hover(Vec3(0f, 0.05f, 0f), 0f)
        s.flight.forceVertical(-0.3f)
        var n = 0
        while (s.outcome == null && n++ < 50) s.step(dt, Sticks(ly = -0.3f), head)
        assertEquals(Grade.NEAR, s.outcome!!.grade)
    }

    @Test
    fun ends10_earlyLandingRightAwayIsAgain() {
        val s = session(LessonId.L3)
        s.flight.hover(Vec3(0f, 0.05f, 0f), 0f)
        s.flight.forceVertical(-0.3f)
        var n = 0
        while (s.outcome == null && n++ < 50) s.step(dt, Sticks(ly = -0.3f), head)
        assertEquals(Grade.RETRY, s.outcome!!.grade)
        assertTrue(s.outcome!!.diffs.any { it is Diff.SpinShort })
    }

    @Test
    fun ends11_pausedDoesNotRunOut() {
        val s = session(LessonId.L5)
        hold(s, Vec3(0f, 1.5f, 0f), 30f)
        val t = s.t
        // paused: the frame system does not step the session at all
        assertNull(s.outcome)
        assertEquals(t, s.t, 1e-4f)
    }

    @Test
    fun ends12_offThePadFarIsAgain() {
        val s = session(LessonId.L5)
        s.flight.hover(Vec3(1.8f, 0.05f, 0f), 0f)
        s.flight.forceVertical(-0.3f)
        var n = 0
        while (s.outcome == null && n++ < 50) s.step(dt, Sticks(ly = -0.3f), head)
        assertEquals(Grade.RETRY, s.outcome!!.grade)
        assertTrue(s.outcome!!.diffs.first() is Diff.LandOff)
    }

    @Test
    fun ends13_freeFlightHardLandingEndsWithoutScore() {
        val s = session(LessonId.F1)
        s.flight.hover(Vec3(0f, 0.05f, 0f), 0f)
        s.flight.forceVertical(-1.5f)
        var n = 0
        while (s.outcome == null && n++ < 50) s.step(dt, Sticks(ly = -1f), head)
        assertNotNull(s.outcome)
        assertNull(LessonId.F1.limitS)
    }
}
