package io.github.livebg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoProbeTest {

    @Test
    fun `normal mp4`() {
        val info = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'bg.mp4':
              Duration: 00:00:10.00, start: 0.000000, bitrate: 4999 kb/s
              Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709, progressive), 1920x1080 [SAR 1:1 DAR 16:9], 4970 kb/s, 30 fps, 30 tbr, 15360 tbn (default)
              Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 128 kb/s (default)
        """.trimIndent()
        assertEquals(VideoProbe.Size(1920, 1080), VideoProbe.parse(info))
    }

    @Test
    fun `portrait phone video is rotated`() {
        val info = """
              Stream #0:0[0x1](und): Video: hevc (Main) (hvc1 / 0x31637668), yuv420p(tv, bt709), 1920x1080, 9000 kb/s, 29.97 fps (default)
                Side data:
                  displaymatrix: rotation of -90.00 degrees
        """.trimIndent()
        assertEquals(VideoProbe.Size(1080, 1920), VideoProbe.parse(info))
    }

    @Test
    fun `gif and webm`() {
        assertEquals(VideoProbe.Size(480, 270), VideoProbe.parse("  Stream #0:0: Video: gif, bgra, 480x270, 10 fps, 10 tbr, 100 tbn"))
        assertEquals(
            VideoProbe.Size(2560, 1440),
            VideoProbe.parse("  Stream #0:0(eng): Video: vp9 (Profile 0), yuv420p(tv), 2560x1440, SAR 1:1 DAR 16:9, 60 fps, 60 tbr, 1k tbn (default)"),
        )
    }

    @Test
    fun `skips cover art`() {
        val info = """
              Stream #0:0: Video: mjpeg (Baseline), yuvj420p(pc), 600x600 [SAR 1:1 DAR 1:1], 90k tbr (attached pic)
              Stream #0:1: Video: h264 (High), yuv420p, 1280x720, 25 fps
        """.trimIndent()
        assertEquals(VideoProbe.Size(1280, 720), VideoProbe.parse(info))
    }

    @Test
    fun `no video stream`() {
        assertNull(VideoProbe.parse("  Stream #0:0: Audio: mp3, 44100 Hz, stereo, fltp, 320 kb/s"))
    }

    @Test
    fun `fit keeps aspect, never upscales, even sizes`() {
        assertEquals(VideoProbe.Size(1280, 720), VideoProbe.fit(VideoProbe.Size(1920, 1080), 1280))
        assertEquals(VideoProbe.Size(720, 1280), VideoProbe.fit(VideoProbe.Size(1080, 1920), 1280))
        assertEquals(VideoProbe.Size(480, 270), VideoProbe.fit(VideoProbe.Size(480, 270), 1280))
        assertEquals(VideoProbe.Size(1280, 534), VideoProbe.fit(VideoProbe.Size(2560, 1069), 1280))
        assertEquals(VideoProbe.Size(2560, 1440), VideoProbe.fit(VideoProbe.Size(3840, 2160), 2560))
    }

    @Test
    fun `original size when no limit`() {
        assertEquals(VideoProbe.Size(4480, 2520), VideoProbe.fit(VideoProbe.Size(4480, 2520), 0))
        assertEquals(VideoProbe.Size(3838, 2160), VideoProbe.fit(VideoProbe.Size(3838, 2160), 0))
        assertEquals(VideoProbe.Size(1918, 1078), VideoProbe.fit(VideoProbe.Size(1919, 1079), 0))
    }
}
