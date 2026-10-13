package dev.omalauncher.ui

import dev.omalauncher.ui.GestureAction.Drawer
import dev.omalauncher.ui.GestureAction.HomeMenu
import dev.omalauncher.ui.GestureAction.None
import dev.omalauncher.ui.GestureAction.Notifications
import dev.omalauncher.ui.GestureAction.QuickSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class GesturesTest {
    private val downward = 12f
    private val upward = -12f

    @Test fun swipingDownPullsTheShade() {
        assertEquals(SwipeTarget.Down, swipeTarget(0f, downward, up = Drawer, down = Notifications))
        assertEquals(SwipeTarget.Down, swipeTarget(0f, downward, up = None, down = Notifications))
    }

    @Test fun swipingDownRunsWhateverItIsSetTo() {
        for (down in listOf(QuickSettings, HomeMenu)) {
            assertEquals(SwipeTarget.Down, swipeTarget(0f, downward, up = Drawer, down = down))
            assertEquals(SwipeTarget.Down, swipeTarget(0f, downward, up = None, down = down))
            assertEquals(SwipeTarget.Drawer, swipeTarget(0f, upward, up = Drawer, down = down))
        }
    }

    @Test fun swipingUpOpensTheDrawer() {
        assertEquals(SwipeTarget.Drawer, swipeTarget(0f, upward, up = Drawer, down = Notifications))
        assertEquals(SwipeTarget.None, swipeTarget(0f, upward, up = None, down = Notifications))
    }

    @Test fun anOpenDrawerIsDraggedWhateverTheSettings() {
        assertEquals(SwipeTarget.Drawer, swipeTarget(0.4f, downward, up = Drawer, down = Notifications))
        assertEquals(SwipeTarget.Drawer, swipeTarget(1f, upward, up = None, down = None))
    }

    @Test fun withNothingForSwipingDownTheDrawerStillFollows() {
        assertEquals(SwipeTarget.Drawer, swipeTarget(0f, downward, up = Drawer, down = None))
        assertEquals(SwipeTarget.None, swipeTarget(0f, downward, up = None, down = None))
    }
}
