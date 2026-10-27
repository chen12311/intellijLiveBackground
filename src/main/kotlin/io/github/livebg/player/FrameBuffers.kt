package io.github.livebg.player

import java.awt.image.BufferedImage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 三块反复使用的帧：一块正在显示（EDT 在画），一块已经解好等着显示，一块解码线程正在写。
 *
 * 解码线程在 [publish] 里等上一帧被显示后才继续，于是不读管道，ffmpeg 就卡在写管道上，
 * 解码速度自然跟着显示速度走，暂停时也不会白白解码。
 */
class FrameBuffers(val width: Int, val height: Int) {

    private val images = Array(3) { BufferedImage(width, height, BufferedImage.TYPE_INT_RGB) }
    private val lock = ReentrantLock()
    private val consumed = lock.newCondition()
    private var shown = -1
    private var ready = -1
    private var closed = false

    /** 解码线程：拿一块空闲的帧来写。永远不阻塞（三块里总有一块既不在显示也不在等待）。 */
    fun acquire(): BufferedImage? = lock.withLock {
        if (closed) return null
        images[(0..2).first { it != shown && it != ready }]
    }

    /** 解码线程：写好了，等上一帧被显示后交出去。返回 false 表示已关闭，应退出。 */
    fun publish(image: BufferedImage): Boolean = lock.withLock {
        while (ready != -1 && !closed) consumed.await()
        if (closed) return false
        ready = images.indexOf(image)
        true
    }

    /** EDT：有新帧就切换过去，返回新的当前帧；没有就返回 null。 */
    fun swap(): BufferedImage? = lock.withLock {
        if (ready == -1) return null
        shown = ready
        ready = -1
        consumed.signal()
        images[shown]
    }

    fun close() = lock.withLock {
        closed = true
        consumed.signalAll()
    }
}
