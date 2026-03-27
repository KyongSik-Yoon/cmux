#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GHOSTTY_DIR="$ROOT_DIR/../ghostty"
OUT_DIR="$ROOT_DIR/build/native"
OUT_LIB="$OUT_DIR/libcmux_glad.so"

if [[ ! -f "$GHOSTTY_DIR/vendor/glad/src/gl.c" ]]; then
  echo "ghostty glad source not found: $GHOSTTY_DIR/vendor/glad/src/gl.c" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

cc -fPIC -shared \
  -I"$GHOSTTY_DIR/vendor/glad/include" \
  "$GHOSTTY_DIR/vendor/glad/src/gl.c" \
  -o "$OUT_LIB"

echo "built: $OUT_LIB"
