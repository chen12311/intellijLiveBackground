package io.github.livebg.player

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 三层兜底：设置里填的路径 → PATH → 常见安装位置。
 * mac 上从程序坞启动 IDE 时，PATH 里不一定有 /opt/homebrew/bin，所以第三层不能省。
 */
object FfmpegLocator {

    /** 设置里填了路径但不可执行时返回 null，不偷偷换成别的。 */
    fun find(configured: String): Path? {
        val custom = configured.trim()
        if (custom.isNotEmpty()) return toPath(custom)?.takeIf(::isExecutable)

        val exe = if (SystemInfo.isWindows) "ffmpeg.exe" else "ffmpeg"
        PathEnvironmentVariableUtil.findInPath(exe)?.toPath()?.let { if (isExecutable(it)) return it }

        return candidates().mapNotNull(::toPath).firstOrNull(::isExecutable)
    }

    private fun candidates(): List<String> = when {
        SystemInfo.isMac -> listOf(
            "/opt/homebrew/bin/ffmpeg", // Apple 芯片上的 Homebrew
            "/usr/local/bin/ffmpeg",    // Intel 上的 Homebrew
            "/opt/local/bin/ffmpeg",    // MacPorts
        )
        SystemInfo.isWindows -> {
            val env = System.getenv()
            listOfNotNull(
                env["LOCALAPPDATA"]?.let { "$it\\Microsoft\\WinGet\\Links\\ffmpeg.exe" },
                env["USERPROFILE"]?.let { "$it\\scoop\\shims\\ffmpeg.exe" },
                env["ProgramData"]?.let { "$it\\chocolatey\\bin\\ffmpeg.exe" },
                env["ProgramFiles"]?.let { "$it\\ffmpeg\\bin\\ffmpeg.exe" },
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
            )
        }
        else -> listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/snap/bin/ffmpeg")
    }

    private fun toPath(s: String): Path? = try {
        Path.of(s)
    } catch (_: InvalidPathException) {
        null
    }

    private fun isExecutable(p: Path) = Files.isRegularFile(p) && Files.isExecutable(p)
}
