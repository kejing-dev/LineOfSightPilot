package io.embodit.dronesight.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Tolerance { LENIENT, STANDARD }

/** An axis-aligned box in course-local metres. */
data class Box(val center: Vec3, val half: Vec3) {
    fun contains(p: Vec3, grow: Float = 0f): Boolean =
        abs(p.x - center.x) <= half.x + grow && abs(p.y - center.y) <= half.y + grow && abs(p.z - center.z) <= half.z + grow

    /** Signed height of [p] outside the box's vertical band (+ above, - below, 0 inside). */
    fun heightOff(p: Vec3): Float =
        when {
            p.y > center.y + half.y -> p.y - (center.y + half.y)
            p.y < center.y - half.y -> p.y - (center.y - half.y)
            else -> 0f
        }
}

/**
 * A check gate standing on the floor: centre [at] (floor), passing direction [normal] (horizontal unit vector), width
 * [width] across, height [height]. A pass = the drone's centre crosses the gate plane in the [normal] direction inside
 * the frame (design entities3d.poles, rules.judge).
 */
data class Gate(val index: Int, val at: Vec3, val normal: Vec3, val width: Float = 1.2f, val height: Float = 2.2f) {
    val across: Vec3
        get() = Vec3(-normal.z, 0f, normal.x)

    fun side(p: Vec3): Float = (p - at).let { Vec3(it.x, 0f, it.z) }.dot(normal)

    /** True when the step [a] -> [b] crosses the plane forwards inside the frame (+ [grow] metres of slack). */
    fun passed(a: Vec3, b: Vec3, grow: Float = 0f): Boolean {
        val sa = side(a)
        val sb = side(b)
        // from on / behind the plane to in front of it (the pad sits on G1's plane: taking off from it and flying out counts)
        if (!(sa <= 0f && sb > 0f)) return false
        val t = if (sb - sa < 1e-6f) 0f else -sa / (sb - sa)
        val c = a + (b - a) * t
        val lateral = abs((c - at).let { Vec3(it.x, 0f, it.z) }.dot(across))
        return lateral <= width / 2 + grow && c.y >= -0.05f && c.y <= height + grow
    }

    /** Crossed the plane backwards or forwards in the frame: used for "wrong order" flashes. */
    fun crossedAny(a: Vec3, b: Vec3): Boolean = passed(a, b) || copy(normal = -normal).passed(a, b)
}

/**
 * The course in the user's living room (design rules.courseGeometry, scaled down from the test field): pad Ø 1.0 m at
 * the origin (lenient Ø 1.4 m), the hover box over it, two poles d apart on the X axis, the figure eight = two circles
 * of radius d/4 round the poles joined by the two crossing lines through the centre, four check gates on it, the spin
 * ring, the 6 x 6 m field.
 */
data class CourseGeometry(val poleDistM: Float = 2.5f, val hoverM: Float = 1.5f, val tolerance: Tolerance = Tolerance.LENIENT) {
    val padRadius: Float
        get() = if (tolerance == Tolerance.LENIENT) 0.7f else 0.5f

    val hoverBox: Box
        get() =
            if (tolerance == Tolerance.LENIENT) Box(Vec3(0f, hoverM, 0f), Vec3(0.6f, 0.4f, 0.6f))
            else Box(Vec3(0f, hoverM, 0f), Vec3(0.4f, 0.25f, 0.4f))

    val leftPole: Vec3
        get() = Vec3(-poleDistM / 2, 0f, 0f)

    val rightPole: Vec3
        get() = Vec3(poleDistM / 2, 0f, 0f)

    val loopRadius: Float
        get() = poleDistM / 4

    /** Half-angle of the crossing lines against the X axis (tangent from the centre to a loop: sin = r / (d/2) = 1/2). */
    private val crossAngle: Float
        get() = (PI / 6).toFloat()

