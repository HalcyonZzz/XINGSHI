package com.xingshi.ai.plugins.tts

internal object Mp3Duration {

    private val BITRATES_MPEG1_L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320
    )
    private val BITRATES_MPEG2_L3 = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160
    )
    private val SAMPLE_RATES_MPEG1 = intArrayOf(44100, 48000, 32000)
    private val SAMPLE_RATES_MPEG2 = intArrayOf(22050, 24000, 16000)
    private val SAMPLE_RATES_MPEG25 = intArrayOf(11025, 12000, 8000)

     
    fun parse(bytes: ByteArray): Long? {
        var offset = 0
        if (bytes.size > 10 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            offset = 10 + size
        }
        var totalSamples = 0L
        var sampleRate = 0
        var frames = 0
        while (offset + 4 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || (bytes[offset + 1].toInt() and 0xE0) != 0xE0) {
                offset += 1
                continue
            }
            val versionBits = (bytes[offset + 1].toInt() shr 3) and 0x03
            val layerBits = (bytes[offset + 1].toInt() shr 1) and 0x03
            if (layerBits != 0x01) { offset += 1; continue }
            val bitrateIndex = (bytes[offset + 2].toInt() shr 4) and 0x0F
            val sampleIndex = (bytes[offset + 2].toInt() shr 2) and 0x03
            val padding = (bytes[offset + 2].toInt() shr 1) and 0x01
            if (bitrateIndex == 0 || bitrateIndex == 0x0F || sampleIndex == 0x03) { offset += 1; continue }
            val mpeg1 = versionBits == 0x03
            val sampleTable = when (versionBits) {
                0x03 -> SAMPLE_RATES_MPEG1
                0x02 -> SAMPLE_RATES_MPEG2
                else -> SAMPLE_RATES_MPEG25
            }
            val bitrate = (if (mpeg1) BITRATES_MPEG1_L3 else BITRATES_MPEG2_L3)[bitrateIndex]
            val rate = sampleTable[sampleIndex]
            val samplesPerFrame = if (mpeg1) 1152 else 576
            val frameLength = (if (mpeg1) 144 else 72) * bitrate * 1000 / rate + padding
            if (frameLength < 4) { offset += 1; continue }
            totalSamples += samplesPerFrame
            sampleRate = rate
            frames += 1
            offset += frameLength
        }
        if (frames == 0 || sampleRate == 0) return null
        return totalSamples * 1000L / sampleRate
    }
}
