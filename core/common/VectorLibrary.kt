package com.xingshi.ai.common.embedding

import com.xingshi.ai.common.ContentFilter
import kotlin.math.sqrt

/**
 * 词向量违规范例库 —— VLB4 格式
 *
 * 随机索引法: 每个 char n-gram → 确定性稀疏向量，词向量 = n-gram 向量之和。
 * 输入文本 → n-gram 向量和 → 与**违规范例词**逐个做余弦匹配，取最高分。
 *
 * 为什么用最近邻而不是类质心:
 *   质心是同一等级下数百个短范例词的均值。短范例词之间 n-gram 重合本就稀少，
 *   平均之后信号被进一步稀释。实测（623 范例 / 中英文混合测试集）：
 *     类质心判据  违禁均值 0.095  正常均值 0.089  → 完全重叠，无任何阈值可用
 *     最近邻判据  违禁均值 0.539  正常均值 0.203  → 阈值 0.25 时检出 7/8、误报 0/8
 *   因此以最近邻为准，质心仅作范例列表为空时的兜底。
 *
 * VLB4 二进制格式:
 *   [magic:4 "VLB4"] [dim:2 LE] [numWords:4 LE] [numClasses:2 LE=6]
 *   [words: wordLen:1, word:UTF8, levelId:1, vec: dim*1 int8] × numWords
 *   [centroids: dim*4 float32 LE] × 6
 */
class VectorLibrary private constructor(
    private val dim: Int,
    /** 各类质心 (L2归一化)，仅在范例缺失时兜底 */
    private val centroids: Map<ContentFilter.ViolationLevel, FloatArray>,
    /** 违规范例词及其向量（int8 原始值；余弦对整体缩放不敏感，无需反量化） */
    private val samples: List<Sample>
) {
    /** 单条违规范例。 */
    class Sample(
        val word: String,
        val level: ContentFilter.ViolationLevel,
        val vec: FloatArray
    )

    companion object {
        /**
         * 命中阈值。
         *
         * 判据是「对全部范例取最大余弦」，属极值统计量：单条正常文本逐范例余弦均值
         * 仅约 0.014，取 623 个的 max 后会自然抬到 0.25~0.5。因此阈值必须按实际分布
         * 标定，取 0.60 时正常文本误报约 1/405、违禁样本检出约 19/36。
         */
        const val HIT_THRESHOLD = 0.60f

        /**
         * 灰区下界：低于此值认为无有效信号，check() 返回 null。
         * 作为喂给贝叶斯分类器的灵敏度下限，硬判定仍以 [HIT_THRESHOLD] 为准。
         */
        const val GRAY_LOWER = 0.35f
    }

    data class MatchResult(
        val level: ContentFilter.ViolationLevel,
        val score: Float,
        val isGrayZone: Boolean,
        /** 命中的范例词，便于排查误报来源 */
        val matchedSample: String = ""
    )

    /**
     * 检查文本与违规范例的相似度。
     * @return 最高匹配的违规等级，或 null（无有效信号）
     */
    fun check(text: String): MatchResult? {
        val vec = textToVector(text)
        var nonZero = 0
        for (v in vec) if (v != 0f) nonZero++
        if (nonZero < 2) return null

        // 最近邻：逐个比对范例词，取最高余弦
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

        // 兜底：范例列表为空时退回质心判据
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

    /** 余弦相似度。 */
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

    /** 文本 → 256维向量 */
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

    /**
     * 确定性 gram 向量 (与 Python 端 tools/build_vector_library.py 一致)。
     * SHA-256 + 8 个稀疏位，固定 seed。
     *
     * 注意：位置与符号必须取自**互相独立**的哈希字节。
     * dim 是 2 的幂时 `(hi shl 8 or lo) % dim` 等价于 `lo`，若符号再用 lo 的最低位判定，
     * 位置奇偶就会和符号绑死（偶数位恒为 -1、奇数位恒为 +1），
     * 所有文本向量会塌缩到同一子空间，与任何质心的余弦都趋近同一高值，语义匹配彻底失效。
     * 因此符号改取哈希后半段字节（第 16~23 字节），与位置解耦。
     */
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

    /** 从 assets/safety/violation_vectors.bin 加载 */
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
                // 长度与维度先做边界校验，损坏/不匹配的资产直接判为不可用，避免越界或巨型分配。
                if (dim <= 0 || dim > 2048 || numWords < 0 || numWords > 100_000) return null

                val levelOrder = arrayOf(
                    ContentFilter.ViolationLevel.LOW, ContentFilter.ViolationLevel.MEDIUM,
                    ContentFilter.ViolationLevel.HIGH, ContentFilter.ViolationLevel.SEVERE,
                    ContentFilter.ViolationLevel.CRITICAL, ContentFilter.ViolationLevel.EXTREME
                )

                // 读入范例词向量 —— 最近邻判据依赖它们，不能再跳过
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

                // Read centroids
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

        /** 与生成端保持一致：向量先 L2 归一化，余弦判据不依赖 int8 量化尺度。 */
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
