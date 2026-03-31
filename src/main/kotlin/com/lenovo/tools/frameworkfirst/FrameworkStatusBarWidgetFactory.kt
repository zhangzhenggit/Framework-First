package com.lenovo.tools.frameworkfirst

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingConstants

class FrameworkStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createWidget(project: Project): StatusBarWidget {
        return FrameworkStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    override fun isConfigurable(): Boolean = false

    private companion object {
        const val DISPLAY_NAME = "Framework-First"
        const val WIDGET_ID = "Framework-First.StatusBar"
    }
}

private class FrameworkStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val overlayService = project.service<FrameworkOverlayService>()
    private val stateService = project.service<FrameworkProjectStateService>()
    private val label = JBLabel().apply {
        border = JBUI.Borders.empty(0, 6)
        horizontalAlignment = SwingConstants.CENTER
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var statusBar: StatusBar? = null

    init {
        stateService.addListener(this) {
            refresh()
        }
        overlayService.addListener(this) {
            refresh()
        }
        label.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        showPopup(event)
                    }
                }
            },
        )
        updatePresentation()
    }

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refresh()
    }

    override fun getComponent(): JComponent = label

    override fun dispose() = Unit

    private fun refresh() {
        if (project.isDisposed) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            updatePresentation()
            statusBar?.updateWidget(ID())
        }
    }

    private fun updatePresentation() {
        val config = FrameworkOverlayConfigLoader.load(project)
        val hasAndroidModules = FrameworkProjectUtil.hasAndroidFacetModules(project)
        val widgetState = widgetState(config, hasAndroidModules)
        label.isVisible = true
        label.icon = when (widgetState) {
            WidgetState.ACTIVE -> ENABLED_ICON
            WidgetState.DISABLED -> DISABLED_ICON
            WidgetState.UNAVAILABLE -> UNAVAILABLE_ICON
        }
        label.toolTipText = buildTooltip(
            widgetState = widgetState,
            frameworkJar = config.frameworkJar,
            hasAndroidModules = hasAndroidModules,
            viewPreference = config.viewPreference,
        )
    }

    private fun showPopup(event: MouseEvent) {
        val enabled = stateService.isEnabled()
        val actionText = if (enabled) {
            "Disable for This Project"
        } else {
            "Enable for This Project"
        }
        val actions = DefaultActionGroup().apply {
            add(
                object : AnAction(actionText) {
                    override fun actionPerformed(event: AnActionEvent) {
                        overlayService.setProjectEnabled(!enabled)
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread {
                        return ActionUpdateThread.BGT
                    }
                },
            )
            addSeparator()
            add(
                object : AnAction("Settings") {
                    override fun actionPerformed(event: AnActionEvent) {
                        FrameworkProjectConfigurable.open(project)
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread {
                        return ActionUpdateThread.BGT
                    }
                },
            )
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                actions,
                DataManager.getInstance().getDataContext(label),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .show(RelativePoint(event))
    }

    private fun buildTooltip(
        widgetState: WidgetState,
        frameworkJar: java.nio.file.Path?,
        hasAndroidModules: Boolean,
        viewPreference: FrameworkViewPreference,
    ): String {
        val status = when (widgetState) {
            WidgetState.ACTIVE -> "Enabled"
            WidgetState.DISABLED -> "Disabled"
            WidgetState.UNAVAILABLE -> "Unavailable"
        }
        val issue = when {
            !hasAndroidModules -> " · no AndroidFacet modules"
            frameworkJar == null -> " · framework.jar not found"
            else -> ""
        }
        return "Framework-First · $status · ${viewPreference.displayName}$issue"
    }

    private fun widgetState(
        config: FrameworkOverlayConfig,
        hasAndroidModules: Boolean,
    ): WidgetState {
        return when {
            !stateService.isEnabled() -> WidgetState.DISABLED
            !hasAndroidModules || config.frameworkJar == null -> WidgetState.UNAVAILABLE
            else -> WidgetState.ACTIVE
        }
    }

    private companion object {
        const val WIDGET_ID = "Framework-First.StatusBar"
        val ENABLED_ICON = IconLoader.getIcon("/icons/frameworkStatusBar.svg", FrameworkStatusBarWidgetFactory::class.java)
        val DISABLED_ICON = IconLoader.getIcon("/icons/frameworkStatusBar_off.svg", FrameworkStatusBarWidgetFactory::class.java)
        val UNAVAILABLE_ICON = IconLoader.getIcon("/icons/frameworkStatusBar_unavailable.svg", FrameworkStatusBarWidgetFactory::class.java)
    }

    private enum class WidgetState {
        ACTIVE,
        DISABLED,
        UNAVAILABLE,
    }
}
