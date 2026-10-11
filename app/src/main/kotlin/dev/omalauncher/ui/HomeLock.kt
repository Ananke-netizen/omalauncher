package dev.omalauncher.ui

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Double-tap on empty home locks the screen. Without root the only way is
 * DevicePolicyManager.lockNow(), which needs a device admin the user activates
 * once (the system shows what it grants). Until then the double-tap opens that
 * activation screen instead of failing silently.
 */
class LockAdminReceiver : DeviceAdminReceiver()

object HomeLock {
    fun admin(context: Context) = ComponentName(context, LockAdminReceiver::class.java)

    fun granted(context: Context) =
        manager(context).isAdminActive(admin(context))

    /** Locks; false (and the activation prompt) when the admin isn't active yet. */
    fun lock(context: Context): Boolean {
        val dpm = manager(context)
        if (!granted(context)) {
            Toast.makeText(context, "double-tap lock: activate Omalauncher's device admin", Toast.LENGTH_SHORT).show()
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Omalauncher uses it only to lock the screen on a double-tap.")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        dpm.lockNow()
        return true
    }

    private fun manager(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
}

/**
 * Two taps make a double-tap when the second lands within [timeoutMs] of the
 * first and no further apart than [slopPx]. Pure, so the window is testable.
 */
fun isDoubleTap(
    firstTimeMs: Long,
    secondTimeMs: Long,
    dx: Float,
    dy: Float,
    timeoutMs: Long = 300L,
    slopPx: Float = 100f,
): Boolean = secondTimeMs - firstTimeMs in 0..timeoutMs && dx * dx + dy * dy <= slopPx * slopPx
