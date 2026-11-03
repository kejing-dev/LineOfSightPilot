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
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.ui.DsUiState
import io.embodit.dronesight.ui.debug.LocalProbePanel
import io.embodit.dronesight.ui.debug.Probe
import io.embodit.dronesight.ui.scene.DroneScene
import io.embodit.dronesight.ui.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView holding the course, the drone and the two small attachment panels left
 * on the Stage since v0.1.2 (the height tag over the drone and the "打开面板" chip); everything else is in the system
 * main window ([io.embodit.dronesight.ui.window.DsWindow]). [DroneScene] parents and places them. Spatial taps go to
 * the scene (the floor catcher while placing); there is no drag on the Stage any more (D21: the window is moved by the
 * system's own caption bar).
 */
@Composable
internal fun DsStage(state: DsUiState, scene: DroneScene) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.TAG) { PoolPanel(PanelIds.TAG, scene) { inView -> AltitudeTag(if (inView) state else null) } }
            AttachmentPanel(PanelIds.OPEN) { PoolPanel(PanelIds.OPEN, scene) { inView -> OpenChip(if (inView) state else null) } }
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
