package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Follows a drag on empty home space from the moment it passed touch slop ([crossing], or null if the
 * pointer went before it could) to release, and says which [HomeSwipe] it was, if any.
 *
 * A drag that's clearly vertical at slop ([isVerticalLock]) is home's: every change is consumed on the
 * Initial pass, before the pager (an ancestor's child, handling on Main) sees it, so a swipe up that leans
 * a little sideways neither turns the page nor pulls the feed. Anything else is only watched, so a level
 * drag still turns the page, and a swipe fires only if nobody else ([consumedBefore] or later) took it.
 */
internal suspend fun AwaitPointerEventScope.followEmptySpaceDrag(
    id: PointerId,
    start: Offset,
    crossing: PointerInputChange?,
    consumedBefore: Boolean,
    minSwipePx: Float = 120f,
): HomeSwipe? {
    var last = crossing?.position ?: start
    var consumedBySomeoneElse = consumedBefore
    val locked = crossing != null && !consumedBefore && isVerticalLock(last.x - start.x, last.y - start.y)
    if (locked) crossing.consume()
    if (crossing?.pressed != false) {
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: break
            if (c.isConsumed) consumedBySomeoneElse = true
            if (locked) c.consume()
            if (!c.pressed) break
            last = c.position
        }
    }
    if (consumedBySomeoneElse && !locked) return null
    return classifyHomeSwipe(last.x - start.x, last.y - start.y, minSwipePx)
}
