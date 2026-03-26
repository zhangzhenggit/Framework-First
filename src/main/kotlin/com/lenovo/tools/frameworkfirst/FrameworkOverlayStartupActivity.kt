package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.DumbService

class FrameworkOverlayStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val service = project.service<FrameworkOverlayService>()
        DumbService.getInstance(project).runWhenSmart {
            service.syncOverlay("startup-smart")
        }
    }
}
