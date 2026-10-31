package io.embodit.dronesight.domain

import kotlin.math.abs

/**
 * Mode 2 stick positions, each -1..1 (design reuseFromReference.port.keep 1): left y = throttle (up / down), left x =
 * yaw (+ = turn right), right y = pitch (+ = forward), right x = roll (+ = right).
 */
data class Sticks(val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f) {
    val isCentred: Boolean
        get() = lx == 0f && ly == 0f && rx == 0f && ry == 0f

    val rightCentred: Boolean
        get() = rx == 0f && ry == 0f

    companion object {
        val ZERO = Sticks()

        /** Per axis, the source with the larger deflection wins (hardware sticks and the virtual pads both work). */
        fun merge(a: Sticks, b: Sticks): Sticks = Sticks(pick(a.lx, b.lx), pick(a.ly, b.ly), pick(a.rx, b.rx), pick(a.ry, b.ry))

        private fun pick(a: Float, b: Float) = if (abs(a) >= abs(b)) a else b
    }
}

/** Settings "摇杆反向": four independent axis flips (design settingsPage ⑨). */
data class StickInvert(val leftY: Boolean = false, val leftX: Boolean = false, val rightY: Boolean = false, val rightX: Boolean = false)

/**
 * Raw axis -> command: dead zone 0.08 (rescaled so the output starts at 0), expo 0.3 (finer small sticks), clamp, the
 * user's inversions (design input.primary).
 */
object StickShaping {
    const val DEAD_ZONE = 0.08f
    const val EXPO = 0.3f

    fun axis(raw: Float, dead: Float = DEAD_ZONE, expo: Float = EXPO): Float {
        if (!raw.isFinite()) return 0f
        val v = raw.coerceIn(-1f, 1f)
        val a = abs(v)
        if (a <= dead) return 0f
        val x = (a - dead) / (1f - dead)
        val shaped = (1f - expo) * x + expo * x * x * x
        return if (v < 0) -shaped else shaped
    }

    fun shape(raw: Sticks, invert: StickInvert = StickInvert()): Sticks =
        Sticks(
            lx = axis(raw.lx) * (if (invert.leftX) -1f else 1f),
            ly = axis(raw.ly) * (if (invert.leftY) -1f else 1f),
            rx = axis(raw.rx) * (if (invert.rightX) -1f else 1f),
            ry = axis(raw.ry) * (if (invert.rightY) -1f else 1f),
        )
}
