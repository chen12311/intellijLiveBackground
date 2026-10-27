package io.github.livebg.player

import com.intellij.openapi.diagnostic.logger

/** 在自己的后台线程里不停产出帧，写进 [buffers]。 */
abstract class FrameSource(private val name: String) {

    @Volatile
    var buffers: FrameBuffers? = null
        protected set

    @Volatile
    protected var stopped = false
        private set

    /** 在解码线程上调用；只在不是主动停止时才会调。 */
    var onError: ((String) -> Unit)? = null

    private var thread: Thread? = null

    fun start() {
        thread = Thread({
            try {
                run()
            } catch (e: Throwable) {
                if (!stopped) {
                    LOG.warn("Live Background: $name 出错", e)
                    onError?.invoke(e.message ?: e.toString())
                }
            } finally {
                buffers?.close()
            }
        }, "LiveBg-$name").apply {
            isDaemon = true
            start()
        }
    }

    open fun stop() {
        stopped = true
        buffers?.close()
        thread?.interrupt()
    }

    protected abstract fun run()

    private companion object {
        val LOG = logger<FrameSource>()
    }
}
