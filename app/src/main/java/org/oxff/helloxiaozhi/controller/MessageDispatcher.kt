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

    /** MCP 动作处理器（由 XiaoZhiController 注入） */
    var mcpActionHandler: McpActionHandler? = null

    /** 音乐动作映射器（由 XiaoZhiController 注入） */
    var musicActionMapper: MusicActionMapper? = null

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
        message.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            Log.i(TAG, "[WS] stt text=「$text」")

            // 音乐关键词匹配降级：若匹配成功则直接执行本地音乐控制，不再走服务器流程
            if (musicActionMapper?.processText(text) == true) {
                Log.i(TAG, "[WS] stt matched music command, skip server processing")
                return@let
            }

            appendChat(botAtParse, ChatRole.USER, text)
        }
        // 服务器端 VAD 检测到用户说话
        mainHandler.post {
            if (stateMachine.state == ChatState.IDLE) {
                stateMachine.setState(ChatState.USER_SPEAKING)
                onUserStartSpeaking?.invoke()
            }
        }
    }

    /**
     * 处理 LLM 消息（模型回复文本）
     */
    private fun handleLlm(json: JsonObject, botAtParse: String?) {
        val message = gson.fromJson(json, LlmMessage::class.java)
        message.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Log.i(TAG, "[WS] llm text=「$it」")
            appendChat(botAtParse, ChatRole.AI, it)
            // 检测结束语，标记待处理（等 AI 语音播放完成后再触发回调）
            if (isFarewellMessage(it)) {
                Log.i(TAG, "[WS] 检测到 AI 结束语，标记待处理")
                pendingFarewell = true
            }
        }
    }

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
                onTtsStart?.invoke()
            }
            TtsMessage.STATE_SENTENCE_START -> {
                val text = tts.text?.trim().orEmpty()
                if (text.isNotEmpty() && !text.startsWith("%")) {
                    Log.i(TAG, "[WS] tts sentence=「$text」")
                    appendChat(botAtParse, ChatRole.AI, text)
                    // TTS 句子也可能是结束语（小智协议中 AI 回复文本可能通过 tts 下发）
                    if (isFarewellMessage(text)) {
                        Log.i(TAG, "[WS] 检测到 AI 结束语(tts)，标记待处理")
                        pendingFarewell = true
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

    private companion object {
        const val TAG = "MessageDispatcher"
        const val DEFAULT_SAMPLE_RATE = 16000

        /** AI 结束语关键词（用于自动挂断语音通话） */
        val FAREWELL_KEYWORDS = listOf(
            "晚安", "拜拜", "再见", "拜", "bye", "goodbye", "good night", "goodnight",
            "退下", "先退", "我走了", "先走了", "告辞", "失陪"
        )
    }
}
