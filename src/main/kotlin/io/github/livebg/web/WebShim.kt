package io.github.livebg.web

import com.google.gson.JsonObject
import java.util.Locale

/** 往入口网页里插 Wallpaper Engine 接口的模拟（resources/web/livebg-shim.js）和这次的设置。 */
object WebShim {

    /** 中文注释也转成 \uXXXX（在注释里无所谓），插进去的全是 ASCII。 */
    private val SCRIPT: String by lazy {
        val source = WebShim::class.java.getResourceAsStream("/web/livebg-shim.js")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        escape(source, html = false)
    }

    /**
     * 要插进 <head> 的两段 <script>。[blur] 是解码后的像素，[scale] 是每个 CSS 像素对应几个解码像素，
     * 两个都是 0 时不盖滤镜层。
     */
    fun tag(properties: JsonObject, fps: Int, blur: Int, dimPercent: Int, scale: Double): String {
        val filter = buildList {
            if (blur > 0) add(String.format(Locale.ROOT, "blur(%.2fpx)", blur / scale))
            if (dimPercent > 0) add(String.format(Locale.ROOT, "brightness(%.3f)", 1 - dimPercent / 100.0))
        }.joinToString(" ")
        val config = JsonObject().apply {
            add("properties", properties)
            addProperty("fps", fps)
            addProperty("filter", filter)
        }
        return "<script>window.__livebgConfig=${scriptSafe(config.toString())};</script><script>$SCRIPT</script>"
    }

    /**
     * 把 [tag] 插进网页：<head> 后面；没有就 <html> 后面；再没有就 <!doctype> 后面（插到它前面页面会变成怪异模式）。
     * 按 ISO-8859-1 读写，一个字节一个字符，网页原来是什么编码都原样保留；插进去的都是 ASCII。
     */
    fun inject(html: ByteArray, tag: String): ByteArray {
        val text = String(html, Charsets.ISO_8859_1)
        val at = listOf(HEAD, HTML, DOCTYPE).firstNotNullOfOrNull { it.find(text) }?.range?.last?.plus(1)
            ?: if (text.startsWith(UTF8_BOM)) UTF8_BOM.length else 0
        return (text.substring(0, at) + tag + text.substring(at)).toByteArray(Charsets.ISO_8859_1)
    }

    /** JSON 放进 <script>：非 ASCII 和 < > & 都转成 \uXXXX，网页什么编码都不会乱，也不会提前碰上 </script>。 */
    internal fun scriptSafe(json: String): String = escape(json, html = true)

    private fun escape(s: String, html: Boolean): String = buildString(s.length) {
        for (c in s) {
            if (c.code > 0x7E || html && (c == '<' || c == '>' || c == '&')) {
                append(String.format(Locale.ROOT, "\\u%04x", c.code))
            } else {
                append(c)
            }
        }
    }

    private val HEAD = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val HTML = Regex("""<html(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("""<!doctype[^>]*>""", RegexOption.IGNORE_CASE)

    /** UTF-8 的 BOM 按 ISO-8859-1 读出来的样子。 */
    private const val UTF8_BOM = "ï»¿"
}
