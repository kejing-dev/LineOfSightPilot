package io.embodit.dronesight.ui.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.math.Vector3
import io.embodit.dronesight.domain.DroneCalibration
import io.embodit.dronesight.domain.Quat4
import io.embodit.dronesight.domain.Vec3
import io.embodit.dronesight.domain.Yaw
import io.embodit.dronesight.platform.DsLog
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The drone and everything that follows it (design entities3d drone / shadow / altitudeLine): root (position + yaw,
 * written every frame from the flight model) -> tilt (visual pitch / roll) -> calib (D25, written ONCE from the
 * measured box: bottom centre to the origin, nose to -Z, 0.69 m outline) -> the GLB. Shadow, nose arrow, height line
 * and the direction-hint arrows lie on the floor under it. Main thread only.
 */
internal class DroneView(private val courseRoot: Entity, private val kit: CourseKit) {
    val root: Entity = Entity().named("drone").also { courseRoot.addChild(it) }
    private val tilt: Entity = Entity().named("droneTilt").also { root.addChild(it) }
    val calib: Entity = Entity().named("droneCalib").also { tilt.addChild(it) }
    var model: Entity? = null
        private set
    var calibration: DroneCalibration.Calib? = null
        private set
    private val props = ArrayList<Entity>()
    private val blurs = ArrayList<ModelEntity>()
    private var propSpin = 0f

    // floor marks
    private val floor = Entity().named("droneFloor").also { courseRoot.addChild(it) }
    private val shadow: Entity = Entity().named("shadow").also { floor.addChild(it) }
    private val shadowDiscs = listOf(1f, 0.78f, 0.56f).mapIndexed { i, k -> model(kit.disc, kit.shadow, "shadow$i", shadow).also { it.setLocal(position = Vec3(0f, 0.010f + i * 0.0008f, 0f), rotation = CourseView.flat(), scale = Vec3(k, k, 1f)) } }
    private val noseA = Bar(model(kit.cube, kit.white, "noseArrowA", floor))
    private val noseB = Bar(model(kit.cube, kit.white, "noseArrowB", floor))
    private val dashes = ArrayList<Bar>()
    private val ticks = ArrayList<Bar>()

    /** The height line's pieces, created a few per frame (main-thread entity creation waits for the runtime). */
    suspend fun prepare() {
        for (i in 0 until DASHES) {
            dashes += Bar(model(kit.cube, kit.frame, "hdash$i", courseRoot)).also { b -> b.e.enabled = false }
            if (i % 5 == 4) kotlinx.coroutines.delay(16)
        }
        for (i in 0 until 5) ticks += Bar(model(kit.cube, kit.white, "htick$i", courseRoot)).also { b -> b.e.enabled = false }
    }
    // direction hints: "right stick ->" and "right stick ^" in the world (shaft + two head bars + label each)
    private val hintRight = List(3) { Bar(model(kit.cube, kit.yellowSolid, "hintR$it", floor)) }
    private val hintUp = List(3) { Bar(model(kit.cube, kit.yellowSolid, "hintU$it", floor)) }
    private val hintRightLabel: ModelEntity = model(kit.label, kit.labelMats["dirRight"] ?: kit.white, "hintRLabel", courseRoot)
    private val hintUpLabel: ModelEntity = model(kit.label, kit.labelMats["dirUp"] ?: kit.white, "hintULabel", courseRoot)
    // landing offset line (S07)
    private val offsetLine = Bar(model(kit.cube, kit.white, "offsetLine", courseRoot)).also { it.e.enabled = false }

    var visible = true
        set(v) {
            field = v
            root.on(v)
            floor.on(v)
        }

    /** Debug `lite shadow 0`: hide the ground shadow disc alone. */
    fun shadowOn(v: Boolean) {
        shadow.on(v)
    }

