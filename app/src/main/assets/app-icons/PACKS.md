# Icon packs

Each folder here is an icon pack: an icon for each app, in one style, that the
launcher uses for every app at once. They're listed in settings › icons on the
phone next to the icon packs installed as apps, and picked there or with
`icons.pack = "<folder>"` in `~/.config/omalauncher/style/style.toml`.

A pack shows in settings as soon as its folder has an icon or a `pack.toml`;
changes to the pack in use show within a second. Delete the folder to remove
the pack. Icons in `~/.config/omalauncher/icons` still come first: they're the
user's picks for single apps.

## A pack's folder

The folder's name is what `icons.pack` takes: letters, digits, `.`, `_` and `-`.

```
neon-grid/
├── pack.toml
├── org.mozilla.firefox.svg
├── com.android.contacts@PeopleActivity.svg
├── …
└── _backdrop.svg
```

Icon files are named the way `~/.config/omalauncher/icons/README.md` says, and
`~/.config/omalauncher/icons/apps.txt` lists every app in the drawer with the
name its file takes. The formats, sizes, `{{ accent }}` theme colors and the
monochrome style work the same as there: read that README too.

## pack.toml

Everything in it is optional.

```toml
name = "Neon Grid"
description = "Thin accent strokes on the theme's background"

# For apps the pack has no icon for, like ones installed later: their own icon,
# shrunk to scale, cut by mask where it's opaque, on image, under overlay.
[backdrop]
image = "_backdrop.svg"
mask = "_mask.svg"
overlay = "_overlay.svg"
scale = 0.6
```

`name` shows on the pack's card (the folder's name without it), and
`description` says what it looks like. A backdrop keeps apps without an icon
from standing out; begin layer files with `_` so they're easy to tell from icons.
What the launcher can't read in pack.toml shows in the style.toml banner on
home while the pack is in use.

## Making one well

- One style for every icon: the same canvas, stroke widths, corner radii and
  palette. Writing the SVGs with a small script from one template keeps them
  consistent and makes a change of style one edit.
- Use the theme's colors (`{{ background }}`, `{{ accent }}`, …) so the pack
  follows the theme; a pack in fixed colors is fine too.
- Check icons with `icon-check <file>` as you go: it prints problems and the
  paths of PNGs of the icon as the phone draws it.
- Apps get installed later: settings › icons offers to have an agent fill in the
  icons a pack is missing, from apps.txt.
