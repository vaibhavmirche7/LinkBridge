package com.vaibhavmirche.linkbridge.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileUtils {
    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                name = name?.substring(cut + 1)
            }
        }
        return name ?: "unknown_file"
    }

    suspend fun generateUniqueFileName(
        docDir: DocumentFile,
        name: String,
        extension: String,
        startFromOne: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        // If we’re not starting from 1, try the plain name first:
        if (!startFromOne) {
            val plainName = "$name.$extension"
            if (docDir.findFile(plainName) == null) {
                return@withContext plainName
            }
        }
        var count = if (startFromOne) 1 else 2
        var candidate: String

        do {
            candidate = "${name}_$count.$extension"
            count++
        } while (docDir.findFile(candidate) != null)

        return@withContext candidate
    }

    suspend fun copyUriToAppDir(
        context: Context,
        sourceUri: Uri,
        destinationDirUri: Uri,
        filename: String
    ): DocumentFile? = withContext(Dispatchers.IO){
        val resolver = context.contentResolver
        val docDir = DocumentFile.fromTreeUri(context, destinationDirUri) ?: return@withContext null

        val nameWithoutExt = filename.substringBeforeLast(".")
        val ext = filename.substringAfterLast(".", "")


        // Check if file exists, if so, create a unique name
        var finalFileName = generateUniqueFileName(docDir, nameWithoutExt, ext)


        val mimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        val newFile = docDir.createFile(mimeType, finalFileName) ?: return@withContext null

        try {
            resolver.openInputStream(sourceUri)?.use { inputStream ->
                resolver.openOutputStream(newFile.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                    return@withContext newFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFile.delete() // Clean up partially created file
        }
        return@withContext null
    }

    suspend fun createTextFileInDir(
        context: Context,
        dirUri: Uri,
        name: String,
        ext: String,
        content: String
    ): DocumentFile? = withContext(Dispatchers.IO) {
        val docDir = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext null
        val fileName = generateUniqueFileName(docDir, name, ext, true)

        val targetFile = docDir.createFile("text/plain", fileName) ?: return@withContext null
        try {
            context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                outputStream.writer().use { it.write(content) }
                return@withContext targetFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            targetFile.delete()
        }
        return@withContext null
    }

    fun persistUriPermission(context: Context, uri: Uri) {
        val contentResolver = context.contentResolver
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        // Store the URI string for later use
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(Constants.EXTRA_FOLDER_URI, uri.toString()) }
    }

    fun isUriPermissionPersisted(context: Context, uri: Uri): Boolean {
        val persistedUriPermissions = context.contentResolver.persistedUriPermissions
        return persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    fun clearPersistedUri(context: Context) {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(Constants.EXTRA_FOLDER_URI) }
    }

    @SuppressLint("DefaultLocale")
    @Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.1f %s",
            size / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun canWriteToUri(context: Context, uri: Uri): Boolean {
        val docFile = DocumentFile.fromTreeUri(context, uri) // Or fromSingleUri if it's not a tree
        return docFile?.canWrite() == true
    }

}