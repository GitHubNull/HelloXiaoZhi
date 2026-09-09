package org.oxff.helloxiaozhi.music

/**
 * 音乐关键词归一化器：从口语化 STT 文本中提取可用于曲库检索的候选关键词。
 *
 * 为什么用「候选列表 + 曲库反查」而不是「一次性清洗出唯一关键词」：
 * 口语指令里目标词的位置与包裹形式高度不定。真机实测 "你先帮我播放音乐吧，
 * 那个那个韩宝仪的音乐。" 中，歌手名被句中泛化词（"音乐吧"）、口头填充词
 * （"那个那个"）与末尾装饰词（"的音乐"）三重包裹，任何单串清洗规则都会被
 * 带偏（实测提取出 "音乐吧，那个那个韩宝" 而搜不到）。改为把句子切成多个
 * 候选片段分别清洗，再交给曲库逐个验证，由曲库的实际内容裁决哪个片段是
 * 真正的曲名/歌手名，鲁棒性远高于穷举装饰词。
 *
 * 每个片段同时产出「激进清洗」（剥离填充词）与「保守清洗」（保留填充词）两个
 * 变体，激进版排在前面：填充词剥离可能误伤本身以 "那个/这个" 开头的曲名，
 * 误伤时保守变体仍能兜住。
 *
 * 纯 Kotlin 实现，无 Android 依赖，由关键词兜底路径（[MusicActionMapper]）与
 * MCP 工具路径（RobotActionRegistry 的 self.music.play）共用。
 */
object MusicKeywordNormalizer {

    /**
     * 生成候选关键词列表（按反查优先级排序）。
     *
     * 不含分隔标点的候选排在前面（曲名/歌手名极少含逗号，命中即精确）；
     * 含标点的整句候选作为末尾兜底，覆盖 "Hello, World" 这类曲名本身带
     * 分隔符的情况——它们会被子句切分拆散，只能靠整句候选找回。
     *
     * @param raw STT 原始文本或服务器 AI 传入的 track 参数
     * @return 候选关键词，可能为空（无任何有效片段时）
     */
    fun candidates(raw: String): List<String> {
        val normalized = raw.trim().trim(*EDGE_PUNCTUATION)
        if (normalized.isEmpty()) return emptyList()

        val preferred = LinkedHashSet<String>()
        val fallback = LinkedHashSet<String>()

        addCandidate(preferred, fallback, cleanSegment(normalized, aggressive = true))
        addCandidate(preferred, fallback, cleanSegment(normalized, aggressive = false))

        for (clause in splitClauses(normalized)) {
            addCandidate(preferred, fallback, cleanSegment(clause, aggressive = true))
            addCandidate(preferred, fallback, cleanSegment(clause, aggressive = false))
        }

        return preferred.toList() + fallback.toList()
    }

    /** 取最优候选（无曲库可反查时用于日志展示或降级搜索） */
    fun primary(raw: String): String? = candidates(raw).firstOrNull()

    private fun addCandidate(
        preferred: MutableSet<String>,
        fallback: MutableSet<String>,
        value: String,
    ) {
        if (!isValidKeyword(value)) return
        if (value.any { it in CLAUSE_DELIMITERS }) fallback.add(value) else preferred.add(value)
    }

    /** 按分隔标点切分子句（空片段丢弃） */
    private fun splitClauses(text: String): List<String> {
        val parts = StringBuilder()
        val clauses = mutableListOf<String>()
        for (ch in text) {
            if (ch in CLAUSE_DELIMITERS) {
                if (parts.isNotEmpty()) {
                    clauses.add(parts.toString())
                    parts.setLength(0)
                }
            } else {
                parts.append(ch)
            }
        }
        if (parts.isNotEmpty()) clauses.add(parts.toString())
        return clauses
    }

    /**
     * 清洗单个片段：剥引导词 → 剥播放动词 → （激进时）剥填充词 → 剥量词 →
     * 剥末尾语气词 → 剥装饰后缀，循环直到稳定。
     */
    private fun cleanSegment(text: String, aggressive: Boolean): String {
        var result = text.trim().trim(*EDGE_PUNCTUATION)
        if (result.isEmpty()) return ""
        var pass = 0
        while (pass++ < MAX_PASSES) {
            val before = result
            result = stripPrefixes(result, POLITE_LEADS)
            result = stripPrefixes(result, PLAY_VERBS)
            if (aggressive) {
                result = stripPrefixes(result, FILLERS)
            }
            result = stripPrefixes(result, LEADING_QUANTIFIERS)
            result = result.trim(*EDGE_PUNCTUATION)
            result = result.trimEnd(*TRAILING_PARTICLES)
            result = stripSuffixes(result, DECORATION_SUFFIXES)
            result = result.trim(*EDGE_PUNCTUATION)
            result = result.trimEnd(*TRAILING_PARTICLES)
            if (result == before) break
        }
        return result
    }

    /** 循环剥离开头词（长词优先，且不允许把片段剥空） */
    private fun stripPrefixes(text: String, words: Array<String>): String {
        var result = text
        var pass = 0
        while (pass++ < MAX_PASSES) {
            val before = result
            for (w in words) {
                if (result.startsWith(w) && result.length > w.length) {
                    result = result.removePrefix(w).trim()
                    break
                }
            }
            if (result == before) break
        }
        return result
    }

