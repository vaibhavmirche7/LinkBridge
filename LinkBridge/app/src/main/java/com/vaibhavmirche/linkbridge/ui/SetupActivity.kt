package com.vaibhavmirche.linkbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vaibhavmirche.linkbridge.util.Constants
import com.vaibhavmirche.linkbridge.util.FileUtils
import com.vaibhavmirche.linkbridge.MainActivity
import com.vaibhavmirche.linkbridge.R


class SetupActivity : AppCompatActivity() {
    private val logger = Timber.tag("SetupActivity")

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                FileUtils.persistUriPermission(this, uri) // Persist permission
                // Store the URI so MainActivity can pick it up
                val prefs = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)
                prefs.edit { putString(Constants.EXTRA_FOLDER_URI, uri.toString()) }

                Toast.makeText(this, getString(R.string.folder_setup_complete), Toast.LENGTH_SHORT)
                    .show()
                launchMainActivity()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.folder_selection_cancelled),
                    Toast.LENGTH_SHORT
                ).show()
                // User might try again or exit; stay on this activity
            }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                logger.d("Notification permission granted.")
            } else {
                logger.w("Notification permission denied.")
                Toast.makeText(this, getString(R.string.notification_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)

        val persistedUriString = prefs.getString(Constants.EXTRA_FOLDER_URI, null)

        if (!persistedUriString.isNullOrEmpty()) {
            val persistedUri = persistedUriString.toUri()
            logger.d("Persisted URI: $persistedUri")
            // Check if permissions are still valid for the persisted URI
            if (FileUtils.isUriPermissionPersisted(this, persistedUri)) {
                // Attempt to access the DocumentFile to further validate
                val docFile = DocumentFile.fromTreeUri(this, persistedUri)
                if (docFile != null && docFile.canRead()) {
                    launchMainActivity()
                    return // Skip setup layout
                } else {
                    // URI persisted but not accessible, clear it and proceed with setup
                    FileUtils.clearPersistedUri(this)
                }
            } else {
                FileUtils.clearPersistedUri(this) // Persisted but no permissions, clear it.
            }
        }

        setContentView(R.layout.activity_setup)
        val btnChooseFolder: Button = findViewById(R.id.btnChooseFolder)
        btnChooseFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }
    }

    private fun launchMainActivity() {
        logger.d("Launching MainActivity")
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish() // Finish SetupActivity so user can't go back to it
    }
}