package io.embodit.dronesight.platform.sense

import android.os.Handler
import android.os.Looper
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.dronesight.platform.DsLog

/**
 * Floor height from plane detection (design I2): started only for S02, stopped as soon as a FLOOR plane arrives or
 * the 5 s timeout ends; no plane colliders, no meshes, nothing else of the room is used (profile D24, reality-audit.md).
 */
class FloorFinder {
    private val main = Handler(Looper.getMainLooper())
    private var subscription: Cancellable? = null
    private var onFloor: ((Float) -> Unit)? = null

    val running: Boolean
        get() = subscription != null

    fun start(onFloor: (Float) -> Unit) {
        if (subscription != null) return
        this.onFloor = onFloor
        subscription =
            runCatching {
                PlaneTrackingManager.subscribeAnchorUpdate { update ->
                    if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
                }
            }.onFailure { DsLog.e("FLOOR subscribe failed: $it", it) }.getOrNull()
        runCatching { PlaneTrackingManager.start() }.onFailure { DsLog.e("FLOOR start failed: $it", it) }
        DsLog.i("FLOOR tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        if (subscription == null) return
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        onFloor = null
        DsLog.i("FLOOR tracking stopped")
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        val cb = onFloor ?: return
        if (update.event == AnchorUpdate.Event.REMOVED) return
        val a = update.anchor
        val name = runCatching { a.semantics.name }.getOrNull() ?: return
        if (!name.equals("FLOOR", ignoreCase = true)) return
        // the height comes from the plane's vertices in Stage space (rotation + position of the anchor applied), as in
        // Figure Model's PlaneTracker; the anchor position alone is logged for comparison
        val py = runCatching { a.transform.position.y }.getOrNull()
        val ys = runCatching {
            val q = a.transform.quaternion
            val p = a.transform.position
            a.vertices.map { v -> (q.rotateVector(v) + p).y }
        }.getOrNull()
        val y = ys?.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: py ?: return
        if (!y.isFinite()) return
        DsLog.i("FLOOR found y=$y anchorY=$py vertices=${ys?.size} spread=${ys?.let { (it.maxOrNull() ?: 0f) - (it.minOrNull() ?: 0f) }} semantics=$name")
        cb(y)
    }
}
