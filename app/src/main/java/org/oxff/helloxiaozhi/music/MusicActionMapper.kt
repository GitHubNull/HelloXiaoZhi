package org.oxff.helloxiaozhi.music

import android.util.Log

/**
 * 音乐动作映射器：根据用户语音指令（STT 识别结果）自动触发音乐控制。
 *
 * 通过关键词匹配实现，作为 MCP 音乐控制的降级兜底方案。
 * 当服务器不支持 MCP 或 MCP 消息未到达时，仍可通过关键词匹配控制音乐播放。
 *
 * 匹配规则（按优先级排序）：
 *  - "播放/放一首/来一首/来点" + 曲名/歌手 → 搜索并播放
 *  - "播放/来点" + 类型（轻音乐/摇滚/流行/古典/爵士） → 按类型随机播放
 *  - "随机播放/随便放一首" → 随机播放
 *  - "暂停/暂停播放" → 暂停
 *  - "继续播放/接着放" → 恢复
 *  - "停止/停止播放/别放了" → 停止
 *  - "下一首/切歌/换一首" → 下一首
 *  - "上一首/上一曲" → 上一首
 */
class MusicActionMapper(
    private val musicPlayer: MusicPlayer,
    private val musicLibrary: MusicLibrary,
) {

    /** 是否启用音乐动作映射（总开关） */
    var enabled: Boolean = true

    /**
     * 处理用户语音指令文本，自动触发相应音乐控制。
     *
     * @param text STT 识别的用户语音文本
     * @return true 表示匹配成功并执行了音乐控制，false 表示未匹配
     */
    fun processText(text: String): Boolean {
        if (!enabled) return false

        val lowerText = text.lowercase()
        Log.d(TAG, "Processing text: $text")

        // 按优先级匹配（先匹配更具体的规则）
        return when {
            // 停止播放
            containsAny(lowerText, STOP_KEYWORDS) -> {
                Log.i(TAG, "Action: stop music")
                musicPlayer.stop()
                true
            }

            // 暂停播放
            containsAny(lowerText, PAUSE_KEYWORDS) -> {
                Log.i(TAG, "Action: pause music")
                musicPlayer.pause()
                true
            }

            // 恢复播放
            containsAny(lowerText, RESUME_KEYWORDS) -> {
                Log.i(TAG, "Action: resume music")
                musicPlayer.resume()
                true
            }

            // 下一首
            containsAny(lowerText, NEXT_KEYWORDS) -> {
                Log.i(TAG, "Action: next track")
                musicPlayer.next()
                true
            }

            // 上一首
            containsAny(lowerText, PREVIOUS_KEYWORDS) -> {
                Log.i(TAG, "Action: previous track")
                musicPlayer.previous()
                true
            }

            // 随机播放
            containsAny(lowerText, RANDOM_KEYWORDS) -> {
                Log.i(TAG, "Action: random play")
                val track = musicLibrary.randomTrack()
                if (track != null) {
                    musicPlayer.play(track)
                    true
                } else {
                    Log.w(TAG, "No tracks in library for random play")
                    false
                }
            }

            // 按类型播放
            containsAny(lowerText, GENRE_KEYWORDS) -> {
                val genre = extractGenre(lowerText)
                if (genre != null) {
                    Log.i(TAG, "Action: play genre=$genre")
                    val track = musicLibrary.randomByGenre(genre)
                    if (track != null) {
                        musicPlayer.play(track)
                        true
                    } else {
                        Log.w(TAG, "No tracks found for genre: $genre")
                        false
                    }
                } else {
                    false
                }
            }

            // 按专辑播放（需在通用播放分支之前，避免“播放XX的专辑”被当作曲名搜索）
            containsAny(lowerText, ALBUM_KEYWORDS) -> {
                val album = extractAlbumKeyword(text)
                if (album != null) {
                    Log.i(TAG, "Action: play album=$album")
                    val tracks = musicLibrary.searchByAlbum(album)
                    if (tracks.isNotEmpty()) {
                        musicPlayer.playPlaylist(tracks)
                        true
                    } else {
                        Log.w(TAG, "No tracks found for album: $album")
                        false
                    }
                } else {
                    false
                }
            }

            // 播放指定曲目/歌手
            containsAny(lowerText, PLAY_KEYWORDS) -> {
                val keyword = extractPlayKeyword(text)
                if (keyword != null) {
                    Log.i(TAG, "Action: play track=$keyword")
                    val tracks = musicLibrary.search(keyword)
                    if (tracks.isNotEmpty()) {
                        musicPlayer.play(tracks.first())
                        true
                    } else {
                        Log.w(TAG, "No tracks found for keyword: $keyword")
                        false
                    }
                } else {
                    false
                }
            }

            else -> false
        }
    }

    /**
     * 提取播放关键词（去除播放指令前缀与后缀装饰词）。
     *
     * 例：
     *  - "播放韩宝仪的歌曲" → "韩宝仪"
     *  - "播放韩宝仪的歌"   → "韩宝仪"
     *  - "来一首晴天"       → "晴天"
     */
    private fun extractPlayKeyword(text: String): String? {
        for (prefix in PLAY_PREFIXES) {
            if (text.startsWith(prefix)) {
                val keyword = text.removePrefix(prefix).trim()
                val cleaned = stripDecorations(keyword)
                if (cleaned.isNotEmpty()) {
                    return cleaned
                }
                if (keyword.isNotEmpty()) {
                    return keyword
                }
            }
        }
        // 如果没有前缀，检查是否包含"播放"等关键词
        for (keyword in PLAY_KEYWORDS) {
            if (text.contains(keyword)) {
                // 提取"播放"后面的内容
                val index = text.indexOf(keyword)
                val after = text.substring(index + keyword.length).trim()
                val cleaned = stripDecorations(after)
                if (cleaned.isNotEmpty()) {
                    return cleaned
                }
                if (after.isNotEmpty()) {
                    return after
                }
            }
        }
        return null
    }

    /**
     * 去除关键词首尾的装饰词（"的歌曲"/"的歌"/"歌曲"/"首歌" 等）与结尾标点，
     * 避免把装饰词带进搜索导致匹配失败（如 "韩宝仪的歌曲。" 搜不到 artist=韩宝仪）。
     *
     * 说明：STT 识别文本末尾常带中文句号/感叹号/问号等标点，若先去标点再去装饰词，
     * 否则 endsWith("的歌曲") 会因结尾是 "。" 而匹配失败。循环剥离直到稳定。
     */
    private fun stripDecorations(keyword: String): String {
        var result = keyword.trim()
        while (true) {
            val before = result
            // 先剥离开头量词（"一首"/"这首歌"/"那首歌" 等），避免 "播放一首韩宝仪的歌曲" 提取成 "一首韩宝仪"
            result = stripLeadingQuantifier(result)
            // 先剥离结尾标点（STT 常带中文标点）
            result = result.trimEnd(*TRAILING_PUNCTUATION)
            // 再剥离装饰词后缀（长的在前，优先匹配，避免 "的歌曲" 被 "的歌" 截断成 "曲"）
            for (suffix in DECORATION_SUFFIXES) {
                if (result.endsWith(suffix) && result.length > suffix.length) {
                    result = result.removeSuffix(suffix).trim()
                    break
                }
            }
            if (result == before) break
        }
        return result
    }

    /**
     * 剥离开头的量词/指代词前缀（长词优先，循环剥离直到稳定）。
     *
     * 例：
     *  - "一首韩宝仪的歌曲" → "韩宝仪的歌曲"（再经过 [stripDecorations] 剥尾部装饰词后得 "韩宝仪"）
     *  - "这首歌晴天"       → "晴天"
     */
    private fun stripLeadingQuantifier(keyword: String): String {
        var result = keyword.trim()
        while (true) {
            val before = result
            for (q in LEADING_QUANTIFIERS) {
                if (result.startsWith(q) && result.length > q.length) {
                    result = result.removePrefix(q).trim()
                    break
                }
            }
            if (result == before) break
        }
        return result
    }

    /**
     * 提取专辑关键词（去除播放指令前缀与“的专辑/专辑”后缀）
     */
    private fun extractAlbumKeyword(text: String): String? {
        val idx = text.indexOf("的专辑")
        val cut = if (idx >= 0) idx else text.indexOf("专辑")
        if (cut < 0) return null
        var candidate = text.substring(0, cut).trim()
        for (prefix in PLAY_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                candidate = candidate.removePrefix(prefix).trim()
                break
            }
        }
        for (kw in PLAY_KEYWORDS) {
            val i = candidate.indexOf(kw)
            if (i >= 0) {
                candidate = candidate.substring(i + kw.length).trim()
                break
            }
        }
        return candidate.ifEmpty { null }
    }

    /**
     * 提取音乐类型
     */
    private fun extractGenre(text: String): String? {
        for ((keyword, genre) in GENRE_MAP) {
            if (text.contains(keyword)) {
                return genre
            }
        }
        return null
    }

    /** 检查文本是否包含任意关键词 */
    private fun containsAny(text: String, keywords: Array<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    companion object {
        private const val TAG = "MusicActionMapper"

        // 播放指令关键词
        private val PLAY_KEYWORDS = arrayOf("播放", "放一首", "来一首", "来点", "放歌", "听歌")
        private val PLAY_PREFIXES = arrayOf("播放", "放一首", "来一首", "来点", "放歌")

        // 控制指令关键词
        private val PAUSE_KEYWORDS = arrayOf("暂停", "暂停播放", "暂停音乐", "停一下")
        private val RESUME_KEYWORDS = arrayOf("继续播放", "接着放", "继续", "恢复播放")
        private val STOP_KEYWORDS = arrayOf("停止", "停止播放", "别放了", "关掉音乐", "关闭音乐")
        private val NEXT_KEYWORDS = arrayOf("下一首", "切歌", "换一首", "下一个", "下首")
        private val PREVIOUS_KEYWORDS = arrayOf("上一首", "上一曲", "前一个", "上首")
        private val RANDOM_KEYWORDS = arrayOf("随机播放", "随便放一首", "随机来一首", "随便播放")

        // 专辑指令关键词
        private val ALBUM_KEYWORDS = arrayOf("的专辑", "专辑")

        // 播放关键词后缀装饰词（长的在前，优先匹配，避免截断错误）
        private val DECORATION_SUFFIXES = arrayOf(
            "的歌曲", "的音乐", "这首歌", "的歌儿", "的歌", "首歌", "歌曲", "音乐", "曲目", "这首",
        )

        // 开头的量词/指代词前缀（长词在前，优先匹配；STT 识别指令文本时常见，须在结尾装饰词前剔除，避免搜错）
        private val LEADING_QUANTIFIERS = arrayOf(
            "这一首", "那一首", "这首歌", "那首歌", "随便一首",
            "一首歌", "来一首", "放一首", "点一首",
            "一首", "那首", "这首", "几首",
        )

        // 结尾标点（STT 识别文本常带中文/英文句末标点，须在装饰词剥离前剔除）
        private val TRAILING_PUNCTUATION = charArrayOf(
            '。', '！', '？', '，', '、', '；', '：', '…',
            '.', '!', '?', ',', ';', ':',
            '”', '’', '"', '」', '』', '）', '）', '|', '~', '～',
        )

        // 音乐类型关键词
        private val GENRE_KEYWORDS = arrayOf(
            "轻音乐", "摇滚", "流行", "古典", "爵士", "民谣", "电子", "说唱",
            "纯音乐", "钢琴曲", "古风", "儿歌", "英文歌",
        )
        private val GENRE_MAP = mapOf(
            "轻音乐" to "轻音乐",
            "摇滚" to "摇滚",
            "流行" to "流行",
            "古典" to "古典",
            "爵士" to "爵士",
            "民谣" to "民谣",
            "电子" to "电子",
            "说唱" to "说唱",
            "纯音乐" to "轻音乐",
            "钢琴曲" to "轻音乐",
            "古风" to "古风",
            "儿歌" to "儿歌",
            "英文歌" to "英文歌",
        )
    }
}
