package dev.omalauncher.widgets

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** ComponentName is a stub on the JVM, so these check what's around it: keys, folders and the profile. */
class ShortcutJsonTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"

    @Test fun shortcutsFromBeforeProfilesArePersonal() {
        // As a launcher before profiles saved it.
        val old = JSONObject("""{"key":"app:$chrome","page":2,"label":"Chrome","component":"$chrome","folder":"folder:abc"}""")
        val s = parseShortcut(old)
        assertEquals("app:$chrome", s.key)
        assertEquals(2, s.page)
        assertEquals("folder:abc", s.folder)
        assertNull(s.serial)
    }

    @Test fun workShortcutsKeepTheirProfile() {
        val saved = JSONObject("""{"key":"app:$chrome@10","page":1,"label":"Chrome","component":"$chrome","user":10}""")
        val s = parseShortcut(saved)
        assertEquals(10L, s.serial)
        assertEquals("app:$chrome@10", s.key)
    }

    @Test fun personalShortcutsAreSavedAsBefore() {
        val o = shortcutJson(Shortcut("app:$chrome", 1, "Chrome"))
        assertFalse(o.has("user"))
        assertNull(parseShortcut(o).serial)
    }

    @Test fun workShortcutsRoundTrip() {
        val o = shortcutJson(Shortcut("app:$chrome@10", 3, "Chrome", folder = "folder:x", serial = 10))
        assertEquals(10L, o.getLong("user"))
        val back = parseShortcut(o)
        assertEquals(10L, back.serial)
        assertEquals("folder:x", back.folder)
        assertEquals(3, back.page)
    }

    @Test fun nullUserIsPersonal() {
        assertNull(parseShortcut(JSONObject("""{"key":"app:x","label":"X","user":null}""")).serial)
    }
}
