package com.xingshi.ai.plugins.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.xingshi.ai.plugins.api.SpeechSegment
import com.xingshi.ai.plugins.api.SpeechSegmentConfig
import com.xingshi.ai.plugins.api.SpeechSegmenter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

 
internal class PcmSegmentAccumulator(private val config: SpeechSegmentConfig) {

    sealed interface Event {
        data object None : Event
        data class Completed(val pcm: ByteArray, val durationMs: Long) : Event
        data object TimedOut : Event
    }

    private val frameDurationMs = 20
    private val bytesPerFrame = config.sampleRate / 1000 * frameDurationMs * 2

    private var buffer = ByteArrayOutputStream()
    private var speechMs = 0
    private var silenceMs = 0
    private var elapsedMs = 0
    private var started = false

    fun feed(frame: ShortArray): Event {
        elapsedMs += frameDurationMs
        val rms = normalizedRms(frame)
        val voiced = rms >= config.energyThreshold

        if (!started) {
            if (!voiced) {
                return if (elapsedMs >= config.maxWaitMs) Event.TimedOut else Event.None
            }
            started = true
        }

        buffer.write(shortsToBytes(frame))
        speechMs += frameDurationMs
        if (voiced) {
            silenceMs = 0
        } else {
            silenceMs += frameDurationMs
        }

        val longEnough = speechMs >= config.minSegmentMs
        val silenceFinished = longEnough && silenceMs >= config.silenceHoldMs
        val tooLong = speechMs >= config.maxSegmentMs
        if (!silenceFinished && !tooLong) return Event.None

        val pcm = buffer.toByteArray()
        val duration = speechMs.toLong()
        reset()
        return if (duration >= config.minSegmentMs) {
            Event.Completed(pcm, duration)
        } else {
            
            Event.None
        }
    }

    private fun reset() {
        buffer = ByteArrayOutputStream()
        speechMs = 0
        silenceMs = 0
        elapsedMs = 0
        started = false
    }

    private fun normalizedRms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (sample in frame) {
            val value = sample / 32768.0
            sum += value * value
        }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun shortsToBytes(frame: ShortArray): ByteArray {
        val out = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val v = frame[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

internal object WavWriter {
    fun write(file: File, pcm: ByteArray, sampleRate: Int) {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val dataSize = pcm.size
        val header = ByteArray(44)
        fun putAscii(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, channels * bits / 8)
        putShort(34, bits)
        putAscii(36, "data")
        putInt(40, dataSize)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}

internal class AudioRecordSegmenter(
    private val config: SpeechSegmentConfig,
    private val workDir: File
) : SpeechSegmenter {

    @Volatile
    private var cancelled = false
    private var record: AudioRecord? = null

    override suspend fun nextSegment(): SpeechSegment? = withContext(Dispatchers.IO) {
        val accumulator = PcmSegmentAccumulator(config)
        val frameSamples = config.sampleRate / 1000 * 20
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSamples * 2 * 4)
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        }.getOrNull() ?: return@withContext null
        record = audioRecord
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            record = null
            return@withContext null
        }
        try {
            audioRecord.startRecording()
            val frame = ShortArray(frameSamples)
            while (!cancelled) {
                currentCoroutineContext().ensureActive()
                val read = audioRecord.read(frame, 0, frame.size)
                if (read <= 0) continue
                val actual = if (read == frame.size) frame else frame.copyOf(read)
                when (val event = accumulator.feed(actual)) {
                    is PcmSegmentAccumulator.Event.Completed -> {
                        val file = File(workDir, "call_segment_${System.currentTimeMillis()}.wav")
                        WavWriter.write(file, event.pcm, config.sampleRate)
                        return@withContext SpeechSegment(file.absolutePath, event.durationMs)
                    }
                    PcmSegmentAccumulator.Event.TimedOut -> return@withContext null
                    PcmSegmentAccumulator.Event.None -> Unit
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
            audioRecord.release()
            record = null
        }
    }

    override fun cancel() {
        cancelled = true
        runCatching { record?.stop() }
    }

    override fun release() {
        cancel()
        runCatching { record?.release() }
        record = null
    }
}
