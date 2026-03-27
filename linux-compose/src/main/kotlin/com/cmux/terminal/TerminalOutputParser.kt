package com.cmux.terminal

import java.io.File

interface TerminalOutputParser {
    var onTitleChanged: ((String) -> Unit)?
    fun feed(data: ByteArray, length: Int)
    fun feed(text: String)
}

enum class TerminalEngine {
    ANSI,
    GHOSTTY,
}

data class TerminalParserSelection(
    val parser: TerminalOutputParser,
    val engine: TerminalEngine,
)

object TerminalParserFactory {
    private const val ENGINE_ENV = "CMUX_TERMINAL_ENGINE"
    private const val GHOSTTY_JNI_ENV = "CMUX_GHOSTTY_JNI_LIB"

    fun create(buffer: TerminalBuffer): TerminalParserSelection {
        val requested = System.getenv(ENGINE_ENV)?.trim()?.lowercase()
        if (requested == "ghostty") {
            val parser = GhosttyParser.tryCreate(buffer)
            if (parser != null) {
                return TerminalParserSelection(parser = parser, engine = TerminalEngine.GHOSTTY)
            }
            System.err.println("cmux: ghostty engine requested but unavailable, falling back to ansi")
        }
        return TerminalParserSelection(parser = AnsiParser(buffer), engine = TerminalEngine.ANSI)
    }

    fun ghosttyConfiguredLibraryPath(): String? {
        val raw = System.getenv(GHOSTTY_JNI_ENV)?.trim()
        if (raw.isNullOrEmpty()) return null
        val file = File(raw)
        return if (file.exists() && file.isFile) file.absolutePath else null
    }

    fun isGhosttyRequested(): Boolean {
        return System.getenv(ENGINE_ENV)?.trim()?.lowercase() == "ghostty"
    }
}
