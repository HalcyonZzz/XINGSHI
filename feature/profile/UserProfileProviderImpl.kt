package com.xingshi.ai.feature.profile

import android.content.Context
import com.xingshi.ai.database.repository.UserRepository
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.UserProfileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Adapter that bridges domain UserProfileProvider to UserRepository.
 * Registered in XingShiApplication via ServiceRegistry.
 */
class UserProfileProviderImpl(context: Context) : UserProfileProvider {

    // 复用 Application 已注册的仓储实例：自带第二份内存状态会让设置页与订阅者各说各话。
    private val repository: UserRepository by lazy {
        ServiceRegistry.getOrThrow(UserRepository::class.java)
    }

    // 用于订阅 StateFlow 的内部作用域，生命周期与 Application 一致
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun getUserId(): String = ServiceRegistry.get(com.xingshi.ai.domain.BackendAccountGateway::class.java)?.state?.value?.userId.orEmpty()

    override fun getNickname(): String = repository.userName.value

    override fun getAvatar(): String? = repository.userAvatar.value

    override fun observeNickname(onChange: (String) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userName.collect { name ->
                onChange(name)
            }
        }
        return { job.cancel() }
    }

    override fun observeAvatar(onChange: (String?) -> Unit): () -> Unit {
        val job = observerScope.launch {
            repository.userAvatar.collect { avatar ->
                onChange(avatar)
            }
        }
        return { job.cancel() }
    }

    override fun isLoggedIn(): Boolean = getUserId().isNotBlank()
}
