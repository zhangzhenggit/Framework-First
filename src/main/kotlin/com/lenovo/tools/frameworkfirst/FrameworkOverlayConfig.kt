package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.BiPredicate
import kotlin.io.path.name

data class FrameworkOverlayConfig(
    val frameworkJar: Path?,
    val autoDetectedFrameworkJar: Path?,
    val configuredCustomFrameworkJar: String?,
    val hasCustomFrameworkOverride: Boolean,
    val discoverySource: String?,
    val viewPreference: FrameworkViewPreference,
)

object FrameworkOverlayConfigLoader {
    private val DEFAULT_FRAMEWORK_JAR_CANDIDATES = listOf(
        ".common_libs/framework.jar",
        "common_libs/framework.jar",
    )
    private val EXCLUDED_DIR_NAMES = setOf("build", ".gradle", ".git", ".idea", "out")
    private val GRADLE_FILE_NAMES = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
    private val FRAMEWORK_JAR_REGEX = Regex("""['"]([^'"]*framework\.jar)['"]""")
    private val COMMON_LIBS_REGEX = Regex("""['"]([^'"]*\.common_libs[^'"]*)['"]""")

    fun load(project: Project): FrameworkOverlayConfig {
        val basePath = project.basePath?.let(Path::of)
            ?: return FrameworkOverlayConfig(
                frameworkJar = null,
                autoDetectedFrameworkJar = null,
                configuredCustomFrameworkJar = null,
                hasCustomFrameworkOverride = false,
                discoverySource = null,
                viewPreference = FrameworkViewPreference.SDK_FIRST,
            )
        val state = project.getService(FrameworkProjectStateService::class.java).settings()
        val autoDetectedFrameworkJar = discoverAutoDetected(basePath)
        val customFrameworkJar = resolveCustomFrameworkJar(basePath, state.customFrameworkJar)
        val hasCustomFrameworkOverride = !state.customFrameworkJar.isNullOrBlank()
        val effectiveFrameworkJar = customFrameworkJar ?: autoDetectedFrameworkJar
        val discoverySource = when {
            hasCustomFrameworkOverride && customFrameworkJar != null -> "custom"
            hasCustomFrameworkOverride -> "custom-missing"
            autoDetectedFrameworkJar != null -> autoDetectSource(basePath, autoDetectedFrameworkJar)
            else -> null
        }

        return FrameworkOverlayConfig(
            frameworkJar = effectiveFrameworkJar,
            autoDetectedFrameworkJar = autoDetectedFrameworkJar,
            configuredCustomFrameworkJar = state.customFrameworkJar,
            hasCustomFrameworkOverride = hasCustomFrameworkOverride,
            discoverySource = discoverySource,
            viewPreference = state.viewPreference,
        )
    }

    private fun discoverAutoDetected(basePath: Path): Path? {
        return discoverByDefault(basePath)
            ?: discoverFromGradle(basePath)
            ?: discoverBySearch(basePath)
    }

    private fun autoDetectSource(basePath: Path, frameworkJar: Path): String {
        if (DEFAULT_FRAMEWORK_JAR_CANDIDATES.any { candidate ->
                basePath.resolve(candidate).normalize() == frameworkJar
            }
        ) {
            return "default"
        }
        if (frameworkJar.startsWith(basePath)) {
            val relativePath = runCatching { basePath.relativize(frameworkJar).toString() }.getOrNull()
            if (relativePath?.contains(".common_libs") == true) {
                return "gradle"
            }
        }
        return "search"
    }

    private fun discoverByDefault(basePath: Path): Path? {
        return DEFAULT_FRAMEWORK_JAR_CANDIDATES
            .asSequence()
            .map(basePath::resolve)
            .map(Path::normalize)
            .firstOrNull(Files::isRegularFile)
    }

