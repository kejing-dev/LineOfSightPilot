package io.embodit.dronesight.ui.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.dronesight.domain.CourseFrame
import io.embodit.dronesight.domain.CourseGeometry
import io.embodit.dronesight.domain.FlightPhase
import io.embodit.dronesight.domain.HeadPose
import io.embodit.dronesight.domain.LessonId
import io.embodit.dronesight.domain.Orient
import io.embodit.dronesight.domain.Placement
import io.embodit.dronesight.domain.Quat4
import io.embodit.dronesight.domain.Session
import io.embodit.dronesight.domain.StickShaping
import io.embodit.dronesight.domain.Sticks
import io.embodit.dronesight.domain.Task
import io.embodit.dronesight.domain.Upright
import io.embodit.dronesight.domain.Vec3
import io.embodit.dronesight.domain.WindLevel
import io.embodit.dronesight.domain.Yaw
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.platform.input.Controllers
import io.embodit.dronesight.platform.input.Ray
import io.embodit.dronesight.platform.sense.FloorFinder
import io.embodit.dronesight.platform.tracking.HeadTracker
import io.embodit.dronesight.ui.DsController
import io.embodit.dronesight.ui.DsUiState
import io.embodit.dronesight.ui.FlightSnap
import io.embodit.dronesight.ui.Lang
import io.embodit.dronesight.ui.Screen
import io.embodit.dronesight.ui.Ui
import io.embodit.dronesight.ui.components.PanelSizes
import io.embodit.dronesight.ui.components.VirtualSticks
import io.embodit.dronesight.ui.theme.DsColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: DroneScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in DsScreen). */
class DroneFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    const val CONTROL = "control"
    const val STICKS = "sticks"
    const val TAG = "tag"
    const val DIALOG = "dialog"
    val pool = listOf(CONTROL, STICKS, TAG, DIALOG)
}

/**
 * The 3D side (design entities3d): the course on your floor, the drone and its floor marks, the four panels. Per-frame
 * work happens in [onFrame] only: head + controllers -> placement (S03) or the flight [Session] (stepped here, judged in
 * the domain) -> transforms. The panels hear about the flight through DsController.onSnap, only when what they show
 * changes. Main thread only.
 */
class DroneScene {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    private val controllers = Controllers()
    private val floorFinder = FloorFinder()
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var courseRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var kit: CourseKit? = null
    private var course: CourseView? = null
    private var drone: DroneView? = null
    private var floorRing: Entity? = null
    private var catcher: Entity? = null
    private var grab: Entity? = null
    private var buildJob: Job? = null
    private var built = false
    private var modelReady = false
    private var attached = false
    private var paused = false

    // head
    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var headSinceMs = -1L
    private var blockSinceMs = -1L

    // floor and course
    private var floorPlaneY: Float? = null
    private var headYAtFloor = 1.6f
    private var floorY = 0f
    private var floorStartMs = -1L
    private var ghost: CourseFrame? = null
    var frame: CourseFrame? = null
        private set
    private var ghostTarget: Vec3? = null
    private var lastScreen: Screen? = null
    private var laidOut: Triple<Float, Float, Any>? = null
    private var ringsFor: Session? = null
    private var courseWritten: CourseFrame? = null
    private var layoutJob: Job? = null
    private var sun: Entity? = null
    private var grabWritten: Upright? = null

    // panels
    private var panelPose: Upright? = null
    private var panelEpoch = -1
    private var panelFacePoint: Vec3? = null
    private var dialogPose: Upright? = null
    private var dragStart: Vec3? = null
    private var dragHand: Vec3 = Vec3.ZERO
    private var dragging = false
    private var panelFade = 1f

    // flight
    private var session: Session? = null
    private var sessionEpoch = -1
    private var lastSnap: FlightSnap? = null
    private var takeoffHoldS = 0f
    private var crashAtMs = -1L
    private var landingAt: Vec3? = null
    private var lastFlightLogMs = 0L

    // timing / logs
    private var lastFrameMs = 0L
    private var startMs = 0L
    private var startupLogs = 0
    private var lastErrorMs = 0L
    private var frames = 0L
    private var lastFrameLogMs = 0L

    // ---- debug seams (src/debug drives them; they feed the same inputs a user would)
    private var debugSticks: Sticks? = null
    private var debugSticksUntil = 0L
    var debugRay: Vec3? = null
    var timeScale = 1f
    var frozen = false
    var forceNoFloor = false
    var forceFloor = false
    var forceBlock: String? = null
    var forceScan = false

    /** Debug `lite NAME 0|1`: switch heavy parts off to find what stalls the emulator's render thread. */
    val lite = HashMap<String, Boolean>()
    private fun liteOn(name: String) = lite[name] ?: true
    private val isEmulator: Boolean =
        android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" } ||
            listOf(android.os.Build.DEVICE, android.os.Build.PRODUCT, android.os.Build.FINGERPRINT).any { it.contains("emulator", ignoreCase = true) }
    var fTest = false

    /** Debug `view DEG`: placements (panel, course, dialog) use the head turned by DEG (+ = left), like turning your
     * head; the emulator camera cannot turn, so QA brings the left panel (view 38) or the course into the picture. */
    var viewYawDeg = 0f
    private val placePose: HeadPose
        get() = if (viewYawDeg == 0f) pose else HeadPose(pose.position, Quat4.axisAngle(Vec3.UP, Math.toRadians(viewYawDeg.toDouble()).toFloat()) * pose.rotation)
    private val debugPath = ArrayDeque<Pair<Vec3, Float?>>()

