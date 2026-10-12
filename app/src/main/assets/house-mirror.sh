#!/bin/bash
# house-mirror <dir>: the widgets, both ways with ~/omalauncher/widgets on the home server.
# `house sync` runs it with the Pi's widgets just pulled into <dir> (the same ssh as house.json).
#
# Three-way, against what both sides held after the last sync: a side that changed wins (edits,
# new files and deletions alike); when both changed the Pi wins and this side's copy is kept in
# .conflicts/. Back to the Pi go the widgets changed here and what owl-check leaves (.errors,
# .preview, .check), in one more ssh, only when something changed. The Pi commits widget changes
# to git there. Examples stay out of it: only top-level .owl files are mirrored.
set -u
PULLED=${1:?the pulled widgets dir}
[ -d "$PULLED" ] || { echo "house-mirror: $PULLED isn't a directory" >&2; exit 2; }
PI=${HOUSE_PI:-raspberrypi}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}
WIDGETS=$HOME/.config/omalauncher/widgets
BASE=$CACHE/house-mirror        # "sha256 name" of every widget as last synced, both sides equal
OUT=$CACHE/house-mirror.out     # a hash of what owl-check left, as last pushed
RW=${HOUSE_WIDGETS:-omalauncher/widgets}     # under the Pi's home; house checks it
[[ $RW =~ ^[A-Za-z0-9._/-]+$ && $RW != *..* ]] || { echo "house-mirror: bad HOUSE_WIDGETS" >&2; exit 2; }
REMOTE_W="~/$RW"

sha() { [ -f "$1" ] && sha256sum < "$1" | cut -c1-64; }
plain() { case $1 in */* | .* | '') return 1 ;; *.owl) return 0 ;; *) return 1 ;; esac; }

mkdir -p "$WIDGETS"
declare -A base=()
if [ -f "$BASE" ]; then while read -r s n; do base[$n]=$s; done < "$BASE"; fi
names=$( { cd "$WIDGETS" && ls -1 -- *.owl 2>/dev/null; cd "$PULLED" && ls -1 -- *.owl 2>/dev/null; } | sort -u)
up=() gone=() next=""
while IFS= read -r n; do
    plain "$n" || continue
    p=$(sha "$WIDGETS/$n"); r=$(sha "$PULLED/$n"); b=${base[$n]:-}
    if [ "$p" = "$r" ]; then
        :
    elif [ "$r" = "$b" ]; then                      # changed here
        if [ -n "$p" ]; then up+=("$n"); else gone+=("$n"); fi
        r=$p
    else                                             # changed on the Pi, or on both sides
        if [ -n "$p" ] && [ "$p" != "$b" ]; then mkdir -p "$WIDGETS/.conflicts"; cp -f "$WIDGETS/$n" "$WIDGETS/.conflicts/$n"; fi
        # Written beside it and renamed, so the launcher never loads half a file.
        if [ -n "$r" ]; then cp -f "$PULLED/$n" "$WIDGETS/.$n.tmp" && mv -f "$WIDGETS/.$n.tmp" "$WIDGETS/$n"; else rm -f -- "$WIDGETS/$n"; fi
    fi
    if [ -n "$r" ]; then next+="$r $n"$'\n'; fi
done <<< "$names"

out=$(cd "$WIDGETS" && find .errors .preview .check -type f 2>/dev/null | sort | xargs -r sha256sum | sha256sum | cut -c1-64)
if [ ${#up[@]} -gt 0 ] || [ ${#gone[@]} -gt 0 ] || [ "$out" != "$(cat "$OUT" 2>/dev/null)" ]; then
    printf '%s\n' "${gone[@]}" | grep -v '^$' > "$WIDGETS/.deleted"
    # The Pi side only deletes plain .owl names it is sent, and commits only .owl files.
    if ! (cd "$WIDGETS" && tar -cf - -- .deleted "${up[@]}" $(ls -d .errors .preview .check 2>/dev/null)) |
        ssh -o BatchMode=yes -o ConnectTimeout=6 "$PI" "mkdir -p $REMOTE_W && cd $REMOTE_W && tar -xf - &&
            while IFS= read -r n; do case \$n in */*|.*|'') ;; *.owl) rm -f -- \"\$n\" ;; esac; done < .deleted; rm -f .deleted
            [ -d .git ] || { git init -q && printf '.errors/\n.preview/\n.check/\n.conflicts/\n' > .gitignore; }
            git add -A -- '*.owl' .gitignore && { git diff --cached --quiet || git commit -qm 'widgets: from the phone'; }"; then
        rm -f "$WIDGETS/.deleted"
        echo "house-mirror: push to $PI failed" >&2
        exit 1                                       # the base stays as it was: the next sync tries again
    fi
    rm -f "$WIDGETS/.deleted"
    echo "$out" > "$OUT"
fi
printf '%s' "$next" > "$BASE"
