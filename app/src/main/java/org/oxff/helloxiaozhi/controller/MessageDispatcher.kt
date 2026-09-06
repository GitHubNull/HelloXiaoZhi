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
        message.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Log.i(TAG, "[WS] stt text=「$it」")
            appendChat(botAtParse, ChatRole.USER, it)
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
        }
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
                }
            }
            TtsMessage.STATE_STOP -> {
                onTtsStop?.invoke()
            }
            else -> Unit
        }
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

    private companion object {
        const val TAG = "MessageDispatcher"
        const val DEFAULT_SAMPLE_RATE = 16000
    }
}