    // =========================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        startMs = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.CONTROL, PanelSizes.CONTROL_W, PanelSizes.CONTROL_MAX_H, PanelSizes.SCALE).maxPitch = 0f
        rig.world(PanelIds.STICKS, PanelSizes.STICKS_W, PanelSizes.STICKS_H, PanelSizes.SCALE).maxPitch = 0.35f
        rig.world(PanelIds.TAG, PanelSizes.TAG_W, PanelSizes.TAG_H, PanelSizes.SCALE).maxPitch = 0f
        rig.world(PanelIds.DIALOG, PanelSizes.DIALOG_W, PanelSizes.DIALOG_H, PanelSizes.SCALE).maxPitch = 0f
        head.start()
        controllers.start()
        ActiveScene.current = this
        attached = true
        DsController.quitHook = { releaseAll() }
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root)
                } catch (t: Throwable) {
                    if (t !is CancellationException) DsLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        DsLog.i("SCENE attached root offset=${DsLog.pos(offset.domain())}")
    }

    private suspend fun frameDelay() = delay(16)

    /** Resources on the IO thread (ANR rule), entities in batches with a frame between them. */
    private suspend fun build(root: Entity) {
        delay(150)
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val k = withContext(Dispatchers.IO) { makeKit(sh) }
        kit = k
        val cr = Entity().named("course")
        root.addChild(cr)
        cr.enabled = false
        courseRoot = cr
        val sun = Entity().named("sun")
        this.sun = sun
        sun.components.set(DirectionalLightComponent(Color4(1f, 0.97f, 0.92f, 1f), SUN_LUX, false))
        root.addChild(sun)
        // from above-front, 45° (design entities3d.light)
        sun.setLocal(rotation = Quat4.fromTo(Vec3(0f, 0f, -1f), Vec3(0.2f, -0.7f, -0.68f).normalized()))
        frameDelay()
        val cv = CourseView(cr, k)
        cv.build()
        // first layout here (entities are created while S00 shows "准备中…", with frames in between); later layouts
        // only refill the meshes on the IO thread
        val p0 = DsController.state.value.prefs
        val geo0 = CourseGeometry(p0.poleDist, p0.hoverH, p0.tolerance)
        cv.layout(geo0, p0.ceiling)
        laidOut = Triple(geo0.poleDistM, geo0.hoverM, (geo0.tolerance to p0.ceiling) as Any)
        course = cv
        frameDelay()
        drone = DroneView(cr, k).also { frameDelay(); it.prepare() }
        frameDelay()
        // floor flash (S02) and the floor catcher for a pinch on the floor without controllers (S03)
        floorRing = model(k.ring, k.frame, "floorFlash", root).also { it.enabled = false }
        val c = Entity().named("floorCatcher")
        c.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(12f, 0.02f, 12f))), physicsMaterial = PhysicsMaterialResource()))
        c.components.set(InteractableComponent())
        root.addChild(c)
        c.enabled = false
        catcher = c
        // the control panel's grab bar (D21: fixed + draggable): a pill above its top edge
        val g = Entity().named("grab:control")
        model(k.cube, k.frame, "grabBar", g).setLocal(scale = Vec3(0.08f, 0.008f, 0.008f))
        g.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(0.18f, 0.045f, 0.04f))), physicsMaterial = PhysicsMaterialResource()))
        g.components.set(InteractableComponent())
        root.addChild(g)
        g.enabled = false
        grab = g
        built = true
        DsLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        // the drone GLB (design default A)
        val r = runCatching { Entity.loadSuspend("asset://$DRONE_ASSET") }
        val e = r.getOrNull()
        if (e == null || !attached) {
            DsLog.e("MODEL $DRONE_ASSET load failed: ${r.exceptionOrNull()}")
            return
        }
        drone?.attach(e)
        modelReady = true
        DsLog.i("MODEL $DRONE_ASSET loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        logInstances()
    }

    private fun makeKit(sh: SharedResources): CourseKit {
        val m = SceneMaterials(sh)
        val labels = HashMap<String, com.pico.spatial.core.ecs.resource.Material>()
        val tex = HashMap<String, com.pico.spatial.core.ecs.resource.TextureResource>()
        fun label(key: String, text: String) {
            val t = m.texture(labelBitmap(text, 256, 128))
            tex[key] = t
            labels[key] = m.unlit(Color.White, t)
        }
        for (i in 1..4) label("gate$i", "$i")
        for ((i, s) in listOf("0°", "90°", "180°", "270°").withIndex()) label("spin$i", s)
        val zh = DsController.state.value.prefs.lang == Lang.ZH || (DsController.state.value.prefs.lang == Lang.SYSTEM && Locale.getDefault().language == "zh")
        label("dirRight", if (zh) "右杆→" else "R stick →")
        label("dirUp", if (zh) "右杆↑" else "R stick ↑")
        label("ftest", "F↑")
        return CourseKit(
            cube = sh.keep(Shapes.cube("cube")),
            cyl = sh.keep(Shapes.cylinder("cyl", 24)),
            disc = sh.keep(Shapes.disc("disc", 40)),
            ring = sh.keep(Shapes.ring("ring", 0.485f, 56)),
            padRing = sh.keep(Shapes.ring("padRing", 0.42f, 56)),
            spinRing = sh.keep(Shapes.ring("spinRing", 0.475f, 64)),
            wpRing = sh.keep(Shapes.ring("wpRing", 0.45f, 48)),
            label = sh.keep(quadXY(0.24f, 0.12f, "label")),
            white = m.unlit(DsColors.FrameWhite),
            frame = m.sheer(DsColors.FrameWhite, 0.6f),
            frameDim = m.sheer(DsColors.FrameWhite, 0.35f),
            bound = m.sheer(DsColors.FrameWhite, 0.22f),
            path = m.sheer(DsColors.FrameWhite, 0.4f),
            green = m.sheer(DsColors.Passable, 0.85f),
            greenSolid = m.unlit(DsColors.Passable),
            yellow = m.sheer(DsColors.Interaction, 0.85f),
            yellowSolid = m.unlit(DsColors.Interaction),
            orange = m.sheer(DsColors.Alert, 0.9f),
            orangeSolid = m.unlit(DsColors.Alert),
            padOrange = m.unlit(DsColors.PadOrange),
            padWhite = m.unlit(DsColors.PadWhite),
            padInk = m.unlit(DsColors.PadInk),
            // no texture on a transparent material: a textured see-through disc stalled the emulator's render thread
            // (bisect 2026-10-31, build.log); the soft edge is three stacked plain discs instead
            shadow = m.sheer(DsColors.Shadow, 0.16f),
            blur = m.sheer(Color(0xFF9AA3AB), 0.35f), // design-style: fixed-figma-color prop blur disc
            labelMats = labels,
            labelTex = tex,
        )
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        controllers.stop()
        floorFinder.stop()
        DsController.soundOrNull?.rotor(0f, 0f)
        DsLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        controllers.start()
        lastFrameMs = 0L
        DsLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    private fun releaseAll() {
        head.stop()
        controllers.stop()
        floorFinder.stop()
    }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        DsController.quitHook = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        releaseAll()
        DsController.soundOrNull?.rotor(0f, 0f)
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { course?.close() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        kit = null
        course = null
        drone = null
        stageRoot = null
        courseRoot = null
        rig = null
        built = false
        modelReady = false
        DsLog.i("SCENE detached")
    }

    // =========================================================================================== per frame

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, MAX_DT)
        lastFrameMs = now
        frames++
        head.pose()?.let {
            // a first frame of all zeros + identity is "not tracking yet" (hand-sign teacher lesson)
            val zero = it.position.length < 1e-3f && abs(it.rotation.w) > 0.99999f
            if (!zero) {
                pose = it
                if (!hasHead) headSinceMs = now
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        val ui = DsController.state.value
        try {
            stepStart(ui, now)
            if (built) {
                stepInput(ui, now, dt)
                stepFloor(ui, now)
                stepCourse(ui, now, dt)
                stepFlight(DsController.state.value, now, dt)
                placePanels(DsController.state.value, now)
            }
        } catch (t: Throwable) {
            // an engine call failing for one frame (runtime restarting) must not take the app down
            if (now - lastErrorMs > 2000) {
                lastErrorMs = now
                DsLog.e("FRAME step failed: $t", t)
            }
        }
        rig.faceViewer(pose.position)
        stepLogs(DsController.state.value, now)
    }

    /** S00 -> S01 / S02 once the scene is built and the head is tracked; S13 when the head stays unavailable 2 s. */
    private fun stepStart(ui: DsUiState, now: Long) {
        if (ui.screen != Screen.STARTING) {
            blockSinceMs = -1L
            return
        }
        val reason = forceBlock ?: head.blockReason() ?: (if (!hasHead && now - startMs > HEAD_WAIT_MS) "head-not-ready" else null)
        if (reason != null) {
            if (blockSinceMs < 0) blockSinceMs = now
            if (now - blockSinceMs > BLOCK_AFTER_MS) DsController.onBlocked(reason)
            return
        }
        blockSinceMs = -1L
        if (built && hasHead && now - headSinceMs > 300 && (modelReady || now - startMs > MODEL_WAIT_MS)) {
            if (panelPose == null) placeControlPanel("first")
            DsController.onSceneReady()
        }
    }

    // ------------------------------------------------------------------------------------------- input

    private var faceTaken = 0

    private fun rawSticks(now: Long): Sticks {
        val hw = controllers.sticks()
        val vs = Sticks(VirtualSticks.lx, VirtualSticks.ly, VirtualSticks.rx, VirtualSticks.ry)
        val dbg = if (now < debugSticksUntil) debugSticks ?: Sticks.ZERO else Sticks.ZERO
        return Sticks.merge(Sticks.merge(hw, vs), dbg)
    }

    private fun stepInput(ui: DsUiState, now: Long, dt: Float) {
        controllers.present()?.let { DsController.onControllerPresence(it) }
        val faces = controllers.takeFacePresses()
        if (faces > 0) repeat(faces) { DsController.onEvent(Ui.PauseKey) }
        val triggers = controllers.takeTriggerPresses()
        if (triggers > 0 && ui.screen == Screen.PLACING && ghost != null) {
            DsLog.i("PLACE trigger")
            DsController.onEvent(Ui.PlaceHere)
        }
        // take-off by the left stick: up > 0.5 held 1 s (design rules.flight)
        if (ui.screen == Screen.READY) {
            val ly = StickShaping.axis(rawSticks(now).ly) * if (ui.prefs.invert.leftY) -1f else 1f
            val sess = session
            if (ly > 0.5f && sess != null && sess.flight.phase == FlightPhase.GROUND) {
                takeoffHoldS += dt
                if (takeoffHoldS >= 1f) {
                    takeoffHoldS = 0f
                    DsLog.i("TAKEOFF by stick")
                    DsController.onEvent(Ui.TakeOff)
                }
            } else takeoffHoldS = 0f
        } else takeoffHoldS = 0f
    }

    // ------------------------------------------------------------------------------------------- floor

    private fun estimatedFloor(ui: DsUiState): Float = (floorPlaneY ?: (headYAtFloor - if (ui.prefs.seated) Placement.SIT_EYE_M else Placement.STAND_EYE_M)) + ui.prefs.floorOffset

    private fun stepFloor(ui: DsUiState, now: Long) {
        if (ui.screen == Screen.FINDING_FLOOR) {
            if (floorStartMs < 0) {
                floorStartMs = now
                headYAtFloor = pose.position.y
                // plane detection for <= 5 s (design I2); debug `nofloor 1` skips it (estimate path)
                val scan = !forceNoFloor && !forceFloor
                if (scan) floorFinder.start { y -> main.post { onFloorPlane(y) } }
                DsLog.i("FLOOR looking headY=${headYAtFloor} scan=$scan emulator=$isEmulator (hw=${android.os.Build.HARDWARE} device=${android.os.Build.DEVICE}) forceNoFloor=$forceNoFloor forceFloor=$forceFloor")
            }
            if (forceFloor) onFloorPlane(pose.position.y - Placement.STAND_EYE_M)
            else if (forceNoFloor || now - floorStartMs > FLOOR_TIMEOUT_MS) {
                floorFinder.stop()
                if (ui.hint != io.embodit.dronesight.ui.Hint.S02_TIMEOUT) DsController.onEvent(Ui.FloorTimeout)
            }
        } else {
            if (floorStartMs >= 0) floorFinder.stop()
            floorStartMs = -1L
        }
        floorY = estimatedFloor(ui)
        // the floor flash: a soft ring under you for 0.5 s when FLOOR was found
        floorRing?.let { r ->
            val t = (now - floorFlashMs) / 500f
            r.on(floorFlashMs > 0 && t in 0f..1f)
            if (r.enabled) {
                val k = 0.4f + 1.6f * t
                r.setLocal(position = Vec3(pose.position.x, floorY + 0.01f, pose.position.z), rotation = CourseView.flat(), scale = Vec3(k, k, 1f))
            }
        }
    }

    private var floorFlashMs = -1L

    private fun onFloorPlane(y: Float) {
        floorFinder.stop()
        val ui = DsController.state.value
        if (ui.screen != Screen.FINDING_FLOOR) return
        floorPlaneY = y
        floorFlashMs = SystemClock.elapsedRealtime()
        DsLog.i("FLOOR plane y=$y head=${DsLog.pos(pose.position)}")
        DsController.onEvent(Ui.FloorFound(y))
    }

    // ------------------------------------------------------------------------------------------- course

    private fun stepCourse(ui: DsUiState, now: Long, dt: Float) {
        val cr = courseRoot ?: return
        val cv = course ?: return
        // geometry follows the settings live (pole gap / hover height / tolerance / ceiling)
        val geo = CourseGeometry(ui.prefs.poleDist, ui.prefs.hoverH, ui.prefs.tolerance)
        val key = Triple(geo.poleDistM, geo.hoverM, (geo.tolerance to ui.prefs.ceiling) as Any)
        if (key != laidOut && layoutJob?.isActive != true) {
            laidOut = key
            val ceiling = ui.prefs.ceiling
            layoutJob = scope?.launch { runCatching { cv.layout(geo, ceiling) }.onFailure { if (it !is CancellationException) DsLog.e("LAYOUT failed: $it", it) } }
            DsLog.i(String.format(Locale.US, "DS-GEOM poles=%.1f hover=%.1f tol=%s ceiling=%.1f gates=%s", geo.poleDistM, geo.hoverM, geo.tolerance, ui.prefs.ceiling, geo.gates.map { DsLog.pos(it.at) }))
        }
        val placing = ui.screen == Screen.PLACING
        if (placing) {
            val g = ghost ?: Placement.courseAhead(placePose, floorY).also { DsLog.i("PLACE ghost start ${DsLog.pos(it.origin)} yaw=${Math.toDegrees(it.yaw.toDouble()).toInt()}") }
            // the ghost follows the controller ray's floor point (or a pinch / debug ray); left stick turns it
            val hit = rayHit()
            val target = hit?.let { Placement.courseAt(it, pose, floorY) } ?: ghostTarget ?: g.origin
            ghostTarget = target
            val k = (dt / 0.1f).coerceIn(0f, 1f)
            val o = g.origin + (Vec3(target.x, floorY, target.z) - g.origin) * k
            val lx = StickShaping.axis(rawSticks(now).lx, 0.15f, 0f)
            val turned = Yaw.wrap(g.yaw - lx * Math.toRadians(45.0).toFloat() * dt)
            ghost = CourseFrame(Vec3(o.x, floorY, o.z), turned)
            // the ghost is marked by the field bounds being shown (design: 60 % see-through). No opacity component on
            // the course root: blending ~250 entities stalled the emulator's render thread (runtime watchdog, build.log)
        } else if (lastScreen == Screen.PLACING && ghost != null && ui.placed) {
            // locked: the course stays here until "重摆场地" (profile D1: placement has an end)
            frame = ghost
            ghost = null
            ghostTarget = null
            val windYaw = (java.util.Random(now).nextFloat() * 2f * PI.toFloat()) - PI.toFloat()
            DsController.onCoursePlaced(windYaw)
            DsLog.i(String.format(Locale.US, "PLACE locked origin=%s yaw=%.1f floorY=%.3f headDist=%.2f", DsLog.pos(frame!!.origin), Math.toDegrees(frame!!.yaw.toDouble()), floorY, hypot(frame!!.origin.x - pose.position.x, frame!!.origin.z - pose.position.z)))
        }
        if (placing && lastScreen != Screen.PLACING) {
            ghost = frame ?: Placement.courseAhead(placePose, floorY)
            ghostTarget = null
        }
        lastScreen = ui.screen
        // floor height can change in settings (± 0.05 m, sitting / standing): the whole course moves with it
        frame?.let { f -> if (abs(f.origin.y - floorY) > 1e-4f) frame = f.copy(origin = Vec3(f.origin.x, floorY, f.origin.z)) }
        val f = ghost ?: frame
        val showCourse = f != null && ui.screen != Screen.STARTING && ui.screen != Screen.WELCOME && ui.screen != Screen.FINDING_FLOOR && ui.screen != Screen.BLOCKED
        cr.on(showCourse && liteOn("course"))
        catcher?.let { c ->
            c.on(placing && !ui.hasController && liteOn("catcher"))
            if (c.enabled) c.setLocal(position = Vec3(pose.position.x, floorY - 0.01f, pose.position.z))
        }
        if (f == null || !showCourse) return
        if (f != courseWritten) {
            cr.setLocal(position = f.origin, rotation = Quat4.axisAngle(Vec3.UP, f.yaw))
            courseWritten = f
        }
        // which parts show for the lesson (S05: "本课的框 / 环 / 桩门 / 航点亮起，其余隐藏")
        val sess = session
        val task = sess?.task
        val lesson = ui.lesson
        val flightPage = ui.isFlight || ui.screen == Screen.RESULT || (ui.screen == Screen.SETTINGS && ui.back != Screen.PICKER)
        val boxOn = placing || !flightPage || task in setOf(Task.HOVER_TAIL, Task.HOVER_NOSE, Task.HOVER_ANY, Task.SPIN) || lesson == LessonId.F1
        cv.box.on(boxOn)
        cv.spin.on(flightPage && task == Task.SPIN)
        cv.poles.on(true)
        cv.eight.on(flightPage && (task == Task.EIGHT || lesson == LessonId.F1))
        cv.arrowsOn(ui.directionHints)
        cv.rings.on(flightPage && lesson == LessonId.F2)
        cv.sock.on((sess?.flight?.wind?.level ?: ui.prefs.wind) != WindLevel.NONE && flightPage)
        if (cv.rings.enabled && sess != null && ringsFor !== sess) {
            cv.layoutRings(sess.rings)
            ringsFor = sess
        }
        val headLocal = f.toLocal(pose.position)
        cv.faceLabels(headLocal)
        if (sess != null && flightPage) {
            val pr = sess.progress()
            cv.boxState(sess.inBox, if (task == Task.SPIN) 0f else pr.fraction, now)
            if (task == Task.SPIN) cv.spinState(pr.fraction)
            if (task == Task.EIGHT) cv.gateState(sess.nextGate, now)
            if (lesson == LessonId.F2) cv.ringState(sess.nextRing)
            cv.sockState(sess.flight.wind.dirYaw, sess.flight.wind.gusting, now)
        } else {
            cv.boxState(false, 0f, now)
        }
        if (!liteOn("bounds")) cv.bounds.on(false) else cv.boundsState(placing, if (sess != null && sess.flight.airborne) sess.flight.pos else null, sess?.outside == true)
    }

    /** Where the aiming ray meets the floor (Stage): controller ray, else the debug ray (x right, z ahead of the head). */
    private fun rayHit(): Vec3? {
        debugRay?.let { r ->
            val yaw = pose.yaw(0f)
            val fwd = Yaw.dir(yaw)
            val right = Vec3(-fwd.z, 0f, fwd.x)
            return pose.position + right * r.x + fwd * r.z
        }
        val ray: Ray = controllers.ray() ?: return null
        return Placement.rayFloor(ray.origin, ray.dir, floorY)
    }

    // ------------------------------------------------------------------------------------------- flight

    private fun stepFlight(ui: DsUiState, now: Long, dt: Float) {
        val dv = drone ?: return
        val f = frame ?: ghost
        val sess = DsController.session
        if (sess !== session || ui.sessionEpoch != sessionEpoch) {
            session = sess
            sessionEpoch = ui.sessionEpoch
            sess?.flight?.park(Vec3.ZERO, 0f)
            lastSnap = null
            crashAtMs = -1L
            landingAt = null
            debugPath.clear()
            DsLog.i("FLIGHT new session lesson=${sess?.lesson}")
        }
        val showDrone = f != null && (ui.isFlight || ui.screen == Screen.PICKER || ui.screen == Screen.RESULT || ui.screen == Screen.SETTINGS || ui.screen == Screen.EXIT_CONFIRM || ui.screen == Screen.PLACING)
        dv.visible = showDrone && modelReady && liteOn("drone")
        sun?.on(liteOn("sun"))
        dv.shadowOn(liteOn("shadow"))
        if (f == null) return
        val headLocal = f.toLocal(pose.position)
        // step
        val stepping = sess != null && !frozen && (ui.screen == Screen.READY || ui.screen == Screen.FLYING)
        if (stepping && sess != null) {
            // debug path: teleports through gates (the judges see a real crossing between two frames)
            debugPath.removeFirstOrNull()?.let { (p, yaw) ->
                sess.flight.hover(p, yaw ?: sess.flight.yaw)
            }
            val sticks = StickShaping.shape(rawSticks(now), ui.prefs.invert)
            val evs = sess.step(dt * timeScale, sticks, headLocal)
            if (evs.isNotEmpty()) {
                for (e in evs) {
                    when (e) {
                        is io.embodit.dronesight.domain.Ev.GateWrong -> course?.flashGate(e.n, now)
                        is io.embodit.dronesight.domain.Ev.Landed -> landingAt = sess.flight.pos
                        io.embodit.dronesight.domain.Ev.Crash -> crashAtMs = now
                        else -> Unit
                    }
                }
                DsLog.i("SESSION ev=${evs.joinToString { it.javaClass.simpleName + (if (it is io.embodit.dronesight.domain.Ev.Landed) "(${it.kind} ${"%.2f".format(it.offsetM)})" else "") }} t=${"%.1f".format(sess.t)}")
                DsController.onSessionEvents(evs)
            }
        }
        // pose: crash bounce, then back on the pad (S09)
        val fl = sess?.flight
        var lift = 0f
        var flip = 0f
        var fade = 1f
        if (crashAtMs > 0) {
            val t = (now - crashAtMs) / 1000f
            if (t < 0.6f) {
                lift = 0.1f * kotlin.math.sin(PI * t / 0.6f).toFloat()
                flip = (t / 0.6f) * 0.9f
            } else if (t < 1.5f) {
                flip = 0.9f
            } else if (t < 2.0f) {
                fade = 1f - (t - 1.5f) / 0.5f
                flip = 0.9f
            } else {
                fl?.park(Vec3.ZERO, 0f)
                fade = ((t - 2.0f) / 0.5f).coerceIn(0f, 1f)
                if (t > 2.5f) crashAtMs = -1L
            }
        }
        val pos = fl?.pos ?: Vec3.ZERO
        val yaw = fl?.yaw ?: 0f
        dv.pose(pos, yaw, fl?.pitchVis ?: 0f, fl?.rollVis ?: 0f, if (ui.screen == Screen.PAUSED) 0.3f else fl?.rotor ?: 0f, dt, lift, flip)
        dv.heightLine(pos, ui.prefs.altLine && (fl?.airborne == true))
        dv.hints(ui.directionHints && ui.isFlight && fl?.airborne == true, pos, yaw, headLocal)
        dv.offset(ui.screen == Screen.LANDING || ui.screen == Screen.RESULT, landingAt)
        // see-through: next to your head (D14) or in front of the panel (D20)
        val stagePos = f.toStage(pos + Vec3(0f, 0.1f, 0f))
        val flatHead = hypot(stagePos.x - pose.position.x, stagePos.z - pose.position.z)
        val nearYou = fl?.airborne == true && flatHead < 0.6f
        val beforePanel = panelHit(stagePos)?.let { it < 0f } == true
        val alpha = when {
            fade < 1f -> fade
            nearYou -> 0.3f
            beforePanel -> 0.4f
            else -> 1f
        }
        dv.fade(alpha)
        // the control panel fades to 35 % when the thing to watch is behind it (rules.viewConeCheck 2)
        val watch = when {
            sess == null -> null
            sess.task == Task.EIGHT && sess.nextGate in 1..4 -> f.toStage(sess.course.gates[sess.nextGate - 1].at + Vec3(0f, 1.1f, 0f))
            sess.task == Task.WAYPOINTS && sess.nextRing < sess.rings.size -> f.toStage(sess.rings[sess.nextRing].center)
            else -> null
        }
        val behind = ui.screen == Screen.FLYING && (panelHit(stagePos)?.let { it > 0f } == true || (watch != null && panelHit(watch)?.let { it > 0f } == true))
        val wantFade = if (behind) 0.35f else 1f
        panelFade += (wantFade - panelFade) * (dt / 0.3f).coerceIn(0f, 1f)
        rig?.get(PanelIds.CONTROL)?.switch?.setOpacity(if (abs(panelFade - 1f) < 0.01f) 1f else panelFade)
        // rotor sound
        val level = when (ui.screen) {
            Screen.READY, Screen.FLYING, Screen.LANDING -> fl?.rotor ?: 0f
            Screen.PAUSED -> 0.3f
            Screen.SETTINGS -> if (ui.back == Screen.PAUSED) 0.3f else 0f
            else -> 0f
        }
        DsController.soundOrNull?.rotor(level, fl?.throttle ?: 0f)
        // snapshot for the panels (only when it changes)
        if (sess != null) {
            val pr = sess.progress()
            val ang = Orient.angleDeg(yaw, pos, headLocal)
            val snap = FlightSnap(
                task = sess.task,
                value = if (pr.task == Task.LAND) (fl!!.maxHeight * 10).toInt() else pr.value.toInt(),
                target = if (pr.task == Task.LAND) (pr.target * 10).toInt() else pr.target.toInt(),
                gate = pr.gate,
                gates = pr.gates,
                chainIndex = sess.taskIndex,
                chainSize = sess.lesson.tasks.size,
                inBox = sess.inBox,
                outside = sess.outside,
                nearYou = nearYou,
                heightDm = (pos.y * 10f).toInt(),
                orientation = Orient.of(ang),
                gusting = sess.flight.wind.gusting,
                timeS = sess.t.toInt(),
                airborne = fl!!.airborne,
                landingOffsetCm = landingAt?.let { (hypot(it.x, it.z) * 100).toInt() },
                touchdown = sess.outcome?.touchdown,
            )
            if (snap != lastSnap) {
                lastSnap = snap
                DsController.onSnap(snap)
            }
            if (fl.airborne && now - lastFlightLogMs >= 100) {
                lastFlightLogMs = now
                DsLog.i(String.format(Locale.US, "DS-FLIGHT t=%.1f pos=(%.2f,%.2f,%.2f) v=(%.2f,%.2f,%.2f) yaw=%.0f mode=%s wind=%s inBox=%s task=%s prog=%.1f/%.0f out=%s", sess.t, pos.x, pos.y, pos.z, fl.vel.x, fl.vel.y, fl.vel.z, Math.toDegrees(yaw.toDouble()), fl.mode, fl.wind.level, sess.inBox, sess.task, pr.value, pr.target, sess.outside))
            }
        }
    }

    /**
     * Where [p] (Stage) falls against the control panel seen from the eye: null when its line of sight misses the
     * panel's window (+5° margin), else (distance to p) - (distance to the panel plane along that line): < 0 = in front
     * of the panel (between you and it), > 0 = behind it.
     */
    private fun panelHit(p: Vec3): Float? {
        val pp = panelPose ?: return null
        val eye = pose.position
        val n = Yaw.dir(pp.yaw) * -1f // panel normal towards the head... the panel faces the head: normal = -dir
        val c = panelContentCenter(pp)
        val toP = p - eye
        val dist = toP.length
        if (dist < 1e-3f) return null
        val d = toP / dist
        val denom = d.dot(n)
        if (abs(denom) < 1e-4f) return null
        val t = (c - eye).dot(n) / denom
        if (t <= 0f) return null
        val hit = eye + d * t
        val right = Vec3(-n.z, 0f, n.x)
        val local = hit - c
        val margin = t * 0.0875f // ~5°
        val hw = PanelSizes.CONTROL_W * DP_M * PanelSizes.SCALE / 2 + margin
        val hh = PanelSizes.CONTROL_H * DP_M * PanelSizes.SCALE / 2 + margin
        if (abs(local.dot(right)) > hw || abs(local.y) > hh) return null
        return dist - t
    }

    /** Centre of the visible 308-dp part of the control panel. */
    private fun panelContentCenter(pp: Upright): Vec3 = pp.position

    // ------------------------------------------------------------------------------------------- panels

    private fun placeControlPanel(why: String) {
        val up = Placement.panel(placePose)
        panelPose = up
        panelFacePoint = pose.position
        DsLog.i(String.format(Locale.US, "PANEL place why=%s at=%s yaw=%.1f head=%s headYaw=%.1f pitch=%.1f", why, DsLog.pos(up.position), Math.toDegrees(up.yaw.toDouble()), DsLog.pos(pose.position), Math.toDegrees(pose.yaw(0f).toDouble()), Math.toDegrees(pose.pitch.toDouble())))
    }

    private fun placePanels(ui: DsUiState, now: Long) {
        val rig = rig ?: return
        if (ui.panelEpoch != panelEpoch && hasHead) {
            if (panelEpoch >= 0 || panelPose == null) placeControlPanel(if (panelEpoch < 0) "first" else "recall")
            panelEpoch = ui.panelEpoch
        }
        val pp = panelPose ?: return
        // the slot is 712 dp tall and drawn from its top edge: the design centre is the centre of the 308-dp page
        val m = DP_M * PanelSizes.SCALE
        val drop = (PanelSizes.CONTROL_MAX_H - PanelSizes.CONTROL_H) / 2f * m
        val control = rig[PanelIds.CONTROL]
        control?.facePoint = panelFacePoint
        showAt(PanelIds.CONTROL, pp.position - Vec3(0f, drop, 0f))
        // grab bar 2 cm above the top edge
        if (grabWritten != pp) grab?.let { g ->
            grabWritten = pp
            g.on(true)
            g.setLocal(position = pp.position + Vec3(0f, PanelSizes.CONTROL_H / 2f * m + 0.025f, 0f), rotation = Quat4.axisAngle(Vec3.UP, control?.facing?.yaw ?: pp.yaw))
        }
        // stick pads under the panel's 308-dp page, 0.15 m closer, tilted up (they turn to the head, pitch <= 20°)
        if (ui.sticksVisible && liteOn("sticks")) {
            val toHead = Vec3(pose.position.x - pp.position.x, 0f, pose.position.z - pp.position.z).normalized()
            val y = pp.position.y - PanelSizes.CONTROL_H / 2f * m - 0.06f - PanelSizes.STICKS_H / 2f * m
            showAt(PanelIds.STICKS, Vec3(pp.position.x, y, pp.position.z) + toHead * 0.15f)
            rig[PanelIds.STICKS]?.facePoint = panelFacePoint
        } else rig[PanelIds.STICKS]?.hide()
        // height tag over the drone (yaw-only billboard)
        val f = frame
        val sess = session
        val tagOn = f != null && sess != null && modelReady && (ui.isFlight || ui.screen == Screen.LANDING) && ui.screen != Screen.CRASH
        if (tagOn && f != null && sess != null) {
            showAt(PanelIds.TAG, f.toStage(sess.flight.pos + Vec3(0f, 0.19f + 0.35f, 0f)))
            rig[PanelIds.TAG]?.facePoint = null
        } else rig[PanelIds.TAG]?.hide()
        // blocked dialog straight ahead
        if (ui.screen == Screen.BLOCKED) {
            val d = dialogPose ?: Placement.dialog(placePose).also { dialogPose = it }
            showAt(PanelIds.DIALOG, d.position)
            rig[PanelIds.DIALOG]?.facePoint = pose.position
            courseRoot?.enabled = false
        } else {
            dialogPose = null
            rig[PanelIds.DIALOG]?.hide()
        }
    }

    private fun showAt(id: String, at: Vec3) {
        val slot = rig?.get(id) ?: return
        if (slot.shown) slot.moveTo(at) else slot.show(at)
    }

    // =========================================================================================== gestures

    fun onSpatialTap(value: SpatialTapValue) {
        val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        DsLog.i("TAP target=$name stage=${DsLog.pos(stage)}")
        val ui = DsController.state.value
        if (name == "floorCatcher" && ui.screen == Screen.PLACING && stage != null) {
            ghostTarget = Placement.courseAt(stage, pose, floorY)
            ghost = ghost?.copy(origin = Vec3(ghostTarget!!.x, floorY, ghostTarget!!.z))
            DsController.onEvent(Ui.PlaceHere)
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
                dragging = name == "grab:control" && panelPose != null
                dragStart = panelPose?.position
                dragHand = Vec3.ZERO
                DsLog.i("DRAG start target=$name panel=$dragging")
            }
            "move" -> {
                if (!dragging) return
                val a = value.dragAmount
                val moved = toStage(a.x, a.y, a.z) ?: return
                val origin = toStage(0f, 0f, 0f) ?: return
                dragHand = moved - origin
                val s = dragStart ?: return
                // hand travel x 1.6 (the panel is about twice as far as the hand)
                val p = s + dragHand * 1.6f
                panelPose = panelPose?.copy(position = p)
            }
            "end", "cancel" -> {
                if (!dragging) return
                dragging = false
                // released: stays where it is (no snapping, no gravity), turns once to face you (D21)
                panelPose?.let { pp -> panelPose = Upright(pp.position, faceYawOf(pp.position)) }
                panelFacePoint = pose.position
                DsLog.i("DRAG end panel=${DsLog.pos(panelPose?.position)}")
            }
        }
    }

    private fun faceYawOf(p: Vec3): Float = Yaw.of(Vec3(p.x - pose.position.x, 0f, p.z - pose.position.z))

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = stageRoot ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val world = contentProbe?.convertPositionTo(local, null) ?: return null
            probe.convertPositionFrom(world, null).domain()
        }.getOrNull()
    }

    // =========================================================================================== logs

    private fun stepLogs(ui: DsUiState, now: Long) {
        val since = now - startMs
        if (startupLogs < 3 && since > STARTUP_LOG_MS[startupLogs]) {
            startupLogs++
            DsLog.i("STARTUP t=${since / 1000}s " + where())
        }
        if (now - lastFrameLogMs > 5000) {
            lastFrameLogMs = now
            DsLog.i("FRAME n=$frames state=${ui.designState} head=${DsLog.pos(pose.position)} hasHead=$hasHead built=$built model=$modelReady placed=${frame != null} floorY=${"%.2f".format(floorY)}")
        }
    }

    /** Where the panels, the course and the drone are, relative to your head (startup logs at 1 / 3 / 5 s, debug `where`). */
    fun where(): String {
        val sb = StringBuilder()
        sb.append("head=").append(DsLog.pos(pose.position)).append(String.format(Locale.US, " yaw=%.0f pitch=%.0f", Math.toDegrees(pose.yaw(0f).toDouble()), Math.toDegrees(pose.pitch.toDouble()))).append(" hasHead=").append(hasHead)
        for (id in PanelIds.pool) {
            val s = rig?.get(id) ?: continue
            val p = s.position
            val rel = p - pose.position
            val az = Math.toDegrees(Yaw.wrap(atan2(-rel.x, -rel.z) - pose.yaw(0f)).toDouble())
            val el = Math.toDegrees(atan2(rel.y, hypot(rel.x, rel.z)).toDouble())
            sb.append(String.format(Locale.US, " %s(shown=%s pos=%s dist=%.2f az=%.0f el=%.0f)", id, s.shown, DsLog.pos(p), rel.length, az, el))
        }
        val f = frame ?: ghost
        if (f != null) {
            val rel = f.origin - pose.position
            sb.append(String.format(Locale.US, " course(origin=%s dist=%.2f az=%.0f yaw=%.0f)", DsLog.pos(f.origin), hypot(rel.x, rel.z), Math.toDegrees(Yaw.wrap(atan2(-rel.x, -rel.z) - pose.yaw(0f)).toDouble()), Math.toDegrees(f.yaw.toDouble())))
            session?.flight?.let { fl -> sb.append(" drone(local=").append(DsLog.pos(fl.pos)).append(" stage=").append(DsLog.pos(f.toStage(fl.pos))).append(" visible=").append(drone?.visible).append(")") }
        }
        return sb.toString()
    }

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    /** Debug `probe3d`: the course root and a few children, enabled flags and world boxes. */
    fun probe3d(): List<String> {
        val out = ArrayList<String>()
        val cr = courseRoot ?: return listOf("no course")
        fun line(name: String, e: Entity?) {
            if (e == null) { out += "PROBE $name missing"; return }
            val b = runCatching { e.getVisualBounds(stageRoot!!) }.getOrNull()
            val w = runCatching { e.convertPositionTo(Vector3.ZERO, stageRoot) }.getOrNull()
            out += String.format(Locale.US, "PROBE %s enabled=%s children=%d stage=%s box c=(%.2f,%.2f,%.2f) h=(%.2f,%.2f,%.2f) scene=%s", name, e.enabled, runCatching { e.getChildrenCount() }.getOrDefault(-1), DsLog.pos(w?.domain()), b?.center?.x ?: 0f, b?.center?.y ?: 0f, b?.center?.z ?: 0f, b?.halfExtent?.x ?: 0f, b?.halfExtent?.y ?: 0f, b?.halfExtent?.z ?: 0f, e.scene != null)
        }
        line("course", cr)
        line("pad", cr.findEntity("pad"))
        line("padRing", cr.findEntity("padRing"))
        line("hoverBox", cr.findEntity("hoverBox"))
        line("poleL", cr.findEntity("poleL"))
        line("drone", cr.findEntity("drone"))
        line("grab", grab)
        out.forEach { DsLog.i(it) }
        return out
    }

    /** D24 instance list: every 3D model instance, its file, world box height, opacity component (ui-audit). */
    fun logInstances(): List<String> {
        val out = ArrayList<String>()
        val d = drone
        val b = d?.boundsInRoot()
        out += String.format(Locale.US, "INSTANCE drone file=%s heightM=%.3f spanM=%.3f bottomY=%.3f centerXZ=(%.3f,%.3f) opacityComponent=%s calib=%s", DRONE_ASSET, (b?.second?.y ?: 0f) * 2f, maxOf(b?.second?.x ?: 0f, b?.second?.z ?: 0f) * 2f, (b?.first?.y ?: 0f) - (b?.second?.y ?: 0f), b?.first?.x ?: 0f, b?.first?.z ?: 0f, d?.hasOpacity, d?.calibration)
        out.forEach { DsLog.i(it) }
        return out
    }

    // =========================================================================================== debug seams

    /** Debug `lite unlit 1`: the drone with flat (Unlit) materials instead of the GLB's PBR ones. */
    fun debugUnlitDrone() {
        val m = shared?.let { SceneMaterials(it) } ?: return
        val map = mapOf(
            "armFA" to m.unlit(Color(0xFF2C2D31)), "armRA" to m.unlit(Color(0xFF2C2D31)), "skidA" to m.unlit(Color(0xFF2C2D31)),
            "battA" to m.unlit(Color(0xFF1E1F22)), "motor" to m.unlit(Color(0xFF3A3D44)), "propA" to m.unlit(Color(0xFF3A3B40)),
            "bodyA" to m.unlit(Color(0xFFE4E4DE)), "topA" to m.unlit(Color(0xFFD3D6D0)),
            "ledF" to m.unlit(DsColors.ArmRed), "ledR" to m.unlit(DsColors.ArmGreen),
        )
        drone?.replaceMaterials(map)
    }

    fun debugStick(s: Sticks, ms: Long) {
        debugSticks = s
        debugSticksUntil = SystemClock.elapsedRealtime() + ms
    }

    /** Puts the drone (in the air) at [local] with [yaw]; used by the `fly` macros (the judges then run as usual). */
    fun debugHover(local: Vec3, yaw: Float?) {
        val s = session ?: DsController.session ?: return
        s.flight.hover(local, yaw ?: s.flight.yaw)
        s.resync()
    }

    /** Teleports along [points] one per frame (gate passes are judged on the crossing between frames). */
    fun debugPath(points: List<Pair<Vec3, Float?>>) {
        debugPath.clear()
        debugPath.addAll(points)
    }

    /** Fast-forward [seconds] of the running session with the current sticks (the judges count it like real time). */
    fun debugSimulate(seconds: Float) {
        val s = session ?: DsController.session ?: return
        val f = frame ?: return
        val headLocal = f.toLocal(pose.position)
        var t = 0f
        val sticks = StickShaping.shape(rawSticks(SystemClock.elapsedRealtime()), DsController.state.value.prefs.invert)
        while (t < seconds && s.outcome == null) {
            val evs = s.step(0.05f, sticks, headLocal)
            if (evs.isNotEmpty()) DsController.onSessionEvents(evs)
            t += 0.05f
        }
        DsLog.i("SIMULATE ${"%.1f".format(seconds)} s -> t=${"%.1f".format(s.t)} task=${s.task} box=${"%.1f".format(s.boxTime)} spin=${"%.0f".format(s.spinDeg)} outcome=${s.outcome?.grade}")
    }

    /** Course-local point between your head and the control panel, 0.5 m from your eyes (debug `fly left`). */
    fun pointBeforePanel(): Vec3? {
        val f = frame ?: return null
        val pp = panelPose ?: return null
        val d = (pp.position - pose.position).normalized()
        return f.toLocal(pose.position + d * 0.5f)
    }

    fun headLocal(): Vec3? = frame?.toLocal(pose.position)

    fun debugFTest(on: Boolean) {
        fTest = on
        val k = kit ?: return
        val m = k.labelMats["ftest"] ?: return
        // the "1" gate tag shows F↑ (same quad, same texture path); QA checks it is upright and not mirrored
        val e = courseRoot?.findEntity("gateTag1") ?: return
        e.setMaterial(if (on) m else (k.labelMats["gate1"] ?: m))
        e.setLocal(scale = if (on) Vec3(3f, 3f, 1f) else Vec3(1f, 1f, 1f))
    }

    companion object {
        const val DRONE_ASSET = "quad_A.glb"
        private const val MAX_DT = 0.1f
        private const val SUN_LUX = 6000f
        private const val HEAD_WAIT_MS = 6000L
        private const val BLOCK_AFTER_MS = 2000L
        private const val MODEL_WAIT_MS = 8000L
        private const val FLOOR_TIMEOUT_MS = 5000L
        private val STARTUP_LOG_MS = longArrayOf(1000L, 3000L, 5000L)

        @Suppress("unused")
        private val unusedMesh: MeshResource? = null
    }
}
