#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

print_kv() {
  printf '%-18s %s\n' "$1" "$2"
}

find_header() {
  if [[ -n "${CMUX_GHOSTTY_HEADER:-}" && -f "${CMUX_GHOSTTY_HEADER}" ]]; then
    echo "${CMUX_GHOSTTY_HEADER}"
    return
  fi

  local search_roots=(
    "$ROOT_DIR/linux/target/debug/build"
    "$ROOT_DIR/../linux/target/debug/build"
  )
  local root
  for root in "${search_roots[@]}"; do
    if [[ -d "$root" ]]; then
      local found
      found="$(find "$root" -type f -path '*ghostty-install/include/ghostty.h' -print -quit 2>/dev/null || true)"
      if [[ -n "$found" ]]; then
        echo "$found"
        return
      fi
    fi
  done

  local candidates=(
    "$ROOT_DIR/ghostty.h"
    "$ROOT_DIR/../ghostty.h"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
}

find_lib() {
  if [[ -n "${CMUX_GHOSTTY_LIB:-}" && -f "${CMUX_GHOSTTY_LIB}" ]]; then
    echo "${CMUX_GHOSTTY_LIB}"
    return
  fi

  local candidates=(
    "$ROOT_DIR/linux/target/debug/libghostty.so"
    "$ROOT_DIR/../linux/target/debug/libghostty.so"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
}

find_helper() {
  if [[ -n "${CMUX_GHOSTTY_GLAD_LIB:-}" && -f "${CMUX_GHOSTTY_GLAD_LIB}" ]]; then
    echo "${CMUX_GHOSTTY_GLAD_LIB}"
    return
  fi

  local candidates=(
    "$ROOT_DIR/build/native/libcmux_glad.so"
    "$ROOT_DIR/../linux/target/debug/libcmux_glad.so"
    "/tmp/cmux-ghostty-host/libcmux_glad.so"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
}

symbol_exists() {
  local lib="$1"
  local symbol="$2"

  if command -v nm >/dev/null 2>&1; then
    nm -D "$lib" 2>/dev/null | awk '{print $NF}' | grep -Fx "$symbol" >/dev/null
    return
  fi

  if command -v objdump >/dev/null 2>&1; then
    objdump -T "$lib" 2>/dev/null | awk '{print $NF}' | grep -Fx "$symbol" >/dev/null
    return
  fi

  return 2
}

header_path="$(find_header || true)"
lib_path="$(find_lib || true)"
helper_path="$(find_helper || true)"

has_linux_enum="no"
requires_gl_area="unknown"
if [[ -n "$header_path" ]]; then
  if grep -q 'GHOSTTY_PLATFORM_LINUX' "$header_path"; then
    has_linux_enum="yes"
  fi
  if grep -q 'ghostty_platform_linux_s' "$header_path" && grep -q 'gl_area' "$header_path"; then
    requires_gl_area="yes"
  else
    requires_gl_area="no"
  fi
fi

required_symbols=(
  ghostty_init
  ghostty_app_new
  ghostty_surface_new
  ghostty_surface_draw
  ghostty_surface_set_size
)
missing_symbols=()
if [[ -n "$lib_path" ]]; then
  for symbol in "${required_symbols[@]}"; do
    if ! symbol_exists "$lib_path" "$symbol"; then
      missing_symbols+=("$symbol")
    fi
  done
fi

runtime_load_direct="unknown"
runtime_load_direct_detail=""
runtime_load_with_helper="n/a"
runtime_load_with_helper_detail=""
if [[ -n "$lib_path" && -f "$lib_path" ]] && command -v python3 >/dev/null 2>&1; then
  set +e
  py_direct_out="$(python3 - "$lib_path" <<'PY'
import ctypes
import os
import sys

path = sys.argv[1]
try:
    ctypes.CDLL(path, mode=os.RTLD_NOW)
    print("ok")
except OSError as e:
    print(f"error:{e}")
    raise SystemExit(1)
PY
)"
  py_direct_code=$?
  set -e

  if [[ $py_direct_code -eq 0 ]]; then
    runtime_load_direct="ok"
  else
    runtime_load_direct="broken"
    runtime_load_direct_detail="${py_direct_out#error:}"
  fi
fi

if [[ -n "$lib_path" && -f "$lib_path" && -n "$helper_path" && -f "$helper_path" ]] && command -v python3 >/dev/null 2>&1; then
  set +e
  py_with_helper_out="$(python3 - "$lib_path" "$helper_path" <<'PY'
import ctypes
import os
import sys

path = sys.argv[1]
helper = sys.argv[2]
try:
    ctypes.CDLL(helper, mode=os.RTLD_NOW | os.RTLD_GLOBAL)
    ctypes.CDLL(path, mode=os.RTLD_NOW)
    print("ok")
except OSError as e:
    print(f"error:{e}")
    raise SystemExit(1)
PY
)"
  py_with_helper_code=$?
  set -e

  if [[ $py_with_helper_code -eq 0 ]]; then
    runtime_load_with_helper="ok"
  else
    runtime_load_with_helper="broken"
    runtime_load_with_helper_detail="${py_with_helper_out#error:}"
  fi
fi

echo "cmux ghostty embed doctor"
echo "========================="
print_kv "header" "${header_path:-not found}"
print_kv "library" "${lib_path:-not found}"
print_kv "helper" "${helper_path:-not found}"
print_kv "linux enum" "$has_linux_enum"
print_kv "needs gl_area" "$requires_gl_area"
print_kv "runtime direct" "$runtime_load_direct"
print_kv "runtime helper" "$runtime_load_with_helper"
if [[ ${#missing_symbols[@]} -eq 0 ]]; then
  print_kv "missing symbols" "none"
else
  print_kv "missing symbols" "$(IFS=,; echo "${missing_symbols[*]}")"
fi
if [[ -n "$runtime_load_direct_detail" ]]; then
  echo "runtime direct detail:"
  echo "$runtime_load_direct_detail"
fi
if [[ -n "$runtime_load_with_helper_detail" ]]; then
  echo "runtime helper detail:"
  echo "$runtime_load_with_helper_detail"
fi

echo
if [[ -z "$header_path" || -z "$lib_path" ]]; then
  echo "verdict: unavailable (missing header or library)"
  exit 1
fi

if [[ "$has_linux_enum" != "yes" ]]; then
  echo "verdict: unavailable (ghostty.h does not expose GHOSTTY_PLATFORM_LINUX)"
  exit 1
fi

if [[ ${#missing_symbols[@]} -gt 0 ]]; then
  echo "verdict: unavailable (required libghostty symbols missing)"
  exit 1
fi

effective_runtime="unknown"
if [[ "$runtime_load_direct" == "ok" ]]; then
  effective_runtime="ok"
elif [[ "$runtime_load_with_helper" == "ok" ]]; then
  effective_runtime="ok"
else
  effective_runtime="broken"
fi

if [[ "$effective_runtime" == "broken" ]]; then
  if [[ "$runtime_load_direct_detail" == *"gladLoaderLoadGLContext"* ]]; then
    echo "hint: build helper via ./scripts/build-ghostty-host-libs.sh"
  fi
  echo "verdict: unavailable (libghostty has unresolved runtime symbols/dependencies)"
  exit 1
fi

if [[ "$requires_gl_area" == "yes" ]]; then
  echo "verdict: partial (libghostty is present, but Linux ABI requires GtkGLArea bridge)"
  exit 2
fi

echo "verdict: ready (compose can attempt direct embedding)"
