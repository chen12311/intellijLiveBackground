package io.github.livebg.web

import com.intellij.openapi.diagnostic.logger
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 拦下 [WebWallpaper.ORIGIN] 下的请求，从壁纸文件夹里读文件返回；入口网页插上 [shimTag]。
 * 别的网址（有的壁纸从 CDN 加载脚本）照常走网络。
 */
class WallpaperRequestHandler(private val wallpaper: WebWallpaper, private val shimTag: String) : CefRequestHandlerAdapter() {

    override fun getResourceRequestHandler(
        browser: CefBrowser?, frame: CefFrame?, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
        requestInitiator: String?, disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? {
        if (!WebWallpaper.isOurs(request.url)) return null
        return object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler =
                FileHandler()
        }
    }

    /**
     * 继承 Adapter 而不是直接实现接口：2026.2 的接口多了 open / read / skip，Adapter 里的默认实现会退回到
     * 下面这套老方法（processRequest / readResponse），两个版本都能用。
     */
    private inner class FileHandler : CefResourceHandlerAdapter() {
        private var input: InputStream? = null
        private var length = 0L
        private var mime = "text/plain"

        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            val file = wallpaper.fileFor(request.url)
            try {
                if (file != null) open(file)
            } catch (e: IOException) {
                LOG.info("Live Background 网页壁纸：读不了 $file", e)
            }
            if (input == null) LOG.info("Live Background 网页壁纸：找不到 ${request.url}")
            callback.Continue()
            return true
        }

        private fun open(file: Path) {
            mime = mimeType(file)
            if (wallpaper.isEntry(file)) {
                val html = WebShim.inject(Files.readAllBytes(file), shimTag)
                input = ByteArrayInputStream(html)
                length = html.size.toLong()
            } else {
                length = Files.size(file)
                input = Files.newInputStream(file)
            }
        }

        override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
            if (input == null) {
                response.status = 404
                response.statusText = "Not Found"
                response.mimeType = "text/plain"
                responseLength.set(0)
                return
            }
            response.status = 200
            response.statusText = "OK"
            response.mimeType = mime
            response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
            response.setHeaderByName("Cache-Control", "no-cache", true)
            responseLength.set(if (length <= Int.MAX_VALUE) length.toInt() else -1)
        }

        override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback?): Boolean {
            val n = try {
                input?.read(dataOut, 0, bytesToRead) ?: -1
            } catch (e: IOException) {
                LOG.info("Live Background 网页壁纸：读文件出错", e)
                -1
            }
            if (n <= 0) {
                bytesRead.set(0)
                close()
                return false
            }
            bytesRead.set(n)
            return true
        }

        override fun cancel() = close()

        private fun close() {
            try {
                input?.close()
            } catch (_: IOException) {
            }
            input = null
        }
    }

    companion object {
        private val LOG = logger<WallpaperRequestHandler>()

        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html",
            "js" to "text/javascript", "mjs" to "text/javascript",
            "css" to "text/css", "json" to "application/json", "xml" to "application/xml",
            "txt" to "text/plain", "glsl" to "text/plain", "frag" to "text/plain", "vert" to "text/plain",
            "wasm" to "application/wasm",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "svg" to "image/svg+xml", "ico" to "image/x-icon", "bmp" to "image/bmp",
            "mp4" to "video/mp4", "webm" to "video/webm", "ogv" to "video/ogg", "mov" to "video/quicktime",
            "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "wav" to "audio/wav", "m4a" to "audio/mp4",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        )

        internal fun mimeType(file: Path): String =
            MIME[file.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"
    }
}
