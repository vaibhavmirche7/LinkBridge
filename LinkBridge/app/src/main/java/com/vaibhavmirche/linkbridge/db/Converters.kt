package com.vaibhavmirche.linkbridge.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromDirection(value: TransferDirection): String = value.name
    @TypeConverter
    fun toDirection(value: String): TransferDirection = TransferDirection.valueOf(value)

    @TypeConverter
    fun fromMode(value: TransferMode): String = value.name
    @TypeConverter
    fun toMode(value: String): TransferMode = TransferMode.valueOf(value)

    @TypeConverter
    fun fromStatus(value: TransferStatus): String = value.name
    @TypeConverter
    fun toStatus(value: String): TransferStatus = TransferStatus.valueOf(value)
}
