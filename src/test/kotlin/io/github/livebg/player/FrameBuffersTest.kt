package io.github.livebg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class FrameBuffersTest {

    private fun BufferedImage.mark(n: Int) = setRGB(0, 0, n)
    private fun BufferedImage.markOf(): Int = getRGB(0, 0) and 0xFFFFFF

    @Test
    fun `frames arrive in order and producer never overwrites the shown frame`() {
        val b = FrameBuffers(4, 4)
        val total = 100
        val producer = thread {
            for (n in 1..total) {
                val img = b.acquire() ?: return@thread
                img.mark(n)
                if (!b.publish(img)) return@thread
            }
        }
        var expected = 1
        var shown: BufferedImage? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (expected <= total && System.nanoTime() < deadline) {
            val frame = b.swap() ?: continue
            if (shown != null) assertNotSame(shown, frame)
            assertEquals(expected, frame.markOf())
            // 显示期间解码线程会继续写别的块；这块的内容不能被改
            Thread.sleep(0, 200_000)
            assertEquals(expected, frame.markOf())
            shown = frame
            expected++
        }
        assertEquals(total + 1, expected)
        producer.join(1000)
    }

    @Test
    fun `producer blocks when nobody consumes (backpressure)`() {
        val b = FrameBuffers(2, 2)
        val produced = AtomicInteger()
        val producer = thread {
            while (true) {
                val img = b.acquire() ?: break
                if (!b.publish(img)) break
                produced.incrementAndGet()
            }
        }
        Thread.sleep(200)
        // 第 1 帧交出去了，第 2 帧写好后卡在 publish 里等
        assertEquals(1, produced.get())
        assertTrue(producer.isAlive)

        b.swap()
        Thread.sleep(100)
        assertEquals(2, produced.get())

        b.close()
        producer.join(1000)
        assertFalse(producer.isAlive)
    }

    @Test
    fun `close wakes up a waiting producer and stops acquire`() {
        val b = FrameBuffers(2, 2)
        b.publish(b.acquire()!!)
        val returned = CountDownLatch(1)
        var result = true
        thread {
            result = b.publish(b.acquire()!!)
            returned.countDown()
        }
        Thread.sleep(100)
        b.close()
        assertTrue(returned.await(1, TimeUnit.SECONDS))
        assertFalse(result)
        assertNull(b.acquire())
    }
}
