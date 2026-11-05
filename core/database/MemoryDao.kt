package com.xingshi.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xingshi.ai.database.model.MemoryEntry
import com.xingshi.ai.database.model.TempMemory

@Dao
interface MemoryDao {
    @Query("DELETE FROM memory_entries WHERE companionId=:companionId")
    suspend fun deleteCompanionMemories(companionId: Long)
    @Query("DELETE FROM temp_memory WHERE companionId=:companionId")
    suspend fun deleteCompanionTempMemories(companionId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTempMemory(tempMemory: TempMemory): Long

    @Query("SELECT * FROM memory_entries WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun getAllMemoriesSync(deviceId: String): List<MemoryEntry>

    @Query("SELECT * FROM temp_memory WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun getAllTempMemoriesSync(deviceId: String): List<TempMemory>

    @Query("DELETE FROM memory_entries WHERE deviceId = :deviceId")
    suspend fun deleteAllMemories(deviceId: String): Int

    @Query("DELETE FROM temp_memory WHERE deviceId = :deviceId")
    suspend fun deleteAllTempMemories(deviceId: String): Int
}
