package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

data class FrameworkOverlayConfig(
    val frameworkJar: Path?,
)

object FrameworkOverlayConfigLoader {
    private const val DEFAULT_FRAMEWORK_JAR = ".common_libs/framework.jar"

    fun load(project: Project): FrameworkOverlayConfig {
        val basePath = project.basePath?.let(Path::of)
            ?: return FrameworkOverlayConfig(null)

        val frameworkJar = basePath.resolve(DEFAULT_FRAMEWORK_JAR)
            .normalize()
            .takeIf(Files::isRegularFile)

        return FrameworkOverlayConfig(
            frameworkJar = frameworkJar,
        )
    }
}
