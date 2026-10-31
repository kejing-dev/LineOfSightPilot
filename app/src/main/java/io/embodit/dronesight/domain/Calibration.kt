package io.embodit.dronesight.domain

/**
 * D25: the one place where the external drone model (dronemodel/renders/quad_A.glb, Blender bpy, design default "A
 * 素白训练机") is calibrated. The calibration node puts the bottom centre of the model's box (the skids' contact plane)
 * on the origin, turns the nose (the red front LEDs) onto the app's drone-forward -Z, keeps +Y up and scales the box
 * to the design size (entities3d.drone: wheelbase 0.45 m, props Ø 0.24 m, so the axis-aligned box is
 * 2 x (0.45 / 2 x cos 45° + 0.12) = 0.558 m wide; the 0.69 m "outline incl. props" is the diagonal; height 0.19 m).
 * Flying, yawing and tilting only
 * move the PARENT of that node; nothing writes the model's own transform. Every instance shares [of]'s result.
 */
object DroneCalibration {
    /** Design size (entities3d.drone.candidates.glbConvention). */
    const val WHEELBASE_M = 0.45f
    const val PROP_D_M = 0.24f
    const val HEIGHT_M = 0.19f

    /** Axis-aligned width of the box over the props (what a measured AABB shows): 0.558 m. */
    val SPAN_M: Float = 2f * (WHEELBASE_M / 2f * 0.70710677f + PROP_D_M / 2f)

    /** Diagonal outline incl. props (design "含桨外廓"): 0.69 m. */
    val OUTLINE_M: Float = WHEELBASE_M + PROP_D_M

    /** App convention: the drone at yaw 0 has its nose along -Z. */
    val droneForward: Vec3 = Vec3(0f, 0f, -1f)

    data class Calib(val offset: Vec3, val rotation: Quat4, val scale: Float) {
        /** Model point (file space) -> calibrated space. */
        fun apply(p: Vec3): Vec3 = rotation.rotate((p + offset) * scale)
    }

    /**
     * From the model's measured box (file space, centre + half extents) and the nose direction measured in the file
     * (from the front LEDs minus the rear LEDs): offset = -bottom centre, rotation = nose -> -Z about +Y, scale =
     * design span / measured span (the larger horizontal extent of the box, over the props).
     */
    fun of(center: Vec3, half: Vec3, fileNose: Vec3): Calib {
        val bottom = Vec3(center.x, center.y - half.y, center.z)
        val flatNose = Vec3(fileNose.x, 0f, fileNose.z).let { if (it.length < 1e-4f) droneForward else it.normalized() }
        val rot = Quat4.fromTo(flatNose, droneForward).let { q ->
            // a 180° turn from fromTo may pick a tilted axis: force the vertical
            if (flatNose.dot(droneForward) < -0.999f) Quat4.axisAngle(Vec3.UP, Math.PI.toFloat()) else q
        }
        val span = maxOf(half.x, half.z) * 2f
        val scale = if (span > 1e-4f) SPAN_M / span else 1f
        return Calib(-bottom, rot, scale)
    }
}
