package dev.omalauncher

import android.content.ComponentName
import android.os.UserHandle

/**
 * Another profile of the phone's user whose apps the launcher shows, like a work
 * profile. The launcher's own profile has none: its apps are the personal ones.
 */
data class AppProfile(
    val user: UserHandle,
    /** What identifies the profile across restarts; a UserHandle isn't to be persisted. */
    val serial: Long,
    /** How the profile is called in labels: "work", "clone"; see [profileName]. */
    val name: String,
    /** Its apps are paused (quiet mode, "work apps" off): listed, but dimmed. */
    val paused: Boolean,
) {
    /** A work profile, the kind whose apps the user can pause and resume. */
    val isWork get() = name == WORK
}

/**
 * An Android app's identity: a work copy of an app has the same component as
 * the personal one, so the profile's serial tells them apart. Null is the
 * launcher's own (personal) profile.
 */
data class AppId(
    val component: ComponentName,
    val serial: Long?,
)

private const val WORK = "work"

/**
 * [base] for the app in the profile [serial]: unchanged for personal apps, so keys
 * saved before profiles existed (usage, pinned apps, folders) still match.
 */
fun profileKey(
    base: String,
    serial: Long?,
): String = if (serial == null) base else "$base@$serial"

/** The app list's order: alphabetical; of two copies of an app, the personal one (no [serial]) first. */
fun <T> appOrder(
    label: (T) -> String,
    serial: (T) -> Long?,
): Comparator<T> = compareBy({ label(it).lowercase() }, { serial(it) ?: -1L })

/**
 * The profile's name in labels, from its user type (LauncherUserInfo, Android 15+).
 * Before Android 15 there's no user type to go by: the profiles a launcher sees are work profiles then.
 */
fun profileName(userType: String?): String =
    when (userType) {
        null, "android.os.usertype.profile.MANAGED" -> WORK
        "android.os.usertype.profile.CLONE" -> "clone"
        "android.os.usertype.profile.PRIVATE" -> "private"
        else -> userType.substringAfterLast('.').lowercase()
    }

/** "Chrome · work", or "Chrome · work · paused": what the menu and screen readers call an app. Just [label] for personal apps. */
fun profileLabel(
    label: String,
    profile: String?,
    paused: Boolean = false,
): String =
    when {
        profile == null -> label
        paused -> "$label · $profile · paused"
        else -> "$label · $profile"
    }
