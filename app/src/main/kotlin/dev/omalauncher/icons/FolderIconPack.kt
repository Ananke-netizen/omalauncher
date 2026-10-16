package dev.omalauncher.icons

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import dev.omalauncher.ui.bitmapMask
import java.io.File

/**
 * An icon pack in a folder of ~/.config/omalauncher/icon-packs, which agents draw: icon files
 * named like those in ~/.config/omalauncher/icons, one per app, and a [PackManifest] that names
 * it and its backdrop. SVGs take the theme's colors, so a pack follows the theme.
 */
class FolderIconPack private constructor(
    override val info: IconPackInfo,
    val dir: File,
    /** Its files that are icons for apps, layers left out. */
    private val icons: Set<String>,
    /** Its layers that are there. */
    private val layerFiles: Set<String>,
    val manifest: PackManifest,
) : IconPack {
    override val hasBackdrop get() = layerFiles.isNotEmpty()

    override val names: List<String> = icons.sorted()

    /** Whether an icon drawn from it so far took the theme's colors: then a new theme draws it again. */
    @Volatile var followsTheme = false
        private set

    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS)
    private val layers = java.util.Collections.synchronizedMap(HashMap<String, Bitmap?>())

    private fun find(component: ComponentName) = IconFiles.find(icons, component.packageName, component.className)

    override fun covers(component: ComponentName) = find(component) != null

    override fun nameFor(
        component: ComponentName,
        day: Int,
    ) = find(component)

    private fun draw(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        val drawn = drawIconFile(File(dir, name), size, colors)
        if (drawn.templated) followsTheme = true
        return drawn.bitmap
    }

    override fun render(
        component: ComponentName,
        day: Int,
        size: Int,
        colors: Map<String, String>,
    ): RenderedIcon? = find(component)?.let { draw(it, size, colors) }?.let { RenderedIcon(it, bitmapMask(it)) }

    private fun layer(
        name: String?,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        name?.takeIf { it in layerFiles } ?: return null
        val key = "$name@$size@${colors.hashCode()}"
        if (layers.containsKey(key)) return layers[key]
        return draw(name, size, colors).also { layers[key] = it }
    }

    override fun backdrop(
        own: Drawable,
        component: ComponentName,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        if (!hasBackdrop) return null
        val m = manifest
        return composeBackdrop(own, size, m.scale, layer(m.backdrop, size, colors), layer(m.mask, size, colors), layer(m.overlay, size, colors))
    }

    override fun thumbnail(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Bitmap? {
        val key = "$name@$size@${colors.hashCode()}"
        thumbnails.get(key)?.let { return it }
        return draw(name, size, colors)?.also { thumbnails.put(key, it) }
    }

    /** The file as it is, so an SVG picked from the pack keeps following the theme. */
    override fun export(
        name: String,
        size: Int,
        colors: Map<String, String>,
    ): Pair<String, ByteArray>? = runCatching { File(dir, name).extension to File(dir, name).readBytes() }.getOrNull()

    companion object {
        private const val THUMBNAILS = 300

        /** The packs in [root], by name. Off the main thread. */
        fun list(root: File): List<IconPackInfo> =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && PackManifest.isValidId(it.name) }
                .mapNotNull(::infoOf)
                .sortedBy { it.label.lowercase() }

        /** [dir] as a pack; null until it has a pack.toml or an icon, as it does once an agent starts on it. */
        fun infoOf(dir: File): IconPackInfo? {
            if (!dir.isDirectory || !PackManifest.isValidId(dir.name)) return null
            val toml = File(dir, PackManifest.FILE)
            val files = dir.listFiles().orEmpty().filter { it.isFile && (IconFiles.isIcon(it.name) || it == toml) }
            if (files.isEmpty()) return null
            val manifest = PackManifest.parse(runCatching { toml.takeIf { it.isFile }?.readText() }.getOrNull())
            return IconPackInfo(dir.name, manifest.name ?: dir.name, files.maxOf { it.lastModified() } + files.size, PackKind.Folder, manifest.description)
        }

        /** Reads [info]'s folder in [root]; null if it's gone. */
        fun load(
            root: File,
            info: IconPackInfo,
        ): FolderIconPack? {
            val dir = File(root, info.id)
            val now = infoOf(dir) ?: return null
            val manifest = PackManifest.parse(runCatching { File(dir, PackManifest.FILE).takeIf { it.isFile }?.readText() }.getOrNull())
            val files = dir.listFiles().orEmpty().filter { it.isFile && IconFiles.isIcon(it.name) }.mapTo(HashSet()) { it.name }
            val layers = manifest.layers.filterTo(HashSet()) { it in files }
            return FolderIconPack(now, dir, files - layers, layers, manifest)
        }
    }
}
