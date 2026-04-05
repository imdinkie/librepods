#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
MODULE_DIR="$REPO_ROOT/local-tools/librepods-privapp-module"
DEST_DIR="$MODULE_DIR/system/priv-app/LibrePods"
DEST_APK="$DEST_DIR/LibrePods.apk"

if [[ ! -f "$APK" ]]; then
  echo "Error: APK not found: $APK. Run assembleDebug first." >&2
  exit 2
fi

if [[ ! -d "$MODULE_DIR" ]]; then
  echo "Error: module dir not found: $MODULE_DIR" >&2
  exit 2
fi

mkdir -p "$DEST_DIR"
cp -f "$APK" "$DEST_APK"

"$REPO_ROOT/local-tools/build-privapp-module-zip.sh"
