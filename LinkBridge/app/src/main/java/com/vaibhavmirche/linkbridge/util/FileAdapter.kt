package com.vaibhavmirche.linkbridge.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.R

class FileAdapter(
    private var files: List<FileItem>,
    private val onItemClick: (FileItem, Int) -> Unit, // For regular clicks
    private val onItemLongClick: (FileItem, Int) -> Boolean // For long clicks to start action mode
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val selectedItems = mutableSetOf<Int>() // Stores positions of selected items

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvSize: TextView = view.findViewById(R.id.tvFileSize)
        val ivSelectionCheck: ImageView = view.findViewById(R.id.ivSelectionCheck)
        val itemLayout: ConstraintLayout =
            view as ConstraintLayout // Assuming root is ConstraintLayout

        fun bind(file: FileItem, position: Int, isSelected: Boolean) {
            tvName.text = file.name
            tvSize.text = FileUtils.formatFileSize(file.size)


            if (isSelected) {
                itemLayout.setBackgroundColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.file_item_selected_background
                    )
                )
                ivSelectionCheck.visibility = View.VISIBLE
            } else {
                itemLayout.setBackgroundColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.default_file_item_background
                    )
                )

                ivSelectionCheck.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onItemClick(file, position)
            }
            itemView.setOnLongClickListener {
                onItemLongClick(file, position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position, selectedItems.contains(position))
    }

    override fun getItemCount(): Int = files.size

    fun updateFiles(newFiles: List<FileItem>) {
        files = newFiles
        selectedItems.clear() // Clear selection on new data
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        if (selectedItems.contains(position)) {
            selectedItems.remove(position)
        } else {
            selectedItems.add(position)
        }
        notifyItemChanged(position)
    }

    fun getSelectedFileItems(): List<FileItem> {
        return selectedItems.map { files[it] }
    }

    fun getSelectedItemCount(): Int {
        return selectedItems.size
    }

    fun clearSelections() {
        selectedItems.clear()
        notifyDataSetChanged() // To redraw all items to their non-selected state
    }
    fun getFileItem(position: Int): FileItem? {
        return files.getOrNull(position)
    }

    fun selectAll() {
        if (selectedItems.size == files.size) { // If all are selected, deselect all
            selectedItems.clear()
        } else { // Otherwise, select all
            files.forEachIndexed { index, _ -> selectedItems.add(index) }
        }
        notifyDataSetChanged()
    }
}