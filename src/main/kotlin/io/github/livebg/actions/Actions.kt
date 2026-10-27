package io.github.livebg.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import io.github.livebg.LiveBgBundle
import io.github.livebg.LiveBgService
import io.github.livebg.Notifier
import io.github.livebg.settings.LiveBgConfigurable
import io.github.livebg.settings.LiveBgSettings
import io.github.livebg.settings.SourceKind

private val settings get() = LiveBgSettings.getInstance().state

class ToggleEnabledAction : DumbAwareToggleAction() {
    override fun isSelected(e: AnActionEvent) = settings.enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        settings.enabled = state
        LiveBgService.getInstance().reload()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class TogglePauseAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = LiveBgService.getInstance().togglePause()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = settings.enabled
        e.presentation.text = LiveBgBundle.message(
            if (LiveBgService.getInstance().userPaused) "action.LiveBg.TogglePause.text.resume" else "action.LiveBg.TogglePause.text.pause"
        )
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ChooseVideoAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileChooserDescriptorFactory.singleFile().withTitle(LiveBgBundle.message("chooser.video.title"))
        FileChooser.chooseFile(descriptor, e.project, null) { file ->
            settings.source = SourceKind.VIDEO
            settings.videoPath = file.path
            settings.enabled = true
            LiveBgService.getInstance().reload()
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ChooseFolderAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(LiveBgBundle.message("chooser.folder.title"))
        FileChooser.chooseFile(descriptor, e.project, null) { dir ->
            settings.source = SourceKind.FOLDER
            settings.folderPath = dir.path
            settings.enabled = true
            LiveBgService.getInstance().reload()
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ChooseWebWallpaperAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileChooserDescriptorFactory.singleFileOrDir().withTitle(LiveBgBundle.message("chooser.web.title"))
        FileChooser.chooseFile(descriptor, e.project, null) { file ->
            settings.source = SourceKind.WEB
            settings.webPath = file.path
            settings.enabled = true
            LiveBgService.getInstance().reload()
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class NextBackgroundAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = LiveBgService.getInstance().nextBackground()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = LiveBgService.getInstance().canSwitch
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class PreviousBackgroundAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = LiveBgService.getInstance().previousBackground()

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = LiveBgService.getInstance().canGoBack
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ShowStatsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = Notifier.info(e.project, LiveBgService.getInstance().statusText())

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class OpenSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) =
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, LiveBgConfigurable::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
