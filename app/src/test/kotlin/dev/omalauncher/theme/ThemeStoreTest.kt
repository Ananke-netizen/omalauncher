package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeStoreTest {
    /** A first run that kept the offered theme writes none; restarting must still show it, not leave home unthemed. */
    @Test fun anUnsetThemeIsTheDefaultOne() {
        assertEquals("tokyo-night", ThemeStore.themeOf(Style()))
        assertEquals("nord", ThemeStore.themeOf(Style().with(Style.THEME, "nord")))
    }
}
