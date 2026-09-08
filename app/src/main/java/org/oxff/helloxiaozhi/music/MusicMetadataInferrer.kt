package org.oxff.helloxiaozhi.music

import org.oxff.helloxiaozhi.data.db.MetadataSource
import org.oxff.helloxiaozhi.music.MusicMetadataDescriptor.MetadataRule

/**
 * 音乐元数据推断引擎：从文件路径、文件名、ID3 标签、描述文件等多层信息推断歌手/类型/专辑。
 *
 * 纯 Kotlin 实现（词典查询经 [ArtistDictionary] 走 SQLite），可独立单元测试。
 *
 * 歌手推断优先级：
 * 1. ID3 标签（非空且非"未知"）
 * 2. 描述文件规则（.music_metadata.txt，命中即采用）
 * 3. 文件夹名推断（父文件夹名清洗 + 词典校验）
 * 4. 祖父文件夹词典校验（处理 歌手/专辑/歌曲.mp3 结构）
 * 5. 文件名模式解析（"歌手 - 歌名.ext" 等，结果经词典校验）
 *
 * 类型推断优先级：
 * 1. ID3 标签
 * 2. 描述文件规则
 * 3. 文件夹名关键词映射
 *
 * 专辑推断：
 * 1. ID3 标签
 * 2. 描述文件规则
 * 3. 若文件夹名推断出歌手，上级文件夹名作为专辑名
 */
