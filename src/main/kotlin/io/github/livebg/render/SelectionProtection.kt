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
 * 坑 3：选区、搜索高亮会被视频盖住。
 *
 * 平台只有在设置了自带背景图时，才会对这些颜色打“要保留”的标记（判断代码是私有的）。
 * 所以用户没设背景图时，临时设一张 1×1 的透明图（透明度 0、不缩放、贴左上角），
 * 让那段判断生效，[VideoPainter] 看到标记就把透明度压低。
 *
 * 用户自己设了背景图就什么都不做（标记本来就有）。关掉时只撤掉我们自己设的值。
 */
object SelectionProtection {

    private val LOG = logger<SelectionProtection>()

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
