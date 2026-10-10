package dev.omalauncher

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.ui.iconMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AndroidApp(
    val label: String,
    val component: ComponentName,
    /** Preinstalled and never updated-in-place away from that: can't be uninstalled, only disabled. */
    val system: Boolean = false,
    /** The profile it's installed in, like work; null for the launcher's own (personal) apps. */
    val profile: AppProfile? = null,
) {
    val id get() = AppId(component, profile?.serial)

    /** "Chrome · work" for a profile's copy, for the menu and screen readers. */
    val fullLabel get() = profileLabel(label, profile?.name, profile?.paused == true)

    /** Opens a personal app; a profile's copy can't be reached this way, see [AndroidAppCatalog.open]. */
    val intent: Intent
        get() =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * The phone's launchable apps, loaded once per process and kept current through
 * LauncherApps callbacks, so the drawer never waits on PackageManager.
 * Labels arrive first; icons fill in behind them. Apps of the user's other
 * profiles (work) are listed with the personal ones, each copy its own entry.
 */
class AndroidAppCatalog(
    private val context: Context,
) {
    val apps = mutableStateListOf<AndroidApp>()

    /** Icons as shown in color: a profile's copy wears the system's badge for that profile. */
    val icons = mutableStateMapOf<AppId, ImageBitmap>()

    /** Alpha-only silhouettes of the same icons, for the monochrome icon style; never badged. */
    val masks = mutableStateMapOf<AppId, ImageBitmap>()

    /** The other profiles listed, by serial; pinned apps look theirs up here. */
    val profiles = mutableStateMapOf<Long, AppProfile>()

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var loading: Job? = null

    /** Icon edge in px: the drawer's 52dp icon at this screen's density. */
    private val iconPx = (52 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    fun start() {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) = reload()

                override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()

                // Updates can change the icon, so drop the cached one.
                override fun onPackageChanged(packageName: String, user: UserHandle) = reload(evict = packageName)

                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()

                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            },
        )
        // A work profile added, removed, paused or resumed; only ever sent to receivers registered at runtime.
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = reload()
            }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        reload()
    }

    /**
     * Opens [app]. A profile's copy goes through LauncherApps, since startActivity can't
     * reach another profile; a paused one brings up the system's "turn on work apps?".
     */
    fun open(
        app: AndroidApp,
        from: Context = context,
    ) = start(app.component, app.profile?.user, from)

    /**
     * Opens a pinned app in the profile [serial] names (null: personal). If that profile is
     * gone, nothing opens, as for an app that was uninstalled; never the personal copy instead.
     */
    fun open(
        component: ComponentName,
        serial: Long?,
    ) {
        val user = serial?.let { profiles[it]?.user ?: runCatching { userManager.getUserForSerialNumber(it) }.getOrNull() }
        if (serial != null && user == null) {
            Log.w(TAG, "cannot open $component: profile $serial is gone")
            return
        }
        start(component, user, context)
    }

    private fun start(
        component: ComponentName,
        user: UserHandle?,
        from: Context,
    ) {
        runCatching {
            if (user == null) from.startActivity(AndroidApp("", component).intent) else launcherApps.startMainActivity(component, user, null, null)
        }.onFailure { Log.w(TAG, "cannot open $component", it) }
    }

    /** The system's App info page, as stock launchers open it, for the copy in [app]'s profile. */
    fun openInfo(app: AndroidApp) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.profile?.user ?: Process.myUserHandle(), null, null) }
            .onFailure { Log.w(TAG, "cannot open info for ${app.component}", it) }
    }

    /** Asks the system to uninstall [app]'s copy (EXTRA_USER, as Launcher3 does); the list updates through the package callback. */
    fun uninstall(app: AndroidApp) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    .putExtra(Intent.EXTRA_USER, app.profile?.user ?: Process.myUserHandle())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "cannot uninstall ${app.component}", it) }
    }

    /** Whether this is the default home app: only that may pause and resume work apps. */
    fun canPause(): Boolean = runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(false)

    /**
     * Pauses or resumes a work profile's apps, like Quick Settings' "Work apps". Resuming may
     * ask for the work profile's lock first; the list follows through the profile broadcasts.
     */
    fun setPaused(
        profile: AppProfile,
        paused: Boolean,
    ) {
        runCatching { userManager.requestQuietModeEnabled(paused, profile.user) }
            .onFailure { Log.w(TAG, "cannot ${if (paused) "pause" else "resume"} profile ${profile.serial}", it) }
    }

    /** [user] as listed: null for the launcher's own profile, or if it went away while loading. */
    private fun profileOf(user: UserHandle): AppProfile? {
        if (user == Process.myUserHandle()) return null
        val serial = userManager.getSerialNumberForUser(user).takeIf { it >= 0 } ?: return null
        // The user type only exists from Android 15; the color badge comes from the system either way.
        val type = if (Build.VERSION.SDK_INT >= 35) runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() else null
        return AppProfile(user, serial, profileName(type), runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false))
    }

    private fun reload(evict: String? = null) {
        loading?.cancel()
        loading =
            scope.launch {
                if (evict != null) {
                    icons.keys.removeAll { it.component.packageName == evict }
                    masks.keys.removeAll { it.component.packageName == evict }
                }
                val (found, infos) =
                    withContext(Dispatchers.IO) {
                        // Every profile a launcher may show, personal first. The private space isn't among
                        // them: only launchers that declare ACCESS_HIDDEN_PROFILES see it, and this one doesn't.
                        val me = Process.myUserHandle()
                        val users = runCatching { launcherApps.profiles }.getOrNull()?.takeIf { me in it } ?: listOf(me)
                        val found = users.mapNotNull { user -> profileOf(user).let { if (it == null && user != me) null else user to it } }
                        found to
                            found
                                .flatMap { (user, profile) ->
                                    // A profile that can't be listed (being removed) leaves the others.
                                    runCatching { launcherApps.getActivityList(null, user) }
                                        .onFailure { Log.w(TAG, "cannot list apps of $user", it) }
                                        .getOrDefault(emptyList())
                                        .filter { it.componentName.packageName != context.packageName }
                                        .map { it to AndroidApp(it.label.toString(), it.componentName, it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, profile) }
                                }
                                .sortedWith(appOrder({ it.second.label }, { it.second.profile?.serial }))
                    }
                val listed = found.mapNotNull { it.second }.associateBy { it.serial }
                if (listed != profiles.toMap()) {
                    profiles.keys.retainAll(listed.keys)
                    profiles.putAll(listed)
                }
                val list = infos.map { it.second }
                if (list != apps.toList()) {
                    apps.clear()
                    apps += list
                }
                val present = list.mapTo(HashSet()) { it.id }
                icons.keys.retainAll(present)
                masks.keys.retainAll(present)
                for ((info, app) in infos) {
                    if (app.id in icons) continue
                    launch {
                        // Both styles are rendered up front so switching is instant; masks are 1 byte per pixel.
                        val rendered =
                            withContext(iconDispatcher) {
                                runCatching {
                                    val d = info.getIcon(0)
                                    // In color a profile's copy wears the system's badge for that profile (work, clone).
                                    // Silhouettes stay unbadged, since the badge's would be a blob; cells draw a themed one.
                                    val shown = if (app.profile != null) info.getBadgedIcon(0) else d
                                    val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
                                    shown.setBounds(0, 0, iconPx, iconPx)
                                    shown.draw(Canvas(bmp))
                                    bmp.asImageBitmap() to iconMask(d, iconPx).asImageBitmap()
                                }.getOrNull()
                            }
                        if (rendered != null) {
                            icons[app.id] = rendered.first
                            masks[app.id] = rendered.second
                        }
                    }
                }
            }
    }

    companion object {
        private const val TAG = "apps"
    }
}
