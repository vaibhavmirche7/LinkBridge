package com.vaibhavmirche.linkbridge.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransferDirection { UPLOADED, DOWNLOADED }
enum class TransferMode { LAN, QR }
enum class TransferStatus { SUCCESS, FAILED, CANCELED }

@Entity(tableName = "transfer_log")
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val direction: TransferDirection,
    val timestamp: Long,
    val mode: TransferMode,
    val deviceName: String,
    val status: TransferStatus
)
