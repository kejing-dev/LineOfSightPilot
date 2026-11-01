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
 * What a ring must keep clear of, in course-local metres (design contentInventory.F2, profile D14 / D20): your eyes
 * ([eye], the REAL head of the moment the round is drawn) and the control panel seen from them ([panelCorners]: the
 * 308-dp page and the stick pads under it, as placed / dragged). The panel's angular window is the horizontal spread of
 * its corners seen from the eye; a ring is out of it when its own angular radius plus [Waypoints.MARGIN_DEG] still
 * clears the window, so no part of a ring is ever drawn between your eyes and the panel.
 */
data class RingGuard(val eye: Vec3, val panelCorners: List<Vec3>) {
    /** Azimuth of the panel centre seen from the eye (Yaw convention, radians). */
    val panelAz: Float
    /** Half the horizontal angle the panel covers, radians. */
    val panelHalfAz: Float

    init {
        val c = panelCorners.fold(Vec3.ZERO) { a, b -> a + b } / panelCorners.size.coerceAtLeast(1).toFloat()
        panelAz = Waypoints.azimuth(c, eye)
        panelHalfAz = panelCorners.maxOfOrNull { abs(Yaw.wrap(Waypoints.azimuth(it, eye) - panelAz)) } ?: 0f
    }

    /** Horizontal distance from the eye. */
    fun flatDist(p: Vec3): Float = hypot(p.x - eye.x, p.z - eye.z)

    /** True when a ball of [radius] metres around [p] reaches into the panel's window (+ margin) seen from the eye. */
    fun blocksPanel(p: Vec3, radius: Float): Boolean {
        val d = flatDist(p)
        if (d <= radius) return true
        val ang = kotlin.math.asin((radius / d).coerceAtMost(1f))
        val off = abs(Yaw.wrap(Waypoints.azimuth(p, eye) - panelAz))
        return off < panelHalfAz + ang + Math.toRadians(Waypoints.MARGIN_DEG.toDouble()).toFloat()
    }

    companion object {
        /**
         * The guard for a course frame, a head and a panel pose, all in Stage space: the panel page ([pageW] x [pageH]
         * metres around [panel]) plus the stick pads ([padW] x [padH], their top [padGap] under the page, [padAhead]
         * metres closer to you).
         */
        fun of(frame: CourseFrame, head: Vec3, panel: Upright, pageW: Float, pageH: Float, padW: Float, padH: Float, padGap: Float = 0.06f, padAhead: Float = 0.15f): RingGuard {
            val right = panel.rotation.rotate(Vec3.RIGHT)
            val toHead = Vec3(head.x - panel.position.x, 0f, head.z - panel.position.z).normalized()
            val c = panel.position
            val pts = ArrayList<Vec3>()
            for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) pts += c + right * (sx * pageW / 2) + Vec3(0f, sy * pageH / 2, 0f)
            val padC = c + toHead * padAhead - Vec3(0f, pageH / 2 + padGap + padH / 2, 0f)
            for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) pts += padC + right * (sx * padW / 2) + Vec3(0f, sy * padH / 2, 0f)
            return RingGuard(frame.toLocal(head), pts.map { frame.toLocal(it) })
        }

        /** The design's default placement: course 3 m ahead and 16° right, panel 38° left (tests, and before the scene is up). */
        fun default(): RingGuard {
            val head = HeadPose(Vec3(0f, Placement.STAND_EYE_M, 0f), Quat4.IDENTITY)
            val frame = Placement.courseAhead(head, 0f)
            return of(frame, head.position, Placement.panel(head), 0.288f, 0.1848f, 0.288f, 0.132f)
        }
    }
}

/**
 * The waypoint challenge generator (design contentInventory.F2, I21): round n has min(3 + n/2, 6) rings of diameter
 * 0.8 - 0.025 * min(n, 4) m inside the 5 x 5 x 2.2 m box over the course, 0.6-2.2 m above the floor, 1.5-3 m apart, a
 * turn of at most 110° between legs, >= 1.5 m (horizontally) from your head and with no part of a ring in the control
 * panel's window seen from your eyes ([RingGuard], built from the real head / panel / course pose, QA q2-2). The legs
 * between rings are kept out of the window too when the room allows (the drone is 0.69 m across). Seed = round number,
 * so a round looks the same for the same placement.
 */
object Waypoints {
    const val HALF_M = 2.5f
    const val MIN_Y = 0.6f
    const val MAX_Y = 2.2f
    const val MIN_GAP = 1.5f
    const val MAX_GAP = 3.0f
    const val MAX_TURN_DEG = 110f
    const val MIN_HEAD_M = 1.5f
    /** Extra angle between a ring's edge and the panel's edge, degrees. */
    const val MARGIN_DEG = 5f
    /** Half the drone's span incl. props: what a leg keeps clear of the panel window. */
    const val DRONE_R = 0.35f
    /** Legs keep at least this far (horizontally) from your head when the room allows. */
    const val LEG_HEAD_M = 1.0f

