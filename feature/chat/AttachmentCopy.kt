package com.xingshi.ai.feature.chat.ui.viewmodel

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

enum class AttachmentCopyResult { Copied, TooLarge, Failed }

object AttachmentCopy {
    const val MAX_IMAGE_DOCUMENT_BYTES: Long = 32L * 1024 * 1024
    const val MAX_VIDEO_BYTES: Long = 512L * 1024 * 1024

    private const val BUFFER_BYTES = 8192

    fun kindOf(mimeType: String, fileName: String): DraftAttachmentKind =
        AttachmentPreparation.kindOf(mimeType, fileName)

    fun limitBytes(kind: DraftAttachmentKind): Long = when (kind) {
        DraftAttachmentKind.VIDEO, DraftAttachmentKind.BILIBILI -> MAX_VIDEO_BYTES
        else -> MAX_IMAGE_DOCUMENT_BYTES
    }

    suspend fun copy(source: File, dest: File, kind: DraftAttachmentKind): AttachmentCopyResult =
        copy(source, dest, limitBytes(kind))

    suspend fun copy(source: File, dest: File, limitBytes: Long): AttachmentCopyResult =
        try {
            source.inputStream().use { input -> copy(input, dest, limitBytes) }
        } catch (e: CancellationException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            AttachmentCopyResult.Failed
        }

    suspend fun copy(input: InputStream, dest: File, limitBytes: Long): AttachmentCopyResult =
        coroutineScope {
            try {
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output ->
                    copyWhileActive(input, output, dest, limitBytes)?.let { return@coroutineScope it }
                }
                AttachmentCopyResult.Copied
            } catch (e: CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                dest.delete()
                AttachmentCopyResult.Failed
            }
        }

    private suspend fun CoroutineScope.copyWhileActive(
        input: InputStream,
        output: OutputStream,
        dest: File,
        limitBytes: Long
    ): AttachmentCopyResult? {
        val finished = AtomicBoolean(false)
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) {
                    Thread({ runCatching { input.close() } }, "attachment-copy-abort")
                        .apply { isDaemon = true }
                        .start()
                }
            }
        }
        try {
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = runInterruptible(Dispatchers.IO) { input.read(buffer) }
                if (read < 0) break
                total += read
                if (total > limitBytes) {
                    dest.delete()
                    finished.set(true)
                    return AttachmentCopyResult.TooLarge
                }
                output.write(buffer, 0, read)
            }
            currentCoroutineContext().ensureActive()
            finished.set(true)
            return null
        } finally {
            closer.cancel()
        }
    }
}
