package dev.omalauncher.ui

import kotlin.math.abs
import kotlin.math.atan2

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

/** The steepest drag, in degrees from horizontal, that may start pulling the feed in. */
const val FEED_PULL_MAX_DEGREES = 25f

/** A fling at least this fast (px/s) opens or closes the feed whatever how far it got. */
const val FEED_OPEN_VELOCITY = 2000f

/** Slower than [FEED_OPEN_VELOCITY], the feed opens once it's this far in. */
const val FEED_OPEN_FRACTION = 0.40f

/** Whether a drag that started [dx] by [dy] (at touch slop) is level enough to pull the feed in. */
fun feedPullAllowed(
    dx: Float,
    dy: Float,
): Boolean {
    if (dx == 0f) return false
    val degrees = Math.toDegrees(atan2(abs(dy), abs(dx)).toDouble())
    return degrees <= FEED_PULL_MAX_DEGREES
}

/** Whether a released feed drag, at [position] (0 hidden, 1 open) and [velocity] px/s towards open, opens it. */
fun feedSettlesOpen(
    velocity: Float,
    position: Float,
): Boolean =
    when {
        velocity > FEED_OPEN_VELOCITY -> true
        velocity < -FEED_OPEN_VELOCITY -> false
        else -> position > FEED_OPEN_FRACTION
    }