    /** Attaches the loaded GLB and calibrates it from its measured box (D25). Returns a log line. */
    fun attach(e: Entity): String {
        calib.addChild(e.named("droneModel"))
        model = e
        val box = runCatching { e.getVisualBounds(calib) }.getOrNull()
        val c = box?.center?.let { Vec3(it.x, it.y, it.z) } ?: Vec3(0f, 0.095f, 0f)
        val h = box?.halfExtent?.let { Vec3(it.x, it.y, it.z) } ?: Vec3(0.345f, 0.095f, 0.345f)
        // nose in the file: the front (red) LEDs are the object "ledF", the rear (green) "ledR" (tools/model/runtime_glb.py
        // joins the design GLB per material; CalibrationTest checks it); measured here rather than assumed
        val front = runCatching { e.findEntity("ledF")?.getVisualBounds(calib)?.center }.getOrNull()
        val back = runCatching { e.findEntity("ledR")?.getVisualBounds(calib)?.center }.getOrNull()
        val nose = if (front != null && back != null) Vec3(front.x - back.x, 0f, front.z - back.z).normalized() else DroneCalibration.droneForward
        val cal = DroneCalibration.of(c, h, nose)
        calibration = cal
        calib.setLocal(position = cal.rotation.rotate(cal.offset * cal.scale), rotation = cal.rotation, scale = Vec3(cal.scale, cal.scale, cal.scale))
        // props: the GLB's own blades turn at low rpm, a blur disc (35 %) replaces them at speed
        e.findEntity("propA")?.let { props += it }
        // one blur disc over each motor (design wheelbase 0.45 m: motors at ±0.159 m on both axes, props at 0.172 m)
        val m = DroneCalibration.WHEELBASE_M / 2f * 0.70710677f
        for ((sx, sz) in listOf(1f to -1f, -1f to -1f, -1f to 1f, 1f to 1f)) {
            blurs += model(kit.disc, kit.blur, "blur", root).also { d ->
                d.setLocal(position = Vec3(sx * m, 0.176f, sz * m), rotation = CourseView.flat(), scale = Vec3(DroneCalibration.PROP_D_M, DroneCalibration.PROP_D_M, 1f))
                d.enabled = false
            }
        }
        val after = runCatching { e.getVisualBounds(root) }.getOrNull()
        val line = String.format(
            Locale.US,
            "CALIB file c=(%.3f,%.3f,%.3f) h=(%.3f,%.3f,%.3f) nose=(%.2f,%.2f) -> offset=%s scale=%.4f rot=%s | calibrated c=(%.3f,%.3f,%.3f) h=(%.3f,%.3f,%.3f) props=%d",
            c.x, c.y, c.z, h.x, h.y, h.z, nose.x, nose.z, DsLog.pos(cal.offset), cal.scale, DsLog.quat(cal.rotation),
            after?.center?.x ?: 0f, after?.center?.y ?: 0f, after?.center?.z ?: 0f, after?.halfExtent?.x ?: 0f, after?.halfExtent?.y ?: 0f, after?.halfExtent?.z ?: 0f, props.size,
        )
        DsLog.i(line)
        return line
    }

    /**
     * Replaces the GLB's own (PBR) materials with flat ones from [byName] (material name -> material), keyed by the
     * object name (tools/model/runtime_glb.py names each object after its material). Returns how many were replaced.
     */
    fun replaceMaterials(byName: Map<String, com.pico.spatial.core.ecs.resource.Material>): Int {
        val m = model ?: return 0
        var n = 0
        fun walk(e: Entity) {
            val name = runCatching { e.getName() }.getOrNull()
            val mat = byName[name]
            val mc = e.components[com.pico.spatial.core.ecs.ModelComponent::class.java]
            if (mat != null && mc != null) {
                val count = runCatching { mc.materials.size }.getOrDefault(0)
                for (i in 0 until count) runCatching { mc.materials[i] = mat }
                n++
            }
            for (c in runCatching { e.getChildren() }.getOrDefault(emptyArray())) walk(c)
        }
        walk(m)
        DsLog.i("DRONE materials replaced=$n")
        return n
    }

    /** Box of the calibrated model in the drone root's space (instance list). */
    fun boundsInRoot(): Pair<Vec3, Vec3>? {
        val m = model ?: return null
        val b = runCatching { m.getVisualBounds(root) }.getOrNull() ?: return null
        return Vec3(b.center.x, b.center.y, b.center.z) to Vec3(b.halfExtent.x, b.halfExtent.y, b.halfExtent.z)
    }

    /**
     * Every frame: [pos] course-local (y = height), [yaw] nose heading, visual [pitch] / [roll], [rotor] 0..1, [dt] s.
     */
    private var lastPoseKey: List<Float>? = null

