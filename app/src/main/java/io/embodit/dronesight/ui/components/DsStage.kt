package io.embodit.dronesight.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.ui.DsUiState
import io.embodit.dronesight.ui.Ui
import io.embodit.dronesight.ui.debug.LocalProbePanel
import io.embodit.dronesight.ui.debug.Probe
import io.embodit.dronesight.ui.scene.DroneScene
import io.embodit.dronesight.ui.scene.PanelIds

/**
 * The Stage content (window model single_panel_with_popup): one full-size SpatialView holding the course, the drone
 * and the four attachment panels (control, stick pads, height tag, blocked dialog), each declared once with a fixed
 * size; [DroneScene] parents and places them. Spatial taps (the floor catcher while placing) and drags (the panel's
 * grab bar) go to the scene.
 */
@Composable
internal fun DsStage(state: DsUiState, scene: DroneScene, onEvent: (Ui) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.CONTROL) { PoolPanel(PanelIds.CONTROL, scene) { inView -> ControlPanel(if (inView) state else null, onEvent) } }
            AttachmentPanel(PanelIds.STICKS) { PoolPanel(PanelIds.STICKS, scene) { inView -> SticksPanel(if (inView) state else null) } }
            AttachmentPanel(PanelIds.TAG) { PoolPanel(PanelIds.TAG, scene) { inView -> AltitudeTag(if (inView) state else null) } }
            AttachmentPanel(PanelIds.DIALOG) { PoolPanel(PanelIds.DIALOG, scene) { inView -> BlockedDialog(if (inView) state else null, onEvent) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** Root of a pooled panel: content composes once the panel is in view (Spatial Ruler finding 3); debug logs presses. */
@Composable
private fun PoolPanel(id: String, scene: DroneScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val watcher =
        Modifier.pointerInput(id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        DsLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
