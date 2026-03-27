#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINUX_DIR="$ROOT_DIR/../linux"

CWD_ARG="${PWD}"
TITLE_ARG="cmux Ghostty"
COMMAND_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cwd)
      CWD_ARG="${2:-}"
      shift 2
      ;;
    --title)
      TITLE_ARG="${2:-}"
      shift 2
      ;;
    --command)
      COMMAND_ARG="${2:-}"
      shift 2
      ;;
    -h|--help)
      cat <<'EOF'
Usage: scripts/ghostty-gtk-window-host.sh [options]

Options:
  --cwd <path>        Initial working directory (default: current directory)
  --title <title>     Host window title (default: cmux Ghostty)
  --command <cmd>     Optional command for initial surface command
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

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

HOST_C="/tmp/cmux-ghostty-gtk-window-host.c"
HOST_BIN="/tmp/cmux-ghostty-gtk-window-host"

cat > "$HOST_C" <<'C'
#include <gtk/gtk.h>
#include <stdbool.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#ifdef linux
#undef linux
#endif
#include "ghostty.h"

static volatile sig_atomic_t keep_running = 1;

static void handle_term(int sig) {
  (void)sig;
  keep_running = 0;
}

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

int main(int argc, char** argv) {
  const char* cwd = argc > 1 ? argv[1] : NULL;
  const char* title = argc > 2 ? argv[2] : "cmux Ghostty";
  const char* command = argc > 3 ? argv[3] : NULL;
  if (command && command[0] == '\0') {
    command = NULL;
  }

  signal(SIGTERM, handle_term);
  signal(SIGINT, handle_term);

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
  gtk_window_set_title(GTK_WINDOW(window), title);
  gtk_widget_set_size_request(window, 980, 640);

  GtkWidget* gl_area = gtk_gl_area_new();
  gtk_gl_area_set_auto_render(GTK_GL_AREA(gl_area), TRUE);
  gtk_gl_area_set_has_depth_buffer(GTK_GL_AREA(gl_area), FALSE);
  gtk_gl_area_set_has_stencil_buffer(GTK_GL_AREA(gl_area), FALSE);
  gtk_gl_area_set_required_version(GTK_GL_AREA(gl_area), 4, 3);
  gtk_window_set_child(GTK_WINDOW(window), gl_area);
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
  if (cwd && cwd[0] != '\0') {
    surface_cfg.working_directory = cwd;
  }
  if (command && command[0] != '\0') {
    surface_cfg.command = command;
  }

  ghostty_surface_t surface = ghostty_surface_new(app, &surface_cfg);
  if (!surface) {
    fprintf(stderr, "ghostty_surface_new failed\n");
    ghostty_app_free(app);
    ghostty_config_free(cfg);
    return 5;
  }

  printf("READY\n");
  fflush(stdout);

  while (keep_running) {
    while (g_main_context_pending(NULL)) {
      g_main_context_iteration(NULL, FALSE);
    }
    ghostty_app_tick(app);
    g_usleep(16 * 1000);
  }

  ghostty_surface_free(surface);
  ghostty_app_free(app);
  ghostty_config_free(cfg);
  return 0;
}
C

cc -o "$HOST_BIN" \
  "$HOST_C" \
  -I"$(dirname "$HEADER_PATH")" \
  $(pkg-config --cflags --libs gtk4) \
  -L"$LINUX_DIR/target/debug" -lghostty \
  -L"$ROOT_DIR/build/native" -lcmux_glad \
  -Wl,-rpath,"$LINUX_DIR/target/debug" \
  -Wl,-rpath,"$ROOT_DIR/build/native"

exec env GHOSTTY_RESOURCES_DIR="$RESOURCES_DIR" "$HOST_BIN" "$CWD_ARG" "$TITLE_ARG" "$COMMAND_ARG"
