package com.vaibhavmirche.linkbridge.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferLogDao {
    @Insert
    suspend fun insert(entry: TransferLogEntity)

    @Query("SELECT * FROM transfer_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransferLogEntity>>

    @Query("DELETE FROM transfer_log")
    suspend fun clearAll()
}
