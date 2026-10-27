package io.github.livebg.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.livebg.LiveBgBundle
import java.awt.Rectangle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SourceKind { VIDEO, FOLDER }

enum class Scope(private val labelKey: String) {
    WINDOW("scope.window"),
    EDITOR("scope.editor");

    override fun toString() = LiveBgBundle.message(labelKey)
}

enum class FillMode(private val labelKey: String) {
    COVER("fill.cover"),
    CONTAIN("fill.contain"),
    STRETCH("fill.stretch");

    override fun toString() = LiveBgBundle.message(labelKey)

    /** 图片 [iw]×[ih] 放进 [cw]×[ch] 区域时的位置和大小。 */
    fun place(iw: Int, ih: Int, cw: Int, ch: Int): Rectangle {
        if (this == STRETCH || iw <= 0 || ih <= 0) return Rectangle(0, 0, cw, ch)
        val sx = cw.toDouble() / iw
        val sy = ch.toDouble() / ih
        val scale = if (this == COVER) max(sx, sy) else min(sx, sy)
        val w = (iw * scale).roundToInt()
        val h = (ih * scale).roundToInt()
        return Rectangle((cw - w) / 2, (ch - h) / 2, w, h)
    }
}

@Service(Service.Level.APP)
@State(name = "LiveBackground", storages = [Storage("liveBackground.xml", roamingType = RoamingType.DISABLED)])
class LiveBgSettings : PersistentStateComponent<LiveBgSettings.State> {

    class State {
        var enabled = true
        var source = SourceKind.VIDEO
        var videoPath = ""

        /** 文件夹轮播 */
        var folderPath = ""
        var folderRecursive = false
        var shuffle = false

        /** 每隔几分钟换一个，只算播放时间；0 = 不自动换 */
        var rotateMinutes = 10

        /** 轮播到的那个，重启 IDE 后接着放 */
        var folderCurrent = ""

        var scope = Scope.WINDOW

        /** 百分比 */
        var opacity = 20
        var fill = FillMode.COVER
        var fps = 24

        /** ffmpeg boxblur 半径，0 = 不模糊 */
        var blur = 2

        /** 压暗百分比 */
        var dim = 15

        /** 解码分辨率的长边上限；0 = 原画，不缩小 */
        var decodeWidth = 1280

        var pauseWhenInactive = true
        var pauseInPowerSave = true
        var protectSelection = false

        var ffmpegPath = ""
        var hwaccel = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): LiveBgSettings = service()
    }
}
