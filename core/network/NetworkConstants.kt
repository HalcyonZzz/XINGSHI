package com.xingshi.ai.network

/**
 * 集中管理网络层常量，避免超时、连接池、URL 路径等数值散落在各个文件中。
 */
object NetworkConstants {

    // ── QQ Bot 开放平台扫码绑定（Hermes 协议） ──
    const val QQ_BOT_LITE_QR_CONNECT_URL = "https://q.qq.com/qqbot/openclaw/connect.html"

    // ── QQ Bot WebSocket 超时（秒） ──
    const val QQ_BOT_WS_CONNECT_TIMEOUT_SECONDS = 15
    const val QQ_BOT_WS_READ_TIMEOUT_SECONDS = 0
    const val QQ_BOT_WS_WRITE_TIMEOUT_SECONDS = 15
    const val QQ_BOT_WS_PING_INTERVAL_SECONDS = 30
}
