package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import com.xingshi.ai.plugins.api.SpeechSegment
import com.xingshi.ai.plugins.api.SpeechSegmenter
import com.xingshi.ai.plugins.api.TtsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单次前台语音/视频通话的轮次控制（不依赖 Android，便于可控验证）。
 *
 * 顺序固定：监听分句 → 停止采集 → STT → 原对话入口 → 正式回复 → TTS → 播放 → 再监听。
 * 播放/等待期间不采集；每次有效识别只提交一次原对话入口；挂断先撤销资格再收尾。
 */
class VoiceCallController(
    val callId: String,
    val companionId: Long,
    private val segmenter: SpeechSegmenter?,
    private val recognize: suspend (SpeechSegment) -> SpeechRecognitionResult,
    private val synthesize: suspend (String) -> TtsResult,
    private val play: suspend (TtsResult.Success) -> Unit,
    private val submit: suspend (text: String) -> Unit,
    /** 视频通话单轮：抓帧 + STT/Vision 并行 + 一次汇合提交；返回本轮原话与观察。 */
    private val videoRound: (suspend (segment: SpeechSegment, submit: suspend (text: String, observation: String) -> Unit) -> VideoCallTurnCoordinator.Outcome)? = null,
    private val submitWithObservation: suspend (text: String, observation: String) -> Unit = { text, _ -> submit(text) },
    private val awaitReply: suspend (callId: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxTurns: Int = 20,
    private val maxCallMs: Long = 10 * 60 * 1000L,
    /** 正式回复音频已交付：绑定时长到同一条已存消息。 */
    private val onReplyAudio: suspend (durationMs: Long) -> Unit = {},
    /** 真实失败原因到达通话状态，而不是只保存在 controller 内部。 */
    private val onStageError: (String) -> Unit = {},
    private val onState: (State) -> Unit = {}
) {

    enum class State {
        PREPARING,
        LISTENING,
        RECOGNIZING,
        WAITING_REPLY,
        SYNTHESIZING,
        PLAYING,
        ENDED
    }

    enum class EndReason { HUNG_UP, COMPLETED, FAILED, INTERRUPTED }

    data class Record(
        val callId: String,
        val companionId: Long,
        val startedAt: Long,
        val endedAt: Long,
        val durationMs: Long,
        val reason: EndReason
    )

    @Volatile
    var state: State = State.PREPARING
        private set

    @Volatile
    private var ended = false

    var record: Record? = null
        private set

    private var lastError: String? = null

    fun lastError(): String? = lastError

    fun hangup(reason: EndReason = EndReason.HUNG_UP) {
        if (ended) return
        finish(reason)
        runCatching { segmenter?.cancel() }
    }

    suspend fun run() {
        val startedAt = now()
        if (segmenter == null) {
            lastError = "语音分句能力不可用"
            finish(EndReason.FAILED, startedAt)
            return
        }
        transition(State.LISTENING)
        try {
            var turns = 0
            while (!ended && turns < maxTurns) {
                if (now() - startedAt > maxCallMs) {
                    finish(EndReason.COMPLETED, startedAt)
                    return
                }
                val segment = withTimeoutOrNull(segmenterTimeoutMs) { segmenter.nextSegment() } ?: break
                if (ended) break

                transition(State.RECOGNIZING)
                val text: String
                if (ended) break
                if (videoRound != null) {
                    // 视频汇合提交也必须经过挂断撤销：识别/视觉回调内挂断后零提交。
                    when (val round = videoRound(segment) { t, observation ->
                        if (!ended) submitWithObservation(t, observation)
                    }) {
                        is VideoCallTurnCoordinator.Outcome.Merged -> text = round.text
                        VideoCallTurnCoordinator.Outcome.EmptySpeech -> {
                            transition(State.LISTENING)
                            continue
                        }
                        is VideoCallTurnCoordinator.Outcome.Failed -> {
                            lastError = round.reason
                            onStageError(round.reason)
                            transition(State.LISTENING)
                            continue
                        }
                    }
                } else {
                    var recognized = recognize(segment)
                    if (recognized is SpeechRecognitionResult.Failure) recognized = recognize(segment)
                    text = when (recognized) {
                        is SpeechRecognitionResult.Success -> recognized.text.trim()
                        is SpeechRecognitionResult.Failure -> {
                            lastError = recognized.reason
                            onStageError(recognized.reason)
                            transition(State.LISTENING)
                            continue
                        }
                        SpeechRecognitionResult.Cancelled -> break
                    }
                    if (ended) break
                    if (text.isBlank()) {
                        // 静音/空结果不建立对话请求
                        transition(State.LISTENING)
                        continue
                    }
                }

                if (ended) break
                transition(State.WAITING_REPLY)
                if (videoRound == null) submit(text)
                val reply = awaitReply(callId) ?: run {
                    lastError = "未取得正式回复"
                    transition(State.LISTENING)
                    continue
                }
                if (ended) break
                if (reply.isBlank()) {
                    transition(State.LISTENING)
                    continue
                }

                transition(State.SYNTHESIZING)
                var audio = synthesize(reply)
                if (audio is TtsResult.Failure) {
                    onStageError(audio.reason)
                    audio = synthesize(reply)
                }
                when (audio) {
                    is TtsResult.Success -> {
                        // 合成回调内挂断：不得开始播放。
                        if (ended) break
                        runCatching { onReplyAudio(audio.durationMs) }
                        transition(State.PLAYING)
                        play(audio)
                    }
                    is TtsResult.Failure -> {
                        lastError = audio.reason
                        onStageError(audio.reason)
                    }
                    TtsResult.Cancelled -> break
                }
                if (ended) break
                turns += 1
                transition(State.LISTENING)
            }
            if (!ended) finish(EndReason.COMPLETED, startedAt)
        } catch (e: CancellationException) {
            hangup(EndReason.INTERRUPTED)
            throw e
        } catch (e: Exception) {
            lastError = e.message
            hangup(EndReason.FAILED)
        }
    }

    private fun transition(next: State) {
        state = next
        onState(next)
    }

    @Synchronized
    private fun finish(reason: EndReason, startedAt: Long = recordStartHint()) {
        if (record != null) return
        val end = now()
        record = Record(
            callId = callId,
            companionId = companionId,
            startedAt = startedAt,
            endedAt = end,
            durationMs = (end - startedAt).coerceAtLeast(0),
            reason = reason
        )
        ended = true
        transition(State.ENDED)
    }

    private var startedAtHint: Long? = null

    private fun recordStartHint(): Long = startedAtHint ?: now()

    init {
        // 记录起点统一在 run() 里取一次；这里仅提供 finish 的兜底
        startedAtHint = now()
    }

    private companion object {
        const val segmenterTimeoutMs = 45_000L
    }
}
