package com.codeflow.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.codeflow.R
import com.codeflow.databinding.ItemGroupBinding
import com.codeflow.model.Group

class GroupAdapter(
    private val onGroupClick: (Group) -> Unit
) : RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

    private val groups = mutableListOf<Group>()

    fun submitList(newGroups: List<Group>) {
        groups.clear()
        groups.addAll(newGroups)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size

    inner class ViewHolder(private val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: Group) {
            binding.tvGroupName.text = group.name
            binding.tvGroupInfo.text =
                "${group.hostName} · ${if (group.hasPassword) "需密码" else "免密"} · ${group.hostIp}:${group.hostPort}"
            binding.tvMemberCount.text = "${group.memberCount} 人"
            binding.root.setOnClickListener { onGroupClick(group) }
        }
    }
}
