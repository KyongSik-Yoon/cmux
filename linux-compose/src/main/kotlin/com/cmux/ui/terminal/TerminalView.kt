package com.cmux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cmux.terminal.Cell
import com.cmux.terminal.CellAttributes
import com.cmux.terminal.Terminal
import com.cmux.terminal.TerminalParserFactory
import com.cmux.terminal.TerminalSnapshot
import com.cmux.ghostty.GhosttyExternalWindowHost
import com.cmux.platform.LinuxClipboard
import com.cmux.ui.theme.CmuxColors
import com.cmux.ui.theme.CmuxTypography
import kotlinx.coroutines.delay
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher

/**
 * Terminal renderer using AnnotatedString rows.
 * Uses AWT KeyEventDispatcher for reliable text input capture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalView(
    terminal: Terminal,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    onFocused: () -> Unit = {}
) {
    val ghosttyExternalModeEnabled = remember {
        System.getenv("CMUX_GHOSTTY_UI_MODE")
            ?.trim()
            ?.lowercase() == "external-window"
    }
    val ghosttyRequested = remember { TerminalParserFactory.isGhosttyRequested() }
    if (ghosttyRequested && ghosttyExternalModeEnabled) {
        GhosttyExternalWindowTerminalView(terminal = terminal, modifier = modifier)
        return
    }

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val pressedMouseButtons = remember { mutableSetOf<Int>() }

    // Subscribe to terminal updates
    val version by terminal.version.collectAsState()

    // Measure cell size for calculating rows/cols
    val textMeasurer = rememberTextMeasurer()
    val cellSize = remember(textMeasurer) {
        val result = textMeasurer.measure(
            text = AnnotatedString("M"),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = CmuxTypography.terminalFontSize,
            )
        )
        IntSize(result.size.width, result.size.height)
    }

    // Track container size and resize terminal
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(containerSize, cellSize) {
        if (containerSize.width > 0 && containerSize.height > 0 && cellSize.width > 0 && cellSize.height > 0) {
            val newCols = containerSize.width / cellSize.width
            val newRows = containerSize.height / cellSize.height
            if (newCols > 0 && newRows > 0) {
                terminal.resize(newCols, newRows)
            }
        }
    }

    // AWT KeyEventDispatcher for capturing KEY_TYPED events (regular character input)
    // KEY_PRESSED doesn't have keyChar for regular characters in AWT
    DisposableEffect(terminal, isFocused) {
        val dispatcher = KeyEventDispatcher { awtEvent ->
            if (!isFocused) return@KeyEventDispatcher false

            when (awtEvent.id) {
                java.awt.event.KeyEvent.KEY_TYPED -> {
                    val ch = awtEvent.keyChar
                    if (ch == java.awt.event.KeyEvent.CHAR_UNDEFINED) return@KeyEventDispatcher false

                    // Fallback path: if Compose KEY_DOWN handling is missed,
                    // still deliver Enter/Backspace so commands can execute.
                    val input = when {
                        ch == '\n' || ch == '\r' -> "\r"
                        ch == '\b' -> "\u007F"
                        ch.code >= 0x20 -> if (awtEvent.isAltDown) "\u001B${ch}" else ch.toString()
                        else -> null
                    }

                    if (input != null) {
                        terminal.write(input)
                        awtEvent.consume()
                        true
                    } else false
                }
                else -> false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    // Cursor blink
    var cursorBlink by remember { mutableStateOf(true) }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            while (true) {
                delay(530)
                cursorBlink = !cursorBlink
            }
        } else {
            cursorBlink = true
        }
    }

    // Get terminal snapshot for rendering
    val snapshot = remember(version) { terminal.getSnapshot() }

    Column(
        modifier = modifier
            .background(CmuxColors.terminalBg)
            .onSizeChanged { containerSize = it }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .onPointerEvent(PointerEventType.Press) {
                focusRequester.requestFocus()
                dispatchMouseEvent(
                    terminal = terminal,
                    event = it,
                    eventType = PointerEventType.Press,
                    cellSize = cellSize,
                    pressedButtons = pressedMouseButtons
                )
            }
            .onPointerEvent(PointerEventType.Release) {
                dispatchMouseEvent(
                    terminal = terminal,
                    event = it,
                    eventType = PointerEventType.Release,
                    cellSize = cellSize,
                    pressedButtons = pressedMouseButtons
                )
            }
            .onPointerEvent(PointerEventType.Move) {
                dispatchMouseEvent(
                    terminal = terminal,
                    event = it,
                    eventType = PointerEventType.Move,
                    cellSize = cellSize,
                    pressedButtons = pressedMouseButtons
                )
            }
            .onPointerEvent(PointerEventType.Scroll) {
                dispatchMouseEvent(
                    terminal = terminal,
                    event = it,
                    eventType = PointerEventType.Scroll,
                    cellSize = cellSize,
                    pressedButtons = pressedMouseButtons
                )
            }
            .onPreviewKeyEvent { event ->
                // Handle special keys and control sequences via Compose key events
                if (event.type == KeyEventType.KeyDown) {
                    if (handleClipboardPasteShortcut(event, terminal)) {
                        return@onPreviewKeyEvent true
                    }
                    val input = keyEventToSpecialInput(event)
                    if (input != null) {
                        terminal.write(input)
                        true
                    } else false
                } else false
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        for (row in 0 until snapshot.rows) {
            if (row >= snapshot.lines.size) break
            val line = snapshot.lines[row]
            val showCursor = snapshot.cursorVisible && snapshot.cursorRow == row && (cursorBlink || !isFocused)

            TerminalRow(
                cells = line,
                cursorCol = if (showCursor) snapshot.cursorCol else -1,
                isFocused = isFocused
            )
        }
    }

    // Auto-focus on first composition (only when autoFocus is true)
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun GhosttyExternalWindowTerminalView(
    terminal: Terminal,
    modifier: Modifier = Modifier
) {
    DisposableEffect(terminal.id) {
        GhosttyExternalWindowHost.ensureStarted(terminal)
        onDispose { GhosttyExternalWindowHost.stop(terminal.id) }
    }

    val cwd by terminal.cwd.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CmuxColors.terminalBg)
            .padding(16.dp)
    ) {
        BasicText(
            text = "Ghostty native host window active\nterminal=${terminal.id}\ncwd=$cwd",
            style = TextStyle(
                color = CmuxColors.onBackground,
                fontFamily = FontFamily.Monospace,
                fontSize = CmuxTypography.terminalFontSize
            )
        )
    }
}

private fun handleClipboardPasteShortcut(event: KeyEvent, terminal: Terminal): Boolean {
    val ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed
    val shift = event.isShiftPressed

    if (ctrlOrMeta && shift && event.key == Key.V) {
        val text = LinuxClipboard.readClipboardText() ?: return true
        terminal.write(normalizePastedText(text))
        return true
    }

    if (shift && event.key == Key.Insert) {
        val text = LinuxClipboard.readPrimarySelectionText()
            ?: LinuxClipboard.readClipboardText()
            ?: return true
        terminal.write(normalizePastedText(text))
        return true
    }

    return false
}

private fun normalizePastedText(text: String): String {
    return text.replace("\r\n", "\n")
}

private fun dispatchMouseEvent(
    terminal: Terminal,
    event: PointerEvent,
    eventType: PointerEventType,
    cellSize: IntSize,
    pressedButtons: MutableSet<Int>
) {
    val mode = terminal.getMouseTrackingState()
    if (!mode.enabled) return
    if (cellSize.width <= 0 || cellSize.height <= 0) return

    val change = event.changes.firstOrNull() ?: return
    val col = (change.position.x / cellSize.width).toInt().coerceAtLeast(0) + 1
    val row = (change.position.y / cellSize.height).toInt().coerceAtLeast(0) + 1

    when (eventType) {
        PointerEventType.Press -> {
            val button = mouseButtonCode(event.buttons) ?: return
            pressedButtons.add(button)
            terminal.write(encodeMouseSequence(mode.sgrMode, button, col, row, press = true, drag = false))
        }
        PointerEventType.Release -> {
            val button = pressedButtons.firstOrNull() ?: mouseButtonCode(event.buttons) ?: 0
            pressedButtons.remove(button)
            terminal.write(encodeMouseSequence(mode.sgrMode, button, col, row, press = false, drag = false))
        }
        PointerEventType.Move -> {
            val activeButton = pressedButtons.firstOrNull()
            if (mode.anyEventTracking || (mode.buttonEventTracking && activeButton != null)) {
                val button = activeButton ?: 3
                terminal.write(encodeMouseSequence(mode.sgrMode, button, col, row, press = true, drag = true))
            }
        }
        PointerEventType.Scroll -> {
            val dy = change.scrollDelta.y
            if (dy == 0f) return
            val wheelCode = if (dy < 0f) 64 else 65
            terminal.write(encodeMouseSequence(mode.sgrMode, wheelCode, col, row, press = true, drag = false))
        }
        else -> Unit
    }
}

private fun mouseButtonCode(buttons: PointerButtons): Int? {
    return when {
        buttons.isPrimaryPressed -> 0
        buttons.isTertiaryPressed -> 1
        buttons.isSecondaryPressed -> 2
        else -> null
    }
}

private fun encodeMouseSequence(
    sgrMode: Boolean,
    button: Int,
    col: Int,
    row: Int,
    press: Boolean,
    drag: Boolean
): String {
    val cb = if (drag) button + 32 else button
    return if (sgrMode) {
        val suffix = if (press) "M" else "m"
        "\u001B[<${cb};${col};${row}${suffix}"
    } else {
        val legacyCode = if (press) cb else 3
        val legacyCb = (legacyCode + 32).coerceAtMost(255)
        val legacyCol = (col + 32).coerceAtMost(255)
        val legacyRow = (row + 32).coerceAtMost(255)
        "\u001B[M${legacyCb.toChar()}${legacyCol.toChar()}${legacyRow.toChar()}"
    }
}

/**
 * Single terminal row rendered as an AnnotatedString.
 * Batches consecutive characters with the same attributes into a single span
 * for significantly better rendering performance.
 */
