package com.xingshi.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.repository.CompanionRepository
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 内置示例伴侣的标签集合，用于识别可被用户删除并记录删除状态的条目。 */
private val BUILTIN_COMPANION_TAGS = setOf("default-test-companion", "default-experience-companion")

class CompanionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CompanionRepository
    val companions: Flow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = CompanionRepository(database.companionDao())
        companions = repository.getAllCompanions()
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            val isBuiltinCompanion = companion.tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it in BUILTIN_COMPANION_TAGS }
            if (isBuiltinCompanion) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            repository.deleteCompanion(companion)
        }
    }
}
