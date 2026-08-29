package org.oxff.helloxiaozhi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.Conversation
import org.oxff.helloxiaozhi.ui.view.AvatarPalette
import org.oxff.helloxiaozhi.util.TimeFormat

/**
 * 会话列表适配器（对应设计稿 chat-list.js 的 renderChatList）。
 *
 * 数据来自 BotRepository.conversations()（按最后消息时间倒序），
 * 头像、未读徽标、唤醒标记、预览文本均在此绑定。
 */
class ChatListAdapter(
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<ChatListAdapter.Holder>() {

    private var bots: Map<String, Bot> = emptyMap()
    private var conversations: List<Conversation> = emptyList()
    private var wakeTargetBotId: String? = null

    fun submit(bots: List<Bot>, conversations: List<Conversation>, wakeTargetBotId: String?) {
        this.bots = bots.associateBy { it.id }
        this.conversations = conversations
        this.wakeTargetBotId = wakeTargetBotId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = conversations.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(conversations[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar = itemView.findViewById<TextView>(R.id.chat_avatar)
        private val unread = itemView.findViewById<TextView>(R.id.chat_unread)
        private val name = itemView.findViewById<TextView>(R.id.chat_name)
        private val time = itemView.findViewById<TextView>(R.id.chat_time)
        private val preview = itemView.findViewById<TextView>(R.id.chat_preview)

        fun bind(conversation: Conversation) {
            val bot = bots[conversation.botId] ?: return
            val context = itemView.context

            avatar.text = bot.avatarText
            avatar.background = AvatarPalette.avatarBackground(
                context, bot.avatarColorIndex,
                context.resources.getDimension(R.dimen.xz_radius_avatar),
            )

            val wakeMarker = if (bot.id == wakeTargetBotId) {
                " " + context.getString(R.string.chat_wake_marker)
            } else ""
            name.text = bot.name + wakeMarker

            time.text = TimeFormat.relative(
                conversation.lastTs,
                yesterdayLabel = context.getString(R.string.time_yesterday),
            )
            preview.text = conversation.lastPreview.ifEmpty {
                context.getString(R.string.chat_preview_none)
            }

            if (conversation.unread > 0) {
                unread.visibility = View.VISIBLE
                unread.text = if (conversation.unread > 99) {
                    context.getString(R.string.chat_unread_overflow)
                } else {
                    conversation.unread.toString()
                }
            } else {
                unread.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(conversation.botId) }
        }
    }
}