    private fun discoverFromGradle(basePath: Path): Path? {
        val gradleFiles = mutableListOf<Path>()
        Files.walk(basePath, 4).use { paths ->
            paths.forEach { path ->
                if (Files.isRegularFile(path) &&
                    path.fileName.toString() in GRADLE_FILE_NAMES &&
                    !pathHasExcludedDir(basePath, path)
                ) {
                    gradleFiles.add(path)
                }
            }
        }

        gradleFiles.forEach { gradleFile ->
            val text = runCatching { Files.readString(gradleFile) }.getOrNull() ?: return@forEach

            FRAMEWORK_JAR_REGEX.findAll(text)
                .mapNotNull { match -> resolveCandidate(gradleFile.parent, match.groupValues[1]) }
                .firstOrNull()
                ?.let { return it }

            COMMON_LIBS_REGEX.findAll(text)
                .mapNotNull { match -> resolveCommonLibsCandidate(gradleFile.parent, match.groupValues[1]) }
                .firstOrNull()
                ?.let { return it }
        }

        return null
    }

    private fun discoverBySearch(basePath: Path): Path? {
        val matches = mutableListOf<Path>()
        Files.find(
            basePath,
            4,
            BiPredicate<Path, java.nio.file.attribute.BasicFileAttributes> { path, attrs ->
                attrs.isRegularFile &&
                    path.fileName.toString().equals("framework.jar", ignoreCase = true) &&
                    !pathHasExcludedDir(basePath, path)
            },
        ).use { paths ->
            paths.forEach(matches::add)
        }

        return matches
            .sortedWith(
                compareBy<Path> { candidate ->
                    if (candidate.toString().contains(".common_libs")) 0 else 1
                }.thenBy { candidate ->
                    basePath.relativize(candidate).nameCount
                },
            )
            .firstOrNull()
            ?.normalize()
            ?.takeIf(Files::isRegularFile)
    }

    private fun resolveCustomFrameworkJar(basePath: Path, rawValue: String?): Path? {
        val value = rawValue
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val path = runCatching { Path.of(value) }.getOrNull()
        val candidate = when {
            path == null -> null
            path.isAbsolute -> path
            else -> basePath.resolve(path)
        }?.normalize()
            ?: return null
        return candidate.takeIf(Files::isRegularFile)
    }

    private fun resolveCandidate(ownerDir: Path, rawValue: String): Path? {
        val normalized = rawValue.trim()
            .replace("\\\\", "\\")
            .takeIf(String::isNotEmpty)
            ?: return null

        val candidate = runCatching {
            val path = Path.of(normalized)
            if (path.isAbsolute) {
                path
            } else {
                ownerDir.resolve(path)
            }
        }.getOrNull()?.normalize() ?: return null

        return when {
            Files.isRegularFile(candidate) && candidate.fileName.toString().equals("framework.jar", ignoreCase = true) -> {
                candidate
            }

            Files.isDirectory(candidate) -> {
                candidate.resolve("framework.jar")
                    .normalize()
                    .takeIf(Files::isRegularFile)
            }

            else -> null
        }
    }

    private fun resolveCommonLibsCandidate(ownerDir: Path, rawValue: String): Path? {
        val candidate = resolveCandidate(ownerDir, rawValue)
        if (candidate != null) {
            return candidate
        }

        val normalized = rawValue.trim()
            .replace("\\\\", "\\")
            .takeIf(String::isNotEmpty)
            ?: return null

        val path = runCatching {
            val valuePath = Path.of(normalized)
            if (valuePath.isAbsolute) {
                valuePath
            } else {
                ownerDir.resolve(valuePath)
            }
        }.getOrNull()?.normalize() ?: return null

        return path.resolve("framework.jar")
            .normalize()
            .takeIf(Files::isRegularFile)
    }

    private fun pathHasExcludedDir(basePath: Path, candidate: Path): Boolean {
        val relative = runCatching { basePath.relativize(candidate) }.getOrNull() ?: return true
        return relative.any { segment -> segment.name in EXCLUDED_DIR_NAMES }
    }
}
