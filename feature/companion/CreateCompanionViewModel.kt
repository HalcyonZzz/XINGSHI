package com.xingshi.ai.feature.companion.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.CompanionRole
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.database.repository.UserRepository
import com.xingshi.ai.common.ImageUtils
import com.xingshi.ai.domain.ServiceRegistry
import androidx.core.content.edit
import com.xingshi.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.asStateFlow

/** 内置示例伴侣的历史标签，用于在删除时记录用户意图。 */
private const val BUILTIN_COMPANION_TAG = "default-test-companion"

class CreateCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val partition = com.xingshi.ai.common.AccountStorage.partition
    private val cloud = ServiceRegistry.get(com.xingshi.ai.domain.CompanionCloudGateway::class.java)
    // 延迟获取：页面可能在 ServiceRegistry 初始化完成前被导航到
    // （冷启动 deep link / 配置变更），构造函数内立即 getOrThrow 会抛 IllegalStateException。
    private val repository by lazy { ServiceRegistry.getOrThrow(CompanionRepository::class.java) }
    private val userRepository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }

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

    /**
     * 直接保存人设。所有 IO/数据库操作在 Dispatchers.IO 执行，异常被捕获并暴露为 saveError，
     * 避免未处理异常导致应用闪退。
     */
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
                        // 编辑保存统一在这里合并未编辑字段：null 表示本次没提供该字段，
                        // 不能把 tags / backstory / speakingStyle / createdAt 覆盖成空。
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
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun resetDeleteCompleted() {
        _deleteCompleted.value = false
    }

    fun consumeDeleteError() {
        _deleteError.value = null
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            if (companion.tags.orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .any { it == BUILTIN_COMPANION_TAG }
            ) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            try {
                repository.deleteCompanion(companion)
                _deleteCompleted.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("CreateCompanionVM", "删除人设失败", e)
                _deleteError.value = "删除失败：${e.message ?: "未知错误"}"
            }
        }
    }
}
