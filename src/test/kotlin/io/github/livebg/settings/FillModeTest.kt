package io.github.livebg.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Rectangle

class FillModeTest {

    @Test
    fun `cover fills the whole area and crops`() {
        // 16:9 的视频放进 16:10 的窗口：高度铺满，左右裁掉
        assertEquals(Rectangle(-89, 0, 1778, 1000), FillMode.COVER.place(1280, 720, 1600, 1000))
    }

    @Test
    fun `contain shows everything`() {
        assertEquals(Rectangle(0, 50, 1600, 900), FillMode.CONTAIN.place(1280, 720, 1600, 1000))
    }

    @Test
    fun `stretch ignores aspect`() {
        assertEquals(Rectangle(0, 0, 1600, 1000), FillMode.STRETCH.place(1280, 720, 1600, 1000))
    }
}
