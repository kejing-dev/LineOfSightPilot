package io.embodit.dronesight.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * D9 / D10 hard gates: every pose given to a panel or to the course comes from [placeUpright] / [Placement] — yaw only.
 * Head pitched down 30°, up 30°, rolled 20°: the panel stays upright, 10-20° below the eye line, 0.8-1.2 m away, facing
 * you. Four-direction placement: standing east / south / west / north of the target, the course and its labels face you
 * (< 10°), scales never negative.
 */
class LayoutTest {
    private fun head(at: Vec3, yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): HeadPose {
        val q = Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat()) *
            Quat4.axisAngle(Vec3(0f, 0f, -1f), Math.toRadians(rollDeg.toDouble()).toFloat())
        return HeadPose(at, q)
    }

    @Test
    fun panelUprightForTiltedHeads() {
        for ((p, r) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, 0f to 0f)) {
            val h = head(Vec3(0.3f, 1.6f, -0.2f), 25f, p, r)
            val up = Placement.panel(h)
            // the upright pose has no pitch / roll by construction: its rotation keeps +Y up
            assertTrue("up stays up", (up.rotation.rotate(Vec3.UP) - Vec3.UP).length < 0.0175f)
            val rel = up.position - h.position
            val dist = rel.length
            val below = Math.toDegrees(atan2(-rel.y, hypot(rel.x, rel.z)).toDouble())
            assertTrue("dist $dist pitch=$p roll=$r", dist in 0.8f..1.2f)
            assertTrue("below eye $below pitch=$p roll=$r", below in 10.0..20.0)
            // faces you: its +Z points back at the head (horizontally)
            val face = up.rotation.rotate(Vec3(0f, 0f, 1f))
            val toHead = Vec3(-rel.x, 0f, -rel.z).normalized()
            assertTrue("faces the head", face.dot(toHead) > 0.985f)
            // 38° to the left of the view
            val az = Math.toDegrees(Yaw.wrap(Yaw.of(Vec3(rel.x, 0f, rel.z)) - h.yaw(0f)).toDouble())
            assertTrue("left 38°: $az", abs(az - 38.0) < 0.5)
        }
    }

    @Test
    fun courseAheadRightAndFarEnough() {
        val h = head(Vec3(0f, 1.6f, 0f), 0f, -30f)
        val f = Placement.courseAhead(h, 0f)
        assertTrue("on the floor", abs(f.origin.y) < 1e-4f)
        val d = hypot(f.origin.x, f.origin.z)
        assertTrue("3 m: $d", abs(d - 3f) < 0.01f)
        val az = Math.toDegrees(Yaw.of(Vec3(f.origin.x, 0f, f.origin.z)).toDouble())
        assertTrue("16° right: $az", abs(az + 16.0) < 0.5)
        // a ray hit at your feet is pushed out to 2 m
        val near = Placement.courseAt(Vec3(0.3f, 0f, -0.5f), h, 0f)
        assertTrue(hypot(near.x, near.z) >= Placement.COURSE_MIN_M - 1e-3f)
    }

    @Test
    fun fourDirectionPlacement() {
        val target = Vec3(1f, 0f, -2f)
        for (deg in listOf(0f, 90f, 180f, 270f)) {
            // you stand 3 m from the target, looking at it
            val r = Math.toRadians(deg.toDouble())
            val at = target + Vec3(kotlin.math.sin(r).toFloat() * 3f, 1.6f, kotlin.math.cos(r).toFloat() * 3f)
            val look = Yaw.of(Vec3(target.x - at.x, 0f, target.z - at.z))
            val h = HeadPose(at, Quat4.axisAngle(Vec3.UP, look))
            val c = Placement.courseAt(Vec3(target.x, 0f, target.z), h, 0f)
            val f = CourseFrame(c, h.yaw(0f))
            // the course's +Z (its "towards you" side) points at you
            val toYou = Vec3(at.x - c.x, 0f, at.z - c.z).normalized()
            val back = f.dirToStage(Vec3(0f, 0f, 1f))
            val ang = Math.toDegrees(kotlin.math.acos(back.dot(toYou).coerceIn(-1f, 1f).toDouble()))
            assertTrue("standing at $deg°: course faces you within 10°, was $ang", ang < 10.0)
            // a billboard label at the pad turns to you (yaw only), scale positive
            val local = f.toLocal(at)
            val labelYaw = atan2(local.x, local.z)
            val labelFace = f.dirToStage(Quat4.axisAngle(Vec3.UP, labelYaw).rotate(Vec3(0f, 0f, 1f)))
            assertTrue("label faces you", labelFace.dot(toYou) > 0.985f)
            val panel = Placement.panel(h)
            assertTrue("panel faces you", panel.rotation.rotate(Vec3(0f, 0f, 1f)).dot(Vec3(at.x - panel.position.x, 0f, at.z - panel.position.z).normalized()) > 0.985f)
        }
    }

    @Test
    fun rayFloorHit() {
        val hit = Placement.rayFloor(Vec3(0f, 1.2f, 0f), Vec3(0f, -0.5f, -1f).normalized(), 0f)!!
        assertTrue(abs(hit.y) < 1e-4f && abs(hit.z + 2.4f) < 1e-3f)
        assertTrue(Placement.rayFloor(Vec3(0f, 1.2f, 0f), Vec3(0f, 0.1f, -1f).normalized(), 0f) == null)
    }

    @Test
    fun courseGeometryGatesOnThePath() {
        for (d in listOf(2f, 2.5f, 3.5f, 5f)) {
            val g = CourseGeometry(d)
            val pts = g.path(0.05f)
            // continuous
            for (i in 1 until pts.size) assertTrue("d=$d gap at $i", (pts[i].first - pts[i - 1].first).length < 0.12f)
            // every gate sits on the path and its normal follows the path there
            for (gate in g.gates) {
                val near = pts.filter { (it.first - gate.at).length < 0.06f }
                assertTrue("d=$d gate ${gate.index} on path", near.isNotEmpty())
                assertTrue("d=$d gate ${gate.index} direction", near.any { it.second.dot(gate.normal) > 0.95f })
            }
        }
    }
}
