package dev.omalauncher.icons

/** One start tag of an XML file, its name and attributes: what reading appfilter.xml and drawable.xml takes. */
class XmlTag(
    val name: String,
    val attrs: Map<String, String>,
)

/**
 * An icon pack's appfilter.xml: which of the pack's drawables stands for which app, and how
 * the pack dresses up the apps it has no drawable for. This is the format icon packs on the
 * Play Store and F-Droid ship, in res/xml or in assets:
 *
 * ```
 * <item component="ComponentInfo{org.mozilla.firefox/org.mozilla.fenix.HomeActivity}" drawable="firefox" />
 * <calendar component="ComponentInfo{…}" prefix="calendar_" />   calendar_1 … calendar_31
 * <iconback img1="back" img2="back_alt" />  <iconmask img1="mask" />  <iconupon img1="shine" />
 * <scale factor="0.7" />
 * ```
 */
class AppFilter(
    /** Drawable name by activity, as [componentKey] writes it. */
    val icons: Map<String, String>,
    /** Drawable name prefix by activity, for apps whose icon shows the date: the prefix and the day of the month, 1 to 31. */
    val calendars: Map<String, String> = emptyMap(),
    /** Backdrops for apps the pack has no drawable for; each app always gets the same one. */
    val backs: List<String> = emptyList(),
    /** Cuts out of an app's own icon where it's opaque, before the backdrop goes behind it. */
    val mask: String? = null,
    /** Drawn over an app's own icon on the backdrop: a shine, a frame. */
    val upon: String? = null,
    /** How large an app's own icon is drawn on the backdrop, 1 as large as the backdrop. */
    val scale: Float = 1f,
) {
    /** Whether the pack dresses up apps it has no drawable for. */
    val hasBackdrop get() = backs.isNotEmpty() || mask != null || upon != null

    /** The drawable for [component] on day [dayOfMonth]: the calendar one, if the pack has it, else its icon. */
    fun drawableFor(
        component: String,
        dayOfMonth: Int,
    ): String? = calendars[component]?.let { it + dayOfMonth } ?: icons[component]

    /** Whether the pack has a drawable for [component]. */
    fun covers(component: String) = component in icons || component in calendars

    /** [component]'s backdrop, from its name, so it keeps the same one each time it's drawn. */
    fun backFor(component: String): String? = backs.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(component.hashCode(), it.size)] }

    /** Every drawable the pack maps an app to, for its icon picker when it has no drawable.xml. */
    val drawables: List<String> get() = icons.values.distinct().sorted()

    companion object {
        /** What one pack may list; more is a broken or hostile file, and the rest is ignored. */
        const val MAX_TAGS = 300_000

        /** The scale a pack can set; outside it the app's icon would vanish or spill off the backdrop. */
        private val SCALE = 0.1f..1.5f

        fun parse(tags: Sequence<XmlTag>): AppFilter {
            val icons = HashMap<String, String>()
            val calendars = HashMap<String, String>()
            val backs = ArrayList<String>()
            var mask: String? = null
            var upon: String? = null
            var scale = 1f
            for (tag in tags.take(MAX_TAGS)) {
                when (tag.name) {
                    // A pack listing an app twice means the first: later lines are usually leftovers.
                    "item" -> {
                        val component = tag.attrs["component"]?.let(::componentKey) ?: continue
                        val drawable = tag.attrs["drawable"]?.trim()?.takeIf(::isResourceName) ?: continue
                        icons.putIfAbsent(component, drawable)
                    }
                    "calendar" -> {
                        val component = tag.attrs["component"]?.let(::componentKey) ?: continue
                        val prefix = tag.attrs["prefix"]?.trim()?.takeIf(::isResourceName) ?: continue
                        calendars.putIfAbsent(component, prefix)
                    }
                    // img1, img2, … in order.
                    "iconback" ->
                        tag.attrs.entries
                            .filter { it.key.startsWith("img") }
                            .sortedBy { it.key.removePrefix("img").toIntOrNull() ?: Int.MAX_VALUE }
                            .mapNotNullTo(backs) { it.value.trim().takeIf(::isResourceName) }
                    "iconmask" -> mask = mask ?: tag.attrs["img1"]?.trim()?.takeIf(::isResourceName)
                    "iconupon" -> upon = upon ?: tag.attrs["img1"]?.trim()?.takeIf(::isResourceName)
                    "scale" -> (tag.attrs["factor"] ?: tag.attrs.values.firstOrNull())?.trim()?.toFloatOrNull()?.takeIf { it in SCALE }?.let { scale = it }
                }
            }
            return AppFilter(icons, calendars, backs.distinct(), mask, upon, scale)
        }
    }
}

