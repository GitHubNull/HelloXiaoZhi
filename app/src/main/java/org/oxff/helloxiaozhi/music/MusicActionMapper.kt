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
     * 提取播放关键词（去除播放指令前缀）
     */
    private fun extractPlayKeyword(text: String): String? {
        for (prefix in PLAY_PREFIXES) {
            if (text.startsWith(prefix)) {
                val keyword = text.removePrefix(prefix).trim()
                // 去除常见的后缀词
                val cleaned = keyword.removeSuffix("的歌").removeSuffix("的歌").trim()
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
                // 去除常见的后缀词
                val cleaned = after.removeSuffix("的歌").removeSuffix("的歌").trim()
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

        // 音乐类型关键词
        private val GENRE_KEYWORDS = arrayOf("轻音乐", "摇滚", "流行", "古典", "爵士", "民谣", "电子", "说唱")
        private val GENRE_MAP = mapOf(
            "轻音乐" to "轻音乐",
            "摇滚" to "摇滚",
            "流行" to "流行",
            "古典" to "古典",
            "爵士" to "爵士",
            "民谣" to "民谣",
            "电子" to "电子",
            "说唱" to "说唱",
        )
    }
}
