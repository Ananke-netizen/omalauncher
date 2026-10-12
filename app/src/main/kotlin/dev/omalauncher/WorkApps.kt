package dev.omalauncher

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Pausing and resuming work apps from a work app's long-press menu, like Quick Settings' "Work apps".
 * Only the default home app may (UserManager.requestQuietModeEnabled); resuming may ask for the work
 * profile's lock first, and [AndroidAppCatalog] follows either way through the profile broadcasts.
 */
class WorkApps(
    private val context: Context,
    private val catalog: AndroidAppCatalog,
) {
    private val userManager = context.getSystemService(UserManager::class.java)

    /** The menu entry for [app]: "pause work apps" or "turn on work apps"; null for apps of the launcher's own profile, or while this isn't the home app. */
    fun action(app: AndroidApp): Pair<String, () -> Unit>? {
        if (app.profile == null || !isHome() || !isManaged(app)) return null
        val paused = catalog.paused(app.profile)
        return (if (paused) "turn on work apps" else "pause work apps") to { setPaused(app, !paused) }
    }

    private fun setPaused(
        app: AndroidApp,
        paused: Boolean,
    ) {
        runCatching { userManager.requestQuietModeEnabled(paused, app.user) }
            .onFailure { Log.w(TAG, "cannot ${if (paused) "pause" else "resume"} profile ${app.profile}", it) }
    }

    private fun isHome() = runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(false)

    /**
     * A work profile, the kind that pauses; a clone profile doesn't. The user type only exists from
     * Android 15: before that, the profiles a launcher sees are work profiles.
     */
    private fun isManaged(app: AndroidApp): Boolean {
        if (Build.VERSION.SDK_INT < 35) return true
        val type = runCatching { context.getSystemService(LauncherApps::class.java).getLauncherUserInfo(app.user)?.userType }.getOrNull()
        return type == null || type == UserManager.USER_TYPE_PROFILE_MANAGED
    }

    private companion object {
        const val TAG = "work-apps"
    }
}
