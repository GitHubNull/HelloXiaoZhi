package org.oxff.helloxiaozhi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.data.db.ArtistDictEntry
import org.oxff.helloxiaozhi.data.db.ArtistType

/**
 * 歌手/乐队词典用户导入条目列表适配器。
 */
class ArtistDictAdapter(
    private val onDelete: (ArtistDictEntry) -> Unit,
) : RecyclerView.Adapter<ArtistDictAdapter.VH>() {

    private val items = mutableListOf<ArtistDictEntry>()

    fun submit(list: List<ArtistDictEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist_dict, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.dict_item_name)
        private val type: TextView = itemView.findViewById(R.id.dict_item_type)
        private val btnDelete: TextView = itemView.findViewById(R.id.btn_dict_item_delete)

        fun bind(entry: ArtistDictEntry) {
            name.text = entry.name
            type.text = itemView.context.getString(
                if (entry.type == ArtistType.BAND) R.string.music_dict_type_band else R.string.music_dict_type_artist,
            )
            btnDelete.setOnClickListener { onDelete(entry) }
        }
    }
}
