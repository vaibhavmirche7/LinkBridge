package com.vaibhavmirche.linkbridge.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vaibhavmirche.linkbridge.R
import com.vaibhavmirche.linkbridge.db.AppDatabase
import kotlinx.coroutines.launch

/**
 * Shows who has connected to this device and when (name + timestamp).
 *
 * Distinct from the Home screen's "Log / History" tab, which lists individual
 * file transfers (filename, size, direction, status) rather than connections.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var adapter: ConnectionLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView: RecyclerView = findViewById(R.id.rvLogs)
        adapter = ConnectionLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val emptyState: View = findViewById(R.id.emptyState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.getInstance(this@LogsActivity)
                    .connectionLogDao()
                    .observeAll()
                    .collect { entries ->
                        adapter.submitList(entries)
                        val hasEntries = entries.isNotEmpty()
                        recyclerView.visibility = if (hasEntries) View.VISIBLE else View.GONE
                        emptyState.visibility = if (hasEntries) View.GONE else View.VISIBLE
                    }
            }
        }
    }
}
