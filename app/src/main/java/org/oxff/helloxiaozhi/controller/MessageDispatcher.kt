package org.oxff.helloxiaozhi.controller

import android.os.Handler
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.oxff.helloxiaozhi.chat.ChatMessage
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.chat.HelloResponse
import org.oxff.helloxiaozhi.chat.LlmMessage
import org.oxff.helloxiaozhi.chat.SttMessage
import org.oxff.helloxiaozhi.chat.TtsMessage
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.robot.McpActionHandler
import org.oxff.helloxiaozhi.music.MusicActionMapper
import org.oxff.helloxiaozhi.wake.SherpaOnnxWakeWordEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息分发器：负责文本消息的解析与分发。
 *
 * 职责：
 *  - 解析服务器下发的文本消息（hello/stt/llm/tts）
 *  - 分发消息到对应的处理器
 *  - 管理聊天消息的落库与通知
 *  - 协调消息处理与状态机
 *
 * 从 XiaoZhiController 拆分而来，专注于消息处理职责。
 */
class MessageDispatcher(
    private val gson: Gson,
    private val repository: BotRepository,
    private val mainHandler: Handler,
    private val stateMachine: ChatStateMachine,
) {
    /** 新消息回调 */
    var onChatMessage: ((botId: String, message: ChatMessage) -> Unit)? = null

    /** Hello 消息回调 */
    var onHelloReceived: ((sessionId: String, sampleRate: Int) -> Unit)? = null

    /** TTS 开始回调 */
    var onTtsStart: (() -> Unit)? = null

    /** TTS 停止回调 */
    var onTtsStop: (() -> Unit)? = null

    /** 用户开始说话回调 */
    var onUserStartSpeaking: (() -> Unit)? = null

    /** AI 回复结束语回调（用于自动挂断语音通话） */
    var onAiFarewell: (() -> Unit)? = null

    /** 待处理的结束语标志：检测到结束语后置位，等 AI 语音播放完成后再触发回调 */
    @Volatile
    var pendingFarewell = false
        private set

    /** MCP 响应回调（需要回发给服务器的 JSON 字符串） */
    var onMcpResponse: ((String) -> Unit)? = null

    /**
     * 本地音乐指令已处理回调。
     * 当 stt 文本被本地音乐关键词匹配成功时触发，用于通知外部发送 AbortMessage
     * 打断服务器即将下发的 TTS，避免服务器 AI 播放自己平台的音乐与本地音乐冲突。
     */
    var onLocalMusicHandled: (() -> Unit)? = null

    /** MCP 动作处理器（由 XiaoZhiController 注入） */
    var mcpActionHandler: McpActionHandler? = null

    /** 音乐动作映射器（由 XiaoZhiController 注入） */
    var musicActionMapper: MusicActionMapper? = null

    /**
     * Barge-in 回调：AI 说话期间服务器端 VAD 检测到用户开口（STT 消息）时触发，
     * 由 XiaoZhiController 发送 AbortMessage 打断服务器 TTS 并本地暂停播放。
     */
    var onBargeIn: (() -> Unit)? = null

    /**
     * 唤醒词打断本地音乐回调：本地音乐播放期间收到以唤醒词开头的 STT 时触发，
     * 由 XiaoZhiController 执行 musicPlayer.stop()（onMusicStop 会自动复位
     * isLocalMusicPlaying 并恢复语音通话流程）。
     */
    var onWakeWordInterrupt: (() -> Unit)? = null

    /**
     * 本地音乐播放状态提供者（由 XiaoZhiController 注入 { audioPipeline.isLocalMusicPlaying }）。
     * 用回调而非直接引用 AudioPipeline，避免 MessageDispatcher 反向依赖造成循环引用。
     */
    var isLocalMusicPlayingProvider: (() -> Boolean)? = null

    /**
     * 服务器回复抑制状态提供者（由 XiaoZhiController 注入
     * `{ audioPipeline.isServerReplySuppressed() }`）。
     *
     * 本地音乐控制指令执行后已发 abort，但 abort 到达前服务器可能已下发
     * llm/tts；抑制窗口内丢弃这些文本，避免 "别唱了" 这类指令引出的 AI 回复
     * 落库到聊天记录里。
     */
    var isReplySuppressedProvider: (() -> Boolean)? = null

    /**
     * 清除服务器回复抑制回调：用户开始新一轮真实对话（STT 落库）时触发，
     * 避免抑制窗口吞掉用户紧接着的下一轮提问的 AI 回复。
     */
    var onClearReplySuppress: (() -> Unit)? = null

    /**
     * 处理接收到的文本消息
     */
    fun handleTextMessage(text: String, activeBotId: String?) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }
        val botAtParse = activeBotId
        val type = json.get("type")?.asString
        if (type != "hello") Log.i(TAG, "[WS] text type=$type")
        when (type) {
            "hello" -> handleHello(json)
            "stt" -> handleStt(json, botAtParse)
            "llm" -> handleLlm(json, botAtParse)
            "tts" -> handleTts(json, botAtParse)
            "mcp" -> handleMcp(json)
            else -> Unit
        }
    }

    /**
     * 处理 Hello 消息
     */
    private fun handleHello(json: JsonObject) {
        val hello = gson.fromJson(json, HelloResponse::class.java)
        hello.sessionId?.let { sessionId ->
            val rate = hello.audioParams?.sampleRate ?: DEFAULT_SAMPLE_RATE
            Log.i(TAG, "[WS] hello: sessionId=$sessionId, sampleRate=$rate")
            onHelloReceived?.invoke(sessionId, rate)
        }
    }

    /**
     * 处理 STT 消息（用户语音识别结果）
     */
    private fun handleStt(json: JsonObject, botAtParse: String?) {
        val message = gson.fromJson(json, SttMessage::class.java)
        val rawText = message.text?.trim()?.takeIf { it.isNotEmpty() }

        // 本地音乐播放中（isLocalMusicPlaying），或音乐指令后的免疫期内（isReplySuppressed），
        // 都走唤醒词门控路径。音乐已停后，同一句语音可能被服务器识别出第二条 STT
        // （真机实测「阿妹阿妹，别唱了」后紧跟一条「这。」），它不带唤醒词，若直接落库会
        // 污染聊天记录并清除抑制窗口（[handleSttText] 的 onClearReplySuppress），
        // 让 AI 围绕这句尾巴回复。免疫期内无唤醒词的 STT 一律忽略。
        if (rawText != null &&
            (isLocalMusicPlayingProvider?.invoke() == true || isReplySuppressed())
        ) {
            handleSttDuringMusic(rawText, botAtParse)
            return
        }

        rawText?.let { handleSttText(it, botAtParse, musicActive = false) }
    }

    /**
     * 本地音乐播放期间的 STT 处理：唤醒词门控 + 指令分类。
     *
     * 关键顺序：**先判定是不是音乐控制指令，再决定要不要停音乐**。
     * 旧实现无条件先调 [onWakeWordInterrupt] 停音乐，导致 "阿妹阿妹，下一首"
     * 会先把播放彻底终止、再执行已无意义的 next()，切歌失效。
     */
    private fun handleSttDuringMusic(rawText: String, botAtParse: String?) {
        val stripped = stripWakeWordPrefix(rawText)
        if (stripped == null) {
            // 非唤醒词开头：环境音/对他人说话，直接忽略（不落库、不迁移状态、音乐照播）
            Log.i(TAG, "[WS] stt dropped (local music playing, no wake word): 「$rawText」")
            return
        }
        if (stripped.isEmpty()) {
            // 只喊了唤醒词：停音乐，等用户接着说
            Log.i(TAG, "[WS] wake word only, stop music and wait: 「$rawText」")
            onWakeWordInterrupt?.invoke()
            return
        }

        // 唤醒词 + 后续文本：音乐控制指令属纯设备操作，不是对话内容
        val mapper = musicActionMapper
        if (mapper != null &&
            (mapper.isControlCommand(stripped) || mapper.isPlayRequest(stripped)) &&
            mapper.processText(stripped)
        ) {
            // 本地已执行控制动作（stop/pause/next/...）：发 abort 取消服务器回复
            // 并开启抑制窗口，且不落库、不迁移状态——避免 AI 接着 "别唱了"
            // 这类指令闲聊（用户实测的尴尬场景）
            Log.i(TAG, "[WS] local music command handled, suppress server reply: 「$stripped」")
            onLocalMusicHandled?.invoke()
            return
        }

        // 非控制指令 → 用户是想对话：停音乐后走正常聊天流程
        Log.i(TAG, "[WS] wake word interrupt for chat: 「$rawText」")
        onWakeWordInterrupt?.invoke()
        handleSttText(stripped, botAtParse, musicActive = false)
    }

    /**
     * STT 文本的常规处理：音乐指令拦截 → 落库 → 状态迁移
     *
     * @param musicActive 本地音乐是否正在播放。仅播放期间才拦截「控制类」指令
     *   （停止/暂停/切歌）；无音乐时这类词很可能是正常聊天内容
     *   （"你别说话"/"先停下来"），拦截会吞掉用户对话并误发 abort。
     *   「播放类」指令（点歌）表达明确听歌意图，任何场景都本地处理。
     */
    private fun handleSttText(text: String, botAtParse: String?, musicActive: Boolean) {
        Log.i(TAG, "[WS] stt text=「$text」 musicActive=$musicActive")

        // 用户开始新一轮真实对话：清除上一轮本地指令遗留的回复抑制，
        // 否则这一轮的 AI 回复会被误丢
        onClearReplySuppress?.invoke()

        val mapper = musicActionMapper
        if (mapper != null) {
            val intercept = mapper.isPlayRequest(text) ||
                (musicActive && mapper.isControlCommand(text))
            if (intercept && mapper.processText(text)) {
                Log.i(TAG, "[WS] stt matched music command, skip server processing")
                // 通知外部打断服务器 TTS：服务器已收到该语音，AI 可能会播放自己平台的音乐，
                // 需要发送 AbortMessage 阻止，避免与本地播放的音乐冲突
                onLocalMusicHandled?.invoke()
                return
            }
        }

        // 去重检查：如果最近的 USER 消息与当前 STT 文本相同，说明是文字输入的消息，
        // 已经在 sendTextMessage() 中添加过了，跳过避免重复
        val isDuplicate = botAtParse?.let { botId ->
            repository.messages(botId).lastOrNull()?.let { lastMsg ->
                lastMsg.role == ChatRole.USER && lastMsg.content == text
            }
        } ?: false
        
        if (!isDuplicate) {
            appendChat(botAtParse, ChatRole.USER, text)
        } else {
            Log.i(TAG, "[WS] stt text duplicate (from text input), skip append: 「$text」")
        }

        // 服务器端 VAD 检测到用户说话
        mainHandler.post {
            when (stateMachine.state) {
                ChatState.IDLE -> {
                    stateMachine.setState(ChatState.USER_SPEAKING)
                    onUserStartSpeaking?.invoke()
                }
                ChatState.AI_SPEAKING -> {
                    // Barge-in（服务器 STT 路径，客户端本地电平检测为主路径）：
                    // AI 说话期间服务器仍下发了 stt，说明用户已开口
                    pendingFarewell = false
                    onBargeIn?.invoke()
                    stateMachine.setState(ChatState.USER_SPEAKING)
                    onUserStartSpeaking?.invoke()
                }
                else -> Unit
            }
        }
    }

    /**
     * 剥离唤醒词前缀：返回唤醒词后的剩余文本；不以唤醒词开头返回 null。
     * 匹配规则：text.startsWith(唤醒词)，剥离前缀及紧随的分隔符（，、,.。 等）后 trim。
     */
    private fun stripWakeWordPrefix(text: String): String? {
        for (wakeWord in WAKE_WORDS) {
            if (text.startsWith(wakeWord)) {
                return text.substring(wakeWord.length)
                    .trimStart { it in WAKE_WORD_SEPARATORS }
                    .trim()
            }
        }
        return null
    }

    /**
     * 处理 LLM 消息（模型回复文本）
     */
    private fun handleLlm(json: JsonObject, botAtParse: String?) {
        val message = gson.fromJson(json, LlmMessage::class.java)
        message.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (isReplySuppressed()) {
                Log.i(TAG, "[WS] llm dropped (server reply suppressed): 「$it」")
                return@let
            }
            Log.i(TAG, "[WS] llm text=「$it」")
            appendChat(botAtParse, ChatRole.AI, it)
            // 检测结束语，标记待处理（等 AI 语音播放完成后再触发回调）
            if (isFarewellMessage(it)) {
                Log.i(TAG, "[WS] 检测到 AI 结束语，标记待处理")
                pendingFarewell = true
            }
        }
    }

    /** 当前是否处于服务器回复抑制窗口内（本地音乐控制指令刚执行完） */
    private fun isReplySuppressed(): Boolean = isReplySuppressedProvider?.invoke() == true

    /**
     * 检测 AI 回复是否为结束语（晚安、拜拜等）
     */
    private fun isFarewellMessage(text: String): Boolean {
        val lowerText = text.lowercase()
        return FAREWELL_KEYWORDS.any { keyword -> lowerText.contains(keyword) }
    }

    /**
     * 处理 TTS 消息
     */
    private fun handleTts(json: JsonObject, botAtParse: String?) {
        val tts = gson.fromJson(json, TtsMessage::class.java)
        when (tts.state) {
            TtsMessage.STATE_START -> {
                if (isReplySuppressed()) {
                    Log.i(TAG, "[WS] tts start dropped (server reply suppressed)")
                } else {
                    onTtsStart?.invoke()
                }
            }
            TtsMessage.STATE_SENTENCE_START -> {
                val text = tts.text?.trim().orEmpty()
                if (text.isNotEmpty() && !text.startsWith("%")) {
                    if (isReplySuppressed()) {
                        Log.i(TAG, "[WS] tts sentence dropped (server reply suppressed): 「$text」")
                    } else {
                        Log.i(TAG, "[WS] tts sentence=「$text」")
                        appendChat(botAtParse, ChatRole.AI, text)
                        // TTS 句子也可能是结束语（小智协议中 AI 回复文本可能通过 tts 下发）
                        if (isFarewellMessage(text)) {
                            Log.i(TAG, "[WS] 检测到 AI 结束语(tts)，标记待处理")
                            pendingFarewell = true
                        }
                    }
                }
            }
            TtsMessage.STATE_STOP -> {
                onTtsStop?.invoke()
            }
            else -> Unit
        }
    }

    /**
     * 处理 MCP 消息（服务器下发的工具调用）
     */
    private fun handleMcp(json: JsonObject) {
        val payload = json.getAsJsonObject("payload") ?: run {
            Log.w(TAG, "[WS] mcp message missing payload")
            return
        }
        val handler = mcpActionHandler ?: run {
            Log.w(TAG, "[WS] mcp received but no handler configured")
            return
        }
        Log.i(TAG, "[WS] mcp method=${payload.get("method")?.asString}")
        val response = handler.handleMcpMessage(payload) ?: return
        // 将响应封装为 mcp 消息回发
        val responseWrapper = JsonObject()
        responseWrapper.addProperty("type", "mcp")
        json.get("session_id")?.let { responseWrapper.add("session_id", it) }
        responseWrapper.add("payload", response)
        onMcpResponse?.invoke(responseWrapper.toString())
    }

    /**
     * 追加聊天消息到仓库并通知 UI
     */
    private fun appendChat(botId: String?, role: ChatRole, content: String) {
        val id = botId ?: return
        val stored = repository.appendMessage(id, role, content) ?: return
        val message = ChatMessage(
            role = role,
            content = content,
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(stored.ts)),
        )
        mainHandler.post { onChatMessage?.invoke(id, message) }
    }

    /**
     * 消费待处理的结束语：AI 语音播放完成后调用，返回 true 表示有待处理的结束语
     */
    fun consumePendingFarewell(): Boolean {
        val pending = pendingFarewell
        pendingFarewell = false
        return pending
    }

    /**
     * 清除待处理的结束语标志（用户打断 AI 时调用）。
     *
     * 打断会清空播放队列，onQueueEmpty 不再触发；若不清除，残留的结束语
     * 标志会在后续任意一次队列播空时误触发自动挂断。
     */
    fun clearPendingFarewell() {
        if (pendingFarewell) {
            Log.i(TAG, "[WS] 用户打断，清除待处理结束语标志")
            pendingFarewell = false
        }
    }

    private companion object {
        const val TAG = "MessageDispatcher"
        const val DEFAULT_SAMPLE_RATE = 16000

        /** AI 结束语关键词（用于自动挂断语音通话） */
        val FAREWELL_KEYWORDS = listOf(
            "晚安", "拜拜", "再见", "拜", "bye", "goodbye", "good night", "goodnight",
            "退下", "先退", "我走了", "先走了", "告辞", "失陪"
        )

        /**
         * 唤醒词列表（用于本地音乐播放期间的 STT 前缀打断匹配）。
         * 与 KWS 引擎配置解耦：STT 文本匹配不依赖引擎；若唤醒词可配置需同步该列表。
         */
        val WAKE_WORDS = listOf(SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD, "小智小智")

        /** 唤醒词后可跟随的分隔符（剥离前缀时一并去除） */
        private val WAKE_WORD_SEPARATORS = charArrayOf('，', '、', ',', '。', '.', '！', '!', '？', '?', ' ')
    }
}
