package io.github.livebg.player

import com.intellij.openapi.util.text.NaturalComparator
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.Locale

/** 哪些文件能当背景，以及扫文件夹。 */
object MediaFiles {

    private val VIDEO = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv", "flv", "ts", "gif", "apng")

    /** 静态图：ffmpeg 只解一帧，之后不再占 CPU。 */
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff")

    /**
     * 包含子文件夹时最多找到第几层（文件夹本身里的文件算第 1 层）。
     * Wallpaper Engine 的目录是 431960/<ID>/xxx.mp4，两层就够；限住层数，误选了很大的目录也不会卡住。
     */
    private const val MAX_DEPTH = 4

    private val ORDER = Comparator<Path> { a, b -> NaturalComparator.INSTANCE.compare(a.toString(), b.toString()) }

    fun isImage(path: Path) = extension(path) in IMAGE

    fun isMedia(path: Path) = extension(path).let { it in VIDEO || it in IMAGE }

    /**
     * 文件夹里能播的文件，按路径自然排序（2 排在 10 前面）。
     * 隐藏文件跳过（mac 拷到移动硬盘上会多出 ._xxx.mp4 这种空壳）；读不了的子文件夹跳过；文件夹不存在返回空列表。
     */
    fun scan(folder: Path, recursive: Boolean): List<Path> {
        val found = ArrayList<Path>()
        val options = EnumSet.of(FileVisitOption.FOLLOW_LINKS)
        Files.walkFileTree(folder, options, if (recursive) MAX_DEPTH else 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != folder && isHidden(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !isHidden(file) && isMedia(file)) found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return found.sortedWith(ORDER)
    }

    private fun isHidden(path: Path) = path.fileName?.toString()?.startsWith(".") == true

    private fun extension(path: Path) =
        path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
}
