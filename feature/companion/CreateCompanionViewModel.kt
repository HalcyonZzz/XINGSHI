package com.xingshi.ai.feature.companion.ui.viewmodel

import android.app.Application
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
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.asStateFlow

class CreateCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val partition = com.xingshi.ai.common.AccountStorage.partition
    private val cloud = ServiceRegistry.get(com.xingshi.ai.domain.CompanionCloudGateway::class.java)
    
    
    private val repository by lazy { ServiceRegistry.getOrThrow(CompanionRepository::class.java) }
    private val userRepository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }
    private val deletion = CompanionCloudDeletion {
        ServiceRegistry.get(com.xingshi.ai.domain.CompanionCloudGateway::class.java)
    }

    val selectedRole: StateFlow<CompanionRole> by lazy { userRepository.selectedRole }

    private val _existingCompanion = MutableStateFlow<CompanionEntity?>(null)
    val existingCompanion: StateFlow<CompanionEntity?> = _existingCompanion

    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError

    fun loadCompanion(id: Long) {
        viewModelScope.launch {
            _existingCompanion.value = repository.getCompanionById(id)
        }
    }

     
    fun saveCompanion(companion: CompanionEntity, isEditMode: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true
            _saveError.value = null
            try {
                check(partition == com.xingshi.ai.common.AccountStorage.partition) { "账号已切换" }
                val avatarUrl = companion.avatarUrl
                val savedAvatar = if (avatarUrl != null && avatarUrl.startsWith("content://")) {
                    ImageUtils.saveUriToInternalStorage(getApplication(), avatarUrl)
                } else avatarUrl

                val companionToSave = companion.copy(avatarUrl = savedAvatar)
                withContext(Dispatchers.IO) {
                    if (cloud != null) {
                        val id = cloud.save(com.xingshi.ai.domain.CloudCompanionDraft(companion.id, companion.name, companion.personality,
                            companion.backstory, companion.speakingStyle, companion.rawPrompt))
                        check(partition == com.xingshi.ai.common.AccountStorage.partition) { "账号已切换" }
                        repository.getCompanionById(id)?.let { saved -> repository.updateCompanion(saved.copy(avatarUrl = savedAvatar ?: saved.avatarUrl)) }
                        return@withContext
                    }
                    if (isEditMode) {
                        
                        
                        val current = repository.getCompanionById(companion.id)
                            ?: error("原人设未加载，无法保存")
                        repository.updateCompanion(
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
                    } else {
                        repository.insertCompanion(companionToSave)
                    }
                }
                _saveCompleted.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("CreateCompanionVM", "保存人设失败", e)
                _saveError.value = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun consumeSaveError() {
        _saveError.value = null
    }

    fun resetSaveCompleted() {
        _saveCompleted.value = false
    }

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
