package com.cmux.terminal

import androidx.compose.ui.graphics.Color

/**
 * Character attributes for terminal cells.
 */
data class CellAttributes(
    val fg: Color = Color.White,
    val bg: Color = Color.Transparent,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val dim: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
)

/**
 * A single terminal cell.
 */
data class Cell(
    val char: Char = ' ',
    val attrs: CellAttributes = CellAttributes(),
    val width: Int = 1 // 1 for normal, 2 for wide chars
)

data class MouseTrackingState(
    val basicTracking: Boolean = false,        // DECSET 1000
    val buttonEventTracking: Boolean = false,  // DECSET 1002
    val anyEventTracking: Boolean = false,     // DECSET 1003
    val sgrMode: Boolean = false               // DECSET 1006
) {
    val enabled: Boolean
        get() = basicTracking || buttonEventTracking || anyEventTracking
}

/**
 * Terminal screen buffer with scrollback.
 */
class TerminalBuffer(
    var cols: Int = 80,
    var rows: Int = 24,
    private val maxScrollback: Int = 10000
) {
    // Active screen lines
    private val screen: MutableList<MutableList<Cell>> = MutableList(rows) { newLine() }

    // Scrollback buffer
    private val scrollback: MutableList<List<Cell>> = mutableListOf()

    // Cursor position (0-based)
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    // Cursor visibility
    var cursorVisible: Boolean = true

    // Current text attributes
    var currentAttrs: CellAttributes = CellAttributes()

    // Scroll region (top inclusive, bottom inclusive)
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    // Saved cursor state
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedAttrs = CellAttributes()

    // Alternate screen buffer
    private var altScreen: MutableList<MutableList<Cell>>? = null
    private var altCursorRow = 0
    private var altCursorCol = 0
    var isAltScreen = false
        private set

    // Tab stops (default every 8 columns)
    private val tabStops: MutableSet<Int> = (0 until cols step 8).toMutableSet()
    private var mouseTrackingState = MouseTrackingState()
    private val dirtyRows: MutableSet<Int> = mutableSetOf<Int>().apply {
        addAll(0 until rows)
    }

    private fun markDirtyRow(row: Int) {
        if (row in 0 until rows) dirtyRows.add(row)
    }

    private fun markDirtyRange(startRow: Int, endRow: Int) {
        val start = startRow.coerceAtLeast(0)
        val end = endRow.coerceAtMost(rows - 1)
        if (start > end) return
        for (row in start..end) {
            dirtyRows.add(row)
        }
    }

    private fun markAllDirty() {
        dirtyRows.clear()
        dirtyRows.addAll(0 until rows)
    }

    fun consumeDirtyRows(): IntArray {
        if (dirtyRows.isEmpty()) return IntArray(0)
        val out = dirtyRows.sorted().toIntArray()
        dirtyRows.clear()
        return out
    }

    fun getMouseTrackingState(): MouseTrackingState = mouseTrackingState

    fun setMouseTrackingMode(mode: Int, enabled: Boolean) {
        mouseTrackingState = when (mode) {
            1000 -> mouseTrackingState.copy(basicTracking = enabled)
            1002 -> mouseTrackingState.copy(buttonEventTracking = enabled)
            1003 -> mouseTrackingState.copy(anyEventTracking = enabled)
            1006 -> mouseTrackingState.copy(sgrMode = enabled)
            else -> mouseTrackingState
        }
    }

    private fun newLine(): MutableList<Cell> = MutableList(cols) { Cell() }

    fun getCell(row: Int, col: Int): Cell {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return Cell()
        return screen[row][col]
    }

    fun getLine(row: Int): List<Cell> {
        if (row < 0 || row >= rows) return List(cols) { Cell() }
        return screen[row]
    }

    fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            // Auto-wrap
            cursorCol = 0
            lineFeed()
        }
        if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
            screen[cursorRow][cursorCol] = Cell(ch, currentAttrs)
            markDirtyRow(cursorRow)
            cursorCol++
        }
    }

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    fun moveCursorUp(n: Int = 1) {
        cursorRow = (cursorRow - n).coerceIn(0, rows - 1)
    }

    fun moveCursorDown(n: Int = 1) {
        cursorRow = (cursorRow + n).coerceIn(0, rows - 1)
    }

    fun moveCursorForward(n: Int = 1) {
        cursorCol = (cursorCol + n).coerceIn(0, cols - 1)
    }

    fun moveCursorBackward(n: Int = 1) {
        cursorCol = (cursorCol - n).coerceIn(0, cols - 1)
    }

    fun carriageReturn() {
        cursorCol = 0
    }

    fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp()
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            scrollDown()
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    fun tab() {
        val nextTab = tabStops.filter { it > cursorCol }.minOrNull() ?: (cols - 1)
        cursorCol = nextTab.coerceAtMost(cols - 1)
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    private fun scrollUp() {
        if (!isAltScreen) {
            scrollback.add(screen[scrollTop].toList())
            if (scrollback.size > maxScrollback) {
                scrollback.removeAt(0)
            }
        }
        screen.removeAt(scrollTop)
        screen.add(scrollBottom, newLine())
        markAllDirty()
    }

    private fun scrollDown() {
        screen.removeAt(scrollBottom)
        screen.add(scrollTop, newLine())
        markAllDirty()
    }

    fun scrollUpN(n: Int) {
        repeat(n) { scrollUp() }
    }

    fun scrollDownN(n: Int) {
        repeat(n) { scrollDown() }
    }

    fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> { // Erase from cursor to end of line
                for (i in cursorCol until cols) {
                    screen[cursorRow][i] = Cell(attrs = currentAttrs)
                }
                markDirtyRow(cursorRow)
            }
            1 -> { // Erase from start of line to cursor
                for (i in 0..cursorCol.coerceAtMost(cols - 1)) {
                    screen[cursorRow][i] = Cell(attrs = currentAttrs)
                }
                markDirtyRow(cursorRow)
            }
            2 -> { // Erase entire line
                screen[cursorRow] = newLine()
                markDirtyRow(cursorRow)
            }
        }
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { // Erase from cursor to end of screen
                eraseInLine(0)
                for (i in (cursorRow + 1) until rows) {
                    screen[i] = newLine()
                }
                markDirtyRange(cursorRow, rows - 1)
            }
            1 -> { // Erase from start of screen to cursor
                for (i in 0 until cursorRow) {
                    screen[i] = newLine()
                }
                eraseInLine(1)
                markDirtyRange(0, cursorRow)
            }
            2, 3 -> { // Erase entire screen
                for (i in 0 until rows) {
                    screen[i] = newLine()
                }
                markAllDirty()
            }
        }
    }

    fun insertLines(n: Int) {
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        repeat(count) {
            screen.removeAt(scrollBottom)
            screen.add(cursorRow, newLine())
        }
        if (count > 0) markAllDirty()
    }

    fun deleteLines(n: Int) {
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        repeat(count) {
            screen.removeAt(cursorRow)
            screen.add(scrollBottom, newLine())
        }
        if (count > 0) markAllDirty()
    }

    fun insertChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        repeat(count) {
            line.add(cursorCol, Cell(attrs = currentAttrs))
            if (line.size > cols) line.removeAt(line.size - 1)
        }
        if (count > 0) markDirtyRow(cursorRow)
    }

    fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        repeat(count) {
            if (cursorCol < line.size) {
                line.removeAt(cursorCol)
                line.add(Cell(attrs = currentAttrs))
            }
        }
        if (count > 0) markDirtyRow(cursorRow)
    }

    fun eraseChars(n: Int) {
        val count = n.coerceAtMost(cols - cursorCol)
        for (i in cursorCol until (cursorCol + count).coerceAtMost(cols)) {
            screen[cursorRow][i] = Cell(attrs = currentAttrs)
        }
        if (count > 0) markDirtyRow(cursorRow)
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        if (scrollTop >= scrollBottom) {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        setCursor(0, 0)
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedAttrs = currentAttrs
    }

    fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
        currentAttrs = savedAttrs
    }

    fun enableAltScreen() {
        if (isAltScreen) return
        altScreen = screen.map { it.toMutableList() }.toMutableList()
        altCursorRow = cursorRow
        altCursorCol = cursorCol
        for (i in 0 until rows) {
            screen[i] = newLine()
        }
        cursorRow = 0
        cursorCol = 0
        isAltScreen = true
        markAllDirty()
    }

    fun disableAltScreen() {
        if (!isAltScreen) return
        altScreen?.let { alt ->
            for (i in 0 until rows.coerceAtMost(alt.size)) {
                screen[i] = alt[i]
            }
        }
        cursorRow = altCursorRow
        cursorCol = altCursorCol
        altScreen = null
        isAltScreen = false
        markAllDirty()
    }

    fun resize(newRows: Int, newCols: Int) {
        val oldRows = rows
        val oldCols = cols
        rows = newRows
        cols = newCols
        scrollBottom = rows - 1
        scrollTop = 0

        // Adjust screen size
        while (screen.size < rows) {
            screen.add(newLine())
        }
        while (screen.size > rows) {
            val removed = screen.removeAt(0)
            if (!isAltScreen) {
                scrollback.add(removed.toList())
            }
        }

        // Adjust column width for each line
        for (i in screen.indices) {
            val line = screen[i]
            while (line.size < cols) line.add(Cell())
            while (line.size > cols) line.removeAt(line.size - 1)
        }

        // Adjust tab stops
        tabStops.clear()
        tabStops.addAll(0 until cols step 8)

        // Clamp cursor
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        markAllDirty()
    }

    fun reset() {
        currentAttrs = CellAttributes()
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
        for (i in 0 until rows) {
            screen[i] = newLine()
        }
        scrollback.clear()
        altScreen = null
        isAltScreen = false
        mouseTrackingState = MouseTrackingState()
        markAllDirty()
    }

    val scrollbackSize: Int get() = scrollback.size

    fun getScrollbackLine(index: Int): List<Cell> {
        if (index < 0 || index >= scrollback.size) return List(cols) { Cell() }
        return scrollback[index]
    }
}
