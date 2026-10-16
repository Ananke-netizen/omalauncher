package dev.omalauncher.widgets

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedProfileTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"

    @Test fun personalAppsHaveNone() {
        // As saved before profiles, and by upstream for the launcher's own profile.
        assertNull(savedProfile(JSONObject("""{"key":"app:$chrome","page":2,"label":"Chrome","component":"$chrome"}""")))
    }

    @Test fun readsUpstreamsField() {
        assertEquals(10L, savedProfile(JSONObject("""{"key":"app:$chrome@10","label":"Chrome","component":"$chrome","profile":10}""")))
    }

    @Test fun readsTheForksEarlierField() {
        // Pinned by this fork's builds before upstream's work profiles: same key, serial under "user".
        assertEquals(10L, savedProfile(JSONObject("""{"key":"app:$chrome@10","label":"Chrome","component":"$chrome","user":10}""")))
    }

    @Test fun upstreamsFieldWins() {
        assertEquals(11L, savedProfile(JSONObject("""{"key":"app:x@11","label":"X","profile":11,"user":10}""")))
    }

    @Test fun nullIsNone() {
        assertNull(savedProfile(JSONObject("""{"key":"app:x","label":"X","profile":null,"user":null}""")))
    }
}
