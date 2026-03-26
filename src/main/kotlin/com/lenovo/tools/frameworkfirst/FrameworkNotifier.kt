package com.lenovo.tools.frameworkfirst

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object FrameworkNotifier {
    private const val GROUP_ID = "Framework-First"
    private const val TITLE = "Framework-First"
    private const val MAX_CONTENT_LENGTH = 240
    private const val TRUNCATED_SUFFIX = "... See idea.log for full details."

    fun warning(project: Project, content: String) {
        Notification(GROUP_ID, TITLE, sanitize(content), NotificationType.WARNING).notify(project)
    }

    private fun sanitize(content: String): String {
        val normalized = content.trim()
        if (normalized.length <= MAX_CONTENT_LENGTH) {
            return normalized
        }

        val takeLength = (MAX_CONTENT_LENGTH - TRUNCATED_SUFFIX.length).coerceAtLeast(0)
        return normalized.take(takeLength) + TRUNCATED_SUFFIX
    }
}
