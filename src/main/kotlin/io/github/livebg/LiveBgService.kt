package io.github.livebg

import com.intellij.ide.PowerSaveMode
import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFrame
import io.github.livebg.LiveBgBundle.message
import io.github.livebg.player.Carousel
import io.github.livebg.player.FfmpegConfig
import io.github.livebg.player.FfmpegDownload
import io.github.livebg.player.FfmpegFrameSource
import io.github.livebg.player.FfmpegLocator
import io.github.livebg.player.FramePlayer
import io.github.livebg.player.FrameSource
import io.github.livebg.player.MediaFiles
import io.github.livebg.player.Stats
import io.github.livebg.render.BackgroundInstaller
import io.github.livebg.render.PlaceholderBackground
import io.github.livebg.settings.LiveBgSettings
import io.github.livebg.settings.Scope
import io.github.livebg.settings.SourceKind
import io.github.livebg.web.JcefSupport
import io.github.livebg.web.WebConfig
import io.github.livebg.web.WebFrameSource
import io.github.livebg.web.WebWallpaper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/** 总调度：设置 → 帧来源 → 播放器 → 各窗口、编辑器的画笔。只在 EDT 上调用。 */
@Service(Service.Level.APP)
class LiveBgService : Disposable {

    private val player: FramePlayer = FramePlayer(onFrame = { installer.onFrame() }, hasViewers = { installer.anyShowing() })
    private val installer: BackgroundInstaller = BackgroundInstaller(this, player)
    private val carousel = Carousel(onSwitch = ::onCarouselSwitch)

    private var started = false
    private var appActive = true

    /** 当前帧来源用的解码参数；变了才重启 ffmpeg，只改透明度之类的不重启。 */
    private var activeKey: List<Any>? = null

    /** 文件夹轮播里最近一个播不了的文件的错误，全都播不了时拿来报错。 */
    private var lastSkipError = ""

    var userPaused = false
        private set

    private val settings get() = LiveBgSettings.getInstance().state

    fun ensureStarted() {
        if (started) return
        started = true
        val app = ApplicationManager.getApplication()
        appActive = app.isActive
        val bus = app.messageBus.connect(this)
        bus.subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) {
                appActive = true
                updateRunning()
            }

