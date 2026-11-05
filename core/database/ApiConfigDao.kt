package com.xingshi.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xingshi.ai.database.model.ApiConfig
import com.xingshi.ai.database.model.ApiProvider
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiConfigDao {
    @Query("SELECT * FROM api_configs ORDER BY id DESC")
    fun getAllConfigs(): Flow<List<ApiConfig>>

    @Query("SELECT * FROM api_configs WHERE provider = :provider ORDER BY id DESC")
    fun getConfigsByProvider(provider: ApiProvider): Flow<List<ApiConfig>>

    @Query("SELECT * FROM api_configs WHERE id = :id")
    suspend fun getConfigById(id: Long): ApiConfig?

    @Query("SELECT * FROM api_configs WHERE apiKey IS NOT NULL AND apiKey != '' ORDER BY id DESC")
    fun getAllConfiguredConfigs(): Flow<List<ApiConfig>>

    @Query("SELECT * FROM api_configs WHERE kind = 'VISUAL' AND apiKey IS NOT NULL AND apiKey != '' ORDER BY id DESC LIMIT 1")
    suspend fun getVisionConfig(): ApiConfig?

    @Query("SELECT * FROM api_configs WHERE kind = 'STT' AND apiKey IS NOT NULL AND apiKey != '' ORDER BY id DESC LIMIT 1")
    suspend fun getSttConfig(): ApiConfig?

    @Query("SELECT * FROM api_configs WHERE kind = 'TTS' AND apiKey IS NOT NULL AND apiKey != '' ORDER BY id DESC LIMIT 1")
    suspend fun getTtsConfig(): ApiConfig?

    @Query("SELECT * FROM api_configs WHERE kind = 'VIDEO' AND apiKey IS NOT NULL AND apiKey != '' AND isEnabled = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getVideoConfig(): ApiConfig?

    @Query("SELECT * FROM api_configs WHERE apiKey IS NOT NULL AND apiKey != '' AND isEnabled = 1 AND kind = 'MAIN' ORDER BY id DESC LIMIT 1")
    suspend fun getActiveEnabledConfig(): ApiConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ApiConfig): Long

    @Update
    suspend fun updateConfig(config: ApiConfig): Int

    @Query("DELETE FROM api_configs WHERE id = :id")
    suspend fun deleteConfigById(id: Long): Int

    @Query("DELETE FROM api_configs WHERE kind = 'STT' AND id != :id")
    suspend fun deleteSttConfigsExcept(id: Long): Int

    @Query("DELETE FROM api_configs WHERE kind = 'TTS' AND id != :id")
    suspend fun deleteTtsConfigsExcept(id: Long): Int

    @Query("DELETE FROM api_configs WHERE kind = 'VIDEO' AND id != :id")
    suspend fun deleteVideoConfigsExcept(id: Long): Int

    @Query("DELETE FROM api_configs WHERE provider = :provider")
    suspend fun deleteConfig(provider: ApiProvider): Int
}
