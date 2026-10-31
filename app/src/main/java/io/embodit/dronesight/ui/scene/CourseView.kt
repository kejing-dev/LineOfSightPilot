package io.embodit.dronesight.ui.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import io.embodit.dronesight.domain.CourseGeometry
import io.embodit.dronesight.domain.Gate
import io.embodit.dronesight.domain.Quat4
import io.embodit.dronesight.domain.Tolerance
import io.embodit.dronesight.domain.Vec3
import io.embodit.dronesight.domain.WaypointRing
import io.embodit.dronesight.domain.Yaw
import io.embodit.dronesight.platform.DsLog
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared look of the course (all programmatic, design entities3d): meshes + materials made once on the IO thread. */
internal class CourseKit(
    val cube: MeshResource,
    val cyl: MeshResource,
    val disc: MeshResource,
    val ring: MeshResource,
    val padRing: MeshResource,
    val spinRing: MeshResource,
    val wpRing: MeshResource,
    val label: MeshResource,
    val white: Material,
    val frame: Material,
    val frameDim: Material,
    val bound: Material,
    val path: Material,
    val green: Material,
    val greenSolid: Material,
    val yellow: Material,
    val yellowSolid: Material,
    val orange: Material,
    val orangeSolid: Material,
    val padOrange: Material,
    val padWhite: Material,
    val padInk: Material,
    val shadow: Material,
    val blur: Material,
    val labelMats: Map<String, Material>,
    val labelTex: Map<String, TextureResource>,
)

/** One bar (a unit cube stretched between two points). */
internal class Bar(val e: ModelEntity) {
    fun span(a: Vec3, b: Vec3, t: Float) = e.bar(a, b, t)

    fun mat(m: Material) = e.setMaterial(m)
}

/**
 * A slot for one merged mesh: the course's static line work is merged into a handful of meshes (one entity per
 * material and part) built on the IO thread; a re-layout builds new meshes and swaps the entities. ~60 separate
 * entities made the 1-fps emulator's render thread fall behind until the runtime watchdog killed it (build.log).
 */
internal class Merged(private val parent: Entity, private val name: String) {
    var e: ModelEntity? = null
        private set
    private var mesh: MeshResource? = null
    private var material: Material? = null
    var enabled = true
        set(v) {
            field = v
            e?.on(v)
        }

    /** The current mesh (a re-layout refills it on the IO thread instead of creating a new entity). */
    val currentMesh: MeshResource?
        get() = mesh

    fun set(newMesh: MeshResource, m: Material) {
        val old = e
        val oldMesh = mesh
        val ne = model(newMesh, m, name, parent)
        ne.on(enabled)
        e = ne
        mesh = newMesh
        material = m
        runCatching { old?.destroy(true) }
        runCatching { oldMesh?.close() }
    }

    fun mat(m: Material) {
        if (m === material) return
        material = m
        e?.setMaterial(m)
    }

    fun close() {
        runCatching { e?.destroy(true) }
        runCatching { mesh?.close() }
        e = null
        mesh = null
    }
}

/**
 * The course in the room (design entities3d pad / hoverBox / spinRing / poles / waypointGates / bounds / windSock),
 * children of the course root (course-local metres, floor at y = 0). Static line work is merged per part; progress
 * pieces (box progress, spin arcs) stay separate entities because they switch on one by one. Main thread only, except
 * the mesh builds inside [layout] (IO).
 */
