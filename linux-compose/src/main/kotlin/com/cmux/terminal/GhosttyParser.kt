package com.cmux.terminal

/**
 * JNI integration placeholder for libghostty parser backend.
 *
 * Current behavior intentionally delegates parsing to AnsiParser so runtime
 * behavior remains stable while we wire native callbacks incrementally.
 */
class GhosttyParser private constructor(
    private val fallback: AnsiParser
) : TerminalOutputParser {

    override var onTitleChanged: ((String) -> Unit)?
        get() = fallback.onTitleChanged
        set(value) {
            fallback.onTitleChanged = value
        }

    override fun feed(data: ByteArray, length: Int) {
        fallback.feed(data, length)
    }

    override fun feed(text: String) {
        fallback.feed(text)
    }

    companion object {
        fun tryCreate(buffer: TerminalBuffer): GhosttyParser? {
            val jniLibPath = TerminalParserFactory.ghosttyConfiguredLibraryPath() ?: return null
            return runCatching {
                System.load(jniLibPath)
                GhosttyParser(AnsiParser(buffer))
            }.onFailure { error ->
                System.err.println("cmux: failed to load ghostty JNI library: ${error.message}")
            }.getOrNull()
        }
    }
}

