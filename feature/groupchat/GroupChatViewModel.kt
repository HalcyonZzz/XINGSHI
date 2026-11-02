package com.xingshi.ai.feature.groupchat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatGroup
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.model.GroupMessage
import com.xingshi.ai.common.StickerInfo
import com.xingshi.ai.database.repository.ChatGroupRepository
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.database.repository.GroupMessageRepository
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.GroupConversationGateway
import com.xingshi.ai.domain.GroupConversationSnapshot
import com.xingshi.ai.domain.GroupHistoryMessage
import com.xingshi.ai.domain.GroupParticipant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class GroupChatViewModel(
    application: Application,
    private val groupId: Long
) : AndroidViewModel(application) {

    // 改用应用级作用域：退出群聊页面后消息落库仍能继续完成，重新进入群聊即可看到。
    private val applicationScope = com.xingshi.ai.common.ApplicationScopeProvider.scope

    private val database = AppDatabase.getDatabase(application)
    private val groupMessageRepository = GroupMessageRepository(database.groupMessageDao())
    private val chatGroupRepository = ChatGroupRepository(database.chatGroupDao())
    private val companionRepository = CompanionRepository(database.companionDao())

    private val _messageLimit = MutableStateFlow(50)
    val messages = _messageLimit.flatMapLatest { limit ->
        groupMessageRepository.getMessagesForGroup(groupId, limit)
    }

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    private val _mentions = MutableStateFlow(GroupMentions())
    val mentions: StateFlow<GroupMentions> = _mentions.asStateFlow()
    private val sendLock = Mutex()
    private val _deliveryIssue = MutableStateFlow<String?>(null)
    val deliveryIssue: StateFlow<String?> = _deliveryIssue.asStateFlow()
    private val retryInFlight = AtomicBoolean(false)

    private data class PendingDelivery(
        val snapshot: GroupConversationSnapshot,
        val sourceMessageId: Long,
        val targets: Set<Long>,
        val selection: GroupMentions
    )

    // 每条未受理消息各自保留责任：后续发送成功/失败不能抹掉更早的待重试投递。
    private val pendingDeliveries = java.util.LinkedHashMap<Long, PendingDelivery>()
    private val _deliveryRetryAvailable = MutableStateFlow(false)
    /** 未受理的群投递保留原消息与提及目标，重试只补发失败的回复请求，不重存原话。 */
    val deliveryRetryAvailable: StateFlow<Boolean> = _deliveryRetryAvailable.asStateFlow()

    private fun retainPending(pending: PendingDelivery) {
        pendingDeliveries[pending.sourceMessageId] = pending
        _deliveryRetryAvailable.value = pendingDeliveries.isNotEmpty()
        GroupPendingDeliveryStore.save(
            getApplication(),
            GroupPendingDeliveryStore.Pending(
                groupId = pending.snapshot.groupId,
                messageId = pending.sourceMessageId,
                targets = pending.targets,
                mentionedIds = pending.selection.companionIds,
                everyone = pending.selection.everyone,
                snapshot = pending.snapshot
            )
        )
    }

    private fun releasePending(sourceMessageId: Long) {
        pendingDeliveries.remove(sourceMessageId)
        GroupPendingDeliveryStore.remove(getApplication(), sourceMessageId)
        _deliveryRetryAvailable.value = pendingDeliveries.isNotEmpty()
        if (pendingDeliveries.isEmpty()) _deliveryIssue.value = null
    }

    /** 重建 VM 后只恢复本群责任；跨群历史不得借当前 VM 的群身份重组。 */
    private fun restorePendingDeliveries() {
        applicationScope.launch(Dispatchers.IO) {
            val restored = GroupPendingDeliveryStore.load(getApplication())
                .filter { it.groupId == groupId }
            if (restored.isEmpty()) return@launch
            restored.forEach { saved ->
                val group = chatGroupRepository.getGroupById(saved.groupId) ?: return@forEach
                val all = companionRepository.getAllCompanions().first().associateBy { it.id }
                pendingDeliveries[saved.messageId] = PendingDelivery(
                    snapshot = (saved.snapshot ?: buildSnapshot(group, all)).copy(sourceMessageId = saved.messageId),
                    sourceMessageId = saved.messageId,
                    targets = saved.targets,
                    selection = GroupMentions(saved.mentionedIds, saved.everyone)
                )
            }
            _deliveryRetryAvailable.value = pendingDeliveries.isNotEmpty()
        }
    }

    private suspend fun buildSnapshot(
        group: ChatGroup,
        all: Map<Long, CompanionEntity>
    ): GroupConversationSnapshot {
        val members = group.getCompanionIdList().distinct().mapNotNull { id ->
            all[id]?.let { GroupParticipant(it.id, it.name, it.avatarUrl) }
        }
        val history = groupMessageRepository.getGroupHistory(group.id).map { row ->
            GroupHistoryMessage(
                id = row.id,
                companionId = row.companionId.takeUnless { it == -1L },
                speakerName = row.publishedAuthorName ?: if (row.companionId == -1L) _userName.value else all[row.companionId]?.name.orEmpty(),
                text = row.content, occurredAt = row.timestamp,
                mediaType = row.fileFormat.name.lowercase(), mediaPath = row.linkString,
                live = row.backendLive,
                authorStableId = row.publishedAuthorStableId, authorInstanceId = row.publishedAuthorInstanceId
            )
        }
        return GroupConversationSnapshot(group.id, group.name, group.createdAt, members, history)
    }

    fun mentionCompanion(id: Long) {
        if (id !in _groupData.value?.getCompanionIdList().orEmpty()) return
        val current = _mentions.value
        _mentions.value = GroupMentions(
            companionIds = if (id in current.companionIds) current.companionIds - id else current.companionIds + id
        )
    }

    fun mentionEveryone() {
        _mentions.value = GroupMentions(everyone = !_mentions.value.everyone)
    }

    fun clearMentions() { _mentions.value = GroupMentions() }

    fun dismissDeliveryIssue() { _deliveryIssue.value = null }

    fun retryDelivery() {
        if (!retryInFlight.compareAndSet(false, true)) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                sendLock.withLock {
                    val gateway = ServiceRegistry.get(GroupConversationGateway::class.java)
                    if (gateway == null) {
                        _deliveryIssue.value = "unavailable"
                        return@withLock
                    }
                    for (candidate in pendingDeliveries.values.toList()) {
                        val pending = pendingDeliveries[candidate.sourceMessageId] ?: continue
                        retryOne(gateway, pending)
                    }
                }
            } finally {
                retryInFlight.set(false)
            }
        }
    }

    private suspend fun retryOne(gateway: GroupConversationGateway, pending: PendingDelivery): Boolean {
        return try {
            gateway.stage(pending.snapshot.copy(messages = pending.snapshot.messages.map { message ->
                if (message.id == pending.sourceMessageId) message.copy(mentionedCompanionIds = pending.selection.targets(pending.snapshot.members.map { it.companionId })) else message
            }))
            if (!syncDeclaredScope(gateway, pending.snapshot.groupId)) {
                _deliveryIssue.value = "failed"
                return false
            }
            val boundary = confirmedBoundary(pending.snapshot.groupId)
            gateway.synchronize(
                if (boundary > 0L) {
                    pending.snapshot.copy(messages = pending.snapshot.messages.filter { it.id > boundary })
                } else {
                    pending.snapshot
                }
            )
            if (pending.targets.isEmpty()) {
                confirmBoundary(pending.sourceMessageId, pending.snapshot.groupId)
                releasePending(pending.sourceMessageId)
                return true
            }
            val failed = mutableListOf<Long>()
            for (target in pending.targets) {
                try {
                    gateway.requestReply(
                        pending.snapshot.groupId,
                        pending.sourceMessageId,
                        target,
                        "group:${pending.snapshot.groupId}:message:${pending.sourceMessageId}:reply:$target"
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed += target
                }
            }
            if (failed.isEmpty()) {
                confirmBoundary(pending.sourceMessageId, pending.snapshot.groupId)
                releasePending(pending.sourceMessageId)
                if (_mentions.value == pending.selection) clearMentions()
                true
            } else {
                retainPending(pending.copy(targets = failed.toSet()))
                _deliveryIssue.value = "failed"
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _deliveryIssue.value = "failed"
            retainPending(pending)
            false
        }
    }

    private val _userName = MutableStateFlow("我")
    val userName: StateFlow<String> = _userName.asStateFlow()
    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    init {
        loadUserProfile()
        viewModelScope.launch(Dispatchers.IO) {
            val total = groupMessageRepository.getMessageCount(groupId)
            _hasMore.value = _messageLimit.value < total
        }
        // 当前群实例的合法增量：按已知本地边界确认进度，不把未授权历史当默认全量。
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val gateway = ServiceRegistry.get(GroupConversationGateway::class.java) ?: return@runCatching
                syncDeclaredScope(gateway)
            }.onFailure { Log.e("GroupChatViewModel", "group incremental sync failed", it) }
        }
    }

    /** 声明范围同步：complete 才确认终点；未确认不推进发送。 */
    private fun confirmedBoundary(targetGroupId: Long = groupId): Long =
        getApplication<Application>().getSharedPreferences("group_sync_boundary", android.content.Context.MODE_PRIVATE)
            .getLong("group_$targetGroupId", 0L)

    private fun confirmBoundary(throughMessageId: Long, targetGroupId: Long = groupId) {
        if (throughMessageId <= 0L) return
        val prefs = getApplication<Application>()
            .getSharedPreferences("group_sync_boundary", android.content.Context.MODE_PRIVATE)
        if (throughMessageId <= prefs.getLong("group_$targetGroupId", 0L)) return
        prefs.edit().putLong("group_$targetGroupId", throughMessageId).apply()
    }

    /** 只承认网关返回的确认终点；null 或不完整都不推进，也不触发回复。 */
    private suspend fun syncDeclaredScope(gateway: GroupConversationGateway, targetGroupId: Long = groupId): Boolean {
        val known = confirmedBoundary(targetGroupId)
        val progress = gateway.synchronizeDelta(targetGroupId, known) ?: return false
        if (!progress.complete) return false
        val handled = getApplication<Application>()
            .getSharedPreferences("group_member_events", android.content.Context.MODE_PRIVATE)
            .getStringSet("handled", emptySet()).orEmpty().toMutableSet()
        gateway.memberEvents(targetGroupId).forEach { event ->
            val key = event.operationId.ifBlank {
                "${event.groupId}:${event.companionId}:${event.type}:${event.occurredAt}"
            }
            if (!handled.add(key)) return@forEach
            val group = chatGroupRepository.getGroupById(targetGroupId) ?: return@forEach
            val members = if (event.members.isNotEmpty()) {
                event.members.map { it.companionId }.toMutableList()
            } else {
                group.getCompanionIdList().toMutableList()
            }
            when (event.type) {
                com.xingshi.ai.domain.GroupMemberEventType.JOIN ->
                    if (event.companionId !in members) members += event.companionId
                com.xingshi.ai.domain.GroupMemberEventType.LEAVE -> members -= event.companionId
            }
            chatGroupRepository.updateGroup(group.copy(companionIds = members.joinToString(",")))
            getApplication<Application>()
                .getSharedPreferences("group_member_events", android.content.Context.MODE_PRIVATE)
                .edit().putStringSet("handled", handled).apply()
        }
        if (progress.knownThroughMessageId > known) {
            gateway.confirmSyncProgress(targetGroupId, progress.knownThroughMessageId)
            confirmBoundary(progress.knownThroughMessageId, targetGroupId)
        }
        return true
    }

    private var nicknameUnsubscribe: (() -> Unit)? = null
    private var avatarUnsubscribe: (() -> Unit)? = null

    private fun loadUserProfile() {
        val provider = ServiceRegistry.get(com.xingshi.ai.domain.UserProfileProvider::class.java)
        _userName.value = provider?.getNickname() ?: "我"
        _userAvatar.value = provider?.getAvatar()
        nicknameUnsubscribe?.invoke()
        nicknameUnsubscribe = provider?.observeNickname { nickname ->
            _userName.value = nickname
        }
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = provider?.observeAvatar { avatar ->
            _userAvatar.value = avatar
        }
    }

    override fun onCleared() {
        super.onCleared()
        nicknameUnsubscribe?.invoke()
        nicknameUnsubscribe = null
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
    }

    fun loadMoreMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isLoadingMore.value) return@launch
            _isLoadingMore.value = true
            try {
                val total = groupMessageRepository.getMessageCount(groupId)
                val newLimit = (_messageLimit.value + 50).coerceAtMost(total)
                if (newLimit > _messageLimit.value) {
                    _messageLimit.value = newLimit
                }
                _hasMore.value = _messageLimit.value < total
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private val _groupData = MutableStateFlow<ChatGroup?>(null)
    val groupData: StateFlow<ChatGroup?> = _groupData.asStateFlow()

    private val _allCompanions = MutableStateFlow<List<CompanionEntity>>(emptyList())
    val allCompanions: StateFlow<List<CompanionEntity>> = _allCompanions.asStateFlow()

    init {
        loadGroupData()
        loadAllCompanions()
        restorePendingDeliveries()
    }

    private fun loadGroupData() {
        viewModelScope.launch {
            try {
                _groupData.value = chatGroupRepository.getGroupById(groupId)
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "loadGroupData failed", e)
            }
        }
    }

    private fun loadAllCompanions() {
        viewModelScope.launch {
            try {
                _allCompanions.value = companionRepository.getAllCompanions().first()
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "loadAllCompanions failed", e)
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isNotBlank()) submitMessage(content, _mentions.value)
    }

    fun sendMessage(content: String, mentionedIds: List<Long>, mentionAll: Boolean) {
        if (content.isNotBlank()) submitMessage(content, GroupMentions(mentionedIds.toSet(), mentionAll))
    }

    private fun submitMessage(
        content: String,
        selection: GroupMentions,
        format: com.xingshi.ai.database.model.FileFormat = com.xingshi.ai.database.model.FileFormat.TEXT,
        attachment: String = ""
    ) {
        applicationScope.launch(Dispatchers.IO) {
            sendLock.withLock {
                var savedMessageId: Long? = null
                var frozenTargets: Set<Long> = emptySet()
                var frozenSnapshot: GroupConversationSnapshot? = null
                try {
                    if (com.xingshi.ai.common.BanManager.isBanned(getApplication())) return@withLock
                    val inputCheck = com.xingshi.ai.common.ContentFilter.checkInput(content)
                    if (inputCheck.isViolating) {
                        _notices.tryEmit("内容已拦截: ${inputCheck.reason}")
                        return@withLock
                    }
                    val group = chatGroupRepository.getGroupById(groupId) ?: return@withLock
                    val all = companionRepository.getAllCompanions().first().associateBy { it.id }
                    val members = group.getCompanionIdList().distinct().mapNotNull { id ->
                        all[id]?.let { GroupParticipant(it.id, it.name, it.avatarUrl) }
                    }
                    val targets = selection.targets(members.map { it.companionId })
                    frozenTargets = targets.toSet()
                    val prefix = members.filter { it.companionId in targets }.joinToString("") { "@${it.name} " }
                    val storedAttachment = if (attachment.isEmpty()) "" else persistAttachment(attachment, "image")
                    val savedContent = when {
                        format == com.xingshi.ai.database.model.FileFormat.IMAGE -> "[图片] $storedAttachment"
                        content.startsWith("[") && content.endsWith("]") -> content
                        else -> prefix + content
                    }
                    val history = groupMessageRepository.getGroupHistory(group.id)
                    val message = GroupMessage(
                        groupId = groupId, companionId = -1L, content = savedContent,
                        timestamp = System.currentTimeMillis(), fileFormat = format, linkString = storedAttachment,
                        publishedAuthorName = _userName.value, backendLive = true
                    )
                    val messageId = groupMessageRepository.sendMessage(message)
                    savedMessageId = messageId
                    // 快照必须包含本地原话与历史：网关缺失、同步失败或部分失败时，
                    // 重试仍能按原 messageId/目标补发，而不是拿空历史另造一轮。
                    val snapshotHistory = (history + message.copy(id = messageId)).map { row ->
                        GroupHistoryMessage(
                            id = row.id,
                            companionId = row.companionId.takeUnless { it == -1L },
                            speakerName = row.publishedAuthorName ?: if (row.companionId == -1L) _userName.value else all[row.companionId]?.name.orEmpty(),
                            text = row.content, occurredAt = row.timestamp,
                            mediaType = row.fileFormat.name.lowercase(), mediaPath = row.linkString,
                            mentionedCompanionIds = if (row.id == messageId) targets else emptyList(), live = row.backendLive,
                            authorStableId = row.publishedAuthorStableId, authorInstanceId = row.publishedAuthorInstanceId
                        )
                    }
                    val snapshot = GroupConversationSnapshot(group.id, group.name, group.createdAt, members, snapshotHistory, messageId)
                    frozenSnapshot = snapshot
                    // 网络开始前保留原消息、目标及快照；重启补交不能依赖 VM 内存。
                    retainPending(PendingDelivery(snapshot, messageId, targets.toSet(), selection))
                    chatGroupRepository.updateTimestamp(groupId)
                    val gateway = ServiceRegistry.get(GroupConversationGateway::class.java)
                    if (gateway == null) {
                        // 本地保存不等于后端已受理：保留原话、目标与重试责任。
                        retainPending(PendingDelivery(snapshot, messageId, targets.toSet(), selection))
                        _deliveryIssue.value = "unavailable"
                        return@withLock
                    }
                    // 发送前必须等待声明范围同步完成；失败保留同步责任（即使没有 @ 目标）。
                    gateway.stage(snapshot)
                    if (!syncDeclaredScope(gateway)) {
                        retainPending(PendingDelivery(snapshot, messageId, targets.toSet(), selection))
                        _deliveryIssue.value = "failed"
                        return@withLock
                    }
                    val failed = dispatchGroupConversation(
                        gateway, snapshot, messageId, GroupMentions(targets.toSet()), confirmedBoundary()
                    )
                    if (failed.isEmpty()) {
                        confirmBoundary(messageId)
                        releasePending(messageId)
                        if (_mentions.value == selection) clearMentions()
                    } else {
                        retainPending(PendingDelivery(snapshot, messageId, failed.toSet(), selection))
                        _deliveryIssue.value = "failed"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    val snapshot = frozenSnapshot
                    if (savedMessageId != null && snapshot != null) {
                        retainPending(
                            PendingDelivery(
                                snapshot,
                                savedMessageId ?: 0L,
                                frozenTargets,
                                selection
                            )
                        )
                    }
                    _deliveryIssue.value = "failed"
                    Log.e("GroupChatViewModel", "group delivery failed", e)
                }
            }
        }
    }
    fun recallMessage(message: GroupMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                groupMessageRepository.deleteMessage(message)
                Log.d("GroupChatViewModel", "消息已撤回: id=${message.id}")
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "recallMessage failed", e)
            }
        }
    }

    fun deleteGroup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = _groupData.value
                    ?: throw IllegalStateException("Group data is null")
                chatGroupRepository.deleteGroup(group)
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "deleteGroup failed", e)
            }
        }
    }

    fun updateGroupAvatar(avatarUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = _groupData.value
                    ?: throw IllegalStateException("Group data is null")
                val updatedGroup = group.copy(avatarUrl = avatarUrl)
                chatGroupRepository.updateGroup(updatedGroup)
                _groupData.value = updatedGroup
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "updateGroupAvatar failed", e)
            }
        }
    }

    fun updateGroupName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val group = _groupData.value
                    ?: throw IllegalStateException("Group data is null")
                val updatedGroup = group.copy(name = name)
                chatGroupRepository.updateGroup(updatedGroup)
                _groupData.value = updatedGroup
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "updateGroupName failed", e)
            }
        }
    }

    fun sendUserSticker(sticker: StickerInfo) {
        submitMessage("[${sticker.fileName ?: sticker.name}]", _mentions.value)
    }

    fun sendImageMessage(imagePath: String) {
        submitMessage("[图片]", _mentions.value, com.xingshi.ai.database.model.FileFormat.IMAGE, imagePath)
    }

    /** cacheDir 原件可被系统清理，落库前转存到 filesDir 保证长期可定位。 */
    private fun persistAttachment(path: String, kind: String): String {
        val source = java.io.File(path)
        if (!source.isFile) return path
        val app = getApplication<Application>()
        val inCache = runCatching {
            source.canonicalPath.startsWith(app.cacheDir.canonicalPath + java.io.File.separator)
        }.getOrDefault(false)
        if (!inCache) return path
        val dir = java.io.File(app.filesDir, "attachments/$kind").apply { mkdirs() }
        val target = java.io.File(dir, "${System.currentTimeMillis()}_${source.name}")
        return runCatching {
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrDefault(path)
    }
}

class GroupChatViewModelFactory(
    private val application: Application,
    private val groupId: Long
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return GroupChatViewModel(application, groupId) as T
    }
}
