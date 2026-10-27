package io.github.livebg

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 项目打开时启动，并给这个项目的窗口登记（编辑器在插件加载前就已经打开的情况，也靠这里补上）。 */
class LiveBgStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { LiveBgService.getInstance().onProjectOpened() }
    }
}
