package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfilesTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"

    @Test fun personalKeysAreAsBeforeProfiles() {
        // What usage stats and pinned apps were saved under; they must keep matching.
        assertEquals("a:$chrome", profileKey("a:$chrome", null))
        assertEquals("app:$chrome", profileKey("app:$chrome", null))
    }

    @Test fun profileCopiesGetTheirOwnKeys() {
        assertEquals("a:$chrome@10", profileKey("a:$chrome", 10))
        assertEquals("app:$chrome@10", profileKey("app:$chrome", 10))
        assertNotEquals(profileKey("a:$chrome", 10), profileKey("a:$chrome", 11))
    }

    @Test fun personalCopyListsFirst() {
        val apps = listOf("Chrome" to 10L, "camera" to null, "Chrome" to null, "Chrome" to 11L, "Calendar" to 10L)
        val sorted = apps.sortedWith(appOrder({ it.first }, { it.second }))
        assertEquals(listOf("Calendar" to 10L, "camera" to null, "Chrome" to null, "Chrome" to 10L, "Chrome" to 11L), sorted)
    }

    @Test fun profileNames() {
        assertEquals("work", profileName("android.os.usertype.profile.MANAGED"))
        assertEquals("clone", profileName("android.os.usertype.profile.CLONE"))
        assertEquals("private", profileName("android.os.usertype.profile.PRIVATE"))
        assertEquals("supervised", profileName("android.os.usertype.profile.SUPERVISED"))
        // Before Android 15 there's no user type, and the profiles a launcher sees are work profiles.
        assertEquals("work", profileName(null))
    }

    @Test fun labels() {
        assertEquals("Chrome", profileLabel("Chrome", null))
        assertEquals("Chrome · work", profileLabel("Chrome", "work"))
        assertEquals("Chrome · work · paused", profileLabel("Chrome", "work", paused = true))
    }
}
