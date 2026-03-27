package com.cmux.platform

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor

/**
 * Clipboard access for Linux desktop sessions.
 * - Clipboard: regular copy/paste buffer (Ctrl+Shift+V path)
 * - Primary selection: X11 PRIMARY selection (Shift+Insert path)
 */
object LinuxClipboard {
    fun readClipboardText(): String? {
        val clipboard = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
        }.getOrNull() ?: return null
        return readText(clipboard)
    }

    fun readPrimarySelectionText(): String? {
        val selection = runCatching {
            Toolkit.getDefaultToolkit().systemSelection
        }.getOrNull() ?: return null
        return readText(selection)
    }

    private fun readText(clipboard: Clipboard): String? {
        return runCatching {
            val content = clipboard.getContents(null) ?: return null
            if (!content.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
            (content.getTransferData(DataFlavor.stringFlavor) as? String)
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
