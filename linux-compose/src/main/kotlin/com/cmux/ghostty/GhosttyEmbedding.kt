package com.cmux.ghostty

import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class GhosttyEmbeddingProbe(
    val libraryPath: String?,
    val headerPath: String?,
    val libraryLoaded: Boolean,
    val missingSymbols: List<String>,
    val hasLinuxPlatformEnum: Boolean,
    val requiresGtkGlAreaHandle: Boolean,
    val usableInCompose: Boolean,
    val reason: String
)

object GhosttyEmbedding {
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

        val (lib, loadedFromPath, loadError) = loadLibrary()
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
        val env = System.getenv("CMUX_GHOSTTY_HEADER")?.trim()
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

    private fun loadLibrary(): Triple<NativeLibrary?, String?, String?> {
        val env = System.getenv("CMUX_GHOSTTY_LIB")?.trim()
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

        var firstError: String? = null
        for (candidate in candidates.distinct()) {
            if (looksLikePath(candidate) && !Files.isRegularFile(Paths.get(candidate))) {
                continue
            }
            val loadResult = runCatching { NativeLibrary.getInstance(candidate) }
            val lib = loadResult.getOrNull()
            if (lib != null) {
                return Triple(lib, candidate, null)
            }
            val message = loadResult.exceptionOrNull()?.message?.trim()
            if (!message.isNullOrEmpty() && firstError == null) {
                firstError = "candidate=$candidate error=$message"
            }
        }
        return Triple(null, null, firstError)
    }

    private fun looksLikePath(candidate: String): Boolean {
        return candidate.contains("/") || candidate.startsWith(".")
    }

    private fun readTextSafe(path: Path): String {
        return runCatching { Files.readString(path) }.getOrDefault("")
    }
}
