package org.oxff.helloxiaozhi.music

import java.util.regex.Pattern

/**
 * `.music_metadata.txt` 描述文件解析器（YAML 子集）。
 *
 * 支持格式（手写轻量解析，不引入 snakeyaml）：
 * ```
 * # 注释行
 * - pattern: "*.mp3"
 *   artist: 韩宝仪
 *   genre: 经典老歌
 * - pattern: "邓丽君*"
 *   artist: 邓丽君
 *   genre: 民谣
 * ```
 *
 * 规则：`- ` 开头新增一条规则；缩进的 `key: value` 填充当前规则字段；
 * `#` 注释与空行忽略；值支持双引号包裹；未知字段忽略。
 */
object MusicMetadataDescriptor {

    /** 描述文件名（隐藏文件） */
    const val FILE_NAME = ".music_metadata.txt"

    /** 一条元数据规则 */
    data class MetadataRule(
        val pattern: String,
        val artist: String? = null,
        val genre: String? = null,
        val album: String? = null,
    )

    /**
     * 解析描述文件文本为规则列表。
     */
    fun parse(text: String): List<MetadataRule> {
        val rules = mutableListOf<MetadataRule>()
        // 当前规则的字段暂存（pattern/artist/genre/album）
        var pattern: String? = null
        var artist: String? = null
        var genre: String? = null
        var album: String? = null
        var inRule = false

        fun flush() {
            if (inRule && pattern != null) {
                rules.add(MetadataRule(pattern = pattern!!, artist = artist, genre = genre, album = album))
            }
            pattern = null; artist = null; genre = null; album = null
            inRule = false
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("- ")) {
                // 新规则开始，先冲刷上一条
                flush()
                inRule = true
                // `- pattern: "*.mp3"` 同行内联字段
                parseField(trimmed.removePrefix("- ").trim())?.let { (key, value) ->
                    when (key) {
                        "pattern" -> pattern = value
                        "artist" -> artist = value
                        "genre" -> genre = value
                        "album" -> album = value
                    }
                }
            } else if (inRule) {
                // 规则内的缩进字段
                parseField(trimmed)?.let { (key, value) ->
                    when (key) {
                        "pattern" -> pattern = value
                        "artist" -> artist = value
                        "genre" -> genre = value
                        "album" -> album = value
                    }
                }
            }
            // 规则外的孤立字段忽略
        }
        flush()
        return rules
    }

    /** 解析 `key: value`，value 支持双引号包裹。无法解析返回 null。 */
    private fun parseField(text: String): Pair<String, String>? {
        val idx = text.indexOf(':')
        if (idx <= 0) return null
        val key = text.substring(0, idx).trim().lowercase()
        var value = text.substring(idx + 1).trim()
        // 去除包裹的双引号
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        if (value.isEmpty()) return null
        return key to value
    }

    /**
     * 用规则列表匹配文件名（不含扩展名）。
     * glob 语法：`*` 匹配任意字符序列，`?` 匹配单字符，大小写不敏感。
     *
     * @return 第一条命中的规则，未命中返回 null
     */
    fun match(rules: List<MetadataRule>, fileName: String): MetadataRule? {
        if (rules.isEmpty()) return null
        val nameWithoutExt = fileName.substringBeforeLast('.')
        for (rule in rules) {
            if (globMatches(rule.pattern, nameWithoutExt) || globMatches(rule.pattern, fileName)) {
                return rule
            }
        }
        return null
    }

    /** glob 模式匹配（大小写不敏感） */
    private fun globMatches(pattern: String, text: String): Boolean {
        val regex = globToRegex(pattern)
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).matches()
    }

    /** glob 转正则：`*`->`.*`、`?`->`.`，其余字符转义 */
    private fun globToRegex(glob: String): String {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\')
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
