package com.xingshi.ai.network

import android.content.Context
import com.xingshi.ai.domain.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class BackendGroupInstance(val instanceId: String, val conversationId: String, val provider: String = "", val model: String = "")
interface BackendGroupIdentity {
    suspend fun instance(localId: Long): BackendGroupInstance?
    suspend fun snapshot(groupId: Long): GroupConversationSnapshot?
    suspend fun groups(): List<Long>
    fun viewerId(): String
    fun viewerName(): String
}

interface BackendGroupHandoffs {
    fun read(key: String): String?
    fun write(values: Map<String, String>)
    fun entries(): Map<String, String>
}

private class PreferenceGroupHandoffs(context: Context) : BackendGroupHandoffs {
    private val prefs = context.getSharedPreferences("backend_group_handoffs", Context.MODE_PRIVATE)
    override fun read(key: String) = prefs.all[key]?.toString()
    override fun entries() = prefs.all.mapNotNull { (key, value) -> value?.toString()?.let { key to it } }.toMap()
    override fun write(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "无法保存群同步责任" }
    }
}

class BackendGroupGateway(
    private val http: BackendHttpClient, private val identities: BackendGroupIdentity,
    private val handoffs: BackendGroupHandoffs,
    private val encrypt: (String) -> String, private val decrypt: (String) -> String
) : GroupConversationGateway {
    constructor(http: BackendHttpClient, identities: BackendGroupIdentity, context: Context,
                encrypt: (String) -> String, decrypt: (String) -> String) :
        this(http, identities, PreferenceGroupHandoffs(context), encrypt, decrypt)

    private val lock = Mutex()
    private val snapshots = mutableMapOf<Long, GroupConversationSnapshot>()
    override fun stage(snapshot: GroupConversationSnapshot) { remember(snapshot) }
    private fun remember(snapshot: GroupConversationSnapshot) = synchronized(snapshots) {
        val earlier = snapshots[snapshot.groupId]
        snapshots[snapshot.groupId] = snapshot.copy(messages =
            (earlier?.messages.orEmpty() + snapshot.messages).associateBy { it.id }.values.sortedBy { it.id })
    }
    private fun groupRef(group: Long) = handoffs.read("group:$group") ?: "mobile-group-${UUID.randomUUID()}".also {
        handoffs.write(mapOf("group:$group" to it))
    }
    fun localGroup(ref: String): Long? = handoffs.entries().entries.firstOrNull { it.key.startsWith("group:") && it.value == ref }?.key?.substringAfter(':')?.toLongOrNull()
    private fun original(group: Long, message: Long) = "${groupRef(group)}:message:$message"
    private fun sourceKey(instance: BackendGroupInstance, ref: String, message: Long) = "source:${instance.instanceId}:$ref:$message"
    private fun headers(instance: BackendGroupInstance) = mapOf("x-loom-instance-id" to instance.instanceId)
    private fun rememberedAuthor(ref: String, localId: Long) = handoffs.read("author:$ref:$localId")
        ?.let { Json.parseToJsonElement(decrypt(it)).jsonObject }
    private suspend fun sourceAuthor(ref: String, message: GroupHistoryMessage): JsonObject {
        val remembered = message.companionId?.let { rememberedAuthor(ref, it) }
        val instanceId = message.authorInstanceId ?: remembered?.text("instance_id")
            ?: message.companionId?.let { identities.instance(it)?.instanceId ?: error("群作者云端身份不可读取") }
        return buildJsonObject {
            put("stable_id", message.authorStableId ?: remembered?.text("stable_id") ?: identities.viewerId())
            put("nickname", message.speakerName.ifBlank { if (instanceId == null) identities.viewerName() else error("缺少群作者昵称") })
            put("kind", if (instanceId == null) "HUMAN" else "AI_INSTANCE")
            instanceId?.let { put("instance_id", it) }
        }
    }
    private fun frozenSource(instance: BackendGroupInstance, ref: String, message: Long) =
        handoffs.read(sourceKey(instance, ref, message))?.let { Json.parseToJsonElement(decrypt(it)).jsonObject }

    override suspend fun synchronize(snapshot: GroupConversationSnapshot) = lock.withLock { synchronizeLocked(snapshot) }
    private suspend fun synchronizeLocked(snapshot: GroupConversationSnapshot, since: Long = 0) {
        remember(snapshot)
        val ref = groupRef(snapshot.groupId)
        val memberIds = snapshot.members.map { identities.instance(it.companionId) ?: error("群成员尚未确认云端身份") }
        val previousMembers = handoffs.read("active-members:$ref")?.split('|').orEmpty()
        val cutoff = snapshot.messages.filter { it.id != snapshot.sourceMessageId }.maxOfOrNull { it.id } ?: 0L
        val changes = mutableMapOf("active-members:$ref" to memberIds.joinToString("|") { it.instanceId })
        snapshot.members.forEachIndexed { index, member ->
            if (rememberedAuthor(ref, member.companionId) == null) changes["author:$ref:${member.companionId}"] = encrypt(buildJsonObject {
                put("stable_id", identities.viewerId()); put("instance_id", memberIds[index].instanceId)
            }.toString())
        }
        memberIds.filter { it.instanceId !in previousMembers }.forEach {
            changes["visible-after:${it.instanceId}:$ref"] = cutoff.toString()
        }
        val memberRows = buildJsonArray {
            add(buildJsonObject { put("stable_id", identities.viewerId()); put("nickname", identities.viewerName()); put("kind", "HUMAN") })
            snapshot.members.forEachIndexed { index, member -> add(buildJsonObject {
                put("stable_id", identities.viewerId()); put("nickname", member.name); put("kind", "AI_INSTANCE"); put("instance_id", memberIds[index].instanceId)
            }) }
        }
        val signature = memberRows.toString()
        val version = (handoffs.read("members-version:$ref")?.toLongOrNull() ?: 0) + if (handoffs.read("members:$ref") == signature) 0 else 1
        changes["members:$ref"] = signature
        changes["members-version:$ref"] = version.coerceAtLeast(1).toString()
        handoffs.write(changes)
        val members = buildJsonObject { put("version", version.coerceAtLeast(1)); put("members", memberRows) }
        for (instance in memberIds) {
            val common = buildJsonObject { put("scene", "GROUP"); put("group_ref", ref); put("group_name", snapshot.groupName); put("member_snapshot", members) }
            http.json("POST", "/scene-conversations/register", common, headers(instance))
            val visibleAfter = handoffs.read("visible-after:${instance.instanceId}:$ref")?.toLongOrNull() ?: 0
            val visibleMessages = snapshot.messages.filter {
                it.id > since && (it.id > visibleAfter || it.id == snapshot.sourceMessageId)
            }.sortedBy { it.id }
            val sources = mutableListOf<JsonObject>()
            for (message in visibleMessages) {
                val frozen = frozenSource(instance, ref, message.id) ?: buildJsonObject {
                    common.forEach { (key, value) -> put(key, value) }
                    put("original_message_id", original(snapshot.groupId, message.id)); put("author", sourceAuthor(ref, message))
                    put("content", message.text); put("content_digest", MessageDigest.getInstance("SHA-256").digest(message.text.toByteArray()).joinToString("") { "%02x".format(it) })
                    put("occurred_at", Instant.ofEpochMilli(message.occurredAt).toString()); put("received_seq", message.id)
                    put("reply_conversation_id", instance.conversationId); put("reply_group_ref", ref)
                    put("mentions", buildJsonArray { message.mentionedCompanionIds.forEach { target ->
                        val targetId = rememberedAuthor(ref, target)?.text("instance_id") ?: identities.instance(target)?.instanceId ?: error("提及目标尚未确认身份")
                        add(buildJsonObject { put("kind", "INSTANCE"); put("instance_id", targetId) })
                    } })
                    if (!message.live) { put("visible_from_seq", visibleMessages.first().id); put("visible_to_seq", visibleMessages.last().id) }
                }.also { handoffs.write(mapOf(sourceKey(instance, ref, message.id) to encrypt(it.toString()))) }
                http.json("POST", "/scene-messages/sync", frozen, headers(instance))
                sources += frozen
            }
            if (sources.isNotEmpty()) {
                val lastId = visibleMessages.last().id
                val syncKey = "increment:${instance.instanceId}:$ref:$lastId"
                val syncId = handoffs.read(syncKey) ?: UUID.randomUUID().toString().also { handoffs.write(mapOf(syncKey to it)) }
                val originalsKey = "increment-originals:${instance.instanceId}:$ref:$lastId"
                val originals = handoffs.read(originalsKey)?.let { Json.parseToJsonElement(decrypt(it)).jsonArray }
                    ?: JsonArray(sources.map { source -> buildJsonObject {
                        put("original_message_id", source.text("original_message_id")); put("author_stable_id", source.obj("author").text("stable_id"))
                    } }).also { handoffs.write(mapOf(originalsKey to encrypt(it.toString()))) }
                val chunks = originals.chunked(50)
                http.json("POST", "/scene-increments/declare", buildJsonObject { put("sync_id", syncId); put("group_ref", ref); put("segment_count", chunks.size); put("through", originals.last()) }, headers(instance))
                chunks.forEachIndexed { index, messages -> http.json("POST", "/scene-increments/${pathSegment(syncId)}/segments", buildJsonObject {
                    put("group_ref", ref); put("index", index); put("originals", JsonArray(messages))
                }, headers(instance)) }
                val progress = http.json("GET", "/scene-increments/${pathSegment(syncId)}", headers = headers(instance))
                check(progress["complete"]?.jsonPrimitive?.booleanOrNull == true) { "群增量尚未完整确认" }
                val throughKey = "through:${instance.instanceId}:$ref"
                val previousThrough = handoffs.read(throughKey)?.toLongOrNull() ?: 0
                if (lastId >= previousThrough) {
                    handoffs.write(mapOf("latest-increment:${instance.instanceId}:$ref" to syncId, throughKey to lastId.toString()))
                }
            }
        }
    }

    override suspend fun synchronizeDelta(groupId: Long, knownThroughMessageId: Long): GroupSyncProgress = lock.withLock {
        val staged = synchronized(snapshots) { snapshots[groupId] }
        val local = identities.snapshot(groupId) ?: staged ?: error("原群资料不可读取")
        val originals = staged?.messages?.associateBy { it.id }.orEmpty()
        val sourceId = staged?.sourceMessageId?.takeIf { id -> id > knownThroughMessageId && staged.members.map { it.companionId } == local.members.map { it.companionId } }
        val snapshot = local.copy(messages = local.messages.map { originals[it.id] ?: it }, sourceMessageId = sourceId)
        synchronizeLocked(snapshot, knownThroughMessageId)
        GroupSyncProgress(groupId, snapshot.messages.maxOfOrNull { it.id }?.coerceAtLeast(knownThroughMessageId) ?: knownThroughMessageId, true)
    }
    override suspend fun synchronizeBeforePrivateChat(companionId: Long) {
        for (id in identities.groups()) {
            val snapshot = identities.snapshot(id) ?: continue
            if (snapshot.members.none { it.companionId == companionId }) continue
            val instance = identities.instance(companionId) ?: error("助手尚未确认身份")
            synchronizeDelta(id, handoffs.read("through:${instance.instanceId}:${groupRef(id)}")?.toLongOrNull() ?: 0)
        }
    }
    override suspend fun requestReply(groupId: Long, sourceMessageId: Long, companionId: Long, operationId: String) {
        val instance = identities.instance(companionId) ?: error("助手尚未确认身份")
        val ref = groupRef(groupId)
        val source = frozenSource(instance, ref, sourceMessageId) ?: error("原群输入尚未确认同步")
        val binding = http.json("GET", "/api/instances/${pathSegment(instance.instanceId)}/chat-configuration")
        check(binding.text("provider").isNotBlank() && binding.text("model").isNotBlank()) { "该助手尚未绑定普通聊天模型" }
        val increment = handoffs.read("latest-increment:${instance.instanceId}:$ref")
        http.json("POST", "/scene-messages/trigger", buildJsonObject {
            put("scene", "GROUP"); put("group_ref", ref); put("original_message_id", source.text("original_message_id")); put("author_stable_id", source.obj("author").text("stable_id"))
            put("provider", binding.text("provider")); put("model", binding.text("model")); put("trace_id", operationId)
            put("scene_increment_ids", JsonArray(listOfNotNull(increment?.let(::JsonPrimitive))))
        }, headers(instance))
    }
    override suspend fun confirmSyncProgress(groupId: Long, throughMessageId: Long) = Unit
}
