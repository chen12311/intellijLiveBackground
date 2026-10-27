package io.github.livebg.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Painter
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import java.awt.Component
import java.lang.reflect.Method
import javax.swing.JRootPane

/**
 * 有几处不走全局变换，而是画的时候直接向窗口要 IDE 自带的背景画笔（withFrameBackground / withEditorBackground）：
 * 没打开文件时的编辑器空白区、Islands 主题的圆角。它们会把我们包上的那层换掉——
 * 设了自带背景图就画那张图，没设就什么都不画。
 *
 * 所以把画笔也挂进窗口自带的那两组画笔里。这两组画笔的类型和增删方法都是包内可见的，只能反射；
 * 2025.1 和 2026.2 一样。找不到就算了，只是这几处没有视频。
 */
internal object IdePainterSlots {

    private val LOG = logger<IdePainterSlots>()

    private val NAMES = listOf(IdeBackgroundUtil.EDITOR_PROP, IdeBackgroundUtil.FRAME_PROP)

    private class Api(val glassPaneClass: Class<*>, val getNamed: Method, val add: Method, val remove: Method)

    private val api: Api? by lazy {
        try {
            val loader = IdeBackgroundUtil::class.java.classLoader
            val glass = Class.forName("com.intellij.openapi.wm.impl.IdeGlassPaneImpl", false, loader)
            val helper = Class.forName("com.intellij.openapi.wm.impl.PainterHelper", false, loader)
            Api(
                glass,
                glass.getMethod("getNamedPainters", String::class.java),
                helper.getDeclaredMethod("addPainter", Painter::class.java, Component::class.java).apply { isAccessible = true },
                helper.getDeclaredMethod("removePainter", Painter::class.java).apply { isAccessible = true },
            )
        } catch (e: ReflectiveOperationException) {
            LOG.warn("Live Background: 找不到 IDE 自带的背景画笔组，空编辑器区和圆角不会有视频", e)
            null
        } catch (e: RuntimeException) { // InaccessibleObjectException / SecurityException
            LOG.warn("Live Background: 找不到 IDE 自带的背景画笔组，空编辑器区和圆角不会有视频", e)
            null
        }
    }

    /** 把 [painter] 挂进 [root] 所在窗口自带的背景画笔组。返回的对象用来摘下；挂不上返回 null。 */
    fun attach(root: JRootPane, painter: Painter): Disposable? {
        val a = api ?: return null
        val glass = root.glassPane
        if (!a.glassPaneClass.isInstance(glass)) return null
        return try {
            val helpers = NAMES.map { a.getNamed.invoke(glass, it) }
            helpers.forEach { a.add.invoke(it, painter, null) }
            Disposable {
                try {
                    helpers.forEach { a.remove.invoke(it, painter) }
                } catch (e: ReflectiveOperationException) {
                    LOG.warn("Live Background: 从 IDE 自带的背景画笔组里摘下画笔失败", e)
                }
            }
        } catch (e: ReflectiveOperationException) {
            LOG.warn("Live Background: 挂进 IDE 自带的背景画笔组失败", e)
            null
        }
    }
}
