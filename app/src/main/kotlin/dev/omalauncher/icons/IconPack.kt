package dev.omalauncher.icons

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val TAG = "icon-packs"

/** An installed icon pack, before it's loaded. */
data class IconPackInfo(
    val packageName: String,
    val label: String,
    /** When it was last installed or updated; a loaded copy from before is stale. */
    val updated: Long,
)

/**
 * An icon pack: an app that ships drawables for other apps' icons, and an appfilter.xml
 * saying which is whose. Its resources are read in place, from its APK, on any thread.
 */
class IconPack private constructor(
    val info: IconPackInfo,
    private val res: Resources,
    val filter: AppFilter,
    /** Its drawable.xml, for picking an icon by hand; empty if it has none. */
    val categories: List<IconCategory>,
) {
    val packageName get() = info.packageName
    val label get() = info.label

    /** Every icon in the pack, for the picker: drawable.xml's, or what appfilter.xml maps apps to without one. */
    val names: List<String> by lazy { categories.flatMap { it.drawables }.distinct().ifEmpty { filter.drawables } }

    private val ids = ConcurrentHashMap<String, Int>()

    /** Picker cells, by name and size; the same few hundred come back while scrolling. */
    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS)

    @SuppressLint("DiscouragedApi")
    private fun id(name: String) = ids.getOrPut(name) { res.getIdentifier(name, "drawable", packageName) }

    /** The drawable called [name], at [density] dpi; null if the pack hasn't got it. */
    fun drawable(
        name: String,
        density: Int,
    ): Drawable? {
        val id = id(name).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawableForDensity(id, density, null) }.onFailure { Log.w(TAG, "$packageName: can't load $name", it) }.getOrNull()
    }

    /** The pack's icon for activity [component] (as [componentKey] writes it), the calendar one for [day] if it has it. */
    fun iconFor(
        component: String,
        day: Int,
        density: Int,
    ): Drawable? =
        filter.calendars[component]?.let { drawable(it + day, density) }
            ?: filter.icons[component]?.let { drawable(it, density) }

    /** [name] drawn [size] px square, for the picker; null if the pack hasn't got it. */
    fun thumbnail(
        name: String,
        size: Int,
        density: Int,
    ): Bitmap? {
        val key = "$name@$size"
        thumbnails.get(key)?.let { return it }
        return drawable(name, density)?.let { draw(it, size) }?.also { thumbnails.put(key, it) }
    }

    /** The backdrop, mask and overlay, drawn at one size; every app without its own icon in the pack shares them. Null for one it hasn't got. */
    private val layers = java.util.Collections.synchronizedMap(HashMap<String, Bitmap?>())

    private fun layer(
        name: String?,
        size: Int,
        density: Int,
    ): Bitmap? {
        name ?: return null
        val key = "$name@$size"
        if (layers.containsKey(key)) return layers[key]
        return drawable(name, density)?.let { draw(it, size) }.also { layers[key] = it }
    }

    /**
     * [own], an app's own icon, dressed the way the pack dresses apps it has no icon for: shrunk by its
     * scale, cut by its mask, on its backdrop, under its overlay. Null if the pack does none of that.
     */
    fun backdrop(
        own: Drawable,
        component: String,
        size: Int,
        density: Int,
    ): Bitmap? {
        if (!filter.hasBackdrop) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inner = (size * filter.scale).roundToInt()
        val at = (size - inner) / 2
        own.setBounds(at, at, at + inner, at + inner)
        own.draw(canvas)
        // The mask cuts the icon alone: the backdrop goes behind it afterwards.
        layer(filter.mask, size, density)?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }) }
        layer(filter.backFor(component), size, density)?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }) }
        layer(filter.upon, size, density)?.let { canvas.drawBitmap(it, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        return out
    }

    companion object {
        private const val THUMBNAILS = 300

        /**
         * How icon packs announce themselves: each declares an activity for one or more of these.
         * The manifest's <queries> lists the same, or Android 11+ hides the packs from us.
         */
        private val ANNOUNCED =
            listOf(
                Intent("org.adw.launcher.THEMES"),
                Intent("com.novalauncher.THEME"),
                Intent("org.adw.launcher.icons.ACTION_PICK_ICON"),
                Intent("com.dlto.atom.launcher.THEME"),
                Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
            )

        /** The icon packs installed, by name. Reads PackageManager: off the main thread. */
        fun installed(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            return ANNOUNCED
                .flatMap { runCatching { pm.queryIntentActivities(it, 0) }.getOrDefault(emptyList()) }
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .mapNotNull { app ->
                    runCatching {
                        IconPackInfo(app.packageName, app.loadLabel(pm).toString(), pm.getPackageInfo(app.packageName, 0).lastUpdateTime)
                    }.getOrNull()
                }.sortedBy { it.label.lowercase() }
        }

        /** The installed app [packageName] as an icon pack, without listing them all; null if it isn't installed. */
        fun infoOf(
            context: Context,
            packageName: String,
        ): IconPackInfo? =
            runCatching {
                val pm = context.packageManager
                val app = pm.getPackageInfo(packageName, 0)
                IconPackInfo(packageName, app.applicationInfo?.loadLabel(pm)?.toString() ?: packageName, app.lastUpdateTime)
            }.getOrNull()

        /** Reads [info]'s appfilter.xml and drawable.xml; null if it's gone or has no appfilter. Off the main thread: a big pack takes a moment. */
        fun load(
            context: Context,
            info: IconPackInfo,
        ): IconPack? {
            val res =
                try {
                    context.packageManager.getResourcesForApplication(info.packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    return null
                }
            val filter = readXml(res, info.packageName, "appfilter") { AppFilter.parse(it) } ?: return null
            val categories = readXml(res, info.packageName, "drawable") { parseDrawableList(it) }.orEmpty()
            Log.i(TAG, "${info.packageName}: ${filter.icons.size} icons, ${categories.sumOf { it.drawables.size }} to pick from")
            return IconPack(info, res, filter, categories)
        }

        /** Parses the pack's xml/[name].xml, or assets/[name].xml where packs also put it; null if it has neither or it doesn't parse. */
        @SuppressLint("DiscouragedApi")
        private fun <T> readXml(
            res: Resources,
            pkg: String,
            name: String,
            parse: (Sequence<XmlTag>) -> T,
        ): T? =
            runCatching {
                val id = res.getIdentifier(name, "xml", pkg)
                if (id != 0) {
                    res.getXml(id).use { parse(it.tags()) }
                } else {
                    val input = runCatching { res.assets.open("$name.xml") }.getOrNull() ?: return null
                    input.use {
                        val parser = Xml.newPullParser()
                        parser.setInput(it, null)
                        parse(parser.tags())
                    }
                }
            }.onFailure { Log.w(TAG, "$pkg: can't read $name.xml", it) }.getOrNull()

        /** The start tags of a parser's document, read as they're asked for. */
        private fun XmlPullParser.tags(): Sequence<XmlTag> =
            sequence {
                while (next() != XmlPullParser.END_DOCUMENT) {
                    if (eventType != XmlPullParser.START_TAG) continue
                    yield(XmlTag(name, (0 until attributeCount).associate { getAttributeName(it) to getAttributeValue(it) }))
                }
            }

        /** [d] drawn [size] px square. */
        fun draw(
            d: Drawable,
            size: Int,
        ): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            return bmp
        }
    }
}
