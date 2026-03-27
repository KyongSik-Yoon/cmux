package com.cmux.terminal

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.lang.reflect.Field

/**
 * Native PTY interface via JNA.
 * Provides forkpty/execvp for spawning shell processes with a pseudo-terminal.
 */
interface CLib : Library {
    companion object {
        val INSTANCE: CLib = Native.load("c", CLib::class.java)
    }

    fun forkpty(masterFd: IntByReference, name: ByteArray?, termp: Pointer?, winp: Pointer?): Int
    fun execvp(file: String, argv: Array<String>): Int
    fun read(fd: Int, buf: ByteArray, count: Int): Int
    fun write(fd: Int, buf: ByteArray, count: Int): Int
    fun close(fd: Int): Int
    fun waitpid(pid: Int, status: IntByReference?, options: Int): Int
    fun kill(pid: Int, sig: Int): Int
    fun setsid(): Int
    fun ioctl(fd: Int, request: Long, vararg args: Any): Int
}

interface UtilLib : Library {
    companion object {
        val INSTANCE: UtilLib = Native.load("util", UtilLib::class.java)
    }

    fun forkpty(masterFd: IntByReference, name: ByteArray?, termp: Pointer?, winp: Pointer?): Int
}

/**
 * winsize struct for ioctl TIOCSWINSZ
 */
class Winsize : Structure() {
    @JvmField var ws_row: Short = 24
    @JvmField var ws_col: Short = 80
    @JvmField var ws_xpixel: Short = 0
    @JvmField var ws_ypixel: Short = 0

    override fun getFieldOrder(): List<String> =
        listOf("ws_row", "ws_col", "ws_xpixel", "ws_ypixel")
}

// TIOCSWINSZ ioctl number for Linux
private const val TIOCSWINSZ: Long = 0x5414

class PtyProcess private constructor(
    val masterFd: Int,
    val pid: Int
) {
    var running = true
        private set

    companion object {
        /**
         * Fork a new PTY process running the user's shell.
         */
        fun spawn(
            shell: String = System.getenv("SHELL") ?: "/bin/bash",
            rows: Int = 24,
            cols: Int = 80,
            env: Map<String, String> = emptyMap()
        ): PtyProcess {
            val masterFdRef = IntByReference()

            // Set initial window size
            val ws = Winsize()
            ws.ws_row = rows.toShort()
            ws.ws_col = cols.toShort()
            ws.write()

            val pid = try {
                UtilLib.INSTANCE.forkpty(masterFdRef, null, null, ws.pointer)
            } catch (e: UnsatisfiedLinkError) {
                CLib.INSTANCE.forkpty(masterFdRef, null, null, ws.pointer)
            }

            if (pid < 0) {
                throw RuntimeException("forkpty failed: ${Native.getLastError()}")
            }

            if (pid == 0) {
                // Child process
                val envVars = System.getenv().toMutableMap()
                envVars["TERM"] = "xterm-256color"
                envVars["COLORTERM"] = "truecolor"
                envVars["CMUX"] = "1"
                envVars.putAll(env)

                for ((k, v) in envVars) {
                    NativeSetenv.setenv(k, v)
                }

                CLib.INSTANCE.execvp(shell, arrayOf(shell, "-l"))
                // If execvp returns, it failed
                System.exit(1)
            }

            return PtyProcess(masterFdRef.value, pid)
        }
    }

    fun resize(rows: Int, cols: Int) {
        val ws = Winsize()
        ws.ws_row = rows.toShort()
        ws.ws_col = cols.toShort()
        ws.write()
        CLib.INSTANCE.ioctl(masterFd, TIOCSWINSZ, ws.pointer)
    }

    fun write(data: ByteArray) {
        if (!running) return
        CLib.INSTANCE.write(masterFd, data, data.size)
    }

    fun write(text: String) = write(text.toByteArray())

    fun read(buf: ByteArray): Int {
        if (!running) return -1
        return CLib.INSTANCE.read(masterFd, buf, buf.size)
    }

    fun destroy() {
        running = false
        CLib.INSTANCE.kill(pid, 9) // SIGKILL
        CLib.INSTANCE.close(masterFd)
        val status = IntByReference()
        CLib.INSTANCE.waitpid(pid, status, 0)
    }

    fun checkAlive(): Boolean {
        if (!running) return false
        val status = IntByReference()
        val result = CLib.INSTANCE.waitpid(pid, status, 1) // WNOHANG
        if (result == pid) {
            running = false
            return false
        }
        return true
    }
}

private object NativeSetenv {
    private val clib = Native.load("c", SetenvLib::class.java)

    interface SetenvLib : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    fun setenv(name: String, value: String) {
        clib.setenv(name, value, 1)
    }
}
