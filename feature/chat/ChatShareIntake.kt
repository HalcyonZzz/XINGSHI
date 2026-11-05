package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.BilibiliFailureCode
import com.xingshi.ai.plugins.api.BilibiliTargetResult
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BilibiliShareIntake {

    data class Share(val id: String, val text: String, val title: String)

    private val mutablePending = MutableStateFlow<Share?>(null)
    val pending: StateFlow<Share?> = mutablePending.asStateFlow()

    @Volatile
    private var handlingId: String? = null

    fun offer(text: String, title: String = "") {
        if (text.isBlank()) return
        mutablePending.value = Share(UUID.randomUUID().toString(), text, title)
    }

    fun isHandling(): Boolean = handlingId != null

    fun claim(share: Share): Boolean = synchronized(this) {
        if (handlingId == share.id) return false
        if (mutablePending.value?.id != share.id) return false
        handlingId = share.id
        true
    }

    fun finish(share: Share) {
        synchronized(this) {
            if (mutablePending.value?.id == share.id) mutablePending.value = null
            if (handlingId == share.id) handlingId = null
        }
    }

    fun clear() {
        synchronized(this) {
            mutablePending.value = null
            handlingId = null
        }
    }
}

sealed interface SharedTextOutcome {
    data class Bilibili(
        val sourceUrl: String,
        val bv: String,
        val page: Int,
        val cid: Long,
        val title: String?
    ) : SharedTextOutcome

    data class PlainText(val text: String) : SharedTextOutcome

    data class Prompt(val key: String) : SharedTextOutcome
}

fun planSharedText(
    result: BilibiliTargetResult,
    sharedText: String,
    shareTitle: String? = null
): SharedTextOutcome = when (result) {
    is BilibiliTargetResult.Resolved -> SharedTextOutcome.Bilibili(
        sourceUrl = result.target.sourceUrl,
        bv = result.target.bv,
        page = result.target.page,
        cid = result.target.cid,
        title = result.title?.takeIf { it.isNotBlank() } ?: shareTitle?.takeIf { it.isNotBlank() }
    )
    BilibiliTargetResult.NotBilibili -> SharedTextOutcome.PlainText(sharedText)
    BilibiliTargetResult.Ambiguous -> SharedTextOutcome.Prompt("ui_bilibili_ambiguous")
    is BilibiliTargetResult.Failed -> SharedTextOutcome.Prompt(
        if (result.code == BilibiliFailureCode.AMBIGUOUS) "ui_bilibili_ambiguous" else "ui_bilibili_failed"
    )
}

sealed interface ShareCompanionRoute {
    data class Choose(val companionIds: List<Long>) : ShareCompanionRoute
    object NoCompanion : ShareCompanionRoute
}

fun planShareCompanionRoute(availableCompanionIds: List<Long>): ShareCompanionRoute =
    if (availableCompanionIds.isEmpty()) {
        ShareCompanionRoute.NoCompanion
    } else {
        ShareCompanionRoute.Choose(availableCompanionIds)
    }
