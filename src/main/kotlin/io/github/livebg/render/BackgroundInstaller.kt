package io.github.livebg.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.util.ui.JBSwingUtilities
import io.github.livebg.player.FramePlayer
import java.awt.Component
import java.awt.Graphics2D
import java.awt.event.HierarchyListener
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * 把背景变换登记到两种根组件上：
 *
 * - 项目窗口的根面板（作用范围选「整个窗口」时）：项目树、工具窗口、工具栏、状态栏都会画上视频；
 *   另外挂进窗口自带的背景画笔组，没打开文件时的编辑器空白区也有视频（见 [IdePainterSlots]）。
 * - 每个主编辑器的 editor.component：作用范围选「只在编辑器」时，以及拖出去的独立编辑器窗口（不是项目窗口）。
 *   已经在登记过的项目窗口里的编辑器不再单独登记，见 [updateEditorTransform]。
 *
 * 坑 2（作用范围）：这个接口把画笔包了一层，画笔拿不到“正在画哪个组件”，没法在画笔里挑组件，
 * 所以范围只能靠登记在哪个根组件上来定。
 *
 * 只在 EDT 上调用。
 */
class BackgroundInstaller(private val parent: Disposable, player: FramePlayer) {

    private class Registration(val painter: VideoPainter, val disposable: Disposable)

    private class EditorRegistration(val painter: VideoPainter, val disposable: Disposable) {
        /** 这个编辑器自己的背景变换；在登记过的项目窗口里时没有。 */
        var transform: Disposable? = null
    }

    private val frames = LinkedHashMap<JRootPane, Registration>()
    private val editors = LinkedHashMap<Editor, EditorRegistration>()

    /** 各窗口画哪一帧：前台窗口播当前帧，后台窗口定格。所有画笔共用。 */
    private val windowFrames = WindowFrames(player)

    /** 弹出层、吸顶行的保护，见 [moveGuardsToEnd]。 */
    private var guards: Disposable? = null

    private var wholeWindow = false

    fun installAll(wholeWindow: Boolean) {
        this.wholeWindow = wholeWindow
        syncFrames()
        EditorFactory.getInstance().allEditors.forEach(::install)
    }

    /** 项目打开、关闭、作用范围变了之后调用：给新的项目窗口登记，撤掉已经关掉的。 */
    fun syncFrames() {
        windowFrames.prune()
        val wanted = if (wholeWindow) projectRootPanes() else emptySet()
        val removed = frames.keys.filter { it !in wanted }
        val added = wanted.filter { it !in frames }
        if (removed.isEmpty() && added.isEmpty()) return
        removed.forEach(::uninstallFrame)
        added.forEach(::installFrame)
        // 编辑器在不在登记过的窗口里跟着变了
        editors.keys.toList().forEach(::updateEditorTransform)
        moveGuardsToEnd()
    }

    fun install(editor: Editor) {
        if (editor.isDisposed || editor.editorKind != EditorKind.MAIN_EDITOR || editor in editors) return
        val disposable = Disposer.newDisposable(parent, "LiveBg: ${editor.javaClass.simpleName}")
        // 每个根组件一个画笔：平台的辅助对象会挂在画笔的监听列表上，登记撤掉时不会自己摘下来，共用画笔会泄漏
        editors[editor] = EditorRegistration(VideoPainter(windowFrames), disposable)
        // 编辑器创建的时候还没放进窗口；放进去、挪到别的窗口（拖出去成独立窗口）时再看要不要自己的变换
        val c = editor.component
        val listener = HierarchyListener { updateEditorTransform(editor) }
        c.addHierarchyListener(listener)
        Disposer.register(disposable) { c.removeHierarchyListener(listener) }
        updateEditorTransform(editor)
    }

    fun uninstall(editor: Editor) {
        val reg = editors.remove(editor) ?: return
        Disposer.dispose(reg.disposable)
        windowFrames.prune()
        if (!editor.isDisposed) editor.component.repaint()
    }

    fun uninstallAll() {
        frames.keys.toList().forEach(::uninstallFrame)
        editors.keys.toList().forEach(::uninstall)
        guards?.let(Disposer::dispose)
        guards = null
        windowFrames.clear()
    }

    /** 停止播放后调用，释放显存。 */
    fun releaseTexture() = windowFrames.clear()

    /**
     * 新帧到了：只让前台窗口重画，后台窗口定格（见 [WindowFrames]）。
     * 在已登记窗口里的编辑器跟着窗口一起重绘，不用单独再叫。
     */
    fun onFrame() {
        val live = windowFrames.liveWindows()
        windowFrames.prune()
        // 同一个窗口里的几个编辑器（作用范围「只在编辑器」）要一起定格，所以每个根面板只问一次
        val decided = HashMap<JRootPane, Boolean>()
        fun shouldRepaint(root: JRootPane) = decided.getOrPut(root) { windowFrames.update(root, live) }

        for ((root, reg) in frames) {
            if (root.isShowing && shouldRepaint(root)) reg.painter.setNeedsRepaint(true)
        }
        for ((editor, reg) in editors) {
            val c = editor.component
            if (!c.isShowing) continue
            val root = SwingUtilities.getRootPane(c) ?: continue
            if (root !in frames && shouldRepaint(root)) reg.painter.setNeedsRepaint(true)
        }
    }

