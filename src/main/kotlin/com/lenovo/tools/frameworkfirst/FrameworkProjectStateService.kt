package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class FrameworkProjectStateService(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var settings: FrameworkProjectSettings = loadSettings()

    fun isEnabled(): Boolean = settings.enabled

    fun settings(): FrameworkProjectSettings = settings

    fun setEnabled(value: Boolean) {
        if (settings.enabled == value) {
            return
        }

        updateSettings(settings.copy(enabled = value))
    }

    fun setCustomFrameworkJar(customFrameworkJar: String?) {
        val normalizedCustomPath = customFrameworkJar
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val updated = settings.copy(
            customFrameworkJar = normalizedCustomPath,
        )
        if (updated == settings) {
            return
        }
        updateSettings(updated)
    }

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    private fun updateSettings(updated: FrameworkProjectSettings) {
        settings = updated
        saveSettings(updated)
        listeners.forEach { listener ->
            runCatching(listener)
        }
    }

    private fun loadSettings(): FrameworkProjectSettings {
        val properties = loadProperties()
        val legacyPathMode = properties.getProperty(projectStateKey(KEY_PATH_MODE))
            ?.let(FrameworkJarPathMode::fromStorageValue)
            ?: FrameworkJarPathMode.AUTO
        val legacyCustomPath = properties.getProperty(projectStateKey(KEY_CUSTOM_FRAMEWORK_JAR))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return FrameworkProjectSettings(
            enabled = properties.getProperty(projectStateKey(KEY_ENABLED))
                ?.toBooleanStrictOrNull()
                ?: true,
            customFrameworkJar = when (legacyPathMode) {
                FrameworkJarPathMode.CUSTOM -> legacyCustomPath
                FrameworkJarPathMode.AUTO -> null
            },
        )
    }

    private fun saveSettings(settings: FrameworkProjectSettings) {
        synchronized(fileLock) {
            val properties = loadProperties()
            properties.setProperty(projectStateKey(KEY_ENABLED), settings.enabled.toString())
            properties.remove(projectStateKey(KEY_PATH_MODE))
            if (settings.customFrameworkJar.isNullOrBlank()) {
                properties.remove(projectStateKey(KEY_CUSTOM_FRAMEWORK_JAR))
            } else {
                properties.setProperty(projectStateKey(KEY_CUSTOM_FRAMEWORK_JAR), settings.customFrameworkJar)
            }

            val stateFile = stateFile()
            Files.createDirectories(stateFile.parent)
            Files.newOutputStream(stateFile).use { output ->
                properties.store(output, "Framework-First project state")
            }
        }
    }

    private fun loadProperties(): Properties {
        synchronized(fileLock) {
            val properties = Properties()
            val stateFile = stateFile()
            if (Files.isRegularFile(stateFile)) {
                Files.newInputStream(stateFile).use(properties::load)
            }
            return properties
        }
    }

    private fun projectStateKey(name: String): String {
        val identity = project.basePath
            ?.let(Path::of)
            ?.normalize()
            ?.toString()
            ?: project.locationHash
        return "${sha256(identity)}.$name"
    }

    private fun stateFile(): Path {
        return Path.of(PathManager.getConfigPath())
            .resolve(CONFIG_DIR_NAME)
            .resolve(PROJECT_STATE_FILE_NAME)
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val CONFIG_DIR_NAME = "Framework-First"
        const val PROJECT_STATE_FILE_NAME = "project-state.properties"
        const val KEY_ENABLED = "enabled"
        const val KEY_PATH_MODE = "pathMode"
        const val KEY_CUSTOM_FRAMEWORK_JAR = "customFrameworkJar"
        val fileLock = Any()
    }
}

data class FrameworkProjectSettings(
    val enabled: Boolean = true,
    val customFrameworkJar: String? = null,
)

enum class FrameworkJarPathMode(val storageValue: String) {
    AUTO("auto"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromStorageValue(value: String): FrameworkJarPathMode {
            return entries.firstOrNull { mode -> mode.storageValue == value } ?: AUTO
        }
    }
}
