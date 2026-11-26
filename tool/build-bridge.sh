#!/usr/bin/env bash
# Builds android/app/libs/tsbridge.aar from bridge/. Needs Go, gomobile, the Android SDK and NDK.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/bridge"
# Same feature trims sing-box uses for its mobile builds.
TAGS="ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,badlinkname,tfogo_checklinkname0"
mkdir -p "$root/android/app/libs"
gomobile bind \
  -target android/arm64,android/amd64 \
  -androidapi 26 \
  -javapkg top.bangwu.taildeck \
  -trimpath \
  -tags "$TAGS" \
  -ldflags "-s -w -checklinkname=0 -buildid=" \
  -o "$root/android/app/libs/tsbridge.aar" .
