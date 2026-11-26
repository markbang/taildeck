#!/usr/bin/env bash
# Fails unless bridge/ is an exact copy of taildeck-ui native/bridge at the commit pinned in bridge/SOURCE,
# and that commit is on taildeck-ui main.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
. "$root/tool/bridge-source.sh"

source_file="$root/bridge/SOURCE"
[ -f "$source_file" ] || die "bridge/SOURCE is missing. Run tool/sync-bridge.sh <taildeck-ui commit>."
field() { sed -n "s/^$1=//p" "$source_file"; }
repo=$(field repo) path=$(field path) commit=$(field commit) tree=$(field tree)
[ "$repo" = "$BRIDGE_REPO" ] && [ "$path" = "$BRIDGE_PATH" ] ||
  die "bridge/SOURCE must pin $BRIDGE_REPO $BRIDGE_PATH, got '$repo' '$path'. Run tool/sync-bridge.sh <taildeck-ui commit>."

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fetch_bridge_source "$commit" "$tmp"
fail=0

if ! git -C "$tmp" merge-base --is-ancestor "$commit" origin/main; then
  err "bridge/SOURCE pins $commit, which is not on taildeck-ui main. Merge the bridge change in taildeck-ui, then run tool/sync-bridge.sh <commit on main>."
  fail=1
fi

actual=$(git -C "$tmp" rev-parse "$commit:$BRIDGE_PATH")
if [ "$tree" != "$actual" ]; then
  err "bridge/SOURCE says tree '$tree', but $BRIDGE_PATH at $commit is tree $actual. SOURCE is stale or was edited by hand; run tool/sync-bridge.sh $commit."
  fail=1
fi

diff=$(cd "$root" && diff -ru "$tmp/$BRIDGE_PATH" bridge | grep -vx "Only in bridge: SOURCE" || true)
if [ -n "$diff" ]; then
  echo "$diff"
  err "bridge/ differs from taildeck-ui $BRIDGE_PATH at $commit (diff above). bridge/ was edited by hand; change it in taildeck-ui, then run tool/sync-bridge.sh <commit on main>."
  fail=1
fi

[ "$fail" = 0 ] || exit 1
echo "bridge/ matches $BRIDGE_REPO $BRIDGE_PATH at $commit"
