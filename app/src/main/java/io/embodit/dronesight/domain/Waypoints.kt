package io.embodit.dronesight.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** A hanging ring of the waypoint run (F2): centre, passing direction (horizontal unit vector), diameter. */
data class WaypointRing(val center: Vec3, val normal: Vec3, val diameter: Float) {
    val radius: Float
        get() = diameter / 2

    /** The drone's centre crosses the ring plane forwards within the ring (+ [grow] metres, lenient). */
    fun passed(a: Vec3, b: Vec3, grow: Float = 0f): Boolean {
        val sa = (a - center).dot(normal)
        val sb = (b - center).dot(normal)
        if (!(sa < 0f && sb >= 0f)) return false
        val t = if (sb - sa < 1e-6f) 0f else -sa / (sb - sa)
        val c = a + (b - a) * t
        return (c - center).length <= radius + grow
    }
}

/**
 * The waypoint challenge generator (design contentInventory.F2, I21): round n has min(3 + n/2, 6) rings of diameter
 * 0.8 - 0.025 * min(n, 4) m inside the 5 x 5 x 2.2 m box over the course, 0.6-2.2 m above the floor, 1.5-3 m apart, a
 * turn of at most 110° between legs, >= 1.5 m (horizontally) from your head and out of the panel's angular window.
 * Seed = round number, so a round always looks the same.
 */
object Waypoints {
    const val HALF_M = 2.5f
    const val MIN_Y = 0.6f
    const val MAX_Y = 2.2f
    const val MIN_GAP = 1.5f
    const val MAX_GAP = 3.0f
    const val MAX_TURN_DEG = 110f
    const val MIN_HEAD_M = 1.5f

    /** Your head in course-local metres for the default placement (C 3 m ahead, 16° right; eyes 1.6 m). */
    val DEFAULT_HEAD = Vec3(-0.83f, 1.6f, 2.88f)

    /** The control panel's window seen from the head: azimuth (+ = left of the course's ahead) 25-51°. */
    const val PANEL_AZ_MIN = 25f
    const val PANEL_AZ_MAX = 51f

    fun count(round: Int): Int = min(3 + round / 2, 6)

    fun diameter(round: Int): Float = (0.8f - 0.025f * min(round, 4)).coerceAtLeast(0.7f)

    /** Wind level that comes with round n (every 3 rounds one more). */
    fun wind(round: Int): WindLevel = when ((round - 1).coerceAtLeast(0) / 3) { 0 -> WindLevel.NONE; 1 -> WindLevel.LIGHT; else -> WindLevel.GUSTY }

    fun generate(round: Int, head: Vec3 = DEFAULT_HEAD): List<WaypointRing> {
        val rnd = java.util.Random(round.toLong() * 7919L + 17L)
        val n = count(round)
        val dia = diameter(round)
        repeat(400) {
            val pts = ArrayList<Vec3>()
            var last = Vec3(0f, 0.8f, 0f)
            var lastDir: Vec3? = null
            var ok = true
            for (i in 0 until n) {
                var placed: Vec3? = null
                for (tryN in 0 until 120) {
                    val az = rnd.nextFloat() * 2f * Math.PI.toFloat()
                    val dist = MIN_GAP + rnd.nextFloat() * (MAX_GAP - MIN_GAP)
                    val dy = (rnd.nextFloat() * 2f - 1f) * 0.8f
                    val flat = kotlin.math.sqrt((dist * dist - dy * dy).coerceAtLeast(0.25f))
                    val c = Vec3(last.x + flat * cos(az), (last.y + dy), last.z + flat * sin(az))
                    if (!fits(c, last, lastDir, head)) continue
                    if (pts.any { (it - c).length < MIN_GAP * 0.8f }) continue
                    placed = c
                    break
                }
                if (placed == null) {
                    ok = false
                    break
                }
                lastDir = Vec3(placed.x - last.x, 0f, placed.z - last.z).normalized()
                pts += placed
                last = placed
            }
            if (ok) {
                var from = Vec3(0f, 0.8f, 0f)
                return pts.map { c ->
                    val d = Vec3(c.x - from.x, 0f, c.z - from.z).normalized()
                    from = c
                    WaypointRing(c, d, dia)
                }
            }
        }
        // never reached for the seeds tested (200); a plain fallback keeps the mode playable
        return listOf(WaypointRing(Vec3(-1.2f, 1.2f, -0.6f), Vec3(-1f, 0f, 0f), dia), WaypointRing(Vec3(0.6f, 1.6f, -1.6f), Vec3(1f, 0f, 0f), dia), WaypointRing(Vec3(1.5f, 1.0f, 0f), Vec3(0f, 0f, 1f), dia))
    }

    fun fits(c: Vec3, last: Vec3, lastDir: Vec3?, head: Vec3): Boolean {
        if (abs(c.x) > HALF_M || abs(c.z) > HALF_M) return false
        if (c.y < MIN_Y || c.y > MAX_Y) return false
        val gap = (c - last).length
        if (gap < MIN_GAP - 1e-3f || gap > MAX_GAP + 1e-3f) return false
        if (hypot(c.x - head.x, c.z - head.z) < MIN_HEAD_M) return false
        if (inPanelWindow(c, head)) return false
        if (lastDir != null) {
            val d = Vec3(c.x - last.x, 0f, c.z - last.z).normalized()
            val turn = Math.toDegrees(kotlin.math.acos(d.dot(lastDir).coerceIn(-1f, 1f).toDouble()))
            if (turn > MAX_TURN_DEG) return false
        }
        return true
    }

    /** Azimuth of [p] seen from [head] (Yaw convention: + = left of course -Z), degrees. */
    fun azimuthDeg(p: Vec3, head: Vec3): Float = Math.toDegrees(atan2(-(p.x - head.x), -(p.z - head.z)).toDouble()).toFloat()

    fun inPanelWindow(p: Vec3, head: Vec3): Boolean {
        val az = azimuthDeg(p, head)
        return az in PANEL_AZ_MIN..PANEL_AZ_MAX
    }
}