    /** Your head in course-local metres for the default placement (C 3 m ahead, 16° right; eyes 1.6 m). */
    val DEFAULT_HEAD = Vec3(-0.83f, 1.6f, 2.88f)

    val START = Vec3(0f, 0.8f, 0f)

    fun count(round: Int): Int = min(3 + round / 2, 6)

    fun diameter(round: Int): Float = (0.8f - 0.025f * min(round, 4)).coerceAtLeast(0.7f)

    /** Wind level that comes with round n (every 3 rounds one more). */
    fun wind(round: Int): WindLevel = when ((round - 1).coerceAtLeast(0) / 3) { 0 -> WindLevel.NONE; 1 -> WindLevel.LIGHT; else -> WindLevel.GUSTY }

    /** Which pass produced the last round: 0 = legs clear too, 1 / 2 = rings only, 3 = grid fallback (logged, tested). */
    @Volatile
    var lastPass = -1
        private set

    fun generate(round: Int, guard: RingGuard = RingGuard.default()): List<WaypointRing> {
        // strict first (legs kept clear too), then rings only, then a wider search; every pass keeps the ring rules
        for (pass in 0 until 3) {
            val rnd = java.util.Random(round.toLong() * 7919L + 17L + pass * 104729L)
            val r = attempt(round, guard, rnd, legs = pass == 0, tries = if (pass < 2) 400 else 4000)
            if (r != null) {
                lastPass = pass
                return r
            }
        }
        lastPass = 3
        return fallback(round, guard)
    }

    private fun attempt(round: Int, guard: RingGuard, rnd: java.util.Random, legs: Boolean, tries: Int): List<WaypointRing>? {
        val n = count(round)
        val dia = diameter(round)
        repeat(tries) {
            val pts = ArrayList<Vec3>()
            var last = START
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
                    if (!fits(c, last, lastDir, guard, dia / 2)) continue
                    if (legs && !legClear(last, c, guard)) continue
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
            if (ok) return rings(pts, dia)
        }
        return null
    }

    private fun rings(pts: List<Vec3>, dia: Float): List<WaypointRing> {
        var from = START
        return pts.map { c ->
            val d = Vec3(c.x - from.x, 0f, c.z - from.z).normalized()
            from = c
            WaypointRing(c, d, dia)
        }
    }

    /**
     * Never reached in the tests (every course yaw x placement x round); kept so the mode stays playable: a walk over a
     * grid of the box that still keeps every ring rule but the turn limit.
     */
    private fun fallback(round: Int, guard: RingGuard): List<WaypointRing> {
        val dia = diameter(round)
        val grid = ArrayList<Vec3>()
        var x = -2.2f
        while (x <= 2.21f) {
            var z = -2.2f
            while (z <= 2.21f) {
                for (y in floatArrayOf(1.0f, 1.6f)) grid += Vec3(x, y, z)
                z += 0.55f
            }
            x += 0.55f
        }
        val pts = ArrayList<Vec3>()
        var last = START
        for (i in 0 until count(round)) {
            val c = grid.filter { fits(it, last, null, guard, dia / 2) && pts.none { p -> (p - it).length < MIN_GAP * 0.8f } }.maxByOrNull { guard.flatDist(it) } ?: break
            pts += c
            last = c
        }
        return rings(pts, dia)
    }

    fun fits(c: Vec3, last: Vec3, lastDir: Vec3?, guard: RingGuard, ringR: Float = diameter(1) / 2): Boolean {
        if (abs(c.x) > HALF_M || abs(c.z) > HALF_M) return false
        if (c.y < MIN_Y || c.y > MAX_Y) return false
        val gap = (c - last).length
        if (gap < MIN_GAP - 1e-3f || gap > MAX_GAP + 1e-3f) return false
        if (guard.flatDist(c) < MIN_HEAD_M + ringR) return false
        if (guard.blocksPanel(c, ringR)) return false
        if (lastDir != null) {
            val d = Vec3(c.x - last.x, 0f, c.z - last.z).normalized()
            val turn = Math.toDegrees(kotlin.math.acos(d.dot(lastDir).coerceIn(-1f, 1f).toDouble()))
            if (turn > MAX_TURN_DEG) return false
        }
        return true
    }

    /** The straight leg [a] -> [b] keeps the drone out of the panel window and 1 m off your head. */
    fun legClear(a: Vec3, b: Vec3, guard: RingGuard): Boolean {
        for (k in 1..7) {
            val p = a.lerp(b, k / 8f)
            if (guard.flatDist(p) < LEG_HEAD_M) return false
            if (guard.blocksPanel(p, DRONE_R)) return false
        }
        return true
    }

    /** Azimuth of [p] seen from [from] (Yaw convention: + = left of course -Z), radians. */
    fun azimuth(p: Vec3, from: Vec3): Float = atan2(-(p.x - from.x), -(p.z - from.z))

    /** Azimuth of [p] seen from [head] (Yaw convention: + = left of course -Z), degrees. */
    fun azimuthDeg(p: Vec3, head: Vec3): Float = Math.toDegrees(azimuth(p, head).toDouble()).toFloat()
}
