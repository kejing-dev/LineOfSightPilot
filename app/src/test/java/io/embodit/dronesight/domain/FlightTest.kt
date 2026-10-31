package io.embodit.dronesight.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** design rules.flight / acceptance: the velocity-command model, GPS vs ATTI, wind, touch-down classes, sticks. */
class FlightTest {
    private val dt = 1f / 60f

    private fun run(f: FlightModel, s: Sticks, seconds: Float): Touchdown? {
        var t = 0f
        while (t < seconds) {
            val td = f.step(dt, s)
            if (td != null) return td
            t += dt
        }
        return null
    }

    private fun airborne(mode: FlightMode = FlightMode.GPS, wind: WindLevel = WindLevel.NONE): FlightModel =
        FlightModel().also {
            it.mode = mode
            it.wind = Wind(wind, 0f, 3L)
            it.hover(Vec3(0f, 1.5f, 0f), 0f)
        }

    @Test
    fun takeOffSpinsUpThenClimbsTo08() {
        val f = FlightModel()
        assertTrue(f.takeOff())
        run(f, Sticks.ZERO, 0.79f)
        assertEquals(FlightPhase.SPIN_UP, f.phase)
        run(f, Sticks.ZERO, 3f)
        assertEquals(FlightPhase.AIR, f.phase)
        assertEquals(0.8f, f.pos.y, 0.01f)
    }

    @Test
    fun gpsReleasedStopsWithinOneSecond() {
        val f = airborne()
        run(f, Sticks(ry = 1f), 2f)
        assertTrue("full stick ~2 m/s, was ${f.vel}", hypot(f.vel.x, f.vel.z) > 1.8f)
        run(f, Sticks.ZERO, 1f)
        assertTrue("GPS release < 0.1 m/s after 1 s, was ${f.vel}", hypot(f.vel.x, f.vel.z) < 0.1f)
    }

    @Test
    fun attiKeepsDriftingAfterRelease() {
        val f = airborne(FlightMode.ATTI)
        val start = f.pos
        run(f, Sticks(rx = 1f), 0.5f)
        run(f, Sticks.ZERO, 2f)
        val moved = hypot(f.pos.x - start.x, f.pos.z - start.z)
        assertTrue("ATTI 0.5 s full + 2 s free > 0.7 m, was $moved", moved > 0.7f)
        assertTrue("still drifting, v=${f.vel}", hypot(f.vel.x, f.vel.z) > 0.1f)
    }

    @Test
    fun lightWindDriftsAttiButNotGps() {
        val a = airborne(FlightMode.ATTI, WindLevel.LIGHT)
        val g = airborne(FlightMode.GPS, WindLevel.LIGHT)
        val a0 = a.pos
        val g0 = g.pos
        run(a, Sticks.ZERO, 5f)
        run(g, Sticks.ZERO, 5f)
        assertTrue("ATTI light wind 5 s > 1 m: ${a.pos}", hypot(a.pos.x - a0.x, a.pos.z - a0.z) > 1f)
        assertTrue("GPS light wind 5 s < 0.2 m: ${g.pos}", hypot(g.pos.x - g0.x, g.pos.z - g0.z) < 0.2f)
    }

    @Test
    fun yawNinetyDegreesPerSecond() {
        val f = airborne()
        run(f, Sticks(lx = 1f), 1f)
        assertEquals(-90.0, Math.toDegrees(f.yaw.toDouble()), 4.0)
    }

    @Test
    fun fullSinkIsAHardLanding() {
        val f = airborne()
        assertEquals(Touchdown.HARD, run(f, Sticks(ly = -1f), 5f))
    }

    @Test
    fun gentleSinkIsSoft() {
        val f = airborne()
        // shaped 0.3 (raw ~0.42) -> 0.48 m/s down
        assertEquals(Touchdown.SOFT, run(f, Sticks(ly = -0.3f), 10f))
    }

    @Test
    fun touchdownClasses() {
        assertEquals(Touchdown.SOFT, Touchdown.of(0.5f))
        assertEquals(Touchdown.HEAVY, Touchdown.of(0.7f))
        assertEquals(Touchdown.HEAVY, Touchdown.of(0.9f))
        assertEquals(Touchdown.HARD, Touchdown.of(0.91f))
    }

    @Test
    fun ceilingStopsTheClimb() {
        val f = airborne()
        assertNull(run(f, Sticks(ly = 1f), 3f))
        assertEquals(2.6f, f.pos.y, 1e-3f)
        assertTrue(f.atCeiling)
    }

    @Test
    fun noseIsMinusZAndRightStickMovesRightOfTheNose() {
        val f = airborne()
        run(f, Sticks(rx = 1f), 1f)
        assertTrue("right stick at yaw 0 moves +X: ${f.pos}", f.pos.x > 0.5f)
        val g = airborne()
        g.hover(Vec3(0f, 1.5f, 0f), Math.PI.toFloat()) // nose towards +Z (towards you)
        run(g, Sticks(rx = 1f), 1f)
        assertTrue("nose-in: right stick goes to YOUR left (-X): ${g.pos}", g.pos.x < -0.5f)
    }

    @Test
    fun stickShaping() {
        assertEquals(0f, StickShaping.axis(0.07f), 0f)
        assertEquals(1f, StickShaping.axis(1f), 1e-5f)
        assertEquals(-1f, StickShaping.axis(-1f), 1e-5f)
        assertTrue(StickShaping.axis(0.5f) < 0.5f)
        val m = Sticks.merge(Sticks(0.2f, -0.9f, 0f, 0f), Sticks(-0.6f, 0.1f, 0.3f, 0f))
        assertEquals(Sticks(-0.6f, -0.9f, 0.3f, 0f), m)
        val inv = StickShaping.shape(Sticks(0f, 1f, 1f, 0f), StickInvert(leftY = true))
        assertTrue(inv.ly < 0f && inv.rx > 0f)
        assertTrue(abs(StickShaping.axis(Float.NaN)) == 0f)
    }
}
