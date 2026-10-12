package dev.omalauncher.widgets

import org.json.JSONObject

/**
 * The profile a pinned app was saved in, as in [Shortcut.profile]; null for the launcher's own.
 * This fork's builds before upstream's work profiles saved it as "user" (ANAL-142), with the same
 * serial and the same key, so those pinned work apps still open in their profile; the next save
 * writes "profile".
 */
internal fun savedProfile(o: JSONObject): Long? =
    when {
        !o.isNull("profile") -> o.getLong("profile")
        !o.isNull("user") -> o.getLong("user")
        else -> null
    }
