package com.vaibhavmirche.linkbridge.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.server.ConnectedDevice

class ConnectedDeviceAdapter :
    ListAdapter<ConnectedDevice, ConnectedDeviceAdapter.ViewHolder>(DIFF) {

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
        val device = getItem(position)
        holder.line1.text = device.name
        holder.line2.text = DateFormat.format("MMM d, h:mm a", device.connectedAt)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectedDevice>() {
            override fun areItemsTheSame(old: ConnectedDevice, new: ConnectedDevice) =
                old.ip == new.ip

            override fun areContentsTheSame(old: ConnectedDevice, new: ConnectedDevice) =
                old == new
        }
    }
}
