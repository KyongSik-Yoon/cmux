#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINUX_DIR="$ROOT_DIR/../linux"

if ! command -v cc >/dev/null 2>&1; then
  echo "missing compiler: cc" >&2
  exit 1
fi

if ! command -v pkg-config >/dev/null 2>&1; then
  echo "missing command: pkg-config" >&2
  exit 1
fi

if ! pkg-config --exists gtk4; then
  echo "missing gtk4 development package (pkg-config gtk4)" >&2
  exit 1
fi

"$ROOT_DIR/scripts/build-ghostty-host-libs.sh" >/dev/null

HEADER_PATH="$(find "$LINUX_DIR/target/debug/build" -type f -path '*ghostty-install/include/ghostty.h' -print -quit 2>/dev/null || true)"
if [[ -z "$HEADER_PATH" ]]; then
  echo "ghostty.h not found under $LINUX_DIR/target/debug/build" >&2
  exit 1
fi

LIB_PATH="$LINUX_DIR/target/debug/libghostty.so"
if [[ ! -f "$LIB_PATH" ]]; then
  echo "libghostty.so not found: $LIB_PATH" >&2
  exit 1
fi

HELPER_LIB="$ROOT_DIR/build/native/libcmux_glad.so"
if [[ ! -f "$HELPER_LIB" ]]; then
  echo "helper lib not found after build: $HELPER_LIB" >&2
  exit 1
fi

RESOURCES_DIR="$(cd "$(dirname "$HEADER_PATH")/.." && pwd)/share/ghostty"
if [[ ! -d "$RESOURCES_DIR" ]]; then
  echo "ghostty resources dir not found: $RESOURCES_DIR" >&2
  exit 1
fi

PROBE_C="/tmp/cmux-ghostty-gtk-surface-probe.c"
PROBE_BIN="/tmp/cmux-ghostty-gtk-surface-probe"
PROBE_LOG="/tmp/cmux-ghostty-gtk-surface-probe.log"

cat > "$PROBE_C" <<'C'
#include <gtk/gtk.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#ifdef linux
#undef linux
#endif
#include "ghostty.h"

static void wakeup_cb(void* userdata) { (void)userdata; }
static bool action_cb(ghostty_app_t app, ghostty_target_s target, ghostty_action_s action) {
  (void)app;
  (void)target;
  (void)action;
  return false;
}
static void read_clipboard_cb(void* userdata, ghostty_clipboard_e clipboard, void* context) {
  (void)userdata;
  (void)clipboard;
  (void)context;
}
static void confirm_read_clipboard_cb(
    void* userdata,
    const char* text,
    void* context,
    ghostty_clipboard_request_e req) {
  (void)userdata;
  (void)text;
  (void)context;
  (void)req;
}
static void write_clipboard_cb(
    void* userdata,
    ghostty_clipboard_e clipboard,
    const ghostty_clipboard_content_s* content,
    size_t len,
    bool confirm) {
  (void)userdata;
  (void)clipboard;
  (void)content;
  (void)len;
  (void)confirm;
}
static void close_surface_cb(void* userdata, bool process_alive) {
  (void)userdata;
  (void)process_alive;
}

int main(void) {
  gtk_init();

  if (ghostty_init(0, NULL) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "ghostty_init failed\n");
    return 2;
  }

  ghostty_runtime_config_s runtime;
  memset(&runtime, 0, sizeof(runtime));
  runtime.supports_selection_clipboard = true;
  runtime.wakeup_cb = wakeup_cb;
  runtime.action_cb = action_cb;
  runtime.read_clipboard_cb = read_clipboard_cb;
  runtime.confirm_read_clipboard_cb = confirm_read_clipboard_cb;
  runtime.write_clipboard_cb = write_clipboard_cb;
  runtime.close_surface_cb = close_surface_cb;

  ghostty_config_t cfg = ghostty_config_new();
  if (!cfg) {
    fprintf(stderr, "ghostty_config_new failed\n");
    return 3;
  }

  ghostty_config_load_default_files(cfg);
  ghostty_config_load_recursive_files(cfg);
  ghostty_config_finalize(cfg);

  ghostty_app_t app = ghostty_app_new(&runtime, cfg);
  if (!app) {
    fprintf(stderr, "ghostty_app_new failed\n");
    ghostty_config_free(cfg);
    return 4;
  }

  GtkWidget* window = gtk_window_new();
  GtkWidget* gl_area = gtk_gl_area_new();
  gtk_gl_area_set_auto_render(GTK_GL_AREA(gl_area), TRUE);
  gtk_gl_area_set_has_depth_buffer(GTK_GL_AREA(gl_area), FALSE);
  gtk_gl_area_set_has_stencil_buffer(GTK_GL_AREA(gl_area), FALSE);
  gtk_gl_area_set_required_version(GTK_GL_AREA(gl_area), 4, 3);
  gtk_window_set_child(GTK_WINDOW(window), gl_area);
  gtk_widget_set_size_request(window, 800, 600);
  gtk_widget_set_visible(window, TRUE);

  for (int i = 0; i < 100; i++) {
    while (g_main_context_pending(NULL)) {
      g_main_context_iteration(NULL, FALSE);
    }
  }

  gtk_gl_area_make_current(GTK_GL_AREA(gl_area));

  ghostty_surface_config_s surface_cfg = ghostty_surface_config_new();
  surface_cfg.platform_tag = GHOSTTY_PLATFORM_LINUX;
  surface_cfg.platform.linux.gl_area = gl_area;
  surface_cfg.scale_factor = 1.0;
  surface_cfg.context = GHOSTTY_SURFACE_CONTEXT_SPLIT;

  ghostty_surface_t surface = ghostty_surface_new(app, &surface_cfg);
  printf("surface=%p\n", surface);

  if (surface) {
    ghostty_surface_free(surface);
  }

  ghostty_app_free(app);
  ghostty_config_free(cfg);
  return surface ? 0 : 5;
}
C

cc -o "$PROBE_BIN" \
  "$PROBE_C" \
  -I"$(dirname "$HEADER_PATH")" \
  $(pkg-config --cflags --libs gtk4) \
  -L"$LINUX_DIR/target/debug" -lghostty \
  -L"$ROOT_DIR/build/native" -lcmux_glad \
  -Wl,-rpath,"$LINUX_DIR/target/debug" \
  -Wl,-rpath,"$ROOT_DIR/build/native"

set +e
GHOSTTY_RESOURCES_DIR="$RESOURCES_DIR" "$PROBE_BIN" >"$PROBE_LOG" 2>&1
probe_code=$?
set -e

cat "$PROBE_LOG"
if [[ $probe_code -ne 0 ]]; then
  echo "probe failed with code=$probe_code" >&2
  exit "$probe_code"
fi

echo "probe passed: ghostty surface created"
