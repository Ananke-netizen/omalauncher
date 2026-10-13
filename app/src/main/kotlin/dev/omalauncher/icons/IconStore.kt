package dev.omalauncher.icons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.AndroidApp
import dev.omalauncher.AndroidAppCatalog
import dev.omalauncher.AppUsage
import dev.omalauncher.LauncherData
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.bitmapMask
import dev.omalauncher.ui.iconMask
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val TAG = "icons"

/** An app's icon and its silhouette, for the monochrome icon style. */
class RenderedIcon(
    val icon: Bitmap,
    val mask: Bitmap,
)

/** Draws apps' icons in place of their own; see [IconStore]. */
fun interface IconSource {
    /** [app]'s icon at [size] px, or null for its own. Called off the main thread, a few at a time. */
    suspend fun render(
        app: AndroidApp,
        info: LauncherActivityInfo,
        size: Int,
    ): RenderedIcon?
}

/** An icon pack as its card in settings shows it: a few of the user's apps as it draws them, and how many it has an icon for. */
class PackPreview(
    val samples: List<Bitmap>,
    val covered: Int,
    val total: Int,
)

/**
 * Where Android apps' icons come from. First a file of the user's in ~/.config/omalauncher/icons,
 * drawn by an agent, picked in settings or put there by hand, which apps.txt there names for each
 * app; then the icon pack style.toml's `icons.pack` names, with its backdrop for apps it has no icon
 * for; then the app's own. A pack is an installed app or a folder in ~/.config/omalauncher/icon-packs
 * that an agent drew. Watches both folders, the style and the theme, and has the drawer draw the
 * icons that changed again. SVG files can take the theme's colors as `{{ accent }}` and redraw when
 * it changes. Main thread.
 */
