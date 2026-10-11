#!/bin/bash
# omalauncher-ask — $1 is a file holding the query typed (or dictated) into the
# Omalauncher bar. It lands in Hermes' canonical Bot Chat as a delivery from the
# phone (queued when she's busy), and Hermes' answer is pushed to Nick's
# Pantheon inbox. Runs on the Pi; called detached by the launcher's hermes
# wrapper over ssh. The launcher never waits for the answer.
#
# The token is read from ~/.hermes-pocket by inbox-push and never printed.
set -u

qf="${1:?usage: omalauncher-ask.sh <query-file>}"
[ -s "$qf" ] || { echo "omalauncher-ask: empty query" >&2; exit 1; }
query="$(cat "$qf")"

DM="$HOME/.hermes/hermes-agent/tools/bot_mode_dm.py"
HERMES_BIN="$HOME/.hermes/hermes-agent/.hermes/bin/hermes"
HERMES_HOME="$HOME/.hermes"
out="$(mktemp /tmp/omalauncher-out.XXXXXX)"

# The delivery prints one JSON object with the reply; it can take a while.
python3 "$DM" --run-delivery \
    --author '{"id":"bot:pixel","name":"Omalauncher","is_bot":true}' \
    query-file "$qf" \
    --profile-home "$HERMES_HOME" \
    "$HERMES_BIN" -p default chat --in '~' -c 'Bot Chat' --create-if-missing -Q \
    > "$out" 2>/dev/null

reply="$(python3 - "$out" <<'PY'
import json, sys
try:
    d = json.loads(open(sys.argv[1]).read().strip().splitlines()[-1])
except Exception:
    d = {}
print(d.get("reply") or d.get("error") or "")
PY
)"
[ -n "$reply" ] || reply="(no reply from Hermes — she may be busy; try again in a moment)"

title="Omalauncher: $(printf '%s' "$query" | cut -c1-40)"
# Truncate: inbox notes show on the lock screen.
~/scripts/inbox-push "$(printf '%s' "$reply" | cut -c1-900)" --bot hermes --title "$title" >/dev/null 2>&1 \
    || echo "omalauncher-ask: inbox push failed" >&2
rm -f "$out"
