package com.cmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cmux.ghostty.GhosttyEmbedding
import com.cmux.ghostty.GhosttyRuntimeSession
import com.cmux.socket.SocketServer
import com.cmux.terminal.Terminal
import com.cmux.ui.notification.NotificationStore
import com.cmux.ui.sidebar.Sidebar
import com.cmux.ui.splitpane.SplitOrientation
import com.cmux.ui.splitpane.SplitPane
import com.cmux.ui.terminal.TerminalView
import com.cmux.ui.theme.CmuxColors
import com.cmux.ui.theme.CmuxTheme

/**
 * Application state manager.
 */
class AppState {
    val tabs = mutableStateListOf<Terminal>()
    var activeTabId by mutableStateOf("")
        private set
    val notificationStore = NotificationStore()
    val socketServer = SocketServer()
    private val initialCwd = System.getProperty("user.dir") ?: System.getenv("HOME") ?: "/"
    private var ghosttyRuntimeSession: GhosttyRuntimeSession? = null

    // Split pane state
    var splitTerminalId by mutableStateOf<String?>(null)
        private set
    var splitOrientation by mutableStateOf(SplitOrientation.HORIZONTAL)
        private set

    init {
        // Set up socket server terminal provider
        socketServer.terminalProvider = object : SocketServer.TerminalProvider {
            override fun listTerminals() = tabs.toList()
            override fun activeTerminal() = tabs.find { it.id == activeTabId }
            override fun selectTerminal(id: String) { selectTab(id) }
            override fun newTerminal(): Terminal = newTab()
            override fun closeTerminal(id: String) { closeTab(id) }
        }
    }

    fun newTab(): Terminal {
        val terminal = Terminal(initialCwd = initialCwd)
        tabs.add(terminal)
        activeTabId = terminal.id
        terminal.start()
        return terminal
    }

    fun selectTab(id: String) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
        }
    }

    fun closeTab(id: String) {
        val terminal = tabs.find { it.id == id } ?: return
        val index = tabs.indexOf(terminal)
        terminal.destroy()
        tabs.remove(terminal)

        if (splitTerminalId == id) {
            splitTerminalId = null
        }

        if (activeTabId == id) {
            activeTabId = when {
                tabs.isEmpty() -> ""
                index < tabs.size -> tabs[index].id
                else -> tabs.last().id
            }
        }

        // If no tabs left, create a new one
        if (tabs.isEmpty()) {
            newTab()
        }
    }

    fun splitPane(orientation: SplitOrientation) {
        if (splitTerminalId != null) {
            // Already split, close the split
            val splitTerm = tabs.find { it.id == splitTerminalId }
            splitTerm?.destroy()
            tabs.removeAll { it.id == splitTerminalId }
            splitTerminalId = null
            return
        }
        val terminal = Terminal(initialCwd = initialCwd)
        tabs.add(terminal)
        terminal.start()
        splitTerminalId = terminal.id
        splitOrientation = orientation
    }

    fun start() {
        socketServer.start()
        ensureGhosttyRuntime()
        if (tabs.isEmpty()) {
            newTab()
        }
    }

    fun shutdown() {
        socketServer.stop()
        ghosttyRuntimeSession?.close()
        ghosttyRuntimeSession = null
        tabs.forEach { it.destroy() }
        tabs.clear()
    }

    private fun ensureGhosttyRuntime() {
        if (ghosttyRuntimeSession != null) return

        val requested = System.getenv("CMUX_TERMINAL_ENGINE")
            ?.trim()
            ?.lowercase()
            ?: return
        if (requested != "ghostty") return

        val probe = GhosttyEmbedding.probe()
        val start = GhosttyRuntimeSession.start(probe)
        ghosttyRuntimeSession = start.session
        System.err.println("cmux: ${start.reason}")
    }
}

@Composable
fun App(state: AppState) {
    CmuxTheme {
        Row(modifier = Modifier.fillMaxSize().background(CmuxColors.background)) {
            // Sidebar
            Sidebar(
                tabs = state.tabs,
                activeTabId = state.activeTabId,
                onTabSelected = { state.selectTab(it) },
                onTabClosed = { state.closeTab(it) },
                onNewTab = { state.newTab() }
            )

            // Vertical divider
            VerticalDivider(
                color = CmuxColors.border,
                thickness = 1.dp,
                modifier = Modifier.fillMaxHeight()
            )

            // Main terminal area
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val activeTerminal = state.tabs.find { it.id == state.activeTabId }
                val splitTerminal = state.splitTerminalId?.let { id ->
                    state.tabs.find { it.id == id }
                }

                if (activeTerminal != null) {
                    if (splitTerminal != null) {
                        SplitPane(
                            orientation = state.splitOrientation,
                            modifier = Modifier.fillMaxSize(),
                            first = {
                                key(activeTerminal.id) {
                                    TerminalView(
                                        terminal = activeTerminal,
                                        autoFocus = true,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            },
                            second = {
                                key(splitTerminal.id) {
                                    TerminalView(
                                        terminal = splitTerminal,
                                        autoFocus = false,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        )
                    } else {
                        key(activeTerminal.id) {
                            TerminalView(
                                terminal = activeTerminal,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
