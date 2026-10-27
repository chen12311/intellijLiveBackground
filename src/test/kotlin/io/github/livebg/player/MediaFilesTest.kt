package io.github.livebg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class MediaFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(vararg names: String) = names.forEach {
        val p = tmp.root.toPath().resolve(it)
        Files.createDirectories(p.parent)
        Files.createFile(p)
    }

    private fun names(paths: List<Path>) = paths.map { tmp.root.toPath().relativize(it).toString().replace('\\', '/') }

    @Test
    fun `picks videos and images, natural order, skips hidden files`() {
        touch("10.mp4", "2.mp4", "b.JPG", "a.webm", "notes.txt", ".hidden.mp4", "._copy.mp4", "sub/c.mov")
        assertEquals(listOf("2.mp4", "10.mp4", "a.webm", "b.JPG"), names(MediaFiles.scan(tmp.root.toPath(), false)))
    }

    @Test
    fun `subfolders are searched a few levels deep`() {
        touch("top.mp4", "123/wall.mp4", "x/y/z/deep.gif", "x/y/z/w/too-deep.mp4", ".git/objects/x.png")
        assertEquals(
            listOf("123/wall.mp4", "top.mp4", "x/y/z/deep.gif"),
            names(MediaFiles.scan(tmp.root.toPath(), true)),
        )
    }

    @Test
    fun `missing folder gives empty list`() {
        assertEquals(emptyList<Path>(), MediaFiles.scan(tmp.root.toPath().resolve("nope"), true))
    }

    @Test
    fun `images are still frames, gif is a video`() {
        assertTrue(MediaFiles.isImage(Path.of("a.PNG")))
        assertTrue(MediaFiles.isImage(Path.of("a.jpeg")))
        assertFalse(MediaFiles.isImage(Path.of("a.gif")))
        assertTrue(MediaFiles.isMedia(Path.of("a.gif")))
        assertFalse(MediaFiles.isMedia(Path.of("mp4")))
    }

    @Test
    fun `still image is decoded once without looping`() {
        fun cmd(still: Boolean) = FfmpegFrameSource(
            FfmpegConfig(Path.of("ffmpeg"), Path.of("bg"), still, 24, 2, 15, 1280, false)
        ).command(VideoProbe.Size(1920, 1080), VideoProbe.Size(1280, 720))

        val image = cmd(still = true)
        assertFalse("-stream_loop" in image)
        assertEquals(listOf("-frames:v", "1", "-f"), image.subList(image.indexOf("-frames:v"), image.indexOf("-frames:v") + 3))
        assertFalse(image[image.indexOf("-vf") + 1].contains("fps="))

        val video = cmd(still = false)
        assertTrue("-stream_loop" in video)
        assertFalse("-frames:v" in video)
        assertTrue(video[video.indexOf("-vf") + 1].startsWith("fps=24,"))
    }

    @Test
    fun `no scaling when the size does not change`() {
        fun cmd(blur: Int, dim: Int, src: VideoProbe.Size, size: VideoProbe.Size) = FfmpegFrameSource(
            FfmpegConfig(Path.of("ffmpeg"), Path.of("bg.jpg"), true, 24, blur, dim, 0, false)
        ).command(src, size)

        val big = VideoProbe.Size(3840, 2160)
        assertTrue(cmd(2, 15, big, VideoProbe.Size(2560, 1440)).any { "scale=2560:1440" in it })
        assertFalse(cmd(2, 15, big, big).any { "scale=" in it })
        // 图片原画、不模糊不压暗：一个滤镜都没有，不能留一个空的 -vf
        assertFalse("-vf" in cmd(0, 0, big, big))
    }
}