@Composable
private fun TerminalRow(
    cells: List<Cell>,
    cursorCol: Int,
    isFocused: Boolean
) {
    val annotatedString = remember(cells, cursorCol, isFocused) {
        buildAnnotatedString {
            var i = 0
            while (i < cells.size) {
                val isCursor = i == cursorCol
                val cell = cells[i]
                val attrs = cell.attrs
                val (fg, bg) = resolveColors(attrs)

                // Cursor cell is always a single-char span
                if (isCursor) {
                    withStyle(
                        SpanStyle(
                            color = CmuxColors.terminalBg,
                            background = CmuxColors.primary.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = CmuxTypography.terminalFontSize,
                            fontWeight = if (attrs.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (attrs.italic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = textDecorationFor(attrs)
                        )
                    ) {
                        val ch = cell.char
                        append(if (ch == '\u0000') ' ' else ch)
                    }
                    i++
                    continue
                }

                // Batch consecutive characters with the same attributes
                val batchStart = i
                i++
                while (i < cells.size && i != cursorCol && cells[i].attrs == attrs) {
                    i++
                }

                val effectiveFg = if (attrs.dim) fg.copy(alpha = 0.5f) else fg
                val effectiveBg = if (bg != Color.Transparent) bg else Color.Unspecified

                withStyle(
                    SpanStyle(
                        color = effectiveFg,
                        background = effectiveBg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = CmuxTypography.terminalFontSize,
                        fontWeight = if (attrs.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (attrs.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = textDecorationFor(attrs)
                    )
                ) {
                    for (col in batchStart until i) {
                        val ch = cells[col].char
                        append(if (ch == '\u0000') ' ' else ch)
                    }
                }
            }
        }
    }

    BasicText(
        text = annotatedString,
        maxLines = 1,
        softWrap = false,
    )
}

private fun textDecorationFor(attrs: CellAttributes): TextDecoration = when {
    attrs.underline && attrs.strikethrough ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    attrs.underline -> TextDecoration.Underline
    attrs.strikethrough -> TextDecoration.LineThrough
    else -> TextDecoration.None
}

private fun resolveColors(attrs: CellAttributes): Pair<Color, Color> {
    var fg = if (attrs.fg == Color.White) CmuxColors.terminalFg else attrs.fg
    var bg = attrs.bg
    if (attrs.inverse) {
        val tmp = fg
        fg = if (bg == Color.Transparent) CmuxColors.terminalBg else bg
        bg = tmp
    }
    if (attrs.hidden) {
        fg = bg
    }
    return fg to bg
}

/**
 * Handle only special keys and control sequences.
 * Regular character input is handled by the AWT KeyEventDispatcher (KEY_TYPED).
 */
private fun keyEventToSpecialInput(event: KeyEvent): String? {
    val ctrl = event.isCtrlPressed
    val alt = event.isAltPressed
    val shift = event.isShiftPressed

    // Control key combinations (Ctrl+A through Ctrl+Z)
    if (ctrl && !alt) {
        return when (event.key) {
            Key.A -> "\u0001"
            Key.B -> "\u0002"
            Key.C -> "\u0003"
            Key.D -> "\u0004"
            Key.E -> "\u0005"
            Key.F -> "\u0006"
            Key.G -> "\u0007"
            Key.H -> "\u0008"
            Key.I -> "\u0009"
            Key.J -> "\u000A"
            Key.K -> "\u000B"
            Key.L -> "\u000C"
            Key.M -> "\u000D"
            Key.N -> "\u000E"
            Key.O -> "\u000F"
            Key.P -> "\u0010"
            Key.Q -> "\u0011"
            Key.R -> "\u0012"
            Key.S -> "\u0013"
            Key.T -> "\u0014"
            Key.U -> "\u0015"
            Key.V -> "\u0016"
            Key.W -> "\u0017"
            Key.X -> "\u0018"
            Key.Y -> "\u0019"
            Key.Z -> "\u001A"
            else -> null
        }
    }

    // Special keys only (Enter, Backspace, arrows, function keys, etc.)
    return when (event.key) {
        Key.Enter -> "\r"
        Key.Backspace -> "\u007F"
        Key.Tab -> if (shift) "\u001B[Z" else "\t"
        Key.Escape -> "\u001B"
        Key.DirectionUp -> if (ctrl) "\u001B[1;5A" else if (alt) "\u001B[1;3A" else "\u001B[A"
        Key.DirectionDown -> if (ctrl) "\u001B[1;5B" else if (alt) "\u001B[1;3B" else "\u001B[B"
        Key.DirectionRight -> if (ctrl) "\u001B[1;5C" else if (alt) "\u001B[1;3C" else "\u001B[C"
        Key.DirectionLeft -> if (ctrl) "\u001B[1;5D" else if (alt) "\u001B[1;3D" else "\u001B[D"
        Key.MoveHome -> "\u001B[H"
        Key.MoveEnd -> "\u001B[F"
        Key.PageUp -> "\u001B[5~"
        Key.PageDown -> "\u001B[6~"
        Key.Insert -> "\u001B[2~"
        Key.Delete -> "\u001B[3~"
        Key.F1 -> "\u001BOP"
        Key.F2 -> "\u001BOQ"
        Key.F3 -> "\u001BOR"
        Key.F4 -> "\u001BOS"
        Key.F5 -> "\u001B[15~"
        Key.F6 -> "\u001B[17~"
        Key.F7 -> "\u001B[18~"
        Key.F8 -> "\u001B[19~"
        Key.F9 -> "\u001B[20~"
        Key.F10 -> "\u001B[21~"
        Key.F11 -> "\u001B[23~"
        Key.F12 -> "\u001B[24~"
        else -> null  // Regular chars handled by AWT KEY_TYPED dispatcher
    }
}
