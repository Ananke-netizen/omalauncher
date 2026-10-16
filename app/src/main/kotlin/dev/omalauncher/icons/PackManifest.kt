package dev.omalauncher.icons

import dev.omalauncher.theme.Toml

/**
 * A folder pack's pack.toml: its name, what it looks like, and how it dresses up the apps it
 * has no icon for, from layers in the folder like an installed pack's:
 *
 * ```
 * name = "Neon Grid"
 * description = "Thin accent strokes on the theme's background"
 * [backdrop]
 * image = "_backdrop.svg"   # behind the app's own icon
 * mask = "_mask.svg"        # cut out of the app's own icon where it's opaque
 * overlay = "_overlay.svg"  # over it
 * scale = 0.6               # how large the app's own icon is drawn on it
 * ```
 *
 * Everything is optional. What can't be read is left out and said in [problems].
 */
class PackManifest(
    val name: String? = null,
    val description: String? = null,
    val backdrop: String? = null,
    val mask: String? = null,
    val overlay: String? = null,
    val scale: Float = DEFAULT_SCALE,
    val problems: List<String> = emptyList(),
) {
    /** The files it uses as layers, which aren't icons for apps. */
    val layers get() = listOfNotNull(backdrop, mask, overlay)

    companion object {
        const val FILE = "pack.toml"

        /** An app's own icon on a backdrop, when pack.toml doesn't say how large. */
        const val DEFAULT_SCALE = 0.6f

        private val SCALE = 0.1f..1.5f
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        /** Whether [id] can name a pack folder: one plain name, which style.toml's icons.pack takes. */
        fun isValidId(id: String) = ID.matches(id)

        /** pack.toml's [text]; null, for a folder without one, is a pack named after its folder. */
        fun parse(text: String?): PackManifest {
            if (text.isNullOrBlank()) return PackManifest()
            val table =
                try {
                    Toml.parse(text)
                } catch (e: Toml.Error) {
                    return PackManifest(problems = listOf("$FILE ${e.message}"))
                }
            val problems = ArrayList<String>()

            fun text(key: String): String? {
                val v = table[key] ?: return null
                return (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: null.also { problems += "$key takes text in quotes" }
            }

            fun layer(key: String): String? {
                val v = text(key) ?: return null
                if (!IconFiles.isIcon(v) || '/' in v) {
                    problems += "$key takes the name of an .svg, .png, .webp or .jpg file in the pack's folder"
                    return null
                }
                return v
            }
            val scale =
                when (val v = table["backdrop.scale"]) {
                    null -> DEFAULT_SCALE
                    is Double -> v.toFloat().takeIf { it in SCALE } ?: DEFAULT_SCALE.also { problems += "backdrop.scale takes a number from 0.1 to 1.5" }
                    else -> DEFAULT_SCALE.also { problems += "backdrop.scale takes a number from 0.1 to 1.5" }
                }
            return PackManifest(
                name = text("name")?.take(60),
                description = text("description")?.take(200),
                backdrop = layer("backdrop.image"),
                mask = layer("backdrop.mask"),
                overlay = layer("backdrop.overlay"),
                scale = scale,
                problems = problems,
            )
        }
    }
}

/**
 * What changed between two listings of the packs folder ("<pack>/<file>" to modification time and
 * size): the files that changed, by pack. A pack that appeared or went is in it with all its files.
 */
fun packChanges(
    old: Map<String, Pair<Long, Long>>,
    new: Map<String, Pair<Long, Long>>,
): Map<String, Set<String>> =
    (old.keys + new.keys)
        .filter { old[it] != new[it] }
        .groupBy({ it.substringBefore('/') }, { it.substringAfter('/') })
        .mapValues { it.value.toSet() }
