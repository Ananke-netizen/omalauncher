package dev.omalauncher.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * Swipe down on home opens the notification shade. There is no third-party API for
 * the shade (StatusBarManager's expand call is system-only), so this is the usual
 * launcher route: a minimal accessibility service whose only job is the global
 * action. The user turns it on once in accessibility settings; until then
 * [open] reports false and the caller says so instead of failing silently.
 */
class ShadeService : AccessibilityService() {
    override fun onAccessibilityEvent(
        event: AccessibilityEvent?,
    ) {
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        connected = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        private var connected: AccessibilityService? = null

        /** True once the user has enabled the service in accessibility settings. */
        val ready get() = connected != null

        /** Pulls the shade down; false when the grant is missing (caller explains). */
        fun open(context: Context): Boolean = connected?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
    }
}
