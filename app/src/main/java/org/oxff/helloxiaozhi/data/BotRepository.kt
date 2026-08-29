package org.oxff.helloxiaozhi.data

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.util.MacGenerator

/**
 * 机器人与会话的本地仓库。
 *
 * 线程模型：内存中的 [AppData] 是唯一事实来源，**所有读写都在主线程**
 * （与 XiaoZhiController 的全部回调保持同一线程，无需加锁）。落盘则 debounce
 * 到单线程 Executor：每条 stt / llm / tts sentence_start 都会触发一次 append，
 * 逐条同步写盘会在主线程上产生可感知的卡顿。
 *
 * 存储介质是单个 JSON 文件而非 SharedPreferences：消息历史体量会增长到数百 KB，
 * 对这么长的字符串反复 commit 是明确的 jank 来源；文件方案也让「重置应用数据」
 * 退化为一次 delete。写入走临时文件 + rename，避免进程被杀时留下半个 JSON。
 *
 * @param file        持久化目标（注入而非从 Context 推导，便于单测）
 * @param seedFactory 首启 / 重置 / 数据损坏时的初始状态工厂
 */
class BotRepository(
    private val file: File,
    private val gson: Gson,
    private val seedFactory: () -> AppData,
) {

    /** 数据变更通知（主线程），用于列表与徽标重渲染 */
    var onDataChanged: (() -> Unit)? = null

    /**
     * 当前正在查看的对话机器人（运行时状态，不落盘），由对话详情页在
     * open / close 时维护。查看期间到达的 AI 消息不计未读：用户正看着
     * 这个对话，回答不应再产生角标提醒。
     *
     * @Volatile：appendMessage 在 OkHttp 工作线程读取，详情页在主线程写入。
     */
    @Volatile
    var visibleBotId: String? = null

    private val ioExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bot-repo-io") }

    private var pendingWrite: ScheduledFuture<*>? = null

    private var data: AppData = load()

    // ---------------- 机器人 ----------------

    fun bots(): List<Bot> = data.bots.toList()

    fun bot(id: String?): Bot? = id?.let { key -> data.bots.firstOrNull { it.id == key } }

    fun botByMac(mac: String): Bot? {
        val normalized = MacGenerator.normalize(mac)
        return data.bots.firstOrNull { MacGenerator.normalize(it.mac) == normalized }
    }

    fun addBot(bot: Bot) {
        data.bots.add(bot)
        persist()
    }

    /** 删除机器人及其会话；最后一个机器人不允许删除，返回 false 表示被拒绝 */
    fun removeBot(id: String): Boolean {
        if (data.bots.size <= 1) return false
        if (!data.bots.removeAll { it.id == id }) return false
        data.conversations.removeAll { it.botId == id }
        if (data.wakeTargetBotId == id) data.wakeTargetBotId = data.bots.firstOrNull()?.id
        if (data.activeBotId == id) data.activeBotId = data.wakeTargetBotId
        persist()
        return true
    }

    /** 标记机器人已完成绑定（OTA 不再返回 activation 字段） */
    fun markActivated(id: String, activated: Boolean) {
        val bot = bot(id) ?: return
        if (bot.activated == activated) return
        bot.activated = activated
        persist()
    }

    // ---------------- 会话 ----------------

    /** 按最后一条消息时间倒序，供聊天 Tab 渲染 */
    fun conversations(): List<Conversation> = data.conversations.sortedByDescending { it.lastTs }

    fun conversation(botId: String?): Conversation? =
        botId?.let { key -> data.conversations.firstOrNull { it.botId == key } }

    fun messages(botId: String?): List<StoredMessage> =
        conversation(botId)?.messages?.toList() ?: emptyList()

    /**
     * 追加一条消息。AI 消息会累加未读（由对话详情在打开时清零），
     * 但该对话正在被查看时（[visibleBotId]）不计未读。
     *
     * @return 落库后的消息；机器人不存在时返回 null（迟到帧对应的机器人已被删除）
     */
    fun appendMessage(
        botId: String,
        role: ChatRole,
        content: String,
        ts: Long = System.currentTimeMillis(),
    ): StoredMessage? {
        if (bot(botId) == null) return null
        val conversation = data.conversations.firstOrNull { it.botId == botId }
            ?: Conversation(botId).also { data.conversations.add(it) }
        val message = StoredMessage(role, content, ts)
        conversation.messages.add(message)
        // 上限裁剪：历史无限增长会让 JSON 序列化与列表渲染同时退化
        while (conversation.messages.size > MAX_MESSAGES) {
            conversation.messages.removeAt(0)
        }
        conversation.lastTs = ts
        conversation.lastPreview = content
        if (role == ChatRole.AI && botId != visibleBotId) conversation.unread += 1
        persist()
        return message
    }

    fun clearUnread(botId: String) {
        val conversation = conversation(botId) ?: return
        if (conversation.unread == 0) return
        conversation.unread = 0
        persist()
    }

    fun totalUnread(): Int = data.conversations.sumOf { it.unread }

    // ---------------- 唤醒目标 / 当前机器人 ----------------

    /**
     * 语音唤醒目标。当前仅用于决定启动时的默认机器人与列表徽标展示，
     * 未接入唤醒词检测。
     */
    var wakeTargetBotId: String?
        get() = data.wakeTargetBotId
        set(value) {
            if (data.wakeTargetBotId == value) return
            data.wakeTargetBotId = value
            persist()
        }

    var activeBotId: String?
        get() = data.activeBotId
        set(value) {
            if (data.activeBotId == value) return
            data.activeBotId = value
            persist()
        }

    /** 启动时应打开的机器人：当前机器人 → 唤醒目标 → 第一个 */
    fun defaultBot(): Bot? =
        bot(data.activeBotId) ?: bot(data.wakeTargetBotId) ?: data.bots.firstOrNull()

    // ---------------- 生命周期 ----------------

    /** 清空全部本地数据并回到首启状态（设置页「重置应用数据」） */
    fun resetAll() {
        pendingWrite?.cancel(false)
        pendingWrite = null
        data = seedFactory()
        writeNow(data)
        onDataChanged?.invoke()
    }

    /**
     * 立即落盘（Activity onPause 调用，避免进程被杀丢数据）。
     *
     * 向单线程 IO Executor 提交一个任务并阻塞等待其完成：取消已调度的任务
     * 并不保证它尚未开始执行，直接再投递一次会让新旧两次写入交错、先写新快照
     * 再被旧快照覆盖。串行化 + 等待才能保证「flush 返回时数据一定已落盘」。
     */
    fun flush() {
        pendingWrite?.cancel(false)
        pendingWrite = null
        val snapshot = gson.toJson(data)
        try {
            ioExecutor.submit { writeSnapshot(snapshot) }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 落盘失败不影响本次会话，内存数据仍然有效
        }
    }

    fun shutdown() {
        flush()
        ioExecutor.shutdown()
    }

    // ---------------- 内部 ----------------

    private fun persist() {
        onDataChanged?.invoke()
        pendingWrite?.cancel(false)
        // 拷贝一份快照交给 IO 线程：主线程随后可能继续改动 data
        val snapshot = gson.toJson(data)
        pendingWrite = ioExecutor.schedule(
            { writeSnapshot(snapshot) },
            DEBOUNCE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun writeNow(target: AppData) {
        val snapshot = gson.toJson(target)
        ioExecutor.execute { writeSnapshot(snapshot) }
    }

    /** 临时文件 + rename：进程在写入中途被杀也不会留下半个 JSON */
    private fun writeSnapshot(json: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // 部分文件系统上目标已存在时 rename 会失败，退化为覆写
                file.writeText(json)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 落盘失败不影响本次会话，内存数据仍然有效
        }
    }

    /** 读取；文件缺失、JSON 损坏或版本不匹配都回落到 seed 而非崩溃 */
    private fun load(): AppData {
        val parsed = try {
            if (file.exists()) gson.fromJson(file.readText(), AppData::class.java) else null
        } catch (_: Exception) {
            null
        }
        val valid = parsed
            ?.takeIf { it.version == AppData.CURRENT_VERSION }
            ?.takeIf { it.bots.isNotEmpty() }
        if (valid == null) {
            val seed = seedFactory()
            writeNow(seed)
            return seed
        }
        return valid
    }

    private companion object {
        /** 每机器人保留的消息条数上限 */
        const val MAX_MESSAGES = 500

        /** 落盘防抖窗口：合并一次 AI 回复内多条 sentence_start 的连续写入 */
        const val DEBOUNCE_MS = 300L
    }
}
