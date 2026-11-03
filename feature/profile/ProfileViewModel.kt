package com.xingshi.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.ImageUtils
import com.xingshi.ai.database.repository.UserRepository
import com.xingshi.ai.domain.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = ServiceRegistry.get(com.xingshi.ai.domain.BackendAccountGateway::class.java)
    private val partition = com.xingshi.ai.common.AccountStorage.partition
    val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    init { viewModelScope.launch { gateway?.state?.collect { account -> error.value = account.error } } }
    fun reconnect() {
        runCatching { gateway?.reconnect() }.onFailure { error.value = it.message ?: "连接恢复失败" }
    }
    fun logout() { viewModelScope.launch {
        runCatching { checkNotNull(gateway) { "请先登录" }.logout() }
            .onFailure { error.value = it.message ?: "退出失败" }
    } }
    
    private val repository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }

    val userName: StateFlow<String> by lazy { repository.userName }
    val userAvatar: StateFlow<String?> by lazy { repository.userAvatar }
    val nameSaving = MutableStateFlow(false)
    val nameError = MutableStateFlow<String?>(null)
    val nameSaved = MutableStateFlow(false)

    fun updateUserName(name: String) {
        val nickname = name.trim()
        if (nickname.isBlank()) { nameError.value = "昵称不能为空"; return }
        if (nameSaving.value) return
        nameError.value = null
        viewModelScope.launch {
            nameSaving.value = true
            runCatching { checkNotNull(gateway) { "请先登录" }.updateProfile(nickname) }
                .onSuccess { nameSaved.value = true }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    nameError.value = failure.message ?: "资料保存失败"
                }
            nameSaving.value = false
        }
    }

    fun consumeNameSaved() { nameSaved.value = false }

    fun clearNameError() { nameError.value = null }

    fun updateUserAvatar(avatarUri: String?) {
        viewModelScope.launch {
            if (partition != com.xingshi.ai.common.AccountStorage.partition) return@launch
            val savedUri = if (avatarUri != null) {
                ImageUtils.saveUriToInternalStorage(getApplication(), avatarUri)
            } else null
            if (partition == com.xingshi.ai.common.AccountStorage.partition) repository.updateUserAvatar(savedUri)
        }
    }
}
