package io.github.livebg.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.util.ui.JBSwingUtilities
import io.github.livebg.player.FramePlayer
import java.awt.Component
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import javax.swing.JLayeredPane
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * 把背景变换登记到两种根组件上：
 *
 * - 项目窗口的根面板（作用范围选「整个窗口」时）：项目树、工具窗口、工具栏、状态栏都会画上视频；
 *   另外挂进窗口自带的背景画笔组，没打开文件时的编辑器空白区也有视频（见 [IdePainterSlots]）。
 * - 每个主编辑器的 editor.component：拖出去的独立编辑器窗口不是项目窗口，靠这个也能播。
 *
 * 坑 2（作用范围）：这个接口把画笔包了一层，画笔拿不到“正在画哪个组件”，没法在画笔里挑组件，
 * 所以范围只能靠登记在哪个根组件上来定。
 *
 * 只在 EDT 上调用。
 */
class BackgroundInstaller(private val parent: Disposable, private val player: FramePlayer) {

    private class Registration(val painter: VideoPainter, val disposable: Disposable)

    private val frames = LinkedHashMap<JRootPane, Registration>()
    private val editors = LinkedHashMap<Editor, Registration>()

    /** 所有画笔共用一份显卡副本，每帧只上传一次。 */
    private val texture = FrameTexture()

    private var wholeWindow = false

    fun installAll(wholeWindow: Boolean) {
        this.wholeWindow = wholeWindow
        syncFrames()
        EditorFactory.getInstance().allEditors.forEach(::install)
    }

    /** 项目打开、关闭后调用：给新的项目窗口登记，撤掉已经关掉的。 */
    fun syncFrames() {
        val wanted = if (wholeWindow) projectRootPanes() else emptySet()
        frames.keys.filter { it !in wanted }.forEach(::uninstallFrame)
        val added = wanted.filter { it !in frames }
        if (added.isEmpty()) return
        added.forEach(::installFrame)
        // 吸顶行的保护得排在窗口的变换后面才剥得掉，所以已经登记的编辑器按新顺序重新登记一遍
        editors.keys.toList().forEach { uninstall(it); install(it) }
    }

    fun install(editor: Editor) {
        if (editor.isDisposed || editor.editorKind != EditorKind.MAIN_EDITOR || editor in editors) return
        val disposable = Disposer.newDisposable(parent, "LiveBg: ${editor.javaClass.simpleName}")
        // 每个根组件一个画笔：平台的辅助对象会挂在画笔的监听列表上，登记撤掉时不会自己摘下来，共用画笔会泄漏
        val painter = VideoPainter(player, texture)
        IdeBackgroundUtil.createTemporaryBackgroundTransform(editor.component, painter, disposable)
        guardStickyLines(editor, disposable)
        editors[editor] = Registration(painter, disposable)
        editor.component.repaint()
    }

    fun uninstall(editor: Editor) {
        val reg = editors.remove(editor) ?: return
        Disposer.dispose(reg.disposable)
        if (!editor.isDisposed) editor.component.repaint()
    }

    fun uninstallAll() {
        frames.keys.toList().forEach(::uninstallFrame)
        editors.keys.toList().forEach(::uninstall)
        texture.flush()
    }

    /** 停止播放后调用，释放显存。 */
    fun releaseTexture() = texture.flush()

    /** 新帧到了：只让正在显示的根组件重绘。在已登记窗口里的编辑器跟着窗口一起重绘，不用单独再叫。 */
    fun onFrame() {
        for ((root, reg) in frames) {
            if (root.isShowing) reg.painter.setNeedsRepaint(true)
        }
        for ((editor, reg) in editors) {
            val c = editor.component
            if (c.isShowing && SwingUtilities.getRootPane(c) !in frames) reg.painter.setNeedsRepaint(true)
        }
    }

    fun anyShowing(): Boolean = frames.keys.any { it.isShowing } || editors.keys.any { it.component.isShowing }

