package com.xingshi.ai.common

object TimeoutBudgets {
    const val BAYESIAN_CLASSIFY_MS = 30L
    const val AC_SCAN_MS = 10L

    const val CONTENT_FILTER_MS = 3_000L
    const val SAFETY_CLASSIFY_MS = 30_000L
    const val PIPELINE_EXECUTE_MS = 8_000L

    const val WECHAT_POLL_TIMEOUT_MS = 15_000L
    const val BROADCAST_GOASYNC_MS = 9_500L

    const val ROOM_WRITE_MS = 5_000L
    const val ROOM_QUERY_MS = 3_000L

    const val LAZY_COLUMN_MAX_ITEMS = 200
    const val IMAGE_CACHE_MB = 128
    const val MEMORY_ALERT_RATIO = 0.85f
    const val MEMORY_RECOVER_RATIO = 0.60f
}
