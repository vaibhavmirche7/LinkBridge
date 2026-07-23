package com.vaibhavmirche.linkbridge.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.R
import com.vaibhavmirche.linkbridge.db.TransferDirection
import com.vaibhavmirche.linkbridge.db.TransferLogEntity
import com.vaibhavmirche.linkbridge.db.TransferStatus
import com.vaibhavmirche.linkbridge.util.FileUtils

class HistoryAdapter : ListAdapter<TransferLogEntity, HistoryAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val ivDirection: ImageView = view.findViewById(R.id.ivDirection)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val context = holder.itemView.context

        holder.tvFileName.text = entry.fileName
        holder.ivDirection.setImageResource(
            if (entry.direction == TransferDirection.UPLOADED) R.drawable.ic_upload_device
            else R.drawable.ic_download_device
        )
        val directionLabel = if (entry.direction == TransferDirection.UPLOADED) "Uploaded" else "Downloaded"
        holder.tvMeta.text = "$directionLabel · ${FileUtils.formatFileSize(entry.fileSize)} · " +
                "${entry.deviceName} · ${entry.mode.name}"
        holder.tvTimestamp.text = DateFormat.format("MMM d, yyyy h:mm a", entry.timestamp)

        val (statusText, statusColor) = when (entry.status) {
            TransferStatus.SUCCESS -> "SUCCESS" to 0xFF2E7D32.toInt()
            TransferStatus.FAILED -> "FAILED" to 0xFFC62828.toInt()
            TransferStatus.CANCELED -> "CANCELED" to 0xFF757575.toInt()
        }
        holder.tvStatus.text = statusText
        holder.tvStatus.setTextColor(statusColor)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransferLogEntity>() {
            override fun areItemsTheSame(old: TransferLogEntity, new: TransferLogEntity) =
                old.id == new.id
            override fun areContentsTheSame(old: TransferLogEntity, new: TransferLogEntity) =
                old == new
        }
    }
}
