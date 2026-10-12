package dev.omalauncher.ui

import kotlin.math.abs
import kotlin.math.atan2

/** The steepest drag, in degrees from horizontal, that may start pulling the feed in. */
const val FEED_PULL_MAX_DEGREES = 25f

/** A fling at least this fast (px/s) opens or closes the feed however far it got. */
const val FEED_OPEN_VELOCITY = 2000f

/** Slower than [FEED_OPEN_VELOCITY], the feed opens once it's this far in. */
const val FEED_OPEN_FRACTION = 0.40f

/**
 * Whether a drag that started [dx] by [dy] (at touch slop) is level enough to pull the feed in. The pager
 * claims any drag whose horizontal travel passes slop, whatever its angle, so without this a swipe up that
 * leans a little to the side, on the first page, pulls the feed.
 */
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
