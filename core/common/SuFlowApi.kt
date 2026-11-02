package com.xingshi.ai.common

/**
 * 服务端点常量。
 *
 * 不内置中继、服务端地址与任何 API 凭证，用户在设置中配置自己的
 * OpenAI 兼容端点。
 */
object SuFlowApi {
    const val BASE_URL = ""
    const val AUTH_BASE_URL = ""
    const val CHAT_BASE_URL = ""
    const val CHAT_PATH = "/chat/completions"
    const val MODELS_PATH = "/models"
    const val USAGE_PATH = "/usage"
    const val HANDSHAKE_PATH = ""
    const val KEYS_FETCH_PATH = ""
    const val OPEN_SOURCE_PROVISION = ""
    const val OPEN_SOURCE_KEY_FETCH = ""
    const val TIMEOUT_SECONDS = 30L
    const val TEST_MODEL = "gpt-4o-mini"

    fun deviceFingerprint(): String {
        val fp = android.os.Build.FINGERPRINT + "|" +
            android.os.Build.MODEL + "|" +
            android.os.Build.SERIAL
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(fp.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