class IconStore(
    private val context: Context,
    private val style: StyleStore,
    private val apps: AndroidAppCatalog,
    private val usage: AppUsage,
) : IconSource {
    /** The guest sees it at ~/[GUEST_DIR]. */
    val dir = LauncherData.iconsDir(context)

    /** Agents' packs, a folder each. The guest sees it at ~/[PACKS_GUEST_DIR]. */
    val packsDir = LauncherData.iconPacksDir(context)
    private val density = context.resources.displayMetrics.densityDpi
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    /** The icon packs installed as apps, by name. */
    private var appPacks by mutableStateOf<List<IconPackInfo>>(emptyList())

    /** The packs agents drew, by name. */
    private var folderPacks by mutableStateOf<List<IconPackInfo>>(emptyList())

    /** Every pack to pick from: the ones agents drew, then the installed ones. */
    val packs get() = folderPacks + appPacks


    /** The icon pack in use; null for the apps' own icons, or until it's loaded. */
    var pack by mutableStateOf<IconPack?>(null)
        private set

    /** The icon files in [dir], by name. */
    var files by mutableStateOf<Set<String>>(emptySet())
        private set

    /** What icons are drawn from right now; read off the main thread, so swapped whole. */
    private class Sources(
        val pack: IconPack?,
        val backdrop: Boolean,
        val files: Set<String>,
        val colors: Map<String, String>,
    )

    @Volatile private var sources = Sources(null, true, emptySet(), emptyMap())

    /** The theme's colors as icons take them, for drawing a pack's icons in settings. */
    val colors get() = sources.colors

    /** Until the pack in style.toml is loaded, icons wait rather than show the app's own for a moment. */
    private val ready = CompletableDeferred<Unit>()

    /** SVG files that use the theme's colors; they're drawn again when it changes. */
    private val templated = ConcurrentHashMap.newKeySet<String>()

    /** Packs loaded for their cards or the picker, by [IconPackInfo.key]; the one in use among them. */
    private val loaded = HashMap<String, IconPack>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanning = Mutex()

    /** The listing [files] was made from: names to modification time and size. */
    private var listing = emptyMap<String, Pair<Long, Long>>()

    /** The packs folder's, as "<pack>/<file>". */
    private var packListing = emptyMap<String, Pair<Long, Long>>()

    /** The style's pack and backdrop setting that [sources] follows, loaded or not. */
    private var applied: Pair<String?, Boolean>? = null

    init {
        apps.iconSource = this
        scope.launch {
            val name = style.style[Style.ICON_PACK]
            val backdrop = style.style[Style.ICON_PACK_BACKDROP]
            rescan(refresh = false)
            packListing = withContext(Dispatchers.IO) { listPacks(packsDir) }
            folderPacks = withContext(Dispatchers.IO) { FolderIconPack.list(packsDir) }
            pack = name?.let { loadPack(it) }
            applied = name to backdrop
            sources = Sources(pack, backdrop, files, themeColors())
            ready.complete(Unit)
            watch()
            // After the package watch is up, so a pack installed while this started isn't missed.
            syncAppPacks()
            // Files saved while the launcher wasn't running, for icon-check; those checked before are skipped.
            val names = files
            val packFiles = packListing.keys
            val colors = sources.colors
            withContext(Dispatchers.IO) {
                for (n in names) check(dir, n, colors)
                for (path in packFiles) if (IconFiles.isIcon(path.substringAfter('/'))) check(File(packsDir, path.substringBefore('/')), path.substringAfter('/'), colors)
            }
        }
    }

    override suspend fun render(
        app: AndroidApp,
        info: LauncherActivityInfo,
        size: Int,
    ): RenderedIcon? {
        ready.await()
        val s = sources
        val component = app.component
        IconFiles.find(s.files, component.packageName, component.className)?.let { name ->
            val drawn = drawIconFile(File(dir, name), size, s.colors)
            if (drawn.templated) templated += name else templated -= name
            drawn.bitmap?.let { return RenderedIcon(it, bitmapMask(it)) }
        }
        val pack = s.pack ?: return null
        pack.render(component, today(), size, s.colors)?.let { return it }
        if (!s.backdrop) return null
        val own = info.getIcon(0)
        return pack.backdrop(own, component, size, s.colors)?.let { RenderedIcon(it, iconMask(own, size)) }
    }

    /** Where [app]'s icon comes from now, as settings and apps.txt say it. */
    fun sourceOf(app: AndroidApp): IconFiles.Source {
        val c = app.component
        val pack = pack
        return when {
            IconFiles.find(files, c.packageName, c.className) != null -> IconFiles.Source.File
            pack == null -> IconFiles.Source.Own
            pack.covers(c) -> IconFiles.Source.Pack
            pack.hasBackdrop && style.style[Style.ICON_PACK_BACKDROP] -> IconFiles.Source.Backdrop
            else -> IconFiles.Source.Own
        }
    }

    /** The file name, without extension, [app]'s icon is saved under. */
    fun stemFor(app: AndroidApp): String {
        val c = app.component
        val entries = apps.apps.filter { it.component.packageName == c.packageName }.distinctBy { it.component.className }.size
        return IconFiles.stemFor(c.packageName, c.className, entries)
    }

    /** Uses [info] for every app, or the apps' own icons with null; saved in style.toml. */
    fun use(info: IconPackInfo?) = style.update { it.with(Style.ICON_PACK, info?.id) }

    /** [info] loaded, from the ones loaded before if it hasn't changed since; null if it's gone or broken. */
    suspend fun open(info: IconPackInfo): IconPack? {
        loaded[info.key]?.takeIf { it.info.updated == info.updated }?.let { return it }
        val pack =
            withContext(Dispatchers.IO) {
                when (info.kind) {
                    PackKind.App -> AppIconPack.load(context, info)
                    PackKind.Folder -> FolderIconPack.load(packsDir, info)
                }
            } ?: return null
        loaded[info.key] = pack
        return pack
    }

    /** Forgets the packs loaded for settings but the one in use: a big pack's list takes megabytes. */
    fun trim() {
        loaded.keys.retainAll(setOfNotNull(pack?.info?.key))
    }

    /** The apps, one per activity, that [pack] has no icon for: what an agent fills in. */
    fun missing(pack: IconPack): Int = apps.apps.distinctBy { it.component }.count { !pack.covers(it.component) }

    /** Deletes the agent-made pack [info], and goes back to the apps' own icons if it was in use. */
    suspend fun remove(info: IconPackInfo) {
        if (info.kind != PackKind.Folder || !PackManifest.isValidId(info.id)) return
        if (pack?.info?.key == info.key) use(null)
        withContext(Dispatchers.IO) { File(packsDir, info.id).deleteRecursively() }
        loaded.remove(info.key)
        folderPacks = withContext(Dispatchers.IO) { FolderIconPack.list(packsDir) }
    }

    /** [info]'s card: the user's most used apps it has icons for, drawn by it. */
    suspend fun preview(
        info: IconPackInfo?,
        size: Int,
    ): PackPreview {
        val all = apps.apps.toList().distinctBy { it.component }
        val now = System.currentTimeMillis()
        val ranked = all.sortedByDescending { usage.score("a:" + it.key, now) }
        if (info == null) {
            val samples = withContext(Dispatchers.IO) { ranked.asSequence().mapNotNull { app -> ownIcon(app)?.let { drawSquare(it, size) } }.take(SAMPLES).toList() }
            return PackPreview(samples, all.size, all.size)
        }
        val pack = open(info) ?: return PackPreview(emptyList(), 0, all.size)
        val colors = sources.colors
        return withContext(Dispatchers.IO) {
            val covered = all.count { pack.covers(it.component) }
            val samples = ranked.asSequence().mapNotNull { app -> pack.render(app.component, today(), size, colors)?.icon }.take(SAMPLES).toList()
            PackPreview(samples, covered, all.size)
        }
    }

    /** [app]'s own icon, not the pack's or a file's; null if it's gone. */
    fun ownIcon(app: AndroidApp): Drawable? =
        runCatching { launcherApps.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(app.component), app.user)?.getIcon(density) }.getOrNull()

    /** Gives [app] the pack's icon [name] as its icon file, whichever pack is in use. */
    suspend fun pick(
        app: AndroidApp,
        pack: IconPack,
        name: String,
    ) {
        val colors = sources.colors
        val (ext, bytes) = withContext(Dispatchers.IO) { pack.export(name, SAVED_PX, colors) } ?: return
        save(app, ext, bytes)
    }

    /** Keeps [app]'s own icon whatever the pack does, as a file; without a pack, that's no file at all. */
    suspend fun pickOwn(app: AndroidApp) {
        if (pack == null) return reset(app)
        val bytes = withContext(Dispatchers.IO) { ownIcon(app)?.let { png(drawSquare(it, SAVED_PX)) } } ?: return
        save(app, "png", bytes)
    }

    /** Makes [uri], an image the user picked, [app]'s icon: center-cropped to a square. */
    suspend fun pickImage(
        app: AndroidApp,
        uri: Uri,
    ): Boolean {
        val bmp =
            withContext(Dispatchers.IO) {
                runCatching {
                    val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val scale = SAVED_PX * 2f / minOf(info.size.width, info.size.height)
                        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val side = minOf(src.width, src.height)
                    val out = Bitmap.createBitmap(SAVED_PX, SAVED_PX, Bitmap.Config.ARGB_8888)
                    val from = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
                    Canvas(out).drawBitmap(src, from, Rect(0, 0, SAVED_PX, SAVED_PX), Paint(Paint.FILTER_BITMAP_FLAG))
                    png(out)
                }.onFailure { Log.w(TAG, "can't read $uri", it) }.getOrNull()
            } ?: return false
        save(app, "png", bmp)
        return true
    }

    /** Deletes the file that gives [app] its icon, so it gets the pack's or its own again. */
    suspend fun reset(app: AndroidApp) {
        val c = app.component
        val name = IconFiles.find(files, c.packageName, c.className) ?: return
        withContext(Dispatchers.IO) { File(dir, name).delete() }
        rescan()
    }

    /** Writes [bytes] as [app]'s icon file with extension [ext], replacing the others under its name. */
    private suspend fun save(
        app: AndroidApp,
        ext: String,
        bytes: ByteArray,
    ) {
        val stem = stemFor(app)
        val names = files
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                for (other in IconFiles.sameStem(names, stem)) File(dir, other).delete()
                writeAtomically(File(dir, "$stem.$ext"), bytes)
            }.onFailure { Log.w(TAG, "can't save the icon for ${app.component}", it) }
        }
        rescan()
    }

    /** The READMEs that tell agents and users how the folders work. */
    fun managed(): List<Managed> =
        listOf(
            Managed(File(dir, "README.md"), Policy.Owned) { context.assets.open("app-icons/README.md").use { it.readBytes() } },
            Managed(File(packsDir, "README.md"), Policy.Owned) { context.assets.open("app-icons/PACKS.md").use { it.readBytes() } },
        )

    /** icon-check, for agents to see an icon as the phone draws it. */
    fun guestManaged(guestRoot: File): List<Managed> =
        listOf(Managed(File(guestRoot, "usr/local/bin/icon-check"), Policy.Owned, executable = true) { context.assets.open("icon-check.sh").use { it.readBytes() } })

    /** The pack style.toml names: a folder of that name in [packsDir], else an installed app. */
    private suspend fun loadPack(name: String): IconPack? {
        val key = Style.ICON_PACK.key
        val folder = withContext(Dispatchers.IO) { if (PackManifest.isValidId(name)) FolderIconPack.infoOf(File(packsDir, name)) else null }
        if (folder != null) {
            val pack = open(folder) as? FolderIconPack
            for (problem in pack?.manifest?.problems.orEmpty()) style.report("$key: ~/$PACKS_GUEST_DIR/$name/${PackManifest.FILE}: $problem")
            return pack
        }
        val info = withContext(Dispatchers.IO) { AppIconPack.infoOf(context, name) }
        val pack = info?.let { open(it) }
        when {
            info == null -> style.report("$key: no folder \"$name\" in ~/$PACKS_GUEST_DIR and no app by that name is installed; showing the apps' own icons")
            pack == null -> style.report("$key: \"$name\" has no appfilter.xml the launcher can read; showing the apps' own icons")
        }
        return pack
    }

    private fun watch() {
        // The pack picked here or in style.toml.
        scope.launch {
            snapshotFlow { style.style[Style.ICON_PACK] to style.style[Style.ICON_PACK_BACKDROP] }.collect { setting ->
                val (name, backdrop) = setting
                if (setting == applied) return@collect
                if (name != applied?.first) {
                    style.clearReports(Style.ICON_PACK)
                    pack = name?.let { loadPack(it) }
                }
                applied = setting
                trim()
                val s = sources
                sources = Sources(pack, backdrop, s.files, s.colors)
                writeAppList()
                apps.refreshIcons()
            }
        }
        // Icons drawn in the theme's colors follow it.
        scope.launch {
            snapshotFlow { Palette.current }.collect {
                val colors = themeColors()
                val s = sources
                if (colors == s.colors) return@collect
                sources = Sources(s.pack, s.backdrop, s.files, colors)
                if ((s.pack as? FolderIconPack)?.followsTheme == true) apps.refreshIcons() else refresh(templated.toSet())
            }
        }
        scope.launch { watchDir(dir).collect { rescan() } }
        scope.launch { watchPacks(packsDir).collect { takePacks(it) } }
        // The drawer's list changed: apps.txt follows.
        scope.launch { snapshotFlow { apps.apps.toList() }.collect { writeAppList() } }
        // A pack installed, updated or removed; the one in use is read again.
        val packages =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    // An update removes the old version first; the new one's arrival follows.
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    scope.launch { syncAppPacks() }
                }
            }
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        // Calendar icons show the day.
        val day =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val calendars = sources.pack?.calendars ?: return
                    if (calendars.isNotEmpty()) apps.refreshIcons(apps.apps.filter { it.component.flattenToString() in calendars }.map { it.key })
                }
            }
        val dayFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        // Only the system sends these.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(packages, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(day, dayFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(packages, packageFilter)
            context.registerReceiver(day, dayFilter)
        }
    }

    /** Lists the installed packs again, and reads the one style.toml names again if it was updated, removed, or is back after it was missing. */
    private suspend fun syncAppPacks() {
        appPacks = withContext(Dispatchers.IO) { AppIconPack.installed(context) }
        val wanted = applied?.first ?: return
        val current = pack
        // Folders are watched apart.
        if (current is FolderIconPack) return
        val now = appPacks.firstOrNull { it.id == wanted }
        if (current == null && now == null) return
        if (current != null && now?.updated == current.info.updated) return
        loaded.remove(IconPackInfo(wanted, wanted, 0).key)
        style.clearReports(Style.ICON_PACK)
        pack = loadPack(wanted)
        sources = Sources(pack, sources.backdrop, sources.files, sources.colors)
        writeAppList()
        apps.refreshIcons()
    }

    /**
     * Lists the folder again and takes in what changed: the drawer draws the icons of apps a changed file
     * names again, and icon-check gets each new file as drawn. [refresh] false only takes the listing.
     */
    private suspend fun rescan(refresh: Boolean = true) =
        scanning.withLock {
            val now = withContext(Dispatchers.IO) { list() }
            if (now == listing) return@withLock
            val changed = (now.keys + listing.keys).filterTo(HashSet()) { now[it] != listing[it] }
            listing = now
            files = now.keys
            val s = sources
            sources = Sources(s.pack, s.backdrop, files, s.colors)
            if (!refresh) return@withLock
            refresh(changed)
            writeAppList()
            val colors = sources.colors
            withContext(Dispatchers.IO) { for (name in changed) if (name in now) check(dir, name, colors) }
        }

    /**
     * Takes in a new listing of the packs folder: the list of agents' packs, the pack in use when its
     * folder changed (all icons when its pack.toml or backdrop did, else the apps whose file changed),
     * and icon-check's checks of the files that changed.
     */
    private suspend fun takePacks(now: Map<String, Pair<Long, Long>>) {
        val changes = packChanges(packListing, now)
        packListing = now
        if (changes.isEmpty()) return
        folderPacks = withContext(Dispatchers.IO) { FolderIconPack.list(packsDir) }
        for (id in changes.keys) loaded.remove(IconPackInfo(id, id, 0, PackKind.Folder).key)
        val wanted = applied?.first
        val names = wanted?.let { changes[it] }
        if (wanted != null && names != null) {
            val before = pack as? FolderIconPack
            style.clearReports(Style.ICON_PACK)
            pack = loadPack(wanted)
            val s = sources
            sources = Sources(pack, s.backdrop, s.files, s.colors)
            val layers = before?.manifest?.layers.orEmpty() + (pack as? FolderIconPack)?.manifest?.layers.orEmpty()
            if (before == null || PackManifest.FILE in names || names.any { it in layers }) apps.refreshIcons() else refresh(names)
            writeAppList()
        }
        val colors = sources.colors
        withContext(Dispatchers.IO) {
            for ((id, changed) in changes) for (name in changed) if (IconFiles.isIcon(name) && "$id/$name" in now) check(File(packsDir, id), name, colors)
        }
    }

    /** Has the drawer draw again the apps whose icon one of [names] gives, or could. */
    private fun refresh(names: Set<String>) {
        if (names.isEmpty()) return
        val keys = apps.apps.filter { app -> IconFiles.candidates(app.component.packageName, app.component.className).any { it in names } }.map { it.key }
        if (keys.isNotEmpty()) apps.refreshIcons(keys)
    }

    private fun list(): Map<String, Pair<Long, Long>> {
        dir.mkdirs()
        return dir.listFiles().orEmpty().filter { it.isFile && IconFiles.isIcon(it.name) }.associate { it.name to (it.lastModified() to it.length()) }
    }

    /**
     * Writes .check/<name>.txt and the pictures in .preview next to icon file [name] in [folder] (the
     * icons folder or a pack's), which icon-check waits for and shows: the file as the drawer draws it,
     * on the theme's background, and as a silhouette in the monochrome style. Skipped when the check
     * already is of this version of the file. Which apps it's for, icon-check reads from apps.txt.
     */
    private fun check(
        folder: File,
        name: String,
        colors: Map<String, String>,
    ) {
        runCatching {
            val file = File(folder, name)
            val checkDir = File(folder, ".check")
            val previewDir = File(folder, ".preview")
            val hash = sha256(file)
            val checkFile = File(checkDir, "$name.txt")
            if (runCatching { checkFile.readLines().firstOrNull() }.getOrNull() == "sha256 $hash") return
            val drawn = drawIconFile(file, PREVIEW_PX, colors)
            val bmp = drawn.bitmap
            if (bmp != null) {
                previewDir.mkdirs()
                fun color(key: String, fallback: Int) = colors[key]?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: fallback
                val background = color("background", 0xFF1A1B26.toInt())
                val accent = color("accent", 0xFF7AA2F7.toInt())
                val surface = color("lighter_background", 0xFF24283B.toInt())
                val side = PREVIEW_PX * 4 / 3
                val at = (side - PREVIEW_PX) / 2f
                val plain = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                Canvas(plain).run {
                    drawColor(background)
                    drawBitmap(bmp, at, at, null)
                }
                val mono = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                Canvas(mono).run {
                    drawColor(background)
                    drawCircle(side / 2f, side / 2f, PREVIEW_PX / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
                    // As the drawer does: the silhouette at 36 of the badge's 52 dp.
                    val inner = PREVIEW_PX * 36 / 52
                    val m = Bitmap.createScaledBitmap(bitmapMask(bmp), inner, inner, true)
                    drawBitmap(m, (side - inner) / 2f, (side - inner) / 2f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN) })
                }
                writePng(File(previewDir, "$name.png"), plain)
                writePng(File(previewDir, "$name.mono.png"), mono)
            }
            val text =
                buildString {
                    append("sha256 ").append(hash).append('\n')
                    append("result ").append(if (bmp != null) "ok" else "failed").append('\n')
                    for (p in drawn.problems) append("problem ").append(p.replace('\n', ' ')).append('\n')
                }
            checkDir.mkdirs()
            writeAtomically(checkFile, text.toByteArray())
        }.onFailure { Log.w(TAG, "can't check $name", it) }
    }

    private fun writePng(
        file: File,
        bmp: Bitmap,
    ) = writeAtomically(file, png(bmp))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private var listWriting: Job? = null

    /** Rewrites apps.txt once changes stop coming, if what it says changed. */
    private fun writeAppList() {
        listWriting?.cancel()
        listWriting =
            scope.launch {
                delay(300)
                val all = apps.apps.toList()
                val entries = all.groupBy { it.component.packageName }.mapValues { (_, v) -> v.distinctBy { it.component.className }.size }
                val lines =
                    all.map { app ->
                        val c = app.component
                        IconFiles.Line(IconFiles.stemFor(c.packageName, c.className, entries[c.packageName] ?: 1), app.label, sourceOf(app))
                    }
                val text = IconFiles.appsText(lines)
                withContext(Dispatchers.IO) {
                    val file = File(dir, APPS)
                    if (runCatching { file.readText() }.getOrNull() != text) runCatching { writeAtomically(file, text.toByteArray()) }.onFailure { Log.w(TAG, "can't write $APPS", it) }
                }
            }
    }

    /** The theme's colors by name, as SVG templates take them. */
    private fun themeColors(): Map<String, String> = Palette.current.named.mapValues { "#%06x".format(it.value.toArgb() and 0xffffff) }

    private fun today() = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    companion object {
        /** The folder, relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/icons"

        /** The packs folder, relative to the guest home. */
        const val PACKS_GUEST_DIR = ".config/omalauncher/icon-packs"

        /** The drawer's apps, for agents to name icon files after. */
        const val APPS = "apps.txt"

        /** Icons picked in settings are saved this large: enough for the drawer at any density, and for another phone's. */
        private const val SAVED_PX = 256

        /** icon-check's pictures. */
        private const val PREVIEW_PX = 192

        /** Apps on a pack's card. */
        private const val SAMPLES = 4
    }
}

