package org.oxff.helloxiaozhi.data

import org.oxff.helloxiaozhi.chat.ChatRole

/**
 * AI 机器人，对应设计稿通讯录的一条记录（mock.js 的 XZ_DEFAULT_BOTS 元素）。
 *
 * 与设计稿 mock 的两处关键差异：
 *  - `avatarColorIndex` 存调色板索引，而不是 mock 的 CSS 渐变字符串
 *    （`linear-gradient(...)` 是浏览器专有表达，落地 Android 必须与平台解耦）；
 *  - 丢弃 mock 的 `personality` / `greeting`：真实人格与问候语由小智服务端持有，
 *    客户端不再本地编造，对话详情副标题改用 `tags` 拼接。
 *
 * `mac` 即握手时的 `Device-Id`，也是 OTA 注册的 `mac_address`——每个机器人是
 * 一个**独立设备身份**，需各自在小智控制台完成绑定。
 */
data class Bot(
    val id: String,
    var name: String,
    var avatarText: String,
    var avatarColorIndex: Int,
    var tags: List<String>,
    var desc: String,
    var mac: String,
    /** 最近一次 OTA 注册未再返回 activation 字段，即已完成绑定 */
    var activated: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * 与某个机器人的会话。
 *
 * `lastTs` 是排序依据；展示串由 TimeFormat 现算，不落库。
 */
data class Conversation(
    val botId: String,
    val messages: MutableList<StoredMessage> = mutableListOf(),
    var lastTs: Long = 0L,
    var lastPreview: String = "",
    var unread: Int = 0,
)

/** 一条已持久化的消息 */
data class StoredMessage(
    val role: ChatRole,
    val content: String,
    val ts: Long,
)

/**
 * 持久化根文档。
 *
 * `version` 用于将来结构变更时判定是否重建；当前版本不匹配即回落到 seed，
 * 而不是尝试迁移——本地数据全部可从服务端重新产生，不值得为兼容付出复杂度。
 */
data class AppData(
    var version: Int = CURRENT_VERSION,
    val bots: MutableList<Bot> = mutableListOf(),
    val conversations: MutableList<Conversation> = mutableListOf(),
    /** 语音唤醒目标；当前仅决定启动时的默认机器人，未接入唤醒词检测 */
    var wakeTargetBotId: String? = null,
    var activeBotId: String? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
