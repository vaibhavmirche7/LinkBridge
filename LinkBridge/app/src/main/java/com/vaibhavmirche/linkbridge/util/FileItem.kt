package com.vaibhavmirche.linkbridge.util

import android.net.Uri

data class FileItem(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val uri: Uri
)