package com.xingshi.ai.common

/**
 * 时滞预算常量 — 每个异步操作的期望和最大时滞。
 */
object TimeoutBudgets {
    // === Native C++ 操作 ===
    const val BAYESIAN_CLASSIFY_MS = 30L     // 贝叶斯分类
    const val AC_SCAN_MS = 10L               // AC关键词扫描

    // === 安全检测 ===
    const val CONTENT_FILTER_MS = 3_000L     // 内容过滤
    const val SAFETY_CLASSIFY_MS = 30_000L   // 安全分类
    const val PIPELINE_EXECUTE_MS = 8_000L   // 内容安全管道执行总预算

    // === 微信 ===
    const val WECHAT_POLL_TIMEOUT_MS = 15_000L // 长轮询超时（统一 Service 20s 与 Worker 15s 不一致）
    const val BROADCAST_GOASYNC_MS = 9_500L    // BroadcastReceiver goAsync() 10s 限制预留 500ms

    // === 数据库操作 ===
    const val ROOM_WRITE_MS = 5_000L         // Room写入
    const val ROOM_QUERY_MS = 3_000L         // Room查询

    // === 分岔点硬限制 ===
    const val LAZY_COLUMN_MAX_ITEMS = 200    // LazyColumn视口上限
    const val IMAGE_CACHE_MB = 128           // 图片缓存上限(MB)
    const val MEMORY_ALERT_RATIO = 0.85f     // 内存告警阈值
    const val MEMORY_RECOVER_RATIO = 0.60f   // 内存恢复阈值(滞环)
}
