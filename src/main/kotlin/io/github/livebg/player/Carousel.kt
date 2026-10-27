package io.github.livebg.player

import io.github.livebg.LiveBgBundle.message
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Timer

/**
 * 文件夹轮播：扫出文件夹里的视频和图片，交给 [Playlist] 排顺序，播够设定的时间就换下一个；当前这个变了就叫 [onSwitch]。
 *
 * 只算播放时间：暂停、IDE 失去焦点、省电模式时停表，回来接着数，每个背景都能完整播够一段。
 * 每次换之前重新扫一遍文件夹，往里加的文件不用重新设置就能轮到。只在 EDT 上调用。
 */
class Carousel(private val onSwitch: () -> Unit) {

    private val playlist = Playlist()
    private val clock = PlayClock()
    private val timer = Timer(0) { advance(forward = true) }.apply { isRepeats = false }

    /** 播不了的文件，之后扫文件夹时跳过；改设置时清掉，再给一次机会。 */
    private val broken = HashSet<Path>()

    private var folder: Path? = null
    private var recursive = false
    private var intervalMillis = 0L
    private var running = false

    val current: Path? get() = playlist.current
    val size: Int get() = playlist.items.size

    /** 从 1 开始，没有就是 0。 */
    val position: Int get() = playlist.items.indexOf(current) + 1
    val canGoBack: Boolean get() = playlist.canGoBack
    val brokenCount: Int get() = broken.size

    /** 离下次自动换还有多久（只算播放时间）；不会自动换时为 null。 */
    val remainingMillis: Long?
        get() = if (intervalMillis > 0 && size > 1) (intervalMillis - clock.elapsedMillis).coerceAtLeast(0) else null

    /**
     * 设置改了之后调用，重新扫文件夹。当前这个还在就接着放，计时也接着走；
     * 不在了就换成 [remembered]（上次关 IDE 时在放的），再不行就从头开始。出错返回说明，正常返回 null。
     */
    fun configure(folderText: String, recursive: Boolean, shuffle: Boolean, minutes: Int, remembered: String): String? {
        broken.clear()
        intervalMillis = minutes.coerceAtLeast(0) * 60_000L
        playlist.shuffle = shuffle
        val dir = toPath(folderText) ?: return clear(message("error.folderNotChosen"))
        if (!Files.isDirectory(dir)) return clear(message("error.folderMissing", folderText))
        folder = dir
        this.recursive = recursive
        rescan()
        if (size == 0) return clear(message("error.folderEmpty", dir))
        if (current == null) {
            val last = toPath(remembered)
            if (last == null || !playlist.select(last)) playlist.next()
            clock.reset()
        }
        schedule()
        return null
    }

    fun next() = advance(forward = true)

    fun previous() = advance(forward = false)

    /** 当前这个播不了：记下来以后跳过，换下一个。全都播不了时当前会变成 null。 */
    fun skipBroken() {
        broken.add(current ?: return)
        advance(forward = true)
    }

    /** 播放 / 暂停时调用，计时跟着停走。 */
    fun setRunning(running: Boolean) {
        this.running = running
        schedule()
    }

    private fun advance(forward: Boolean) {
        if (folder == null) return
        val before = current
        rescan()
        if (forward) playlist.next() else playlist.previous()
        if (current != before) {
            clock.reset()
            onSwitch()
        }
        schedule()
    }

    private fun rescan() {
        val dir = folder ?: return
        playlist.update(MediaFiles.scan(dir, recursive).filter { it !in broken })
    }

    private fun schedule() {
        timer.stop()
        val remaining = remainingMillis
        if (!running || remaining == null) {
            clock.pause()
            return
        }
        clock.resume()
        timer.initialDelay = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        timer.start()
    }

    private fun clear(error: String): String {
        folder = null
        playlist.update(emptyList())
        schedule()
        return error
    }

    private fun toPath(s: String): Path? = try {
        s.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    } catch (_: InvalidPathException) {
        null
    }
}

/** 只在播放时走的表，单位毫秒。 */
class PlayClock(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private var total = 0L
    private var since = 0L
    private var ticking = false

    val elapsedMillis: Long get() = total + if (ticking) now() - since else 0

    fun resume() {
        if (ticking) return
        since = now()
        ticking = true
    }

    fun pause() {
        if (!ticking) return
        total += now() - since
        ticking = false
    }

    fun reset() {
        total = 0
        since = now()
    }
}
