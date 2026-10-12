#!/usr/bin/env bash
# Renders frame.html's slides into feed-N.png, 1080x1920 each, for the README.
# Needs Chrome or Chromium; CHROME picks the binary.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHROME="${CHROME:-$(command -v chromium || command -v google-chrome || echo "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")}"
COUNT=3

for i in $(seq 1 $COUNT); do
  file="$HERE/feed-$i.png"
  "$CHROME" --headless=new --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1080,1920 --virtual-time-budget=8000 \
    --screenshot="$file" "file://$HERE/frame.html?s=$i" 2>/dev/null
  echo "$file"
done
