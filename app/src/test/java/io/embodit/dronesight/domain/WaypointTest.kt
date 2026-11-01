package io.embodit.dronesight.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** design contentInventory.F2 / I21: 200 seeds, every constraint holds and every ring can be flown through. */
class WaypointTest {
    @Test
    fun twoHundredSeedsKeepEveryRule() {
        val guard = RingGuard.default()
        val head = guard.eye
        for (round in 1..200) {
            val rings = Waypoints.generate(round, guard)
            assertTrue("round $round count ${rings.size}", rings.size == Waypoints.count(round))
            var last = Vec3(0f, 0.8f, 0f)
            var lastDir: Vec3? = null
            for ((i, r) in rings.withIndex()) {
                val c = r.center
                val tag = "round $round ring $i $c"
                assertTrue("$tag box", abs(c.x) <= Waypoints.HALF_M && abs(c.z) <= Waypoints.HALF_M)
                assertTrue("$tag height", c.y in Waypoints.MIN_Y..Waypoints.MAX_Y)
                val gap = (c - last).length
                assertTrue("$tag gap $gap", gap >= Waypoints.MIN_GAP - 1e-3f && gap <= Waypoints.MAX_GAP + 1e-3f)
                assertTrue("$tag head", hypot(c.x - head.x, c.z - head.z) >= Waypoints.MIN_HEAD_M)
                assertTrue("$tag panel window", !guard.blocksPanel(c, r.radius))
                val d = Vec3(c.x - last.x, 0f, c.z - last.z).normalized()
                if (lastDir != null) {
                    val turn = Math.toDegrees(kotlin.math.acos(d.dot(lastDir).coerceIn(-1f, 1f).toDouble()))
                    assertTrue("$tag turn $turn", turn <= Waypoints.MAX_TURN_DEG + 0.01)
                }
                assertTrue("$tag diameter", r.diameter >= 0.7f - 1e-4f && r.diameter <= 0.8f)
                // flyable: straight through the centre along the ring's normal passes; the drone (0.69 m incl. props) is
                // judged by its centre, so a ring of >= 0.7 m can always be flown
                assertTrue("$tag passable", r.passed(c - r.normal * 0.2f, c + r.normal * 0.2f))
                last = c
                lastDir = d
            }
        }
    }

    @Test
    fun roundsGetHarder() {
        assertTrue(Waypoints.count(1) == 3 && Waypoints.count(6) == 6 && Waypoints.count(20) == 6)
        assertTrue(Waypoints.diameter(1) > Waypoints.diameter(4) && Waypoints.diameter(10) >= 0.7f)
        assertTrue(Waypoints.wind(1) == WindLevel.NONE && Waypoints.wind(4) == WindLevel.LIGHT && Waypoints.wind(7) == WindLevel.GUSTY)
    }

