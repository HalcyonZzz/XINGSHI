package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 视频通话单轮汇合：话段结束立即抓帧，STT 与 Vision 并行，两法结果都合法才提交一次。
 * 只描述本轮材料，不读“最近一张图/上一次识别”的全局缓存；空转写不建立对话责任。
 */
class VideoCallTurnCoordinator(
    private val recognize: suspend () -> SpeechRecognitionResult,
    private val analyze: suspend () -> VisionAnalysisResult,
    private val submit: suspend (text: String, observation: String) -> Unit
) {

    sealed interface Outcome {
        data class Merged(val text: String, val observation: String) : Outcome
        data object EmptySpeech : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun runRound(): Outcome = coroutineScope {
        // 同一轮材料内各失败阶段只重试原识别/原帧一次，不重录、不换帧。
        val stt = async {
            val first = recognize()
            if (first is SpeechRecognitionResult.Failure) recognize() else first
        }
        val vision = async {
            val first = analyze()
            if (first is VisionAnalysisResult.Failure) analyze() else first
        }
        val speech = stt.await()
        when (speech) {
            is SpeechRecognitionResult.Cancelled -> {
                vision.cancel()
                Outcome.Failed("识别已取消")
            }
            is SpeechRecognitionResult.Failure -> {
                vision.cancel()
                Outcome.Failed(speech.reason)
            }
            is SpeechRecognitionResult.Success -> {
                val text = speech.text.trim()
                if (text.isBlank()) {
                    // 空转写不提交，也不留下一条无话可答的视觉消息
                    vision.cancel()
                    Outcome.EmptySpeech
                } else {
                    when (val observation = vision.await()) {
                        is VisionAnalysisResult.Described -> {
                            val description = observation.description.trim()
                            if (description.isBlank()) {
                                Outcome.Failed("视觉描述为空")
                            } else {
                                submit(text, description)
                                Outcome.Merged(text, description)
                            }
                        }
                        is VisionAnalysisResult.Failure -> Outcome.Failed(observation.reason)
                        VisionAnalysisResult.Cancelled -> Outcome.Failed("视觉识别已取消")
                    }
                }
            }
        }
    }
}
