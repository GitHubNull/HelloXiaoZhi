package org.oxff.helloxiaozhi.music

import android.util.Log

/**
 * 音乐动作映射器：根据用户语音指令（STT 识别结果）自动触发音乐控制。
 *
 * 通过关键词匹配实现，作为 MCP 音乐控制的降级兜底方案。
 * 当服务器不支持 MCP 或 MCP 消息未到达时，仍可通过关键词匹配控制音乐播放。
 *
 * 匹配规则（按优先级排序）：
 *  - "播放/放一首/来一首/来点" + 曲名/歌手 → 归一化成候选关键词后反查曲库并播放
 *    （口语长句由 [MusicKeywordNormalizer] 处理；无具体目标时降级随机播放）
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

            // 播放指定曲目/歌手：归一化成多个候选关键词后逐个反查曲库，
            // 命中即播。口语长句里目标词常被泛化词/填充词/装饰词包裹
            // （实测 "你先帮我播放音乐吧，那个那个韩宝仪的音乐。"），
            // 单串清洗必失配，须由曲库内容裁决哪个候选才是真目标
            containsAny(lowerText, PLAY_KEYWORDS) -> {
                val candidates = MusicKeywordNormalizer.candidates(text)
                Log.i(TAG, "Play candidates: $candidates")
                val matched = candidates.asSequence()
                    .map { it to musicLibrary.search(it) }
                    .firstOrNull { it.second.isNotEmpty() }
                when {
                    matched != null -> {
                        val track = matched.second.first()
                        Log.i(TAG, "Action: play keyword=${matched.first} track=${track.title}")
                        musicPlayer.play(track)
                        true
                    }

                    // 纯泛化指令（"播放音乐"/"放首歌"）归一化后无候选，
                    // 用户意图就是"随便放点音乐"，降级为随机播放
                    candidates.isEmpty() -> {
                        val track = musicLibrary.randomTrack()
                        if (track != null) {
                            Log.i(TAG, "Action: play random (generic request)")
                            musicPlayer.play(track)
                            true
                        } else {
                            Log.w(TAG, "No tracks in library for generic play request")
                            false
                        }
                    }

                    else -> {
                        Log.w(TAG, "No tracks found for candidates: $candidates")
                        false
                    }
                }
            }

            else -> false
        }
    }

    /**
     * 判定文本是否为「播放控制类」指令（停止/暂停/继续/上一首/下一首）。
     *
     * 只判定不执行，供调用方按场景决定是否拦截。必须在本地音乐播放期间
     * 才拦截这类词：无音乐时 "别唱了"/"停下"/"你别说话" 很可能是正常聊天
     * 内容，拦截会吞掉用户对话并误发 abort 打断服务器回复。
     */
    fun isControlCommand(text: String): Boolean {
        val lower = text.lowercase()
        return containsAny(lower, STOP_KEYWORDS) ||
            containsAny(lower, PAUSE_KEYWORDS) ||
            containsAny(lower, RESUME_KEYWORDS) ||
            containsAny(lower, NEXT_KEYWORDS) ||
            containsAny(lower, PREVIOUS_KEYWORDS)
    }

    /**
     * 判定文本是否为「播放类」指令（点歌/随机/按类型/按专辑）。
     *
     * 只判定不执行。此类指令表达明确的听歌意图，与是否在播音乐无关，
     * 任何场景下都应本地直接处理（比绕服务器 LLM + MCP 往返快很多）。
     */
    fun isPlayRequest(text: String): Boolean {
        val lower = text.lowercase()
        return containsAny(lower, RANDOM_KEYWORDS) ||
            containsAny(lower, GENRE_KEYWORDS) ||
            containsAny(lower, ALBUM_KEYWORDS) ||
            containsAny(lower, PLAY_KEYWORDS)
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

        // 播放指令关键词（作为 PLAY 分支门控；须覆盖口语中"放一下/放个/听一下"
        // 等说法，缺一项就会让整句落到 else 分支而不触发任何播放）
        private val PLAY_KEYWORDS = arrayOf(
            "播放", "放一首", "放一下", "放首歌", "放个", "放首", "放歌",
            "来一首", "来一曲", "来点", "听歌", "听一下", "听一首",
            "点一首", "点歌", "唱一首",
        )
        private val PLAY_PREFIXES = arrayOf("播放", "放一首", "来一首", "来点", "放歌")

        // 控制指令关键词（含大量口语说法：真机实测用户说 "别唱了" 时，
        // 旧词表未命中 → 不发 abort 且落库为聊天消息，服务器 AI 接着这句话闲聊）
        private val PAUSE_KEYWORDS = arrayOf(
            "暂停播放", "暂停音乐", "暂停一下", "暂停", "停一下", "停一会", "先停",
        )
        private val RESUME_KEYWORDS = arrayOf(
            "继续播放", "继续放", "接着放", "接着唱", "恢复播放", "继续", "恢复",
        )
        private val STOP_KEYWORDS = arrayOf(
            "停止播放", "停止", "停掉", "停下", "别放了", "别唱了", "别唱", "别念了",
            "不听了", "不放了", "关掉音乐", "关闭音乐", "关掉", "别说话", "安静点",
            "安静", "闭嘴", "stop",
        )
        private val NEXT_KEYWORDS = arrayOf(
            "下一首", "下一曲", "下首", "下一个", "切歌", "换一首", "换首歌", "换一个",
        )
        private val PREVIOUS_KEYWORDS = arrayOf(
            "上一首", "上一曲", "上首", "前一个", "回到上一首",
        )
        private val RANDOM_KEYWORDS = arrayOf("随机播放", "随便放一首", "随机来一首", "随便播放")

        // 专辑指令关键词
        private val ALBUM_KEYWORDS = arrayOf("的专辑", "专辑")

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