    /**
     * The gates in flying order: G1 the centre crossing heading left (and away), G2 the far left of the left loop
     * (heading towards you), G3 the centre crossing heading right, G4 the far right of the right loop.
     */
    val gates: List<Gate>
        get() {
            val c = cos(crossAngle)
            val s = sin(crossAngle)
            return listOf(
                Gate(1, Vec3.ZERO, Vec3(-c, 0f, -s)),
                Gate(2, Vec3(-3 * poleDistM / 4, 0f, 0f), Vec3(0f, 0f, 1f)),
                Gate(3, Vec3.ZERO, Vec3(c, 0f, -s)),
                Gate(4, Vec3(3 * poleDistM / 4, 0f, 0f), Vec3(0f, 0f, 1f)),
            )
        }

    /**
     * Points along the figure eight (floor), in flying order from the centre, about [step] metres apart, with the
     * direction of travel at each point.
     */
    fun path(step: Float = 0.25f): List<Pair<Vec3, Vec3>> {
        val out = ArrayList<Pair<Vec3, Vec3>>()
        val r = loopRadius
        val a = crossAngle
        val half = poleDistM / 2
        val tangentLen = sqrt(half * half - r * r)
        fun line(from: Vec3, dir: Vec3, len: Float) {
            val n = maxOf(1, (len / step).toInt())
            for (i in 0 until n) out += (from + dir * (len * i / n)) to dir
        }
        // the loops: centre (±half, 0); tangent points seen from the origin at ±(90° - a) from the pole direction
        fun loop(cx: Float, startAng: Double, sweep: Double) {
            val len = abs(sweep) * r
            val n = maxOf(4, (len / step).toInt())
            for (i in 0 until n) {
                val t = startAng + sweep * i / n
                val p = Vec3(cx + r * cos(t).toFloat(), 0f, r * sin(t).toFloat())
                val d = if (sweep > 0) Vec3(-sin(t).toFloat(), 0f, cos(t).toFloat()) else Vec3(sin(t).toFloat(), 0f, -cos(t).toFloat())
                out += p to d
            }
        }
        val d1 = Vec3(-cos(a), 0f, -sin(a))
        line(Vec3.ZERO, d1, tangentLen)
        // left loop: enter at the far tangent point, round the far side, the far left (G2, heading +Z), the near side
        val enterL = Math.atan2(-sin(a).toDouble() * tangentLen, (-cos(a) * tangentLen + half).toDouble())
        loop(-half, enterL, -(2 * PI - 2 * (PI / 2 - a)))
        val d3 = Vec3(cos(a), 0f, -sin(a))
        val nearL = Vec3(-cos(a) * tangentLen, 0f, sin(a) * tangentLen)
        line(nearL, d3, tangentLen * 2)
        val enterR = Math.atan2((-sin(a) * tangentLen).toDouble(), (cos(a) * tangentLen - half).toDouble())
        loop(half, enterR, 2 * PI - 2 * (PI / 2 - a))
        val nearR = Vec3(cos(a) * tangentLen, 0f, sin(a) * tangentLen)
        line(nearR, d1, tangentLen)
        return out
    }

    companion object {
        const val FIELD_HALF_M = 3.0f
        const val POLE_M = 1.8f
        const val MIN_POLE_M = 2.0f
        const val MAX_POLE_M = 5.0f
    }
}

/** Where you stand relative to the drone: tail-in (nose away, < 45°), nose-in (> 135°), side (design rules.noseIndication). */
enum class Orientation { TAIL_IN, NOSE_IN, SIDE }

object Orient {
    /** Angle (degrees) between the nose and the line you -> drone (0 = pointing straight away from you). */
    fun angleDeg(noseYaw: Float, drone: Vec3, head: Vec3): Float {
        val away = Vec3(drone.x - head.x, 0f, drone.z - head.z)
        if (away.length < 1e-3f) return 0f
        val n = Yaw.dir(noseYaw)
        val c = (n.dot(away) / away.length).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(c).toDouble()).toFloat()
    }

    fun of(angleDeg: Float): Orientation = when {
        angleDeg < 45f -> Orientation.TAIL_IN
        angleDeg > 135f -> Orientation.NOSE_IN
        else -> Orientation.SIDE
    }
}
