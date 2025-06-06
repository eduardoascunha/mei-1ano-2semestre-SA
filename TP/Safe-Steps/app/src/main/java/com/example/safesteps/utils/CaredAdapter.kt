package com.example.safesteps.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.safesteps.databinding.ItemCaredBinding

class CaredAdapter(
    private var caredList: List<CaredUser>,
    private val onCaredClickListener: (CaredUser) -> Unit
) : RecyclerView.Adapter<CaredAdapter.CaredViewHolder>() {

    class CaredViewHolder(val binding: ItemCaredBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CaredViewHolder {
        val binding = ItemCaredBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CaredViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CaredViewHolder, position: Int) {
        val cared = caredList[position]

        with(holder.binding) {
            tvCaredName.text = "${cared.firstName} ${cared.lastName}"
            root.setOnClickListener {
                onCaredClickListener(cared)
            }
        }
    }

    override fun getItemCount(): Int = caredList.size

    fun updateList(newList: List<CaredUser>) {
        caredList = newList
        notifyDataSetChanged()
    }
}