package io.embodit.dronesight.domain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D25: the bundled GLB (assets/quad_A.glb) goes through [DroneCalibration] exactly as the scene does: the box and the
 * nose are measured from the FILE (accessor min / max + node transforms; front LEDs = material ledF), then the
 * calibrated model must have its bottom centre on the origin (±1 cm), its nose along -Z (< 2°) and the design size
 * (< 10 %). All materials OPAQUE.
 */
class CalibrationTest {
    private fun glb(): JSONObject {
        val f = listOf(File("src/main/assets/quad_A.glb"), File("app/src/main/assets/quad_A.glb")).first { it.exists() }
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val len = b.getInt(12)
        return JSONObject(String(f.readBytes(), 20, len, Charsets.UTF_8))
    }

    private data class Box(val min: Vec3, val max: Vec3)

    /** World box per node (translation + rotation applied to the 8 corners of the accessor box). */
    private fun nodeBoxes(j: JSONObject): List<Triple<String, String, Box>> {
        val nodes = j.getJSONArray("nodes")
        val meshes = j.getJSONArray("meshes")
        val acc = j.getJSONArray("accessors")
        val mats = j.getJSONArray("materials")
        val out = ArrayList<Triple<String, String, Box>>()
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            if (!n.has("mesh")) continue
            val t = n.optJSONArray("translation")?.let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) } ?: Vec3.ZERO
            val q = n.optJSONArray("rotation")?.let { Quat4(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) } ?: Quat4.IDENTITY
            val prims = meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
            for (k in 0 until prims.length()) {
                val p = prims.getJSONObject(k)
                val a = acc.getJSONObject(p.getJSONObject("attributes").getInt("POSITION"))
                val mn = a.getJSONArray("min")
                val mx = a.getJSONArray("max")
                val corners = ArrayList<Vec3>()
                for (x in 0..1) for (y in 0..1) for (z in 0..1) {
                    val c = Vec3((if (x == 0) mn else mx).getDouble(0).toFloat(), (if (y == 0) mn else mx).getDouble(1).toFloat(), (if (z == 0) mn else mx).getDouble(2).toFloat())
                    corners += q.rotate(c) + t
                }
                val lo = Vec3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z })
                val hi = Vec3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })
                out += Triple(n.getString("name"), mats.getJSONObject(p.getInt("material")).getString("name"), Box(lo, hi))
            }
        }
        return out
    }

    @Test
    fun calibratedDroneSitsOnTheOriginNoseMinusZDesignSize() {
        val j = glb()
        val boxes = nodeBoxes(j)
        val lo = Vec3(boxes.minOf { it.third.min.x }, boxes.minOf { it.third.min.y }, boxes.minOf { it.third.min.z })
        val hi = Vec3(boxes.maxOf { it.third.max.x }, boxes.maxOf { it.third.max.y }, boxes.maxOf { it.third.max.z })
        val center = (lo + hi) * 0.5f
        val half = (hi - lo) * 0.5f
        fun mid(b: Box) = (b.min + b.max) * 0.5f
        val front = boxes.filter { it.second == "ledF" }.map { mid(it.third) }
        val back = boxes.filter { it.second == "ledR" }.map { mid(it.third) }
        assertTrue("front + rear LEDs", front.isNotEmpty() && back.isNotEmpty())
        val nose = (front.reduce { a, b -> a + b } * 0.5f - back.reduce { a, b -> a + b } * 0.5f).let { Vec3(it.x, 0f, it.z).normalized() }
        val cal = DroneCalibration.of(center, half, nose)
        // bottom centre -> origin (±1 cm)
        val bottom = cal.apply(Vec3(center.x, lo.y, center.z))
        assertTrue("bottom centre $bottom", bottom.length < 0.01f)
        // nose -> -Z (< 2°)
        val n = cal.rotation.rotate(nose)
        val ang = Math.toDegrees(kotlin.math.acos(n.dot(DroneCalibration.droneForward).coerceIn(-1f, 1f).toDouble()))
        assertTrue("nose $n angle $ang", ang < 2.0)
        // size within 10 % of the design (axis box over the props 0.558 m, height 0.19 m)
        val span = maxOf(half.x, half.z) * 2f * cal.scale
        val height = half.y * 2f * cal.scale
        assertTrue("span $span vs ${DroneCalibration.SPAN_M}", kotlin.math.abs(span - DroneCalibration.SPAN_M) / DroneCalibration.SPAN_M < 0.10f)
        assertTrue("height $height vs ${DroneCalibration.HEIGHT_M}", kotlin.math.abs(height - DroneCalibration.HEIGHT_M) / DroneCalibration.HEIGHT_M < 0.10f)
        // wheelbase: diagonal motor-to-motor distance (the four motors are one object "motor": its box edge minus the
        // motor radius 0.014 m is the motor centre offset on each axis)
        val motor = boxes.first { it.first.startsWith("motor") }.third
        val off = (motor.max.x - 0.014f) * cal.scale
        val wb = 2f * off * 1.41421356f
        assertTrue("wheelbase $wb", kotlin.math.abs(wb - DroneCalibration.WHEELBASE_M) / DroneCalibration.WHEELBASE_M < 0.10f)
    }

    @Test
    fun everyMaterialIsOpaque() {
        val mats = glb().getJSONArray("materials")
        for (i in 0 until mats.length()) {
            val m = mats.getJSONObject(i)
            assertTrue("${m.optString("name")} alphaMode=${m.optString("alphaMode")}", m.optString("alphaMode", "OPAQUE") == "OPAQUE")
        }
    }

    @Test
    fun calibrationTurnsAnyNoseOntoMinusZ() {
        for (deg in listOf(0, 45, 90, 135, 180, -90)) {
            val r = Math.toRadians(deg.toDouble())
            val nose = Vec3(-kotlin.math.sin(r).toFloat(), 0f, -kotlin.math.cos(r).toFloat())
            val cal = DroneCalibration.of(Vec3(0.2f, 0.1f, -0.3f), Vec3(0.279f, 0.095f, 0.279f), nose)
            val n = cal.rotation.rotate(nose)
            assertTrue("deg $deg -> $n", (n - DroneCalibration.droneForward).length < 0.02f)
            assertTrue("upright", (cal.rotation.rotate(Vec3.UP) - Vec3.UP).length < 1e-3f)
        }
    }
}
