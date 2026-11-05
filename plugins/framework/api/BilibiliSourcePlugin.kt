package com.xingshi.ai.plugins.api

interface BilibiliSourcePlugin {

    fun resolve(sharedText: String): BilibiliTargetResult

    suspend fun fetch(request: BilibiliFetchRequest, runtime: BilibiliFetchRuntime): BilibiliFetchResult
}

data class BilibiliTarget(
    val sourceUrl: String,
    val bv: String,
    val page: Int,
    val cid: Long
)

sealed class BilibiliTargetResult {
    data class Resolved(val target: BilibiliTarget, val title: String? = null) : BilibiliTargetResult()

    object NotBilibili : BilibiliTargetResult()

    object Ambiguous : BilibiliTargetResult()

    data class Failed(val code: BilibiliFailureCode) : BilibiliTargetResult()
}

data class BilibiliFetchRequest(
    val target: BilibiliTarget,
    val cacheDir: String
)

interface BilibiliFetchRuntime {
    fun isActive(): Boolean
}

sealed class BilibiliFetchResult {
    data class Ready(
        val sourceUrl: String,
        val linkText: String,
        val videoPath: String,
        val quality: String
    ) : BilibiliFetchResult()

    data class Failed(val code: BilibiliFailureCode, val detail: String? = null) : BilibiliFetchResult()

    object Cancelled : BilibiliFetchResult()
}

enum class BilibiliFailureCode {
    NOT_A_BILIBILI_LINK,
    AMBIGUOUS,
    PAGE_OUT_OF_RANGE,
    UNAVAILABLE,
    EXPAND_FAILED,
    DOWNLOAD_FAILED,
    INCOMPLETE,
    MATERIALS_FAILED,
    IO_ERROR
}
