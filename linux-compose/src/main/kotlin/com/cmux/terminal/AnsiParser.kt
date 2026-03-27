package com.cmux.terminal

import androidx.compose.ui.graphics.Color

/**
 * ANSI/VT100 escape sequence parser.
 * Processes raw terminal output and updates the TerminalBuffer.
 */
class AnsiParser(private val buffer: TerminalBuffer) : TerminalOutputParser {

    private enum class State {
        GROUND,
        ESCAPE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        OSC,
        OSC_STRING,
        DCS,
        CHARSET,
    }

    private var state = State.GROUND
    private val params = mutableListOf<Int>()
    private var currentParam = -1
    private var intermediateChars = StringBuilder()
    private var oscString = StringBuilder()
    private var privateMarker: Char? = null

    // Terminal title (set via OSC)
    var title: String = ""
        private set

    // Callback for title changes
    override var onTitleChanged: ((String) -> Unit)? = null

    // UTF-8 decoder state
    private val utf8Buffer = ByteArray(4)
    private var utf8Remaining = 0
    private var utf8Index = 0

    override fun feed(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val byte = data[i].toInt() and 0xFF

            // If we're in the middle of a UTF-8 sequence
            if (utf8Remaining > 0) {
                if (byte and 0xC0 == 0x80) { // continuation byte
                    utf8Buffer[utf8Index++] = byte.toByte()
                    utf8Remaining--
                    if (utf8Remaining == 0) {
                        // Decode complete UTF-8 sequence
                        val str = String(utf8Buffer, 0, utf8Index, Charsets.UTF_8)
                        for (ch in str) {
                            processChar(ch.code)
                        }
                    }
                } else {
                    // Invalid continuation, reset and reprocess this byte
                    utf8Remaining = 0
                    utf8Index = 0
                    processChar(byte)
                }
                i++
                continue
            }

            // Check for multi-byte UTF-8 start
            when {
                byte < 0x80 -> {
                    // ASCII - process directly
                    processChar(byte)
                }
                byte and 0xE0 == 0xC0 -> { // 2-byte sequence
                    utf8Buffer[0] = byte.toByte()
                    utf8Index = 1
                    utf8Remaining = 1
                }
                byte and 0xF0 == 0xE0 -> { // 3-byte sequence
                    utf8Buffer[0] = byte.toByte()
                    utf8Index = 1
                    utf8Remaining = 2
                }
                byte and 0xF8 == 0xF0 -> { // 4-byte sequence
                    utf8Buffer[0] = byte.toByte()
                    utf8Index = 1
                    utf8Remaining = 3
                }
                else -> {
                    // Invalid byte, skip
                }
            }
            i++
        }
    }

    override fun feed(text: String) {
        for (ch in text) {
            processChar(ch.code)
        }
    }

    private fun processChar(codePoint: Int) {
        // Handle C0 control characters in any state
        if (codePoint < 0x20 && state != State.OSC && state != State.OSC_STRING && state != State.DCS) {
            when (codePoint) {
                0x07 -> { /* BEL - ignore */ }
                0x08 -> buffer.backspace()
                0x09 -> buffer.tab()
                0x0A, 0x0B, 0x0C -> buffer.lineFeed() // LF, VT, FF
                0x0D -> buffer.carriageReturn()
                0x1B -> {
                    state = State.ESCAPE
                    intermediateChars.clear()
                    params.clear()
                    currentParam = -1
                    privateMarker = null
                    return
                }
                // 0x00-0x06, 0x0E, 0x0F, etc. - ignore
            }
            if (codePoint != 0x1B) return
        }

        when (state) {
            State.GROUND -> processGround(codePoint)
            State.ESCAPE -> processEscape(codePoint)
            State.CSI_ENTRY, State.CSI_PARAM -> processCSI(codePoint)
            State.CSI_INTERMEDIATE -> processCSIIntermediate(codePoint)
            State.OSC, State.OSC_STRING -> processOSC(codePoint)
            State.DCS -> processDCS(codePoint)
            State.CHARSET -> {
                // Consume one character for charset designation and return to ground
                state = State.GROUND
            }
        }
    }

    private fun processGround(codePoint: Int) {
        if (codePoint >= 0x20) {
            // Handle supplementary (surrogate pair) characters
            if (codePoint > 0xFFFF) {
                for (ch in String(Character.toChars(codePoint))) {
                    buffer.putChar(ch)
                }
            } else {
                buffer.putChar(codePoint.toChar())
            }
        }
    }

    private fun processEscape(byte: Int) {
        when (byte.toChar()) {
            '[' -> {
                state = State.CSI_ENTRY
                params.clear()
                currentParam = -1
                intermediateChars.clear()
                privateMarker = null
            }
            ']' -> {
                state = State.OSC
                oscString.clear()
                currentParam = -1
            }
            '(' , ')' , '*' , '+' -> {
                state = State.CHARSET
            }
            'P' -> state = State.DCS
            'D' -> { buffer.lineFeed(); state = State.GROUND }
            'E' -> { buffer.carriageReturn(); buffer.lineFeed(); state = State.GROUND }
            'M' -> { buffer.reverseLineFeed(); state = State.GROUND }
            'H' -> { /* Set tab stop - ignore for now */ state = State.GROUND }
            '7' -> { buffer.saveCursor(); state = State.GROUND }
            '8' -> { buffer.restoreCursor(); state = State.GROUND }
            'c' -> { buffer.reset(); state = State.GROUND }
            '>' -> state = State.GROUND // Normal keypad mode
            '=' -> state = State.GROUND // Application keypad mode
            '#' -> state = State.GROUND // DEC test
            else -> state = State.GROUND
        }
    }

    private fun processCSI(byte: Int) {
        val ch = byte.toChar()
        when {
            ch in '0'..'9' -> {
                if (currentParam < 0) currentParam = 0
                currentParam = currentParam * 10 + (byte - 0x30)
                state = State.CSI_PARAM
            }
            ch == ';' -> {
                params.add(if (currentParam < 0) 0 else currentParam)
                currentParam = -1
                state = State.CSI_PARAM
            }
            ch == '?' || ch == '>' || ch == '!' || ch == '=' -> {
                privateMarker = ch
                state = State.CSI_PARAM
            }
            ch == ' ' || ch == '"' || ch == '\'' || ch == '$' -> {
                intermediateChars.append(ch)
                state = State.CSI_INTERMEDIATE
            }
            byte in 0x40..0x7E -> {
                // Final byte
                if (currentParam >= 0) params.add(currentParam)
                executeCSI(ch)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun processCSIIntermediate(byte: Int) {
        val ch = byte.toChar()
        when {
            byte in 0x20..0x2F -> intermediateChars.append(ch)
            byte in 0x40..0x7E -> {
                if (currentParam >= 0) params.add(currentParam)
                executeCSI(ch)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun processOSC(byte: Int) {
        when {
            byte == 0x07 -> { // BEL terminates OSC
                handleOSC()
                state = State.GROUND
            }
            byte == 0x1B -> {
                // ESC might be start of ST (ESC \)
                state = State.OSC_STRING
            }
            byte == 0x9C -> { // ST
                handleOSC()
                state = State.GROUND
            }
            else -> oscString.append(byte.toChar())
        }
    }

    private fun processDCS(byte: Int) {
        // Consume DCS sequences until ST
        if (byte == 0x1B || byte == 0x9C) {
            state = State.GROUND
        }
    }

    private fun handleOSC() {
        val str = oscString.toString()
        val semicolonIndex = str.indexOf(';')
        if (semicolonIndex >= 0) {
            val code = str.substring(0, semicolonIndex).toIntOrNull() ?: return
            val value = str.substring(semicolonIndex + 1)
            when (code) {
                0, 2 -> { // Set window title
                    title = value
                    onTitleChanged?.invoke(value)
                }
                1 -> { /* Set icon name - ignore */ }
                // Could handle OSC 8 (hyperlinks), OSC 52 (clipboard), etc.
            }
        }
    }

    private fun param(index: Int, default: Int = 0): Int {
        return if (index < params.size && params[index] > 0) params[index] else default
    }

    private fun executeCSI(finalByte: Char) {
        if (privateMarker != null) {
            executePrivateCSI(finalByte)
            return
        }

        when (finalByte) {
            'A' -> buffer.moveCursorUp(param(0, 1))    // CUU
            'B' -> buffer.moveCursorDown(param(0, 1))   // CUD
            'C' -> buffer.moveCursorForward(param(0, 1)) // CUF
            'D' -> buffer.moveCursorBackward(param(0, 1)) // CUB
            'E' -> { // CNL - cursor next line
                buffer.moveCursorDown(param(0, 1))
                buffer.carriageReturn()
            }
            'F' -> { // CPL - cursor previous line
                buffer.moveCursorUp(param(0, 1))
                buffer.carriageReturn()
            }
            'G' -> buffer.setCursor(buffer.cursorRow, param(0, 1) - 1)  // CHA
            'H', 'f' -> { // CUP / HVP
                buffer.setCursor(param(0, 1) - 1, param(1, 1) - 1)
            }
            'J' -> buffer.eraseInDisplay(param(0))     // ED
            'K' -> buffer.eraseInLine(param(0))        // EL
            'L' -> buffer.insertLines(param(0, 1))     // IL
            'M' -> buffer.deleteLines(param(0, 1))     // DL
            'P' -> buffer.deleteChars(param(0, 1))     // DCH
            'S' -> buffer.scrollUpN(param(0, 1))       // SU
            'T' -> buffer.scrollDownN(param(0, 1))     // SD
            'X' -> buffer.eraseChars(param(0, 1))      // ECH
            '@' -> buffer.insertChars(param(0, 1))     // ICH
            'd' -> buffer.setCursor(param(0, 1) - 1, buffer.cursorCol) // VPA
            'r' -> { // DECSTBM - set scroll region
                val top = param(0, 1) - 1
                val bottom = param(1, buffer.rows) - 1
                buffer.setScrollRegion(top, bottom)
            }
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'm' -> executeSGR()                         // SGR
            'n' -> { /* Device status report - ignore for now */ }
            'c' -> { /* Device attributes - ignore */ }
            't' -> { /* Window manipulation - ignore */ }
            'l' -> { /* Reset mode - ignore */ }
            'h' -> { /* Set mode - ignore */ }
            'q' -> { // DECSCUSR - set cursor style (with space intermediate)
                // Ignore for now
            }
        }
    }

    private fun executePrivateCSI(finalByte: Char) {
        if (privateMarker != '?') return

        when (finalByte) {
            'h' -> { // DECSET
                for (p in params) {
                    when (p) {
                        1 -> { /* Application cursor keys */ }
                        25 -> buffer.cursorVisible = true
                        47, 1047 -> buffer.enableAltScreen()
                        1049 -> {
                            buffer.saveCursor()
                            buffer.enableAltScreen()
                        }
                        1000, 1002, 1003, 1006 -> buffer.setMouseTrackingMode(p, true)
                        2004 -> { /* Bracketed paste mode */ }
                    }
                }
            }
            'l' -> { // DECRST
                for (p in params) {
                    when (p) {
                        1 -> { /* Normal cursor keys */ }
                        25 -> buffer.cursorVisible = false
                        47, 1047 -> buffer.disableAltScreen()
                        1049 -> {
                            buffer.disableAltScreen()
                            buffer.restoreCursor()
                        }
                        1000, 1002, 1003, 1006 -> buffer.setMouseTrackingMode(p, false)
                        2004 -> { /* Disable bracketed paste */ }
                    }
                }
            }
        }
    }

    private fun executeSGR() {
        if (params.isEmpty()) {
            buffer.currentAttrs = CellAttributes()
            return
        }

        var i = 0
        var attrs = buffer.currentAttrs
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> attrs = CellAttributes()
                1 -> attrs = attrs.copy(bold = true)
                2 -> attrs = attrs.copy(dim = true)
                3 -> attrs = attrs.copy(italic = true)
                4 -> attrs = attrs.copy(underline = true)
                7 -> attrs = attrs.copy(inverse = true)
                8 -> attrs = attrs.copy(hidden = true)
                9 -> attrs = attrs.copy(strikethrough = true)
                21, 22 -> attrs = attrs.copy(bold = false, dim = false)
                23 -> attrs = attrs.copy(italic = false)
                24 -> attrs = attrs.copy(underline = false)
                27 -> attrs = attrs.copy(inverse = false)
                28 -> attrs = attrs.copy(hidden = false)
                29 -> attrs = attrs.copy(strikethrough = false)
                in 30..37 -> attrs = attrs.copy(fg = ansi256Color(p - 30))
                38 -> {
                    val (color, consumed) = parseExtendedColor(i)
                    if (color != null) attrs = attrs.copy(fg = color)
                    i += consumed
                }
                39 -> attrs = attrs.copy(fg = Color.White)
                in 40..47 -> attrs = attrs.copy(bg = ansi256Color(p - 40))
                48 -> {
                    val (color, consumed) = parseExtendedColor(i)
                    if (color != null) attrs = attrs.copy(bg = color)
                    i += consumed
                }
                49 -> attrs = attrs.copy(bg = Color.Transparent)
                in 90..97 -> attrs = attrs.copy(fg = ansi256Color(p - 90 + 8))
                in 100..107 -> attrs = attrs.copy(bg = ansi256Color(p - 100 + 8))
            }
            i++
        }
        buffer.currentAttrs = attrs
    }

    private fun parseExtendedColor(startIndex: Int): Pair<Color?, Int> {
        if (startIndex + 1 >= params.size) return null to 0
        return when (params[startIndex + 1]) {
            5 -> { // 256-color
                if (startIndex + 2 < params.size) {
                    ansi256Color(params[startIndex + 2]) to 2
                } else null to 1
            }
            2 -> { // 24-bit RGB
                if (startIndex + 4 < params.size) {
                    val r = params[startIndex + 2].coerceIn(0, 255)
                    val g = params[startIndex + 3].coerceIn(0, 255)
                    val b = params[startIndex + 4].coerceIn(0, 255)
                    Color(r, g, b) to 4
                } else null to 1
            }
            else -> null to 0
        }
    }

    companion object {
        // Standard ANSI colors (index 0-15)
        private val ANSI_COLORS = arrayOf(
            Color(0x00, 0x00, 0x00), // 0: Black
            Color(0xCC, 0x00, 0x00), // 1: Red
            Color(0x00, 0xCC, 0x00), // 2: Green
            Color(0xCC, 0xCC, 0x00), // 3: Yellow
            Color(0x00, 0x00, 0xCC), // 4: Blue
            Color(0xCC, 0x00, 0xCC), // 5: Magenta
            Color(0x00, 0xCC, 0xCC), // 6: Cyan
            Color(0xCC, 0xCC, 0xCC), // 7: White
            Color(0x55, 0x55, 0x55), // 8: Bright Black
            Color(0xFF, 0x55, 0x55), // 9: Bright Red
            Color(0x55, 0xFF, 0x55), // 10: Bright Green
            Color(0xFF, 0xFF, 0x55), // 11: Bright Yellow
            Color(0x55, 0x55, 0xFF), // 12: Bright Blue
            Color(0xFF, 0x55, 0xFF), // 13: Bright Magenta
            Color(0x55, 0xFF, 0xFF), // 14: Bright Cyan
            Color(0xFF, 0xFF, 0xFF), // 15: Bright White
        )

        fun ansi256Color(index: Int): Color {
            return when {
                index < 0 -> Color.White
                index < 16 -> ANSI_COLORS[index]
                index < 232 -> {
                    // 216-color cube (6x6x6)
                    val i = index - 16
                    val r = (i / 36) * 51
                    val g = ((i % 36) / 6) * 51
                    val b = (i % 6) * 51
                    Color(r, g, b)
                }
                index < 256 -> {
                    // Grayscale ramp
                    val v = 8 + (index - 232) * 10
                    Color(v, v, v)
                }
                else -> Color.White
            }
        }
    }
}
