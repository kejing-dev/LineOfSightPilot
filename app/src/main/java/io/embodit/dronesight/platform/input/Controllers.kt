package io.embodit.dronesight.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.dronesight.domain.Quat4
import io.embodit.dronesight.domain.Sticks
import io.embodit.dronesight.domain.Vec3
import io.embodit.dronesight.platform.DsLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A pointing ray in Stage metres. */
data class Ray(val origin: Vec3, val dir: Vec3)

/**
 * Both PICO controllers (design input.primary): the two thumbsticks = the two sticks of a Mode 2 transmitter (left
 * controller = left stick), the trigger (place the course), A (right) / X (left) = pause / continue, and the aiming
 * controller's ray (pose from `latestData`, -Z forward). `ControllerActionListener` runs on the data source's thread:
 * it only copies values into atomics; the frame system reads them (Full Space only, SDK 6.1 controller-tracking).
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val lx = AtomicInteger(0)
    private val ly = AtomicInteger(0)
    private val rx = AtomicInteger(0)
    private val ry = AtomicInteger(0)
    private val trigR = AtomicBoolean(false)
    private val trigL = AtomicBoolean(false)
    private val triggerPresses = AtomicInteger(0)
    private val faceDown = AtomicBoolean(false)
    private val facePresses = AtomicInteger(0)
    private var trigDown = false
    private var useRight = true
    private var lastSupport: DataProvider.SupportState? = null
    private val actions = AtomicInteger(0)

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
            actions.incrementAndGet()
            lx.set(bits(runCatching { a.left.thumbstickValue.x }.getOrDefault(0f)))
            ly.set(bits(runCatching { a.left.thumbstickValue.y }.getOrDefault(0f)))
            rx.set(bits(runCatching { a.right.thumbstickValue.x }.getOrDefault(0f)))
            ry.set(bits(runCatching { a.right.thumbstickValue.y }.getOrDefault(0f)))
            val r = runCatching { a.right.triggerPressed }.getOrDefault(false)
            val l = runCatching { a.left.triggerPressed }.getOrDefault(false)
            if ((r && !trigR.get()) || (l && !trigL.get())) triggerPresses.incrementAndGet()
            if (l && !r) useRight = false
            if (r && !l) useRight = true
            trigR.set(r)
            trigL.set(l)
            val face = runCatching { a.right.aButtonPressed || a.left.xButtonPressed }.getOrDefault(false)
            if (face && !faceDown.getAndSet(true)) facePresses.incrementAndGet()
            if (!face) faceDown.set(false)
        }

    private fun bits(v: Float) = java.lang.Float.floatToIntBits(if (v.isFinite()) v else 0f)

    private fun f(a: AtomicInteger) = java.lang.Float.intBitsToFloat(a.get())

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val r = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        DsLog.i("TRACK controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        for (a in listOf(lx, ly, rx, ry)) a.set(0)
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { runCatching { it.supportState }.getOrNull() }

    /** Controllers count as present when the provider is SUPPORTED (DEVICE_NOT_SUPPORTED = no controllers). */
    fun present(): Boolean? {
        val s = supportState() ?: return null
        if (s != lastSupport) {
            lastSupport = s
            DsLog.i("TRACK controller support=$s actions=${actions.get()}")
        }
        return when (s) {
            DataProvider.SupportState.SUPPORTED -> true
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> false
            else -> null
        }
    }

    /** Raw stick positions (before dead zone / expo). */
    fun sticks(): Sticks = Sticks(f(lx), f(ly), f(rx), f(ry))

    fun takeTriggerPresses(): Int = triggerPresses.getAndSet(0)

    fun takeFacePresses(): Int = facePresses.getAndSet(0)

    /** Ray of the aiming controller (the one whose trigger was used last), or null without a pose. */
    fun ray(): Ray? {
        val p = provider ?: return null
        if (supportState() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = (if (useRight) data.right ?: data.left else data.left ?: data.right) ?: return null
        val pos = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val q = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!pos.isFinite()) return null
        val fwd = q.rotate(Vec3.AHEAD)
        if (!fwd.isFinite() || fwd.length < 0.5f) return null
        return Ray(pos, fwd.normalized())
    }
}
