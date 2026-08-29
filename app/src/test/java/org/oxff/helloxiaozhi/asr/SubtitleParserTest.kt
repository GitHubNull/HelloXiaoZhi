package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SubtitleParser 字幕解析器单元测试：SRT / ASS / VTT 三种格式与自动检测。
 */
class SubtitleParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeSrt(): java.io.File = tempFolder.newFile("sample.srt").apply {
        writeText(
            """
            1
            00:00:01,000 --> 00:00:03,500
            今天天气怎么样

            2
            00:00:04,000 --> 00:00:06,000
            适合出去玩
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun writeAss(): java.io.File = tempFolder.newFile("sample.ass").apply {
        writeText(
            """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,今天天气怎么样
            Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,适合出去玩
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun writeVtt(): java.io.File = tempFolder.newFile("sample.vtt").apply {
        writeText(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.500
            今天天气怎么样

            00:00:04.000 --> 00:00:06.000
            适合出去玩
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    @Test
    fun `SRT 解析时间与文本`() {
        val entries = SubtitleParser.parseSrt(writeSrt())
        assertEquals(2, entries.size)
        assertEquals(1000, entries[0].startMs)
        assertEquals(3500, entries[0].endMs)
        assertEquals("今天天气怎么样", entries[0].text)
        assertEquals("适合出去玩", entries[1].text)
    }

    @Test
    fun `ASS 解析时间与文本`() {
        val entries = SubtitleParser.parseAss(writeAss())
        assertEquals(2, entries.size)
        assertEquals(1000, entries[0].startMs)
        assertEquals(3500, entries[0].endMs)
        assertEquals("今天天气怎么样", entries[0].text)
    }

    @Test
    fun `VTT 解析时间与文本`() {
        val entries = SubtitleParser.parseVtt(writeVtt())
        assertEquals(2, entries.size)
        assertEquals(4000, entries[1].startMs)
        assertEquals("适合出去玩", entries[1].text)
    }

    @Test
    fun `自动检测格式`() {
        assertEquals(2, SubtitleParser.parse(writeSrt()).size)
        assertEquals(2, SubtitleParser.parse(writeAss()).size)
        assertEquals(2, SubtitleParser.parse(writeVtt()).size)
    }
}
