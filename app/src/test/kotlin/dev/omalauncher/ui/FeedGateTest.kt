package dev.omalauncher.ui

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed gate: only a level drag pulls the feed, and only a real fling or a real pull opens it. */
class FeedGateTest {
    /** A drag [deg] degrees off horizontal, going right (towards the feed). */
    private fun rightAt(deg: Double): Pair<Float, Float> {
        val r = Math.toRadians(deg)
        return (cos(r) * 100).toFloat() to (-sin(r) * 100).toFloat()
    }

    @Test fun onlyLevelDragsPullTheFeed() {
        for (deg in listOf(0.0, 10.0, 24.0)) {
            val (dx, dy) = rightAt(deg)
            assertTrue("$deg° off horizontal pulls", feedPullAllowed(dx, dy))
        }
        // A swipe up 20–30° off vertical is 60–70° off horizontal: refused.
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
    }
}
