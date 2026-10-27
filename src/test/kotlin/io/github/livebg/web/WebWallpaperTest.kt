package io.github.livebg.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class WebWallpaperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: Path get() = tmp.root.toPath().toRealPath()

    private fun write(name: String, text: String = ""): Path {
        val p = root.resolve(name)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    @Test
    fun `reads entry and user properties from project json`() {
        write("site/main.html", "<html></html>")
        write(
            "project.json",
            "﻿" + """
            {"file": "site/main.html", "type": "Web", "title": "测试",
             "general": {"properties": {"schemecolor": {"type": "color", "value": "0.1 0.2 0.3"}, "speed": {"value": 40}}}}
            """.trimIndent(),
        )
        val w = WebWallpaper.load(root)
        assertEquals("site/main.html", w.entry)
        assertEquals("0.1 0.2 0.3", w.properties.getAsJsonObject("schemecolor")["value"].asString)
        assertEquals(40, w.properties.getAsJsonObject("speed")["value"].asInt)
        // 选 project.json 本身也一样
        assertEquals("site/main.html", WebWallpaper.load(root.resolve("project.json")).entry)
    }

    @Test
    fun `plain folder with index html or a single html file`() {
        write("a/index.html")
        write("b/page.htm")
        assertEquals("index.html", WebWallpaper.load(root.resolve("a")).entry)
        val single = WebWallpaper.load(root.resolve("b/page.htm"))
        assertEquals("page.htm", single.entry)
        assertEquals(root.resolve("b"), single.root)
        assertTrue(single.properties.entrySet().isEmpty())
    }

    @Test
    fun `other wallpaper types and broken folders are rejected`() {
        write("video/project.json", """{"file": "a.mp4", "type": "video"}""")
        write("missing/project.json", """{"file": "nope.html", "type": "web"}""")
        write("escape/project.json", """{"file": "../outside.html", "type": "web"}""")
        write("outside.html")
        write("broken/project.json", "{not json")
        write("empty/readme.txt")
        for (dir in listOf("video", "missing", "escape", "broken", "empty", "does-not-exist")) {
            assertThrows(dir, IOException::class.java) { WebWallpaper.load(root.resolve(dir)) }
        }
    }

    @Test
    fun `urls map back to files inside the wallpaper only`() {
        write("w/index.html")
        write("w/img/a b.png")
        write("w/中文.js")
        write("secret.txt")
        val w = WebWallpaper.load(root.resolve("w"))

        assertEquals("http://wallpaper.livebg/index.html", w.entryUrl)
        assertTrue(w.isEntry(w.fileFor(w.entryUrl)!!))
        assertEquals(root.resolve("w/img/a b.png"), w.fileFor("http://wallpaper.livebg/img/a%20b.png?v=3#x"))
        assertEquals(root.resolve("w/中文.js"), w.fileFor("http://wallpaper.livebg/%E4%B8%AD%E6%96%87.js"))

        assertNull(w.fileFor("http://wallpaper.livebg/%2e%2e/secret.txt"))
        assertNull(w.fileFor("http://wallpaper.livebg/img/..%2F..%2Fsecret.txt"))
        assertNull(w.fileFor("http://wallpaper.livebg/nope.png"))
        assertNull(w.fileFor("http://wallpaper.livebg/img")) // 文件夹
        assertNull(w.fileFor("https://cdn.example.com/index.html"))
    }

    @Test
    fun `entry url is encoded`() {
        write("w/my page/首页.html")
        write("w/project.json", """{"file": "my page/首页.html", "type": "web"}""")
        val w = WebWallpaper.load(root.resolve("w"))
        assertEquals("http://wallpaper.livebg/my%20page/%E9%A6%96%E9%A1%B5.html", w.entryUrl)
        assertEquals(root.resolve("w/my page/首页.html"), w.fileFor(w.entryUrl))
    }

    @Test
    fun `mime types`() {
        assertEquals("text/javascript", WallpaperRequestHandler.mimeType(Path.of("a/b.JS")))
        assertEquals("application/wasm", WallpaperRequestHandler.mimeType(Path.of("x.wasm")))
        assertEquals("application/octet-stream", WallpaperRequestHandler.mimeType(Path.of("noext")))
    }
}
