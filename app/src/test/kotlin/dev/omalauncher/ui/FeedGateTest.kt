package dev.omalauncher.ui

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The axis lock and the feed gate: what decides between the bar, the pager and the feed. */
class FeedGateTest {
    /** A drag [deg] degrees off vertical, going up and to the right. */
    private fun upAt(deg: Double): Pair<Float, Float> {
        val r = Math.toRadians(deg)
        return (sin(r) * 100).toFloat() to (-cos(r) * 100).toFloat()
    }

    /** A drag [deg] degrees off horizontal, going right (towards the feed). */
    private fun rightAt(deg: Double): Pair<Float, Float> {
        val r = Math.toRadians(deg)
        return (cos(r) * 100).toFloat() to (-sin(r) * 100).toFloat()
    }

    @Test fun straightAndSlightlyDiagonalSwipesUpAreLocked() {
        for (deg in listOf(0.0, 10.0, 20.0, 25.0)) {
            val (dx, dy) = upAt(deg)
            assertTrue("$deg° off vertical locks", isVerticalLock(dx, dy))
        }
    }

    @Test fun levelAndSteepDiagonalDragsAreNotLocked() {
        for (deg in listOf(30.0, 45.0, 70.0, 90.0)) {
            val (dx, dy) = upAt(deg)
            assertFalse("$deg° off vertical stays with the pager", isVerticalLock(dx, dy))
        }
    }

    @Test fun onlyLevelDragsPullTheFeed() {
        for (deg in listOf(0.0, 10.0, 24.0)) {
            val (dx, dy) = rightAt(deg)
            assertTrue("$deg° off horizontal pulls", feedPullAllowed(dx, dy))
        }
        // Every diagonal swipe up (20–30° off vertical is 60–70° off horizontal) is refused.
        for (deg in listOf(26.0, 45.0, 60.0, 70.0, 90.0)) {
            val (dx, dy) = rightAt(deg)
            assertFalse("$deg° off horizontal doesn't pull", feedPullAllowed(dx, dy))
        }
        assertFalse("no sideways travel at all", feedPullAllowed(0f, -50f))
    }

    @Test fun theGateIsTheSameEitherWay() {
        assertTrue(feedPullAllowed(-100f, 20f))
        assertFalse(feedPullAllowed(-100f, 80f))
    }

    @Test fun aGentleFlickNoLongerOpensTheFeed() {
        // 800 px/s used to open it from barely in.
        assertFalse(feedSettlesOpen(800f, 0.05f))
        assertFalse(feedSettlesOpen(1999f, 0.1f))
        assertTrue(feedSettlesOpen(2001f, 0.05f))
        assertFalse(feedSettlesOpen(-2001f, 0.9f))
    }

    @Test fun slowReleasesSettleByHowFarItGot() {
        assertFalse(feedSettlesOpen(0f, 0.35f))
        assertFalse(feedSettlesOpen(0f, FEED_OPEN_FRACTION))
        assertTrue(feedSettlesOpen(0f, 0.41f))
    }

    @Test fun thresholdsAreTheNamedOnes() {
        assertEquals(2000f, FEED_OPEN_VELOCITY)
        assertEquals(0.40f, FEED_OPEN_FRACTION)
        assertEquals(25f, FEED_PULL_MAX_DEGREES)
        assertNull(classifyHomeSwipe(10f, -10f, 120f))
    }
}
