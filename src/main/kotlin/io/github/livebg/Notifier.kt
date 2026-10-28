package io.github.livebg

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.livebg.settings.LiveBgConfigurable

object Notifier {

    /** [actions] 排在「打开设置」前面。 */
    fun warn(message: String, vararg actions: NotificationAction) {
        group().createNotification("Live Background", toHtml(message), NotificationType.WARNING)
            .apply { actions.forEach { addAction(it) } }
            .addAction(NotificationAction.createSimpleExpiring(LiveBgBundle.message("notification.openSettings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(null, LiveBgConfigurable::class.java)
            })
            .notify(null)
    }

    fun info(project: Project?, message: String) {
        group().createNotification("Live Background", toHtml(message), NotificationType.INFORMATION).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Live Background")

    private fun toHtml(text: String) = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
}
