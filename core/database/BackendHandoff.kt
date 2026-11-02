package com.xingshi.ai.database

import androidx.room.*

@Entity(tableName = "backend_companion_links", indices = [Index(value = ["instanceId"], unique = true)])
data class BackendCompanionLink(@PrimaryKey val companionId: Long, val instanceId: String,
    val conversationId: String, val version: Long, val personaConfigId: String = "", val personaVersion: Long = 0,
    val provider: String = "", val model: String = "", val deletingOperationId: String? = null)

/** Existing input's durable handoff, never a second input or model queue. Payload is encrypted by the repository caller. */
@Entity(tableName = "backend_input_handoffs")
data class BackendInputHandoff(@PrimaryKey val localMessageId: Long, val companionId: Long,
    val clientMessageId: String, val sourceEventId: String, val encryptedPayload: String,
    val encryptedRoute: String = "", val callId: String = "", val humanMessageId: String? = null,
    val generationId: String? = null, val state: String = "PENDING", val error: String? = null)

@Entity(tableName = "backend_delivery_receipts")
data class BackendDeliveryReceipt(@PrimaryKey val messageId: String, val companionId: Long,
    val localMessageId: Long, val encryptedPayload: String, val state: String = "SAVED")

@Dao
interface BackendHandoffDao {
    @Query("SELECT * FROM backend_companion_links WHERE companionId=:id") suspend fun link(id: Long): BackendCompanionLink?
    @Query("SELECT * FROM backend_companion_links WHERE instanceId=:id") suspend fun linkForInstance(id: String): BackendCompanionLink?
    @Query("SELECT * FROM backend_companion_links") suspend fun links(): List<BackendCompanionLink>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLink(link: BackendCompanionLink)
    @Query("DELETE FROM backend_companion_links WHERE companionId=:id") suspend fun deleteLink(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInput(input: BackendInputHandoff): Long
    @Update suspend fun updateInput(input: BackendInputHandoff)
    @Query("SELECT * FROM backend_input_handoffs WHERE state IN ('PENDING','ACCEPTED','UNKNOWN') ORDER BY localMessageId") suspend fun pendingInputs(): List<BackendInputHandoff>
    @Query("SELECT * FROM backend_input_handoffs WHERE state='PREPARING' ORDER BY localMessageId") suspend fun preparingInputs(): List<BackendInputHandoff>
    @Query("SELECT * FROM backend_input_handoffs WHERE companionId=:id AND state='FAILED' AND generationId IS NOT NULL ORDER BY localMessageId DESC LIMIT 1") suspend fun failedGeneration(id: Long): BackendInputHandoff?
    @Query("SELECT * FROM backend_input_handoffs WHERE localMessageId=:id") suspend fun input(id: Long): BackendInputHandoff?
    @Query("SELECT * FROM backend_input_handoffs WHERE humanMessageId=:id") suspend fun inputForHuman(id: String): BackendInputHandoff?
    @Query("DELETE FROM backend_input_handoffs WHERE companionId=:id") suspend fun deleteInputs(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReceipt(receipt: BackendDeliveryReceipt): Long
    @Update suspend fun updateReceipt(receipt: BackendDeliveryReceipt)
    @Query("SELECT * FROM backend_delivery_receipts WHERE messageId=:id") suspend fun receipt(id: String): BackendDeliveryReceipt?
    @Query("SELECT * FROM backend_delivery_receipts WHERE localMessageId=:id") suspend fun receiptForLocal(id: Long): BackendDeliveryReceipt?
    @Query("SELECT * FROM backend_delivery_receipts WHERE state!='DONE'") suspend fun pendingReceipts(): List<BackendDeliveryReceipt>
    @Query("DELETE FROM backend_delivery_receipts WHERE companionId=:id") suspend fun deleteReceipts(id: Long)
}
