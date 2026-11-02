package io.github.livebg.render

import io.github.livebg.player.FramePlayer
import io.github.livebg.player.FrameSource
import java.awt.Component
import java.awt.GraphicsConfiguration
import java.awt.Image
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * 各窗口画哪一帧：前台窗口播当前帧，别的窗口定格在离开前台时的那一帧上。
 *
 * 坑 6：开着几个项目窗口时，每来一帧就把每个窗口整个重画一遍——被挡住的、在别的桌面上的也一样（isShowing 照样是 true），
 * 一个窗口整个重画一次就要好几毫秒，几个窗口乘上帧率，EDT 就被占满了，打字都卡。所以每帧只重画前台窗口。
 * 后台窗口自己也会局部重画（状态栏、跑着的控制台…），这时候要是画当前帧，那一小块就和周围对不上，所以给它留一份定格的帧。
 *
 * 只在 EDT 上用。
 */
class WindowFrames(private val player: FramePlayer) {

    private class Still(val source: FrameSource?, val frame: BufferedImage) {
        /** 自己一份显卡副本，不和当前帧抢同一块。 */
        val texture = FrameTexture()
    }

    /** 当前帧的显卡副本，前台窗口共用，每帧只上传一次。 */
    private val live = FrameTexture()

    /** 按根面板记：画笔都按根面板对齐，同一个窗口里的画笔得画同一帧。 */
    private val stills = HashMap<Component, Still>()

    private var lastActive: WeakReference<Window>? = null

    val hasFrame: Boolean get() = player.currentFrame != null

    /** 画 [root] 这个根面板时用的帧：定格的帧，或者当前帧。 */
    fun frameFor(root: Component): BufferedImage? = stills[root]?.frame ?: player.currentFrame

    /** 画到 [gc] 上用的图，[frame] 是 [frameFor] 返回的那一帧。 */
    fun imageFor(root: Component, gc: GraphicsConfiguration, frame: BufferedImage): Image {
        val still = stills[root]
        return if (still != null) still.texture.imageFor(gc, frame, 0) else live.imageFor(gc, frame, player.frameSerial)
    }

    /**
     * 前台窗口：有焦点的窗口，加上它的 owner（对话框、弹窗开在哪个窗口上，那个窗口也算）。
     * IDE 不在前台时（「失去焦点时暂停」关掉了才会走到这里）算最后一个有焦点的。
     * 返回 null 表示还不知道哪个在前台（IDE 一直没拿到过焦点），这时每个窗口都算。
     */
    fun liveWindows(): List<Window>? {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        if (active != null) lastActive = WeakReference(active)
        val w = active ?: lastActive?.get() ?: return null
        return generateSequence(w) { it.owner }.toList()
    }

    fun isLive(c: Component, live: List<Window>?): Boolean = live == null || SwingUtilities.getWindowAncestor(c) in live

    /**
     * 新帧到了，[root] 这个窗口要不要重画。前台窗口每帧都画；后台窗口刚定格（或者换了来源，要重新定格）时画一次，之后不画。
     * 每帧每个根面板只能调一次。
     */
    fun update(root: JRootPane, live: List<Window>?): Boolean {
        if (isLive(root, live)) {
            stills.remove(root)?.texture?.flush()
            return true
        }
        val frame = player.currentFrame ?: return false
        val old = stills[root]
        if (old != null && old.source === player.source) return false
        old?.texture?.flush()
        stills[root] = Still(player.source, copy(frame))
        return true
    }

    /** 扔掉已经不显示的窗口（关掉、最小化）的定格帧。 */
    fun prune() {
        stills.entries.removeIf { (root, still) ->
            (!root.isShowing).also { if (it) still.texture.flush() }
        }
    }

    /** 停止播放、全部卸载时调用，把显存和定格帧都还回去。 */
    fun clear() {
        live.flush()
        stills.values.forEach { it.texture.flush() }
        stills.clear()
    }

    private fun copy(frame: BufferedImage): BufferedImage =
        BufferedImage(frame.colorModel, frame.copyData(null), frame.isAlphaPremultiplied, null)
}
