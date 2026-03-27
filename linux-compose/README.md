# cmux Linux (Compose Multiplatform)

Linux port of cmux using JetBrains Compose Multiplatform.

## Features

- Terminal emulator with PTY support (via JNA `forkpty`)
- ANSI/VT100 escape sequence parser (SGR colors, cursor movement, alternate screen, scroll regions)
- Parser backend abstraction (`ansi` default, optional `ghostty` JNI load with safe fallback)
- Xterm mouse tracking support (DECSET 1000/1002/1003 + SGR 1006)
- Vertical tab sidebar with live title/cwd tracking
- Resizable split panes (horizontal/vertical)
- Notification panel for AI agent events
- Socket control API (TCP on localhost, protocol-compatible with cmux macOS)
- Canvas-based terminal rendering (Skia)
- Tokyo Night color theme

## Requirements

- JDK 21+
- Linux (x86_64)

## Build & Run

```bash
# Build launcher JAR + lib bundle
./gradlew packageUberJarForCurrentOS

# Run
java -jar build/compose/jars/cmux-linux-x64-0.1.0.jar

# Or run directly via Gradle
./gradlew run
```

## Desktop Smoke Test (X11/Wayland)

```bash
# Auto-detect X11/Wayland and run smoke
./scripts/linux-desktop-smoke.sh --mode auto

# Force backend mode when needed
./scripts/linux-desktop-smoke.sh --mode x11
./scripts/linux-desktop-smoke.sh --mode wayland
```

Detailed checklist:

- `docs/linux-desktop-smoke-checklist.md`

## Optional Ghostty Parser Backend (WIP)

`ansi` remains the default parser backend. You can request Ghostty backend loading:

```bash
export CMUX_TERMINAL_ENGINE=ghostty
export CMUX_GHOSTTY_JNI_LIB=/absolute/path/to/libcmuxghostty.so
java -jar build/compose/jars/cmux-linux-x64-0.1.0.jar
```

If the JNI library is unavailable or fails to load, cmux logs a warning and automatically falls back to `ansi`.

## Ghostty Full-Embed Status Check

Run the doctor script to validate whether local artifacts are ready for full Ghostty embedding:

```bash
./scripts/ghostty-embed-doctor.sh
```

If `runtime direct` fails with `gladLoaderLoadGLContext`, build host helper libs first:

```bash
./scripts/build-ghostty-host-libs.sh
```

To verify that Ghostty can actually create a Linux `GtkGLArea` surface on your desktop stack:

```bash
./scripts/ghostty-gtk-surface-probe.sh
```

If this probe succeeds but cmux still cannot full-embed, the remaining work is Compose↔GTK host-widget bridging, not Ghostty runtime/bootstrap.

The doctor checks:
- Linux platform API presence in `ghostty.h`
- required exported `ghostty_*` symbols
- runtime `dlopen` viability (detects unresolved symbols such as `gladLoaderLoadGLContext`)
- whether current Linux ABI still requires `GtkGLArea` (`gl_area`)

Environment overrides:

```bash
export CMUX_GHOSTTY_HEADER=/absolute/path/to/ghostty.h
export CMUX_GHOSTTY_LIB=/absolute/path/to/libghostty.so
export CMUX_GHOSTTY_GLAD_LIB=/absolute/path/to/libcmux_glad.so
```

At app startup, cmux also logs a one-line `ghostty-embed` status with detected library/header path and fallback reason.

## Package

```bash
# Create .deb / .rpm / AppImage
./gradlew packageDeb
./gradlew packageRpm
./gradlew createDistributable
```

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Shift+T` | New tab |
| `Ctrl+Shift+W` | Close tab |
| `Ctrl+Shift+E` | Split horizontal |
| `Ctrl+Shift+O` | Split vertical |
| `Ctrl+Shift+V` | Paste from desktop clipboard |
| `Shift+Insert` | Paste from X11 primary selection (fallback to clipboard) |
| `Alt+1-9` | Switch to tab N |
| `Ctrl+Tab` | Next tab |
| `Ctrl+Shift+Tab` | Previous tab |

## Socket API

```bash
# Find the port
PORT=$(cat /tmp/cmux-linux.sock)

# List tabs
echo "tab.list" | nc localhost $PORT

# Create new tab
echo "tab.new" | nc localhost $PORT

# Send input to active terminal
echo "input hello world" | nc localhost $PORT

# Available commands
echo "help" | nc localhost $PORT
```

## Architecture

```
src/main/kotlin/com/cmux/
├── Main.kt                    # Entry point, window setup
├── App.kt                     # App state, tab management
├── terminal/
│   ├── Pty.kt                 # Native PTY via JNA (forkpty/execvp)
│   ├── TerminalBuffer.kt      # Screen buffer with scrollback
│   ├── AnsiParser.kt          # ANSI escape sequence parser
│   ├── GhosttyParser.kt       # Ghostty JNI parser adapter (WIP, safe fallback)
│   ├── TerminalOutputParser.kt # Parser backend abstraction + factory
│   └── Terminal.kt            # High-level terminal session
├── ui/
│   ├── theme/Theme.kt         # Tokyo Night dark theme
│   ├── terminal/TerminalView.kt  # Canvas-based terminal renderer
│   ├── sidebar/Sidebar.kt     # Vertical tab sidebar
│   ├── splitpane/SplitPane.kt # Resizable split panes
│   └── notification/          # Notification panel
└── socket/
    └── SocketServer.kt        # Control socket server
```
