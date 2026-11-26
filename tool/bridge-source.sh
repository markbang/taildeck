# Shared by tool/sync-bridge.sh and tool/check-bridge.sh. Source it, do not run it.
BRIDGE_REPO=https://github.com/markbang/taildeck-ui
BRIDGE_PATH=native/bridge

err() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::$*"; else echo "error: $*"; fi >&2
}

die() {
  err "$@"
  exit 1
}

# fetch_bridge_source <commit> <dir>: fetches taildeck-ui main history without file contents,
# plus <commit>, then checks out only $BRIDGE_PATH at <commit> into <dir>.
fetch_bridge_source() {
  local commit=$1 dir=$2
  [[ $commit =~ ^[0-9a-f]{40}$ ]] || die "need a full 40-character taildeck-ui commit SHA, got '$commit'"
  git init -q "$dir"
  git -C "$dir" remote add origin "$BRIDGE_REPO"
  git -C "$dir" fetch -q --filter=blob:none origin +refs/heads/main:refs/remotes/origin/main "$commit" ||
    die "cannot fetch commit $commit from $BRIDGE_REPO; check that it exists there"
  git -C "$dir" sparse-checkout set --no-cone "/$BRIDGE_PATH/"
  git -C "$dir" -c advice.detachedHead=false checkout -q --detach "$commit"
  [ -d "$dir/$BRIDGE_PATH" ] || die "taildeck-ui $commit has no $BRIDGE_PATH"
}
