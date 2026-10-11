package dev.omalauncher.ui

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
    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < minPx) return null
    if (kotlin.math.abs(dy) <= kotlin.math.abs(dx) * 1.5f) return null
    return if (dy < 0f) HomeSwipe.Up else HomeSwipe.Down
}
