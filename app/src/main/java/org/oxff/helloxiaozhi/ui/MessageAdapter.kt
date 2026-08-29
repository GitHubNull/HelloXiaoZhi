package org.oxff.helloxiaozhi.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.data.StoredMessage
import org.oxff.helloxiaozhi.ui.view.BubbleDrawables
import org.oxff.helloxiaozhi.util.TimeFormat

/**
 * 消息气泡适配器（对应设计稿 chat-detail.js 的 appendMsg）。
 *
 * 数据源是 [StoredMessage]（含 ts），时间串由 TimeFormat 现算。
 * 用户消息右对齐渐变气泡，AI 消息左对齐深色气泡。
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageHolder>() {

    private val items = mutableListOf<StoredMessage>()

    fun submit(list: List<StoredMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(message: StoredMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position].role == ChatRole.USER) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageHolder(view)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class MessageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val container = itemView.findViewById<LinearLayout>(R.id.message_container)
        private val bubble = itemView.findViewById<TextView>(R.id.bubble_text)
        private val time = itemView.findViewById<TextView>(R.id.time_text)

        fun bind(message: StoredMessage) {
            bubble.text = message.content
            time.text = TimeFormat.hhmm(message.ts)
            val isUser = message.role == ChatRole.USER
            // 在根容器上设 gravity，不再强转 FrameLayout.LayoutParams
            (container.layoutParams as LinearLayout.LayoutParams).gravity =
                if (isUser) Gravity.END else Gravity.START

            val context = itemView.context
            if (isUser) {
                bubble.background = BubbleDrawables.chatUser(context)
                bubble.setTextColor(ContextCompat.getColor(context, R.color.xz_text_on_primary))
            } else {
                bubble.background = BubbleDrawables.chatAi(context)
                bubble.setTextColor(ContextCompat.getColor(context, R.color.xz_text_primary))
            }
        }
    }

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_AI = 1
    }
}
