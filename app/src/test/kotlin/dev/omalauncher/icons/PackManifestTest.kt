package dev.omalauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestTest {
    @Test fun readsNameDescriptionAndBackdrop() {
        val m =
            PackManifest.parse(
                """
                name = "Neon Grid"
                description = "Thin accent strokes"
                [backdrop]
                image = "_backdrop.svg"
                mask = "_mask.png"
                overlay = "_overlay.svg"
                scale = 0.5
                """.trimIndent(),
            )
        assertEquals("Neon Grid", m.name)
        assertEquals("Thin accent strokes", m.description)
        assertEquals(listOf("_backdrop.svg", "_mask.png", "_overlay.svg"), m.layers)
        assertEquals(0.5f, m.scale, 0f)
        assertEquals(emptyList<String>(), m.problems)
    }

    @Test fun aFolderWithoutOneIsAPackNamedAfterIt() {
        for (text in listOf(null, "", "  \n")) {
            val m = PackManifest.parse(text)
            assertNull(m.name)
            assertEquals(emptyList<String>(), m.layers)
            assertEquals(PackManifest.DEFAULT_SCALE, m.scale, 0f)
        }
    }

    @Test fun badValuesAreLeftOutAndSaid() {
        val m =
            PackManifest.parse(
                """
                name = 3
                [backdrop]
                image = "../outside.svg"
                mask = "notes.txt"
                overlay = ".hidden.svg"
                scale = 9
                """.trimIndent(),
            )
        assertNull(m.name)
        assertEquals(emptyList<String>(), m.layers)
        assertEquals(PackManifest.DEFAULT_SCALE, m.scale, 0f)
        assertEquals(5, m.problems.size)
    }

    @Test fun aFileThatDoesntParseIsSaid() {
        val m = PackManifest.parse("name = \"unclosed\n")
        assertNull(m.name)
        assertTrue(m.problems.single().startsWith("pack.toml line 1"))
    }

    @Test fun packNamesAreOnePlainName() {
        for (ok in listOf("neon", "neon-grid", "Neon_Grid.2", "a")) assertTrue(ok, PackManifest.isValidId(ok))
        for (bad in listOf("", ".hidden", "-flag", "a/b", "../x", "a b", "a;rm", "x".repeat(65))) assertFalse(bad, PackManifest.isValidId(bad))
    }

    @Test fun changesAreGroupedByPack() {
        val old = mapOf("neon/a.svg" to (1L to 10L), "neon/b.svg" to (1L to 10L), "mono/pack.toml" to (1L to 5L))
        val new = mapOf("neon/a.svg" to (2L to 11L), "neon/b.svg" to (1L to 10L), "fresh/c.svg" to (3L to 7L))
        assertEquals(mapOf("neon" to setOf("a.svg"), "mono" to setOf("pack.toml"), "fresh" to setOf("c.svg")), packChanges(old, new))
        assertEquals(emptyMap<String, Set<String>>(), packChanges(new, new))
    }
}
