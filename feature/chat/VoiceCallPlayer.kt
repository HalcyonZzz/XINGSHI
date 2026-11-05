package com.xingshi.ai.feature.chat

import android.media.MediaPlayer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class VoiceCallPlayer {

    @Volatile
    private var player: MediaPlayer? = null

    suspend fun play(path: String) = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            fun finish(error: Exception? = null) {
                runCatching { mediaPlayer.release() }
                if (player === mediaPlayer) player = null
                if (continuation.isActive) {
                    if (error == null) continuation.resume(Unit)
                    else continuation.resumeWithException(error)
                }
            }
            mediaPlayer.setOnCompletionListener { finish() }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                finish(java.io.IOException("通话播放失败 ($what/$extra)"))
                true
            }
            continuation.invokeOnCancellation {
                runCatching { mediaPlayer.stop() }
                runCatching { mediaPlayer.release() }
                if (player === mediaPlayer) player = null
            }
            try {
                mediaPlayer.setDataSource(path)
                mediaPlayer.prepare()
                mediaPlayer.start()
            } catch (error: Exception) {
                finish(error)
            }
        }
    }

    fun release() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }
}
