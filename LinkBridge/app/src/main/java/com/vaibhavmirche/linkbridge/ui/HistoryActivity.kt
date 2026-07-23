package com.vaibhavmirche.linkbridge.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.R
import com.vaibhavmirche.linkbridge.db.AppDatabase
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

class HistoryActivity : AppCompatActivity() {

    private val logger = Timber.tag("HistoryActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView: RecyclerView = findViewById(R.id.rvHistory)
        val emptyState: View = findViewById(R.id.emptyState)
        val emptyStateText: TextView = emptyState.findViewById(R.id.tvEmptyStateMessage)
        val adapter = HistoryAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Show *something* the instant the screen opens, rather than leaving it blank while
        // waiting on the first database emission.
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        emptyStateText.text = getString(R.string.history_loading)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.getInstance(this@HistoryActivity)
                    .transferLogDao()
                    .observeAll()
                    .catch { e ->
                        logger.e(e, "Failed to load transfer history")
                        recyclerView.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                        emptyStateText.text = getString(R.string.history_load_failed)
                        Toast.makeText(
                            this@HistoryActivity,
                            getString(R.string.history_load_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .collect { entries ->
                        adapter.submitList(entries)
                        val hasEntries = entries.isNotEmpty()
                        recyclerView.visibility = if (hasEntries) View.VISIBLE else View.GONE
                        emptyState.visibility = if (hasEntries) View.GONE else View.VISIBLE
                        emptyStateText.text = getString(R.string.history_empty_state)
                    }
            }
        }
    }
}
