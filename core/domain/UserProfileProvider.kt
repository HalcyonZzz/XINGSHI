package com.xingshi.ai.domain

interface UserProfileProvider {
    fun getUserId(): String
    fun getNickname(): String
    fun getAvatar(): String?

    fun observeNickname(onChange: (String) -> Unit): () -> Unit

    fun observeAvatar(onChange: (String?) -> Unit): () -> Unit

    fun isLoggedIn(): Boolean
}
