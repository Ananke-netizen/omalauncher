package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The empty-home vertical swipe classifier: only a clear vertical drag counts. */
class HomeGesturesTest {
    @Test fun aClearSwipeUpIsUp() {
        assertEquals(HomeSwipe.Up, classifyHomeSwipe(0f, -600f, 120f))
        // A little sideways drift stays a swipe.
        assertEquals(HomeSwipe.Up, classifyHomeSwipe(80f, -600f, 120f))
    }

    @Test fun aClearSwipeDownIsDown() {
        assertEquals(HomeSwipe.Down, classifyHomeSwipe(0f, 600f, 120f))
    }

    /** Page turns, feed reveals and taps belong to the pager, not to us. */
    @Test fun horizontalAndShortDragsAreNotSwipes() {
        assertNull(classifyHomeSwipe(600f, 0f, 120f))
        assertNull(classifyHomeSwipe(500f, -300f, 120f)) // diagonal: the pager wins
        assertNull(classifyHomeSwipe(0f, -100f, 120f)) // too short
        assertNull(classifyHomeSwipe(0f, 0f, 120f))
    }
}
