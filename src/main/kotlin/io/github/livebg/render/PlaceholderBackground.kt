package io.github.livebg.render

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * 坑 5：Islands 主题默认按项目给窗口画渐变色（「区分项目」，默认开）。没设自带背景图时，它把岛（项目树、编辑器、
 * 没打开文件时的空白区…）里的背景变换全剥掉，视频只剩岛之间的缝里能看见。设了背景图就不画渐变，也不剥。
 *
 * 所以插件开着、用户又没设背景图时，临时设一张 1×1 的透明图（透明度 0、不缩放、贴左上角）。
 * 用户自己设了背景图就什么都不做。关掉时只撤掉我们自己设的值。
 */
object PlaceholderBackground {

    private val LOG = logger<PlaceholderBackground>()

    private val png: Path get() = PathManager.getSystemDir().resolve("live-background").resolve("clear-1x1.png")

    fun update(enable: Boolean) {
        val props = PropertiesComponent.getInstance()
        val current = props.getValue(IdeBackgroundUtil.EDITOR_PROP)
        val ours = current != null && current.startsWith(png.toString())
        when {
            enable && current.isNullOrEmpty() -> {
                if (!writePng()) return
                props.setValue(IdeBackgroundUtil.EDITOR_PROP, "$png,0,plain,top_left")
                IdeBackgroundUtil.repaintAllWindows()
            }
            !enable && ours -> {
                props.unsetValue(IdeBackgroundUtil.EDITOR_PROP)
                IdeBackgroundUtil.repaintAllWindows()
            }
        }
    }

    private fun writePng(): Boolean = try {
        if (!Files.exists(png)) {
            Files.createDirectories(png.parent)
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", png.toFile())
        }
        true
    } catch (e: IOException) {
        LOG.warn("Live Background: 写透明图失败", e)
        false
    }
}
