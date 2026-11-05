package com.xingshi.ai.common.embedding

import com.xingshi.ai.common.ContentFilter
import kotlin.math.sqrt

class VectorLibrary private constructor(
    private val dim: Int,
    private val centroids: Map<ContentFilter.ViolationLevel, FloatArray>,
    private val samples: List<Sample>
) {
    class Sample(
        val word: String,
        val level: ContentFilter.ViolationLevel,
        val vec: FloatArray
    )

    companion object {
        const val HIT_THRESHOLD = 0.60f

        const val GRAY_LOWER = 0.35f
    }

    data class MatchResult(
        val level: ContentFilter.ViolationLevel,
        val score: Float,
        val isGrayZone: Boolean,
        val matchedSample: String = ""
    )

    fun check(text: String): MatchResult? {
        val vec = textToVector(text)
        var nonZero = 0
        for (v in vec) if (v != 0f) nonZero++
        if (nonZero < 2) return null

        var bestLevel: ContentFilter.ViolationLevel? = null
        var bestScore = 0f
        var bestWord = ""
        for (s in samples) {
            val dot = cosine(vec, s.vec)
            if (dot > bestScore) {
                bestScore = dot
                bestLevel = s.level
                bestWord = s.word
            }
        }

        if (bestLevel == null) {
            for ((level, centroid) in centroids) {
                var dot = 0f
                for (i in vec.indices) { dot += vec[i] * centroid[i] }
                if (dot > bestScore) {
                    bestScore = dot
                    bestLevel = level
                }
            }
        }

        val level = bestLevel ?: return null
        if (bestScore < GRAY_LOWER) return null
        return MatchResult(
            level = level,
            score = bestScore,
            isGrayZone = bestScore < HIT_THRESHOLD,
            matchedSample = bestWord
        )
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-9f || nb <= 1e-9f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun textToVector(text: String): FloatArray {
        val vec = FloatArray(dim)
        val chars = text.toCharArray()
        var count = 0
        for (n in 2..3) {
            for (i in 0..chars.size - n) {
                val gram = String(chars, i, n)
                val gv = gramVector(gram)
                for (j in 0 until dim) { vec[j] += gv[j] }
                count++
            }
        }
        if (count > 0) {
            var l2 = 0f
            for (v in vec) l2 += v * v
            l2 = sqrt(l2)
            if (l2 > 1e-9f) {
                for (i in vec.indices) vec[i] /= l2
            }
        }
        return vec
    }

    private fun gramVector(gram: String): FloatArray {
        val vec = FloatArray(dim)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(gram.toByteArray())
        val used = mutableSetOf<Int>()
        for (i in 0 until 8) {
            var pos = ((hash[i * 2].toInt() and 0xFF) shl 8 or (hash[i * 2 + 1].toInt() and 0xFF)) % dim
            val sign = if ((hash[i * 2 + 16].toInt() and 1) != 0) 1f else -1f
            var attempts = 0
            while (pos in used && attempts < 20) { pos = (pos + 7) % dim; attempts++ }
            used.add(pos)
            vec[pos] = sign
        }
        return vec
    }

    class Loader {
        fun load(bytes: ByteArray): VectorLibrary? {
            try {
                var pos = 0
                fun rShort(): Int = ((bytes[pos++].toInt() and 0xFF) or ((bytes[pos++].toInt() and 0xFF) shl 8))
                fun rInt(): Int = ((bytes[pos++].toInt() and 0xFF) or ((bytes[pos++].toInt() and 0xFF) shl 8) or
                        ((bytes[pos++].toInt() and 0xFF) shl 16) or ((bytes[pos++].toInt() and 0xFF) shl 24))
                fun rFloat(): Float = java.lang.Float.intBitsToFloat(rInt())

                if (bytes.size < 12) return null
                val magic = String(bytes, pos, 4, Charsets.US_ASCII); pos += 4
                if (magic != "VLB4") return null
                val dim = rShort()
                val numWords = rInt()
                val numClasses = rShort()
                if (dim <= 0 || dim > 2048 || numWords < 0 || numWords > 100_000) return null

                val levelOrder = arrayOf(
                    ContentFilter.ViolationLevel.LOW, ContentFilter.ViolationLevel.MEDIUM,
                    ContentFilter.ViolationLevel.HIGH, ContentFilter.ViolationLevel.SEVERE,
                    ContentFilter.ViolationLevel.CRITICAL, ContentFilter.ViolationLevel.EXTREME
                )

                val samples = ArrayList<Sample>(numWords)
                for (i in 0 until numWords) {
                    val wlen = bytes[pos++].toInt() and 0xFF
                    if (wlen <= 0 || pos + wlen > bytes.size) return null
                    val word = String(bytes, pos, wlen, Charsets.UTF_8); pos += wlen
                    val levelId = bytes[pos++].toInt() and 0xFF
                    val vec = FloatArray(dim)
                    for (j in 0 until dim) { vec[j] = bytes[pos++].toFloat() }
                    normalizeInPlace(vec)
                    val lv = levelOrder.getOrNull(levelId) ?: continue
                    samples.add(Sample(word, lv, vec))
                }

                val centroids = mutableMapOf<ContentFilter.ViolationLevel, FloatArray>()
                for (ci in 0 until numClasses) {
                    val centroid = FloatArray(dim)
                    for (i in 0 until dim) { centroid[i] = rFloat() }
                    normalizeInPlace(centroid)
                    levelOrder.getOrNull(ci)?.let { centroids[it] = centroid }
                }

                return VectorLibrary(dim, centroids, samples)
            } catch (e: Exception) {
                return null
            }
        }

        private fun normalizeInPlace(vec: FloatArray) {
            var l2 = 0f
            for (v in vec) l2 += v * v
            l2 = sqrt(l2)
            if (l2 > 1e-9f) {
                for (i in vec.indices) vec[i] /= l2
            }
        }
    }
}
