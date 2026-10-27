package io.github.livebg.player

import java.nio.file.Path
import kotlin.random.Random

/**
 * 轮播的顺序：按顺序一个接一个；或者随机——洗一遍牌放完再洗，一轮里不重复，新一轮的第一个不和刚放的撞。
 * 只管挑哪个，不管什么时候换（见 [Carousel]）。
 */
class Playlist(private val random: Random = Random.Default) {

    var items: List<Path> = emptyList()
        private set

    var current: Path? = null
        private set

    var shuffle = false
        set(value) {
            if (field != value) deck.clear()
            field = value
        }

    /** 随机模式下这一轮还没放的。 */
    private val deck = ArrayDeque<Path>()

    /** 放过的，随机模式下「上一个」从这里倒着拿。 */
    private val history = ArrayDeque<Path>()

    /** 当前这个从列表里没了：按顺序播时从它原来后面那个接着放，不回到第一个。 */
    private var resume: Path? = null

    val canGoBack: Boolean get() = if (shuffle) history.isNotEmpty() else items.size > 1

    /** 换成重新扫到的列表。当前这个还在就留着。 */
    fun update(newItems: List<Path>) {
        if (newItems == items) return
        val keep = newItems.toHashSet()
        val cur = current
        if (cur != null && cur !in keep) {
            val i = items.indexOf(cur)
            resume = (1 until items.size).asSequence().map { items[(i + it) % items.size] }.firstOrNull { it in keep }
            current = null
        }
        items = newItems
        deck.retainAll(keep)
        history.retainAll(keep)
    }

    /** 选中 [path]；不在列表里返回 false。 */
    fun select(path: Path): Boolean {
        if (path !in items) return false
        moveTo(path)
        return true
    }

    fun next(): Path? {
        if (items.isEmpty()) return null
        val i = items.indexOf(current)
        val target = when {
            shuffle -> draw()
            i >= 0 -> items[(i + 1) % items.size]
            else -> resume?.takeIf { it in items } ?: items[0]
        }
        moveTo(target)
        return target
    }

    /** 回到上一个。随机模式下还没放过别的就返回 null。 */
    fun previous(): Path? {
        if (items.isEmpty()) return null
        if (!shuffle) {
            val i = items.indexOf(current).takeIf { it >= 0 } ?: resume?.let(items::indexOf)?.takeIf { it >= 0 } ?: 0
            val target = items[(i - 1 + items.size) % items.size]
            moveTo(target)
            return target
        }
        val target = history.removeLastOrNull() ?: return null
        // 再按「下一个」能回到现在这个
        current?.let { deck.addFirst(it) }
        current = target
        resume = null
        return target
    }

    private fun moveTo(target: Path) {
        val cur = current
        if (cur != null && cur != target) {
            history.addLast(cur)
            if (history.size > MAX_HISTORY) history.removeFirst()
        }
        current = target
        resume = null
        deck.remove(target)
    }

    private fun draw(): Path {
        if (deck.isEmpty()) {
            deck.addAll(items.shuffled(random))
            if (deck.size > 1 && deck.first() == current) deck.addLast(deck.removeFirst())
        }
        return deck.removeFirst()
    }

    private companion object {
        const val MAX_HISTORY = 100
    }
}