    fun pose(pos: Vec3, yaw: Float, pitch: Float, roll: Float, rotor: Float, dt: Float, extraLift: Float = 0f, extraRoll: Float = 0f) {
        // nothing moved and the rotors are still: no writes (every write is a runtime command)
        val key = listOf(pos.x, pos.y, pos.z, yaw, pitch, roll, extraLift, extraRoll, if (rotor > 0.12f) 1f else 0f)
        if (key == lastPoseKey && rotor <= 0.12f) return
        lastPoseKey = key
        root.setLocal(position = Vec3(pos.x, pos.y + extraLift, pos.z), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        tilt.setLocal(rotation = Quat4.axisAngle(Vec3.RIGHT, -pitch) * Quat4.axisAngle(Vec3(0f, 0f, 1f), -roll - extraRoll))
        // props: the GLB blades have their pivot at the drone centre (vertices baked), so they are not spun; above a
        // little rotor speed they hide and the 35 % blur discs show
        val fast = rotor > 0.12f
        propSpin = (propSpin + rotor * 40f * dt) % (2 * PI.toFloat())
        for (p in props) p.on(!fast)
        for ((i, b) in blurs.withIndex()) {
            b.on(fast)
            if (fast) b.setLocal(rotation = Quat4.axisAngle(Vec3.UP, if (i % 2 == 0) propSpin else -propSpin) * CourseView.flat())
        }
        // shadow: Ø 0.5 m / 40 % on the floor, Ø 0.7 m / 25 % at 2.5 m
        val k = (pos.y / 2.5f).coerceIn(0f, 1f)
        val d = 0.5f + 0.2f * k
        floor.setLocal(position = Vec3(pos.x, 0f, pos.z), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        // three stacked 16 % discs (centre ~40 %); higher up the inner disc goes (outer ring stays): lighter and wider
        shadow.setLocal(scale = Vec3(d, 1f, d))
        shadowDiscs[2].on(k < 0.6f)
        // nose arrow: a white V at the shadow's front edge, pointing along the nose (-Z of the drone)
        val tip = Vec3(0f, 0.014f, -(d / 2 + 0.12f))
        noseA.span(tip + Vec3(-0.09f, 0f, 0.1f), tip, 0.03f)
        noseB.span(tip + Vec3(0.09f, 0f, 0.1f), tip, 0.03f)
    }

    /** Height line from the drone's belly to the floor (0.08 on / 0.06 off) + a tick every 0.5 m. */
    private var lineOff = false
    private var hintsOff = false

    fun heightLine(pos: Vec3, on: Boolean) {
        if (!on) {
            if (lineOff) return
            lineOff = true
            for (b in dashes) b.e.enabled = false
            for (t in ticks) t.e.enabled = false
            return
        }
        lineOff = false
        val h = pos.y
        for ((i, b) in dashes.withIndex()) {
            val y0 = i * 0.14f
            val y1 = y0 + 0.08f
            val show = on && y1 < h - 0.02f
            b.e.enabled = show
            if (show) b.span(Vec3(pos.x, y0 + 0.01f, pos.z), Vec3(pos.x, y1, pos.z), 0.008f)
        }
        for ((i, t) in ticks.withIndex()) {
            val y = 0.5f * (i + 1)
            val show = on && y < h - 0.05f
            t.e.enabled = show
            if (show) t.span(Vec3(pos.x - 0.04f, y, pos.z), Vec3(pos.x + 0.04f, y, pos.z), 0.008f)
        }
    }

    /**
     * Direction hints (rules.noseIndication): where "right stick ->" and "right stick ^" take the drone in the world,
     * drawn on the floor next to the shadow. Labels billboard towards [headLocal].
     */
    fun hints(on: Boolean, pos: Vec3, yaw: Float, headLocal: Vec3) {
        if (!on && hintsOff) return
        hintsOff = !on
        for (b in hintRight + hintUp) b.e.enabled = on
        hintRightLabel.enabled = on
        hintUpLabel.enabled = on
        if (!on) return
        // floor-local (rotated with the drone): right = +X, forward = -Z
        fun arrow(bars: List<Bar>, dir: Vec3) {
            val start = dir * 0.42f + Vec3(0f, 0.01f, 0f)
            val end = start + dir * 0.25f
            val side = Vec3(-dir.z, 0f, dir.x)
            bars[0].span(start, end, 0.025f)
            bars[1].span(end - dir * 0.08f + side * 0.06f, end, 0.025f)
            bars[2].span(end - dir * 0.08f - side * 0.06f, end, 0.025f)
        }
        arrow(hintRight, Vec3(1f, 0f, 0f))
        arrow(hintUp, Vec3(0f, 0f, -1f))
        val q = Quat4.axisAngle(Vec3.UP, yaw)
        val rp = Vec3(pos.x, 0f, pos.z) + q.rotate(Vec3(0.85f, 0.12f, 0f))
        val up = Vec3(pos.x, 0f, pos.z) + q.rotate(Vec3(0f, 0.12f, -0.85f))
        hintRightLabel.setLocal(position = rp, rotation = Quat4.axisAngle(Vec3.UP, atan2(headLocal.x - rp.x, headLocal.z - rp.z)), scale = Vec3(0.8f, 0.8f, 1f))
        hintUpLabel.setLocal(position = up, rotation = Quat4.axisAngle(Vec3.UP, atan2(headLocal.x - up.x, headLocal.z - up.z)), scale = Vec3(0.8f, 0.8f, 1f))
    }

    /** Landing offset: a short line from the pad centre to the touch-down point (S07). */
    private var offsetShown: Vec3? = null

    fun offset(on: Boolean, at: Vec3?) {
        val want = if (on) at else null
        if (want == offsetShown) return
        offsetShown = want
        offsetLine.e.enabled = on && at != null
        if (on && at != null) offsetLine.span(Vec3(0f, 0.012f, 0f), Vec3(at.x, 0.012f, at.z), 0.015f)
    }

    fun fade(opacity: Float) {
        root.setOpacity(opacity)
    }

    val hasOpacity: Boolean
        get() = root.hasOpacityComponent

    companion object {
        const val DASHES = 20
    }
}

@Suppress("unused")
private fun unusedVec(v: Vector3) = v

@Suppress("unused")
private fun yawOf(v: Vec3) = Yaw.of(v)

@Suppress("unused")
private val unusedMaterial: Material? = null
