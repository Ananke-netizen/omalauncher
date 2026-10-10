#!/usr/bin/env bash
# Brings upstream's changes (SimonSchubert/omalauncher) into this fork on a new branch,
# ready for a pull request. Applies upstream's own diff since the commit recorded in
# .upstream, then records the new one there. Unlike a merge, this keeps working when
# upstream rewrites its history (it published it as one squashed commit) and when a sync
# pull request is squash-merged; the commit message lists upstream's commits it covers.
#
#   scripts/sync-upstream.sh --check     what upstream has that we don't
#   scripts/sync-upstream.sh             a branch upstream-sync/<sha> off origin/main with it
#
# UPSTREAM_URL and UPSTREAM_BRANCH override the source; BASE the branch to start from.
set -euo pipefail
cd "$(dirname "$0")/.."

URL="${UPSTREAM_URL:-https://github.com/SimonSchubert/omalauncher.git}"
BRANCH="${UPSTREAM_BRANCH:-main}"
BASE="${BASE:-origin/main}"

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$URL"
git fetch --quiet upstream "$BRANCH"
git fetch --quiet origin 2>/dev/null || true
theirs=$(git rev-parse "upstream/$BRANCH")
short=$(git rev-parse --short "$theirs")
from=$(git show "$BASE:.upstream" 2>/dev/null | tr -d '[:space:]')
[ -n "$from" ] || { echo "$BASE has no .upstream: put the upstream commit it is based on there" >&2; exit 1; }
git cat-file -e "$from^{commit}" 2>/dev/null || { echo "upstream no longer has $from, the last synced commit" >&2; exit 1; }

if [ "$from" = "$theirs" ]; then
  echo "up to date with upstream $short"
  exit 0
fi

# Upstream's commits since the last sync; after a rewrite they're no longer descendants of it.
log() {
  if git merge-base --is-ancestor "$from" "$theirs"; then
    git log --oneline --no-decorate "$from..$theirs"
  else
    echo "(upstream rewrote its history since $(git rev-parse --short "$from"))"
  fi
}

if [ "${1:-}" = --check ]; then
  log
  git diff --stat "$from" "$theirs" | tail -1
  exit 0
fi

[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "commit or set aside your changes first" >&2; exit 1; }
git switch --quiet --no-track -c "upstream-sync/$short" "$BASE"
if ! git diff --binary "$from" "$theirs" | git apply --3way --index; then
  echo "$theirs" > .upstream
  git add .upstream
  echo "conflicts: resolve them, git add the files, then git commit -m 'Sync upstream $short'" >&2
  exit 1
fi
echo "$theirs" > .upstream
git add .upstream
git commit --quiet -m "Sync upstream $short" -m "$(log)"
git log --oneline -1
echo "on upstream-sync/$short: run scripts/dev.sh test, then push it and open a pull request"
