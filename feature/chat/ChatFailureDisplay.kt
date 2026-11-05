package com.xingshi.ai.feature.chat

import com.xingshi.ai.network.BackendChatFailure

object ChatFailureDisplay {
    enum class Target { PROFILE, MAIN_MODEL, RETRY, HINT }

    data class Entry(val labelKey: String, val target: Target)

    data class Presentation(
        val reasonKey: String,
        val entry: Entry?,
        val code: String,
        val category: String,
        val retryable: Boolean,
        val nextAction: String,
        val detail: String,
    )

    fun present(failure: BackendChatFailure): Presentation {
        val nextAction = effectiveNextAction(failure)
        return Presentation(
            reasonKey = reasonKey(failure),
            entry = entry(nextAction),
            code = failure.code,
            category = failure.category,
            retryable = failure.retryable,
            nextAction = nextAction,
            detail = failure.reason,
        )
    }

    fun reasonKey(failure: BackendChatFailure?): String {
        failure ?: return REASON_OTHER
        CODE_KEYS[failure.code]?.let { return it }
        CATEGORY_KEYS[failure.category]?.let { return it }
        return if (failure.retryable) REASON_RETRYABLE else REASON_TERMINAL
    }

    fun reasonKeyForCode(code: String): String? = CODE_KEYS[code]

    fun reasonKeyForCategory(category: String): String? = CATEGORY_KEYS[category]

    fun nextActionForCode(code: String): String = CODE_ACTIONS[code] ?: ""

    fun entry(nextAction: String): Entry? = ENTRIES[nextAction]

    private fun effectiveNextAction(failure: BackendChatFailure): String {
        if (failure.nextAction.isNotBlank() && failure.nextAction != NONE) return failure.nextAction
        val derived = nextActionForCode(failure.code)
        return when {
            derived.isNotBlank() -> derived
            failure.retryable -> RETRY_LATER
            else -> NONE
        }
    }

    const val NONE = "NONE"
    const val RETRY_LATER = "RETRY_LATER"
    const val REASON_OTHER = "ui_chat_failure_reason_other"
    const val REASON_RETRYABLE = "ui_chat_failure_reason_retryable"
    const val REASON_TERMINAL = "ui_chat_failure_reason_terminal"

    private const val REASON_PREFIX = "ui_chat_failure_reason_"

    private val CODE_KEYS: Map<String, String> = listOf(
        "PROFILE_NICKNAME_REQUIRED",
        "PROMPT_NOT_CONFIGURED",
        "MAIN_MODEL_NOT_CONFIGURED",
        "MAIN_MODEL_DISABLED",
        "AI_PROFILE_NOT_FOUND",
        "AI_PROFILE_INCOMPATIBLE",
        "AI_CREDENTIAL_INVALID",
        "AI_CREDENTIAL_DECRYPT_FAILED",
        "CREDENTIAL_CONFIG_MISMATCH",
        "PROVIDER_AUTHENTICATION_FAILED",
        "PROVIDER_MODEL_NOT_FOUND",
        "PROVIDER_INVALID_REQUEST",
        "PROVIDER_INVALID_RESPONSE",
        "PROVIDER_NOT_SENT",
        "PROVIDER_TIMEOUT",
        "PROVIDER_RATE_LIMITED",
        "PROVIDER_UPSTREAM_5XX",
        "GENERATION_RETRY_EXHAUSTED",
        "SUBMISSION_UNKNOWN",
        "INDEX_PENDING",
        "INDEX_UNAVAILABLE",
        "RECALL_UNAVAILABLE",
        "CONTEXT_RETRY_EXHAUSTED",
        "PROMPT_BUDGET_EXCEEDED",
        "DEADLINE_EXCEEDED",
        "CANCELLED",
    ).associate { it to REASON_PREFIX + it.lowercase() }

    private val CATEGORY_KEYS: Map<String, String> = listOf(
        "PROFILE",
        "MAIN_MODEL",
        "CREDENTIAL",
        "MEMORY",
        "INPUT",
        "PROVIDER",
        "SUBMISSION",
        "OTHER",
        "INTERNAL",
    ).associate { it to "ui_chat_failure_category_" + it.lowercase() }

    private val CODE_ACTIONS: Map<String, String> = mapOf(
        "PROFILE_NICKNAME_REQUIRED" to "COMPLETE_PROFILE",
        "PROMPT_NOT_CONFIGURED" to "CONFIGURE_PROFILE",
        "MAIN_MODEL_NOT_CONFIGURED" to "CONFIGURE_MAIN_MODEL",
        "MAIN_MODEL_DISABLED" to "ENABLE_MAIN_MODEL",
        "AI_PROFILE_NOT_FOUND" to "REPAIR_MODEL_CONFIG",
        "AI_PROFILE_INCOMPATIBLE" to "REPAIR_MODEL_CONFIG",
        "PROVIDER_MODEL_NOT_FOUND" to "REPAIR_MODEL_CONFIG",
        "AI_CREDENTIAL_INVALID" to "REPAIR_CREDENTIAL",
        "AI_CREDENTIAL_DECRYPT_FAILED" to "REPAIR_CREDENTIAL",
        "CREDENTIAL_CONFIG_MISMATCH" to "REPAIR_CREDENTIAL",
        "PROVIDER_AUTHENTICATION_FAILED" to "REPAIR_CREDENTIAL",
    )

    private val ENTRIES: Map<String, Entry> = mapOf(
        "COMPLETE_PROFILE" to Entry("ui_chat_failure_entry_complete_profile", Target.PROFILE),
        "CONFIGURE_PROFILE" to Entry("ui_chat_failure_entry_configure_profile", Target.PROFILE),
        "CONFIGURE_MAIN_MODEL" to Entry("ui_chat_failure_entry_configure_main_model", Target.MAIN_MODEL),
        "ENABLE_MAIN_MODEL" to Entry("ui_chat_failure_entry_enable_main_model", Target.MAIN_MODEL),
        "REPAIR_MODEL_CONFIG" to Entry("ui_chat_failure_entry_repair_model_config", Target.MAIN_MODEL),
        "REPAIR_CREDENTIAL" to Entry("ui_chat_failure_entry_repair_credential", Target.MAIN_MODEL),
        "RETRY_LATER" to Entry("ui_chat_failure_entry_retry_later", Target.RETRY),
        "RETRY_EXPLICITLY" to Entry("ui_chat_failure_entry_retry_explicitly", Target.RETRY),
        "SHORTEN_INPUT" to Entry("ui_chat_failure_entry_shorten_input", Target.HINT),
        "WAIT" to Entry("ui_chat_failure_entry_wait", Target.HINT),
        "CONTACT_SUPPORT" to Entry("ui_chat_failure_entry_contact_support", Target.HINT),
    )
}
