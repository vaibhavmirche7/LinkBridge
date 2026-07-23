package com.vaibhavmirche.linkbridge.util

object Constants {
    const val DEFAULT_SERVER_PORT = 8000
    const val NOTIFICATION_CHANNEL_ID = "TransferServiceChannel"
    const val NOTIFICATION_ID = 1
    const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
    const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    const val SHARED_PREFS_NAME = "TransferPrefs"
    const val IP_PERMISSION_VALIDITY_MS = 60 * 60 * 1000L // 1 hour
    const val TRANSFER_APPROVAL_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes to accept/decline
    const val TRANSFER_TOKEN_VALIDITY_MS = 2 * 60 * 1000L // window to actually upload after accepting

    const val EXTRA_FOLDER_URI = "FOLDER_URI"
}