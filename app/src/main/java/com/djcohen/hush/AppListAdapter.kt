package com.djcohen.hush

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.djcohen.hush.databinding.ItemAppBinding

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    var blocked: Boolean,
)

class AppListAdapter(
    private var items: List<AppEntry>,
    /** Return false to veto the change (the switch snaps back). */
    private val onToggle: (AppEntry, Boolean) -> Boolean,
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    @SuppressLint("NotifyDataSetChanged")
    fun update(newItems: List<AppEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.appIcon.setImageDrawable(item.icon)
        holder.binding.appLabel.text = item.label
        holder.binding.appSwitch.isChecked = item.blocked
        holder.binding.root.setOnClickListener {
            val newValue = !item.blocked
            if (!onToggle(item, newValue)) return@setOnClickListener
            item.blocked = newValue
            holder.binding.appSwitch.isChecked = newValue
        }
    }

    override fun getItemCount(): Int = items.size
}
