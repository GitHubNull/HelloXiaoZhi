package org.oxff.helloxiaozhi.ui.page

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.adapter.BotListAdapter

/**
 * 通讯录 Tab 页面控制器（对应设计稿 bot-list.js）。
 *
 * 渲染机器人列表、唤醒徽标；点击进入对话，长按删除。
 */
class ContactsPageController(
    root: View,
    private val repository: BotRepository,
    private val onOpenChat: (String) -> Unit,
    private val onDeleteBot: (Bot) -> Unit,
    private val onAddBot: () -> Unit,
) {

    private val botList = root.findViewById<RecyclerView>(R.id.bot_list)
    private val contactsCount = root.findViewById<TextView>(R.id.contacts_count)
    private val btnAddBot = root.findViewById<TextView>(R.id.btn_add_bot)

    private val adapter = BotListAdapter(
        onClick = { onOpenChat(it.id) },
        onLongClick = onDeleteBot,
    )

    init {
        botList.layoutManager = LinearLayoutManager(root.context)
        botList.adapter = adapter
        btnAddBot.setOnClickListener { onAddBot() }
    }

    /** 刷新列表（Tab 切换到通讯录 / 数据变更时调用） */
    fun render() {
        val bots = repository.bots()
        contactsCount.text = contactsCount.context.getString(R.string.contacts_count, bots.size)
        adapter.submit(bots, repository.wakeTargetBotId)
    }
}
