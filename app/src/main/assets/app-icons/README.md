# App icons

Files in this folder replace Android apps' icons on the phone: on the home
screen, in the drawer, in folders and in widgets. A file shows within a second
of saving; delete it and the app gets its icon back. Settings › icons on the
phone saves its picks here too, as PNG files.

Where an app's icon comes from, first match wins:

1. a file here named after the app
2. the icon pack in `~/.config/omalauncher/style/style.toml` (`icons.pack`)
3. the app's own icon (on the icon pack's backdrop when the pack has one and
   `icons.pack-backdrop` is true)

## Names

`apps.txt` lists the apps in the drawer, one per line, with tabs between: the
file name its icon takes (add the extension), the app's name, and where its
icon comes from now (`file`, `pack`, `backdrop` or `own`). The launcher keeps it
current.

- `<package>.svg` replaces the icon of every entry that package has in the
  drawer: `org.mozilla.firefox.svg`
- `<package>@<Activity>.svg` replaces one, for packages with more than one
  entry; apps.txt names those this way: `com.android.contacts@PeopleActivity.svg`

When both exist, the second wins for its entry. A work profile's copy of an app
uses the same file, with the briefcase added.

## Formats

`.svg`, `.png`, `.webp` or `.jpg`, with a lowercase extension; when a name has
more than one, that's also the order that wins. Up to 4 MB.

SVG is best: sharp at any size, and it can follow the theme. Draw on a square
canvas, `viewBox="0 0 108 108"` for example; the launcher draws icons about
52 dp across, and doesn't crop them to a shape, so what's transparent shows the
wallpaper or tile behind it. Keep strokes 3 units or more at that viewBox so
they survive the small size. Bitmaps should be square, 256 px or more; others
are fit inside the square.

## Theme colors

In an SVG, `{{ name }}` is replaced by the current Omarchy theme's color, as
`#rrggbb`, when the icon is drawn, and again whenever the theme changes:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  <rect x="6" y="6" width="96" height="96" rx="20" fill="{{ background }}" stroke="{{ accent }}" stroke-width="4"/>
  <path d="M34 54 L50 70 L76 40" fill="none" stroke="{{ foreground }}" stroke-width="8" stroke-linecap="round"/>
</svg>
```

The names: `background`, `foreground`, `accent`, `cursor`, `muted`,
`selection_background`, `selection_foreground`, `red`, `green`, `yellow`,
`blue`, `magenta`, `cyan` (and `bright_` of each), `color0` to `color15`,
`lighter_background`, `dark_background`, `light_foreground`,
`dark_foreground`. Also `{{ accent_strip }}` (without the `#`), `{{ accent_rgb }}`
(`r,g,b`) and `{{ mix accent background 30% }}` (a blend). The current theme's
colors.toml is usually in `~/.local/state/omarchy/current/theme/`.

## Monochrome

With `icons.monochrome = true` in style.toml, the launcher draws every icon as a
silhouette in the theme's accent color: bright parts opaque, dark parts clear,
and the shades between as they are. An icon meant to work both ways keeps its
detail in light strokes on a dark or clear ground.

## Checking

The phone draws an icon with its own SVG renderer. `icon-check <file>` waits
until the launcher has drawn the file as saved, then prints its problems and the
paths of two PNGs: the icon on the theme's background, and its monochrome
silhouette. Look at both.
