package com.xingshi.ai.feature.chat.ui.viewmodel

import java.io.File

internal object BilibiliWorkDir {
    fun acquire(rootDir: File, localMessageId: Long, index: Int): File =
        File(rootDir, "m$localMessageId-i$index").apply { mkdirs() }

    fun ownerMessageId(dir: File): Long? = FIXED_NAME.matchEntire(dir.name)?.groupValues?.get(1)?.toLongOrNull()

    private val FIXED_NAME = Regex("^m(\\d+)-i\\d+$")

    fun reclaim(rootDir: File, videoPath: String) {
        temporaryDir(rootDir, videoPath)?.deleteRecursively()
    }

    private fun temporaryDir(rootDir: File, videoPath: String): File? = runCatching {
        val root = rootDir.canonicalFile
        val video = File(videoPath).canonicalFile
        video.parentFile?.takeIf { it.parentFile == root }
    }.getOrNull()
}
