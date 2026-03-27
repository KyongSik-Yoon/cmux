package com.cmux.ghostty

import com.cmux.terminal.Terminal
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object GhosttyExternalWindowHost {
    private val processes = ConcurrentHashMap<String, Process>()

    fun ensureStarted(terminal: Terminal) {
        val existing = processes[terminal.id]
        if (existing != null && existing.isAlive) return

        stop(terminal.id)

        val cwd = terminal.cwd.value
        val title = "cmux ghostty ${terminal.id}"
        val scriptPath = resolveScriptPath() ?: run {
            System.err.println("cmux: ghostty external host script not found")
            return
        }

        val logFile = File("/tmp/cmux-ghostty-window-${terminal.id}.log")
        val process = ProcessBuilder(
            scriptPath,
            "--cwd", cwd,
            "--title", title
        )
            .directory(File(System.getProperty("user.dir") ?: "."))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()

        processes[terminal.id] = process
        System.err.println(
            "cmux: ghostty external window host started terminal=${terminal.id} log=${logFile.absolutePath}"
        )
    }

    fun stop(terminalId: String) {
        val process = processes.remove(terminalId) ?: return
        runCatching {
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun resolveScriptPath(): String? {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "scripts/ghostty-gtk-window-host.sh"),
            File(cwd, "../linux-compose/scripts/ghostty-gtk-window-host.sh"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
