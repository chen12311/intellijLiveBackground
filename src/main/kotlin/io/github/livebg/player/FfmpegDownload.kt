package io.github.livebg.player

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import com.intellij.util.io.DigestUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.system.CpuArch
import io.github.livebg.LiveBgBundle.message
import java.io.IOException
import java.io.OutputStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 没装 ffmpeg 时一键下载：插件自己编的精简版（只解码、LGPL、静态链接，见仓库里的 ffmpeg/build.sh），发在 GitHub Release 上。
 * 地址、大小、SHA-256 写在 resources/ffmpeg/downloads.properties 里随插件发布，下载后先核对再用。
 *
 * 装在 JetBrains 各 IDE 共用的数据目录下，按版本分文件夹：升级 IDE、换一个 IDE 都不用重新下。
 */
object FfmpegDownload {

    class Asset(val version: String, val url: String, val size: Long, val sha256: String)

    private val LOG = logger<FfmpegDownload>()
    private val EXE = if (SystemInfo.isWindows) "ffmpeg.exe" else "ffmpeg"
    private val running = AtomicBoolean()

    /** 这台机器能下载的版本；没有（还没发布、Windows on ARM 之类）就只能自己装。 */
    val asset: Asset? by lazy {
        val text = FfmpegDownload::class.java.getResourceAsStream("/ffmpeg/downloads.properties")
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }
        val target = target()
        if (text == null || target == null) null else parse(text, target)
    }

    val isRunning: Boolean get() = running.get()

    /** 下载好的 ffmpeg。优先当前版本；插件换了版本、还没重新下时，接着用旧的。 */
    fun installed(): Path? {
        val root = root()
        asset?.let { a -> root.resolve(a.version).resolve(EXE).takeIf(::isExecutable)?.let { return it } }
        val dirs = try {
            Files.list(root).use { s -> s.filter { !it.fileName.toString().startsWith(".") }.toList() }
        } catch (_: IOException) {
            return null
        }
        return dirs.sortedDescending().map { it.resolve(EXE) }.firstOrNull(::isExecutable)
    }

    /**
     * 后台下载，状态栏里有进度、能取消。成功调 [onDone]，失败调 [onError]，取消了都不调；都在 EDT 上。
     * 已经在下载就什么都不做。
     */
    fun downloadInBackground(onDone: (Path) -> Unit, onError: (String) -> Unit) {
        val asset = asset ?: return
        if (!running.compareAndSet(false, true)) return
        object : Task.Backgroundable(null, message("ffmpeg.download.title"), true) {
            private var exe: Path? = null

            override fun run(indicator: ProgressIndicator) {
                exe = download(asset, indicator)
            }

            override fun onSuccess() {
                exe?.let(onDone)
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Live Background: 下载 ffmpeg 失败", error)
                onError(error.message ?: error.toString())
            }

            override fun onFinished() {
                running.set(false)
            }
        }.queue()
    }

    /** 弹进度框下载（设置页里用）。取消了、或者已经在后台下载，返回 null；失败抛 [IOException]。 */
    fun downloadWithProgress(): Path? {
        val asset = asset ?: return null
        if (!running.compareAndSet(false, true)) return null
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously<Path, IOException>(
                { download(asset, ProgressManager.getInstance().progressIndicator) },
                message("ffmpeg.download.title"), true, null,
            )
        } catch (_: ProcessCanceledException) {
            return null
        } finally {
            running.set(false)
        }
    }

    /** 下载、核对、解压、试跑一遍，返回装好的 ffmpeg。只能在后台线程调用。 */
    internal fun download(asset: Asset, indicator: ProgressIndicator?, root: Path = root()): Path {
        Files.createDirectories(root)
        val zip = Files.createTempFile(root, ".download-", ".zip")
        try {
            // 走 IDE 里设置的代理
            HttpRequests.request(asset.url).productNameAsUserAgent().saveToFile(zip, indicator)
            indicator?.text2 = ""
            return install(zip, asset, root)
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    /**
     * 核对 SHA-256，解压到临时文件夹，试跑通过后整个挪到 root/版本：
     * 半路失败不会留下一个能被 [installed] 找到的坏 ffmpeg。
     */
    internal fun install(zip: Path, asset: Asset, root: Path): Path {
        if (!sha256(zip).equals(asset.sha256, ignoreCase = true)) throw IOException(message("ffmpeg.download.checksum"))
        val staging = Files.createTempDirectory(root, ".install-")
        try {
            Decompressor.Zip(zip).extract(staging)
            val exe = staging.resolve(EXE)
            if (!Files.isRegularFile(exe)) throw IOException(message("ffmpeg.download.broken", "$EXE not found in the archive"))
            NioFiles.setExecutable(exe)
            // 缺系统库、被安全软件拦下之类的，现在就报出来，不等到放视频
            val output = try {
                ExecUtil.execAndGetOutput(GeneralCommandLine(exe.toString(), "-hide_banner", "-version"), 15_000)
            } catch (e: ExecutionException) {
                throw IOException(message("ffmpeg.download.broken", e.message ?: e.toString()), e)
            }
            if (output.isTimeout || output.exitCode != 0) {
                throw IOException(message("ffmpeg.download.broken", output.stderr.ifBlank { "exit code ${output.exitCode}" }))
            }
            val dir = root.resolve(asset.version)
            if (Files.exists(dir)) NioFiles.deleteRecursively(dir) // 上次没装完的
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE)
            return dir.resolve(EXE)
        } finally {
            if (Files.exists(staging)) NioFiles.deleteRecursively(staging)
        }
    }

    /** 每行 `平台=文件名 字节数 sha256`，再加上 version 和下载地址的前缀 url。 */
    internal fun parse(text: String, target: String): Asset? {
        val props = Properties().apply { load(StringReader(text)) }
        val version = props.getProperty("version") ?: return null
        val url = props.getProperty("url") ?: return null
        val parts = props.getProperty(target)?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.size != 3) return null
        val size = parts[1].toLongOrNull() ?: return null
        return Asset(version, url + parts[0], size, parts[2])
    }

    /** 和 ffmpeg/build.sh 的 target 对应。IDE 在 Apple 芯片上用 Rosetta 跑时拿 x64 版，也能用。 */
    internal fun target(): String? {
        val os = when {
            SystemInfo.isMac -> "macos"
            SystemInfo.isWindows -> "windows"
            SystemInfo.isLinux -> "linux"
            else -> return null
        }
        val arch = when {
            CpuArch.isArm64() -> "arm64"
            CpuArch.isIntel64() -> "x64"
            else -> return null
        }
        return "$os-$arch"
    }

    private fun root(): Path = PathManager.getCommonDataPath().resolve("LiveBackground").resolve("ffmpeg")

    private fun sha256(file: Path): String {
        val digest = DigestUtil.sha256()
        Files.newInputStream(file).use { it.transferTo(DigestOutputStream(OutputStream.nullOutputStream(), digest)) }
        return DigestUtil.digestToHash(digest)
    }

    private fun isExecutable(p: Path) = Files.isRegularFile(p) && Files.isExecutable(p)
}
