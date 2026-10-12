#!/bin/bash
# house-menu: the home server from the phone's terminal (`house` with no arguments).
# Status, the brain switch, restarts of units that already failed (the stewardship rule: nothing
# that runs gets restarted from here, and never the model servers) and recent logs.
# Anything destructive stays in Pantheon's Ops screen.
set -u
PI=${HOUSE_PI:-raspberrypi}
pi() { ssh -o BatchMode=yes -o ConnectTimeout=6 "$PI" "$@"; }
# Model-serving units are never restarted from anywhere but the stack's own tools.
DENY='llama-swap|llama-server|llama-warm|llama-rewarm|halogen'

pick() {   # pick "header" options...: one line chosen, or nothing
    local header=$1; shift
    if command -v gum > /dev/null; then gum choose --header "$header" -- "$@"
    elif command -v fzf > /dev/null; then printf '%s\n' "$@" | fzf --prompt "$header > " --height 40% --reverse
    else
        local PS3="$header> " o
        select o in "$@"; do [ -n "$o" ] && { echo "$o"; return; }; done
    fi
}
sure() {
    if command -v gum > /dev/null; then gum confirm "$1"
    else read -r -p "$1 [y/N] " a; [[ $a == [yY]* ]]; fi
}
pause() { read -r -p $'\n(enter) ' _; }

status() {
    # The Pi formats it: house.json is there, and the phone may not have python.
    pi 'python3 - <<"EOF"
import json, pathlib, time
h = json.loads((pathlib.Path.home() / ".local/share/house/house.json").read_text())
he = h.get("health") or {}
ev = he.get("evo") or {}
c = h.get("todo_counts") or {}
i = h.get("inbox") or {}
s = i.get("side") or {}
g = lambda d, k, dflt="?": d.get(k, dflt) if d.get(k) is not None else dflt
print("pi     ", g(he, "overall"), " (checked", int(he.get("age_s") or 0) // 60, "min ago)")
for f in he.get("failing") or []:
    print("        failing:", f)
print("evo    ", g(ev, "mode"), "" if ev.get("ready") else "(not ready)", g(ev, "tps_24h"), "tok/s")
print("model  ", g(h, "chat_model"))
print("to-dos ", g(c, "open", 0), "open,", g(c, "overdue", 0), "overdue,", g(c, "today", 0), "today")
print("inbox  ", g(i, "unread"), "unread · legal", g(s, "legal", 0), "· class", g(s, "class", 0), "· build", g(s, "build", 0))
print("proton ", g(h, "proton_days"), "days left")
print("as of  ", time.strftime("%H:%M", time.localtime(h.get("generated") or 0)))
EOF'
    echo; pi '~/scripts/brain' 2> /dev/null | head -8
}

brain() {
    local lane; lane=$(pick "brain" halogen glm opus back) || return
    case $lane in halogen | glm | opus) sure "Switch the brain to $lane?" && pi "~/scripts/brain $lane" ;; esac
}

restart() {
    local units; units=$(pi 'systemctl --user --failed --no-legend --plain 2>/dev/null | cut -d" " -f1' | grep -Ev "$DENY" | grep -E '^[A-Za-z0-9@_.:-]+$')
    if [ -z "$units" ]; then echo "no failed units on the Pi"; return; fi
    local u; u=$(pick "failed units" $units back) || return
    [ "$u" = back ] && return
    sure "Restart $u?" || return
    pi "systemctl --user restart '$u'; sleep 2; systemctl --user is-active '$u'"
}

logs() {
    local what; what=$(pick "logs" "warnings, last hour" "a unit" back) || return
    case $what in
        warnings*) pi 'journalctl --user --since -1h -p warning --no-pager | tail -n 80' ;;
        "a unit")
            local u; u=$(pick unit $(pi 'systemctl --user list-units --type=service --state=running,failed --no-legend --plain | cut -d" " -f1' | grep -E '^[A-Za-z0-9@_.:-]+$') back) || return
            [ "$u" = back ] || pi "journalctl --user -u '$u' -n 80 --no-pager" ;;
    esac
}

while :; do
    clear
    echo "house · $PI"; echo
    case $(pick "what" status brain "restart a failed unit" logs quit) in
        status) status; pause ;;
        brain) brain; pause ;;
        restart*) restart; pause ;;
        logs) logs; pause ;;
        * ) exit 0 ;;
    esac
done
