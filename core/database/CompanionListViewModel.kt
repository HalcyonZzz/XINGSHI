package com.xingshi.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.database.AppDatabase
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.repository.CompanionRepository
import com.xingshi.ai.domain.CompanionCloudGateway
import com.xingshi.ai.domain.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CompanionDeletionOutcome {
    object Deleted : CompanionDeletionOutcome
    object Pending : CompanionDeletionOutcome
    data class Failed(val reason: String) : CompanionDeletionOutcome
}

 
data class CompanionDeleteNotice(val key: String, val detail: String = "")

class CompanionCloudDeletion(private val cloud: () -> CompanionCloudGateway?) {
    private val inFlight = mutableSetOf<Long>()

    suspend fun delete(localId: Long): CompanionDeletionOutcome {
        synchronized(inFlight) { if (!inFlight.add(localId)) return CompanionDeletionOutcome.Pending }
        return try {
            val gateway = cloud() ?: return CompanionDeletionOutcome.Failed("")
            if (gateway.delete(localId)) CompanionDeletionOutcome.Deleted else CompanionDeletionOutcome.Pending
        } catch (timeout: TimeoutCancellationException) {
            CompanionDeletionOutcome.Pending
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CompanionDeletionOutcome.Failed(failure.message.orEmpty())
        } finally {
            synchronized(inFlight) { inFlight.remove(localId) }
        }
    }
}

class CompanionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CompanionRepository
    val companions: Flow<List<CompanionEntity>>
    private val deletion = CompanionCloudDeletion {
        ServiceRegistry.get(CompanionCloudGateway::class.java)
    }

    private val _deleteMessage = MutableStateFlow<CompanionDeleteNotice?>(null)
    val deleteMessage: StateFlow<CompanionDeleteNotice?> = _deleteMessage.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = CompanionRepository(database.companionDao())
        companions = repository.getAllCompanions()
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            _deleteMessage.value = when (val outcome = deletion.delete(companion.id)) {
                CompanionDeletionOutcome.Deleted -> null
                CompanionDeletionOutcome.Pending -> CompanionDeleteNotice("ui_companion_delete_pending")
                is CompanionDeletionOutcome.Failed -> CompanionDeleteNotice("ui_companion_delete_failed", outcome.reason)
            }
        }
    }

    fun consumeDeleteMessage() {
        _deleteMessage.value = null
    }
}
