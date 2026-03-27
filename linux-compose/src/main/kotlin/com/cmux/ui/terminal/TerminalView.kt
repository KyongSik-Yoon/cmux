package com.cmux.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.cmux.terminal.Cell
import com.cmux.terminal.CellAttributes
import com.cmux.terminal.Terminal
import com.cmux.terminal.TerminalSnapshot
import com.cmux.ui.theme.CmuxColors
import com.cmux.ui.theme.CmuxTypography
import kotlinx.coroutines.delay

/**
 * Compose Canvas-based terminal renderer.
 * Renders the terminal buffer directly to a Canvas for performance.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalView(
    terminal: Terminal,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // Subscribe to terminal updates
    val version by terminal.version.collectAsState()

    // Measure cell size using text measurer
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

    Box(
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
                if (event.type == KeyEventType.KeyDown) {
                    val input = keyEventToTerminalInput(event)
                    if (input != null) {
                        terminal.write(input)
                        true
                    } else false
                } else false
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTerminal(snapshot, cellSize, textMeasurer, cursorBlink && isFocused)
        }
    }

    // Auto-focus on first composition
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

private fun DrawScope.drawTerminal(
    snapshot: TerminalSnapshot,
    cellSize: IntSize,
    textMeasurer: TextMeasurer,
    showCursor: Boolean
) {
    val cw = cellSize.width.toFloat()
    val ch = cellSize.height.toFloat()

    // Draw cells
    for (row in 0 until snapshot.rows) {
        if (row >= snapshot.lines.size) break
        val line = snapshot.lines[row]
        val y = row * ch

        for (col in line.indices) {
            val cell = line[col]
            val x = col * cw
            val attrs = cell.attrs
            val (fg, bg) = resolveColors(attrs)

            // Draw background if not transparent
            if (bg != Color.Transparent && bg != CmuxColors.terminalBg) {
                drawRect(
                    color = bg,
                    topLeft = Offset(x, y),
                    size = Size(cw, ch)
                )
            }

            // Draw character
            if (cell.char != ' ' && cell.char != '\u0000') {
                val style = TextStyle(
                    color = if (attrs.dim) fg.copy(alpha = 0.5f) else fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = CmuxTypography.terminalFontSize,
                    fontWeight = if (attrs.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (attrs.italic) FontStyle.Italic else FontStyle.Normal,
                )
                val textResult = textMeasurer.measure(
                    text = AnnotatedString(cell.char.toString()),
                    style = style
                )
                drawText(textResult, topLeft = Offset(x, y))
            }

            // Draw underline
            if (attrs.underline) {
                drawLine(
                    color = fg,
                    start = Offset(x, y + ch - 2),
                    end = Offset(x + cw, y + ch - 2),
                    strokeWidth = 1f
                )
            }

            // Draw strikethrough
            if (attrs.strikethrough) {
                drawLine(
                    color = fg,
                    start = Offset(x, y + ch / 2),
                    end = Offset(x + cw, y + ch / 2),
                    strokeWidth = 1f
                )
            }
        }
    }

    // Draw cursor
    if (snapshot.cursorVisible && showCursor) {
        val cx = snapshot.cursorCol * cw
        val cy = snapshot.cursorRow * ch
        drawRect(
            color = CmuxColors.primary.copy(alpha = 0.8f),
            topLeft = Offset(cx, cy),
            size = Size(cw, ch)
        )
        // Redraw character under cursor with inverted color
        if (snapshot.cursorRow < snapshot.lines.size && snapshot.cursorCol < snapshot.lines[snapshot.cursorRow].size) {
            val cell = snapshot.lines[snapshot.cursorRow][snapshot.cursorCol]
            if (cell.char != ' ' && cell.char != '\u0000') {
                val textResult = textMeasurer.measure(
                    text = AnnotatedString(cell.char.toString()),
                    style = TextStyle(
                        color = CmuxColors.terminalBg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = CmuxTypography.terminalFontSize,
                        fontWeight = if (cell.attrs.bold) FontWeight.Bold else FontWeight.Normal,
                    )
                )
                drawText(textResult, topLeft = Offset(cx, cy))
            }
        }
    }
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
 * Convert Compose key events to terminal escape sequences.
 */
private fun keyEventToTerminalInput(event: KeyEvent): String? {
    val ctrl = event.isCtrlPressed
    val alt = event.isAltPressed
    val shift = event.isShiftPressed

    // Control key combinations
    if (ctrl && !alt) {
        val key = event.key
        return when (key) {
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

    // Special keys
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
        else -> {
            // Regular character input
            val char = event.utf16CodePoint
            if (char > 0) {
                val ch = char.toChar()
                if (alt) "\u001B${ch}" else ch.toString()
            } else null
        }
    }
}

// Extension to get UTF-16 code point from key event
private val KeyEvent.utf16CodePoint: Int
    get() {
        return try {
            val nativeEvent = this.nativeKeyEvent
            // For AWT-based Compose, nativeKeyEvent is java.awt.event.KeyEvent
            if (nativeEvent is java.awt.event.KeyEvent) {
                nativeEvent.keyChar.code
            } else 0
        } catch (e: Exception) {
            0
        }
    }
