package com.xingshi.ai.feature.groupchat

import android.content.Context

/** 群投递未受理责任的重启恢复：只存身份与原目标，快照重试时从本地库重建。 */
object GroupPendingDeliveryStore {

    private const val PREFS = "group_pending_deliveries"

    data class Pending(
        val groupId: Long,
        val messageId: Long,
        val targets: Set<Long>,
        val mentionedIds: Set<Long>,
        val everyone: Boolean,
        val snapshot: com.xingshi.ai.domain.GroupConversationSnapshot? = null
    )

    fun save(context: Context, pending: Pending) {
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                pending.messageId.toString(),
                listOf(
                    pending.groupId.toString(),
                    pending.messageId.toString(),
                    pending.targets.joinToString(","),
                    pending.mentionedIds.joinToString(","),
                    pending.everyone.toString(),
                    pending.snapshot?.let(::encodeSnapshot).orEmpty()
                ).joinToString("|")
            )
            .commit()) { "无法保留群投递责任" }
    }

    fun remove(context: Context, messageId: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(messageId.toString())
            .commit()
    }

    fun load(context: Context): List<Pending> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.values.mapNotNull { value ->
            val parts = (value as? String)?.split("|") ?: return@mapNotNull null
            if (parts.size < 5) return@mapNotNull null
            runCatching {
                Pending(
                    groupId = parts[0].toLong(),
                    messageId = parts[1].toLong(),
                    targets = parts[2].split(",").mapNotNull { it.toLongOrNull() }.toSet(),
                    mentionedIds = parts[3].split(",").mapNotNull { it.toLongOrNull() }.toSet(),
                    everyone = parts[4].toBoolean(),
                    snapshot = parts.getOrNull(5)?.takeIf { it.isNotBlank() }?.let(::decodeSnapshot)
                )
            }.getOrNull()
        }

    private fun encodeSnapshot(snapshot: com.xingshi.ai.domain.GroupConversationSnapshot): String {
        val value = org.json.JSONObject().put("id", snapshot.groupId).put("name", snapshot.groupName).put("created", snapshot.createdAt)
            .put("source", snapshot.sourceMessageId)
            .put("members", org.json.JSONArray().apply { snapshot.members.forEach { put(org.json.JSONObject().put("id", it.companionId).put("name", it.name).put("avatar", it.avatar)) } })
            .put("messages", org.json.JSONArray().apply { snapshot.messages.forEach { message -> put(org.json.JSONObject()
                .put("id", message.id).put("companion", message.companionId).put("name", message.speakerName).put("text", message.text).put("at", message.occurredAt)
                .put("type", message.mediaType).put("path", message.mediaPath).put("mentions", org.json.JSONArray(message.mentionedCompanionIds)).put("live", message.live)
                .put("authorStableId", message.authorStableId).put("authorInstanceId", message.authorInstanceId)) } })
        return com.xingshi.ai.database.repository.ChatMessageCrypto.encrypt(value.toString())
    }
    private fun decodeSnapshot(encoded: String): com.xingshi.ai.domain.GroupConversationSnapshot {
        val value = org.json.JSONObject(com.xingshi.ai.database.repository.ChatMessageCrypto.decrypt(encoded))
        val members = value.getJSONArray("members")
        val messages = value.getJSONArray("messages")
        return com.xingshi.ai.domain.GroupConversationSnapshot(value.getLong("id"), value.getString("name"), value.getLong("created"),
            (0 until members.length()).map { index -> members.getJSONObject(index).let { com.xingshi.ai.domain.GroupParticipant(it.getLong("id"), it.getString("name"), it.optString("avatar").takeIf { avatar -> avatar.isNotBlank() && avatar != "null" }) } },
            (0 until messages.length()).map { index -> messages.getJSONObject(index).let { item ->
                val mentions = item.getJSONArray("mentions")
                com.xingshi.ai.domain.GroupHistoryMessage(item.getLong("id"), if (item.isNull("companion")) null else item.getLong("companion"), item.getString("name"), item.getString("text"), item.getLong("at"), item.getString("type"), item.getString("path"), (0 until mentions.length()).map { mentions.getLong(it) }, item.optBoolean("live"),
                    item.optString("authorStableId").takeIf { it.isNotBlank() && it != "null" }, item.optString("authorInstanceId").takeIf { it.isNotBlank() && it != "null" })
            } }, if (value.isNull("source")) null else value.getLong("source"))
    }
}
