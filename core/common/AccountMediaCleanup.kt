package com.xingshi.ai.common

import java.io.File

 
object AccountMediaCleanup {
    fun remove(roots: List<File>, candidates: Collection<String>, retained: Collection<String>) {
        fun canonical(value: String): File? = runCatching {
            val file = if (value.startsWith("file://")) File(java.net.URI(value)) else File(value)
            file.takeIf { it.isAbsolute }?.canonicalFile
        }.getOrNull()
        val directories = roots.map { it.canonicalFile }
        val shared = retained.mapNotNull(::canonical).toSet()
        for (file in candidates.mapNotNull(::canonical).distinct()) {
            if (file in shared || directories.none { file.toPath().startsWith(it.toPath()) && file != it }) continue
            check(!file.exists() || (file.isFile && file.delete())) { "助手专属媒体清理尚未完成" }
        }
    }
}
