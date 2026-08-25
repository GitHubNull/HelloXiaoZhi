package org.oxff.helloxiaozhi.chat

import com.google.gson.annotations.SerializedName

/**
 * 消息模型，逐字段对应 ref 前端 src/types/message.ts。
 *
 * 上行消息（设备端 -> 服务器）：
 *  - HelloMessage:    连接建立后握手，声明 Opus 音频参数
 *  - ListenMessage:   开始/停止监听（state = start / stop）
 *  - DetectMessage:   文字聊天输入（state = detect，携带文本）
 *  - AbortMessage:    打断服务器 TTS
 *
 * 下行消息（服务器 -> 设备端）：
 *  - HelloResponse:   握手应答，携带 session_id 与服务器音频参数
 *  - SttMessage:      语音识别结果（用户说话内容回显）
 *  - LlmMessage:      大模型表情/情绪消息
 *  - TtsMessage:      TTS 状态机（start / stop / sentence_start / sentence_end）
 */

// ==================== 上行消息 ====================

/** 音频参数声明（与 hello 消息一起发送，frame_duration 60 即每帧 960 采样 @16kHz） */
data class AudioParams(
    val format: String = "opus",
    @SerializedName("sample_rate") val sampleRate: Int = 16000,
    val channels: Int = 1,
    @SerializedName("frame_duration") val frameDuration: Int = 60,
)

/** 连接建立后的握手消息（version 3 与 Web 端保持一致） */
data class HelloMessage(
    val type: String = "hello",
    val version: Int = 3,
    val transport: String = "websocket",
    @SerializedName("audio_params") val audioParams: AudioParams = AudioParams(),
)

/** 开始/停止监听消息（session_id 对齐 ESP32 protocol.cc SendStartListening/SendStopListening） */
data class ListenMessage(
    val type: String = "listen",
    val state: String,
    val mode: String = "auto",
    @SerializedName("session_id") val sessionId: String,
) {
    companion object {
        fun start(sessionId: String) = ListenMessage(state = "start", sessionId = sessionId)
        fun stop(sessionId: String) = ListenMessage(state = "stop", sessionId = sessionId)
    }
}

/** 文字输入消息（对应 UserMessage：state = detect，携带文本） */
data class DetectMessage(
    val type: String = "listen",
    val state: String = "detect",
    val text: String,
    val source: String = "text",
)

/** 打断消息（对应 AbortMessage） */
data class AbortMessage(
    val type: String = "abort",
    @SerializedName("session_id") val sessionId: String,
)

// ==================== 下行消息 ====================

/** 服务器握手应答 */
data class HelloResponse(
    val type: String = "hello",
    val transport: String = "websocket",
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("audio_params") val audioParams: AudioParams? = null,
)

/** 语音识别结果（用户说话内容回显） */
data class SttMessage(
    val type: String = "stt",
    val text: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
)

/** 大模型消息（emotion 为表情/情绪文本） */
data class LlmMessage(
    val type: String = "llm",
    val text: String? = null,
    val emotion: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
)

/** TTS 状态消息 */
data class TtsMessage(
    val type: String = "tts",
    val state: String? = null,
    val text: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
) {
    companion object {
        const val STATE_START = "start"
        const val STATE_STOP = "stop"
        const val STATE_SENTENCE_START = "sentence_start"
        const val STATE_SENTENCE_END = "sentence_end"
    }
}
