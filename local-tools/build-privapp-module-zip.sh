#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/local-tools/librepods-privapp-module"
OUT_ZIP="$REPO_ROOT/local-tools/LibrePods-privapp-module.zip"

if [[ ! -d "$MODULE_DIR" ]]; then
  echo "Error: module dir not found: $MODULE_DIR" >&2
  exit 2
fi

if [[ ! -f "$MODULE_DIR/module.prop" ]]; then
  echo "Error: module.prop not found in $MODULE_DIR (not a Magisk module layout?)" >&2
  exit 2
fi

rm -f "$OUT_ZIP"

( cd "$MODULE_DIR" && zip -r "$OUT_ZIP" . \
    -x "*.DS_Store" "*__MACOSX*" "*DEBIAN*" "._*" ".gitignore" )

echo "Wrote: $OUT_ZIP"
