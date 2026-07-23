package com.vaibhavmirche.linkbridge.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.vaibhavmirche.linkbridge.MainActivity
import com.vaibhavmirche.linkbridge.R

/**
 * The app's entry point (after first-run setup): four destinations -
 * QR Transfer, LAN/Hotspot Transfer, Server Mode, and Log/History.
 *
 * Server Mode is intentionally inert - per spec it's a placeholder for future
 * work and must do nothing when tapped, so no click listener is attached to it.
 *
 * Settings/About/Logs live in this toolbar's 3-dot overflow menu (moved here
 * from the LAN screen so they're reachable from the app's entry point).
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<android.view.View>(R.id.cardQrTransfer).setOnClickListener {
            startActivity(Intent(this, QrTransferActivity::class.java))
        }

        findViewById<android.view.View>(R.id.cardLanTransfer).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // cardServerMode: no listener attached on purpose - must do nothing when tapped.

        findViewById<android.view.View>(R.id.cardHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }

            R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
