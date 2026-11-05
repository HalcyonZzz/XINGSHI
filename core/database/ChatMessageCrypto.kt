package com.xingshi.ai.database.repository

import com.xingshi.ai.database.model.ChatMessage
import com.xingshi.ai.database.model.GroupMessage
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChatMessageCrypto {
    private const val KEYSTORE_ALIAS = "xingshi_chat_message_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val PREFIX = "enc:v1:"

    private val keyStoreKey: SecretKey? by lazy {
        runCatching { getOrCreateAndroidKeyStoreKey() }.getOrNull()
    }

    private val encryptionKey: SecretKey
        get() = keyStoreKey ?: error("AndroidKeyStore chat message key unavailable")

    private val legacyFallbackKey: SecretKey by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("xingshi-chat-message-storage-v1".toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private val decryptionKeys: List<SecretKey>
        get() = listOfNotNull(keyStoreKey) + legacyFallbackKey

    fun encryptForStorage(message: ChatMessage): ChatMessage {
        return try {
            message.copy(
                content = encrypt(message.content),
                searchContent = message.searchContent.ifBlank { message.content },
                linkString = encrypt(message.linkString)
            )
        } catch (e: Exception) {
            android.util.Log.e("ChatMessageCrypto", "encryptForStorage failed: ${e.message}", e)
            throw e
        }
    }

    fun decryptFromStorage(message: ChatMessage): ChatMessage {
        return try {
            message.copy(
                content = decrypt(message.content),
                linkString = decrypt(message.linkString)
            )
        } catch (e: Exception) {
            message.copy(
                content = DECRYPT_FAILED_PLACEHOLDER,
                linkString = ""
            )
        }
    }

    fun encryptForStorage(message: GroupMessage): GroupMessage {
        return message.copy(
            content = encrypt(message.content),
            searchContent = message.searchContent.ifBlank { message.content },
            linkString = encrypt(message.linkString)
        )
    }

    fun decryptFromStorage(message: GroupMessage): GroupMessage {
        return try {
            message.copy(
                content = decrypt(message.content),
                linkString = decrypt(message.linkString)
            )
        } catch (e: Exception) {
            message.copy(
                content = DECRYPT_FAILED_PLACEHOLDER,
                linkString = ""
            )
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        if (isStoredCiphertext(plaintext)) return plaintext
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(value: String): String {
        if (value.isEmpty() || !value.startsWith(PREFIX)) return value
        val combined = runCatching {
            Base64.getDecoder().decode(value.removePrefix(PREFIX))
        }.getOrNull() ?: return value
        if (combined.size <= GCM_IV_LENGTH) return value
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        for (key in decryptionKeys) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
        throw javax.crypto.AEADBadTagException("ChatMessage decrypt failed: no key matched")
    }

    private fun isStoredCiphertext(value: String): Boolean {
        if (!value.startsWith(PREFIX)) return false
        return runCatching { decrypt(value) }.isSuccess
    }

    private fun getOrCreateAndroidKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    const val DECRYPT_FAILED_PLACEHOLDER = "[消息解密失败]"
}

@kotlin.jvm.JvmName("filterDecryptedChat")
fun List<com.xingshi.ai.database.model.ChatMessage>.filterDecrypted(): List<com.xingshi.ai.database.model.ChatMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }

@kotlin.jvm.JvmName("filterDecryptedGroup")
fun List<com.xingshi.ai.database.model.GroupMessage>.filterDecrypted(): List<com.xingshi.ai.database.model.GroupMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }
