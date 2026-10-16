package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Our home gestures: swipe up = the bar, swipe left = the feed, double-tap = lock (wallpaper strip only).
 * Those are asserted in HomeGestureUiTest (up -> bar, level swipe -> feed), FeedGateTest and HomeLockTest.
 * This pins the merge with mainline: its home drag (drawer on swipe up, shade / quick settings / home menu on
 * swipe down) must stay off and unofferable, so it can never take a swipe before our handlers see it.
 */
class HomeGestureRegressionTest {
    @Test fun mainlineHomeDragIsOffAndNotOffered() {
        assertEquals(listOf(GestureAction.None), SwipeUpChoices)
        assertEquals(listOf(GestureAction.None), SwipeDownChoices)
    }

    @Test fun withTheDefaultsNoHomeSwipeIsTakenByTheDrawerOrTheShade() {
        val up = SwipeUpChoices.first()
        val down = SwipeDownChoices.first()
        for (dy in listOf(-40f, -12f, -1f, 1f, 12f, 40f)) {
            assertEquals("dy=$dy", SwipeTarget.None, swipeTarget(0f, dy, up, down))
        }
    }

    @Test fun anOpenDrawerCanStillBeDraggedClosed() {
        assertEquals(SwipeTarget.Drawer, swipeTarget(0.5f, 12f, SwipeUpChoices.first(), SwipeDownChoices.first()))
    }
}