    fun repaintAll() {
        frames.keys.forEach { it.repaint() }
        editors.keys.forEach { it.component.repaint() }
    }

    val editorCount: Int get() = editors.size
    val frameCount: Int get() = frames.size

    private fun installFrame(root: JRootPane) {
        val disposable = Disposer.newDisposable(parent, "LiveBg: frame")
        val painter = VideoPainter(player, texture)
        IdeBackgroundUtil.createTemporaryBackgroundTransform(root, painter, disposable)
        guardPopups(root, disposable)
        // 空编辑器区、Islands 圆角直接向窗口要自带画笔，不走上面的变换；再单独挂一个画笔进去。它不负责通知重绘
        IdePainterSlots.attach(root, VideoPainter(player, texture, coverBelow = true))?.let { Disposer.register(disposable, it) }
        frames[root] = Registration(painter, disposable)
        root.repaint()
    }

    private fun uninstallFrame(root: JRootPane) {
        val reg = frames.remove(root) ?: return
        Disposer.dispose(reg.disposable)
        root.repaint()
    }

    private fun projectRootPanes(): Set<JRootPane> {
        val wm = WindowManager.getInstance()
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .mapNotNullTo(LinkedHashSet()) { wm.getFrame(it)?.rootPane }
    }

    /**
     * 补全列表、提示之类的轻量弹窗也挂在窗口的根面板下面，照样会被画上视频，字就看不清了。
     * 自带背景图不画弹出层，这里照做：紧跟着窗口的变换登记，在弹出层里把那层剥掉。
     */
    private fun guardPopups(root: JRootPane, disposable: Disposable) {
        val remove = JBSwingUtilities.addGlobalCGTransform { c, g ->
            if (isInPopupLayer(root, c)) IdeBackgroundUtil.getOriginalGraphics(g) else g
        }
        Disposer.register(disposable, remove)
    }

    /**
     * 吸顶行（sticky lines）是拿编辑器内容组件画到另一块面板上的，坐标和编辑器对不上，
     * 视频会错位。自带背景遇到这种情况直接不画，这里照做。
     *
     * 变换按登记顺序依次执行，紧跟在这个编辑器（以及它所在窗口）的背景变换后面登记，就能把包上的那层再剥掉。
     */
    private fun guardStickyLines(editor: Editor, disposable: Disposable) {
        val impl = editor as? EditorImpl ?: return
        val content = impl.contentComponent
        val gutter = impl.gutterComponentEx
        val remove = JBSwingUtilities.addGlobalCGTransform { c, g ->
            val skip = (c === content && (impl.isDumb || isStickyLinePainting(impl))) ||
                (c === gutter && isStickyLinePainting(impl))
            if (skip) IdeBackgroundUtil.getOriginalGraphics(g) else g
        }
        Disposer.register(disposable, remove)
    }

    private companion object {
        /** 2025.1 起就有，但标着 @Internal，用反射调；找不到就当没在画吸顶行（最多吸顶行里画面错位）。 */
        val STICKY_PAINTING: MethodHandle? = try {
            MethodHandles.publicLookup().findVirtual(
                EditorImpl::class.java, "isStickyLinePainting", MethodType.methodType(Boolean::class.javaPrimitiveType),
            )
        } catch (_: ReflectiveOperationException) {
            null
        }

        fun isStickyLinePainting(editor: EditorImpl): Boolean =
            STICKY_PAINTING != null && STICKY_PAINTING.invoke(editor) as Boolean

        /** [c] 在 [root] 下面，并且某一层祖先放在弹出层或更高的层里。 */
        fun isInPopupLayer(root: JRootPane, c: Component): Boolean {
            var popup = false
            var child = c
            var p = c.parent
            while (p != null) {
                if (p is JLayeredPane && p.getLayer(child) >= JLayeredPane.POPUP_LAYER) popup = true
                if (p === root) return popup
                child = p
                p = p.parent
            }
            return false
        }
    }
}
