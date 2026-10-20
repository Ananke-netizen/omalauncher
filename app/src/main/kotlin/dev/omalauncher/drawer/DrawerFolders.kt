package dev.omalauncher.drawer

/** A folder in the app drawer: its [name] and its apps' package names, in the order they show. */
data class DrawerFolder(
    val name: String,
    val packages: List<String>,
)

/**
 * The drawer's folders as kept in style/drawer.txt, which Linux sees as
 * ~/.config/omalauncher/style/drawer.txt: a folder's name on a line of its
 * own, then its apps' package names, each on an indented line below it.
 * Lines starting with # are comments. An app listed twice stays in the first
 * folder that lists it; empty folders are dropped.
 */
object DrawerFoldersFile {
    const val NAME = "drawer.txt"

    private const val HEADER =
        "# Folders in the app drawer, in order: a folder's name, then its apps'\n" +
            "# package names indented below it. Apps in no folder are listed after them.\n" +
            "# Long-press an app in the drawer and pick \"folder\" to move it.\n"

    fun parse(text: String): List<DrawerFolder> {
        val out = ArrayList<Pair<String, ArrayList<String>>>()
        val seen = HashSet<String>()
        for (raw in text.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indented = raw[0] == ' ' || raw[0] == '\t'
            val line = raw.trim()
            if (!indented) {
                out += line to ArrayList()
            } else if (out.isNotEmpty() && seen.add(line)) {
                out.last().second += line
            }
        }
        return out.filter { it.second.isNotEmpty() }.map { DrawerFolder(it.first, it.second) }
    }

    fun format(folders: List<DrawerFolder>): String =
        buildString {
            append(HEADER)
            for (f in folders) {
                if (f.packages.isEmpty()) continue
                append('\n').append(f.name.trim()).append('\n')
                for (p in f.packages) append("  ").append(p).append('\n')
            }
        }

    /** [folders] with [pkg] taken out of whichever it was in and, unless [to] is null, added at the end of [to]. */
    fun move(
        folders: List<DrawerFolder>,
        pkg: String,
        to: String?,
    ): List<DrawerFolder> {
        val without = folders.map { it.copy(packages = it.packages - pkg) }
        val moved = if (to == null) without else without.map { if (it.name == to) it.copy(packages = it.packages + pkg) else it }
        return moved.filter { it.packages.isNotEmpty() }
    }

    /** The folder [pkg] shows in, if any. */
    fun folderOf(
        folders: List<DrawerFolder>,
        pkg: String,
    ): String? = folders.firstOrNull { pkg in it.packages }?.name
}
