package io.embodit.dronesight.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

enum class FlightMode { GPS, ATTI }

enum class WindLevel { NONE, LIGHT, GUSTY }

/** Touch-down by vertical speed (design rules.flight): <= 0.5 m/s soft, 0.5-0.9 heavy (still a landing), > 0.9 hard. */
enum class Touchdown {
    SOFT,
    HEAVY,
    HARD;

    companion object {
        const val SOFT_MAX_MPS = 0.5f
        const val HEAVY_MAX_MPS = 0.9f

        fun of(speedDown: Float): Touchdown =
            when {
                speedDown <= SOFT_MAX_MPS -> SOFT
                speedDown <= HEAVY_MAX_MPS -> HEAVY
                else -> HARD
            }
    }
}

/**
 * Wind over the course (design rules.wind): none 0; light 0.6 m/s + every 4-7 s a 2 s puff of ±0.3; gusty 1.0 m/s +
 * every 3-5 s a 2 s gust of +0.8. Direction (course-local yaw) is drawn when the course is placed. Deterministic per
 * seed (unit tests, debug `wind gust`).
 */
class Wind(val level: WindLevel, val dirYaw: Float, seed: Long = 1L) {
    private val rnd = java.util.Random(seed)
    private var t = 0f
    private var nextGust = firstGap()
    private var gustStart = -10f
    private var gustSign = 1f

    /** True while a gust / puff is blowing (the sock shakes, the tag shows the wind arrow). */
    var gusting = false
        private set

    val base: Float
        get() = when (level) { WindLevel.NONE -> 0f; WindLevel.LIGHT -> 0.6f; WindLevel.GUSTY -> 1.0f }

    private fun firstGap(): Float = when (level) { WindLevel.NONE -> Float.MAX_VALUE; WindLevel.LIGHT -> 4f + rnd.nextFloat() * 3f; WindLevel.GUSTY -> 3f + rnd.nextFloat() * 2f }

    /** Starts a gust now (debug `wind gust`). */
    fun gustNow() {
        if (level == WindLevel.NONE) return
        gustStart = t
        gustSign = if (level == WindLevel.LIGHT && rnd.nextBoolean()) -1f else 1f
        nextGust = t + GUST_S + firstGap()
    }

    /** Advances [dt] s; returns the wind velocity, course-local, horizontal (m/s). */
    fun step(dt: Float): Vec3 {
        t += dt
        if (level == WindLevel.NONE) return Vec3.ZERO
        if (t >= nextGust) gustNow()
        val g = t - gustStart
        gusting = g in 0f..GUST_S
        val bump = if (gusting) sin(PI * g / GUST_S).toFloat() else 0f
        val extra = when (level) { WindLevel.LIGHT -> 0.3f * gustSign; WindLevel.GUSTY -> 0.8f; WindLevel.NONE -> 0f } * bump
        return Yaw.dir(dirYaw) * (base + extra)
    }

    /** GPS hold: the slow zero-mean sway around the hold point (±5 cm light, ±10 cm gusty, about 0.7 Hz). */
    fun sway(): Vec3 {
        val a = when (level) { WindLevel.NONE -> 0f; WindLevel.LIGHT -> 0.05f; WindLevel.GUSTY -> 0.10f }
        if (a == 0f) return Vec3.ZERO
        val x = 0.6f * sin(2 * PI * 0.7 * t).toFloat() + 0.4f * sin(2 * PI * 0.31 * t + 1.3).toFloat()
        val z = 0.6f * sin(2 * PI * 0.53 * t + 0.4).toFloat() + 0.4f * sin(2 * PI * 0.23 * t + 2.1).toFloat()
        return Vec3(x * a, 0f, z * a)
    }

    companion object {
        const val GUST_S = 2f
    }
}

/** Speeds and time constants of design rules.flight. */
data class FlightParams(
    val vMaxGps: Float = 2.0f,
    val vMaxSlow: Float = 1.2f,
    val climbMax: Float = 1.0f,
    val sinkMax: Float = 1.6f,
    val yawRateDeg: Float = 90f,
    val tauPush: Float = 0.35f,
    val tauBrake: Float = 0.2f,
    val attiAccel: Float = 2.1f,
    val attiDrag: Float = 0.8f,
    val tiltPerAccelDeg: Float = 2.5f,
    val tiltMaxDeg: Float = 25f,
    val spinUpS: Float = 0.8f,
    val autoClimbM: Float = 0.8f,
    val ceilingM: Float = 2.6f,
    val boundsHalfM: Float = 3.0f,
)

enum class FlightPhase { GROUND, SPIN_UP, CLIMB, AIR }

