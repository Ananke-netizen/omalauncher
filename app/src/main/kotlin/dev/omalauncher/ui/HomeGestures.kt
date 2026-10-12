package dev.omalauncher.ui

import kotlin.math.abs

/**
 * What a finished home-screen swipe means. Vertical swipes on empty home space
 * belong to the launcher, not to the pager (horizontal) or a scrolling widget
 * (which consumes its own drags before these run).
 */
enum class HomeSwipe { Up, Down }

/**
 * Classifies a finished drag: [dy] is negative for a swipe up. A swipe counts
 * only when it clearly went further vertically than horizontally ([dx]) and at
 * least [minPx] far; anything else stays with the pager or the widget it
 * started on. Pure, so the thresholds are testable without a device.
 */
fun classifyHomeSwipe(
    dx: Float,
    dy: Float,
    minPx: Float,
): HomeSwipe? {
    if (maxOf(abs(dx), abs(dy)) < minPx) return null
    if (abs(dy) <= abs(dx) * 1.5f) return null
    return if (dy < 0f) HomeSwipe.Up else HomeSwipe.Down
}

/** How much steeper than wide a drag must be, once past touch slop, for home to claim it from the pager. */
const val VERTICAL_LOCK_RATIO = 2f

/**
 * Whether a drag on empty home space, [dx] by [dy] when it first passes touch slop, is clearly vertical.
 * Home then consumes it before the pager sees it, so a slightly diagonal swipe up neither turns the page
 * nor pulls the feed: the pager claims any drag whose horizontal travel passes slop, whatever its angle.
 */
fun isVerticalLock(
    dx: Float,
    dy: Float,
): Boolean = abs(dy) > VERTICAL_LOCK_RATIO * abs(dx)
