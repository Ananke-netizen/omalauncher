package dev.omalauncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Swipes through the real pieces home is made of: [feedPullGate] and [rememberFeedScroll] around a real
 * [HorizontalPager], with empty space watched the way HomeGrid does it ([followEmptySpaceDrag]).
 * Page 0 here is home's first page, the one the feed slides in from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class HomeGestureUiTest {
    @get:Rule val rule = createComposeRule()

    private val reveal = FeedReveal()
    private val swipes = mutableListOf<HomeSwipe>()
    private lateinit var pager: PagerState

    private fun home(page: Int) {
        rule.setContent {
            reveal.bind()
            pager = rememberPagerState(initialPage = page) { 3 }
            Box(Modifier.fillMaxSize().nestedScroll(rememberFeedScroll(reveal, enabled = true)).feedPullGate(reveal).testTag("home")) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val start = down.position
                            var crossing: PointerInputChange? = null
                            while (true) {
                                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) return@awaitEachGesture
                                if ((c.position - start).getDistance() > viewConfiguration.touchSlop) {
                                    crossing = c
                                    break
                                }
                            }
                            followEmptySpaceDrag(down.id, start, crossing, consumedBefore = false)?.let { swipes += it }
                        }
                    },
                ) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A swipe up [deg] degrees off vertical, leaning right (towards the feed), over half the height. */
    private fun swipeUpLeaning(deg: Double, millis: Long = 250) {
        rule.onNodeWithTag("home").performTouchInput {
            val r = Math.toRadians(deg)
            val length = height * 0.5f
            val from = Offset(width * 0.3f, height * 0.85f)
            swipe(from, from + Offset((sin(r) * length).toFloat(), (-cos(r) * length).toFloat()), millis)
        }
        rule.waitForIdle()
    }

    private fun swipeSideways(toRight: Boolean) {
        rule.onNodeWithTag("home").performTouchInput {
            val y = height * 0.5f
            val (a, b) = if (toRight) width * 0.1f to width * 0.85f else width * 0.85f to width * 0.1f
            swipe(Offset(a, y), Offset(b, y), 150)
        }
        rule.waitForIdle()
    }

    private val feedOpen get() = reveal.progress.value > 0f || reveal.progress.targetValue > 0f

    @Test fun aStraightSwipeUpOpensTheBarNotTheFeed() {
        home(0)
        swipeUpLeaning(0.0)
        assertEquals(listOf(HomeSwipe.Up), swipes)
        assertTrue("feed stayed shut", !feedOpen)
        assertEquals(0, pager.currentPage)
    }

    @Test fun diagonalSwipesUpOnTheFirstPageNeverOpenTheFeed() {
        home(0)
        for (deg in listOf(20.0, 25.0, 30.0)) {
            for (millis in listOf(120L, 300L)) {
                swipeUpLeaning(deg, millis)
                assertTrue("$deg° in ${millis}ms left the feed shut", !feedOpen)
                assertEquals("$deg° stays on the first page", 0, pager.currentPage)
            }
        }
        // Up to 25° home claims it from the pager, so it's still a swipe up.
        assertTrue("locked diagonals still open the bar: $swipes", swipes.count { it == HomeSwipe.Up } >= 4)
    }

    @Test fun aLevelSwipeOnTheFirstPageOpensTheFeed() {
        home(0)
        swipeSideways(toRight = true)
        assertEquals(1f, reveal.progress.targetValue)
        assertTrue(swipes.isEmpty())
    }

    @Test fun onTheSecondPageSidewaysSwipesTurnPages() {
        home(1)
        swipeSideways(toRight = false)
        assertEquals(2, pager.currentPage)
        swipeSideways(toRight = true)
        assertEquals(1, pager.currentPage)
        assertTrue("turning pages never pulls the feed", !feedOpen)
    }

    @Test fun aDiagonalSwipeUpOnTheSecondPageDoesNotTurnIt() {
        home(1)
        swipeUpLeaning(20.0)
        assertEquals(1, pager.currentPage)
        assertEquals(listOf(HomeSwipe.Up), swipes)
    }
}
