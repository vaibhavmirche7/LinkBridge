package com.vaibhavmirche.linkbridge.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.db.ConnectionLogEntity

class ConnectionLogAdapter : ListAdapter<ConnectionLogEntity, ConnectionLogAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val line1: TextView = view.findViewById(android.R.id.text1)
        val line2: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.line1.text = "${entry.deviceName} (${entry.ip})"
        holder.line2.text = DateFormat.format("MMM d, yyyy h:mm a", entry.connectedAt)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectionLogEntity>() {
            override fun areItemsTheSame(old: ConnectionLogEntity, new: ConnectionLogEntity) =
                old.id == new.id
            override fun areContentsTheSame(old: ConnectionLogEntity, new: ConnectionLogEntity) =
                old == new
        }
    }
}
