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
 * Panel sizes (design panels[].sizeDp), dp; all at scale 0.75 (1 dp = 0.6 mm). The control panel is declared at its
 * tallest page (settings 712); each page draws its glass from the top edge down to its own height. Width 480 instead
 * of the design's 400 and the picker as one list (648 high): the English lesson names and taglines fit on one line
 * each (deviation, within tolerance; profile B19 "装不下就放大面板").
 */
object PanelSizes {
    const val SCALE = 0.75f
    const val CONTROL_W = 480f
    const val CONTROL_MAX_H = 712f
    const val CONTROL_H = 308f
    const val WELCOME_H = 380f
    const val PICKER_H = 648f
    const val RESULT_H = 320f
    const val RESULT_L6_H = 360f
    const val EXIT_H = 200f
    const val STICKS_W = 480f
    const val STICKS_H = 220f
    const val TAG_W = 200f
    const val TAG_H = 84f
    const val DIALOG_W = 400f
    const val DIALOG_H = 220f
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
