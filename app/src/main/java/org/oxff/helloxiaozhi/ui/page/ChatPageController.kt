package org.oxff.helloxiaozhi.ui.page

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.adapter.ChatListAdapter

/**
 * 聊天 Tab 页面控制器（对应设计稿 chat-list.js）。
 *
 * 渲染会话列表、未读徽标、空状态；点击进入对话详情。
 */
class ChatPageController(
    root: View,
    private val repository: BotRepository,
    private val onOpenChat: (String) -> Unit,
    private val onGoContacts: () -> Unit,
) {

    private val chatList = root.findViewById<RecyclerView>(R.id.chat_list)
    private val chatCount = root.findViewById<TextView>(R.id.chat_count)
    private val chatEmpty = root.findViewById<View>(R.id.chat_empty)
    private val chatEmptyAction = root.findViewById<TextView>(R.id.chat_empty_action)

    private val adapter = ChatListAdapter(onClick = onOpenChat)

    init {
        chatList.layoutManager = LinearLayoutManager(root.context)
        chatList.adapter = adapter
        chatEmptyAction.setOnClickListener { onGoContacts() }
    }

    /** 刷新列表（Tab 切换到聊天 / 数据变更时调用） */
    fun render() {
        val bots = repository.bots()
        val conversations = repository.conversations()
        val wakeTarget = repository.wakeTargetBotId

        chatCount.text = chatCount.context.getString(R.string.chat_count, conversations.size)
        adapter.submit(bots, conversations, wakeTarget)

        val empty = conversations.isEmpty()
        chatEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        chatList.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
