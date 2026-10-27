package io.github.livebg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.random.Random

class PlaylistTest {

    private val a = Path.of("/bg/a.mp4")
    private val b = Path.of("/bg/b.mp4")
    private val c = Path.of("/bg/c.jpg")
    private val d = Path.of("/bg/d.webm")

    private fun playlist(vararg items: Path, shuffle: Boolean = false, seed: Int = 1) =
        Playlist(Random(seed)).apply {
            this.shuffle = shuffle
            update(items.toList())
        }

    @Test
    fun `in order wraps around both ways`() {
        val p = playlist(a, b, c)
        assertEquals(listOf(a, b, c, a), List(4) { p.next() })
        assertEquals(listOf(c, b, a, c), List(4) { p.previous() })
    }

    @Test
    fun `rescan keeps the current one`() {
        val p = playlist(a, b, c)
        p.next()
        p.next()
        p.update(listOf(a, b, c, d))
        assertEquals(b, p.current)
        assertEquals(c, p.next())
    }

    @Test
    fun `current one removed - continue with the one after it, not from the start`() {
        val p = playlist(a, b, c, d)
        p.select(b)
        p.update(listOf(a, d))
        assertNull(p.current)
        assertEquals(d, p.next())
    }

    @Test
    fun `select only accepts listed items`() {
        val p = playlist(a, b)
        assertFalse(p.select(c))
        assertTrue(p.select(b))
        assertEquals(b, p.current)
    }

    @Test
    fun `shuffle plays every item once per round and never repeats across rounds`() {
        val items = List(20) { Path.of("/bg/$it.mp4") }
        for (seed in 0 until 50) {
            val p = Playlist(Random(seed)).apply { shuffle = true; update(items) }
            var last: Path? = null
            repeat(5) {
                val round = List(items.size) { p.next()!! }
                assertEquals(items.toSet(), round.toSet())
                assertNotEquals(last, round.first())
                last = round.last()
            }
        }
    }

    @Test
    fun `shuffle previous walks back through history and next walks forward again`() {
        val p = playlist(a, b, c, d, shuffle = true)
        val played = List(3) { p.next()!! }
        assertEquals(played[1], p.previous())
        assertEquals(played[0], p.previous())
        assertNull(p.previous())
        assertEquals(played[0], p.current)
        assertEquals(played[1], p.next())
        assertEquals(played[2], p.next())
    }

    @Test
    fun `single item and empty list`() {
        val one = playlist(a, shuffle = true)
        assertEquals(a, one.next())
        assertEquals(a, one.next())
        assertNull(playlist().next())
        assertNull(playlist().previous())
    }
}

class PlayClockTest {

    @Test
    fun `only counts while running`() {
        var now = 1000L
        val clock = PlayClock { now }
        now += 500
        assertEquals(0, clock.elapsedMillis)

        clock.resume()
        now += 300
        assertEquals(300, clock.elapsedMillis)
        clock.pause()
        now += 10_000
        assertEquals(300, clock.elapsedMillis)

        clock.resume()
        clock.resume()
        now += 200
        assertEquals(500, clock.elapsedMillis)

        clock.reset()
        now += 50
        assertEquals(50, clock.elapsedMillis)
    }
}
