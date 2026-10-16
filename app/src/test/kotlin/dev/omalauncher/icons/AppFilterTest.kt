package dev.omalauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterTest {
    private fun tag(
        name: String,
        vararg attrs: Pair<String, String>,
    ) = XmlTag(name, mapOf(*attrs))

    @Test fun mapsActivitiesToDrawables() {
        val filter =
            AppFilter.parse(
                sequenceOf(
                    tag("resources"),
                    tag("item", "component" to "ComponentInfo{org.mozilla.firefox/org.mozilla.fenix.HomeActivity}", "drawable" to "firefox"),
                    // The class written short, as ComponentName.flattenToShortString does.
                    tag("item", "component" to "ComponentInfo{com.example.mail/.Inbox}", "drawable" to "mail"),
                    tag("item", "component" to " ComponentInfo{com.example.maps/com.example.maps.Main} ", "drawable" to " maps "),
                ),
            )
        assertEquals("firefox", filter.icons["org.mozilla.firefox/org.mozilla.fenix.HomeActivity"])
        assertEquals("mail", filter.icons["com.example.mail/com.example.mail.Inbox"])
        assertEquals("maps", filter.icons["com.example.maps/com.example.maps.Main"])
        assertTrue(filter.covers("com.example.mail/com.example.mail.Inbox"))
        assertFalse(filter.covers("com.example.mail/com.example.mail.Other"))
        assertFalse(filter.hasBackdrop)
    }

    @Test fun skipsWhatIsntAnActivityOrADrawable() {
        val filter =
            AppFilter.parse(
                sequenceOf(
                    tag("item", "component" to ":BROWSER", "drawable" to "browser"),
                    tag("item", "component" to "ComponentInfo{}", "drawable" to "x"),
                    tag("item", "component" to "ComponentInfo{com.a/}", "drawable" to "x"),
                    tag("item", "component" to "ComponentInfo{/com.a.B}", "drawable" to "x"),
                    tag("item", "component" to "ComponentInfo{com.a/com.a.B}"),
                    tag("item", "component" to "ComponentInfo{com.a/com.a.C}", "drawable" to "../../evil"),
                    tag("item", "drawable" to "orphan"),
                ),
            )
        assertEquals(emptyMap<String, String>(), filter.icons)
    }

    @Test fun firstEntryForAnActivityWins() {
        val filter =
            AppFilter.parse(
                sequenceOf(
                    tag("item", "component" to "ComponentInfo{com.a/com.a.B}", "drawable" to "first"),
                    tag("item", "component" to "ComponentInfo{com.a/.B}", "drawable" to "second"),
                ),
            )
        assertEquals("first", filter.icons["com.a/com.a.B"])
    }

    @Test fun calendarsShowTheDay() {
        val filter =
            AppFilter.parse(
                sequenceOf(
                    tag("item", "component" to "ComponentInfo{com.cal/com.cal.Main}", "drawable" to "calendar"),
                    tag("calendar", "component" to "ComponentInfo{com.cal/com.cal.Main}", "prefix" to "calendar_"),
                ),
            )
        assertEquals("calendar_7", filter.drawableFor("com.cal/com.cal.Main", 7))
        assertEquals("calendar_31", filter.drawableFor("com.cal/com.cal.Main", 31))
        assertEquals(null, filter.drawableFor("com.other/com.other.Main", 7))
    }

    @Test fun readsTheBackdrop() {
        val filter =
            AppFilter.parse(
                sequenceOf(
                    tag("iconback", "img2" to "back_b", "img1" to "back_a"),
                    tag("iconmask", "img1" to "mask"),
                    tag("iconupon", "img1" to "shine"),
                    tag("scale", "factor" to "0.64"),
                ),
            )
        assertTrue(filter.hasBackdrop)
        assertEquals(listOf("back_a", "back_b"), filter.backs)
        assertEquals("mask", filter.mask)
        assertEquals("shine", filter.upon)
        assertEquals(0.64f, filter.scale, 0.0001f)
        // Each app keeps its backdrop.
        val back = filter.backFor("com.a/com.a.B")
        assertTrue(back in filter.backs)
        assertEquals(back, filter.backFor("com.a/com.a.B"))
    }

    @Test fun scaleOutsideItsRangeIsIgnored() {
        assertEquals(1f, AppFilter.parse(sequenceOf(tag("scale", "factor" to "0"))).scale, 0f)
        assertEquals(1f, AppFilter.parse(sequenceOf(tag("scale", "factor" to "12"))).scale, 0f)
        assertEquals(1f, AppFilter.parse(sequenceOf(tag("scale", "factor" to "big"))).scale, 0f)
        // Some packs leave out the attribute's name.
        assertEquals(0.8f, AppFilter.parse(sequenceOf(tag("scale", "x" to "0.8"))).scale, 0.0001f)
    }

    @Test fun backdroplessPackHasNone() {
        val filter = AppFilter.parse(sequenceOf(tag("iconback")))
        assertFalse(filter.hasBackdrop)
        assertNull(filter.backFor("com.a/com.a.B"))
    }

    @Test fun componentKeys() {
        assertEquals("com.a/com.a.Main", componentKey("ComponentInfo{com.a/com.a.Main}"))
        assertEquals("com.a/com.a.Main", componentKey("com.a/.Main"))
        assertEquals("com.a/org.b.Main", componentKey("com.a/org.b.Main"))
        assertNull(componentKey(":CAMERA"))
        assertNull(componentKey("ComponentInfo{com.a/com.a.Main extra}"))
    }

    @Test fun drawableListKeepsCategories() {
        val categories =
            parseDrawableList(
                sequenceOf(
                    tag("resources"),
                    tag("item", "drawable" to "orphan"),
                    tag("category", "title" to "New"),
                    tag("item", "drawable" to "firefox"),
                    tag("item", "drawable" to "firefox"),
                    tag("category", "title" to "Empty"),
                    tag("category", "title" to "A"),
                    tag("item", "drawable" to "android"),
                    tag("item", "drawable" to "bad name"),
                ),
            )
        assertEquals(listOf(null, "New", "A"), categories.map { it.title })
        assertEquals(listOf(listOf("orphan"), listOf("firefox"), listOf("android")), categories.map { it.drawables })
    }

    @Test fun searchFindsPrefixesThenPartsThenWords() {
        val names = listOf("google_maps", "chrome_beta", "google_chrome", "chrome", "calculator")
        assertEquals(listOf("google_chrome", "chrome", "google_maps", "chrome_beta"), searchDrawables(names, "Google Chrome"))
        assertEquals(listOf("chrome", "chrome_beta", "google_chrome"), searchDrawables(names, "chrome"))
        assertEquals(names, searchDrawables(names, "  "))
        assertEquals(emptyList<String>(), searchDrawables(names, "zz"))
    }
}
