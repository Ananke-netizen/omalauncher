package dev.omalauncher.theme

import dev.omalauncher.upgrade.writeAtomically
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/** What we keep from the Omarchy repo: themes, config templates and app configs. */
private val KEEP_PATHS =
    listOf(
        "themes/", "default/themed/", "default/bash/", "config/btop/", "config/starship.toml",
        "config/lazygit/", "config/tmux/", "etc/fastfetch/", "icon.txt",
    )

/**
 * Omarchy's repo at [ThemeStore.OMARCHY_REF], unpacked where Omarchy keeps it
 * (~/.local/share/omarchy). A download replaces the whole directory in one
 * rename once complete: an interrupted one leaves the previous version, and
 * nothing lingers from it after a successful one.
 */
class OmarchyRelease(
    val dir: File,
) {
    private val marker = File(dir, ".omalauncher-ref")

    fun isCurrent() = runCatching { marker.readText().trim() }.getOrNull() == ThemeStore.OMARCHY_REF

    /**
     * Fetches one theme's colors and one of its backgrounds alone, from
     * raw.githubusercontent.com at the same commit: a few MB instead of the
     * release, so first run doesn't wait for it. [download] replaces them
     * with the full release later. Blocking.
     */
    fun downloadTheme(
        name: String,
        background: String?,
    ) {
        val theme = File(dir, "themes/$name")
        // The background first: a theme dir counts as installed once colors.toml is there.
        background?.let { fetch("themes/$name/backgrounds/$it", File(theme, "backgrounds/$it")) }
        fetch("themes/$name/colors.toml", File(theme, "colors.toml"))
    }

    private fun fetch(
        path: String,
        target: File,
    ) {
        val conn = URL("https://raw.githubusercontent.com/omacom/omarchy/${ThemeStore.OMARCHY_REF}/$path").openConnection()
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        writeAtomically(target, conn.getInputStream().use { it.readBytes() })
    }

    /** Blocking. [onProgress] gets the megabytes read so far. */
    fun download(onProgress: (Int) -> Unit) {
        val staging = File(dir.parentFile, ".omarchy.download").apply { deleteRecursively() }
        unpackTarball("https://codeload.github.com/omacom/omarchy/tar.gz/${ThemeStore.OMARCHY_REF}", staging, onProgress) { path ->
            // The lock-screen images are unused here and add ~20 MB.
            KEEP_PATHS.any { path.startsWith(it) } && !path.endsWith("unlock.png")
        }
        File(staging, marker.name).writeText(ThemeStore.OMARCHY_REF + "\n")
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "can't move the themes into place" }
    }
}

/**
 * Unpacks a GitHub tarball at [url] into [into], without the top directory
 * GitHub wraps it in. Takes regular files whose path [keep] accepts, and
 * none that would land outside [into]. [onProgress] gets the megabytes read
 * so far; past [maxMegabytes] it gives up. Blocking.
 */
internal fun unpackTarball(
    url: String,
    into: File,
    onProgress: (Int) -> Unit = {},
    maxMegabytes: Int = Int.MAX_VALUE,
    keep: (String) -> Boolean,
) {
    val conn = URL(url).openConnection()
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    var read = 0L
    val counting =
        object : FilterInputStream(conn.getInputStream()) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int =
                super.read(b, off, len).also {
                    if (it > 0) {
                        val before = read shr 20
                        read += it
                        if (read shr 20 != before) {
                            check(read shr 20 <= maxMegabytes) { "bigger than $maxMegabytes MB" }
                            onProgress((read shr 20).toInt())
                        }
                    }
                }
        }
    val root = into.canonicalFile
    TarArchiveInputStream(GZIPInputStream(counting.buffered(1 shl 16))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            // Entries look like <repo>-<ref>/path...
            val path = entry.name.substringAfter('/')
            if (!entry.isFile || path.isEmpty() || !keep(path)) continue
            val out = File(root, path)
            if (!out.canonicalPath.startsWith(root.path + File.separator)) continue
            out.parentFile?.mkdirs()
            out.outputStream().use { tar.copyTo(it) }
        }
    }
}
