package org.oxff.helloxiaozhi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.ui.view.AvatarPalette

/**
 * 通讯录机器人列表适配器（对应设计稿 bot-list.js 的 renderBotList）。
 *
 * 点击进入对话，长按删除（最后一个机器人不允许删除）。
 */
class BotListAdapter(
    private val onClick: (Bot) -> Unit,
    private val onLongClick: (Bot) -> Unit,
) : RecyclerView.Adapter<BotListAdapter.Holder>() {

    private var bots: List<Bot> = emptyList()
    private var wakeTargetBotId: String? = null

    fun submit(bots: List<Bot>, wakeTargetBotId: String?) {
        this.bots = bots
        this.wakeTargetBotId = wakeTargetBotId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = bots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bot, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(bots[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar = itemView.findViewById<TextView>(R.id.bot_avatar)
        private val name = itemView.findViewById<TextView>(R.id.bot_name)
        private val wakeBadge = itemView.findViewById<TextView>(R.id.bot_wake_badge)
        private val tagsContainer = itemView.findViewById<LinearLayout>(R.id.bot_tags)
        private val desc = itemView.findViewById<TextView>(R.id.bot_desc)

        fun bind(bot: Bot) {
            val context = itemView.context

            avatar.text = bot.avatarText
            avatar.background = AvatarPalette.avatarBackground(
                context, bot.avatarColorIndex,
                context.resources.getDimension(R.dimen.xz_radius_avatar),
            )

            name.text = bot.name
            wakeBadge.visibility =
                if (bot.id == wakeTargetBotId) View.VISIBLE else View.GONE

            // 性格标签胶囊
            tagsContainer.removeAllViews()
            bot.tags.forEach { tag ->
                val pill = TextView(context).apply {
                    text = tag
                    setTextColor(context.getColor(R.color.xz_text_secondary))
                    textSize = 10f
                    setBackgroundResource(R.drawable.bg_pill_neutral)
                    val pad = (8 * resources.displayMetrics.density).toInt()
                    val padV = (2 * resources.displayMetrics.density).toInt()
                    setPadding(pad, padV, pad, padV)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = (6 * itemView.resources.displayMetrics.density).toInt()
                }
                tagsContainer.addView(pill, lp)
            }

            desc.text = bot.desc

            itemView.setOnClickListener { onClick(bot) }
            itemView.setOnLongClickListener { onLongClick(bot); true }
        }
    }
}
