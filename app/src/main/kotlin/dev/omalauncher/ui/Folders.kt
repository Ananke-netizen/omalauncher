package dev.omalauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.zIndex
import dev.omalauncher.AppProfile
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A folder on the home grid: its first four apps in a 2x2 badge. */
@Composable
fun FolderTile(
    folder: Folder,
    /** The first four apps' icons, as [ShortcutTile] gets them. */
    icons: List<ImageBitmap?>,
    focused: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    /** The same apps' profiles, so paused work apps show dimmed; too small for a badge of their own. */
    profiles: List<AppProfile?> = emptyList(),
) {
    val bare = LauncherStyle.bareAppIcons
    BoxWithConstraints(
        modifier
            .tileFrame(focused)
            .pointerInput(folder.key) { detectTapGestures(onTap = { onTap() }) }
            .padding(4.dp),
    ) {
        val badge = min(maxWidth, maxHeight) * if (bare) 0.95f else 0.85f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(badge).clip(LauncherStyle.shape(badge / 3)).background(Palette.surface),
                contentAlignment = Alignment.Center,
            ) {
                val mini = badge * 0.36f
                Column(verticalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(badge * 0.06f)) {
                            for (col in 0 until 2) {
                                val i = row * 2 + col
                                Box(Modifier.size(mini), contentAlignment = Alignment.Center) {
                                    folder.apps.getOrNull(i)?.let { ProfileMarks(profiles.getOrNull(i), badged = true, mini) { AppGlyph(it.label, icons.getOrNull(i), mini) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small app icon: its picture (a silhouette in monochrome mode) or its initials. */
@Composable
private fun AppGlyph(
    label: String,
    icon: ImageBitmap?,
    size: Dp,
) {
    if (icon != null) {
        Image(icon, label, Modifier.size(size), colorFilter = if (LauncherStyle.monochromeIcons) ColorFilter.tint(Palette.accent) else null)
    } else {
        BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = (size.value * 0.45f).sp))
    }
}

/**
 * Where an open folder draws, in the home grid's pixels: a panel that opens out of the folder's
 * tile, [anchor], up and to the right, with the apps at home's cell size. Where it doesn't fit
 * that way it opens down or to the left instead, from the tile's other corner, and it stays inside
 * [bounds] within home's outer gap, so it always covers its tile. Smaller only when it doesn't fit
 * at all; centered with no [anchor]. The grid's gestures and the panel's drawing share it.
 */
internal class FolderGeometry(
    /** Where the panel may go: the folder's page, so on an unfolded foldable it stays off the fold. */
    bounds: IntRect,
    /** The folder's tile, which the panel opens out of and folds back into. */
    val anchor: IntRect?,
    cellW: Float,
    cellH: Float,
    gap: Int,
    count: Int,
    density: Density,
) {
    val cols = ceil(sqrt(count.toFloat())).toInt().coerceIn(2, 4)
    private val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
    private val pad = with(density) { 16.dp.roundToPx() }

    // Where tiles stop too.
    private val area = IntRect(bounds.left + gap, bounds.top + gap, bounds.right - gap, bounds.bottom - gap)
    private val scale =
        minOf(
            1f,
            (area.width - 2 * pad) / (cols * cellW),
            (area.height - 2 * pad) / (rows * cellH),
        )
    private val w = cellW * scale
    private val h = cellH * scale
    private val gap = gap

    val panel: IntRect =
        run {
            val pw = (cols * w).roundToInt() + 2 * pad
            val ph = (rows * h).roundToInt() + 2 * pad
            val left = if (anchor == null) area.left + (area.width - pw) / 2 else along(anchor.left, anchor.right - pw, pw, area.left, area.right)
            val top = if (anchor == null) area.top + (area.height - ph) / 2 else along(anchor.bottom - ph, anchor.top, ph, area.top, area.bottom)
            IntRect(left, top, left + pw, top + ph)
        }

    /** The [i]th app's tile, gapped like home's. */
    fun app(i: Int): IntRect {
        val left = panel.left + pad + (i % cols) * w
        val top = panel.top + pad + (i / cols) * h
        return IntRect((left + gap / 2f).roundToInt(), (top + gap / 2f).roundToInt(), (left + w - gap / 2f).roundToInt(), (top + h - gap / 2f).roundToInt())
    }

    // The app slot over the tile (or the panel's middle), and the farthest any app is from it.
    private val origin =
        (anchor ?: panel).center.let { c ->
            ((c.x - panel.left - pad) / w).toInt().coerceIn(0, cols - 1) to ((c.y - panel.top - pad) / h).toInt().coerceIn(0, rows - 1)
        }
    private val far = ((0 until count).maxOfOrNull(::steps) ?: 0).coerceAtLeast(1)

    private fun steps(i: Int) = abs(i % cols - origin.first) + abs(i / cols - origin.second)

    /**
     * When the [i]th app shows up as the panel opens, 0 first to 1 last: by how many slots it is
     * from the tile, so the apps fill in as a diagonal sweep away from it.
     */
    fun wave(i: Int) = steps(i) / far.toFloat()
}

/**
 * Where a panel [size] long starts along one axis: at [preferred] when it fits between [min] and
 * [max], else at [fallback] (opening the other way from the tile), then moved inside if need be.
 */
private fun along(
    preferred: Int,
    fallback: Int,
    size: Int,
    min: Int,
    max: Int,
) = (if (preferred >= min && preferred + size <= max) preferred else fallback).coerceAtMost(max - size).coerceAtLeast(min)

/** How far [t] is through the stretch of an animation from [start] to [end], 0 to 1. */
internal fun phase(
    t: Float,
    start: Float,
    end: Float,
) = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** How long a folder takes to open out of its tile, and to fold back in. */
private const val OPEN_MS = 300
private const val CLOSE_MS = 200

/**
 * Which folder shows over the grid, and how far it's open: the open one as it opens, then
 * after it closes, until it has folded back into its tile.
 */
@Stable
internal class FolderReveal {
    /** The folder drawn, open or closing. */
    var key by mutableStateOf<String?>(null)
        private set
    private val progress = Animatable(0f)

    /** How far [folder] is open: 0 in its tile, 1 open. Read it while drawing, so animating doesn't recompose. */
    fun progressOf(folder: String?) = if (folder == key) progress.value else 0f

    /** Opens [open], or with null closes the folder that's showing; reversing midway carries on from there. */
    suspend fun follow(open: String?) {
        if (open != null) {
            // Another folder than one still folding away starts from its own tile.
            if (open != key) progress.snapTo(0f)
            key = open
            progress.animateTo(1f, tween(((1f - progress.value) * OPEN_MS).roundToInt(), easing = LinearEasing))
        } else {
            progress.animateTo(0f, tween((progress.value * CLOSE_MS).roundToInt(), easing = LinearEasing))
            key = null
        }
    }
}

/** A [FolderReveal] following the [open] folder's key. */
@Composable
internal fun rememberFolderReveal(open: String?): FolderReveal {
    val reveal = remember { FolderReveal() }
    LaunchedEffect(open) { reveal.follow(open) }
    return reveal
}

/**
 * An open folder over the home grid: a scrim, the panel and its apps drawn by [render].
 * Taps, long-presses and drags are [HomeGrid]'s, like every touch on home.
 *
 * It opens with [progress], from 0 to 1: the folder tile's frame grows out of the tile into the
 * panel, turning from border to accent, and the apps fade in as it does, nearest the tile first.
 * Closing runs it backwards, into the tile. While [closing], its apps swallow taps, so one meant
 * for home under it can't open a folder app on its way out.
 */
@Composable
internal fun FolderPanel(
    folder: Folder,
    geometry: FolderGeometry,
    progress: () -> Float,
    closing: Boolean,
    /** The app being dragged out of it, which the grid draws instead. */
    carried: String?,
    render: @Composable (HomeItem, Modifier, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val from = (geometry.anchor ?: geometry.panel).toRect()
    val to = geometry.panel.toRect()
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(4f)
            .drawWithContent {
                val t = progress()
                drawRect(Palette.overlay.copy(alpha = 0.6f * phase(t, 0f, 0.5f)))
                // The tile's frame (FolderTile's tileFrame) becomes the panel's: a wireframe at first, so the
                // tile still shows through, filling in as it grows.
                val f = FastOutSlowInEasing.transform(phase(t, 0f, 0.6f))
                val r = lerp(from, to, f)
                val stroke = 2.dp.toPx()
                val radius = lerp(16.dp, 20.dp, f).toPx() * LauncherStyle.corners.scale
                drawRoundRect(Palette.background.copy(alpha = f), r.topLeft, r.size, CornerRadius(radius))
                drawRoundRect(
                    lerp(Palette.border, Palette.accent, f),
                    r.topLeft + Offset(stroke / 2, stroke / 2),
                    Size(r.width - stroke, r.height - stroke),
                    CornerRadius((radius - stroke / 2).coerceAtLeast(0f)),
                    style = Stroke(stroke),
                )
                // The apps show only inside the frame so far.
                clipRect(r.left + stroke, r.top + stroke, r.right - stroke, r.bottom - stroke) { this@drawWithContent.drawContent() }
            },
    ) {
        folder.apps.forEachIndexed { i, app ->
            if (app.key == carried) return@forEachIndexed
            val r = geometry.app(i)
            val wave = geometry.wave(i)
            key(app.key) {
                render(
                    app,
                    Modifier
                        .offset { IntOffset(r.left, r.top) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .inert(closing)
                        .graphicsLayer {
                            val a = LinearOutSlowInEasing.transform(phase(progress(), 0.3f + 0.35f * wave, 0.65f + 0.35f * wave))
                            alpha = a
                            scaleX = 0.85f + 0.15f * a
                            scaleY = 0.85f + 0.15f * a
                        },
                    false,
                )
            }
        }
    }
}

/** While [on], touches stop here: taken on their way in, before the content sees them. */
private fun Modifier.inert(on: Boolean) =
    pointerInput(on) {
        if (!on) return@pointerInput
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
