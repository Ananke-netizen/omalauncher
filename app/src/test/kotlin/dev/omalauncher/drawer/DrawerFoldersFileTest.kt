package dev.omalauncher.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerFoldersFileTest {
    @Test fun readsNamesAndIndentedPackages() {
        val text = "# c\n\nTalk\n  com.whatsapp\n\torg.telegram.messenger\n\nEmpty\nMoney\n  com.venmo\n  com.whatsapp\n"
        assertEquals(
            listOf(DrawerFolder("Talk", listOf("com.whatsapp", "org.telegram.messenger")), DrawerFolder("Money", listOf("com.venmo"))),
            DrawerFoldersFile.parse(text),
        )
    }

    @Test fun packagesBeforeAnyFolderAreIgnored() {
        assertEquals(emptyList<DrawerFolder>(), DrawerFoldersFile.parse("  com.x\n"))
    }

    @Test fun formatRoundTrips() {
        val f = listOf(DrawerFolder("Eat & shop", listOf("a.b", "c.d")), DrawerFolder("Go", listOf("e.f")))
        assertEquals(f, DrawerFoldersFile.parse(DrawerFoldersFile.format(f)))
    }

    @Test fun moveTakesOutAndAppends() {
        val f = listOf(DrawerFolder("A", listOf("x", "y")), DrawerFolder("B", listOf("z")))
        assertEquals(listOf(DrawerFolder("A", listOf("y")), DrawerFolder("B", listOf("z", "x"))), DrawerFoldersFile.move(f, "x", "B"))
        assertEquals(listOf(DrawerFolder("A", listOf("x", "y"))), DrawerFoldersFile.move(f, "z", null))
        assertEquals("A", DrawerFoldersFile.folderOf(f, "y"))
        assertNull(DrawerFoldersFile.folderOf(f, "q"))
    }
}
