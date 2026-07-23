package com.vaibhavmirche.linkbridge.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionLogDao {
    @Insert
    suspend fun insert(entry: ConnectionLogEntity)

    @Query("SELECT * FROM connection_log ORDER BY connectedAt DESC")
    fun observeAll(): Flow<List<ConnectionLogEntity>>

    @Query("DELETE FROM connection_log")
    suspend fun clearAll()
}
