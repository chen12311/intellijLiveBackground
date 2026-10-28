package io.github.livebg.player

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import io.github.livebg.LiveBgBundle.message
import java.awt.image.DataBufferInt
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

class FfmpegConfig(
    val ffmpeg: Path,
    val video: Path,
    /** 静态图：只解一帧，交出去就结束，之后不再占 CPU。 */
    val still: Boolean,
    val fps: Int,
    val blur: Int,
    val dimPercent: Int,
    /** 解码分辨率的长边上限；≤ 0 = 原画，不缩小。 */
    val maxSide: Int,
    val hwaccel: Boolean,
)

/**
 * 视频文件 → ffmpeg 子进程（解码、缩放、模糊、压暗）→ 管道输出 bgr0 原始像素 → 三缓冲。
 * bgr0 每像素 4 字节 B G R 0，按小端读成 int 就是 TYPE_INT_RGB 的 0x00RRGGBB，直接写进图片的像素数组。
 *
 * 不用 bgr24 + TYPE_3BYTE_BGR：Metal 没有 3 字节的纹理格式，每帧传显卡时要先在 EDT 上转一遍。
 * 实测（M2 Max）每帧上传 1920×1080 3.5 → 1.2 ms，3840×2160 10.8 → 4.5 ms。
 */
class FfmpegFrameSource(private val cfg: FfmpegConfig) : FrameSource("ffmpeg") {

    @Volatile
    private var process: Process? = null

    override fun run() {
        val src = VideoProbe.probe(cfg.ffmpeg, cfg.video)
        val size = VideoProbe.fit(src, cfg.maxSide)
        if (stopped) return
        val b = FrameBuffers(size.width, size.height).also { buffers = it }

        val proc = GeneralCommandLine(command(src, size)).withCharset(StandardCharsets.UTF_8).createProcess()
        process = proc
        if (stopped) proc.destroy()
        val stderr = StderrTail(proc).also { it.start() }

        val chunk = ByteArray(minOf(size.width * size.height, CHUNK_PIXELS) * 4)
        val chunkInts = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        try {
            DataInputStream(proc.inputStream).use { input ->
                while (!stopped) {
                    val img = b.acquire() ?: break
                    // 直接写进图片自己的像素数组。拿了数组 Java2D 就不再自动缓存这张图到显卡，
                    // 反正 FrameTexture 每帧都自己上传，实测上传耗时一样
                    readFrame(input, chunk, chunkInts, (img.raster.dataBuffer as DataBufferInt).data)
                    if (!b.publish(img) || cfg.still) break
                }
            }
        } catch (e: IOException) {
            if (stopped) return
            val exited = proc.waitFor(2, TimeUnit.SECONDS)
            val reason = if (e is EOFException) message("error.ffmpegEnded") else message("error.ffmpegRead", "${e.message}")
            val code = if (exited) message("error.exitCode", "${proc.exitValue()}") else ""
            throw IOException("$reason$code\n${stderr.text()}", e)
        }
    }

    override fun stop() {
        super.stop()
        process?.let { p ->
            p.destroy()
            p.onExit().orTimeout(2, TimeUnit.SECONDS).exceptionally { p.destroyForcibly(); null }
        }
    }

    /** 一块一块读，免得 4K、原画时再多占一整帧大小的字节数组。 */
    private fun readFrame(input: DataInputStream, chunk: ByteArray, chunkInts: IntBuffer, pixels: IntArray) {
        var off = 0
        while (off < pixels.size) {
            val n = minOf(chunk.size / 4, pixels.size - off)
            input.readFully(chunk, 0, n * 4)
            chunkInts.get(0, pixels, off, n)
            off += n
        }
    }

    /** [src] 是视频原本的尺寸，[size] 是要输出的尺寸；一样（原画、视频本来就不大）就不缩放。 */
    internal fun command(src: VideoProbe.Size, size: VideoProbe.Size): List<String> {
        val filters = buildList {
            if (!cfg.still) add("fps=${cfg.fps}")
            if (size != src) add("scale=${size.width}:${size.height}:flags=bilinear")
            // 和 boxblur=半径:1 一样的方框模糊（两者 PSNR 53 dB），但更省：4K 下 ffmpeg 从约 0.8 核降到 0.5 核
            if (cfg.blur > 0) add("avgblur=sizeX=${cfg.blur}")
            if (cfg.dimPercent > 0) add(dimFilter(1 - cfg.dimPercent / 100.0))
        }.joinToString(",")

        return buildList {
            add(cfg.ffmpeg.toString())
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostdin"))
            if (cfg.hwaccel && SystemInfo.isMac && !cfg.still) addAll(listOf("-hwaccel", "videotoolbox"))
            if (!cfg.still) addAll(listOf("-stream_loop", "-1"))
            addAll(listOf("-i", cfg.video.toString()))
            addAll(listOf("-an", "-sn", "-dn"))
            if (filters.isNotEmpty()) addAll(listOf("-vf", filters))
            if (cfg.still) addAll(listOf("-frames:v", "1"))
            addAll(listOf("-f", "rawvideo", "-pix_fmt", "bgr0", "pipe:1"))
        }
    }

    private companion object {
        /** 每次从管道读多少像素（×4 字节 = 1 MB）。 */
        const val CHUNK_PIXELS = 1 shl 18

        /**
         * RGB 乘以 [k]，和网页壁纸的 CSS brightness() 一样。在 YUV 上做：黑点和灰点不动，离它们的距离乘 k，
         * 换算回 RGB 正好是乘 k；minval / maxval 按位深和 limited / full range 取值，10 bit、jpg 都对。
         * 不用 eq：它要 --enable-gpl，插件下载的 LGPL 版 ffmpeg 里没有。lutrgb 结果一样（PSNR 48 dB），
         * 但要先转成 RGB，4K 下 CPU 翻倍；这个和 eq 一样省。
         */
        fun dimFilter(k: Double): String {
            val mid = "(minval+maxval)/2"
            val chroma = String.format(Locale.ROOT, "$mid+(val-$mid)*%.3f", k)
            return String.format(Locale.ROOT, "lutyuv=y=minval+(val-minval)*%.3f:u=%s:v=%s", k, chroma, chroma)
        }
    }

    /** 一直读 stderr，免得缓冲区写满把 ffmpeg 卡住；留最后几行用来报错。 */
    private class StderrTail(private val proc: Process) : Thread("LiveBg-ffmpeg-stderr") {
        private val lines = ArrayDeque<String>()

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    synchronized(lines) {
                        lines.addLast(line)
                        if (lines.size > 8) lines.removeFirst()
                    }
                }
            } catch (_: IOException) {
            }
        }

        fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    }
}
