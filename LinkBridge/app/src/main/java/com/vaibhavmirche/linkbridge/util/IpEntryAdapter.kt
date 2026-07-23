package com.vaibhavmirche.linkbridge.util

import android.annotation.SuppressLint
import android.content.Context
import android.widget.ArrayAdapter

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * A dropdown adapter showing labeled IP entries in the list, but only the raw value when closed.
 */
class IpEntryAdapter(
    context: Context,
    entries: List<IpEntry> = emptyList()
) : ArrayAdapter<IpEntry>(
    context,
    android.R.layout.simple_dropdown_item_1line,
    entries.toMutableList()
) {
    @SuppressLint("SetTextI18n")
    fun expand(
        tv: TextView,
        position: Int,
    ): View {
        val item = getItem(position)
        tv.text = "${item?.label} ${item?.value}"
        return tv
    }
    override fun getDropDownView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val tv = super.getDropDownView(position, convertView, parent) as TextView
        return expand(tv , position)
    }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val tv = super.getView(position, convertView, parent) as TextView
        return expand(tv , position)
    }

}

