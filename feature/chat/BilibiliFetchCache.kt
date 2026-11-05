package com.xingshi.ai.feature.chat.ui.viewmodel

import com.xingshi.ai.common.SecureLog
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object BilibiliFetchCache {
    private const val FILE_NAME = "fetch.json"

    private const val MAX_SIDECAR_BYTES = 512L * 1024

    private const val TAG = "BilibiliFetchCache"

    fun write(dir: File, requestUrl: String, item: PreparedAttachment) {
        val video = File(item.videoPath)
        val payload = buildJsonObject {
            put("path", item.path)
            put("video", video.name)
            put("length", video.length())
            put("fileName", item.fileName)
            put("mimeType", item.mimeType)
            put("text", item.text)
            put("format", item.format)
            put("request", requestUrl)
            put("sourceUrl", item.sourceUrl)
            put("linkText", item.linkText)
        }
        val text = payload.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SIDECAR_BYTES) {
            SecureLog.e(TAG, "bilibili fetch cache sidecar too large, skip")
            return
        }
        rethrowing("write") {
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun read(dir: File, requestUrl: String? = null): PreparedAttachment? = rethrowing("read") {
        val sidecar = File(dir, FILE_NAME)
        if (!sidecar.isFile || sidecar.length() > MAX_SIDECAR_BYTES) return@rethrowing null
        val payload = Json.parseToJsonElement(sidecar.readText()).jsonObject
        val video = File(dir, payload.text("video") ?: return@rethrowing null).canonicalFile
        if (video.parentFile != dir.canonicalFile) return@rethrowing null
        if (!video.isFile || video.length() <= 0L) return@rethrowing null
        val expected = payload.text("length")?.toLongOrNull()
        if (expected == null || video.length() != expected) return@rethrowing null
        if (requestUrl != null && payload.text("request") != requestUrl) return@rethrowing null
        PreparedAttachment(
            kind = DraftAttachmentKind.BILIBILI,
            path = payload.text("path") ?: "",
            fileName = payload.text("fileName") ?: video.name,
            mimeType = payload.text("mimeType") ?: "video/mp4",
            text = payload.text("text") ?: "",
            format = payload.text("format") ?: "",
            sourceUrl = payload.text("sourceUrl") ?: "",
            linkText = payload.text("linkText") ?: "",
            videoPath = video.absolutePath
        )
    }

    private inline fun <T> rethrowing(what: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath || t is LinkageError) throw t
        SecureLog.e(TAG, "bilibili fetch cache $what failed", t)
        null
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
