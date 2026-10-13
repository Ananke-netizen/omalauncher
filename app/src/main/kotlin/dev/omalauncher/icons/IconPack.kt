package dev.omalauncher.icons

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.util.Xml
import dev.omalauncher.ui.iconMask
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "icon-packs"

/** Where an icon pack comes from. */
enum class PackKind {
    /** An installed app that ships icons; [IconPackInfo.id] is its package name. */
    App,

    /** A folder in ~/.config/omalauncher/icon-packs, which agents make; [IconPackInfo.id] is its name. */
    Folder,
}

/** An icon pack, before it's loaded. */
data class IconPackInfo(
    /** What style.toml's icons.pack names it by: an app's package name, a folder's name. */
    val id: String,
    val label: String,
    /** When it last changed; a loaded copy from before is stale. */
    val updated: Long,
    val kind: PackKind = PackKind.App,
    /** What a folder pack's pack.toml says it looks like. */
    val description: String? = null,
) {
    /** Unique across kinds: a folder may share a name with an app. */
    val key get() = "${kind.name.lowercase()}:$id"
}

/**
 * A set of icons for other apps, and maybe a backdrop for the apps it has none for: an installed
 * pack ([AppIconPack]) or a folder an agent drew ([FolderIconPack]). Read on any thread. [colors]
 * are the theme's, for icons that take them.
 */
interface IconPack {
    val info: IconPackInfo
    val id get() = info.id
    val label get() = info.label

    /** Whether it dresses up apps it has no icon for. */
    val hasBackdrop: Boolean

    /** Activities, as ComponentName.flattenToString writes them, whose icon shows the day. */
    val calendars: Set<String> get() = emptySet()

    /** Every icon in it by name, for the picker. */
    val names: List<String>

    /** Whether it has an icon for [component]. */
    fun covers(component: ComponentName): Boolean

    /** The name of its icon for [component] on day [day] of the month, or null. */
    fun nameFor(
        component: ComponentName,
        day: Int,
    ): String?

    /** Its icon for [component], drawn [size] px, or null. */
    fun render(
        component: ComponentName,
        day: Int,
        size: Int,
        colors: Map<String, String>,
    ): RenderedIcon?

    /** [own] on its backdrop, or null without one. */
    fun backdrop(
        own: Drawable,
        component: ComponentName,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap?

    /** Icon [name] drawn [size] px, for the picker. */
    fun thumbnail(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap?

    /** Icon [name] as a file an app's icon can be saved as: its extension and bytes. */
    fun export(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Pair<String, ByteArray>?
}

/**
 * An installed icon pack: an app that ships drawables for other apps' icons, and an appfilter.xml
 * saying which is whose. Its resources are read in place, from its APK, at [density] dpi.
 */
class AppIconPack private constructor(
    override val info: IconPackInfo,
    private val res: Resources,
    val filter: AppFilter,
    /** Its drawable.xml, for picking an icon by hand; empty if it has none. */
    val categories: List<IconCategory>,
    private val density: Int,
) : IconPack {
    override val hasBackdrop get() = filter.hasBackdrop
    override val calendars get() = filter.calendars.keys

    /** Every icon in the pack, for the picker: drawable.xml's, or what appfilter.xml maps apps to without one. */
    override val names: List<String> by lazy { categories.flatMap { it.drawables }.distinct().ifEmpty { filter.drawables } }

    private val ids = ConcurrentHashMap<String, Int>()

    /** Picker cells, by name and size; the same few hundred come back while scrolling. */
    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS)

    @SuppressLint("DiscouragedApi")
    private fun resId(name: String) = ids.getOrPut(name) { res.getIdentifier(name, "drawable", id) }

    /** The drawable called [name]; null if the pack hasn't got it. */
    fun drawable(name: String): Drawable? {
        val resId = resId(name).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawableForDensity(resId, density, null) }.onFailure { Log.w(TAG, "${this.id}: can't load $name", it) }.getOrNull()
    }

    override fun covers(component: ComponentName) = filter.covers(component.flattenToString())

    override fun nameFor(
        component: ComponentName,
        day: Int,
    ) = filter.drawableFor(component.flattenToString(), day)

    override fun render(
        component: ComponentName,
        day: Int,
        size: Int,
        colors: Map<String, String>,
    ): RenderedIcon? {
        val key = component.flattenToString()
        val d = filter.calendars[key]?.let { drawable(it + day) } ?: filter.icons[key]?.let { drawable(it) } ?: return null
        return RenderedIcon(drawSquare(d, size), iconMask(d, size))
    }

    override fun thumbnail(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        val key = "$name@$size"
        thumbnails.get(key)?.let { return it }
        return drawable(name)?.let { drawSquare(it, size) }?.also { thumbnails.put(key, it) }
    }

    override fun export(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Pair<String, ByteArray>? = drawable(name)?.let { "png" to png(drawSquare(it, size)) }

    /** The backdrop, mask and overlay, drawn at one size; every app without its own icon in the pack shares them. Null for one it hasn't got. */
    private val layers = java.util.Collections.synchronizedMap(HashMap<String, Bitmap?>())

    private fun layer(
        name: String?,
        size: Int,
    ): Bitmap? {
        name ?: return null
        val key = "$name@$size"
        if (layers.containsKey(key)) return layers[key]
        return drawable(name)?.let { drawSquare(it, size) }.also { layers[key] = it }
    }

    override fun backdrop(
        own: Drawable,
        component: ComponentName,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        if (!filter.hasBackdrop) return null
        return composeBackdrop(own, size, filter.scale, layer(filter.backFor(component.flattenToString()), size), layer(filter.mask, size), layer(filter.upon, size))
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
        ): AppIconPack? {
            val res =
                try {
                    context.packageManager.getResourcesForApplication(info.id)
                } catch (e: PackageManager.NameNotFoundException) {
                    return null
                }
            val filter = readXml(res, info.id, "appfilter") { AppFilter.parse(it) } ?: return null
            val categories = readXml(res, info.id, "drawable") { parseDrawableList(it) }.orEmpty()
            Log.i(TAG, "${info.id}: ${filter.icons.size} icons, ${categories.sumOf { it.drawables.size }} to pick from")
            return AppIconPack(info, res, filter, categories, context.resources.displayMetrics.densityDpi)
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

    }
}

/** [d] drawn [size] px square. */
fun drawSquare(
    d: Drawable,
    size: Int,
): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, size, size)
    d.draw(Canvas(bmp))
    return bmp
}

/** [bmp] as PNG bytes. */
internal fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
