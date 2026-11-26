#!/usr/bin/env bash
# Usage: tool/sync-bridge.sh [<taildeck-ui commit>]
# Replaces bridge/ with taildeck-ui native/bridge at that commit and pins it in bridge/SOURCE.
# Without a commit it uses the current taildeck-ui main.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
. "$root/tool/bridge-source.sh"

commit=${1:-}
if [ -z "$commit" ]; then
  commit=$(git ls-remote "$BRIDGE_REPO" refs/heads/main | cut -f1)
  [ -n "$commit" ] || die "cannot read main from $BRIDGE_REPO"
  echo "No commit given; using taildeck-ui main at $commit"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fetch_bridge_source "$commit" "$tmp"
git -C "$tmp" merge-base --is-ancestor "$commit" origin/main ||
  die "$commit is not on taildeck-ui main. Merge the bridge change there first, then sync to a commit on main."
tree=$(git -C "$tmp" rev-parse "$commit:$BRIDGE_PATH")

rm -rf "$root/bridge"
cp -R "$tmp/$BRIDGE_PATH" "$root/bridge"
cat > "$root/bridge/SOURCE" <<SRC
# Read-only copy. Change the bridge in taildeck-ui, then run tool/sync-bridge.sh <commit>.
repo=$BRIDGE_REPO
path=$BRIDGE_PATH
commit=$commit
tree=$tree
SRC
echo "bridge/ now matches $BRIDGE_REPO/tree/$commit/$BRIDGE_PATH (tree $tree)"