/** A drawable.xml category: the icons a pack lists under one title, in its picker. */
class IconCategory(
    val title: String?,
    val drawables: List<String>,
)

/**
 * A pack's drawable.xml: every icon it has, by category (`<category title="…"/>` then
 * `<item drawable="…"/>` lines), for picking one by hand. Empty categories are left out.
 */
fun parseDrawableList(tags: Sequence<XmlTag>): List<IconCategory> {
    val out = ArrayList<IconCategory>()
    var title: String? = null
    var items = ArrayList<String>()
    fun end() {
        if (items.isNotEmpty()) out += IconCategory(title, items.distinct())
        items = ArrayList()
    }
    for (tag in tags.take(AppFilter.MAX_TAGS)) {
        when (tag.name) {
            "category" -> {
                end()
                title = tag.attrs["title"]?.trim()?.takeIf { it.isNotEmpty() }
            }
            "item" -> tag.attrs["drawable"]?.trim()?.takeIf(::isResourceName)?.let { items += it }
        }
    }
    end()
    return out
}

/**
 * The activity in an appfilter `component` as `package/class`, with the class written out in full
 * like ComponentName.flattenToString: `ComponentInfo{com.app/.Main}` is `com.app/com.app.Main`.
 * Null for what isn't one, like the generic `:BROWSER` entries some packs list.
 */
fun componentKey(raw: String): String? {
    var s = raw.trim()
    if (s.startsWith("ComponentInfo{")) s = s.removePrefix("ComponentInfo{").removeSuffix("}")
    val slash = s.indexOf('/')
    if (slash <= 0 || slash == s.lastIndex) return null
    val pkg = s.substring(0, slash).trim()
    var cls = s.substring(slash + 1).trim()
    if (pkg.isEmpty() || cls.isEmpty() || pkg.any { it.isWhitespace() } || cls.any { it.isWhitespace() }) return null
    if (cls.startsWith(".")) cls = pkg + cls
    return "$pkg/$cls"
}

/** Whether [name] can be an Android resource name; anything else would never resolve. */
fun isResourceName(name: String) = name.isNotEmpty() && name.length <= 200 && name.all { it.isLetterOrDigit() || it == '_' || it == '.' }

/**
 * The pack's [names] that match [query], best first: the name itself, names starting with it, then names
 * containing it, comparing letters and digits only, so "google chrome" finds google_chrome; then names
 * containing its words of three letters or more, so an app's name ("Google Chrome") also finds "chrome",
 * a whole word first. Everything for a blank query.
 */
fun searchDrawables(
    names: List<String>,
    query: String,
): List<String> {
    val q = simplify(query)
    if (q.isEmpty()) return names
    val words = query.split(Regex("[^\\p{L}\\p{N}]+")).map(::simplify).filter { it.length >= 3 && it != q }.distinct()
    val exact = ArrayList<String>()
    val starts = ArrayList<String>()
    val contains = ArrayList<String>()
    val partly = ArrayList<Pair<String, Int>>()
    for (name in names) {
        val n = simplify(name)
        when {
            n == q -> exact += name
            n.startsWith(q) -> starts += name
            q in n -> contains += name
            else -> {
                val score = (if (n in words) words.size + 1 else 0) + words.count { it in n }
                if (score > 0) partly += name to score
            }
        }
    }
    return exact + starts + contains + partly.sortedByDescending { it.second }.map { it.first }
}

private fun simplify(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
