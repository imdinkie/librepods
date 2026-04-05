#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/local-tools/logs"
mkdir -p "$OUT_DIR"

STAMP="$(date +%Y-%m-%d_%H-%M-%S)"
OUT_FILE="$OUT_DIR/librepods-log-$STAMP.txt"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 2
fi

adb wait-for-device >/dev/null 2>&1

{
  echo "# LibrePods log capture"
  echo "# started_at=$STAMP"
  echo
  echo "## host"
  echo "cwd=$PWD"
  echo "repo_root=$REPO_ROOT"
  echo
  echo "## device"
  adb shell getprop ro.product.manufacturer 2>/dev/null | sed 's/^/manufacturer=/'
  adb shell getprop ro.product.model 2>/dev/null | sed 's/^/model=/'
  adb shell getprop ro.build.fingerprint 2>/dev/null | sed 's/^/fingerprint=/'
  adb shell getprop ro.build.version.release 2>/dev/null | sed 's/^/android_release=/'
  adb shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/sdk=/'
  adb shell getprop ro.build.version.incremental 2>/dev/null | sed 's/^/incremental=/'
  echo
  echo "## librepods props"
  adb shell getprop | grep librepods || true
  echo
  echo "## bluetooth library paths"
  adb shell su -c 'for p in /apex/com.android.btservices/lib64/libbluetooth_jni.so /apex/com.android.bluetooth/lib64/libbluetooth_jni.so /system/apex/com.android.btservices/lib64/libbluetooth_jni.so /system/apex/com.android.bluetooth/lib64/libbluetooth_jni.so /system/lib64/libbluetooth_jni.so /system_ext/lib64/libbluetooth_jni.so /vendor/lib64/libbluetooth_jni.so /system/lib64/libbluetooth_qti.so /system_ext/lib64/libbluetooth_qti.so /vendor/lib64/libbluetooth_qti.so; do [ -e "$p" ] && echo "$p"; done' 2>&1 || true
  echo
  echo "## note"
  echo "logcat cleared immediately before streaming starts"
  echo
} > "$OUT_FILE"

adb logcat -c

echo "Writing logs to: $OUT_FILE"
echo "Reproduce the issue now. Press Ctrl+C when done."

cleanup() {
  {
    echo
    echo "## capture_end"
    date '+ended_at=%Y-%m-%d_%H-%M-%S'
  } >> "$OUT_FILE"
  echo
  echo "Saved: $OUT_FILE"
}

trap cleanup INT TERM

adb logcat -v threadtime 2>&1 | tee -a "$OUT_FILE"
