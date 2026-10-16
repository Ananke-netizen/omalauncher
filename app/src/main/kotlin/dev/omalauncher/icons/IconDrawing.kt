package dev.omalauncher.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.caverock.androidsvg.SVG
import dev.omalauncher.theme.Templates
import dev.omalauncher.ui.decodeSampled
import dev.omalauncher.ui.renderSvg
import java.io.File
import kotlin.math.roundToInt

/** What drawing an icon file gave: the bitmap, or null and why not, and whether it took the theme's colors. */
internal class Drawn(
    val bitmap: Bitmap?,
    val problems: List<String>,
    val templated: Boolean = false,
)

/** Draws icon [file] at [size] px, an SVG with the theme's [colors] put in for its `{{ name }}` placeholders. */
internal fun drawIconFile(
    file: File,
    size: Int,
    colors: Map<String, String>,
): Drawn {
    val length = file.length()
    if (length > IconFiles.MAX_BYTES) return Drawn(null, listOf("it's ${length shr 10} KB; icon files over ${IconFiles.MAX_BYTES shr 20} MB aren't read"))
    val bmp: Bitmap
    val problems = ArrayList<String>()
    var templated = false
    if (file.extension == "svg") {
        val text = runCatching { file.readText() }.getOrElse { return Drawn(null, listOf("can't be read: ${it.message}")) }
        val filled = if ("{{" in text) Templates.render(text, colors) else text
        templated = filled != text
        for (token in IconFiles.unfilled(filled)) problems += "$token isn't a theme color (README.md lists them), so it isn't filled in"
        bmp = runCatching { renderSvg(SVG.getFromString(filled), size) }.getOrElse { return Drawn(null, problems + "isn't SVG androidsvg can draw: ${it.message}", templated) }
    } else {
        val decoded = decodeSampled(file, size) ?: return Drawn(null, listOf("isn't an image Android can read"))
        bmp = fit(decoded, size)
    }
    if (!visible(bmp)) problems += "draws nothing visible: check its viewBox, size and colors"
    return Drawn(bmp, problems, templated)
}

/** [bmp] scaled to fit a [size] px square, centered: an icon that isn't square isn't stretched. */
private fun fit(
    bmp: Bitmap,
    size: Int,
): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val scale = size.toFloat() / maxOf(bmp.width, bmp.height)
    val w = (bmp.width * scale).roundToInt()
    val h = (bmp.height * scale).roundToInt()
    Canvas(out).drawBitmap(bmp, null, Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

private fun visible(bmp: Bitmap): Boolean {
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return pixels.any { (it ushr 24) > 16 }
}

/**
 * [own], an app's own icon, dressed the way a pack dresses apps it has no icon for: shrunk to [scale],
 * cut by [mask] where it's opaque, on [back], under [upon]. Each layer is drawn [size] px square.
 */
internal fun composeBackdrop(
    own: Drawable,
    size: Int,
    scale: Float,
    back: Bitmap?,
    mask: Bitmap?,
    upon: Bitmap?,
): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val inner = (size * scale).roundToInt()
    val at = (size - inner) / 2
    own.setBounds(at, at, at + inner, at + inner)
    own.draw(canvas)
    // The mask cuts the icon alone: the backdrop goes behind it afterwards.
    mask?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }) }
    back?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }) }
    upon?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
    return out
}
