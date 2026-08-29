package org.oxff.helloxiaozhi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.ui.view.AvatarPalette

/**
 * 唤醒目标选择列表适配器（对应设计稿 settings.js 的 renderWakeTargetList）。
 *
 * 单选：点击某行即把该机器人设为唤醒目标，勾选标记移动。
 */
class WakeTargetAdapter(
    private val onSelect: (Bot) -> Unit,
) : RecyclerView.Adapter<WakeTargetAdapter.Holder>() {

    private var bots: List<Bot> = emptyList()
    private var selectedBotId: String? = null

    fun submit(bots: List<Bot>, selectedBotId: String?) {
        this.bots = bots
        this.selectedBotId = selectedBotId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = bots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wake_target, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(bots[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar = itemView.findViewById<TextView>(R.id.wake_avatar)
        private val name = itemView.findViewById<TextView>(R.id.wake_name)
        private val check = itemView.findViewById<FrameLayout>(R.id.wake_check)
        private val checkIcon = itemView.findViewById<ImageView>(R.id.wake_check_icon)

        fun bind(bot: Bot) {
            val context = itemView.context
            val selected = bot.id == selectedBotId

            avatar.text = bot.avatarText
            avatar.background = AvatarPalette.avatarBackground(
                context, bot.avatarColorIndex,
                context.resources.getDimension(R.dimen.xz_radius_sm),
            )
            name.text = bot.name

            check.setBackgroundResource(
                if (selected) R.drawable.bg_check_selected else R.drawable.bg_check_unselected,
            )
            checkIcon.visibility = if (selected) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onSelect(bot) }
        }
    }
}