class MusicMetadataInferrer(
    private val artistDictionary: ArtistDictionary,
) {

    /** 推断结果 */
    data class InferredMetadata(
        val artist: String?,   // null = 无法推断
        val genre: String?,    // null = 无法推断
        val album: String?,    // null = 无法推断
        val source: MetadataSource, // 命中来源
    )

    /**
     * 推断音乐文件元数据。
     *
     * @param filePath 文件完整路径（本地路径或 SAF URI 显示路径）
     * @param fileName 文件名（含扩展名）
     * @param id3Artist ID3 提取的歌手（可能为 null）
     * @param id3Genre ID3 提取的类型（可能为 null）
     * @param id3Album ID3 提取的专辑（可能为 null）
     * @param descriptorRules 所在目录的描述文件规则（可能为 null）
     * @return 推断后的元数据
     */
    fun infer(
        filePath: String,
        fileName: String,
        id3Artist: String?,
        id3Genre: String?,
        id3Album: String?,
        descriptorRules: List<MetadataRule>? = null,
    ): InferredMetadata {
        // 描述文件规则（命中则优先覆盖文件夹名/文件名推断）
        val rule = descriptorRules?.let { MusicMetadataDescriptor.match(it, fileName) }

        val artistResult = inferArtist(filePath, fileName, id3Artist, rule)
        val genre = inferGenre(filePath, id3Genre, rule)
        val album = inferAlbum(filePath, id3Album, artistResult.first, rule)
        return InferredMetadata(
            artist = artistResult.first,
            genre = genre,
            album = album,
            source = artistResult.second,
        )
    }

    // ---------------- 歌手推断 ----------------

    private fun inferArtist(
        filePath: String,
        fileName: String,
        id3Artist: String?,
        rule: MetadataRule?,
    ): Pair<String?, MetadataSource> {
        // 第 1 级：ID3 标签
        if (!id3Artist.isNullOrBlank() && !isUnknownArtist(id3Artist)) {
            return id3Artist.trim() to MetadataSource.ID3
        }

        // 第 2 级：描述文件规则
        if (rule?.artist != null) {
            return rule.artist to MetadataSource.DESCRIPTION_FILE
        }

        // 第 3 级：文件夹名推断
        val parentDirName = extractParentDirName(filePath)
        if (parentDirName != null) {
            val cleaned = cleanDirName(parentDirName)
            if (cleaned != null) {
                // 词典校验
                val matched = artistDictionary.matchArtist(cleaned)
                if (matched != null) return matched to MetadataSource.FOLDER_NAME

                // 父目录未命中词典，检查祖父目录（处理 歌手/专辑/歌曲.mp3 结构）
                val grandParentDirName = extractGrandParentDirName(filePath)
                if (grandParentDirName != null) {
                    val grandCleaned = cleanDirName(grandParentDirName)
                    if (grandCleaned != null) {
                        val grandMatched = artistDictionary.matchArtist(grandCleaned)
                        if (grandMatched != null) return grandMatched to MetadataSource.FOLDER_NAME
                    }
                }

                // 词典均未命中，返回清洗后的父目录名
                return cleaned to MetadataSource.FOLDER_NAME
            }
        }

        // 第 4 级：文件名模式解析
        val fromFileName = parseArtistFromFileName(fileName)
        if (fromFileName != null) {
            val matched = artistDictionary.matchArtist(fromFileName)
            return (matched ?: fromFileName) to MetadataSource.PATTERN_MATCH
        }

        return null to MetadataSource.FOLDER_NAME
    }

    // ---------------- 类型推断 ----------------

    private fun inferGenre(filePath: String, id3Genre: String?, rule: MetadataRule?): String? {
        // 第 1 级：ID3 标签
        if (!id3Genre.isNullOrBlank()) return id3Genre.trim()

        // 第 2 级：描述文件规则
        if (rule?.genre != null) return rule.genre

        // 第 3 级：文件夹名关键词映射
        val parentDirName = extractParentDirName(filePath) ?: return null
        val lowerDir = parentDirName.lowercase()
        for ((keyword, genre) in GENRE_KEYWORD_MAP) {
            if (lowerDir.contains(keyword.lowercase())) return genre
        }
        return null
    }

    // ---------------- 专辑推断 ----------------

    private fun inferAlbum(
        filePath: String,
        id3Album: String?,
        inferredArtist: String?,
        rule: MetadataRule?,
    ): String? {
        // 第 1 级：ID3 标签
        if (!id3Album.isNullOrBlank()) return id3Album.trim()

        // 第 2 级：描述文件规则
        if (rule?.album != null) return rule.album

        // 第 3 级：若推断出了歌手，尝试从目录层级推断专辑
        if (inferredArtist != null) {
            val parentDirName = extractParentDirName(filePath)
            val grandParentDirName = extractGrandParentDirName(filePath)

            // 情形 A：歌手来自祖父目录（如 /音乐/周杰伦/范特西/晴天.mp3），父目录为专辑
            if (parentDirName != null && grandParentDirName != null) {
                val grandCleaned = cleanDirName(grandParentDirName)
                if (grandCleaned != null && artistDictionary.matchArtist(grandCleaned) == inferredArtist) {
                    // 祖父目录命中词典且等于推断出的歌手，父目录就是专辑
                    val parentCleaned = cleanDirName(parentDirName)
                    if (parentCleaned != null && parentCleaned != inferredArtist) {
                        return parentCleaned
                    }
                }
            }

            // 情形 B：歌手来自父目录，祖父目录作为专辑
            if (grandParentDirName != null && !isGenericDirName(grandParentDirName)) {
                val cleaned = cleanDirName(grandParentDirName)
                // 祖父文件夹名不应等于歌手名（避免 /周杰伦/周杰伦/ 的情况）
                if (cleaned != null && cleaned != inferredArtist) return cleaned
            }
        }
        return null
    }

    // ---------------- 路径工具 ----------------

    /** 提取文件的直接父文件夹名 */
    private fun extractParentDirName(filePath: String): String? {
        val normalized = filePath.replace('\\', '/')
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return null
        val parentPath = normalized.substring(0, lastSlash)
        val secondLastSlash = parentPath.lastIndexOf('/')
        return if (secondLastSlash >= 0) {
            parentPath.substring(secondLastSlash + 1)
        } else {
            parentPath
        }
    }

    /** 提取文件的祖父文件夹名 */
    private fun extractGrandParentDirName(filePath: String): String? {
        val normalized = filePath.replace('\\', '/')
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return null
        val parentPath = normalized.substring(0, lastSlash)
        val secondLastSlash = parentPath.lastIndexOf('/')
        if (secondLastSlash <= 0) return null
        val grandParentPath = parentPath.substring(0, secondLastSlash)
        val thirdLastSlash = grandParentPath.lastIndexOf('/')
        return if (thirdLastSlash >= 0) {
            grandParentPath.substring(thirdLastSlash + 1)
        } else {
            grandParentPath
        }
    }

    // ---------------- 文件夹名清洗 ----------------

    /**
     * 清洗文件夹名，提取候选歌手名。
     * 返回 null 表示该文件夹名是通用名，不适合作为歌手名。
     */
    private fun cleanDirName(dirName: String): String? {
        val trimmed = dirName.trim()
        if (trimmed.isEmpty()) return null

        // 跳过通用文件夹名
        if (isGenericDirName(trimmed)) return null

        var cleaned = trimmed

        // 去除尾部数字（如 "周杰伦2" → "周杰伦"）
        cleaned = cleaned.replace(Regex("\\d+$"), "").trim()

        // 去除常见后缀
        for (suffix in DIR_SUFFIXES) {
            if (cleaned.endsWith(suffix, ignoreCase = true)) {
                cleaned = cleaned.dropLast(suffix.length).trim()
                // 去除后缀前的分隔符
                cleaned = cleaned.trimEnd('-', '_', ' ', '—', '–')
                break
            }
        }

        // 去除常见前缀
        for (prefix in DIR_PREFIXES) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.drop(prefix.length).trim()
                cleaned = cleaned.trimStart('-', '_', ' ', '—', '–')
                break
            }
        }

        // 去除括号内容（如 "周杰伦(精选)" → "周杰伦"）
        cleaned = cleaned.replace(Regex("[（(].*?[）)]"), "").trim()

        return cleaned.ifEmpty { null }
    }

    /** 判断是否为通用文件夹名（不适合作为歌手名） */
    private fun isGenericDirName(name: String): Boolean {
        val lower = name.lowercase().trim()
        return GENERIC_DIR_NAMES.any { lower == it || lower.startsWith(it) }
    }

    // ---------------- 文件名模式解析 ----------------

    /**
     * 从文件名解析歌手名。
     * 支持模式："歌手 - 歌名.ext"、"歌手-歌名.ext"、"[歌手] 歌名.ext"、"歌手—歌名.ext"
     */
    private fun parseArtistFromFileName(fileName: String): String? {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        if (nameWithoutExt.isBlank()) return null

        // 模式 1：[歌手] 歌名
        val bracketMatch = Regex("^\\[([^]]+)]\\s*.+$").find(nameWithoutExt)
        if (bracketMatch != null) {
            val artist = bracketMatch.groupValues[1].trim()
            if (artist.isNotEmpty()) return artist
        }

        // 模式 2：歌手 - 歌名 / 歌手-歌名 / 歌手—歌名
        val separators = listOf(" - ", "-", "—", "–", "_")
        for (sep in separators) {
            val idx = nameWithoutExt.indexOf(sep)
            if (idx > 0) {
                val artist = nameWithoutExt.substring(0, idx).trim()
                // 过滤掉过短的候选名（可能是编号等）
                if (artist.length >= 2 && !artist.all { it.isDigit() }) {
                    return artist
                }
            }
        }

        return null
    }

    // ---------------- 工具方法 ----------------

    private fun isUnknownArtist(artist: String): Boolean {
        val lower = artist.lowercase().trim()
        return lower in UNKNOWN_ARTISTS || lower.isEmpty()
    }

    companion object {
        /** 未知歌手占位符 */
        private val UNKNOWN_ARTISTS = setOf(
            "未知歌手", "未知", "unknown", "unknown artist", "various artists", "va",
        )

        /** 通用文件夹名（跳过不作为歌手名） */
        private val GENERIC_DIR_NAMES = setOf(
            "music", "歌曲", "mp3", "下载", "无损", "flac", "wav", "aac",
            "audio", "sound", "sounds", "track", "tracks", "song", "songs",
            "musics", "audios", "media", "新建文件夹",
            "我的音乐", "音乐", "本地音乐", "手机音乐", "下载音乐",
        )

        /** 文件夹名常见后缀（清洗时去除） */
        private val DIR_SUFFIXES = listOf(
            "精选", "合集", "全集", "专辑", "歌曲", "音乐", "songs", "music",
            "collection", "best", "greatest hits", "精选集", "大全",
        )

        /** 文件夹名常见前缀（清洗时去除） */
        private val DIR_PREFIXES = listOf(
            "歌手", "artist", "singer",
        )

        /** 类型关键词映射（文件夹名 → 类型） */
        private val GENRE_KEYWORD_MAP: Map<String, String> = mapOf(
            "轻音乐" to "轻音乐",
            "纯音乐" to "轻音乐",
            "钢琴曲" to "轻音乐",
            "钢琴" to "轻音乐",
            "摇滚" to "摇滚",
            "rock" to "摇滚",
            "流行" to "流行",
            "pop" to "流行",
            "古典" to "古典",
            "classical" to "古典",
            "爵士" to "爵士",
            "jazz" to "爵士",
            "民谣" to "民谣",
            "folk" to "民谣",
            "电子" to "电子",
            "electronic" to "电子",
            "edm" to "电子",
            "说唱" to "说唱",
            "rap" to "说唱",
            "hip-hop" to "说唱",
            "hiphop" to "说唱",
            "嘻哈" to "说唱",
            "古风" to "古风",
            "中国风" to "古风",
            "儿歌" to "儿歌",
            "儿童" to "儿歌",
            "英文歌" to "英文歌",
            "english" to "英文歌",
            "日语" to "日语歌",
            "japanese" to "日语歌",
            "韩语" to "韩语歌",
            "korean" to "韩语歌",
            "k-pop" to "韩语歌",
            "kpop" to "韩语歌",
            "r&b" to "R&B",
            "rnb" to "R&B",
            "蓝调" to "蓝调",
            "blues" to "蓝调",
            "乡村" to "乡村",
            "country" to "乡村",
            "金属" to "金属",
            "metal" to "金属",
            "朋克" to "朋克",
            "punk" to "朋克",
            "雷鬼" to "雷鬼",
            "reggae" to "雷鬼",
            "拉丁" to "拉丁",
            "latin" to "拉丁",
            "新世纪" to "新世纪",
            "new age" to "新世纪",
            "ambient" to "氛围音乐",
            "氛围" to "氛围音乐",
            "lofi" to "Lo-Fi",
            "lo-fi" to "Lo-Fi",
        )
    }
}
