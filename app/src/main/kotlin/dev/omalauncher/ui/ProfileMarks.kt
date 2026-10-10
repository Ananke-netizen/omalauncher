package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.AppProfile
import dev.omalauncher.profileLabel

/** What screen readers call an app's icon: "Chrome · work" for a profile's copy, just [label] for a personal app. */
internal fun describe(
    label: String,
    profile: AppProfile?,
) = profileLabel(label, profile?.name, profile?.paused == true)

/** How much of a paused profile's app icon shows: listed, but dimmed, as stock launchers do. */
private const val PAUSED_ALPHA = 0.4f

/**
 * An app icon of [size] with its profile's marks: dimmed while the profile is paused, and
 * a themed badge in the corner unless [badged], i.e. the icon already wears the system's
 * (color icons do; silhouettes and initials don't). Personal apps draw [icon] alone.
 */
@Composable
internal fun ProfileMarks(
    profile: AppProfile?,
    badged: Boolean,
    size: Dp,
    icon: @Composable () -> Unit,
) {
    if (profile == null) return icon()
    Box(Modifier.alpha(if (profile.paused) PAUSED_ALPHA else 1f)) {
        icon()
        if (!badged) ProfileBadge(profile, size * 0.38f, Modifier.align(Alignment.BottomEnd))
    }
}

/** The themed badge: a briefcase for work, the profile's initial for others (a clone). */
@Composable
private fun ProfileBadge(
    profile: AppProfile,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(LauncherStyle.shape(size / 2)).background(Palette.accent), contentAlignment = Alignment.Center) {
        if (profile.isWork) {
            Image(Icons.Work, null, Modifier.size(size * 0.62f), colorFilter = ColorFilter.tint(Palette.background))
        } else {
            BasicText(profile.name.take(1), style = Mono.copy(color = Palette.background, fontSize = (size.value * 0.6f).sp))
        }
    }
}
