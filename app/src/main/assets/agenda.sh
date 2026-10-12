#!/bin/bash
# agenda: the Radicale calendars in khal, read-only, from the home server over ssh.
#
#   agenda           pull, then khal's interactive view (ikhal)
#   agenda list      pull, then the next 14 days as text
#   agenda pull      only pull
#
# The calendars come as Radicale keeps them (one .ics per item, the vdir layout khal reads), with
# one ssh as your own user, so no CalDAV password is stored on the phone. Every calendar is marked
# readonly for khal; add and change events in Pantheon or on the desktop.
set -u
PI=${HOUSE_PI:-raspberrypi}
STORE='~/.var/lib/radicale/collection-root/erebus'
DATA=${XDG_DATA_HOME:-$HOME/.local/share}/agenda
CONF=${XDG_CONFIG_HOME:-$HOME/.config}/khal/config

pull() {
    if ! command -v khal > /dev/null; then
        # The drawer's Agenda opens in a terminal, so it can ask; a script just gets the hint.
        [ -t 0 ] && read -r -p "agenda needs khal. Install it now? [Y/n] " a && [[ ! $a == [nN]* ]] && pacman -S --needed --noconfirm khal
        command -v khal > /dev/null || { echo "agenda needs khal: pacman -S --needed khal" >&2; exit 1; }
    fi
    mkdir -p "$DATA"
    local tmp; tmp=$(mktemp -d "$DATA/.pull.XXXXXX")
    if ! ssh -o BatchMode=yes -o ConnectTimeout=6 "$PI" "cd $STORE && tar -cf - --exclude=.Radicale.cache --exclude=.Radicale.lock ." | tar -xf - -C "$tmp"; then
        rm -rf "$tmp"; echo "agenda: couldn't reach $PI, showing the last pull" >&2; return
    fi
    rm -rf "$DATA/cal.old"; [ -d "$DATA/cal" ] && mv "$DATA/cal" "$DATA/cal.old"
    mv "$tmp" "$DATA/cal" && rm -rf "$DATA/cal.old"
    # khal's config, one readonly calendar per collection that holds events, named as Radicale shows it.
    mkdir -p "$(dirname "$CONF")"
    python3 - "$DATA/cal" "$CONF" <<'EOF'
import json, pathlib, sys
root, conf = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
out = ["# written by agenda on every pull; edits here are replaced", "[calendars]"]
for d in sorted(p for p in root.iterdir() if p.is_dir()):
    props = {}
    try: props = json.loads((d / ".Radicale.props").read_text())
    except Exception: continue          # not a collection Radicale serves
    if props.get("tag") != "VCALENDAR": continue
    if "VEVENT" not in props.get("C:supported-calendar-component-set", "VEVENT"): continue
    name = (props.get("D:displayname") or d.name[:8]).replace("[", "(").replace("]", ")")
    out += [f"[[{name}]]", f"path = {d}", "type = calendar", "readonly = True"]
    if props.get("ICAL:calendar-color"): out.append(f"color = '{props['ICAL:calendar-color'][:7]}'")
out += ["[locale]", "timeformat = %H:%M", "dateformat = %a %d %b", "longdateformat = %a %d %b %Y",
        "datetimeformat = %a %d %b %H:%M", "longdatetimeformat = %a %d %b %Y %H:%M",
        "[default]", "highlight_event_days = True"]
conf.write_text("\n".join(out) + "\n")
EOF
}

case ${1:-} in
    pull) pull ;;
    list) pull; khal list today 14d ;;
    '') pull; exec ikhal ;;
    *) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
