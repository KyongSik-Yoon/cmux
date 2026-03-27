package com.cmux.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level terminal session combining PTY, buffer, and ANSI parser.
 * This is the main interface for terminal operations.
 */
class Terminal(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    var cols: Int = 80,
    var rows: Int = 24,
    private val initialCwd: String = System.getProperty("user.dir") ?: System.getenv("HOME") ?: "/",
    shell: String = System.getenv("SHELL") ?: "/bin/bash"
) {
    val buffer = TerminalBuffer(cols, rows)
    private val parserSelection = TerminalParserFactory.create(buffer)
    val parser: TerminalOutputParser = parserSelection.parser
    val engine: TerminalEngine = parserSelection.engine
    private var ptyProcess: PtyProcess? = null
    private var readJob: Job? = null
    private var cwdJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Version counter for triggering recomposition
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private val versionCounter = AtomicLong(0)
    private val started = AtomicBoolean(false)

    // Terminal state
    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive

    private val _title = MutableStateFlow(shell.substringAfterLast('/'))
    val title: StateFlow<String> = _title

    // Working directory tracking
    private val _cwd = MutableStateFlow(initialCwd)
    val cwd: StateFlow<String> = _cwd
    private var cachedLines: MutableList<List<Cell>> = mutableListOf()
    private var snapshotInitialized = false
    private val perfLogEnabled = System.getenv("CMUX_PERF_LOG") == "1"
    private var perfSampleCount = 0
    private var perfTotalCopiedRows = 0L
    private var perfTotalSnapshotNanos = 0L

    init {
        if (engine == TerminalEngine.GHOSTTY) {
            System.err.println("cmux: terminal engine=ghostty (ansi-compatible fallback active)")
        }
        parser.onTitleChanged = { newTitle ->
            _title.value = newTitle
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val pty = PtyProcess.spawn(
            rows = rows,
            cols = cols,
            cwd = initialCwd,
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

        cwdJob = scope.launch {
            val cwdPath = Path.of("/proc", pty.pid.toString(), "cwd")
            while (isActive && pty.running) {
                runCatching {
                    Files.readSymbolicLink(cwdPath).toString()
                }.getOrNull()?.let { cwd ->
                    if (cwd != _cwd.value) {
                        _cwd.value = cwd
                    }
                }
                delay(150)
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

    fun getMouseTrackingState(): MouseTrackingState {
        synchronized(buffer) {
            return buffer.getMouseTrackingState()
        }
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
        cwdJob?.cancel()
        ptyProcess?.destroy()
        scope.cancel()
        _alive.value = false
    }

    fun getSnapshot(): TerminalSnapshot {
        synchronized(buffer) {
            val startNs = if (perfLogEnabled) System.nanoTime() else 0L
            var copiedRows = 0

            if (!snapshotInitialized || cachedLines.size != rows) {
                cachedLines = MutableList(rows) { row ->
                    buffer.getLine(row).toList()
                }
                buffer.consumeDirtyRows()
                snapshotInitialized = true
                copiedRows = rows
            } else {
                val dirtyRows = buffer.consumeDirtyRows()
                for (row in dirtyRows) {
                    if (row in 0 until rows) {
                        cachedLines[row] = buffer.getLine(row).toList()
                        copiedRows++
                    }
                }
            }

            if (perfLogEnabled) {
                perfSampleCount++
                perfTotalCopiedRows += copiedRows.toLong()
                perfTotalSnapshotNanos += System.nanoTime() - startNs
                if (perfSampleCount % 120 == 0) {
                    val avgMs = perfTotalSnapshotNanos.toDouble() / perfSampleCount / 1_000_000.0
                    val avgRows = perfTotalCopiedRows.toDouble() / perfSampleCount.toDouble()
                    System.err.println(
                        "cmux.perf snapshot avgMs=" +
                            "%.3f".format(avgMs) +
                            " avgCopiedRows=" +
                            "%.2f".format(avgRows) +
                            " rows=$rows cols=$cols"
                    )
                }
            }

            return TerminalSnapshot(
                lines = cachedLines.toList(),
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
