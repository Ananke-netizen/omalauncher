package dev.omalauncher.icons

/**
 * How icon files in ~/.config/omalauncher/icons are named, which the user, agents and the
 * launcher's icon picker all write: `<package>.svg` for every entry of an app in the drawer,
 * `<package>@<Activity>.svg` for one of several. SVG, PNG, WebP or JPEG.
 */
object IconFiles {
    /** The formats read, the one that wins first when a name has more than one. */
    val EXTENSIONS = listOf("svg", "png", "webp", "jpg", "jpeg")

    /** The largest file read; icons are small, and more is a mistake (a photo saved at full size). */
    const val MAX_BYTES = 4L shl 20

    /** What apps.txt calls where an app's icon comes from now. */
    enum class Source(
        val word: String,
    ) {
        File("file"),
        Pack("pack"),
        Backdrop("backdrop"),
        Own("own"),
    }

    /** The names an icon file for activity [cls] of [pkg] can go by, without extension, the one that wins first. */
    fun stems(
        pkg: String,
        cls: String,
    ) = listOf("$pkg@${simpleName(cls)}", pkg)

    /**
     * The name to save [cls]'s icon under: its package's, unless the package has [entries] more
     * than one in the drawer, where that name would change the others too.
     */
    fun stemFor(
        pkg: String,
        cls: String,
        entries: Int,
    ) = if (entries > 1) "$pkg@${simpleName(cls)}" else pkg

    /** The file in [names] (a directory's listing) that gives [cls] of [pkg] its icon, or null. */
    fun find(
        names: Set<String>,
        pkg: String,
        cls: String,
    ): String? {
        for (stem in stems(pkg, cls)) for (ext in EXTENSIONS) "$stem.$ext".takeIf { it in names }?.let { return it }
        return null
    }

    /** Whether [name] is an icon file the launcher reads: one of [EXTENSIONS], not hidden. */
    fun isIcon(name: String) = !name.startsWith(".") && name.substringAfterLast('.', "") in EXTENSIONS && stemOf(name).isNotEmpty()

    /** Every file name that would give [cls] of [pkg] its icon; a change to any of them can change it. */
    fun candidates(
        pkg: String,
        cls: String,
    ) = stems(pkg, cls).flatMap { stem -> EXTENSIONS.map { "$stem.$it" } }

    /** [name] without its extension. */
    fun stemOf(name: String) = name.substringBeforeLast('.')

    /** The other files in [names] that name [stem] too, so saving one icon for it leaves no other in its way. */
    fun sameStem(
        names: Set<String>,
        stem: String,
    ) = EXTENSIONS.map { "$stem.$it" }.filter { it in names }

    /** Activity [cls]'s name without its package: `HomeActivity` for `org.mozilla.fenix.HomeActivity`. */
    fun simpleName(cls: String) = cls.substringAfterLast('.')

    /** One drawer app in apps.txt. */
    class Line(
        val stem: String,
        val label: String,
        val source: Source,
    )

    /**
     * apps.txt: the apps in the drawer, one per line, for agents to name icon files after. Tabs between
     * the name to save under, the app's name and where its icon comes from now. A package installed
     * in two profiles shares its icon file, so it's listed once.
     */
    fun appsText(lines: List<Line>): String =
        buildString {
            append("# The apps in the drawer: the file name an icon for it takes (add .svg, .png, .webp or .jpg),\n")
            append("# its name, and where its icon comes from now: file (one here), pack (the icon pack's),\n")
            append("# backdrop (its own on the icon pack's backdrop) or own. The launcher rewrites this file.\n")
            for (line in lines.distinctBy { it.stem }.sortedWith(compareBy({ it.label.lowercase() }, { it.stem }))) {
                append(line.stem).append('\t').append(line.label.replace(Regex("\\s+"), " ").trim()).append('\t').append(line.source.word).append('\n')
            }
        }

    /** Placeholders a template left unfilled, `{{ accnet }}` for a typo: they'd draw as no color at all. */
    fun unfilled(svg: String): List<String> = Regex("""\{\{[^}]*\}\}""").findAll(svg).map { it.value }.distinct().toList()
}