/**
 * [dir]'s icon files, as names with their modification time and size, now and after every change.
 * inotify tells of changes; a listing every few seconds covers a folder that's missing or was replaced.
 */
private fun watchDir(dir: File): Flow<Map<String, Pair<Long, Long>>> =
    callbackFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        var observer: FileObserver? = null
        val loop =
            launch {
                while (isActive) {
                    if (observer == null && dir.isDirectory) {
                        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE
                        observer =
                            object : FileObserver(dir, mask) {
                                override fun onEvent(
                                    event: Int,
                                    path: String?,
                                ) {
                                    if (path != null && IconFiles.isIcon(path)) changed.trySend(Unit)
                                }
                            }.also { it.startWatching() }
                    }
                    send(dir.listFiles().orEmpty().filter { it.isFile && IconFiles.isIcon(it.name) }.associate { it.name to (it.lastModified() to it.length()) })
                    // A writer may still be at it; look once it's done.
                    if (withTimeoutOrNull(if (observer != null) 30_000L else 5_000L) { changed.receive() } != null) delay(150)
                }
            }
        awaitClose {
            loop.cancel()
            observer?.stopWatching()
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

/** The packs folder's packs' files that matter, icons and pack.toml, as "<pack>/<file>" to modification time and size. */
private fun listPacks(root: File): Map<String, Pair<Long, Long>> =
    root
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && PackManifest.isValidId(it.name) }
        .flatMap { pack ->
            pack.listFiles().orEmpty().filter { it.isFile && (IconFiles.isIcon(it.name) || it.name == PackManifest.FILE) }.map { "${pack.name}/${it.name}" to (it.lastModified() to it.length()) }
        }.toMap()

