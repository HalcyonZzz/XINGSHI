package com.xingshi.ai.common

/**
 * 聊天业务层常量集中管理。
 *
 * 包括：
 * - 聊天消息分页参数
 * - 消息通道背压参数
 * - 群聊分页参数
 * - 微信/QQ 通道轮询参数
 *
 * 这些数值被 ChatViewModel / GroupChatViewModel 共用，
 * 集中后便于统一调整并保持多模块一致。
 */
object ChatConstants {

    // ── 聊天消息分页 ──
    /** 初始进入聊天时加载的消息条数 */
    const val CHAT_PAGE_SIZE = 50

    /** 点击"加载更多"时追加的历史消息条数 */
    const val CHAT_LOAD_MORE_SIZE = 30

    // ── 消息队列 ──
    /** 发送消息 Channel 容量上限，满时拒绝新消息（不排队、不阻塞） */
    const val MESSAGE_QUEUE_CAPACITY = 100

    /** 消费器/观察器异常后最多自动重启次数，防止无限重试雪崩 */
    const val MAX_AUTO_RESTARTS = 5

    /** UI 层内存中保留的最大消息条数 */
    const val MAX_UI_MESSAGES = 200

    // ── 群聊 ──
    /** 群聊历史消息默认加载条数 */
    const val GROUP_CHAT_MESSAGE_LIMIT = 50

    // ── 日志/调试截断 ──
    /** 普通日志中消息内容预览最大长度 */
    const val LOG_MESSAGE_PREVIEW_MAX_LENGTH = 30

    /** 长日志中消息内容预览最大长度 */
    const val LOG_LONG_MESSAGE_PREVIEW_MAX_LENGTH = 50

    /** 错误日志中异常消息预览最大长度 */
    const val LOG_ERROR_PREVIEW_MAX_LENGTH = 80

    /** 安全分类错误提示中异常消息预览最大长度 */
    const val SAFETY_ERROR_PREVIEW_MAX_LENGTH = 50

    /** 加载更多历史时的延迟，让加载指示器可见 */
    const val LOAD_MORE_HISTORY_DELAY_MS = 200L

    /** 消息观察器崩溃后重启前的退避延迟 */
    const val OBSERVE_MESSAGES_RESTART_DELAY_MS = 500L

    /** 设置页恢复配置状态前的延迟，避免主线程阻塞 */
    const val SETTINGS_RESTORE_DELAY_MS = 500L

    // ── QQ Bot WebSocket ──
    /** QQ Bot WebSocket 心跳间隔系数（相对服务端 interval 的比例） */
    const val QQ_BOT_HEARTBEAT_FACTOR = 0.8

    /** QQ Bot WebSocket 心跳最小间隔（毫秒） */
    const val QQ_BOT_HEARTBEAT_MIN_MS = 5000L

    /** QQ Bot WebSocket 重连退避基数（毫秒） */
    const val QQ_BOT_RECONNECT_BACKOFF_BASE_MS = 2000L

    /** QQ Bot WebSocket 最大重连退避（毫秒） */
    const val QQ_BOT_RECONNECT_MAX_DELAY_MS = 30000L

    // ── WeChat Polling Service ──
    /** 微信轮询消息长轮询超时（毫秒） */
    const val WECHAT_SERVICE_POLL_TIMEOUT_MS = 20000L

    /** 微信轮询超时后的重试延迟（毫秒） */
    const val WECHAT_SERVICE_TIMEOUT_RETRY_DELAY_MS = 3000L

    /** 微信轮询连接错误后的重试延迟（毫秒） */
    const val WECHAT_SERVICE_CONNECTION_RETRY_DELAY_MS = 5000L

    /** 微信轮询通用错误后的重试延迟（毫秒） */
    const val WECHAT_SERVICE_ERROR_RETRY_DELAY_MS = 5000L
}
