package com.xingshi.ai.feature.qqbot.service

import android.content.Context
import com.xingshi.ai.feature.qqbot.data.QQBotMessageRepository
import com.xingshi.ai.feature.qqbot.data.QQBotTokenStore
import com.xingshi.ai.feature.qqbot.data.network.QQBotApiClient

object QQBotServiceLocator {

    @Volatile
    private var tokenStore: QQBotTokenStore? = null

    @Volatile
    private var apiClient: QQBotApiClient? = null

    @Volatile
    private var messageRepository: QQBotMessageRepository? = null

    fun tokenStore(context: Context): QQBotTokenStore {
        return tokenStore ?: synchronized(this) {
            tokenStore ?: QQBotTokenStore(context.applicationContext).also {
                tokenStore = it
            }
        }
    }

    fun apiClient(context: Context): QQBotApiClient {
        return apiClient ?: synchronized(this) {
            apiClient ?: QQBotApiClient(tokenStore(context)).also {
                apiClient = it
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            messageRepository = null
            apiClient = null
            tokenStore = null
        }
    }

    fun messageRepository(context: Context): QQBotMessageRepository {
        return messageRepository ?: synchronized(this) {
            messageRepository ?: run {
                val store = tokenStore(context)
                val client = apiClient(context)
                QQBotMessageRepository(context.applicationContext, store, client).also {
                    messageRepository = it
                }
            }
        }
    }


}
