package org.oxff.helloxiaozhi.asr

import java.io.File

/**
 * 字幕解析器：支持 SRT/ASS/VTT 格式。
 */
object SubtitleParser {

    /** 解析 SRT 格式字幕 */
    fun parseSrt(file: File): List<SubtitleEntry> {
        val content = file.readText(Charsets.UTF_8)
        val subtitles = mutableListOf<SubtitleEntry>()

        // SRT 格式：序号\n时间轴 --> 时间轴\n文本\n\n
        val pattern = Regex(
            "(\\d+)\\s*\\n" +
            "(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*\\n" +
            "((?:.+\\n?)+?)(?=\\n\\d+\\n|\\n*$)",
            RegexOption.MULTILINE
        )

        pattern.findAll(content).forEach { match ->
            val startStr = match.groupValues[2]
            val endStr = match.groupValues[3]
            val text = match.groupValues[4].trim()

            val startMs = timeToMs(startStr)
            val endMs = timeToMs(endStr)

            subtitles.add(SubtitleEntry(startMs, endMs, text))
        }

        return subtitles
    }

    /** 解析 ASS 格式字幕 */
    fun parseAss(file: File): List<SubtitleEntry> {
        val content = file.readText(Charsets.UTF_8)
        val subtitles = mutableListOf<SubtitleEntry>()

        // ASS 格式：Dialogue: 0,0:00:00.00,0:00:05.00,样式,角色,边距L,边距R,边距V,特效,文本（时间后共 6 个逗号分隔字段）
        val pattern = Regex(
            "Dialogue:\\s*\\d+," +
            "(\\d+:\\d{2}:\\d{2}\\.\\d{2})," +
            "(\\d+:\\d{2}:\\d{2}\\.\\d{2})," +
            "[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,[^,]*,(.+)",
            RegexOption.MULTILINE
        )

        pattern.findAll(content).forEach { match ->
            val startStr = match.groupValues[1]
            val endStr = match.groupValues[2]
            val text = match.groupValues[3].trim()

            val startMs = assTimeToMs(startStr)
            val endMs = assTimeToMs(endStr)

            subtitles.add(SubtitleEntry(startMs, endMs, text))
        }

        return subtitles
    }

    /** 解析 VTT 格式字幕 */
    fun parseVtt(file: File): List<SubtitleEntry> {
        val content = file.readText(Charsets.UTF_8)
        val subtitles = mutableListOf<SubtitleEntry>()

        // VTT 格式：时间轴 --> 时间轴\n文本\n\n
        val pattern = Regex(
            "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*\\n" +
            "((?:.+\\n?)+?)(?=\\n\\d{2}:\\d{2}:\\d{2}\\.\\d{3}|\\n*$)",
            RegexOption.MULTILINE
        )

        pattern.findAll(content).forEach { match ->
            val startStr = match.groupValues[1]
            val endStr = match.groupValues[2]
            val text = match.groupValues[3].trim()

            val startMs = vttTimeToMs(startStr)
            val endMs = vttTimeToMs(endStr)

            subtitles.add(SubtitleEntry(startMs, endMs, text))
        }

        return subtitles
    }

    /** 自动检测格式并解析 */
    fun parse(file: File): List<SubtitleEntry> {
        val content = file.readText(Charsets.UTF_8)

        return when {
            content.contains("[Script Info]") -> parseAss(file)  // ASS 格式特征
            content.startsWith("WEBVTT") -> parseVtt(file)       // VTT 格式特征
            else -> parseSrt(file)                                // 默认为 SRT
        }
    }

    /** SRT 时间格式转换为毫秒（00:00:00,000） */
    private fun timeToMs(timeStr: String): Int {
        val parts = timeStr.split(":", ",")
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val millis = parts[3].toInt()
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
    }

    /** ASS 时间格式转换为毫秒（0:00:00.00） */
    private fun assTimeToMs(timeStr: String): Int {
        val parts = timeStr.split(":", ".")
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val centiseconds = parts[3].toInt()
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + centiseconds * 10
    }

    /** VTT 时间格式转换为毫秒（00:00:00.000） */
    private fun vttTimeToMs(timeStr: String): Int {
        val parts = timeStr.split(":", ".")
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts[2].toInt()
        val millis = parts[3].toInt()
        return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
    }
}
