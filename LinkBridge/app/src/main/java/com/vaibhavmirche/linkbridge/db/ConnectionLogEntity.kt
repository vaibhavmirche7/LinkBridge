package com.vaibhavmirche.linkbridge.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_log")
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceName: String,
    val ip: String,
    val connectedAt: Long
)
