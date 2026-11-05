package com.xingshi.ai.feature.companion.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.CompanionRole
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.database.repository.UserRepository
import com.xingshi.ai.common.ImageUtils
import com.xingshi.ai.database.viewmodel.CompanionCloudDeletion
import com.xingshi.ai.database.viewmodel.CompanionDeleteNotice
import com.xingshi.ai.database.viewmodel.CompanionDeletionOutcome
import com.xingshi.ai.domain.CloudCompanionDraft
import com.xingshi.ai.domain.CompanionCloudGateway
import com.xingshi.ai.domain.CompanionLimitReachedException
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.asStateFlow

const val COMPANION_LIMIT_REACHED_KEY = "ui_companion_limit_reached"

class CompanionCreationRejection(
    private val prefs: SharedPreferences?,
    private val deleteAvatar: (String) -> Boolean = { java.io.File(it).delete() },
) {
    suspend fun insert(markNew: Boolean, insertCompanion: suspend () -> Long): Long {
        val localId = insertCompanion()
        if (markNew) {
            check(prefs != null && prefs.edit().putLong("new-companion", localId).commit())
        }
        return localId
    }

    suspend fun <T> handle(
        localId: Long,
        createdHere: Boolean,
        avatarCopy: String? = null,
        deleteCompanion: suspend (Long) -> Unit = {},
        save: suspend () -> T,
    ): T = try {
        save()
    } catch (rejected: CompanionLimitReachedException) {
        discard(localId, createdHere, avatarCopy, deleteCompanion)
        throw rejected
    }

    suspend fun discard(
        localId: Long,
        createdHere: Boolean,
        avatarCopy: String? = null,
        deleteCompanion: suspend (Long) -> Unit = {},
    ): Boolean {
        val owned = createdHere || prefs?.let {
            it.getLong("new-companion", 0) == localId ||
                it.contains("pending-companion:$localId") || it.contains("op:companion:$localId")
        } == true
        if (owned) {
            deleteCompanion(localId)
            prefs?.let { prefs ->
                val editor = prefs.edit().remove("pending-companion:$localId").remove("op:companion:$localId")
                if (prefs.getLong("new-companion", 0) == localId) editor.remove("new-companion")
                editor.commit()
            }
        }
        discardAvatar(avatarCopy)
        return owned
    }

    fun discardAvatar(avatarCopy: String?) {
        avatarCopy?.let(deleteAvatar)
    }
}

class CompanionSaveFlow(
    private val expectedPartition: String,
    private val currentPartition: () -> String,
    private val copyAvatar: suspend (String) -> String?,
    private val gateway: () -> CompanionCloudGateway?,
    private val loadCompanion: suspend (Long) -> CompanionEntity?,
    private val updateCompanion: suspend (CompanionEntity) -> Unit,
    private val rejection: CompanionCreationRejection,
) {
    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError

    fun begin(): Boolean {
        if (_isSaving.value) return false
        _isSaving.value = true
        _saveError.value = null
        return true
    }

    fun consumeSaveError() {
        _saveError.value = null
    }

    fun resetSaveCompleted() {
        _saveCompleted.value = false
    }

    suspend fun save(companion: CompanionEntity, isEditMode: Boolean) {
        _isSaving.value = true
        _saveError.value = null
        var avatarCopy: String? = null
        try {
            check(expectedPartition == currentPartition()) { "账号已切换" }
            val avatarUrl = companion.avatarUrl
            val savedAvatar = if (avatarUrl != null && avatarUrl.startsWith("content://")) {
                copyAvatar(avatarUrl)
            } else avatarUrl
            if (savedAvatar != null && savedAvatar != avatarUrl) avatarCopy = savedAvatar
            val companionToSave = companion.copy(avatarUrl = savedAvatar)
            withContext(Dispatchers.IO) {
                val cloud = gateway()
                if (cloud != null) {
                    val id = cloud.save(CloudCompanionDraft(companion.id, companion.name, companion.personality,
                        companion.backstory, companion.speakingStyle, companion.rawPrompt))
                    check(expectedPartition == currentPartition()) { "账号已切换" }
                    loadCompanion(id)?.let { saved -> updateCompanion(saved.copy(avatarUrl = savedAvatar ?: saved.avatarUrl)) }
                    return@withContext
                }
                check(isEditMode) { "登录后才能创建助手" }
                val current = loadCompanion(companion.id)
                    ?: error("原人设未加载，无法保存")
                updateCompanion(
                    current.copy(
                        name = companionToSave.name.ifBlank { current.name },
                        avatarUrl = companionToSave.avatarUrl ?: current.avatarUrl,
                        age = companionToSave.age ?: current.age,
                        personality = companionToSave.personality,
                        backstory = companionToSave.backstory ?: current.backstory,
                        speakingStyle = companionToSave.speakingStyle ?: current.speakingStyle,
                        tags = companionToSave.tags ?: current.tags,
                        rawPrompt = companionToSave.rawPrompt,
                        systemPrompt = companionToSave.systemPrompt?.takeIf { it.isNotBlank() }
                            ?: current.systemPrompt
                    )
                )
            }
            _saveCompleted.value = true
        } catch (limit: CompanionLimitReachedException) {
            rejection.discard(companion.id, createdHere = false, avatarCopy = avatarCopy)
            _saveError.value = COMPANION_LIMIT_REACHED_KEY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rejection.discardAvatar(avatarCopy)
            SecureLog.e("CreateCompanionVM", "保存人设失败", e)
            _saveError.value = "保存失败：${e.message ?: "未知错误"}"
        } finally {
            _isSaving.value = false
        }
    }
}

class CreateCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val partition = com.xingshi.ai.common.AccountStorage.partition
    private val repository by lazy { ServiceRegistry.getOrThrow(CompanionRepository::class.java) }
    private val userRepository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }
    private val deletion = CompanionCloudDeletion {
        ServiceRegistry.get(com.xingshi.ai.domain.CompanionCloudGateway::class.java)
    }
    private val saver = CompanionSaveFlow(
        expectedPartition = partition,
        currentPartition = { com.xingshi.ai.common.AccountStorage.partition },
        copyAvatar = { uri -> ImageUtils.saveUriToInternalStorage(getApplication(), uri) },
        gateway = { ServiceRegistry.get(CompanionCloudGateway::class.java) },
        loadCompanion = { repository.getCompanionById(it) },
        updateCompanion = { repository.updateCompanion(it) },
        rejection = CompanionCreationRejection(prefs = null),
    )

    val selectedRole: StateFlow<CompanionRole> by lazy { userRepository.selectedRole }

    private val _existingCompanion = MutableStateFlow<CompanionEntity?>(null)
    val existingCompanion: StateFlow<CompanionEntity?> = _existingCompanion

    val saveCompleted: StateFlow<Boolean> = saver.saveCompleted

    val isSaving: StateFlow<Boolean> = saver.isSaving

    val saveError: StateFlow<String?> = saver.saveError

    fun loadCompanion(id: Long) {
        viewModelScope.launch {
            _existingCompanion.value = repository.getCompanionById(id)
        }
    }

    fun saveCompanion(companion: CompanionEntity, isEditMode: Boolean) {
        if (!saver.begin()) return
        viewModelScope.launch { saver.save(companion, isEditMode) }
    }

    fun consumeSaveError() = saver.consumeSaveError()

    fun resetSaveCompleted() = saver.resetSaveCompleted()

    private val _deleteCompleted = MutableStateFlow(false)
    val deleteCompleted: StateFlow<Boolean> = _deleteCompleted.asStateFlow()
    private val _deleteError = MutableStateFlow<CompanionDeleteNotice?>(null)
    val deleteError: StateFlow<CompanionDeleteNotice?> = _deleteError.asStateFlow()

    fun resetDeleteCompleted() {
        _deleteCompleted.value = false
    }

    fun consumeDeleteError() {
        _deleteError.value = null
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            _deleteError.value = null
            when (val outcome = deletion.delete(companion.id)) {
                CompanionDeletionOutcome.Deleted -> _deleteCompleted.value = true
                CompanionDeletionOutcome.Pending -> _deleteError.value = CompanionDeleteNotice("ui_companion_delete_pending")
                is CompanionDeletionOutcome.Failed ->
                    _deleteError.value = CompanionDeleteNotice("ui_companion_delete_failed", outcome.reason)
            }
        }
    }
}
