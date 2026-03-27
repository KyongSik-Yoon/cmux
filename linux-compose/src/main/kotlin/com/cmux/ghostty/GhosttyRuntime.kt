package com.cmux.ghostty

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

data class GhosttyRuntimeStart(
    val session: GhosttyRuntimeSession?,
    val reason: String
)

class GhosttyRuntimeSession private constructor(
    private val api: GhosttyLib,
    private val app: Pointer,
    private val config: Pointer
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { api.ghostty_app_free(app) }
        runCatching { api.ghostty_config_free(config) }
    }

    companion object {
        private const val GHOSTTY_SUCCESS = 0
        private const val ENV_RESOURCES = "GHOSTTY_RESOURCES_DIR"

        fun start(probe: GhosttyEmbeddingProbe): GhosttyRuntimeStart {
            if (!probe.libraryLoaded || probe.libraryPath == null) {
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty runtime skipped: library unavailable (${probe.reason})"
                )
            }

            val resourcesDir = resolveResourcesDir(probe.headerPath)
            if (resourcesDir != null && System.getenv(ENV_RESOURCES).isNullOrEmpty()) {
                PosixEnv.setenv(ENV_RESOURCES, resourcesDir)
            }

            if (probe.helperLibraryPath != null) {
                runCatching { NativeLibrary.getInstance(probe.helperLibraryPath) }
                    .onFailure { error ->
                        return GhosttyRuntimeStart(
                            session = null,
                            reason = "ghostty runtime helper load failed: ${error.message}"
                        )
                    }
            }

            val api = runCatching {
                Native.load(probe.libraryPath, GhosttyLib::class.java) as GhosttyLib
            }.getOrElse { error ->
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty runtime api load failed: ${error.message}"
                )
            }

            val initCode = runCatching { api.ghostty_init(0, null) }.getOrElse { error ->
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_init threw: ${error.message}"
                )
            }
            if (initCode != GHOSTTY_SUCCESS) {
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_init failed code=$initCode"
                )
            }

            val config = api.ghostty_config_new()
            if (config == null) {
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_config_new returned null"
                )
            }

            runCatching {
                api.ghostty_config_load_default_files(config)
                api.ghostty_config_load_recursive_files(config)
                api.ghostty_config_finalize(config)
            }.onFailure { error ->
                runCatching { api.ghostty_config_free(config) }
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_config_load/finalize failed: ${error.message}"
                )
            }

            runCatching {
                val count = api.ghostty_config_diagnostics_count(config).coerceAtLeast(0)
                repeat(count) { index ->
                    val diag = api.ghostty_config_get_diagnostic(config, index)
                    val message = diag.message?.getString(0)
                    if (!message.isNullOrBlank()) {
                        System.err.println("cmux: ghostty config diagnostic: $message")
                    }
                }
            }

            val runtimeConfig = runCatching {
                GhosttyRuntimeConfig().apply {
                    userdata = Pointer.NULL
                    supports_selection_clipboard = 1
                    wakeup_cb = Pointer.NULL
                    action_cb = Pointer.NULL
                    read_clipboard_cb = Pointer.NULL
                    confirm_read_clipboard_cb = Pointer.NULL
                    write_clipboard_cb = Pointer.NULL
                    close_surface_cb = Pointer.NULL
                    write()
                }
            }.getOrElse { error ->
                runCatching { api.ghostty_config_free(config) }
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty runtime config init failed: ${error.message}"
                )
            }

            val app = runCatching {
                api.ghostty_app_new(runtimeConfig, config)
            }.getOrElse { error ->
                runCatching { api.ghostty_config_free(config) }
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_app_new threw: ${error.message}"
                )
            }

            if (app == null) {
                runCatching { api.ghostty_config_free(config) }
                return GhosttyRuntimeStart(
                    session = null,
                    reason = "ghostty_app_new returned null"
                )
            }

            return GhosttyRuntimeStart(
                session = GhosttyRuntimeSession(api = api, app = app, config = config),
                reason = "ghostty runtime initialized"
            )
        }

        private fun resolveResourcesDir(headerPath: String?): String? {
            if (!System.getenv(ENV_RESOURCES).isNullOrEmpty()) {
                return System.getenv(ENV_RESOURCES)
            }
            if (headerPath.isNullOrBlank()) return null

            val header = Paths.get(headerPath)
            if (!Files.isRegularFile(header)) return null

            val includeDir = header.parent ?: return null
            val installDir = includeDir.parent ?: return null
            val candidate = installDir.resolve("share/ghostty")
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize().toString()
            }
            return null
        }
    }
}

private interface GhosttyLib : Library {
    fun ghostty_init(argc: Long, argv: Pointer?): Int

    fun ghostty_config_new(): Pointer?
    fun ghostty_config_free(config: Pointer)
    fun ghostty_config_load_default_files(config: Pointer)
    fun ghostty_config_load_recursive_files(config: Pointer)
    fun ghostty_config_finalize(config: Pointer)
    fun ghostty_config_diagnostics_count(config: Pointer): Int
    fun ghostty_config_get_diagnostic(config: Pointer, index: Int): GhosttyDiagnostic.ByValue

    fun ghostty_app_new(runtimeConfig: GhosttyRuntimeConfig, config: Pointer): Pointer?
    fun ghostty_app_free(app: Pointer)
}

open class GhosttyRuntimeConfig : Structure() {
    @JvmField var userdata: Pointer? = Pointer.NULL
    @JvmField var supports_selection_clipboard: Byte = 0
    @JvmField var wakeup_cb: Pointer? = Pointer.NULL
    @JvmField var action_cb: Pointer? = Pointer.NULL
    @JvmField var read_clipboard_cb: Pointer? = Pointer.NULL
    @JvmField var confirm_read_clipboard_cb: Pointer? = Pointer.NULL
    @JvmField var write_clipboard_cb: Pointer? = Pointer.NULL
    @JvmField var close_surface_cb: Pointer? = Pointer.NULL

    override fun getFieldOrder(): List<String> = listOf(
        "userdata",
        "supports_selection_clipboard",
        "wakeup_cb",
        "action_cb",
        "read_clipboard_cb",
        "confirm_read_clipboard_cb",
        "write_clipboard_cb",
        "close_surface_cb",
    )
}

open class GhosttyDiagnostic : Structure() {
    @JvmField var message: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("message")

    class ByValue : GhosttyDiagnostic(), Structure.ByValue
}

private object PosixEnv {
    private val libc = Native.load("c", SetenvLib::class.java) as SetenvLib

    fun setenv(name: String, value: String) {
        libc.setenv(name, value, 1)
    }
}

private interface SetenvLib : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
}
