# Linux Desktop Smoke Checklist

Use this checklist for real desktop validation on Linux.

## Preconditions

- X11: `DISPLAY` is set
- Wayland: `WAYLAND_DISPLAY` is set
- `java`, `timeout`, `nc` are installed
- Run from `linux-compose` directory

## Standard smoke run

```bash
./scripts/linux-desktop-smoke.sh --mode auto
```

## Explicit backend runs

```bash
# X11
./scripts/linux-desktop-smoke.sh --mode x11

# Wayland
./scripts/linux-desktop-smoke.sh --mode wayland
```

## Manual UI checklist

1. App launches without stacktrace.
2. Initial tab accepts typing and Enter executes commands.
3. Create 5+ tabs quickly via sidebar `+` button.
4. Switch tabs and verify active tab receives keyboard input.
5. Check sidebar path updates (`~` -> real cwd) within ~1s after `cd`.
6. Split pane open/close and verify both panes remain responsive.
7. Confirm socket commands still respond:
```bash
PORT=$(cat /tmp/cmux-linux.sock)
echo "ping" | nc 127.0.0.1 "$PORT"
echo "tab.list" | nc 127.0.0.1 "$PORT"
```

## Failure artifact collection

When smoke fails, attach:

1. First stacktrace only
2. `/tmp/cmux-linux-smoke.log`
3. Output of:
```bash
echo "DISPLAY=$DISPLAY WAYLAND_DISPLAY=$WAYLAND_DISPLAY"
sha256sum build/compose/jars/cmux-linux-x64-0.1.0.jar
```