    /**
     * QA q2-2: the rings are drawn from the REAL head, panel and course pose. Head facing 0 / 90 / 200°, the course
     * turned from -180° to +170° (S03 left stick), placed by the ray 2-4 m ahead and 1.5 m to either side, the panel at
     * its default place and dragged: every point of every ring is >= 1.5 m (horizontally) from the head and no line of
     * sight from the eye to a ring point passes through the panel page or the stick pads (any depth, +2 cm).
     */
    @Test
    fun anyCourseYawKeepsRingsOffYouAndThePanel() {
        var cases = 0
        val passes = IntArray(4)
        for (headDeg in intArrayOf(0, 90, 200)) {
            val hy = Math.toRadians(headDeg.toDouble()).toFloat()
            val head = HeadPose(Vec3(0.4f, Placement.STAND_EYE_M, -0.3f), Quat4.axisAngle(Vec3.UP, hy))
            val fwd = Yaw.dir(hy)
            val right = Vec3(-fwd.z, 0f, fwd.x)
            val origins = buildList {
                add(Placement.courseAhead(head, 0f).origin)
                for (ahead in floatArrayOf(2.0f, 4.0f)) for (side in floatArrayOf(-1.5f, 0f, 1.5f)) add(Placement.courseAt(head.position + fwd * ahead + right * side, head, 0f))
            }
            val panels = listOf(Placement.panel(head), Placement.panel(head).let { Upright(it.position + right * 0.35f + fwd * 0.1f, it.yaw - 0.3f) })
            for (o in origins) for (turnDeg in -180 until 180 step 10) for (panel in panels) {
                val frame = CourseFrame(o, Yaw.wrap(hy + Math.toRadians(turnDeg.toDouble()).toFloat()))
                val guard = RingGuard.of(frame, head.position, panel, PAGE_W, PAGE_H, PAD_W, PAD_H)
                for (round in 1..8) {
                    val rings = Waypoints.generate(round, guard)
                    passes[Waypoints.lastPass]++
                    val tag = "head $headDeg origin $o turn $turnDeg panel ${panel.position} round $round"
                    assertTrue("$tag count ${rings.size}", rings.size == Waypoints.count(round))
                    for ((i, r) in rings.withIndex()) {
                        for (p in ringPoints(r)) {
                            val s = frame.toStage(p)
                            val flat = hypot(s.x - head.position.x, s.z - head.position.z)
                            assertTrue("$tag ring $i head $flat", flat >= Waypoints.MIN_HEAD_M)
                            assertTrue("$tag ring $i blocks the panel at $s", !throughPanel(head.position, s, panel))
                        }
                        assertTrue("$tag ring $i box", abs(r.center.x) <= Waypoints.HALF_M && abs(r.center.z) <= Waypoints.HALF_M)
                    }
                    cases++
                }
            }
        }
        println("WAYPOINT passes strict/rings/wide/fallback = ${passes.toList()} of $cases")
        assertTrue(cases > 4000)
        assertTrue("grid fallback used ${passes[3]} times", passes[3] == 0)
    }

    /** The QA case: course turned -58.6° in S03 (en_S10_gate3.png), round 1: no ring in front of the panel. */
    @Test
    fun qaCaseTurnedCourse() {
        val head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
        val f0 = Placement.courseAhead(head, 0f)
        val frame = f0.turned(Math.toRadians(-58.6).toFloat())
        val panel = Placement.panel(head)
        val guard = RingGuard.of(frame, head.position, panel, PAGE_W, PAGE_H, PAD_W, PAD_H)
        for (round in 1..20) for (r in Waypoints.generate(round, guard)) for (p in ringPoints(r)) {
            val s = frame.toStage(p)
            assertTrue(hypot(s.x - head.position.x, s.z - head.position.z) >= 1.5f)
            assertTrue(!throughPanel(head.position, s, panel))
        }
    }

    private fun ringPoints(r: WaypointRing): List<Vec3> {
        val side = r.normal.cross(Vec3.UP).normalized()
        return listOf(r.center) + (0 until 24).map { k ->
            val a = k / 24.0 * 2 * Math.PI
            r.center + side * (r.radius * kotlin.math.cos(a).toFloat()) + Vec3.UP * (r.radius * kotlin.math.sin(a).toFloat())
        }
    }

    /** Does the line of sight eye -> [p] (beyond the eye, any depth) cross the panel page or the stick pads (+2 cm)? */
    private fun throughPanel(eye: Vec3, p: Vec3, panel: Upright): Boolean {
        val right = panel.rotation.rotate(Vec3.RIGHT)
        val n = panel.rotation.rotate(Vec3(0f, 0f, 1f))
        val toHead = Vec3(eye.x - panel.position.x, 0f, eye.z - panel.position.z).normalized()
        val rects = listOf(
            Triple(panel.position, PAGE_W / 2, PAGE_H / 2),
            Triple(panel.position + toHead * 0.15f - Vec3(0f, PAGE_H / 2 + 0.06f + PAD_H / 2, 0f), PAD_W / 2, PAD_H / 2),
        )
        val d = (p - eye).normalized()
        for ((c, hw, hh) in rects) {
            val denom = d.dot(n)
            if (abs(denom) < 1e-5f) continue
            val t = (c - eye).dot(n) / denom
            if (t <= 0f) continue
            val hit = eye + d * t - c
            if (abs(hit.dot(right)) <= hw + 0.02f && abs(hit.y) <= hh + 0.02f) return true
        }
        return false
    }

    companion object {
        const val PAGE_W = 0.288f
        const val PAGE_H = 0.1848f
        const val PAD_W = 0.288f
        const val PAD_H = 0.132f
    }
}
