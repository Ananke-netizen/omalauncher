package dev.omalauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconFilesTest {
    private val pkg = "com.android.contacts"
    private val cls = "com.android.contacts.activities.PeopleActivity"

    @Test fun anActivitysFileWinsOverItsPackages() {
        val names = setOf("$pkg.png", "$pkg@PeopleActivity.svg")
        assertEquals("$pkg@PeopleActivity.svg", IconFiles.find(names, pkg, cls))
        assertEquals("$pkg.png", IconFiles.find(names, pkg, "$pkg.activities.DialtactsActivity"))
        assertNull(IconFiles.find(names, "org.other", "org.other.Main"))
    }

    @Test fun svgWinsOverBitmaps() {
        assertEquals("$pkg.svg", IconFiles.find(setOf("$pkg.jpg", "$pkg.png", "$pkg.svg"), pkg, cls))
        assertEquals("$pkg.png", IconFiles.find(setOf("$pkg.jpg", "$pkg.png", "$pkg.webp"), pkg, cls))
    }

    @Test fun savesUnderThePackageUnlessItHasSeveralEntries() {
        assertEquals(pkg, IconFiles.stemFor(pkg, cls, entries = 1))
        assertEquals("$pkg@PeopleActivity", IconFiles.stemFor(pkg, cls, entries = 2))
    }

    @Test fun readsOnlyIconFiles() {
        assertTrue(IconFiles.isIcon("org.mozilla.firefox.svg"))
        assertTrue(IconFiles.isIcon("org.mozilla.firefox@HomeActivity.webp"))
        assertFalse(IconFiles.isIcon("apps.txt"))
        assertFalse(IconFiles.isIcon("README.md"))
        assertFalse(IconFiles.isIcon(".check"))
        assertFalse(IconFiles.isIcon(".hidden.svg"))
        assertFalse(IconFiles.isIcon(".svg"))
        // The launcher looks names up with lowercase extensions only.
        assertFalse(IconFiles.isIcon("org.mozilla.firefox.SVG"))
    }

    @Test fun aChangeToAnyCandidateRedrawsTheApp() {
        val candidates = IconFiles.candidates(pkg, cls)
        assertTrue("$pkg.jpeg" in candidates)
        assertTrue("$pkg@PeopleActivity.svg" in candidates)
        assertFalse("$pkg.txt" in candidates)
    }

    @Test fun sameStemListsTheOtherFormats() {
        assertEquals(listOf("$pkg.svg", "$pkg.webp"), IconFiles.sameStem(setOf("$pkg.svg", "$pkg.webp", "$pkg@X.png", "other.png"), pkg))
    }

    @Test fun appsTextListsEachFileNameOnce() {
        val text =
            IconFiles.appsText(
                listOf(
                    IconFiles.Line("org.mozilla.firefox", "Firefox", IconFiles.Source.Pack),
                    // The same app in the work profile shares its file.
                    IconFiles.Line("org.mozilla.firefox", "Firefox", IconFiles.Source.Pack),
                    IconFiles.Line("$pkg@PeopleActivity", "Contacts\n", IconFiles.Source.File),
                    IconFiles.Line("com.b", "  Big   app ", IconFiles.Source.Own),
                ),
            )
        val lines = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(
            listOf(
                "com.b\tBig app\town",
                "$pkg@PeopleActivity\tContacts\tfile",
                "org.mozilla.firefox\tFirefox\tpack",
            ),
            lines,
        )
    }

    @Test fun findsUnfilledPlaceholders() {
        assertEquals(listOf("{{ accnet }}"), IconFiles.unfilled("""<rect fill="{{ accnet }}"/><rect fill="{{ accnet }}"/><rect fill="#7aa2f7"/>"""))
        assertEquals(emptyList<String>(), IconFiles.unfilled("""<rect fill="#7aa2f7"/>"""))
    }
}
