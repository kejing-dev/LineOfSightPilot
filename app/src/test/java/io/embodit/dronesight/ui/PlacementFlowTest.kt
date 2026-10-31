package io.embodit.dronesight.ui

import io.embodit.dronesight.domain.LessonId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile D1 "放置模式必须有终点": S03 has an end (place -> picker), the placed course is locked (placing input outside
 * S03 does nothing), "重摆场地" goes back to S03 and placing again locks again. Runs the real controller (Android
 * calls are stubbed by the unit-test android.jar).
 */
class PlacementFlowTest {
    @Test
    fun placementHasAnEndAndOnlyMoveReopensIt() {
        val c = DsController
        c.onSceneReady()
        if (c.state.value.screen == Screen.WELCOME) c.onEvent(Ui.WelcomeOk)
        assertEquals(Screen.FINDING_FLOOR, c.state.value.screen)
        c.onEvent(Ui.FloorFound(0f))
        assertEquals(Screen.PLACING, c.state.value.screen)
        c.onEvent(Ui.PlaceHere)
        assertEquals("placing ends in the picker", Screen.PICKER, c.state.value.screen)
        assertTrue(c.state.value.placed)
        // locked: a trigger / pinch "place" outside S03 changes nothing
        val before = c.state.value
        c.onEvent(Ui.PlaceHere)
        assertEquals(before, c.state.value)
        // a lesson, then "重摆场地" from settings -> S03 -> placed again -> back to the lesson (S05)
        c.onEvent(Ui.Pick(LessonId.L2))
        assertEquals(Screen.READY, c.state.value.screen)
        c.onEvent(Ui.OpenSettings)
        assertEquals(Screen.SETTINGS, c.state.value.screen)
        c.onEvent(Ui.Replace)
        assertEquals(Screen.PLACING, c.state.value.screen)
        c.onEvent(Ui.PlaceHere)
        assertEquals(Screen.READY, c.state.value.screen)
        assertEquals(LessonId.L2, c.state.value.lesson)
        // the picker's own "重摆场地" goes back to the picker
        c.onEvent(Ui.ToPicker)
        c.onEvent(Ui.Replace)
        assertEquals(Screen.PLACING, c.state.value.screen)
        c.onEvent(Ui.PlaceHere)
        assertEquals(Screen.PICKER, c.state.value.screen)
        // settings has the quit row with a confirm (D26): ask -> S12, cancel -> S11
        c.onEvent(Ui.OpenSettings)
        c.onEvent(Ui.QuitAsk)
        assertEquals(Screen.EXIT_CONFIRM, c.state.value.screen)
        c.onEvent(Ui.QuitCancel)
        assertEquals(Screen.SETTINGS, c.state.value.screen)
    }
}
