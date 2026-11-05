package com.xingshi.ai.common

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI

object PackageIcons {

    const val FILE_NAME = "icon.png"
    const val SIZE_PX = 256
    const val MAX_BYTES = 256 * 1024

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)

    fun isPackageFile(declared: String): Boolean = declared.trim() == FILE_NAME

    fun resolve(directory: File, declared: String): File? {
        if (!isPackageFile(declared)) return null
        return try {
            val root = directory.canonicalFile
            if (!root.isDirectory) return null
            val file = File(root, FILE_NAME).canonicalFile
            if (file.parentFile != root || !file.isFile) return null
            file
        } catch (e: Exception) {
            null
        }
    }

    fun readFile(file: File): ByteArray? = try {
        if (!file.isFile) null else file.inputStream().use { readBounded(it) }
    } catch (e: Exception) {
        null
    }

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray? {
        if (limit <= 0) return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: Exception) {
                return null
            }
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun valid(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size !in 1..MAX_BYTES || bytes.size < 33) return false
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) return false
        }
        if (intAt(bytes, 8) != 13) return false
        for (index in IHDR.indices) {
            if (bytes[12 + index] != IHDR[index]) return false
        }
        if (intAt(bytes, 16) != SIZE_PX || intAt(bytes, 20) != SIZE_PX) return false
        val bitDepth = bytes[24].toInt() and 0xFF
        val colorType = bytes[25].toInt() and 0xFF
        if (bitDepth !in setOf(1, 2, 4, 8, 16)) return false
        if (colorType !in setOf(0, 2, 3, 4, 6)) return false
        return true
    }

    fun isHttpsUrl(url: String): Boolean {
        val value = url.trim()
        if (value.isEmpty()) return false
        return try {
            val parsed = URI(value)
            parsed.scheme?.equals("https", ignoreCase = true) == true &&
                !parsed.host.isNullOrBlank() &&
                parsed.userInfo.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    fun declaredIconName(value: String): String {
        val name = value.trim()
        if (name == FILE_NAME) return name
        if (name.isEmpty()) return ""
        if (name.contains("://") || name.contains('/') || name.contains('\\')) return ""
        if (name.startsWith("/") || name.contains("..") || name.contains(':')) return ""
        return name
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
