package io.github.livebg.player

/** P0 用的性能统计，只在 EDT 上读写。每秒滚动一次。 */
object Stats {

    data class Snapshot(val fps: Double, val paintsPerSecond: Double, val paintMsPerSecond: Double, val frameSize: String)

    @Volatile
    var last = Snapshot(0.0, 0.0, 0.0, "-")
        private set

    var frameSize = "-"

    private var frames = 0
    private var paints = 0L
    private var paintNanos = 0L
    private var windowStart = System.nanoTime()

    fun painted(nanos: Long) {
        paints++
        paintNanos += nanos
    }

    fun frameShown() {
        frames++
        val now = System.nanoTime()
        val dt = now - windowStart
        if (dt < 1_000_000_000L) return
        val perSecond = 1e9 / dt
        last = Snapshot(frames * perSecond, paints * perSecond, paintNanos / 1e6 * perSecond, frameSize)
        frames = 0
        paints = 0
        paintNanos = 0
        windowStart = now
    }

    fun reset() {
        last = Snapshot(0.0, 0.0, 0.0, "-")
        frames = 0
        paints = 0
        paintNanos = 0
        windowStart = System.nanoTime()
    }
}
