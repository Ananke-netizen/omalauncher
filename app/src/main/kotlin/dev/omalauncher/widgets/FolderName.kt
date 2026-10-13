package dev.omalauncher.widgets

import java.text.BreakIterator

/** The longest folder name kept, in characters; a label under a 1x1 tile shows far fewer. */
internal const val FOLDER_NAME_MAX = 40

/**
 * A folder name as kept: one line with single spaces, at most [FOLDER_NAME_MAX] characters, cut
 * between whole characters (an emoji or an accented letter stays whole). Null for a blank one,
 * which leaves the folder unnamed.
 */
fun folderName(text: String?): String? {
    val line = text.orEmpty().trim().replace(Regex("\\s+"), " ")
    if (line.isEmpty()) return null
    if (line.length <= FOLDER_NAME_MAX) return line
    val chars = BreakIterator.getCharacterInstance().apply { setText(line) }
    return line.substring(0, chars.preceding(FOLDER_NAME_MAX + 1)).trimEnd()
}
