package io.embodit.dronesight

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.dronesight.ui.DsScreen
import io.embodit.dronesight.ui.components.PanelSizes
import io.embodit.dronesight.ui.theme.DsTheme
import io.embodit.dronesight.ui.window.DsWindow
import io.embodit.dronesight.ui.window.PANEL_ID

/**
 * App entry (design container STAGE_MIXED, skeleton D). The default Stage is Mixed (manifest pico.spatial.stage.style=1):
 * the course and the drone stand on your real floor. v0.1.2 (profile D8 / D21, factory snippet native-window-move):
 * all 2D content is one system Planar window opened from the Stage; the system moves / closes / minimises it.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { DsTheme { DsScreen() } }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { DsWindow() }
    }
