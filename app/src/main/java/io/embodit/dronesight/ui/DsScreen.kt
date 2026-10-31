package io.embodit.dronesight.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import io.embodit.dronesight.platform.DsLog
import io.embodit.dronesight.ui.components.DsStage
import io.embodit.dronesight.ui.components.LocalDsResources
import io.embodit.dronesight.ui.components.localizedResources
import io.embodit.dronesight.ui.debug.Probe
import io.embodit.dronesight.ui.scene.DroneFrameSystem
import io.embodit.dronesight.ui.scene.DroneScene

/** The one screen: wires [DsController] (state), [DroneScene] (entities, per-frame work) and [DsStage] (panels). */
@Composable
fun DsScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DsController.start(context)
        true
    }
    val state by DsController.state.collectAsStateWithLifecycle()
    val scene = remember { DroneScene() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<DroneFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): DroneScene = scene }
        // background: a flight pauses (S15), trackers and sound stop; back: resume trackers, the flight waits for 继续
        val observer =
            LifecycleEventObserver { _, event ->
                DsLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        DsController.onEvent(Ui.Background(true))
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        DsController.onEvent(Ui.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        DsLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<DroneFrameSystem>()
            scene.detach()
            DsLog.i("STAGE disposed")
        }
    }

    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    CompositionLocalProvider(LocalDsResources provides resources) {
        DsStage(state = state, scene = scene, onEvent = DsController::onEvent)
    }
}
