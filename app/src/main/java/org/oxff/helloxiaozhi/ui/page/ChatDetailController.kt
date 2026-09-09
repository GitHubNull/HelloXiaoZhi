package org.oxff.helloxiaozhi.ui.page

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.MessageAdapter
import org.oxff.helloxiaozhi.ui.view.SlideInContainer
import org.oxff.helloxiaozhi.ui.view.ToastHost

/**
 * 对话详情滑入层控制器（对应设计稿 chat-detail.js）。
 *
 * 消息渲染、发送、连接状态门控（未连接时禁用输入与通话按钮）、
 * 按机器人过滤消息（只展示当前打开的机器人）。
 */
class ChatDetailController(
    private val container: SlideInContainer,
    private val repository: BotRepository,
    private val controller: XiaoZhiController,
    private val toast: ToastHost,
    private val onStartCall: () -> Unit,
) {

    private val botName = container.findViewById<TextView>(R.id.detail_bot_name)
    private val botStatus = container.findViewById<TextView>(R.id.detail_bot_status)
    private val statusLine = container.findViewById<View>(R.id.detail_status_line)
    private val msgList = container.findViewById<RecyclerView>(R.id.msg_list)
    private val input = container.findViewById<EditText>(R.id.msg_input)
    private val btnSend = container.findViewById<ImageButton>(R.id.btn_send)
    private val btnCall = container.findViewById<ImageButton>(R.id.btn_call)
    private val btnBack = container.findViewById<ImageButton>(R.id.btn_back_chat)

    private val adapter = MessageAdapter()
    private var openBotId: String? = null
    
    /** 当前打开的对话消息缓存（避免每次 onChatMessage 都从 repository 全量查询） */
    private var cachedMessages: MutableList<org.oxff.helloxiaozhi.data.StoredMessage> = mutableListOf()

    init {
        msgList.layoutManager = LinearLayoutManager(container.context)
        msgList.adapter = adapter
        btnBack.setOnClickListener { close() }
        btnSend.setOnClickListener { sendCurrent() }
        btnCall.setOnClickListener { tryStartCall() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent()
                true
            } else false
        }
    }

    val isOpen: Boolean get() = container.isOpen

    fun open(botId: String) {
        val bot = repository.bot(botId) ?: return
        openBotId = botId
        // 先标记正在查看再清零：此后到达的 AI 回复不再计未读，
        // 避免用户眼看着回答却仍被角标提醒（退出重进才消）
        repository.visibleBotId = botId
        repository.clearUnread(botId)
        botName.text = bot.name
        updateStatus(bot)
        cachedMessages = repository.messages(botId).toMutableList()
        adapter.submit(cachedMessages)
        scrollBottom()
        container.open()
        
        // 打开聊天详情时确保连接已建立（修复：进入详情页时连接可能已断开）
        if (controller.connectionStatus != ConnectionStatus.CONNECTED) {
            controller.ensureConnected()
        }
    }

    fun close() {
        openBotId = null
        cachedMessages.clear()
        repository.visibleBotId = null
        container.close()
    }

    /** 连接状态变化时刷新副标题与输入可用性 */
    fun onConnectionStatusChanged(status: ConnectionStatus) {
        val bot = repository.bot(openBotId) ?: return
        updateStatus(bot, status)
    }

    /** 新消息到达：只展示当前打开的机器人，其余由未读徽标体现 */
    fun onChatMessage(botId: String, message: org.oxff.helloxiaozhi.chat.ChatMessage) {
        if (botId != openBotId) return
        // 从缓存中取最后一条消息，避免每次从 repository 全量查询
        val lastMsg = cachedMessages.lastOrNull()
        if (lastMsg != null && lastMsg.content == message.content && lastMsg.role == message.role) {
            // 重复消息（乐观更新已添加），跳过
            return
        }
        // 添加新消息到缓存和适配器
        val stored = org.oxff.helloxiaozhi.data.StoredMessage(
            role = message.role,
            content = message.content,
            ts = System.currentTimeMillis()
        )
        cachedMessages.add(stored)
        adapter.add(stored)
        scrollBottom()
    }

    private fun updateStatus(bot: Bot, status: ConnectionStatus = controller.connectionStatus) {
        val context = container.context
        // 连接状态指示线：机器人名下方，按 ConnectionStatus 改色（复用现有四色）
        val lineColorRes = when (status) {
            ConnectionStatus.CONNECTED -> R.color.xz_status_connected
            ConnectionStatus.CONNECTING -> R.color.xz_status_connecting
            ConnectionStatus.DISCONNECTED -> R.color.xz_status_disconnected
            ConnectionStatus.ERROR -> R.color.xz_status_error
        }
        (statusLine.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(androidx.core.content.ContextCompat.getColor(context, lineColorRes))
        statusLine.visibility = View.VISIBLE
        val (textRes, enabled) = when (status) {
            ConnectionStatus.CONNECTED -> R.string.status_connected to true
            ConnectionStatus.CONNECTING -> R.string.status_connecting to false
            ConnectionStatus.DISCONNECTED -> R.string.status_disconnected to false
            ConnectionStatus.ERROR -> R.string.status_error to false
        }
        // 已连接时副标题显示机器人的性格标签，否则显示连接状态
        botStatus.text = if (status == ConnectionStatus.CONNECTED) {
            bot.tags.joinToString("、").ifEmpty { context.getString(textRes) }
        } else {
            context.getString(textRes)
        }
        input.isEnabled = enabled
        btnSend.isEnabled = enabled
        btnCall.isEnabled = enabled
        input.alpha = if (enabled) 1f else 0.5f
        btnSend.alpha = if (enabled) 1f else 0.5f
        btnCall.alpha = if (enabled) 1f else 0.5f
    }

    private fun sendCurrent() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (controller.connectionStatus != ConnectionStatus.CONNECTED) {
            toast.show(
                container.context.getString(R.string.toast_not_connected),
                ToastHost.Kind.ERROR,
            )
            return
        }
        
        // 发送消息（内部已实现乐观更新，会立即在本地添加用户消息）
        controller.sendTextMessage(text)
        
        // 立即清空输入框并滚动到底部（乐观更新的 UI 反馈）
        input.setText("")
        scrollBottom()
    }

    private fun tryStartCall() {
        if (controller.connectionStatus != ConnectionStatus.CONNECTED) {
            toast.show(
                container.context.getString(R.string.toast_not_connected),
                ToastHost.Kind.ERROR,
            )
            return
        }
        onStartCall()
    }

    private fun scrollBottom() {
        msgList.post { msgList.scrollToPosition(adapter.itemCount - 1) }
    }
}
