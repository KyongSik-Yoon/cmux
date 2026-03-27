package com.cmux.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level terminal session combining PTY, buffer, and ANSI parser.
 * This is the main interface for terminal operations.
 */
class Terminal(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    var cols: Int = 80,
    var rows: Int = 24,
    shell: String = System.getenv("SHELL") ?: "/bin/bash"
) {
    val buffer = TerminalBuffer(cols, rows)
    val parser = AnsiParser(buffer)
    private var ptyProcess: PtyProcess? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Version counter for triggering recomposition
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private val versionCounter = AtomicLong(0)

    // Terminal state
    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive

    private val _title = MutableStateFlow(shell.substringAfterLast('/'))
    val title: StateFlow<String> = _title

    // Working directory tracking
    private val _cwd = MutableStateFlow(System.getenv("HOME") ?: "/")
    val cwd: StateFlow<String> = _cwd

    init {
        parser.onTitleChanged = { newTitle ->
            _title.value = newTitle
            // Many shells set title to "user@host: /path"
            val pathMatch = Regex(""":?\s*(.+)""").find(newTitle)
            pathMatch?.groupValues?.get(1)?.let { path ->
                if (path.startsWith("/") || path.startsWith("~")) {
                    _cwd.value = path
                }
            }
        }
    }

    fun start() {
        val pty = PtyProcess.spawn(
            rows = rows,
            cols = cols,
            env = mapOf("CMUX" to "1", "CMUX_TAB_ID" to id)
        )
        ptyProcess = pty

        readJob = scope.launch {
            val readBuf = ByteArray(8192)
            try {
                while (isActive && pty.running) {
                    val n = pty.read(readBuf)
                    if (n > 0) {
                        synchronized(buffer) {
                            parser.feed(readBuf, n)
                        }
                        _version.value = versionCounter.incrementAndGet()
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                // PTY read error
            } finally {
                _alive.value = false
            }
        }
    }

    fun write(data: String) {
        ptyProcess?.write(data)
    }

    fun write(data: ByteArray) {
        ptyProcess?.write(data)
    }

    fun sendKey(key: String) {
        write(key)
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        synchronized(buffer) {
            buffer.resize(newRows, newCols)
        }
        ptyProcess?.resize(newRows, newCols)
        _version.value = versionCounter.incrementAndGet()
    }

    fun destroy() {
        readJob?.cancel()
        ptyProcess?.destroy()
        scope.cancel()
        _alive.value = false
    }

    fun getSnapshot(): TerminalSnapshot {
        synchronized(buffer) {
            val lines = (0 until rows).map { row ->
                buffer.getLine(row).toList()
            }
            return TerminalSnapshot(
                lines = lines,
                cursorRow = buffer.cursorRow,
                cursorCol = buffer.cursorCol,
                cursorVisible = buffer.cursorVisible,
                rows = rows,
                cols = cols
            )
        }
    }
}

/**
 * Immutable snapshot of terminal state for rendering.
 */
data class TerminalSnapshot(
    val lines: List<List<Cell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val rows: Int,
    val cols: Int
)
