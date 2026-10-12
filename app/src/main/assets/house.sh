#!/bin/bash
# house: the home server's data and actions for this launcher's widgets, over ssh.
# Installed by the launcher (overwritten on update); the Pi side is ~/scripts/house-* there.
#
#   house sync               pull house.json (and the Pi's widgets) in one ssh into ~/.cache/house.json
#   house done|undo <uid>    tick a Radicale to-do (or untick it)
#   house add <text> [date]  a new to-do, due YYYY-MM-DD if given
#   house capture <text>     a line in the vault inbox
#   house brain [lane]       the brain status, or switch: halogen, glm or opus
#   house                    the menu
#
# User text never goes through a shell on the Pi: it travels on stdin and is read there with "$(cat)".
set -u
PI=${HOUSE_PI:-raspberrypi}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}
HERE=$(dirname "$0")

pi() { ssh -o BatchMode=yes -o ConnectTimeout=6 -o ServerAliveInterval=5 "$PI" "$@"; }
die() { echo "house: $*" >&2; exit 1; }

sync() {
    mkdir -p "$CACHE"
    tmp=$(mktemp -d "$CACHE/.house.XXXXXX") || die "no temp dir"
    trap 'rm -rf "$tmp"' EXIT
    # One ssh: the snapshot, then the Pi's top-level widgets for house-mirror.
    pi 'mkdir -p ~/omalauncher/widgets && cd ~/omalauncher/widgets && find . -maxdepth 1 -name "*.owl" -type f -printf "%f\n" |
        tar -cf - -C ~/.local/share/house house.json -C ~/omalauncher/widgets -T -' < /dev/null | tar -xf - -C "$tmp" 2> /dev/null
    local got=("${PIPESTATUS[@]}")
    [ -s "$tmp/house.json" ] || die "couldn't reach $PI"
    chmod 600 "$tmp/house.json"
    mv -f "$tmp/house.json" "$CACHE/house.json"       # a rename: readers never see half a file
    # Only a complete pull may be mirrored: a cut one would read as widgets deleted on the Pi.
    if [ "${got[0]}" = 0 ] && [ "${got[1]}" = 0 ] && [ -x "$HERE/house-mirror" ]; then
        "$HERE/house-mirror" "$tmp" || echo "house: widgets not mirrored" >&2
    fi
    echo ok
}

uid() { [[ ${1:-} =~ ^[A-Za-z0-9@._:+-]{1,200}$ ]] || die "not a to-do uid"; }

case ${1:-menu} in
    sync) sync ;;
    done | undo)
        uid "${2:-}"
        pi "~/scripts/house-todo $1 $2" < /dev/null && sync > /dev/null ;;
    add)
        [ -n "${2:-}" ] || die "nothing to add"
        due=${3:-}
        [ -z "$due" ] || [[ $due =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "the date is YYYY-MM-DD"
        printf '%s' "$2" | pi "~/scripts/house-todo add \"\$(cat)\" $due" && sync > /dev/null ;;
    capture)
        [ -n "${2:-}" ] || die "nothing to capture"
        printf '%s' "$2" | pi '~/scripts/house-capture "$(cat)"' ;;
    brain)
        case ${2:-} in
            '') pi '~/scripts/brain' < /dev/null ;;
            halogen | glm | opus) pi "~/scripts/brain $2" < /dev/null ;;
            *) die "brain takes halogen, glm or opus" ;;
        esac ;;
    menu)
        [ -x "$HERE/house-menu" ] || die "the menu comes with a newer launcher"
        exec "$HERE/house-menu" ;;
    -h | --help | help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) die "unknown: $1 (house help)" ;;
esac
