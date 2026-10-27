package io.github.livebg.web

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefOSRHandlerFactory
import io.github.livebg.LiveBgBundle.message
import io.github.livebg.player.FrameBuffers
import io.github.livebg.player.FrameSource
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefDragData
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.DataBufferInt
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

class WebConfig(
    val wallpaper: WebWallpaper,
    val fps: Int,
    val blur: Int,
    val dimPercent: Int,
    /** 输出宽度（像素）；页面始终按 1920×1080 的 CSS 像素排版，缩放到这个宽度。 */
    val width: Int,
)

/**
 * 网页壁纸 → IDE 自带的 Chromium（JCEF）离屏渲染 → 每画完一帧在 onPaint 里拷进三缓冲，之后和视频走同一条路。
 *
 * 和 ffmpeg 不一样，浏览器自己按帧率出帧，onPaint 在浏览器的线程上调、不能卡住，所以交帧用 [FrameBuffers.offer]：
 * 上一帧还没显示就顶掉。暂停时把浏览器帧率降到 1，再告诉页面暂停（Wallpaper Engine 的 setPaused）。
 *
 * 后台线程只管生命周期：在 EDT 上建好浏览器后一直等，直到停止或者页面加载失败，失败照常从 [onError] 报出去。
 */
class WebFrameSource(private val cfg: WebConfig) : FrameSource("web") {

    /** 每个 CSS 像素对应几个输出像素。 */
    private val scale = cfg.width.toDouble() / VIEW_WIDTH

    private val done = CountDownLatch(1)

    @Volatile
    private var fatal: String? = null

    @Volatile
    private var active = true

    /** 只在 EDT 上读写。 */
    private var browser: JBCefBrowser? = null

    private val consoleLines = AtomicInteger()

    override fun run() {
        ApplicationManager.getApplication().invokeAndWait({ if (!stopped) browser = createBrowser() }, ModalityState.any())
        done.await()
        fatal?.let { throw IOException(it) }
    }

    /** EDT。 */
    override fun stop() {
        super.stop()
        done.countDown()
        browser?.let(Disposer::dispose)
        browser = null
    }

    /** EDT。 */
    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        browser?.cefBrowser?.let(::applyActive)
    }

    private fun applyActive(cef: CefBrowser) {
        cef.setWindowlessFrameRate(if (active) cfg.fps else PAUSED_FPS)
        cef.executeJavaScript("window.__livebgSetPaused && window.__livebgSetPaused(${!active});", "", 0)
    }

    private fun createBrowser(): JBCefBrowser {
        val renderHandler = RenderHandler()
        val b = JBCefBrowser.createBuilder()
            .setOffScreenRendering(true)
            .setCreateImmediately(true)
            .setWindowlessFramerate(if (active) cfg.fps else PAUSED_FPS)
            .setOSRHandlerFactory(object : JBCefOSRHandlerFactory {
                override fun createCefRenderHandler(component: JComponent): CefRenderHandler = renderHandler
            })
            .build()
        val shim = WebShim.tag(cfg.wallpaper.properties, cfg.fps, cfg.blur, cfg.dimPercent, scale)
        b.jbCefClient
            .addRequestHandler(WallpaperRequestHandler(cfg.wallpaper, shim), b.cefBrowser)
            .addLoadHandler(LoadHandler(), b.cefBrowser)
            .addDisplayHandler(ConsoleLogger(), b.cefBrowser)
        b.loadURL(cfg.wallpaper.entryUrl)
        return b
    }

    /** 浏览器的线程。整帧拷过来：onPaint 每次给的都是完整画面（BGRA），小端读成 int 正好是 0xAARRGGBB。 */
    private fun onFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (stopped) return
        val b = buffers?.takeIf { it.width == width && it.height == height } ?: FrameBuffers(width, height).also { buffers = it }
        val img = b.acquire() ?: return
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(0, pixels, 0, minOf(pixels.size, buffer.capacity() / 4))
        b.offer(img)
    }

    private fun fail(error: String) {
        if (stopped) return
        fatal = error
        done.countDown()
    }

    /** 视口固定 1920×1080 CSS 像素，按 [scale] 出图。不画弹出层（下拉框之类），不管鼠标。 */
    private inner class RenderHandler : CefRenderHandler {
        private val view = Rectangle(0, 0, VIEW_WIDTH, VIEW_HEIGHT)

        override fun getViewRect(browser: CefBrowser?) = view

        override fun getScreenInfo(browser: CefBrowser?, screenInfo: CefScreenInfo): Boolean {
            screenInfo.Set(scale, 32, 8, false, view, view)
            return true
        }

        override fun getScreenPoint(browser: CefBrowser?, viewPoint: Point) = viewPoint

        override fun getDeviceScaleFactor(browser: CefBrowser?) = scale

        override fun onPopupShow(browser: CefBrowser?, show: Boolean) {}

        override fun onPopupSize(browser: CefBrowser?, size: Rectangle?) {}

        override fun onPaint(
            browser: CefBrowser?, popup: Boolean, dirtyRects: Array<out Rectangle>?, buffer: ByteBuffer, width: Int, height: Int,
        ) {
            if (!popup) onFrame(buffer, width, height)
        }

        override fun onCursorChange(browser: CefBrowser?, cursorType: Int) = true

        override fun startDragging(browser: CefBrowser?, dragData: CefDragData?, mask: Int, x: Int, y: Int) = false

        override fun updateDragCursor(browser: CefBrowser?, operation: Int) {}
    }

    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
            // 建浏览器之前就暂停了的话，页面的脚本这时才有，再告诉它一次
            if (frame.isMain && !active) applyActive(browser)
        }

        override fun onLoadError(
            browser: CefBrowser?, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String?, failedUrl: String?,
        ) {
            if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                fail(message("error.web.load", errorText ?: errorCode.name, failedUrl ?: ""))
            }
        }
    }

    /** 页面的报错写进 idea.log，壁纸显示不对时看这里。只记警告和错误，最多 [MAX_CONSOLE_LINES] 行，免得每帧打日志的壁纸刷屏。 */
    private inner class ConsoleLogger : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser?, level: CefSettings.LogSeverity, message: String?, source: String?, line: Int,
        ): Boolean {
            if (level != CefSettings.LogSeverity.LOGSEVERITY_WARNING && level != CefSettings.LogSeverity.LOGSEVERITY_ERROR) return false
            if (consoleLines.incrementAndGet() > MAX_CONSOLE_LINES) return false
            LOG.info("Live Background 网页壁纸 [$level] $message（$source:$line）")
            return false
        }
    }

    companion object {
        private val LOG = logger<WebFrameSource>()

        const val VIEW_WIDTH = 1920
        const val VIEW_HEIGHT = 1080
        private const val PAUSED_FPS = 1
        private const val MAX_CONSOLE_LINES = 50
    }
}

/**
 * 能不能用内嵌浏览器。单独放一个类：2026.2 起 JCEF 是单独的内置插件，可能被禁用，
 * 那时一加载 [WebFrameSource] 就会找不到类，没机会在里面 catch。
 */
object JcefSupport {
    fun isAvailable(): Boolean = try {
        JBCefApp.isSupported()
    } catch (_: LinkageError) {
        false
    }
}
