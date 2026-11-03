package io.embodit.dronesight.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.dronesight.ui.Lang
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中 / EN), switched live (Rehearsal Hall Strings.kt): the
 * screen provides a Resources object for that locale and every panel reads its strings from it.
 */
val LocalDsResources = staticCompositionLocalOf<Resources> { error("LocalDsResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag =
        when (lang) {
            Lang.SYSTEM -> return context.resources
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalDsResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalDsResources.current.getString(id, *args)

/**
 * Sizes, dp. v0.1.2: all 2D content is in ONE system Planar window (profile D8 / D21, native-window-move): the flight
 * page with the stick pads under it and the nine-lesson list fit in 540 x 680 (B19); the window brings its own glass,
 * 32-dp corners and the system caption bar (move / close / minimise). On the Stage only the height tag over the drone
 * and the "打开面板" chip are left, both at scale 0.75 (1 dp = 0.6 mm).
 */
object PanelSizes {
    const val SCALE = 0.75f
    const val WINDOW_W = 540f
    const val WINDOW_H = 680f
    /** The flight page's own part (title, mode / wind, keys, hint, toggles) above the stick pads. */
    const val FLIGHT_H = 276f
    const val STICKS_H = 200f
    const val TAG_W = 200f
    const val TAG_H = 84f
    const val OPEN_W = 300f
    const val OPEN_H = 88f
}

/** The two virtual stick pads (S14): written by the pads (main thread), read by the frame system. */
object VirtualSticks {
    @Volatile var lx = 0f
    @Volatile var ly = 0f
    @Volatile var rx = 0f
    @Volatile var ry = 0f
    @Volatile var leftHeld = false
    @Volatile var rightHeld = false

    /** Bumped by the debug hook `vstick` so the pads redraw the injected knob position. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun clear() {
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        leftHeld = false; rightHeld = false
        version.intValue++
    }
}
