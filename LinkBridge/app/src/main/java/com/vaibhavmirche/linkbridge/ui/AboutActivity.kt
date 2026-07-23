package com.vaibhavmirche.linkbridge.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.vaibhavmirche.linkbridge.BuildConfig
import com.vaibhavmirche.linkbridge.R
import timber.log.Timber


class AboutActivity : AppCompatActivity() {
    private val logger = Timber.tag("AboutActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Setup Toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set Version Name
        val versionTextView: TextView = findViewById(R.id.tvVersion)
        val versionName = BuildConfig.VERSION_NAME
        versionTextView.text = getString(R.string.version_name, versionName)

        versionTextView.setOnLongClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Version", versionName)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "$versionName copied!", Toast.LENGTH_SHORT).show()
            true
        }

        // Fade-in Animation for Card
        val cardContent = findViewById<androidx.cardview.widget.CardView>(R.id.cardContent)
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        cardContent.startAnimation(fadeIn)

        // GitHub Button Click
        val btnGithub: MaterialButton = findViewById(R.id.btnGithub)
        btnGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/vaibhavmirche7/LinkBridge".toUri())
            startActivity(intent)
        }
    }
}