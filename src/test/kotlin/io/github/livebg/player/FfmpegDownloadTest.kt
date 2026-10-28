package io.github.livebg.player

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.DigestUtil
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FfmpegDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `picks the line for the platform`() {
        val text = """
            version=9.0.2-1
            url=https://example.com/download/ffmpeg-9.0.2-1/
            macos-arm64=ffmpeg-macos-arm64.zip 3293420 abcdef
            windows-x64=ffmpeg-windows-x64.zip  5000000   123456
        """.trimIndent()
        val asset = FfmpegDownload.parse(text, "windows-x64")!!
        assertEquals("9.0.2-1", asset.version)
        assertEquals("https://example.com/download/ffmpeg-9.0.2-1/ffmpeg-windows-x64.zip", asset.url)
        assertEquals(5_000_000L, asset.size)
        assertEquals("123456", asset.sha256)

        assertNull(FfmpegDownload.parse(text, "linux-arm64"))
        assertNull("没有 version", FfmpegDownload.parse("url=x/\nlinux-x64=a.zip 1 2", "linux-x64"))
        assertNull("少一列", FfmpegDownload.parse("version=1\nurl=x/\nlinux-x64=a.zip 2", "linux-x64"))
    }

    @Test
    fun `this machine has a target`() {
        assertNotNull(FfmpegDownload.target())
    }

    @Test
    fun `installs into a folder named after the version`() {
        assumeFalse(SystemInfo.isWindows) // 拿 shell 脚本冒充 ffmpeg
        val zip = zipWith("#!/bin/sh\necho ffmpeg version test\n")
        val root = tmp.newFolder("root").toPath()

        val exe = FfmpegDownload.install(zip, asset(zip), root)

        assertEquals(root.resolve("1.0-1").resolve("ffmpeg"), exe)
        assertTrue(Files.isExecutable(exe))
        assertEquals("临时文件夹要清掉", listOf("1.0-1"), children(root))
    }

    @Test
    fun `wrong checksum installs nothing`() {
        val zip = zipWith("#!/bin/sh\n")
        val root = tmp.newFolder("root").toPath()

        assertThrows(IOException::class.java) { FfmpegDownload.install(zip, asset(zip, sha256 = "00"), root) }
        assertEquals(emptyList<String>(), children(root))
    }

    @Test
    fun `ffmpeg that does not run installs nothing`() {
        assumeFalse(SystemInfo.isWindows)
        val zip = zipWith("#!/bin/sh\nexit 3\n")
        val root = tmp.newFolder("root").toPath()

        assertThrows(IOException::class.java) { FfmpegDownload.install(zip, asset(zip), root) }
        assertEquals(emptyList<String>(), children(root))
    }

    @Test
    fun `downloads over http, then installs`() {
        assumeFalse(SystemInfo.isWindows)
        val zip = zipWith("#!/bin/sh\necho ffmpeg version test\n")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ffmpeg.zip") { exchange ->
            val bytes = Files.readAllBytes(zip)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/ffmpeg.zip"
            val asset = FfmpegDownload.Asset("1.0-1", url, Files.size(zip), DigestUtil.sha256Hex(Files.readAllBytes(zip)))
            val root = tmp.newFolder("root").toPath()

            val exe = FfmpegDownload.download(asset, null, root)

            assertEquals(root.resolve("1.0-1").resolve("ffmpeg"), exe)
            assertEquals("下载的 zip 也要清掉", listOf("1.0-1"), children(root))
        } finally {
            server.stop(0)
        }
    }

    /** 和 ffmpeg/build.sh 打的包一样：根目录下一个 ffmpeg，加上许可证。 */
    private fun zipWith(script: String): Path {
        val zip = tmp.newFile("ffmpeg.zip").toPath()
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("ffmpeg"))
            out.write(script.toByteArray())
            out.putNextEntry(ZipEntry("LICENSE.txt"))
            out.write("LGPL".toByteArray())
        }
        return zip
    }

    private fun asset(zip: Path, sha256: String = DigestUtil.sha256Hex(Files.readAllBytes(zip))) =
        FfmpegDownload.Asset("1.0-1", "https://example.com/ffmpeg.zip", Files.size(zip), sha256)

    private fun children(dir: Path): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
}
