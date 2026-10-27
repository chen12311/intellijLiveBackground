package io.github.livebg.render

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import io.github.livebg.LiveBgService

class EditorHook : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        onEdt { if (!editor.isDisposed) LiveBgService.getInstance().onEditorCreated(editor) }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        onEdt { serviceIfCreated<LiveBgService>()?.onEditorReleased(editor) }
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action)
    }
}
