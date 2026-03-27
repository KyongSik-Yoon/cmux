package com.cmux.socket

import com.cmux.terminal.Terminal
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.io.File

/**
 * Unix domain socket server for external control of cmux.
 * Provides a simple text-based protocol compatible with the macOS cmux socket API.
 *
 * Note: Uses TCP on localhost as a fallback since JNA Unix domain sockets
 * are more complex. Future: migrate to Unix domain sockets via JDK 16+ or JNA.
 */
class SocketServer(
    private val port: Int = 0,  // 0 = auto-assign
    private val socketPath: String = "/tmp/cmux-linux.sock"
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handlers = mutableMapOf<String, CommandHandler>()

    var actualPort: Int = 0
        private set

    fun interface CommandHandler {
        fun handle(args: List<String>): String
    }

    // Terminal manager reference
    var terminalProvider: TerminalProvider? = null

    interface TerminalProvider {
        fun listTerminals(): List<Terminal>
        fun activeTerminal(): Terminal?
        fun selectTerminal(id: String)
        fun newTerminal(): Terminal
        fun closeTerminal(id: String)
    }

    init {
        registerBuiltinCommands()
    }

    private fun registerBuiltinCommands() {
        register("ping") { _ -> "pong" }
        register("version") { _ -> "cmux-linux 0.1.0" }

        register("tab.list") { _ ->
            val terminals = terminalProvider?.listTerminals() ?: emptyList()
            terminals.joinToString("\n") { t ->
                "${t.id}\t${t.title.value}\t${t.cwd.value}\t${if (t.alive.value) "alive" else "dead"}"
            }
        }

        register("tab.current") { _ ->
            val t = terminalProvider?.activeTerminal() ?: return@register "error: no active tab"
            "${t.id}\t${t.title.value}\t${t.cwd.value}"
        }

        register("tab.select") { args ->
            if (args.isEmpty()) return@register "error: tab id required"
            terminalProvider?.selectTerminal(args[0])
            "ok"
        }

        register("tab.new") { _ ->
            val t = terminalProvider?.newTerminal() ?: return@register "error: failed"
            t.id
        }

        register("tab.close") { args ->
            if (args.isEmpty()) return@register "error: tab id required"
            terminalProvider?.closeTerminal(args[0])
            "ok"
        }

        register("input") { args ->
            if (args.isEmpty()) return@register "error: text required"
            val text = args.joinToString(" ")
            terminalProvider?.activeTerminal()?.write(text)
            "ok"
        }

        register("help") { _ ->
            handlers.keys.sorted().joinToString("\n")
        }
    }

    fun register(command: String, handler: CommandHandler) {
        handlers[command] = handler
    }

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress())
                actualPort = serverSocket!!.localPort

                // Write port to socket path file so CLI can discover it
                File(socketPath).writeText(actualPort.toString())

                while (isActive) {
                    val client = serverSocket!!.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    System.err.println("Socket server error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val line = reader.readLine() ?: return
            val parts = line.trim().split(" ", limit = 2)
            val command = parts[0]
            val args = if (parts.size > 1) parts[1].split("\t") else emptyList()

            val handler = handlers[command]
            val response = if (handler != null) {
                try {
                    handler.handle(args)
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
            } else {
                "error: unknown command '$command'. Use 'help' for available commands."
            }

            writer.println(response)
        } catch (e: Exception) {
            // Client error, ignore
        } finally {
            socket.close()
        }
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
        File(socketPath).delete()
    }
}