    /** 前台窗口里有没有正在显示的视频；没有就不用换帧，解码也跟着停下。 */
    fun anyLive(): Boolean {
        val live = windowFrames.liveWindows()
        fun Component.isLive() = isShowing && windowFrames.isLive(this, live)
        return frames.keys.any { it.isLive() } || editors.keys.any { it.component.isLive() }
    }

    fun repaintAll() {
        frames.keys.forEach { it.repaint() }
        editors.keys.forEach { it.component.repaint() }
    }

    val editorCount: Int get() = editors.size
    val frameCount: Int get() = frames.size

    private fun installFrame(root: JRootPane) {
        val disposable = Disposer.newDisposable(parent, "LiveBg: frame")
        val painter = VideoPainter(windowFrames)
        IdeBackgroundUtil.createTemporaryBackgroundTransform(root, painter, disposable)
        // 空编辑器区、Islands 圆角直接向窗口要自带画笔，不走上面的变换；再单独挂一个画笔进去。它不负责通知重绘
        IdePainterSlots.attach(root, VideoPainter(windowFrames, coverBelow = true))?.let { Disposer.register(disposable, it) }
        frames[root] = Registration(painter, disposable)
        root.repaint()
    }

    private fun uninstallFrame(root: JRootPane) {
        val reg = frames.remove(root) ?: return
        Disposer.dispose(reg.disposable)
        root.repaint()
    }

    /**
     * 编辑器在登记过的项目窗口里时，窗口的变换已经盖住了它，不再单独给它登记，有就撤掉。
     * 每个背景变换在 IDE 里每画一个组件都要往上找一遍祖先，开着几个项目、几十个编辑器时，
     * 每画一个组件就多找几十遍，整个窗口重画一次能多出好几毫秒。
     * 不在登记过的窗口里（作用范围「只在编辑器」、拖出去的独立编辑器窗口）才自己登记；还没放进窗口时先不管。
     */
    private fun updateEditorTransform(editor: Editor) {
        val reg = editors[editor] ?: return
        val c = editor.component
        val root = SwingUtilities.getRootPane(c) ?: return
        val current = reg.transform
        if (root in frames) {
            if (current == null) return
            Disposer.dispose(current)
            reg.transform = null
        } else {
            if (current != null) return
            val transform = Disposer.newDisposable(reg.disposable, "LiveBg: editor transform")
            IdeBackgroundUtil.createTemporaryBackgroundTransform(c, reg.painter, transform)
            reg.transform = transform
            moveGuardsToEnd()
        }
        c.repaint()
    }

    private fun projectRootPanes(): Set<JRootPane> {
        val wm = WindowManager.getInstance()
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .mapNotNullTo(LinkedHashSet()) { wm.getFrame(it)?.rootPane }
    }

    /**
     * 变换按登记顺序依次执行，保护得排在所有背景变换后面才剥得掉包上的那层，所以每登记一个背景变换就把它挪到最后。
     * 全局只登记这一个，不随窗口、编辑器的数量变多。
     */
    private fun moveGuardsToEnd() {
        guards?.let(Disposer::dispose)
        guards = JBSwingUtilities.addGlobalCGTransform(::guard).also { Disposer.register(parent, it) }
    }

    private fun guard(c: JComponent, g: Graphics2D): Graphics2D {
        val original = IdeBackgroundUtil.getOriginalGraphics(g)
        if (original === g) return g // 没被包上，不用剥
        return if (isStickyLineOrDumb(c) || isInPopupLayer(c)) original else g
    }

    /**
     * 补全列表、提示之类的轻量弹窗也挂在窗口的根面板下面，照样会被画上视频，字就看不清了。
     * 自带背景图不画弹出层，这里照做：[c] 在某个登记过的窗口里、并且某一层祖先放在弹出层或更高的层里，就把那层剥掉。
     */
    private fun isInPopupLayer(c: Component): Boolean {
        if (frames.isEmpty()) return false
        var popup = false
        var child = c
        var p = c.parent
        while (p != null) {
            if (p is JLayeredPane && p.getLayer(child) >= JLayeredPane.POPUP_LAYER) popup = true
            if (p is JRootPane && p in frames) return popup
            child = p
            p = p.parent
        }
        return false
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

        /**
         * 吸顶行（sticky lines）是拿编辑器内容组件画到另一块面板上的，坐标和编辑器对不上，
         * 视频会错位。自带背景遇到这种情况直接不画（还没准备好的编辑器内容也不画），这里照做。
         */
        fun isStickyLineOrDumb(c: JComponent): Boolean = when (c) {
            is EditorComponentImpl -> c.editor.let { it.isDumb || isStickyLinePainting(it) }
            is EditorGutterComponentEx -> (c.editor as? EditorImpl)?.let(::isStickyLinePainting) == true
            else -> false
        }
    }
}
