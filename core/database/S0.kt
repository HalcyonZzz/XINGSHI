package com.xingshi.ai.database.repository

/**
 * API 密钥编解码实现。
 *
 * 密钥沿用常规仓储路径存储；存储策略集中在本类，便于按需替换。
 */
object S0 : ApiConfigRepository.SecretCodec {
    override fun encrypt(plaintext: String): String = ChatMessageCrypto.encrypt(plaintext)
    override fun decrypt(value: String): String? = runCatching { ChatMessageCrypto.decrypt(value) }.getOrNull()
    override fun isEncrypted(value: String): Boolean = value.startsWith("enc:v1:")
}
