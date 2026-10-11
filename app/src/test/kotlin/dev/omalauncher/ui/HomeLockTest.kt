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

    /** The lock zone is the wallpaper strip below the last tile row, not the gaps between tiles. */
    @Test fun lockZoneIsBelowTheTiles() {
        // gap 10, cells 100 tall, tiles reach row 8: the strip starts at 810.
        assertTrue(inLockZone(900f, 10f, 100f, 8))
        assertTrue(inLockZone(811f, 10f, 100f, 8))
        assertFalse(inLockZone(810f, 10f, 100f, 8)) // exactly at the boundary is still tile space
        assertFalse(inLockZone(400f, 10f, 100f, 8)) // between widgets: never a lock
        assertTrue(inLockZone(50f, 10f, 100f, 0)) // no tiles placed: the whole screen is wallpaper
    }
}
