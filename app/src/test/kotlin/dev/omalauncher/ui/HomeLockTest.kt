package dev.omalauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The double-tap window on empty home: two quick taps close together, nothing else. */
class HomeLockTest {
    @Test fun twoQuickTapsAreADoubleTap() {
        assertTrue(isDoubleTap(1000L, 1150L, 0f, 0f))
        assertTrue(isDoubleTap(1000L, 1300L, 30f, 40f)) // right at the window and slop
    }

    @Test fun slowOrDistantOrLateIsNot() {
        assertFalse(isDoubleTap(1000L, 1301L, 0f, 0f)) // too slow
        assertFalse(isDoubleTap(1000L, 1150L, 80f, 80f)) // too far apart
        assertFalse(isDoubleTap(1000L, 900L, 0f, 0f)) // second before first
    }
}
