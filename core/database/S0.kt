package com.xingshi.ai.database.repository

object S0 : ApiConfigRepository.SecretCodec {
    override fun encrypt(plaintext: String): String = ChatMessageCrypto.encrypt(plaintext)
    override fun decrypt(value: String): String? = runCatching { ChatMessageCrypto.decrypt(value) }.getOrNull()
    override fun isEncrypted(value: String): Boolean = value.startsWith("enc:v1:")
}
