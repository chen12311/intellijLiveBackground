package io.github.livebg.player

import java.awt.image.BufferedImage
import javax.swing.Timer

/**
 * 按帧率在 EDT 上切换“当前帧”，切换后通知 [onFrame] 去重绘。
 * 定时器停了（或者 [hasViewers] 说眼下没人看），解码线程就会在 [FrameBuffers.publish] 里等着，ffmpeg 也跟着停下。
 */
class FramePlayer(private val onFrame: () -> Unit, private val hasViewers: () -> Boolean) {

    /** 只在 EDT 上读写。 */
    var currentFrame: BufferedImage? = null
        private set

    /** 每换一帧加一。三块缓冲轮着用，同一个对象会装不同的帧，靠这个判断内容变没变。只在 EDT 上读写。 */
    var frameSerial = 0L
        private set

    var source: FrameSource? = null
        private set

    private var running = false

    /** 换了来源、新来源的第一帧还没到。暂停时也等它到了再停，不然暂停期间换的来源一直显示旧画面。 */
    private var awaitingFirstFrame = false

    private val timer = Timer(40) { tick() }.apply { isCoalesce = true }

    /** 上一个来源的画面先留着，等新来源的第一帧到了再换上，切换时不会闪一下空白。 */
    fun start(newSource: FrameSource, fps: Int) {
        timer.stop()
        source?.stop()
        Stats.reset()
        source = newSource
        awaitingFirstFrame = true
        timer.delay = 1000 / fps.coerceIn(1, 60)
        timer.initialDelay = 0
        newSource.start()
        updateTimer()
    }

    fun stop() {
        timer.stop()
        source?.stop()
        source = null
        currentFrame = null
        awaitingFirstFrame = false
    }

    fun setRunning(running: Boolean) {
        this.running = running
        updateTimer()
    }

    val isRunning: Boolean get() = running && source != null

    private fun updateTimer() {
        if (source != null && (running || awaitingFirstFrame)) timer.start() else timer.stop()
        source?.setActive(running)
    }

    private fun tick() {
        val src = source ?: return
        if (currentFrame != null && !awaitingFirstFrame && !hasViewers()) return
        val frame = src.buffers?.swap() ?: return
        currentFrame = frame
        frameSerial++
        awaitingFirstFrame = false
        Stats.frameSize = "${frame.width}×${frame.height}"
        Stats.frameShown()
        onFrame()
        if (!running) timer.stop()
    }
}
