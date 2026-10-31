package io.embodit.dronesight

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.dronesight.ui.DsScreen
import io.embodit.dronesight.ui.theme.DsTheme

/**
 * App entry (design container STAGE_MIXED, skeleton D). The default Stage is Mixed (manifest pico.spatial.stage.style=1):
 * the course and the drone stand on your real floor; there is no Shared Space window.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { DsTheme { DsScreen() } }
    }
