package com.xingshi.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.ChatGroup
import com.xingshi.ai.database.repository.ChatGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ChatGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChatGroupRepository
    val groups: Flow<List<ChatGroup>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatGroupRepository(database.chatGroupDao())
        groups = repository.getAllGroups()
    }

    fun createGroup(
        name: String,
        companionIds: List<Long>,
        onCreated: (suspend (Long, Long) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val createdAt = System.currentTimeMillis()
            val group = ChatGroup(
                name = name,
                companionIds = companionIds.joinToString(","),
                createdAt = createdAt,
                updatedAt = createdAt
            )
            val id = repository.insertGroup(group)
            onCreated?.invoke(id, createdAt)
        }
    }

    fun deleteGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
        }
    }

    fun updateGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.updateGroup(group)
        }
    }
}
