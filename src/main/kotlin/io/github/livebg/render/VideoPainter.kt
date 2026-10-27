package io.github.livebg.render

import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import io.github.livebg.player.FramePlayer
import io.github.livebg.player.Stats
import io.github.livebg.settings.LiveBgSettings
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.SwingUtilities

/**
 * 编辑器每填一块不透明的背景色，平台就会调一次这里，在这块区域上叠一层“当前帧”。
 *
 * [component] 是登记时传的根组件（项目窗口的根面板、它的玻璃层，或者 editor.component）。画的时候都按整个窗口来对齐，
 * 所以项目树、分屏的几个编辑器拼起来是同一幅画面，和 IDE 自带背景图的效果一样。
 *
 * [coverBelow]：挂在 IDE 自带的背景画笔组里（见 [IdePainterSlots]）时为 true。自带背景图的画笔排在前面，
 * 已经在这块区域上画过那张图了；先用平台刚填的颜色（还留在 g 上）把这块重新填一遍盖掉，再叠视频，和别处一样。
 */
class VideoPainter(
    private val player: FramePlayer,
    private val texture: FrameTexture,
    private val coverBelow: Boolean = false,
) : AbstractPainter() {

    // 坑 1：AbstractPainter 画完一次就把“需要画”清掉，导致只有第一块区域有画面。只要有帧就一直返回 true。
    override fun needsRepaint(): Boolean = player.currentFrame != null

    override fun executePaint(component: Component, g: Graphics2D) {
        val frame = player.currentFrame ?: return
        val start = System.nanoTime()
        val st = LiveBgSettings.getInstance().state

        val root = SwingUtilities.getRootPane(component) ?: component
        val origin = if (root === component) Point() else SwingUtilities.convertPoint(component, 0, 0, root)
        val dest = st.fill.place(frame.width, frame.height, root.width, root.height)

        var alpha = st.opacity.coerceIn(0, 100) / 100f
        if (PRESERVED_HINT != null && g.getRenderingHint(PRESERVED_HINT) == true) alpha *= PRESERVED_ALPHA_FACTOR

        val image = texture.imageFor(g.deviceConfiguration, frame, player.frameSerial)
        val g2 = g.create() as Graphics2D
        try {
            if (coverBelow) g2.clipBounds?.let(g2::fill)
            g2.composite = AlphaComposite.SrcOver.derive(alpha)
            // 只对显卡副本开双线性；原帧带双线性画到显卡表面上就是坑 4 那条软件路径
            if (image !== frame) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(image, dest.x - origin.x, dest.y - origin.y, dest.width, dest.height, null)
        } finally {
            g2.dispose()
        }
        Stats.painted(System.nanoTime() - start)
    }

    private companion object {
        /** 自带背景对选区、高亮等颜色用 0.65 倍；视频会动，更干扰阅读，压得更低一些。 */
        const val PRESERVED_ALPHA_FACTOR = 0.3f

        /**
         * 平台在填“要保留的颜色”（选区、搜索高亮…）时会打上这个标记。
         * 2025.1 里这个字段是包内可见的，2026.2 是 public 但标着 @Internal，所以用反射读；
         * 读不到（以后改名了）就当没有，不影响播放。
         */
        val PRESERVED_HINT: RenderingHints.Key? = try {
            IdeBackgroundUtil::class.java.getDeclaredField("ADJUST_ALPHA")
                .apply { isAccessible = true }
                .get(null) as? RenderingHints.Key
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) { // InaccessibleObjectException / SecurityException
            null
        }
    }
}
