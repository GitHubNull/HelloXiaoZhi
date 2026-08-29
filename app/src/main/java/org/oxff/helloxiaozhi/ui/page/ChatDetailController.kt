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
    private val msgList = container.findViewById<RecyclerView>(R.id.msg_list)
    private val input = container.findViewById<EditText>(R.id.msg_input)
    private val btnSend = container.findViewById<ImageButton>(R.id.btn_send)
    private val btnCall = container.findViewById<ImageButton>(R.id.btn_call)
    private val btnBack = container.findViewById<ImageButton>(R.id.btn_back_chat)

    private val adapter = MessageAdapter()
    private var openBotId: String? = null

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
        repository.clearUnread(botId)
        botName.text = bot.name
        updateStatus(bot)
        adapter.submit(repository.messages(botId))
        scrollBottom()
        container.open()
    }

    fun close() {
        openBotId = null
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
        repository.messages(botId).lastOrNull()?.let {
            adapter.add(it)
            scrollBottom()
        }
    }

    private fun updateStatus(bot: Bot, status: ConnectionStatus = controller.connectionStatus) {
        val context = container.context
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
        controller.sendTextMessage(text)
        input.setText("")
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
