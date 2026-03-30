package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class FrameworkProjectConfigurable(private val project: Project) : SearchableConfigurable {
    private val stateService = project.service<FrameworkProjectStateService>()
    private val overlayService = project.service<FrameworkOverlayService>()
    private val sdkOverlayService = project.service<FrameworkSdkOverlayService>()

    private var panel: JPanel? = null
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var resetButton: JButton
    private lateinit var viewPreferenceComboBox: JComboBox<FrameworkViewPreference>
    private lateinit var warningLabel: JLabel
    private lateinit var statusValueLabel: JLabel
    private lateinit var androidFacetModuleCountValueLabel: JLabel
    private lateinit var overlayModuleCountValueLabel: JLabel
    private lateinit var baseSdkValueLabel: JTextField

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Framework-First"

    override fun createComponent(): JComponent {
        if (panel == null) {
            pathField = TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(
                    project,
                    FileChooserDescriptorFactory.createSingleFileDescriptor("jar"),
                    TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
                )
            }
            resetButton = JButton("Reset")
            viewPreferenceComboBox = JComboBox(FrameworkViewPreference.entries.toTypedArray())
            warningLabel = JLabel().apply {
                foreground = JBColor.ORANGE
                horizontalAlignment = SwingConstants.LEFT
            }
            statusValueLabel = JLabel()
            androidFacetModuleCountValueLabel = JLabel()
            overlayModuleCountValueLabel = JLabel()
            baseSdkValueLabel = readonlyField()

            val pathRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(pathField, BorderLayout.CENTER)
                add(resetButton, BorderLayout.EAST)
            }

            val content = JPanel(GridBagLayout())
            var row = 0
            addInfoRow(content, row++, "Framework Jar Path", pathRow)
            addInfoRow(content, row++, "Code Insight Base", viewPreferenceComboBox)
            content.add(
                warningLabel,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                    insets = Insets(0, 0, 12, 0)
                },
            )
            content.add(
                createInfoGrid(),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    gridwidth = 2
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                },
            )

            panel = JPanel(BorderLayout()).apply {
                add(content, BorderLayout.NORTH)
            }

            pathField.textField.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = updateUiState()
                    override fun removeUpdate(e: DocumentEvent?) = updateUiState()
                    override fun changedUpdate(e: DocumentEvent?) = updateUiState()
                },
            )
            viewPreferenceComboBox.addActionListener {
                updateUiState()
            }
            resetButton.addActionListener {
                pathField.text = autoDetectedText()
                updateUiState()
            }
            reset()
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = stateService.settings()
        return pendingCustomFrameworkJar() != settings.customFrameworkJar ||
            selectedViewPreference() != settings.viewPreference
    }

    override fun apply() {
        val pendingCustomPath = pendingCustomFrameworkJar()
        val resolvedCustomPath = resolveConfiguredPath(pendingCustomPath)

        if (pendingCustomPath != null) {
            val resolved = resolvedCustomPath
                ?: throw ConfigurationException("framework.jar path is invalid.")
            validateFrameworkJar(resolved)
        }

        stateService.setCustomFrameworkJar(pendingCustomPath)
        stateService.setViewPreference(selectedViewPreference())
        if (overlayService.isProjectEnabled()) {
            overlayService.syncOverlay("settings-apply")
        }
        reset()
    }

    override fun reset() {
        val config = FrameworkOverlayConfigLoader.load(project)
        pathField.text = currentFieldText(config)
        viewPreferenceComboBox.selectedItem = config.viewPreference
        updateUiState()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun updateUiState() {
        val config = FrameworkOverlayConfigLoader.load(project)
        val pendingCustomPath = pendingCustomFrameworkJar()
        val resolvedCustomPath = resolveConfiguredPath(pendingCustomPath)
        val appliedViewPreference = config.viewPreference
        val androidFacetModules = FrameworkProjectUtil.androidFacetModules(project)
        resetButton.isEnabled = pendingCustomPath != null

        warningLabel.text = buildWarningMessage(
            config,
            pendingCustomPath,
            resolvedCustomPath,
            appliedViewPreference,
        )
        statusValueLabel.text = buildStatusValue(
            config,
            appliedViewPreference,
        )
        androidFacetModuleCountValueLabel.text = androidFacetModules.size.toString()
        overlayModuleCountValueLabel.text = overlayModuleCount(androidFacetModules, appliedViewPreference).toString()
        baseSdkValueLabel.text = baseSdkNames(androidFacetModules).ifEmpty { "Not resolved" }
    }

    private fun buildStatusValue(
        config: FrameworkOverlayConfig,
        appliedViewPreference: FrameworkViewPreference,
    ): String {
        val enabledPart = if (stateService.isEnabled()) "Enabled" else "Disabled"
        val effectivePath = config.frameworkJar ?: config.autoDetectedFrameworkJar
        val readinessPart = if (effectivePath != null) "Ready" else "Unresolved"
        val modePart = appliedViewPreference.displayName
        return "$enabledPart · $readinessPart · $modePart"
    }

    private fun buildWarningMessage(
        config: FrameworkOverlayConfig,
        pendingCustomPath: String?,
        resolvedCustomPath: Path?,
        appliedViewPreference: FrameworkViewPreference,
    ): String {
        if (pendingCustomPath != null) {
            if (resolvedCustomPath == null) {
                return "Configured framework.jar path is invalid."
            }
            if (!Files.isRegularFile(resolvedCustomPath)) {
                return "Configured framework.jar does not exist."
            }
            val autoDetected = config.autoDetectedFrameworkJar?.normalize()
            if (autoDetected != null && autoDetected != resolvedCustomPath.normalize()) {
                return "Custom path differs from auto-detected path; IDE insight may differ from Gradle compile classpath."
            }
            return modeStatusWarning(config, appliedViewPreference)
        }
        return if (config.autoDetectedFrameworkJar == null) {
            "Auto-detected framework.jar not found."
        } else {
            modeStatusWarning(config, appliedViewPreference)
        }
    }

    private fun modeStatusWarning(
        config: FrameworkOverlayConfig,
        selectedViewPreference: FrameworkViewPreference,
    ): String {
        if (!stateService.isEnabled()) {
            return ""
        }
        if (selectedViewPreference == FrameworkViewPreference.FRAMEWORK_FIRST &&
            config.frameworkJar != null &&
            overlayModuleCount(FrameworkProjectUtil.androidFacetModules(project), selectedViewPreference) == 0
        ) {
            return "Framework implementation overlay is unavailable for the current project."
        }
        return ""
    }

    private fun autoDetectedText(config: FrameworkOverlayConfig = FrameworkOverlayConfigLoader.load(project)): String {
        return config.autoDetectedFrameworkJar?.toString().orEmpty()
    }

    private fun currentFieldText(config: FrameworkOverlayConfig): String {
        return config.configuredCustomFrameworkJar
            ?: config.autoDetectedFrameworkJar?.toString()
            .orEmpty()
    }

    private fun pendingCustomFrameworkJar(): String? {
        val currentText = pathField.text.trim().takeIf(String::isNotEmpty) ?: return null
        val autoDetected = FrameworkOverlayConfigLoader.load(project).autoDetectedFrameworkJar?.normalize()
        val resolved = resolveConfiguredPath(currentText)
        if (autoDetected != null && resolved != null && resolved.normalize() == autoDetected) {
            return null
        }
        if (autoDetected == null && currentText.isBlank()) {
            return null
        }
        return currentText
    }

    private fun selectedViewPreference(): FrameworkViewPreference {
        return (viewPreferenceComboBox.selectedItem as? FrameworkViewPreference) ?: FrameworkViewPreference.SDK_FIRST
    }

    private fun resolveConfiguredPath(rawValue: String?): Path? {
        val value = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val path = runCatching { Path.of(value) }.getOrNull() ?: return null
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            Path.of(project.basePath ?: return null).resolve(path).normalize()
        }
    }

    private fun validateFrameworkJar(path: Path) {
        if (!Files.isRegularFile(path)) {
            throw ConfigurationException("framework.jar does not exist: $path")
        }
        if (!Files.isReadable(path)) {
            throw ConfigurationException("framework.jar is not readable: $path")
        }

        val validationResult = runCatching {
            JarFile(path.toFile()).use { jarFile ->
                val hasAndroidClass = jarFile.entries().asSequence().any { entry ->
                    !entry.isDirectory &&
                        entry.name.startsWith("android/") &&
                        entry.name.endsWith(".class")
                }
                val hasKnownPlatformClass = KNOWN_PLATFORM_CLASSES.any { entryName ->
                    jarFile.getJarEntry(entryName) != null
                }
                hasAndroidClass && hasKnownPlatformClass
            }
        }.getOrElse {
            throw ConfigurationException("framework.jar is not a readable Android framework jar: $path")
        }

        if (!validationResult) {
            throw ConfigurationException("Selected file does not look like an Android framework.jar: $path")
        }
    }

    private fun overlayModuleCount(
        androidFacetModules: List<com.intellij.openapi.module.Module>,
        viewPreference: FrameworkViewPreference,
    ): Int {
        return androidFacetModules.count { module ->
            ModuleRootManager.getInstance(module).sdk?.let { sdk ->
                sdkOverlayService.isUsableManagedOverlaySdk(sdk, viewPreference)
            } == true
        }
    }

    private fun baseSdkNames(androidFacetModules: List<com.intellij.openapi.module.Module>): String {
        return androidFacetModules
            .mapNotNull { module -> ModuleRootManager.getInstance(module).sdk }
            .map(sdkOverlayService::resolveBaseSdk)
            .map { sdk -> sdk.name }
            .distinct()
            .sorted()
            .joinToString(", ")
    }

    private fun createInfoGrid(): JPanel {
        val grid = JPanel(GridBagLayout())
        var row = 0
        addInfoRow(grid, row++, "Status", statusValueLabel)
        addInfoRow(grid, row++, "AndroidFacet Modules", androidFacetModuleCountValueLabel)
        addInfoRow(grid, row++, "Overlay Modules", overlayModuleCountValueLabel)
        addInfoRow(grid, row, "Base SDKs", baseSdkValueLabel)
        return grid
    }

    private fun addInfoRow(
        panel: JPanel,
        row: Int,
        label: String,
        value: JComponent,
    ) {
        val labelComponent = JLabel(label).apply {
            preferredSize = Dimension(JBUI.scale(LABEL_COLUMN_WIDTH), preferredSize.height)
            minimumSize = preferredSize
        }
        panel.add(
            labelComponent,
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 0, 10, 16)
            },
        )
        panel.add(
            value,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 10, 0)
            },
        )
    }

    private fun readonlyField(): JTextField {
        return JTextField().apply {
            isEditable = false
            border = JBUI.Borders.empty()
            horizontalAlignment = SwingConstants.LEFT
        }
    }

    companion object {
        const val ID = "com.lenovo.tools.frameworkfirst.project"
        const val LABEL_COLUMN_WIDTH = 170
        private val KNOWN_PLATFORM_CLASSES = listOf(
            "android/os/Build.class",
            "android/view/View.class",
            "android/provider/Settings.class",
        )

        fun open(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                ShowSettingsUtil.getInstance().editConfigurable(project, FrameworkProjectConfigurable(project))
            }
        }
    }
}