/**
 * The drone: a velocity-command model, not rigid-body physics (design container.notes, rules.flight). Course-local
 * coordinates; y is the height above the floor. GPS: right stick = horizontal velocity (m/s), released -> brake with
 * tau 0.2 s, then hold the point (with the wind sway). ATTI: right stick = horizontal acceleration, drag k = 0.8/s
 * towards the wind velocity, released -> keeps drifting. Both hold altitude; left stick y = climb / sink, left x = yaw.
 * The tilt is visual only. Pure Kotlin, stepped by the frame system, tested on the JVM.
 */
class FlightModel(var params: FlightParams = FlightParams()) {
    var pos: Vec3 = Vec3.ZERO
        private set
    var vel: Vec3 = Vec3.ZERO
        private set

    /** Nose heading, Yaw convention (0 = course -Z, + = turned left); the nose is the model's -Z. */
    var yaw: Float = 0f
        private set

    var phase: FlightPhase = FlightPhase.GROUND
        private set

    var mode: FlightMode = FlightMode.GPS
    var slow: Boolean = false
    var wind: Wind = Wind(WindLevel.NONE, 0f)

    /** Visual body tilt (radians): + pitch = nose down (accelerating forward), + roll = right side down. */
    var pitchVis = 0f
        private set
    var rollVis = 0f
        private set

    /** 0 = stopped, 1 = full spin (visual / sound). */
    var rotor = 0f
        private set

    /** Throttle 0..1 for the rotor pitch (sound). */
    var throttle = 0f
        private set

    var atCeiling = false
        private set

    /** Highest height of this flight (landing lesson "go up to 1.5 m first"). */
    var maxHeight = 0f
        private set

    private var spin = 0f
    private var hold: Vec3? = null
    private var accel = Vec3.ZERO
    private var lastWind = Vec3.ZERO

    val airborne: Boolean
        get() = phase == FlightPhase.AIR || phase == FlightPhase.CLIMB

    val nose: Vec3
        get() = Yaw.dir(yaw)

    val right: Vec3
        get() = nose.let { Vec3(-it.z, 0f, it.x) }

    val windNow: Vec3
        get() = lastWind

    /** Puts the drone on the floor at [at] (course-local, y ignored), nose to [yaw], rotors off. */
    fun park(at: Vec3, yaw: Float = 0f) {
        pos = Vec3(at.x, 0f, at.z)
        vel = Vec3.ZERO
        this.yaw = yaw
        phase = FlightPhase.GROUND
        hold = null
        rotor = 0f
        spin = 0f
        pitchVis = 0f
        rollVis = 0f
        accel = Vec3.ZERO
        maxHeight = 0f
        atCeiling = false
    }

    /** Start of the take-off: 0.8 s spin-up, then the automatic climb to 0.8 m (design rules.flight). */
    fun takeOff(): Boolean {
        if (phase != FlightPhase.GROUND) return false
        phase = FlightPhase.SPIN_UP
        spin = 0f
        maxHeight = 0f
        return true
    }

    /** Debug / tests: puts the drone in the air at [at] with [yaw], holding still. */
    fun hover(at: Vec3, yaw: Float) {
        pos = Vec3(at.x, at.y.coerceIn(0.05f, params.ceilingM), at.z)
        vel = Vec3.ZERO
        this.yaw = yaw
        phase = FlightPhase.AIR
        hold = null
        rotor = 1f
        maxHeight = maxOf(maxHeight, pos.y)
    }

    /** Debug: an exact vertical speed (crash / landing frames). */
    fun forceVertical(vy: Float) {
        vel = Vec3(vel.x, vy, vel.z)
    }

    /**
     * Advances [dt] s with the shaped [sticks]. Returns the touch-down when the drone reached the floor in this step.
     */
    fun step(dt: Float, sticks: Sticks): Touchdown? {
        if (dt <= 0f) return null
        val w = wind.step(dt)
        lastWind = w
        when (phase) {
            FlightPhase.GROUND -> {
                rotor = (rotor - dt / 1.2f).coerceAtLeast(0f)
                throttle = 0f
                return null
            }
            FlightPhase.SPIN_UP -> {
                spin += dt
                rotor = (spin / params.spinUpS).coerceIn(0f, 1f) * 0.7f
                throttle = 0.2f
                if (spin >= params.spinUpS) phase = FlightPhase.CLIMB
                return null
            }
            FlightPhase.CLIMB -> {
                rotor = 0.85f
                throttle = 0.7f
                val target = params.autoClimbM
                val vy = (target - pos.y).coerceIn(0f, 0.8f).coerceAtLeast(0.15f)
                vel = Vec3(0f, vy, 0f)
                pos = Vec3(pos.x, (pos.y + vy * dt).coerceAtMost(target), pos.z)
                maxHeight = maxOf(maxHeight, pos.y)
                if (pos.y >= target - 1e-3f) {
                    phase = FlightPhase.AIR
                    vel = Vec3.ZERO
                    hold = Vec3(pos.x, 0f, pos.z)
                }
                return null
            }
            FlightPhase.AIR -> return stepAir(dt, sticks, w)
        }
    }

