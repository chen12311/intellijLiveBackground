package io.github.livebg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 用本机真的 ffmpeg 跑一遍解码链路；没装 ffmpeg 就跳过。 */
class FfmpegFrameSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ffmpeg: Path

    @Before
    fun findFfmpeg() {
        ffmpeg = FfmpegLocator.find("").also { assumeNotNull(it) }!!
    }

    /** 用 ffmpeg 自带的测试画面生成一个文件。 */
    private fun make(name: String, input: String, vararg output: String): Path {
        val out = tmp.root.toPath().resolve(name)
        val cmd = listOf(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", input) +
            output + out.toString()
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "生成测试文件失败：$log" }
        return out
    }

    private fun nextFrame(src: FrameSource, timeoutMs: Long = 10_000): BufferedImage {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            src.buffers?.swap()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("等不到下一帧")
    }

    /** 测试图会经过一次 YUV 转换，每个通道允许差几级。 */
    private fun assertColor(expected: Int, actual: Int) {
        val ok = (0..16 step 8).all { shift -> abs((expected shr shift and 0xFF) - (actual shr shift and 0xFF)) <= 4 }
        assertTrue("期望 %06X，实际 %06X".format(expected, actual and 0xFFFFFF), ok)
    }

    @Test(timeout = 30_000)
    fun `still image is decoded once and the decoder stops by itself`() {
        val jpg = make("bg.jpg", "testsrc2=size=1920x1080", "-frames:v", "1")
        val src = FfmpegFrameSource(FfmpegConfig(ffmpeg, jpg, true, 24, 2, 15, 1280, false))
        var error: String? = null
        src.onError = { error = it }
        src.start()
        try {
            val frame = nextFrame(src)
            assertEquals(1280 to 720, frame.width to frame.height)
            // 解码线程交完这一帧就结束，结束时会关掉缓冲
            val deadline = System.currentTimeMillis() + 5_000
            while (src.buffers!!.acquire() != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertNull("解码线程没有自己结束", src.buffers!!.acquire())
            assertNull(src.buffers!!.swap())
            assertNull(error)
        } finally {
            src.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `original size keeps every pixel and colors are in the right channels`() {
        // 左红右蓝，原画、不模糊不压暗：尺寸不变，颜色没有串到别的通道
        val png = make("rb.png", "color=c=0xFF0000:s=200x100[l];color=c=0x0000FF:s=200x100[r];[l][r]hstack", "-frames:v", "1")
        val src = FfmpegFrameSource(FfmpegConfig(ffmpeg, png, true, 24, 0, 0, 0, false))
        var error: String? = null
        src.onError = { error = it }
        src.start()
        try {
            val frame = nextFrame(src)
            assertEquals(400 to 100, frame.width to frame.height)
            assertColor(0xFF0000, frame.getRGB(50, 50))
            assertColor(0x0000FF, frame.getRGB(350, 50))
            assertNull(error)
        } finally {
            src.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `video keeps looping`() {
        // 1 秒、24 帧的视频，读 60 帧说明 -stream_loop 在循环
        val mp4 = make("bg.mp4", "testsrc2=size=640x360:rate=24", "-t", "1", "-pix_fmt", "yuv420p")
        val src = FfmpegFrameSource(FfmpegConfig(ffmpeg, mp4, false, 24, 0, 0, 1280, false))
        var error: String? = null
        src.onError = { error = it }
        src.start()
        try {
            repeat(60) {
                val frame = nextFrame(src)
                assertEquals(640 to 360, frame.width to frame.height)
            }
            assertNull(error)
        } finally {
            src.stop()
        }
    }
}
