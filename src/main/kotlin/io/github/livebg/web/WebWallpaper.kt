package io.github.livebg.web

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.github.livebg.LiveBgBundle.message
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * 一个网页壁纸：所在文件夹 [root]、入口网页 [entry]（相对 root，用 / 分隔）、用户设置的默认值 [properties]。
 *
 * 页面从假网址 [ORIGIN] 加载，请求由 [WallpaperRequestHandler] 拦下来、用 [fileFor] 找到文件夹里的文件返回。
 * 不用 file://：那样页面里 fetch / XHR 读自己的文件会被浏览器拦掉。
 */
class WebWallpaper(root: Path, val entry: String, val properties: JsonObject) {

    val root: Path = root.toAbsolutePath().normalize()

    val entryUrl: String get() = URI("http", HOST, "/$entry", null).toASCIIString()

    /** 网址对应的文件；不是我们的网址、跑出了壁纸文件夹（../）、文件不存在都返回 null。 */
    fun fileFor(url: String): Path? {
        if (!isOurs(url)) return null
        val path = try {
            URI(url).path ?: return null
        } catch (_: URISyntaxException) {
            return null
        }
        val file = try {
            root.resolve(path.removePrefix("/")).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        return file.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
    }

    fun isEntry(file: Path): Boolean = file == root.resolve(entry).normalize()

    companion object {
        private const val HOST = "wallpaper.livebg"
        const val ORIGIN = "http://$HOST"

        fun isOurs(url: String): Boolean = url.startsWith("$ORIGIN/")

        /**
         * [path] 可以是 Wallpaper Engine 的壁纸文件夹（里面有 project.json）、project.json 本身、
         * 有 index.html 的文件夹，或者直接一个 html。不是网页壁纸、读不了时抛 [IOException]，消息可以直接给用户看。
         */
        fun load(path: Path): WebWallpaper {
            if (Files.isRegularFile(path)) {
                val name = path.fileName.toString()
                return when {
                    name.equals(PROJECT_JSON, ignoreCase = true) -> fromProject(path.parent, path)
                    isHtml(name) -> WebWallpaper(path.parent, name, JsonObject())
                    else -> throw IOException(message("error.web.notWallpaper", path))
                }
            }
            if (!Files.isDirectory(path)) throw IOException(message("error.web.missing", path))
            val project = path.resolve(PROJECT_JSON)
            if (Files.isRegularFile(project)) return fromProject(path, project)
            if (Files.isRegularFile(path.resolve(INDEX_HTML))) return WebWallpaper(path, INDEX_HTML, JsonObject())
            throw IOException(message("error.web.notWallpaper", path))
        }

        private fun fromProject(dir: Path, project: Path): WebWallpaper {
            val json = try {
                // 有的 project.json 带 BOM
                JsonParser.parseString(String(Files.readAllBytes(project), Charsets.UTF_8).removePrefix("﻿")) as? JsonObject
            } catch (e: JsonParseException) {
                throw IOException(message("error.web.badProject", project, e.message ?: e.toString()), e)
            } ?: throw IOException(message("error.web.badProject", project, "not an object"))

            val type = string(json["type"])?.lowercase(Locale.ROOT)
            if (type != null && type != "web") throw IOException(message("error.web.wrongType", type))

            val root = dir.toAbsolutePath().normalize()
            val file = string(json["file"]) ?: INDEX_HTML
            val entry = try {
                root.resolve(file).normalize()
            } catch (_: InvalidPathException) {
                null
            }
            if (entry == null || !entry.startsWith(root) || !Files.isRegularFile(entry)) {
                throw IOException(message("error.web.entryMissing", file))
            }
            val properties = ((json["general"] as? JsonObject)?.get("properties") as? JsonObject) ?: JsonObject()
            return WebWallpaper(root, root.relativize(entry).joinToString("/"), properties)
        }

        private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.asString

        private fun isHtml(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("html", "htm")

        private const val PROJECT_JSON = "project.json"
        private const val INDEX_HTML = "index.html"
    }
}
