package com.xingshi.ai.feature.chat

import com.xingshi.ai.plugins.api.SpeechRecognitionResult
import com.xingshi.ai.plugins.api.VisionAnalysisResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

 
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
