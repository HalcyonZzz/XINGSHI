package com.xingshi.ai.common

/**
 * AI 伴侣的角色类型。
 *
 * 区分女性/男性两种基础角色，用于 UI 的角色选择与展示差异。
 */
enum class CompanionRole {
    GIRLFRIEND,
    BOYFRIEND;

    companion object {
        fun fromName(name: String?): CompanionRole = when (name?.uppercase()) {
            "BOYFRIEND" -> BOYFRIEND
            else -> GIRLFRIEND
        }
    }
}