/**
 * [root]'s packs' files, as [listPacks] lists them, now and after every change. inotify on the folder
 * and on each pack's tells of changes; a listing every few seconds covers what it misses.
 */
private fun watchPacks(root: File): Flow<Map<String, Pair<Long, Long>>> =
    callbackFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        // By pack, "" for the folder itself; only touched by the loop below and on close.
        val observers = HashMap<String, FileObserver>()
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE

        fun observe(
            key: String,
            dir: File,
        ) {
            if (key in observers) return
            observers[key] =
                object : FileObserver(dir, mask) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        // icon-check's own .check and .preview in a pack aren't changes to it.
                        if (path != null && !path.startsWith(".")) changed.trySend(Unit)
                    }
                }.also { it.startWatching() }
        }
        val loop =
            launch {
                while (isActive) {
                    root.mkdirs()
                    observe("", root)
                    val packs = root.listFiles().orEmpty().filter { it.isDirectory && PackManifest.isValidId(it.name) }
                    for (pack in packs) observe(pack.name, pack)
                    for (gone in observers.keys.filter { it.isNotEmpty() && packs.none { p -> p.name == it } }) observers.remove(gone)?.stopWatching()
                    send(listPacks(root))
                    // A writer may still be at it; look once it's done.
                    if (withTimeoutOrNull(30_000L) { changed.receive() } != null) delay(150)
                }
            }
        awaitClose {
            loop.cancel()
            observers.values.forEach { it.stopWatching() }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
