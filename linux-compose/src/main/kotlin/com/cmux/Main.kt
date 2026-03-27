package com.cmux

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.cmux.ghostty.GhosttyEmbedding
import com.cmux.ui.theme.CmuxColors

fun main() = application {
    val state = remember { AppState() }

    // Start terminals and socket server
    LaunchedEffect(Unit) {
        val requestedEngine = System.getenv("CMUX_TERMINAL_ENGINE")
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { "ansi" }
            ?: "ansi"
        val embeddingProbe = GhosttyEmbedding.probe()
        System.err.println(
            "cmux: ghostty-embed requestedEngine=$requestedEngine " +
                "usableInCompose=${embeddingProbe.usableInCompose} " +
                "lib=${embeddingProbe.libraryPath ?: "none"} " +
                "header=${embeddingProbe.headerPath ?: "none"} " +
                "reason=${embeddingProbe.reason}"
        )
        state.start()
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose { state.shutdown() }
    }

    Window(
        onCloseRequest = {
            state.shutdown()
            exitApplication()
        },
        title = "cmux",
        state = rememberWindowState(
            size = DpSize(1200.dp, 800.dp),
            position = WindowPosition(Alignment.Center)
        ),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                handleGlobalKeyEvent(event, state)
            } else false
        }
    ) {
        App(state)
    }
}

/**
 * Global keyboard shortcuts.
 */
private fun handleGlobalKeyEvent(event: KeyEvent, state: AppState): Boolean {
    val ctrl = event.isCtrlPressed
    val shift = event.isShiftPressed
    val alt = event.isAltPressed

    // Ctrl+Shift shortcuts (terminal-safe)
    if (ctrl && shift) {
        return when (event.key) {
            Key.T -> { state.newTab(); true }
            Key.W -> {
                if (state.tabs.size > 1) {
                    state.closeTab(state.activeTabId)
                }
                true
            }
            Key.E -> { state.splitPane(com.cmux.ui.splitpane.SplitOrientation.HORIZONTAL); true }
            Key.O -> { state.splitPane(com.cmux.ui.splitpane.SplitOrientation.VERTICAL); true }
            else -> false
        }
    }

    // Alt+number for tab switching
    if (alt && !ctrl) {
        val num = when (event.key) {
            Key.One -> 1; Key.Two -> 2; Key.Three -> 3
            Key.Four -> 4; Key.Five -> 5; Key.Six -> 6
            Key.Seven -> 7; Key.Eight -> 8; Key.Nine -> 9
            else -> 0
        }
        if (num > 0 && num <= state.tabs.size) {
            state.selectTab(state.tabs[num - 1].id)
            return true
        }
    }

    // Ctrl+Tab / Ctrl+Shift+Tab for next/prev tab
    if (ctrl && event.key == Key.Tab) {
        val currentIdx = state.tabs.indexOfFirst { it.id == state.activeTabId }
        if (currentIdx >= 0) {
            val nextIdx = if (shift) {
                (currentIdx - 1 + state.tabs.size) % state.tabs.size
            } else {
                (currentIdx + 1) % state.tabs.size
            }
            state.selectTab(state.tabs[nextIdx].id)
            return true
        }
    }

    return false
}
