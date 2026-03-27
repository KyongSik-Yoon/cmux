package com.cmux.ghostty

import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class GhosttyEmbeddingProbe(
    val libraryPath: String?,
    val helperLibraryPath: String?,
    val headerPath: String?,
    val libraryLoaded: Boolean,
    val missingSymbols: List<String>,
    val hasLinuxPlatformEnum: Boolean,
    val requiresGtkGlAreaHandle: Boolean,
    val usableInCompose: Boolean,
    val reason: String
)

object GhosttyEmbedding {
    private const val GHOSTTY_LIB_ENV = "CMUX_GHOSTTY_LIB"
    private const val GHOSTTY_HEADER_ENV = "CMUX_GHOSTTY_HEADER"
    private const val GHOSTTY_GLAD_LIB_ENV = "CMUX_GHOSTTY_GLAD_LIB"

    private val requiredSymbols = listOf(
        "ghostty_init",
        "ghostty_app_new",
        "ghostty_surface_new",
        "ghostty_surface_draw",
        "ghostty_surface_set_size",
    )

    fun probe(): GhosttyEmbeddingProbe {
        val headerPath = resolveHeaderPath()
        val headerText = headerPath?.let(::readTextSafe).orEmpty()
        val hasLinuxPlatformEnum = headerText.contains("GHOSTTY_PLATFORM_LINUX")
        val requiresGtkGlAreaHandle = headerText.contains("ghostty_platform_linux_s") &&
            headerText.contains("gl_area")

        val load = loadLibrary()
        val lib = load.library
        val loadedFromPath = load.libraryPath
        val helperPath = load.helperPath
        val loadError = load.error
        val missing = if (lib != null) {
            requiredSymbols.filterNot { symbol ->
                runCatching { lib.getFunction(symbol) }.isSuccess
            }
        } else {
            requiredSymbols
        }

        val usable = lib != null &&
            missing.isEmpty() &&
            hasLinuxPlatformEnum &&
            !requiresGtkGlAreaHandle

        val reason = when {
            lib == null ->
                "libghostty not loadable (set CMUX_GHOSTTY_LIB or build linux ghostty artifacts)" +
                    loadError?.let { ": $it" }.orEmpty()
            missing.isNotEmpty() ->
                "missing required ghostty symbols: ${missing.joinToString(",")}"
            !hasLinuxPlatformEnum ->
                "ghostty.h does not expose GHOSTTY_PLATFORM_LINUX"
            requiresGtkGlAreaHandle ->
                "current Ghostty Linux ABI requires GtkGLArea handle (Compose host bridge not implemented)"
            else -> "ghostty embedding is available"
        }

        return GhosttyEmbeddingProbe(
            libraryPath = loadedFromPath,
            helperLibraryPath = helperPath,
            headerPath = headerPath?.toAbsolutePath()?.toString(),
            libraryLoaded = lib != null,
            missingSymbols = missing,
            hasLinuxPlatformEnum = hasLinuxPlatformEnum,
            requiresGtkGlAreaHandle = requiresGtkGlAreaHandle,
            usableInCompose = usable,
            reason = reason
        )
    }

    private fun resolveHeaderPath(): Path? {
        val env = System.getenv(GHOSTTY_HEADER_ENV)?.trim()
        if (!env.isNullOrEmpty()) {
            val path = Paths.get(env)
            if (Files.isRegularFile(path)) return path
        }

        val cwd = Paths.get(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            cwd.resolve("linux/target/debug/build")
                .takeIf { Files.isDirectory(it) }
                ?.let { base ->
                    findHeaderUnder(base)
                },
            cwd.resolve("../linux/target/debug/build")
                .takeIf { Files.isDirectory(it) }
                ?.let { base ->
                    findHeaderUnder(base)
                },
            cwd.resolve("ghostty.h"),
            cwd.resolve("../ghostty.h"),
        )
        return candidates.firstOrNull { it != null && Files.isRegularFile(it) }
    }

    private fun findHeaderUnder(base: Path): Path? {
        Files.walk(base).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() == "ghostty.h" }
                .filter { it.toString().contains("ghostty-install/include") }
                .findFirst()
                .orElse(null)
        }
    }

    private fun loadLibrary(): LibraryLoadResult {
        val env = System.getenv(GHOSTTY_LIB_ENV)?.trim()
        val cwd = Paths.get(System.getProperty("user.dir") ?: ".")
        val candidates = mutableListOf<String>()
        if (!env.isNullOrEmpty()) {
            candidates += env
        }
        candidates += listOf(
            cwd.resolve("linux/target/debug/libghostty.so").toString(),
            cwd.resolve("../linux/target/debug/libghostty.so").toString(),
            "ghostty",
        )

        val helperPath = resolveHelperLibraryPath(cwd)
        var firstError: String? = null
        if (helperPath != null) {
            val helperLoad = runCatching { NativeLibrary.getInstance(helperPath) }
            if (helperLoad.isFailure) {
                val helperError = helperLoad.exceptionOrNull()?.message?.trim()
                if (!helperError.isNullOrEmpty()) {
                    firstError = "helper=$helperPath error=$helperError"
                }
            }
        }

        for (candidate in candidates.distinct()) {
            if (looksLikePath(candidate) && !Files.isRegularFile(Paths.get(candidate))) {
                continue
            }
            val loadResult = runCatching { NativeLibrary.getInstance(candidate) }
            val lib = loadResult.getOrNull()
            if (lib != null) {
                return LibraryLoadResult(
                    library = lib,
                    libraryPath = candidate,
                    helperPath = helperPath,
                    error = null
                )
            }
            val message = loadResult.exceptionOrNull()?.message?.trim()
            if (!message.isNullOrEmpty() && firstError == null) {
                firstError = "candidate=$candidate error=$message"
            }
        }
        return LibraryLoadResult(
            library = null,
            libraryPath = null,
            helperPath = helperPath,
            error = firstError
        )
    }

    private fun resolveHelperLibraryPath(cwd: Path): String? {
        val env = System.getenv(GHOSTTY_GLAD_LIB_ENV)?.trim()
        if (!env.isNullOrEmpty() && Files.isRegularFile(Paths.get(env))) {
            return Paths.get(env).toAbsolutePath().toString()
        }

        val candidates = listOf(
            cwd.resolve("build/native/libcmux_glad.so"),
            cwd.resolve("../linux/target/debug/libcmux_glad.so"),
            cwd.resolve("/tmp/cmux-ghostty-host/libcmux_glad.so"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.toString()
    }

    private fun looksLikePath(candidate: String): Boolean {
        return candidate.contains("/") || candidate.startsWith(".")
    }

    private fun readTextSafe(path: Path): String {
        return runCatching { Files.readString(path) }.getOrDefault("")
    }
}

private data class LibraryLoadResult(
    val library: NativeLibrary?,
    val libraryPath: String?,
    val helperPath: String?,
    val error: String?
)