internal class CourseView(private val root: Entity, private val kit: CourseKit) {
    lateinit var pad: Entity
    private lateinit var padLenient: Entity
    lateinit var box: Entity
    private lateinit var boxEdges: Merged
    private val boxProgress = ArrayList<Bar>()
    lateinit var spin: Entity
    private val spinArcs = ArrayList<Bar>()
    private val spinLabels = ArrayList<ModelEntity>()
    lateinit var poles: Entity
    private lateinit var polesOrange: Merged
    private lateinit var polesWhite: Merged
    lateinit var eight: Entity
    private val gates = ArrayList<Merged>()
    private val gateTags = ArrayList<ModelEntity>()
    private lateinit var pathDashes: Merged
    lateinit var pathArrows: Merged
    lateinit var rings: Entity
    private val ringEnts = ArrayList<ModelEntity>()
    private val ringLines = ArrayList<Bar>()
    lateinit var bounds: Entity
    private val faces = ArrayList<Merged>()
    lateinit var sock: Entity
    private lateinit var sockBag: Entity
    private var geo: CourseGeometry? = null

    private fun bar(parent: Entity, name: String, m: Material): Bar = Bar(model(kit.cube, m, name, parent))

    /** Builds the entities that never change shape (pad, rings, progress pieces, labels, sock). */
    suspend fun build() {
        pad = Entity().named("pad").also { root.addChild(it) }
        model(kit.padRing, kit.padOrange, "padRing", pad).setLocal(position = Vec3(0f, 0.004f, 0f), rotation = flat())
        kotlinx.coroutines.delay(16)
        model(kit.disc, kit.padWhite, "padFace", pad).setLocal(position = Vec3(0f, 0.003f, 0f), rotation = flat(), scale = Vec3(0.84f, 0.84f, 1f))
        kotlinx.coroutines.delay(16)
        // the H: two uprights and a bar, one mesh (no text texture)
        val h = withContext(Dispatchers.IO) {
            MeshBuilder(72).apply {
                // flat: 2 mm thick, on top of the pad face
                addBox(Vec3(-0.12f, 0.006f, 0f), Vec3(0.05f, 0.002f, 0.34f))
                addBox(Vec3(0.12f, 0.006f, 0f), Vec3(0.05f, 0.002f, 0.34f))
                addBox(Vec3(0f, 0.006f, 0f), Vec3(0.24f, 0.002f, 0.05f))
            }.build("padH")
        }
        model(h, kit.padInk, "padH", pad)
        kotlinx.coroutines.delay(16)
        padLenient = model(kit.ring, kit.frame, "padLenient", pad).also { it.setLocal(position = Vec3(0f, 0.002f, 0f), rotation = flat(), scale = Vec3(1.4f, 1.4f, 1f)) }
        kotlinx.coroutines.delay(16)
        box = Entity().named("hoverBox").also { root.addChild(it) }
        boxEdges = Merged(box, "boxEdges")
        repeat(12) { boxProgress += bar(box, "boxProg$it", kit.greenSolid).also { b -> b.e.enabled = false }; kotlinx.coroutines.delay(16) }
        kotlinx.coroutines.delay(16)
        spin = Entity().named("spinRing").also { root.addChild(it) }
        model(kit.spinRing, kit.frame, "spinRingLine", spin).setLocal(position = Vec3(0f, 0.004f, 0f), rotation = flat(), scale = Vec3(1.2f, 1.2f, 1f))
        kotlinx.coroutines.delay(16)
        val ticks = withContext(Dispatchers.IO) {
            MeshBuilder(12 * 24).apply {
                for (i in 0 until 12) {
                    val a = 2 * PI * i / 12
                    val p = Vec3((0.6 * sin(a)).toFloat(), 0.005f, (-0.6 * cos(a)).toFloat())
                    addBar(p * 0.92f, p * 1.08f, 0.012f)
                }
            }.build("spinTicks")
        }
        model(ticks, kit.white, "spinTicks", spin)
        kotlinx.coroutines.delay(16)
        for (i in 0 until 12) {
            val a1 = 2 * PI * (i + 0.08) / 12
            val a2 = 2 * PI * (i + 0.92) / 12
            kotlinx.coroutines.delay(16)
            spinArcs += bar(spin, "spinArc$i", kit.greenSolid).also { b ->
                b.span(Vec3((0.6 * sin(a1)).toFloat(), 0.007f, (-0.6 * cos(a1)).toFloat()), Vec3((0.6 * sin(a2)).toFloat(), 0.007f, (-0.6 * cos(a2)).toFloat()), 0.035f)
                b.e.enabled = false
            }
        }
        kotlinx.coroutines.delay(16)
        for ((i, deg) in listOf("0°", "90°", "180°", "270°").withIndex()) {
            val m = kit.labelMats["spin$i"] ?: continue
            val a = PI / 2 * i
            val p = Vec3((0.78 * sin(a)).toFloat(), 0.12f, (-0.78 * cos(a)).toFloat())
            spinLabels += model(kit.label, m, "spinLabel$deg", spin).also { it.setLocal(position = p, scale = Vec3(0.6f, 0.6f, 1f)) }
            kotlinx.coroutines.delay(16)
        }
        kotlinx.coroutines.delay(16)
        poles = Entity().named("poles").also { root.addChild(it) }
        polesOrange = Merged(poles, "polesOrange")
        polesWhite = Merged(poles, "polesWhite")
        eight = Entity().named("eight").also { root.addChild(it) }
        for (g in 0 until 4) {
            gates += Merged(eight, "gate${g + 1}")
            val m = kit.labelMats["gate${g + 1}"]
            if (m != null) gateTags += model(kit.label, m, "gateTag${g + 1}", eight)
            kotlinx.coroutines.delay(16)
        }
        pathDashes = Merged(eight, "pathDashes")
        pathArrows = Merged(eight, "pathArrows")
        kotlinx.coroutines.delay(16)
        rings = Entity().named("rings").also { root.addChild(it) }
        repeat(6) { i ->
            ringEnts += model(kit.wpRing, kit.frame, "ring$i", rings).also { it.enabled = false }
            kotlinx.coroutines.delay(16)
            ringLines += bar(rings, "ringLine$i", kit.bound).also { it.e.enabled = false }
            kotlinx.coroutines.delay(16)
        }
        bounds = Entity().named("bounds").also { root.addChild(it) }
        repeat(4) { f -> faces += Merged(bounds, "bound$f") }
        kotlinx.coroutines.delay(16)
        sock = Entity().named("windSock").also { root.addChild(it) }
        model(kit.cyl, kit.padWhite, "sockPole", sock).setLocal(position = Vec3(0f, 0.6f, 0f), scale = Vec3(0.025f, 1.2f, 0.025f))
        kotlinx.coroutines.delay(16)
        sockBag = Entity().named("sockBag").also { sock.addChild(it) }
        sockBag.setLocal(position = Vec3(0f, 1.15f, 0f))
        for (i in 0 until 4) {
            val k = 1f - i * 0.18f
            model(kit.cyl, if (i % 2 == 0) kit.padOrange else kit.padWhite, "sock$i", sockBag).setLocal(position = Vec3(0f, 0f, -(0.05f + i * 0.1f)), rotation = Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat()), scale = Vec3(0.14f * k, 0.1f, 0.14f * k))
            kotlinx.coroutines.delay(16)
        }
    }

    // =========================================================================================== layout

    /** Places everything for [g] (pole gap, hover height, tolerance; field ceiling [ceiling]); meshes built on IO. */
    suspend fun layout(g: CourseGeometry, ceiling: Float) {
        geo = g
        labelHead = null
        boundsKey = -1
        lastGate = -1
        lastBoxIn = null
        val b = g.hoverBox
        val c = b.center
        val hh = b.half
        val corners = listOf(Vec3(-hh.x, -hh.y, -hh.z), Vec3(hh.x, -hh.y, -hh.z), Vec3(hh.x, -hh.y, hh.z), Vec3(-hh.x, -hh.y, hh.z)).map { it + c }
        val top = corners.map { it + Vec3(0f, 2 * hh.y, 0f) }
        val half = CourseGeometry.FIELD_HALF_M
        val faceCorners = listOf(
            Vec3(-half, 0f, -half) to Vec3(half, 0f, -half), // N (far)
            Vec3(half, 0f, -half) to Vec3(half, 0f, half), // E
            Vec3(half, 0f, half) to Vec3(-half, 0f, half), // S (near you)
            Vec3(-half, 0f, half) to Vec3(-half, 0f, -half), // W
        )
        val pts = g.path(0.25f)
        val slots = listOf(boxEdges, polesOrange, polesWhite, pathDashes, pathArrows) + gates + faces
        val mats = listOf(kit.frame, kit.padOrange, kit.padWhite, kit.path, kit.path) + List(4) { kit.frame } + List(4) { kit.bound }
        // geometry and GPU upload on the IO thread: an existing mesh is refilled in place (no entity is created on the
        // main thread after the first layout; creating 13 entities there blocked input long enough for an ANR)
        val fresh = withContext(Dispatchers.IO) {
            val builders = buildLayout(g, ceiling, corners, top, faceCorners, pts)
            builders.mapIndexed { k, b ->
                val existing = slots[k].currentMesh
                if (existing != null && b.replace(existing)) null else b.build(slotName(k))
            }
        }
        for ((k, mesh) in fresh.withIndex()) {
            if (mesh != null) {
                slots[k].set(mesh, mats[k])
                kotlinx.coroutines.delay(16)
            }
        }
        // progress segments along the bottom edge nearest to you (+Z)
        val a = corners[3] + Vec3(0f, 0.02f, 0.01f)
        val e = corners[2] + Vec3(0f, 0.02f, 0.01f)
        for (i in 0 until 12) boxProgress[i].span(a.lerp(e, (i + 0.1f) / 12f), a.lerp(e, (i + 0.9f) / 12f), 0.03f)
        padLenient.on(g.tolerance == Tolerance.LENIENT)
        for ((i, gate) in g.gates.withIndex()) placeGateTag(i, gate)
        sock.setLocal(position = Vec3(half - 0.4f, 0f, -half + 0.4f))
        DsLog.i("DS-GEOM merged meshes=${slots.size} created=${fresh.count { it != null }} pathPoints=${pts.size}")
    }

    private fun slotName(k: Int): String = when (k) { 0 -> "boxEdges"; 1 -> "polesOrange"; 2 -> "polesWhite"; 3 -> "pathDashes"; 4 -> "pathArrows"; in 5..8 -> "gate${k - 4}"; else -> "bound${k - 9}" }

    /** The 13 merged meshes' geometry (IO thread). Order = slots in [layout]. */
    private fun buildLayout(g: CourseGeometry, ceiling: Float, corners: List<Vec3>, top: List<Vec3>, faceCorners: List<Pair<Vec3, Vec3>>, pts: List<Pair<Vec3, Vec3>>): List<MeshBuilder> {
        val boxM = MeshBuilder(12 * 24).apply {
            for (i in 0 until 4) {
                addBar(corners[i], corners[(i + 1) % 4], EDGE)
                addBar(top[i], top[(i + 1) % 4], EDGE)
                addBar(corners[i], top[i], EDGE)
            }
        }
        val stripe = CourseGeometry.POLE_M / 6
        val orange = MeshBuilder(400)
        val white = MeshBuilder(400)
        for (p in listOf(g.leftPole, g.rightPole)) {
            for (i in 0 until 6) (if (i % 2 == 0) orange else white).addCylinder(p, 0.02f, stripe * i, stripe * (i + 1), 12)
            orange.addBar(Vec3(p.x, CourseGeometry.POLE_M - 0.08f, p.z), Vec3(p.x + 0.2f, CourseGeometry.POLE_M - 0.08f, p.z), 0.12f)
        }
        val gateM = g.gates.map { gate ->
            MeshBuilder(72).apply {
                val across = gate.across
                val a = gate.at + across * (gate.width / 2)
                val bb = gate.at - across * (gate.width / 2)
                addBar(a, a + Vec3(0f, gate.height, 0f), 0.012f)
                addBar(bb, bb + Vec3(0f, gate.height, 0f), 0.012f)
                addBar(a + Vec3(0f, gate.height, 0f), bb + Vec3(0f, gate.height, 0f), 0.012f)
            }
        }
        val dashes = MeshBuilder(pts.size * 24).apply {
            for ((p, dir) in pts) addBar(p + Vec3(0f, 0.006f, 0f) - dir * 0.075f, p + Vec3(0f, 0.006f, 0f) + dir * 0.075f, 0.02f)
        }
        val arrows = MeshBuilder(ARROWS * 48).apply {
            val step = maxOf(1, pts.size / ARROWS)
            for (i in 0 until ARROWS) {
                val idx = i * step + step / 2
                if (idx >= pts.size) break
                val (p, dir) = pts[idx]
                val side = Vec3(-dir.z, 0f, dir.x)
                val tip = p + dir * 0.09f + Vec3(0f, 0.008f, 0f)
                addBar(tip - dir * 0.09f + side * 0.07f, tip, 0.018f)
                addBar(tip - dir * 0.09f - side * 0.07f, tip, 0.018f)
            }
        }
        val faceM = faceCorners.map { (p0, p1) ->
            MeshBuilder(96).apply {
                val lo = 0.3f
                addBar(p0 + Vec3(0f, lo, 0f), p1 + Vec3(0f, lo, 0f), 0.012f)
                addBar(p0 + Vec3(0f, ceiling, 0f), p1 + Vec3(0f, ceiling, 0f), 0.012f)
                addBar(p0 + Vec3(0f, lo, 0f), p0 + Vec3(0f, ceiling, 0f), 0.012f)
                addBar(p1 + Vec3(0f, lo, 0f), p1 + Vec3(0f, ceiling, 0f), 0.012f)
            }
        }
        return listOf(boxM, orange, white, dashes, arrows) + gateM + faceM
    }

    private fun placeGateTag(i: Int, g: Gate) {
        // the number tag sits on top of the frame, a little to the side so the two centre tags (1 and 3) do not overlap
        gateTags.getOrNull(i)?.setLocal(position = g.at + Vec3(0f, g.height + 0.12f, 0f) + g.across * (if (i == 0) 0.32f else if (i == 2) -0.32f else 0f))
    }

    fun layoutRings(rs: List<WaypointRing>) {
        for (i in 0 until 6) {
            val e = ringEnts[i]
            val l = ringLines[i]
            if (i < rs.size) {
                val r = rs[i]
                e.setLocal(position = r.center, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), r.normal), scale = Vec3(r.diameter, r.diameter, 1f))
                l.span(Vec3(r.center.x, 0.005f, r.center.z), r.center - Vec3(0f, r.radius, 0f), 0.01f)
                e.on(true)
                l.e.on(true)
            } else {
                e.on(false)
                l.e.on(false)
            }
        }
    }

    /** Path arrows only with direction hints on (design entities3d.poles). */
    fun arrowsOn(v: Boolean) {
        if (pathArrows.enabled != v) pathArrows.enabled = v
    }

    // =========================================================================================== per frame

    private var lastBoxIn: Boolean? = null
    private var boxFlashUntil = 0L
    private var boxFlashing = false
    private var lastProgress = -1
    private var lastSpin = -1
    private var lastGate = -1
    private var gateFlash = -1
    private var gateFlashUntil = 0L
    private var lastRing = -1

    /** Box colour + progress (fraction 0..1). Orange flash for 0.3 s after leaving the box. */
    fun boxState(inBox: Boolean, fraction: Float, now: Long) {
        if (lastBoxIn == true && !inBox) boxFlashUntil = now + 300
        val flashing = now < boxFlashUntil
        if (lastBoxIn != inBox || flashing != boxFlashing) boxEdges.mat(if (flashing) kit.orange else if (inBox) kit.green else kit.frame)
        boxFlashing = flashing
        lastBoxIn = inBox
        val n = (fraction * 12f).toInt().coerceIn(0, 12)
        if (n != lastProgress) {
            for (i in 0 until 12) boxProgress[i].e.on(i < n)
            lastProgress = n
        }
    }

    fun spinState(fraction: Float) {
        val n = (fraction * 12f).toInt().coerceIn(0, 12)
        if (n == lastSpin) return
        for (i in 0 until 12) spinArcs[i].e.on(i < n)
        lastSpin = n
    }

    /** Gates: the next one yellow, passed green, wrong-order flash orange. */
    fun gateState(next: Int, now: Long) {
        val flashing = now < gateFlashUntil
        if (next == lastGate && !flashing && gateFlash < 0) return
        if (!flashing) gateFlash = -1
        lastGate = next
        for (i in 0 until 4) {
            val n = i + 1
            gates[i].mat(
                when {
                    n == gateFlash -> kit.orangeSolid
                    n < next -> kit.greenSolid
                    n == next -> kit.yellowSolid
                    else -> kit.frame
                }
            )
        }
    }

    fun flashGate(n: Int, now: Long) {
        gateFlash = n
        gateFlashUntil = now + 400
        lastGate = -1
    }

    fun ringState(next: Int) {
        if (next == lastRing) return
        lastRing = next
        for (i in 0 until 6) ringEnts[i].setMaterial(if (i < next) kit.greenSolid else if (i == next) kit.yellowSolid else kit.frame)
    }

    private var boundsKey = -1

    /** Which faces of the field show (all while placing; else the ones the drone is near), orange when outside. */
    fun boundsState(all: Boolean, drone: Vec3?, outside: Boolean) {
        val half = CourseGeometry.FIELD_HALF_M
        val near = BooleanArray(4)
        if (drone != null) {
            near[0] = drone.z < -half + 0.5f
            near[1] = drone.x > half - 0.5f
            near[2] = drone.z > half - 0.5f
            near[3] = drone.x < -half + 0.5f
        }
        val key = (if (all) 1 else 0) or (if (outside) 2 else 0) or near.withIndex().sumOf { (i, b) -> if (b) 4 shl i else 0 }
        if (key == boundsKey) return
        boundsKey = key
        var any = all
        for (f in 0 until 4) {
            val on = all || near[f]
            any = any || on
            faces[f].enabled = on
            if (on) faces[f].mat(if (!all && outside) kit.orangeSolid else kit.bound)
        }
        bounds.on(any)
    }

    private var labelHead: Vec3? = null

    /** Billboard the labels (yaw only) towards [headLocal]. */
    fun faceLabels(headLocal: Vec3) {
        val last = labelHead
        if (last != null && (last - headLocal).length < 0.01f) return
        labelHead = headLocal
        for (l in spinLabels) billboard(l, headLocal)
        for (l in gateTags) billboard(l, headLocal)
    }

    private fun billboard(e: Entity, head: Vec3) {
        val p = e.transformOrNull?.position ?: return
        val d = head - Vec3(p.x, p.y, p.z)
        e.setLocal(rotation = Quat4.axisAngle(Vec3.UP, atan2(d.x, d.z)))
    }

    /** Wind sock points where the wind goes; shakes in gusts. */
    fun sockState(dirYaw: Float, gusting: Boolean, now: Long) {
        val wobble = if (gusting) 0.25f * sin(now / 90.0).toFloat() else 0.05f * sin(now / 700.0).toFloat()
        sockBag.setLocal(rotation = Quat4.axisAngle(Vec3.UP, Yaw.wrap(dirYaw + wobble)) * Quat4.axisAngle(Vec3.RIGHT, if (gusting) -0.05f else -0.35f))
    }

    fun close() {
        (listOf(boxEdges, polesOrange, polesWhite, pathDashes, pathArrows) + gates + faces).forEach { it.close() }
    }

    companion object {
        const val EDGE = 0.012f
        const val ARROWS = 10

        fun flat(): Quat4 = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat())
    }
}
