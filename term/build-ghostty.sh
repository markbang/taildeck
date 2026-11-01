#!/usr/bin/env bash
# Builds libghostty-vt for Android into android/app/src/main/cpp/ghostty.
# Needs Zig 0.17 and the Android NDK (ANDROID_NDK_HOME or ANDROID_HOME).
set -euo pipefail
GHOSTTY_REV=83b57ac
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/cpp/ghostty"
SRC="${GHOSTTY_SRC:-${TMPDIR:-/tmp}/ghostty-src}"
if [ ! -d "$SRC/.git" ]; then
  git clone https://github.com/ghostty-org/ghostty "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$GHOSTTY_REV" 2>/dev/null || true
git -C "$SRC" checkout -q "$GHOSTTY_REV"
rm -rf "$OUT" && mkdir -p "$OUT/lib"
for pair in arm64-v8a:aarch64-linux-android x86_64:x86_64-linux-android; do
  abi=${pair%%:*}; target=${pair#*:}
  (cd "$SRC" && zig build -Demit-lib-vt=true -Dtarget="$target" -Doptimize=ReleaseFast --prefix "$SRC/zig-out-$target")
  mkdir -p "$OUT/lib/$abi"
  cp "$SRC/zig-out-$target/lib/libghostty-vt.a" "$OUT/lib/$abi/"
done
cp -r "$SRC/include" "$OUT/include"
