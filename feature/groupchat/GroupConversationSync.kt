package com.xingshi.ai.feature.groupchat

import android.content.Context
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.domain.GroupConversationGateway
import com.xingshi.ai.domain.GroupConversationSnapshot
import com.xingshi.ai.domain.GroupParticipant
import com.xingshi.ai.domain.ServiceRegistry
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.first

 
object GroupConversationSync {

    private const val PREFS = "group_conversation_sync"
    private const val KEY_PENDING = "pending_groups"

    private fun encode(snapshot: GroupConversationSnapshot): String {
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        val members = snapshot.members.joinToString(",") { member ->
            "${member.companionId}:${enc(member.name)}:${enc(member.avatar.orEmpty())}"
        }
        return listOf(
            snapshot.groupId.toString(),
            enc(snapshot.groupName),
            snapshot.createdAt.toString(),
            members
        ).joinToString("|")
    }

    private fun decode(entry: String): GroupConversationSnapshot? {
        val parts = entry.split("|")
        if (parts.size != 4) return null
        val groupId = parts[0].toLongOrNull() ?: return null
        val groupName = runCatching { URLDecoder.decode(parts[1], Charsets.UTF_8.name()) }.getOrNull() ?: return null
        val createdAt = parts[2].toLongOrNull() ?: return null
        val members = parts[3].split(",").filter { it.isNotBlank() }.mapNotNull { raw ->
            val fields = raw.split(":")
            if (fields.size != 3) return@mapNotNull null
            val companionId = fields[0].toLongOrNull() ?: return@mapNotNull null
            val name = runCatching { URLDecoder.decode(fields[1], Charsets.UTF_8.name()) }.getOrNull() ?: return@mapNotNull null
            val avatar = runCatching { URLDecoder.decode(fields[2], Charsets.UTF_8.name()) }.getOrNull().orEmpty()
            GroupParticipant(companionId, name, avatar.ifBlank { null })
        }
        return GroupConversationSnapshot(groupId, groupName, createdAt, members, emptyList())
    }

    private fun markPending(context: Context, snapshot: GroupConversationSnapshot) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .filterNot { it.substringBefore("|") == snapshot.groupId.toString() }
        prefs.edit().putStringSet(KEY_PENDING, entries.toSet() + snapshot.groupId.toString() + encode(snapshot)).commit()
    }

    private fun clearPending(context: Context, groupId: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .filterNot { it == groupId.toString() || it.substringBefore("|") == groupId.toString() }
        prefs.edit().putStringSet(KEY_PENDING, entries.toSet()).commit()
    }

     
    suspend fun retryPending(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toList()
        if (entries.isEmpty()) return
        val gateway = ServiceRegistry.get(GroupConversationGateway::class.java) ?: return
        val db = AppDatabase.getDatabase(context)
        val groups = com.xingshi.ai.database.repository.ChatGroupRepository(db.chatGroupDao())
        val snapshots = entries.mapNotNull(::decode).associateBy { it.groupId }
        val ids = (snapshots.keys + entries.mapNotNull { it.toLongOrNull() }).distinct()
        ids.forEach { groupId ->
            val stored = snapshots[groupId]
            val group = groups.getGroupById(groupId) ?: return@forEach
            val snapshot = stored ?: run {
                val all = CompanionRepository(db.companionDao()).getAllCompanions().first().associateBy { it.id }
                val members = group.getCompanionIdList().distinct().mapNotNull { id ->
                    all[id]?.let { GroupParticipant(it.id, it.name, it.avatarUrl) }
                }
                GroupConversationSnapshot(group.id, group.name, group.createdAt, members, emptyList())
            }
            runCatching {
                gateway.synchronize(snapshot)
                val boundaryPrefs = context.applicationContext.getSharedPreferences("group_sync_boundary", Context.MODE_PRIVATE)
                val known = boundaryPrefs.getLong("group_${group.id}", 0L)
                
                val progress = gateway.synchronizeDelta(group.id, known)
                if (progress == null || !progress.complete) return@runCatching
                if (progress.knownThroughMessageId > known) {
                    gateway.confirmSyncProgress(group.id, progress.knownThroughMessageId)
                    boundaryPrefs.edit().putLong("group_${group.id}", progress.knownThroughMessageId).apply()
                }
                clearPending(context, group.id)
            }
        }
    }

    suspend fun afterGroupCreated(
        context: Context,
        groupId: Long,
        name: String,
        createdAt: Long,
        companionIds: List<Long>
    ) {
        val all = CompanionRepository(AppDatabase.getDatabase(context).companionDao())
            .getAllCompanions().first().associateBy { it.id }
        val members = companionIds.distinct().mapNotNull { id ->
            all[id]?.let { GroupParticipant(it.id, it.name, it.avatarUrl) }
        }
        val snapshot = GroupConversationSnapshot(
            groupId = groupId,
            groupName = name,
            createdAt = createdAt,
            members = members,
            messages = emptyList()
        )
        val gateway = ServiceRegistry.get(GroupConversationGateway::class.java) ?: run {
            markPending(context, snapshot)
            return
        }
        runCatching {
            gateway.synchronize(snapshot)
            val progress = gateway.synchronizeDelta(groupId, 0L)
            if (progress == null || !progress.complete) {
                markPending(context, snapshot)
                return@runCatching
            }
            gateway.confirmSyncProgress(groupId, progress.knownThroughMessageId)
            clearPending(context, groupId)
        }.onFailure {
            
            markPending(context, snapshot)
        }
    }
}
