package com.lenovo.tools.frameworkfirst

import com.android.tools.idea.gradle.project.sync.setup.post.ProjectSetupStep
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class FrameworkOverlaySyncStep : ProjectSetupStep() {
    override fun setUpProject(project: Project) {
        project.service<FrameworkOverlayService>().syncOverlay("gradle-sync")
    }

    override fun invokeOnFailedSync(): Boolean = false
}
