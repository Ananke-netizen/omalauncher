package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameTest {
    @Test fun keepsANameAsTyped() {
        assertEquals("Games", folderName("Games"))
        assertEquals("work · chat", folderName("work · chat"))
    }

    @Test fun blankLeavesTheFolderUnnamed() {
        assertNull(folderName(null))
        assertNull(folderName(""))
        assertNull(folderName("   \n\t "))
    }

    @Test fun putsItOnOneLineWithSingleSpaces() {
        assertEquals("social media", folderName("  social \n\n media\t"))
    }

    @Test fun cutsALongNameAtTheLimit() {
        val long = "a".repeat(FOLDER_NAME_MAX + 10)
        assertEquals("a".repeat(FOLDER_NAME_MAX), folderName(long))
    }

    @Test fun neverCutsACharacterInHalf() {
        // An emoji is two chars in a String; one straddling the limit is left out whole.
        val name = "a".repeat(FOLDER_NAME_MAX - 1) + "🎮" + "b"
        assertEquals("a".repeat(FOLDER_NAME_MAX - 1), folderName(name))
        // An accented letter written as a letter and a combining mark stays together.
        val accented = "a".repeat(FOLDER_NAME_MAX - 1) + "é" + "b"
        assertEquals("a".repeat(FOLDER_NAME_MAX - 1), folderName(accented))
    }

    @Test fun doesNotEndInASpaceAfterCutting() {
        val name = "a".repeat(FOLDER_NAME_MAX - 1) + " bcd"
        assertEquals("a".repeat(FOLDER_NAME_MAX - 1), folderName(name))
    }
}
