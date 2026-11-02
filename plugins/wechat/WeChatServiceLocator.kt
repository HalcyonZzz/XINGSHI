package com.xingshi.ai.feature.wechat.service

import android.content.Context
import com.xingshi.ai.feature.wechat.data.WeChatMessageRepository
import com.xingshi.ai.feature.wechat.data.WeChatSdkClientManager
import com.xingshi.ai.feature.wechat.data.WeChatTokenStore

object WeChatServiceLocator {

    @Volatile
    private var tokenStore: WeChatTokenStore? = null

    @Volatile
    private var messageRepository: WeChatMessageRepository? = null

    @Volatile
    private var sdkClientManager: WeChatSdkClientManager? = null

    fun tokenStore(context: Context): WeChatTokenStore {
        return tokenStore ?: synchronized(this) {
            tokenStore ?: WeChatTokenStore(context.applicationContext).also {
                tokenStore = it
            }
        }
    }

    fun messageRepository(context: Context): WeChatMessageRepository {
        return messageRepository ?: synchronized(this) {
            messageRepository ?: run {
                val store = tokenStore(context)
                val manager = sdkClientManager(context)
                WeChatMessageRepository(context.applicationContext, manager, store).also {
                    messageRepository = it
                }
            }
        }
    }

    /** 退出登录后丢弃旧仓库：其 processScope 已取消，再次登录必须重建实例。 */
    fun resetRepository() {
        synchronized(this) {
            messageRepository = null
            sdkClientManager = null
        }
    }

    fun shutdown() {
        synchronized(this) {
            messageRepository = null
            sdkClientManager = null
            tokenStore = null
        }
    }

    fun sdkClientManager(context: Context): WeChatSdkClientManager {
        return sdkClientManager ?: synchronized(this) {
            sdkClientManager ?: WeChatSdkClientManager(tokenStore(context)).also {
                sdkClientManager = it
            }
        }
    }
}
