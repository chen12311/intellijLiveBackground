package io.github.livebg.player

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import io.github.livebg.LiveBgBundle.message
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 用 `ffmpeg -i` 的输出拿视频尺寸，不额外依赖 ffprobe。 */
object VideoProbe {

    data class Size(val width: Int, val height: Int)

    private val VIDEO_LINE = Regex("""Stream #.*?Video:.*""")
    private val SIZE = Regex("""(?<![0-9A-Za-z])(\d{2,5})x(\d{2,5})(?![0-9])""")
    private val ROTATION = Regex("""rotation of (-?\d+(?:\.\d+)?) degrees""")

    /** 只能在后台线程调用。 */
    fun probe(ffmpeg: Path, video: Path): Size {
        val cmd = GeneralCommandLine(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", video.toString())
            .withCharset(StandardCharsets.UTF_8)
        // 没有指定输出，ffmpeg 会以非 0 退出，这是正常的，信息在 stderr 里
        val output = ExecUtil.execAndGetOutput(cmd, 15_000)
        if (output.isTimeout) throw IOException(message("error.probeTimeout"))
        return parse(output.stderr) ?: throw IOException(message("error.probeSize", output.stderr.lines().takeLast(5).joinToString("\n")))
    }

    fun parse(ffmpegInfo: String): Size? {
        val line = VIDEO_LINE.findAll(ffmpegInfo).map { it.value }.firstOrNull { "attached pic" !in it } ?: return null
        val m = SIZE.find(line) ?: return null
        var w = m.groupValues[1].toInt()
        var h = m.groupValues[2].toInt()
        // 手机拍的竖屏视频：ffmpeg 默认会按旋转信息转正，输出的宽高要对调
        val rotation = ROTATION.find(ffmpegInfo)?.groupValues?.get(1)?.toDouble()?.roundToInt()
        if (rotation != null && abs(rotation) % 180 == 90) w = h.also { h = w }
        return Size(w, h)
    }

    /** 等比缩小到长边不超过 [maxSide]（≤ 0 = 原画，不缩小），不放大；宽高取偶数。 */
    fun fit(src: Size, maxSide: Int): Size {
        val scale = if (maxSide <= 0) 1.0 else min(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        fun even(v: Double) = maxOf(2, (v / 2).toInt() * 2)
        return Size(even(src.width * scale), even(src.height * scale))
    }
}