            override fun applicationDeactivated(ideFrame: IdeFrame) {
                appActive = false
                updateRunning()
            }
        })
        bus.subscribe(PowerSaveMode.TOPIC, PowerSaveMode.Listener { updateRunning() })
        bus.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosed(project: Project) {
                app.invokeLater { if (settings.enabled) installer.syncFrames() }
            }
        })
        reload()
    }

    /** 设置改了之后调用。 */
    fun reload() {
        if (!started) return ensureStarted()
        val st = settings
        PlaceholderBackground.update(st.enabled)
        if (!st.enabled) {
            stopPlayback()
            installer.uninstallAll()
            return
        }
        installer.installAll(st.scope == Scope.WINDOW)
        if (st.source == SourceKind.FOLDER) {
            carousel.configure(st.folderPath, st.folderRecursive, st.shuffle, st.rotateMinutes, st.folderCurrent)
                ?.let { return if (st.folderPath.isBlank()) stopPlayback() else fail(it) } // 还没选时不弹提示，见 startSource
            st.folderCurrent = carousel.current.toString()
        }
        val key = sourceKey()
        if (key != activeKey || player.source == null) startSource(key)
        updateRunning()
        installer.repaintAll()
    }

    fun togglePause() {
        userPaused = !userPaused
        updateRunning()
    }

    /** 文件夹轮播：手动换下一个 / 上一个。 */
    fun nextBackground() {
        if (canSwitch) carousel.next()
    }

    fun previousBackground() {
        if (canSwitch) carousel.previous()
    }

    val canSwitch: Boolean get() = settings.enabled && settings.source == SourceKind.FOLDER && carousel.size > 1

    val canGoBack: Boolean get() = canSwitch && carousel.canGoBack

    /** 项目打开后调用：给它的窗口登记。 */
    fun onProjectOpened() {
        if (!started) return ensureStarted()
        if (settings.enabled) installer.syncFrames()
    }

    fun onEditorCreated(editor: Editor) {
        ensureStarted()
        if (settings.enabled) installer.install(editor)
    }

    fun onEditorReleased(editor: Editor) {
        installer.uninstall(editor)
    }

    fun statusText(): String {
        val st = settings
        val s = Stats.last
        val state = when {
            !st.enabled -> "status.state.disabled"
            player.source == null -> "status.state.idle"
            !player.isRunning -> "status.state.paused"
            else -> "status.state.playing"
        }
        val source = when (st.source) {
            SourceKind.VIDEO -> st.videoPath.ifBlank { message("status.source.noVideo") }
            SourceKind.FOLDER -> message("status.source.folder", st.folderPath.ifBlank { message("status.source.folder.none") })
            SourceKind.WEB -> message("status.source.web", st.webPath.ifBlank { message("status.source.folder.none") })
        }
        return listOfNotNull(
            message("status.line", message(state), source),
            carouselStatus(),
            message("status.fps", String.format(Locale.ROOT, "%.1f", s.fps), "${st.fps}"),
            message("status.frameSize", s.frameSize),
            message("status.viewers", "${installer.frameCount}", "${installer.editorCount}"),
            message("status.paints", String.format(Locale.ROOT, "%.0f", s.paintsPerSecond)),
            message("status.paintTime", String.format(Locale.ROOT, "%.1f", s.paintMsPerSecond)),
        ).joinToString("\n")
    }

    override fun dispose() {
        carousel.setRunning(false)
        player.stop()
        installer.uninstallAll()
        PlaceholderBackground.update(false)
    }

    private fun sourceKey(): List<Any> {
        val st = settings
        return when (st.source) {
            SourceKind.VIDEO -> listOf(
                SourceKind.VIDEO, st.videoPath, st.ffmpegPath, st.fps, st.blur, st.dim, st.decodeWidth, st.hwaccel,
            )
            SourceKind.FOLDER -> listOf(
                SourceKind.FOLDER, carousel.current.toString(), st.ffmpegPath, st.fps, st.blur, st.dim, st.decodeWidth, st.hwaccel,
            )
            SourceKind.WEB -> listOf(SourceKind.WEB, st.webPath, st.fps, st.blur, st.dim, st.decodeWidth)
        }
    }

    private fun startSource(key: List<Any>) {
        val st = settings
        val source: FrameSource = when (st.source) {
            SourceKind.VIDEO -> {
                // 还没选视频（刚装好就是这样）：什么都不放，也不弹提示，免得每次启动都弹
                if (st.videoPath.isBlank()) return stopPlayback()
                val video = toPath(st.videoPath)
                if (video == null || !Files.isRegularFile(video)) return fail(message("error.videoMissing", st.videoPath))
                ffmpegSource(video) ?: return
            }
            SourceKind.FOLDER -> ffmpegSource(carousel.current ?: return fail(message("error.folderEmpty", st.folderPath))) ?: return
            SourceKind.WEB -> {
                if (st.webPath.isBlank()) return stopPlayback()
                webSource(toPath(st.webPath) ?: return fail(message("error.web.missing", st.webPath))) ?: return
            }
        }
        source.onError = { message ->
            ApplicationManager.getApplication().invokeLater {
                if (player.source === source) onSourceError(message)
            }
        }
        activeKey = key
        player.start(source, st.fps)
    }

    /** 找不到 ffmpeg 时报错，返回 null。没自己指定路径、又有能下载的版本时，提示里带「下载」按钮。 */
    private fun ffmpegSource(file: Path): FrameSource? {
        val st = settings
        val ffmpeg = FfmpegLocator.find(st.ffmpegPath)
        if (ffmpeg == null) {
            val asset = FfmpegDownload.asset
            when {
                st.ffmpegPath.isNotBlank() || asset == null -> fail(message("error.ffmpegNotFound"))
                FfmpegDownload.isRunning -> stopPlayback() // 下好了会自己 reload
                else -> fail(
                    message("error.ffmpegDownload", StringUtil.formatFileSize(asset.size)),
                    downloadFfmpegAction(message("notification.downloadFfmpeg")),
                )
            }
            return null
        }
        return FfmpegFrameSource(
            FfmpegConfig(ffmpeg, file, MediaFiles.isImage(file), st.fps, st.blur, st.dim, st.decodeWidth, st.hwaccel)
        )
    }

    /** 不能用内嵌浏览器、不是网页壁纸时报错，返回 null。 */
    private fun webSource(path: Path): FrameSource? {
        if (!JcefSupport.isAvailable()) {
            fail(message("error.web.noJcef"))
            return null
        }
        val wallpaper = try {
            WebWallpaper.load(path)
        } catch (e: IOException) {
            fail(e.message ?: e.toString())
            return null
        }
        val st = settings
        // 原画对网页没有意义，按 1:1（1920 宽）出图
        val width = if (st.decodeWidth > 0) st.decodeWidth else WebFrameSource.VIEW_WIDTH
        return WebFrameSource(WebConfig(wallpaper, st.fps, st.blur, st.dim, width))
    }

    private fun onSourceError(error: String) {
        if (settings.source != SourceKind.FOLDER) return fail(message("error.playback", error))
        // 文件夹里有一个播不了：跳过它接着轮，全都播不了才报错（见 onCarouselSwitch）
        LOG.warn("Live Background: 跳过播不了的文件 ${carousel.current}：$error")
        lastSkipError = error
        carousel.skipBroken()
    }

    /** 轮播换到了下一个（到点、手动、或者跳过了播不了的）。 */
    private fun onCarouselSwitch() {
        val st = settings
        val item = carousel.current ?: return fail(
            if (carousel.brokenCount > 0) message("error.allBroken", "${carousel.brokenCount}", lastSkipError)
            else message("error.folderNowEmpty", st.folderPath)
        )
        st.folderCurrent = item.toString()
        startSource(sourceKey())
        updateRunning()
    }

    private fun carouselStatus(): String? {
        if (settings.source != SourceKind.FOLDER || carousel.current == null) return null
        val next = carousel.remainingMillis?.let {
            val seconds = it / 1000
            message("status.carousel.next", "${seconds / 60}", String.format(Locale.ROOT, "%02d", seconds % 60))
        } ?: message("status.carousel.manual")
        val skipped = if (carousel.brokenCount > 0) message("status.carousel.skipped", "${carousel.brokenCount}") else ""
        return message("status.carousel", "${carousel.position}", "${carousel.size}", "${carousel.current?.fileName}", next, skipped)
    }

    private fun fail(message: String, vararg actions: NotificationAction) {
        stopPlayback()
        Notifier.warn(message, *actions)
    }

    /**
     * 后台下载 ffmpeg，下好了接着放；失败了提示里带「重试」。
     * 失败时不 fail()：下载期间可能已经换成了网页壁纸，不能把它停掉。
     */
    private fun downloadFfmpegAction(text: String): NotificationAction = NotificationAction.createSimpleExpiring(text) {
        FfmpegDownload.downloadInBackground(
            onDone = { reload() },
            onError = { Notifier.warn(message("error.ffmpegDownloadFailed", it), downloadFfmpegAction(message("notification.retry"))) },
        )
    }

    private fun stopPlayback() {
        carousel.setRunning(false)
        player.stop()
        installer.releaseTexture()
        activeKey = null
        installer.repaintAll()
    }

    private fun updateRunning() {
        val st = settings
        val run = st.enabled &&
            !userPaused &&
            !(st.pauseWhenInactive && !appActive) &&
            !(st.pauseInPowerSave && PowerSaveMode.isEnabled())
        player.setRunning(run)
        carousel.setRunning(run && st.source == SourceKind.FOLDER && player.source != null)
    }

    private fun toPath(s: String): Path? = try {
        s.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    } catch (_: InvalidPathException) {
        null
    }

    companion object {
        private val LOG = logger<LiveBgService>()

        fun getInstance(): LiveBgService = service()
    }
}
