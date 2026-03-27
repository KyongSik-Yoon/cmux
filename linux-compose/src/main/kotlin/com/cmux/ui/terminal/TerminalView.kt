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
import com.cmux.terminal.TerminalSnapshot
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
    onFocused: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

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
                    // Skip control characters (handled by onPreviewKeyEvent)
                    // and CHAR_UNDEFINED
                    if (ch != java.awt.event.KeyEvent.CHAR_UNDEFINED && ch.code >= 0x20) {
                        val input = if (awtEvent.isAltDown) "\u001B${ch}" else ch.toString()
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
            .onPreviewKeyEvent { event ->
                // Handle special keys and control sequences via Compose key events
                if (event.type == KeyEventType.KeyDown) {
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

    // Auto-focus on first composition
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * Single terminal row rendered as an AnnotatedString.
 */
@Composable
private fun TerminalRow(
    cells: List<Cell>,
    cursorCol: Int,
    isFocused: Boolean
) {
    val annotatedString = remember(cells, cursorCol, isFocused) {
        buildAnnotatedString {
            for ((col, cell) in cells.withIndex()) {
                val attrs = cell.attrs
                val (fg, bg) = resolveColors(attrs)
                val isCursor = col == cursorCol

                val effectiveFg = when {
                    isCursor -> CmuxColors.terminalBg
                    attrs.dim -> fg.copy(alpha = 0.5f)
                    else -> fg
                }
                val effectiveBg = when {
                    isCursor -> CmuxColors.primary.copy(alpha = 0.85f)
                    bg != Color.Transparent -> bg
                    else -> Color.Unspecified
                }

                withStyle(
                    SpanStyle(
                        color = effectiveFg,
                        background = effectiveBg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = CmuxTypography.terminalFontSize,
                        fontWeight = if (attrs.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (attrs.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = when {
                            attrs.underline && attrs.strikethrough ->
                                TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            attrs.underline -> TextDecoration.Underline
                            attrs.strikethrough -> TextDecoration.LineThrough
                            else -> TextDecoration.None
                        }
                    )
                ) {
                    val ch = cell.char
                    append(if (ch == '\u0000') ' ' else ch)
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