    /** 循环剥离结尾词（长词优先，且不允许把片段剥空） */
    private fun stripSuffixes(text: String, words: Array<String>): String {
        var result = text
        var pass = 0
        while (pass++ < MAX_PASSES) {
            val before = result
            for (w in words) {
                if (result.endsWith(w) && result.length > w.length) {
                    result = result.removeSuffix(w).trim()
                    break
                }
            }
            if (result == before) break
        }
        return result
    }

    /**
     * 候选有效性：过短、纯泛化词（"音乐"/"歌曲"）、纯填充词（"就是那个"）
     * 或不含字母数字的片段一律丢弃，避免用这类无信息量的词去搜而误命中同名曲目。
     */
    private fun isValidKeyword(value: String): Boolean {
        if (value.length < MIN_KEYWORD_LENGTH) return false
        if (value in GENERIC_WORDS) return false
        if (isFillerOnly(value)) return false
        return value.any { it.isLetterOrDigit() }
    }

    /**
     * 判断片段是否完全由口头填充词组成（如 "就是那个"、"那个"）。
     *
     * 与 [stripPrefixes] 不同，此处不保留非空尾段：子句 "就是那个" 经保守清洗
     * 后仍为 "就是那个"，若不滤掉会作为候选参与反查而误命中。
     */
    private fun isFillerOnly(value: String): Boolean {
        if (value.isEmpty()) return false
        var rest = value
        var changed = true
        var pass = 0
        while (changed && pass++ < MAX_PASSES) {
            changed = false
            for (f in FILLERS) {
                if (rest.startsWith(f)) {
                    rest = rest.removePrefix(f)
                    changed = true
                    break
                }
            }
        }
        return rest.isEmpty()
    }

    /** 剥离轮次上限（防御性：前缀/后缀互相触发时避免死循环） */
    private const val MAX_PASSES = 8

    /** 候选最短长度（单字关键词误命中率过高） */
    private const val MIN_KEYWORD_LENGTH = 2

    /** 子句分隔符（中英文标点） */
    private val CLAUSE_DELIMITERS = charArrayOf(
        '，', '。', '！', '？', '、', '；', '：',
        ',', '.', '!', '?', ';', ':',
        '\n', '\r', '\t',
    )

    /** 首尾可剥离标点（含书名号/引号/括号，STT 常带） */
    private val EDGE_PUNCTUATION = charArrayOf(
        '。', '！', '？', '，', '、', '；', '：', '…',
        '.', '!', '?', ',', ';', ':',
        '“', '”', '‘', '’', '"', '\'', '《', '》', '〈', '〉',
        '「', '」', '『', '』', '（', '）', '(', ')', '[', ']',
        ' ', '\t', '\n', '\r', '|', '~', '～',
    )

    /** 礼貌/引导前缀（长词优先） */
    private val POLITE_LEADS = arrayOf(
        "能不能麻烦你", "能不能麻烦", "能不能给我", "能不能帮我", "能不能",
        "可不可以", "麻烦你", "麻烦", "请你", "请",
        "你可以", "可以", "我想要", "我想", "我要", "我需要",
        "你先", "你再", "你帮我", "你给我", "帮忙", "帮我", "给我", "替我", "先",
    )

    /** 播放动词前缀（长词优先；"放"/"听"/"唱" 单字置末，避免误剥曲名首字） */
    private val PLAY_VERBS = arrayOf(
        "播放一下", "播放", "点一首", "点歌", "放一下", "放一首", "放首歌",
        "放个", "放首", "放歌", "来一首", "来一曲", "来首", "来点",
        "听一下", "听一首", "听歌", "唱一首",
        "听", "唱", "放",
    )

    /** 口头填充词（仅激进清洗时剥离，误伤由保守变体兜底） */
    private val FILLERS = arrayOf(
        "那个那个", "这个这个", "什么什么", "那个", "这个", "就是", "然后",
        "所以", "另外", "反正", "嗯", "呃", "额", "唉",
    )

    /** 开头量词/指代词（长词优先） */
    private val LEADING_QUANTIFIERS = arrayOf(
        "这一首", "那一首", "这首歌", "那首歌", "随便一首",
        "一首歌", "来一首", "放一首", "点一首",
        "一首", "一曲", "一支", "那首", "这首", "几首",
    )

    /** 结尾装饰词（长词优先，避免 "的歌曲" 被 "的歌" 截断成 "曲"） */
    private val DECORATION_SUFFIXES = arrayOf(
        "的歌曲", "的音乐", "的曲子", "的专辑", "的歌儿", "这首歌", "那首歌",
        "的歌", "首歌", "歌曲", "音乐", "曲目", "曲子", "这首", "那首", "的",
    )

    /** 末尾语气词 */
    private val TRAILING_PARTICLES = charArrayOf(
        '吧', '呗', '啊', '呀', '哦', '噢', '嘛', '呢', '哈', '咯', '啦', '诶',
    )

    /** 纯泛化词：单独出现时不构成有效检索关键词 */
    private val GENERIC_WORDS = setOf(
        "音乐", "歌", "歌曲", "首歌", "曲子", "曲目", "歌儿", "曲",
        "东西", "声音", "节目", "内容",
    )
}
