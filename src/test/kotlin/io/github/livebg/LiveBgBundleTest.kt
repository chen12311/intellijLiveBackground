package io.github.livebg

import com.intellij.DynamicBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLClassLoader
import java.util.Locale
import java.util.Properties

class LiveBgBundleTest {

    private fun load(name: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream("messages/$name.properties") ?: error("找不到 $name.properties")
        return stream.reader(Charsets.UTF_8).use { Properties().apply { load(it) } }
    }

    private val en = load("LiveBgBundle")
    private val zh = load("LiveBgBundle_zh")

    @Test
    fun `chinese and english have the same keys`() {
        assertEquals("中文缺的键", emptySet<String>(), en.stringPropertyNames() - zh.stringPropertyNames())
        assertEquals("英文缺的键", emptySet<String>(), zh.stringPropertyNames() - en.stringPropertyNames())
    }

    @Test
    fun `both languages take the same parameters`() {
        val param = Regex("""\{\d+}""")
        for (key in en.stringPropertyNames()) {
            val expected = param.findAll(en.getProperty(key)).map { it.value }.toSet()
            val actual = param.findAll(zh.getProperty(key) ?: continue).map { it.value }.toSet()
            assertEquals("$key 的参数", expected, actual)
        }
    }

    @Test
    fun `ide language picks the file`() {
        // 平台按（类加载器, 路径）缓存，第一次查到哪个语言就一直是哪个，所以每个语言换一个新的类加载器
        fun scope(locale: Locale) = DynamicBundle.getResourceBundle(URLClassLoader(arrayOf(), javaClass.classLoader), "messages.LiveBgBundle", locale)
            .getString("scope.window")
        assertEquals("整个窗口", scope(Locale.SIMPLIFIED_CHINESE))
        assertEquals("Whole window", scope(Locale.ENGLISH))
        assertEquals("Whole window", scope(Locale.JAPANESE)) // 没有的语言退回英文
    }

    @Test
    fun `parameters are filled in`() {
        val text = LiveBgBundle.message("error.videoMissing", "/tmp/a.mp4")
        assertTrue(text, text.endsWith("/tmp/a.mp4") && "{0}" !in text)
    }
}
