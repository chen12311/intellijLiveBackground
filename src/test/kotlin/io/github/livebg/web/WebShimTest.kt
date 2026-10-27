package io.github.livebg.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebShimTest {

    private fun inject(html: String) = String(WebShim.inject(html.toByteArray(Charsets.UTF_8), "<X>"), Charsets.UTF_8)

    @Test
    fun `goes right after head, then html, then doctype`() {
        assertEquals("<!DOCTYPE html><html><head lang=\"en\"><X><title>t</title></head></html>",
            inject("<!DOCTYPE html><html><head lang=\"en\"><title>t</title></head></html>"))
        assertEquals("<html class=a><X><header>h</header></html>", inject("<html class=a><header>h</header></html>"))
        assertEquals("<!doctype html><X><canvas></canvas>", inject("<!doctype html><canvas></canvas>"))
        assertEquals("<X><canvas></canvas>", inject("<canvas></canvas>"))
        assertEquals("﻿<X><canvas></canvas>", inject("﻿<canvas></canvas>"))
    }

    @Test
    fun `original bytes are kept whatever the encoding`() {
        val gbk = "<html><head><title>壁纸</title></head></html>".toByteArray(charset("GBK"))
        val out = WebShim.inject(gbk, "<X>")
        assertEquals("<html><head><X><title>壁纸</title></head></html>", String(out, charset("GBK")))
    }

    @Test
    fun `script tag is ascii and carries the config`() {
        val props = JsonObject().apply {
            add("title", JsonObject().apply { addProperty("value", "</script><b>中文 & 'q'") })
        }
        val tag = WebShim.tag(props, fps = 24, blur = 3, dimPercent = 15, scale = 1280.0 / 1920)
        assertTrue(tag.all { it.code in 0x09..0x7E })
        assertEquals("only our two script tags", 2, Regex("</script>").findAll(tag).count())

        val json = Regex("""window\.__livebgConfig=(.*?);</script>""").find(tag)!!.groupValues[1]
        val config = JsonParser.parseString(json).asJsonObject
        assertEquals("</script><b>中文 & 'q'", config.getAsJsonObject("properties").getAsJsonObject("title")["value"].asString)
        assertEquals(24, config["fps"].asInt)
        assertEquals("blur(4.50px) brightness(0.850)", config["filter"].asString)
    }

    @Test
    fun `no filter when blur and dim are off`() {
        val tag = WebShim.tag(JsonObject(), fps = 30, blur = 0, dimPercent = 0, scale = 1.0)
        assertTrue(tag.contains("\"filter\":\"\""))
        assertFalse(tag.contains("blur("))
    }
}
