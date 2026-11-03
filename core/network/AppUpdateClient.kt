package com.xingshi.ai.network

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

 
data class AppUpdateManifest(
    val applicationId: String = "",
    val versionCode: Long = 0L,
    val versionName: String = "",
    val minSdk: Int = 0,
    val apkUrl: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val releaseNotes: String = ""
)

sealed interface AppUpdateFetchResult {
    data class Success(val manifest: AppUpdateManifest) : AppUpdateFetchResult

     
    data class Failure(val code: String) : AppUpdateFetchResult
}

 
object AppUpdateClient {

    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient()

    suspend fun fetchManifest(url: String): AppUpdateFetchResult = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).build())
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            abortsOnCancel(call, cancelled) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@abortsOnCancel AppUpdateFetchResult.Failure("http_${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
                    val jsonObject = runCatching { json.parseToJsonElement(text).jsonObject }
                        .getOrElse { return@abortsOnCancel AppUpdateFetchResult.Failure("catalog_invalid") }
                    val manifest = jsonObject.toManifest()
                    if (manifest.versionCode <= 0L || manifest.apkUrl.isBlank()) {
                        return@abortsOnCancel AppUpdateFetchResult.Failure("catalog_incomplete")
                    }
                    AppUpdateFetchResult.Success(manifest)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled.get()) throw kotlinx.coroutines.CancellationException()
            AppUpdateFetchResult.Failure("network")
        }
    }

     
    suspend fun download(
        url: String,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).build())
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            abortsOnCancel(call, cancelled) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw UpdateDownloadException("http_${response.code}")
                    val body = response.body ?: throw UpdateDownloadException("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: 0L
                    body.byteStream().use { input ->
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.delete()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                received += read
                                if (received > MAX_APK_BYTES) throw UpdateDownloadException("too_large")
                                output.write(buffer, 0, read)
                                onProgress(received, total)
                            }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UpdateDownloadException) {
            Result.failure(e)
        } catch (e: Exception) {
            if (cancelled.get()) throw kotlinx.coroutines.CancellationException()
            Result.failure(UpdateDownloadException("network"))
        } finally {
            runCatching { call.cancel() }
        }
    }

     
    private suspend fun <T> abortsOnCancel(
        call: okhttp3.Call,
        cancelled: java.util.concurrent.atomic.AtomicBoolean,
        block: suspend () -> T
    ): T = coroutineScope {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                
                if (!finished.get()) {
                    cancelled.set(true)
                    runCatching { call.cancel() }
                }
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            guard.cancel()
        }
    }

    class UpdateDownloadException(val code: String) : Exception(code)

    private fun JsonObject.toManifest(): AppUpdateManifest {
        fun text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun number(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
        return AppUpdateManifest(
            applicationId = text("applicationId"),
            versionCode = number("versionCode"),
            versionName = text("versionName"),
            minSdk = number("minSdk").toInt(),
            apkUrl = text("apkUrl"),
            sizeBytes = number("sizeBytes"),
            sha256 = text("sha256"),
            releaseNotes = text("releaseNotes")
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
