package dev.claudechat

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {
    private fun send(project: Project, msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Claude Chat")
            .createNotification(msg, type).notify(project)
    }
    fun info(project: Project, msg: String) = send(project, msg, NotificationType.INFORMATION)
    fun warn(project: Project, msg: String) = send(project, msg, NotificationType.WARNING)
    fun error(project: Project, msg: String) = send(project, msg, NotificationType.ERROR)
}
