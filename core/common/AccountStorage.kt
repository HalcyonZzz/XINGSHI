package com.xingshi.ai.common

import java.util.Base64

 
object AccountStorage {
    @Volatile var partition: String = "unbound"
        private set
    fun select(environment: String, userId: String) {
        require(environment.isNotBlank() && userId.isNotBlank())
        partition = Base64.getUrlEncoder().withoutPadding().encodeToString("$environment\n$userId".toByteArray())
    }
    fun unbind() { partition = "unbound" }
    fun preferenceName(name: String): String = if (name in setOf("auth_store_encrypted", "auth_prefs", "backend_environment", "agreement_prefs")) name else "account_${partition}_$name"
}
