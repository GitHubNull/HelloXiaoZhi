package org.oxff.helloxiaozhi.chat

/** 语音通话状态（对应 ref types/chat.ts 的 ChatState） */
enum class ChatState { IDLE, USER_SPEAKING, AI_SPEAKING }

/** 状态机事件（对应 ref types/chat.ts 的 ChatEvent） */
enum class ChatEvent {
    USER_START_SPEAKING,
    USER_STOP_SPEAKING,
    AI_START_SPEAKING,
    AI_STOP_SPEAKING,
}

/** 聊天消息角色（对应 ref types/message.ts 的 Role） */
enum class ChatRole { USER, AI }

/** 聊天列表条目（对应 ref types/message.ts 的 Message） */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val time: String,
)

/** WebSocket 连接状态（对应 Web 端 connectionStatus） */
enum class ConnectionStatus { CONNECTED, DISCONNECTED, ERROR }
