package com.vaibhavmirche.linkbridge.util

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

class InMemoryLogTree(
    private val maxLines: Int = 100
) : Timber.Tree() {

    private val buffer = ArrayDeque<String>(maxLines)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority <= Log.INFO) return  // Skip if priority is low.

        val time = System.currentTimeMillis()
        // Format however you like:
        val line = StringBuilder().apply {
            append(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(Date(time)))
            append(" ")
            append(priorityToChar(priority))
            append("/")
            append(tag ?: "NoTag")
            append(": ")
            append(message)
            t?.let { append("\n").append(Log.getStackTraceString(it)) }
        }.toString()

        synchronized(buffer) {
            if (buffer.size == maxLines) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** Returns a snapshot of all buffered lines, newest last */
    fun getLog(): List<String> {
        synchronized(buffer) { return buffer.toList() }
    }

    private fun priorityToChar(p: Int): Char = when (p) {
        Log.VERBOSE -> 'V'
        Log.DEBUG   -> 'D'
        Log.INFO    -> 'I'
        Log.WARN    -> 'W'
        Log.ERROR   -> 'E'
        Log.ASSERT  -> 'A'
        else                     -> '?'
    }
}