    private fun stepAir(dt: Float, s: Sticks, w: Vec3): Touchdown? {
        val p = params
        // yaw: stick right turns the nose right (clockwise seen from above = negative in the Yaw convention)
        yaw = Yaw.wrap(yaw - s.lx * Math.toRadians(p.yawRateDeg.toDouble()).toFloat() * dt)
        val f = nose
        val r = right
        val vh0 = Vec3(vel.x, 0f, vel.z)
        var vh: Vec3
        var cmdAccel: Vec3
        when (mode) {
            FlightMode.GPS -> {
                val vmax = if (slow) p.vMaxSlow else p.vMaxGps
                val cmd = (f * s.ry + r * s.rx) * vmax
                val tau = if (s.rightCentred) p.tauBrake else p.tauPush
                vh = vh0 + (cmd - vh0) * (1f - exp(-dt / tau))
                cmdAccel = (vh - vh0) / dt
                if (s.rightCentred && vh.length < HOLD_BELOW_MPS) {
                    vh = Vec3.ZERO
                    if (hold == null) hold = wind.sway().let { Vec3(pos.x - it.x, 0f, pos.z - it.z) }
                } else {
                    hold = null
                }
            }
            FlightMode.ATTI -> {
                hold = null
                val a = (f * s.ry + r * s.rx) * p.attiAccel
                cmdAccel = a
                vh = vh0 + (a - (vh0 - w) * p.attiDrag) * dt
            }
        }
        // vertical: velocity command, altitude hold on release
        val vyCmd = if (s.ly >= 0f) s.ly * p.climbMax else s.ly * p.sinkMax
        val tauV = if (s.ly == 0f) p.tauBrake else p.tauPush
        var vy = vel.y + (vyCmd - vel.y) * (1f - exp(-dt / tauV))
        if (s.ly == 0f && abs(vy) < 0.02f) vy = 0f
        throttle = (0.55f + 0.45f * s.ly).coerceIn(0.1f, 1f)
        rotor = 1f
        // position
        val held = hold
        var nx: Float
        var nz: Float
        if (held != null) {
            val sw = wind.sway()
            nx = held.x + sw.x
            nz = held.z + sw.z
        } else {
            nx = pos.x + vh.x * dt
            nz = pos.z + vh.z * dt
        }
        var ny = pos.y + vy * dt
        atCeiling = false
        if (ny >= p.ceilingM) {
            ny = p.ceilingM
            if (vy > 0f) vy = 0f
            atCeiling = true
        }
        vel = Vec3(vh.x, vy, vh.z)
        // visual tilt: acceleration in the body frame (+ GPS leans into the wind a little)
        accel = accel + (cmdAccel - accel) * (1f - exp(-dt / 0.12f))
        val k = Math.toRadians(p.tiltPerAccelDeg.toDouble()).toFloat()
        val maxT = Math.toRadians(p.tiltMaxDeg.toDouble()).toFloat()
        var lean = Vec3.ZERO
        if (mode == FlightMode.GPS && w.length > 0.05f) lean = w.normalized() * Math.toRadians((3.0 + 5.0 * (w.length / 1.8).coerceAtMost(1.0))).toFloat()
        val tp = (accel.dot(f) * k + lean.dot(f)).coerceIn(-maxT, maxT)
        val tr = (accel.dot(r) * k + lean.dot(r)).coerceIn(-maxT, maxT)
        pitchVis += (tp - pitchVis) * (1f - exp(-dt / 0.1f))
        rollVis += (tr - rollVis) * (1f - exp(-dt / 0.1f))
        // touch-down
        if (ny <= TOUCH_M && vy < 0f) {
            val speed = -vy
            pos = Vec3(nx, 0f, nz)
            vel = Vec3.ZERO
            phase = FlightPhase.GROUND
            hold = null
            pitchVis = 0f
            rollVis = 0f
            accel = Vec3.ZERO
            return Touchdown.of(speed)
        }
        pos = Vec3(nx, ny.coerceAtLeast(0f), nz)
        maxHeight = maxOf(maxHeight, pos.y)
        return null
    }

    /** Horizontal distance from the course centre beyond the 6 x 6 m field (0 inside). */
    fun outOfBounds(): Boolean = abs(pos.x) > params.boundsHalfM || abs(pos.z) > params.boundsHalfM

    companion object {
        const val TOUCH_M = 0.02f
        const val HOLD_BELOW_MPS = 0.02f
    }
}
