package io.embodit.dronesight.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** design contentInventory.F2 / I21: 200 seeds, every constraint holds and every ring can be flown through. */
class WaypointTest {
    @Test
    fun twoHundredSeedsKeepEveryRule() {
        val head = Waypoints.DEFAULT_HEAD
        for (round in 1..200) {
            val rings = Waypoints.generate(round, head)
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
                assertTrue("$tag panel window", !Waypoints.inPanelWindow(c, head))
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
}
