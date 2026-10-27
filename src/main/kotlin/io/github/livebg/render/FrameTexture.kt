package io.github.livebg.render

import java.awt.AlphaComposite
import java.awt.GraphicsConfiguration
import java.awt.Image
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.awt.image.VolatileImage

/**
 * 当前帧在显卡上的副本，所有编辑器的画笔共用。只在 EDT 上用。
 *
 * 坑 4：刚改写过的 BufferedImage 带双线性缩放直接画到 Metal 表面上，会退回软件路径——
 * 实测每次约 250 ms、分配约 135 MB，一秒两三次就把 EDT 占满，堆也跟着狂涨。
 * 所以每帧先 1:1 传到同尺寸的 VolatileImage（约 1 ms），之后的缩放、透明度都在显卡上做
 * （每块区域不到 0.1 ms，不分配内存）。每帧、每块屏幕只传一次。
 */
class FrameTexture {

    private class Entry(var image: VolatileImage, var serial: Long)

    private val cache = HashMap<GraphicsConfiguration, Entry>()

    /**
     * 返回画到 [gc] 上用的图：显卡副本，尺寸和 [frame] 一样。
     * [serial] 变了说明 [frame] 的内容换了，要重新上传。
     * 目标不是显卡表面（比如画进 BufferedImage）或者显卡副本这会儿用不了，就返回 [frame] 本身。
     */
    fun imageFor(gc: GraphicsConfiguration, frame: BufferedImage, serial: Long): Image {
        if (!gc.imageCapabilities.isAccelerated) return frame
        var e = cache[gc]
        if (e == null || e.image.width != frame.width || e.image.height != frame.height) {
            e?.image?.flush()
            e = Entry(gc.createCompatibleVolatileImage(frame.width, frame.height, Transparency.OPAQUE), -1)
            cache[gc] = e
        }
        var status = e.image.validate(gc)
        if (status == VolatileImage.IMAGE_INCOMPATIBLE) {
            e.image.flush()
            e.image = gc.createCompatibleVolatileImage(frame.width, frame.height, Transparency.OPAQUE)
            status = e.image.validate(gc)
        }
        if (status != VolatileImage.IMAGE_OK || e.serial != serial) {
            upload(frame, e.image)
            e.serial = serial
        }
        return if (e.image.contentsLost()) frame else e.image
    }

    /** 停止播放、全部卸载时调用，把显存还回去。 */
    fun flush() {
        cache.values.forEach { it.image.flush() }
        cache.clear()
    }

    private fun upload(frame: BufferedImage, target: VolatileImage) {
        val g = target.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.drawImage(frame, 0, 0, null)
        } finally {
            g.dispose()
        }
    }
}
