package io.github.livebg.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBgSettingsTest {

    @Test
    fun `removed demo source falls back to video and keeps the rest`() {
        // 以前的版本可能存着 source=DEMO：读不出来就用默认的视频，其他设置照旧
        val xml = """
            <component name="LiveBackground">
              <option name="source" value="DEMO" />
              <option name="videoPath" value="/tmp/a.mp4" />
            </component>
        """.trimIndent()
        val settings = LiveBgSettings()
        settings.loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), LiveBgSettings.State::class.java))

        assertEquals(SourceKind.VIDEO, settings.state.source)
        assertEquals("/tmp/a.mp4", settings.state.videoPath)
    }
}
