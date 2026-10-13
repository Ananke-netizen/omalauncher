#!/bin/bash
# omalauncher: checks app icon files the way the launcher draws them (see
# IconStore.kt). Waits until the launcher has drawn each file as it is on disk,
# then prints its problems, which apps it's for and where its pictures are.
set -u
dir="$HOME/.config/omalauncher/icons"

if [ $# -eq 0 ] || [ "$1" = -h ] || [ "$1" = --help ]; then
  cat >&2 <<EOF
usage: icon-check <icon.svg>...

Waits for the launcher to draw each icon file as saved, then prints its
problems, the apps it gives an icon to (from apps.txt), and the paths of two
PNGs: the icon on the theme's background, and as the monochrome style draws
it. Exits 1 if an icon can't be drawn, has problems or names no app.
EOF
  exit 2
fi

# The rest of the first "<key> ..." line of check file $1.
field() { sed -n "s/^$2 //p" "$1" 2>/dev/null | head -1; }

# Runs $1 every tenth of a second until it succeeds, at most $2 times.
wait_for() {
  local i
  for ((i = 0; i < $2; i++)); do
    eval "$1" && return 0
    sleep 0.1
  done
  return 1
}

status=0
for arg in "$@"; do
  f=$(realpath -- "$arg" 2>/dev/null)
  if [ -z "$f" ] || [ ! -f "$f" ]; then
    echo "$arg: no such file"
    status=1
    continue
  fi
  name=$(basename -- "$f")
  if [ "$(dirname -- "$f")" != "$(realpath -- "$dir")" ] || [[ $name == .* ]] || ! [[ $name =~ \.(svg|png|webp|jpg|jpeg)$ ]]; then
    echo "$arg: the launcher only reads .svg, .png, .webp and .jpg files directly in $dir"
    status=1
    continue
  fi
  hash=$(sha256sum <"$f" | cut -d' ' -f1)
  check="$dir/.check/$name.txt"

  # A file saved a moment ago takes the launcher about a second to pick up.
  if ! wait_for '[ "$(field "$check" sha256)" = "$hash" ]' 100; then
    echo "$name: the launcher hasn't drawn this version yet; run icon-check again"
    status=1
    continue
  fi

  # apps.txt: the name an app's icon file takes, a tab, its name. A package's
  # name covers its entries listed as <package>@<Activity> too.
  stem=${name%.*}
  apps=$(awk -F '\t' -v s="$stem" '!/^#/ && ($1 == s || index($1, s "@") == 1) { print $2 }' "$dir/apps.txt" 2>/dev/null | paste -sd ',' - | sed 's/,/, /g')

  if [ "$(field "$check" result)" != ok ]; then
    echo "$name: can't be drawn"
    status=1
  elif [ -n "$apps" ]; then
    echo "$name: drawn, for $apps"
  else
    echo "$name: drawn, but no app in apps.txt takes this name, so nothing shows it"
    status=1
  fi
  if grep -q '^problem ' "$check"; then
    sed -n 's/^problem /  problem: /p' "$check"
    status=1
  fi
  if [ "$(field "$check" result)" = ok ]; then
    echo "  preview: $dir/.preview/$name.png (on the theme's background); look at it"
    echo "  monochrome: $dir/.preview/$name.mono.png (the monochrome icon style); look at it too"
  fi
done
exit $status
