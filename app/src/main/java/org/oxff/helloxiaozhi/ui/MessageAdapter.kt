package org.oxff.helloxiaozhi.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.chat.ChatMessage
import org.oxff.helloxiaozhi.chat.ChatRole

/**
 * 聊天列表适配器（对应 Web 端 ChatContainer.vue）：
 * 用户消息右对齐紫色气泡，AI 消息左对齐灰色气泡。
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageHolder>() {

    private val items = mutableListOf<ChatMessage>()

    fun submit(list: List<ChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(message: ChatMessage) {
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

        // message_container 在 item_message.xml 中是 LinearLayout
        // （其父容器 FrameLayout 会为其分配 FrameLayout.LayoutParams）
        private val container = itemView.findViewById<LinearLayout>(R.id.message_container)
        private val bubble = itemView.findViewById<TextView>(R.id.bubble_text)
        private val time = itemView.findViewById<TextView>(R.id.time_text)

        fun bind(message: ChatMessage) {
            bubble.text = message.content
            time.text = message.time
            val isUser = message.role == ChatRole.USER
            val lp = container.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isUser) Gravity.END else Gravity.START
            container.layoutParams = lp

            val context = itemView.context
            if (isUser) {
                bubble.setBackgroundResource(R.drawable.bg_user_bubble)
                bubble.setTextColor(ContextCompat.getColor(context, R.color.bubble_user_text))
            } else {
                bubble.setBackgroundResource(R.drawable.bg_ai_bubble)
                bubble.setTextColor(ContextCompat.getColor(context, R.color.bubble_ai_text))
            }
        }
    }

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_AI = 1
    }
}
